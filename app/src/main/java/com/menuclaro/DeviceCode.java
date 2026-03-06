package com.menuclaro;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

public class DeviceCode {

    private static final String PREFS = "menuclaro_prefs";
    private static final String KEY_CODE = "device_code";

    public static String getCode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String code = prefs.getString(KEY_CODE, null);
        if (code == null) {
            // Generar código único de 6 caracteres alfanumérico
            code = UUID.randomUUID().toString().toUpperCase()
                    .replaceAll("[^A-Z0-9]", "")
                    .substring(0, 6);
            prefs.edit().putString(KEY_CODE, code).apply();
        }
        return code;
    }
}
