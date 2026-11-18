package com.example.trafficwidget;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decides whether a refresh is allowed, then updates only what changed.
 *
 * <p>Every refresh attempt writes exactly one line to {@link WidgetLogger}: the trigger
 * (screen-on, unlock, 15-minute job, manual…) plus the per-widget result and, on failure,
 * the reason. Skipped attempts (screen off / locked) are logged as well, because a missing
 * heartbeat is exactly what makes both widgets look stale.</p>
 */
final class RefreshController {

    private static final String TAG = "TrafficWidget";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "traffic-widget-refresh");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private static final AtomicBoolean ASYNC_RUNNING = new AtomicBoolean(false);
    /** A refresh was requested while one was already running; run once more afterward. */
    private static final AtomicBoolean ASYNC_PENDING = new AtomicBoolean(false);

    private RefreshController() {
    }

    /** True only when the display is interactive and the lock screen is gone. */
    static boolean isUnlockedAndInteractive(Context context) {
        PowerManager powerManager = context.getSystemService(PowerManager.class);
        KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
        return powerManager != null
                && powerManager.isInteractive()
                && keyguardManager != null
                && !keyguardManager.isKeyguardLocked();
    }

    /** Called from unlock / screen-on / periodic ticks. Never queries while locked or dark. */
    static void refreshIfAllowed(Context context, String trigger) {
        if (!isUnlockedAndInteractive(context)) {
            WidgetLogger.logSkip(context, trigger + "，跳过刷新：屏幕未点亮或已锁屏");
            return;
        }
        if (!ASYNC_RUNNING.compareAndSet(false, true)) {
            ASYNC_PENDING.set(true);
            return;
        }
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                doRefresh(app, trigger);
            } finally {
                ASYNC_RUNNING.set(false);
                if (ASYNC_PENDING.getAndSet(false)) {
                    refreshIfAllowed(app, "补跑排队中的刷新");
                }
            }
        });
    }

    /** Blocking variant for JobService, which must keep the process alive until work ends. */
    static void refreshIfAllowedBlocking(Context context, String trigger) {
        if (!isUnlockedAndInteractive(context)) {
            WidgetLogger.logSkip(context, trigger + "，跳过刷新：屏幕未点亮或已锁屏");
            return;
        }
        doRefresh(context.getApplicationContext(), trigger);
    }

    /** Widget added or enabled: query only if the screen is already available. */
    static void onWidgetChanged(Context context) {
        if (isUnlockedAndInteractive(context)) {
            refreshIfAllowed(context, "小部件添加或启用");
        } else {
            WidgetLogger.log(context, "小部件添加或启用，跳过刷新：屏幕未点亮或已锁屏");
        }
    }

    /** Resizing only redraws the cached bitmap; it never queries while screen is unavailable. */
    static void onWidgetOptionsChanged(Context context, int appWidgetId) {
        if (!isUnlockedAndInteractive(context)) {
            WidgetLogger.log(context, "小部件尺寸变化，跳过重绘：屏幕未点亮或已锁屏");
            return;
        }
        StatsCache.Entry cached = StatsCache.load(context);
        if (cached == null) {
            WidgetLogger.log(context, "小部件尺寸变化，跳过重绘：没有缓存数据");
            return;
        }
        String result = WidgetUpdater.updateSingleWidget(context, appWidgetId, cached.snapshot);
        WidgetLogger.log(context, "小部件尺寸变化，重绘：" + result);
    }

    /**
     * Rebuilds every widget from the cached numbers without querying. Used after boot or an
     * app update so the RemoteViews (and the click intent they carry) are refreshed even if
     * the phone happens to be locked at that moment.
     */
    static void redrawFromCache(Context context, String trigger) {
        StatsCache.Entry cached = StatsCache.load(context);
        if (cached == null) {
            WidgetLogger.log(context, trigger + "，跳过：没有缓存数据");
            return;
        }
        WidgetUpdater.UpdateSummary result = WidgetUpdater.updateAll(context, cached.snapshot);
        WidgetLogger.log(context, trigger + "，重绘：" + result.text);
    }

    private static synchronized void doRefresh(Context context, String trigger) {
        String todayKey = StatsRepository.dayKey();
        String monthKey = StatsRepository.monthKey();
        StatsCache.Entry cached = StatsCache.load(context);

        StatsRepository.Snapshot snapshot;
        int mode = TrafficSettings.getMode(context);
        boolean needsFullRefresh = cached == null
                || !todayKey.equals(cached.dayKey)
                || !monthKey.equals(cached.monthKey)
                || cached.mode != mode;

        if (needsFullRefresh) {
            Log.i(TAG, "Full refresh: date "
                    + (cached == null ? "none" : cached.dayKey) + " -> " + todayKey
                    + ", mode " + (cached == null ? "none" : cached.mode) + " -> " + mode);
            snapshot = StatsRepository.load(context);
            if (snapshot.hasUsageAccess && snapshot.error == null) {
                StatsCache.save(context, snapshot, todayKey, monthKey);
            }
        } else {
            snapshot = refreshTodayOnly(context, cached, todayKey, monthKey);
        }

        // The scope may have been switched while the query was in flight. The numbers in hand
        // are a mix of both scopes, so throw them away and let the pending run start over.
        if (TrafficSettings.getMode(context) != mode) {
            Log.i(TAG, "Traffic scope changed during refresh; retrying with the new scope");
            WidgetLogger.log(context, trigger + "，流量统计范围在刷新过程中变化，已重新排队");
            ASYNC_PENDING.set(true);
            return;
        }

        if (!isUnlockedAndInteractive(context)) {
            Log.i(TAG, "Screen left the foreground during refresh; skip redraw");
            WidgetLogger.log(context, trigger + "，放弃重绘：查询期间熄屏或锁屏");
            return;
        }

        WidgetUpdater.UpdateSummary update = WidgetUpdater.updateAll(context, snapshot);
        StringBuilder line = new StringBuilder(trigger).append("，");
        if (!snapshot.hasUsageAccess) {
            line.append("缺少使用情况访问权限，");
        } else if (snapshot.error != null) {
            line.append("数据读取失败（").append(snapshot.error).append("），");
        }
        line.append(needsFullRefresh ? "全量刷新" : "增量刷新").append(update.text);
        // 刷新成功时把本次推给小部件的今日流量值也记下来，方便与系统页面对照。
        if (snapshot.hasUsageAccess && snapshot.error == null && update.anySuccess()) {
            line.append("，数据为").append(StatsFormat.spaced(snapshot.todayBytes));
        }
        WidgetLogger.log(context, line.toString());
    }

    private static StatsRepository.Snapshot refreshTodayOnly(
            Context context, StatsCache.Entry cached, String todayKey, String monthKey) {
        try {
            long todayBytes = StatsRepository.queryToday(context);
            long previousToday = cached.snapshot.todayBytes;
            long delta = Math.max(0L, todayBytes - previousToday);

            long[] daily = cached.snapshot.dailyBytes.clone();
            daily[StatsRepository.CHART_DAYS - 1] = todayBytes;

            long monthlyBytes = cached.snapshot.monthlyBytes + delta;
            StatsRepository.Snapshot snapshot = new StatsRepository.Snapshot(
                    monthlyBytes, todayBytes, daily, true, null);
            StatsCache.save(context, snapshot, todayKey, monthKey);
            return snapshot;
        } catch (Exception e) {
            Log.w(TAG, "Today-only query failed", e);
            return new StatsRepository.Snapshot(
                    cached.snapshot.monthlyBytes,
                    cached.snapshot.todayBytes,
                    cached.snapshot.dailyBytes,
                    true,
                    "读取失败");
        }
    }
}
