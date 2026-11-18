package com.example.trafficwidget;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Last successfully rendered stats, plus the day and traffic scope they belong to.
 *
 * <p>The date key is what lets a screen-on refresh notice that midnight has passed and
 * rebuild yesterday / the earlier five days instead of only updating today's number. The
 * traffic mode is stored as well so switching 仅数据流量 / 仅WLAN / 全部 always forces a
 * full re-query instead of adding new-scope deltas to old-scope totals.</p>
 */
final class StatsCache {

    private static final String PREFS = "traffic_widget_stats";
    private static final String KEY_MONTHLY = "monthly";
    private static final String KEY_TODAY = "today";
    private static final String KEY_DAY_KEY = "day_key";
    private static final String KEY_MONTH_KEY = "month_key";
    private static final String KEY_MODE = "traffic_mode";
    private static final String KEY_SAVED_AT = "saved_at";
    private static final String KEY_DAY_PREFIX = "day_";

    static final class Entry {
        final StatsRepository.Snapshot snapshot;
        final String dayKey;
        final String monthKey;
        final long savedAt;
        final int mode;

        Entry(StatsRepository.Snapshot snapshot, String dayKey, String monthKey,
              long savedAt, int mode) {
            this.snapshot = snapshot;
            this.dayKey = dayKey;
            this.monthKey = monthKey;
            this.savedAt = savedAt;
            this.mode = mode;
        }
    }

    private StatsCache() {
    }

    static void save(Context context, StatsRepository.Snapshot snapshot,
                     String dayKey, String monthKey) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit()
                .putLong(KEY_MONTHLY, snapshot.monthlyBytes)
                .putLong(KEY_TODAY, snapshot.todayBytes)
                .putString(KEY_DAY_KEY, dayKey)
                .putString(KEY_MONTH_KEY, monthKey)
                .putInt(KEY_MODE, TrafficSettings.getMode(context))
                .putLong(KEY_SAVED_AT, System.currentTimeMillis());
        for (int i = 0; i < StatsRepository.CHART_DAYS; i++) {
            editor.putLong(KEY_DAY_PREFIX + i, snapshot.dailyBytes[i]);
        }
        editor.apply();
    }

    static Entry load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String dayKey = preferences.getString(KEY_DAY_KEY, null);
        String monthKey = preferences.getString(KEY_MONTH_KEY, null);
        if (dayKey == null || monthKey == null || !preferences.contains(KEY_MONTHLY)) {
            return null;
        }

        long[] daily = new long[StatsRepository.CHART_DAYS];
        for (int i = 0; i < daily.length; i++) {
            long value = preferences.getLong(KEY_DAY_PREFIX + i, -1L);
            if (value < 0L) {
                return null;
            }
            daily[i] = value;
        }

        long monthly = preferences.getLong(KEY_MONTHLY, -1L);
        long today = preferences.getLong(KEY_TODAY, -1L);
        if (monthly < 0L || today < 0L) {
            return null;
        }

        StatsRepository.Snapshot snapshot = new StatsRepository.Snapshot(
                monthly, today, daily, true, null);
        return new Entry(snapshot, dayKey, monthKey,
                preferences.getLong(KEY_SAVED_AT, 0L),
                preferences.getInt(KEY_MODE, -1));
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
