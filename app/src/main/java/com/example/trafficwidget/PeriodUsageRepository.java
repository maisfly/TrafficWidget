package com.example.trafficwidget;

import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Week / month aggregates for the 周 and 月 tabs of the monthly page.
 *
 * <p>Buckets are persisted in SharedPreferences and only the volatile parts are re-read:
 * the bucket that is still running (today's week / this month) is queried on every open,
 * a bucket is fully re-read exactly once after it ends (the cached copy was partial), and
 * older buckets are reused as-is. A cache older than {@link #MAX_CACHE_AGE_MS} is thrown
 * away and rebuilt from the system, which bounds any accumulated drift.</p>
 */
final class PeriodUsageRepository {

    private static final String TAG = "TrafficWidget";
    private static final String PREFS = "traffic_widget_periods";
    private static final long MAX_CACHE_AGE_MS = 45L * 24L * 60L * 60L * 1000L;

    static final int KIND_WEEK = 0;
    static final int KIND_MONTH = 1;

    static final int WEEK_BUCKETS = 10;
    static final int MONTH_BUCKETS = 6;

    static final class Bucket {
        final String key;
        final String label;
        final long bytes;

        Bucket(String key, String label, long bytes) {
            this.key = key;
            this.label = label;
            this.bytes = bytes;
        }
    }

    static final class PeriodUsage {
        final int kind;
        final List<Bucket> buckets;
        final long totalBytes;
        final long averageBytes;
        final Bucket maxBucket;
        final boolean hasUsageAccess;
        final String error;

        PeriodUsage(int kind, List<Bucket> buckets, long totalBytes, long averageBytes,
                    Bucket maxBucket, boolean hasUsageAccess, String error) {
            this.kind = kind;
            this.buckets = buckets;
            this.totalBytes = totalBytes;
            this.averageBytes = averageBytes;
            this.maxBucket = maxBucket;
            this.hasUsageAccess = hasUsageAccess;
            this.error = error;
        }
    }

    /** One bucket to query: [start, end) with a cache key and the label shown under the bar. */
    private static final class Range {
        final String key;
        final String label;
        final long start;
        final long end;

        Range(String key, String label, long start, long end) {
            this.key = key;
            this.label = label;
            this.start = start;
            this.end = end;
        }
    }

    private static final class Cache {
        static final Cache EMPTY = new Cache(false, 0L, Collections.emptyMap());

        final boolean usable;
        final long savedAt;
        final Map<String, Long> values;

        Cache(boolean usable, long savedAt, Map<String, Long> values) {
            this.usable = usable;
            this.savedAt = savedAt;
            this.values = values;
        }

        Long value(String key) {
            return values.get(key);
        }
    }

    private PeriodUsageRepository() {
    }

    static PeriodUsage loadWeeks(Context context) {
        return load(context, KIND_WEEK);
    }

    static PeriodUsage loadMonths(Context context) {
        return load(context, KIND_MONTH);
    }

    /** The traffic scope changed: every cached aggregate is invalid. */
    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static PeriodUsage load(Context context, int kind) {
        List<Range> ranges = kind == KIND_WEEK ? weekRanges(context) : monthRanges();
        if (!UsageAccess.isGranted(context)) {
            return failure(kind, ranges, false, "尚未授予“使用情况访问权限”");
        }
        NetworkStatsManager manager = context.getSystemService(NetworkStatsManager.class);
        if (manager == null) {
            return failure(kind, ranges, true, "系统不支持 NetworkStatsManager");
        }

        Cache cache = readCache(context, kind);
        long now = System.currentTimeMillis();
        long[] values = new long[ranges.size()];
        try {
            for (int i = 0; i < ranges.size(); i++) {
                Range range = ranges.get(i);
                Long cached = cache.value(range.key);
                // Bucket fully covered by the cache: reuse it without touching the system.
                if (cache.usable && cached != null && range.end <= cache.savedAt) {
                    values[i] = cached;
                    continue;
                }
                long end = Math.min(range.end, now);
                if (end <= range.start) {
                    values[i] = 0L;
                    continue;
                }
                values[i] = StatsRepository.queryRange(context, manager, range.start, end);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "No permission for period usage", e);
            return failure(kind, ranges, false, "尚未授予“使用情况访问权限”");
        } catch (Exception e) {
            Log.e(TAG, "Unable to read period usage", e);
            return failure(kind, ranges, true, "读取流量统计失败，点此处重试");
        }

        List<Bucket> buckets = new ArrayList<>(ranges.size());
        for (int i = 0; i < ranges.size(); i++) {
            buckets.add(new Bucket(ranges.get(i).key, ranges.get(i).label, values[i]));
        }
        saveCache(context, kind, buckets);
        return build(kind, buckets, true, null);
    }

    private static PeriodUsage failure(int kind, List<Range> ranges, boolean access, String error) {
        List<Bucket> buckets = new ArrayList<>(ranges.size());
        for (Range range : ranges) {
            buckets.add(new Bucket(range.key, range.label, 0L));
        }
        return new PeriodUsage(kind, buckets, 0L, 0L, null, access, error);
    }

    private static PeriodUsage build(int kind, List<Bucket> buckets,
                                     boolean access, String error) {
        long total = 0L;
        Bucket max = null;
        for (Bucket bucket : buckets) {
            total += bucket.bytes;
            if (max == null || bucket.bytes > max.bytes) {
                max = bucket;
            }
        }
        long average = buckets.isEmpty() ? 0L : total / buckets.size();
        return new PeriodUsage(kind, buckets, total, average, max, access, error);
    }

    /** Current month plus the five before it, oldest first. */
    private static List<Range> monthRanges() {
        long now = System.currentTimeMillis();
        Calendar cursor = Calendar.getInstance();
        cursor.setTimeInMillis(now);
        cursor.set(Calendar.DAY_OF_MONTH, 1);
        clearTime(cursor);
        cursor.add(Calendar.MONTH, -(MONTH_BUCKETS - 1));

        List<Range> ranges = new ArrayList<>(MONTH_BUCKETS);
        for (int i = 0; i < MONTH_BUCKETS; i++) {
            long start = cursor.getTimeInMillis();
            int year = cursor.get(Calendar.YEAR);
            int month = cursor.get(Calendar.MONTH) + 1;
            cursor.add(Calendar.MONTH, 1);
            long end = cursor.getTimeInMillis();
            ranges.add(new Range(
                    String.format(Locale.ROOT, "%04d-%02d", year, month),
                    month + "月",
                    start,
                    end));
        }
        return ranges;
    }

    /** Current week (Monday -> today) plus the nine full weeks before it, oldest first. */
    private static List<Range> weekRanges(Context context) {
        Calendar cursor = Calendar.getInstance();
        clearTime(cursor);
        // Monday is the first day: Sunday (1) -> 6 days since Monday, Monday (2) -> 0.
        int sinceMonday = (cursor.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        cursor.add(Calendar.DAY_OF_YEAR, -sinceMonday - 7 * (WEEK_BUCKETS - 1));

        List<Range> ranges = new ArrayList<>(WEEK_BUCKETS);
        for (int i = 0; i < WEEK_BUCKETS; i++) {
            long start = cursor.getTimeInMillis();
            int year = cursor.get(Calendar.YEAR);
            int month = cursor.get(Calendar.MONTH) + 1;
            int day = cursor.get(Calendar.DAY_OF_MONTH);
            cursor.add(Calendar.DAY_OF_YEAR, 7);
            long end = cursor.getTimeInMillis();
            boolean current = i == WEEK_BUCKETS - 1;
            ranges.add(new Range(
                    String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day),
                    current ? context.getString(R.string.this_week)
                            : String.format(Locale.CHINA, "%d/%d", month, day),
                    start,
                    end));
        }
        return ranges;
    }

    private static String prefix(int kind) {
        return kind == KIND_WEEK ? "week_" : "month_";
    }

    private static Cache readCache(Context context, int kind) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String prefix = prefix(kind);
        if (preferences.getInt(prefix + "mode", -1) != TrafficSettings.getMode(context)) {
            return Cache.EMPTY;
        }
        long savedAt = preferences.getLong(prefix + "saved_at", 0L);
        long now = System.currentTimeMillis();
        if (savedAt <= 0L || savedAt > now || now - savedAt > MAX_CACHE_AGE_MS) {
            return Cache.EMPTY;
        }

        int count = preferences.getInt(prefix + "count", 0);
        Map<String, Long> values = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String key = preferences.getString(prefix + "key_" + i, null);
            long value = preferences.getLong(prefix + "val_" + i, -1L);
            if (key != null && value >= 0L) {
                values.put(key, value);
            }
        }
        return new Cache(true, savedAt, values);
    }

    private static void saveCache(Context context, int kind, List<Bucket> buckets) {
        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        String prefix = prefix(kind);
        editor.putInt(prefix + "count", buckets.size())
                .putLong(prefix + "saved_at", System.currentTimeMillis())
                .putInt(prefix + "mode", TrafficSettings.getMode(context));
        for (int i = 0; i < buckets.size(); i++) {
            Bucket bucket = buckets.get(i);
            editor.putString(prefix + "key_" + i, bucket.key)
                    .putLong(prefix + "val_" + i, bucket.bytes);
        }
        editor.apply();
    }

    private static void clearTime(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }
}
