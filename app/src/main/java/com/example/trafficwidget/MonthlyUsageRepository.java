package com.example.trafficwidget;

import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.time.YearMonth;
import java.util.Calendar;
import java.util.Locale;

/**
 * Loads the daily bars of the current month for the 日 tab.
 *
 * <p>Past days of the current month are cached: opening the tab queries today's bucket,
 * re-reads a day once after it ended (the cached copy was partial) and reuses the rest. If
 * the month or the traffic scope changes the cache is rebuilt from the system.</p>
 */
final class MonthlyUsageRepository {

    private static final String TAG = "TrafficWidget";
    private static final long MB = 1024L * 1024L;
    private static final String DAY_PREFS = "traffic_widget_month_days";
    private static final String KEY_MODE = "mode";
    private static final String KEY_MONTH = "month";
    private static final String KEY_SAVED_AT = "saved_at";
    private static final String KEY_DAY_PREFIX = "day_";

    static final class MonthUsage {
        final int year;
        final int month;
        final int daysInMonth;
        final long monthlyBytes;
        final long[] dailyBytes;
        final boolean hasUsageAccess;
        final String error;

        MonthUsage(int year, int month, int daysInMonth, long monthlyBytes,
                   long[] dailyBytes, boolean hasUsageAccess, String error) {
            this.year = year;
            this.month = month;
            this.daysInMonth = daysInMonth;
            this.monthlyBytes = monthlyBytes;
            this.dailyBytes = dailyBytes;
            this.hasUsageAccess = hasUsageAccess;
            this.error = error;
        }

        static MonthUsage noPermission(int year, int month, int days) {
            return new MonthUsage(year, month, days, 0L, new long[days], false,
                    "尚未授予“使用情况访问权限”");
        }
    }

    /** Cached day buckets for one month; usable only for the same month and scope. */
    private static final class DayCache {
        static final DayCache EMPTY = new DayCache(false, 0L, new long[0]);

        final boolean usable;
        final long savedAt;
        final long[] values;

        DayCache(boolean usable, long savedAt, long[] values) {
            this.usable = usable;
            this.savedAt = savedAt;
            this.values = values;
        }

        Long value(int dayIndex) {
            if (dayIndex < 0 || dayIndex >= values.length || values[dayIndex] < 0L) {
                return null;
            }
            return values[dayIndex];
        }
    }

    private MonthlyUsageRepository() {
    }

    /** The traffic scope changed: the cached daily bars no longer match. */
    static void clearCache(Context context) {
        context.getSharedPreferences(DAY_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    static MonthUsage load(Context context) {
        YearMonth nowMonth = YearMonth.now();
        int year = nowMonth.getYear();
        int month = nowMonth.getMonthValue();
        int days = nowMonth.lengthOfMonth();

        if (!UsageAccess.isGranted(context)) {
            return MonthUsage.noPermission(year, month, days);
        }
        NetworkStatsManager manager = context.getSystemService(NetworkStatsManager.class);
        if (manager == null) {
            return new MonthUsage(year, month, days, 0L, new long[days], true,
                    "系统不支持 NetworkStatsManager");
        }

        try {
            Calendar monthStart = Calendar.getInstance();
            monthStart.clear();
            monthStart.set(year, month - 1, 1, 0, 0, 0);

            long now = System.currentTimeMillis();
            String monthKey = String.format(Locale.ROOT, "%04d-%02d", year, month);
            DayCache cache = readDayCache(context, monthKey);
            long[] daily = new long[days];
            Calendar cursor = (Calendar) monthStart.clone();
            for (int i = 0; i < days; i++) {
                long start = cursor.getTimeInMillis();
                cursor.add(Calendar.DAY_OF_YEAR, 1);
                long end = Math.min(cursor.getTimeInMillis(), now);
                if (end <= start) {
                    continue;
                }
                Long cached = cache.value(i);
                // A day is reusable only once the cache was written after that day ended;
                // today's bucket therefore gets one fresh query per open.
                if (cache.usable && cached != null && end <= cache.savedAt) {
                    daily[i] = cached;
                } else {
                    daily[i] = StatsRepository.queryRange(context, manager, start, end);
                }
            }

            long monthly = StatsRepository.queryRange(
                    context, manager, monthStart.getTimeInMillis(), now);
            saveDayCache(context, monthKey, daily);
            return new MonthUsage(year, month, days, monthly, daily, true, null);
        } catch (SecurityException e) {
            Log.w(TAG, "No permission for month usage", e);
            return MonthUsage.noPermission(year, month, days);
        } catch (Exception e) {
            Log.e(TAG, "Unable to read month usage", e);
            return new MonthUsage(year, month, days, 0L, new long[days], true,
                    "读取本月流量失败");
        }
    }

    private static DayCache readDayCache(Context context, String monthKey) {
        SharedPreferences preferences =
                context.getSharedPreferences(DAY_PREFS, Context.MODE_PRIVATE);
        if (preferences.getInt(KEY_MODE, -1) != TrafficSettings.getMode(context)) {
            return DayCache.EMPTY;
        }
        if (!monthKey.equals(preferences.getString(KEY_MONTH, null))) {
            return DayCache.EMPTY;
        }
        long savedAt = preferences.getLong(KEY_SAVED_AT, 0L);
        if (savedAt <= 0L) {
            return DayCache.EMPTY;
        }
        long[] values = new long[31];
        for (int i = 0; i < values.length; i++) {
            values[i] = preferences.getLong(KEY_DAY_PREFIX + i, -1L);
        }
        return new DayCache(true, savedAt, values);
    }

    private static void saveDayCache(Context context, String monthKey, long[] daily) {
        SharedPreferences.Editor editor =
                context.getSharedPreferences(DAY_PREFS, Context.MODE_PRIVATE).edit();
        editor.putInt(KEY_MODE, TrafficSettings.getMode(context))
                .putString(KEY_MONTH, monthKey)
                .putLong(KEY_SAVED_AT, System.currentTimeMillis());
        for (int i = 0; i < daily.length; i++) {
            editor.putLong(KEY_DAY_PREFIX + i, daily[i]);
        }
        for (int i = daily.length; i < 31; i++) {
            editor.remove(KEY_DAY_PREFIX + i);
        }
        editor.apply();
    }

    static String formatMonthAmount(long bytes) {
        double mb = Math.max(0L, bytes) / (double) MB;
        if (mb >= 1024d) {
            return String.format(Locale.CHINA, "%.2f GB", mb / 1024d);
        }
        return String.format(Locale.CHINA, "%.1f MB", mb);
    }
}
