package com.example.trafficwidget;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted choice of which network transports the widget and month page use. */
final class TrafficSettings {

    static final int MODE_MOBILE = 0;
    static final int MODE_WLAN = 1;
    static final int MODE_ALL = 2;

    private static final String PREFS = "traffic_widget_settings";
    private static final String KEY_MODE = "traffic_mode";

    private TrafficSettings() {
    }

    static int getMode(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int mode = preferences.getInt(KEY_MODE, MODE_MOBILE);
        if (mode < MODE_MOBILE || mode > MODE_ALL) {
            return MODE_MOBILE;
        }
        return mode;
    }

    static void setMode(Context context, int mode) {
        if (mode < MODE_MOBILE || mode > MODE_ALL) {
            mode = MODE_MOBILE;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_MODE, mode).apply();
    }

    static boolean includesMobile(int mode) {
        return mode == MODE_MOBILE || mode == MODE_ALL;
    }

    static boolean includesWlan(int mode) {
        return mode == MODE_WLAN || mode == MODE_ALL;
    }
}
