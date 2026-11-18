package com.example.trafficwidget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Home-screen entry point. Refreshes are gated by screen + keyguard state. */
public class TrafficWidgetProvider extends AppWidgetProvider {

    /** Kept so an alarm installed by an older build can be recognised and cancelled. */
    static final String ACTION_REFRESH = "com.example.trafficwidget.action.REFRESH";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        WidgetUpdateScheduler.schedule(context);
        // 传入 appWidgetIds 让下面的刷新逻辑能判断哪些是 2x1，哪些是 2x2
        RefreshController.onWidgetChanged(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        WidgetUpdateScheduler.schedule(context);
        // 同理，传入发生尺寸变化的那个具体 ID
        RefreshController.onWidgetOptionsChanged(context, appWidgetId);
    }

    @Override
    public void onEnabled(Context context) {
        WidgetUpdateScheduler.schedule(context);
        RefreshController.onWidgetChanged(context); // null 代表刷新所有
    }

    @Override
    public void onDisabled(Context context) {
        // Another size may still be on the home screen; only stop the heartbeat when the
        // last widget of either provider is gone.
        if (WidgetUpdater.hasAnyWidgets(context)) {
            return;
        }
        WidgetUpdateScheduler.cancel(context);
        StatsCache.clear(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            // Compatibility with the alarm used by the previous version.
            WidgetUpdateScheduler.schedule(context);
            RefreshController.refreshIfAllowed(context, "收到旧版定时广播");
            return;
        }
        super.onReceive(context, intent);
    }
}