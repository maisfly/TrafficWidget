package com.example.trafficwidget;

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.util.Log;

import java.util.Calendar;
import java.util.Locale;

/**
 * Reads mobile-data usage from the system's NetworkStats database.
 *
 * <p>The six chart values are ordered oldest -> today. Each value is the mobile traffic
 * (RX + TX) of all apps on the device during that local calendar day.</p>
 */
final class StatsRepository {

    private static final String TAG = "TrafficWidget";
    static final int CHART_DAYS = 6;

    static final class Snapshot {
        final long monthlyBytes;
        final long todayBytes;
        final long[] dailyBytes;
        final boolean hasUsageAccess;
        final String error;

        Snapshot(long monthlyBytes, long todayBytes, long[] dailyBytes,
                 boolean hasUsageAccess, String error) {
            this.monthlyBytes = monthlyBytes;
            this.todayBytes = todayBytes;
            this.dailyBytes = dailyBytes;
            this.hasUsageAccess = hasUsageAccess;
            this.error = error;
        }

        static Snapshot noPermission() {
            return new Snapshot(0, 0, new long[CHART_DAYS], false, "尚未授予“使用情况访问权限”");
        }

        static Snapshot failure(String message) {
            return new Snapshot(0, 0, new long[CHART_DAYS], true, message);
        }
    }

    private StatsRepository() {
    }

    static Snapshot load(Context context) {
        if (!UsageAccess.isGranted(context)) {
            return Snapshot.noPermission();
        }

        NetworkStatsManager manager = context.getSystemService(NetworkStatsManager.class);
        if (manager == null) {
            return Snapshot.failure("系统不支持 NetworkStatsManager");
        }

        try {
            Calendar today = Calendar.getInstance();
            clearTime(today);

            // Seven boundaries produce six-day intervals, ending at "now".
            // The first boundary is five days ago, so interval 5 starts today 00:00.
            long[] starts = new long[CHART_DAYS + 1];
            Calendar cursor = (Calendar) today.clone();
            cursor.add(Calendar.DAY_OF_YEAR, -(CHART_DAYS - 1));
            for (int i = 0; i < starts.length; i++) {
                starts[i] = cursor.getTimeInMillis();
                cursor.add(Calendar.DAY_OF_YEAR, 1);
            }

            long now = System.currentTimeMillis();
            long[] daily = new long[CHART_DAYS];
            for (int i = 0; i < CHART_DAYS; i++) {
                long end = Math.min(starts[i + 1], now);
                if (end > starts[i]) {
                    daily[i] = queryRange(context, manager, starts[i], end);
                }
            }

            Calendar month = Calendar.getInstance();
            month.set(Calendar.DAY_OF_MONTH, 1);
            clearTime(month);
            long monthly = queryRange(context, manager, month.getTimeInMillis(), now);

            return new Snapshot(monthly, daily[CHART_DAYS - 1], daily, true, null);
        } catch (SecurityException e) {
            Log.w(TAG, "No permission to query network stats", e);
            return Snapshot.noPermission();
        } catch (Exception e) {
            Log.e(TAG, "Unable to read network stats", e);
            return Snapshot.failure("读取系统流量数据失败");
        }
    }

    /** Reads only today's interval; used for 15-minute screen-on refreshes. */
    static long queryToday(Context context) throws Exception {
        if (!UsageAccess.isGranted(context)) {
            throw new SecurityException("Usage access is not granted");
        }
        NetworkStatsManager manager = context.getSystemService(NetworkStatsManager.class);
        if (manager == null) {
            throw new IllegalStateException("NetworkStatsManager unavailable");
        }
        Calendar today = Calendar.getInstance();
        clearTime(today);
        long now = System.currentTimeMillis();
        return queryRange(context, manager, today.getTimeInMillis(), now);
    }

    /**
     * Resolves the user's traffic mode and sums the corresponding transports.
     * Mobile queries are metered-only; WLAN follows the system's Wi-Fi template.
     */
    static long queryRange(Context context, NetworkStatsManager manager, long start, long end)
            throws Exception {
        int mode = TrafficSettings.getMode(context);
        long total = 0L;
        if (TrafficSettings.includesMobile(mode)) {
            total += queryNetwork(manager, ConnectivityManager.TYPE_MOBILE, start, end);
        }
        if (TrafficSettings.includesWlan(mode)) {
            total += queryNetwork(manager, ConnectivityManager.TYPE_WIFI, start, end);
        }
        return total;
    }

    private static long queryNetwork(NetworkStatsManager manager, int networkType,
                                     long start, long end) throws Exception {
        NetworkStats.Bucket bucket = manager.querySummaryForDevice(
                networkType, null, start, end);
        if (bucket == null) {
            return 0L;
        }
        return nonNegative(bucket.getRxBytes()) + nonNegative(bucket.getTxBytes());
    }

    static String dayKey() {
        Calendar calendar = Calendar.getInstance();
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    static String monthKey() {
        Calendar calendar = Calendar.getInstance();
        return String.format(Locale.ROOT, "%04d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1);
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }

    private static void clearTime(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }
}
