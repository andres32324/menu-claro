package com.menuclaro;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.ExistingPeriodicWorkPolicy;
import java.util.concurrent.TimeUnit;

public class ServiceWatcher extends Worker {

    public ServiceWatcher(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!StreamService.isRunning) {
            try {
                Intent intent = new Intent(getApplicationContext(), StreamService.class);
                intent.setAction("START");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    getApplicationContext().startForegroundService(intent);
                } else {
                    getApplicationContext().startService(intent);
                }
            } catch (Exception ignored) {}
        }
        return Result.success();
    }

    // Llamar esto desde MainActivity y BootReceiver
    public static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                ServiceWatcher.class, 15, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "MenuClaroWatcher",
                ExistingPeriodicWorkPolicy.KEEP,
                request);
    }
}
