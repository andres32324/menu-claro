package com.menuclaro;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_CODE = 100;
    private GestureDetector gestureDetector;
    private LinearLayout layoutSecret;
    private TextView tvIp;
    private TextView tvCode;
    private View btnToggle;
    private TextView tvBtnStatus;
    private boolean isStreaming = false;
    private Handler hideHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        layoutSecret = findViewById(R.id.layoutSecret);
        tvIp         = findViewById(R.id.tvIp);
        tvCode       = findViewById(R.id.tvCode);
        btnToggle    = findViewById(R.id.btnToggle);
        tvBtnStatus  = findViewById(R.id.tvBtnStatus);

        // Ocultar controles secretos al inicio
        layoutSecret.setVisibility(View.INVISIBLE);

        // Detector de doble toque en el texto "Servicios"
        TextView tvServicios = findViewById(R.id.tvServicios);
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                toggleSecretPanel();
                return true;
            }
        });
        tvServicios.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));

        // Botón ON/OFF
        btnToggle.setOnClickListener(v -> {
            if (isStreaming) stopStream();
            else startStream();
        });

        // Actualizar IP y código
        updateInfo();

        // Solicitar ignorar optimización de batería
        requestBatteryOptimization();

        // Pedir permisos
        checkPermissions();
    }

    private void toggleSecretPanel() {
        if (layoutSecret.getVisibility() == View.INVISIBLE) {
            layoutSecret.setVisibility(View.VISIBLE);
            updateInfo();
            // Auto ocultar después de 10 segundos si no se usa
            hideHandler.removeCallbacksAndMessages(null);
            hideHandler.postDelayed(() -> {
                if (!isStreaming) layoutSecret.setVisibility(View.INVISIBLE);
            }, 10000);
        } else {
            layoutSecret.setVisibility(View.INVISIBLE);
            hideHandler.removeCallbacksAndMessages(null);
        }
    }

    private void updateInfo() {
        String ip = NetworkUtils.getLocalIP(this);
        String code = DeviceCode.getCode(this);
        tvIp.setText(ip);
        tvCode.setText("# " + code);
    }

    private void startStream() {
        if (!hasPermissions()) {
            checkPermissions();
            return;
        }
        isStreaming = true;
        btnToggle.setBackgroundResource(R.drawable.btn_on);
        tvBtnStatus.setText("●");
        tvBtnStatus.setTextColor(0xFF4CAF50);
        Intent intent = new Intent(this, StreamService.class);
        intent.setAction("START");
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopStream() {
        isStreaming = false;
        btnToggle.setBackgroundResource(R.drawable.btn_off);
        tvBtnStatus.setText("●");
        tvBtnStatus.setTextColor(0xFFE53935);
        Intent intent = new Intent(this, StreamService.class);
        intent.setAction("STOP");
        startService(intent);
    }

    private void requestBatteryOptimization() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void checkPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA
        }, PERMISSION_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        boolean ok = true;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
        if (!ok) Toast.makeText(this, "Permisos necesarios para funcionar", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Verificar si el servicio sigue corriendo
        isStreaming = StreamService.isRunning;
        if (isStreaming) {
            btnToggle.setBackgroundResource(R.drawable.btn_on);
            tvBtnStatus.setTextColor(0xFF4CAF50);
        } else {
            btnToggle.setBackgroundResource(R.drawable.btn_off);
            tvBtnStatus.setTextColor(0xFFE53935);
        }
    }
}
