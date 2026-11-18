package com.example.trafficwidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * Renders an already-loaded snapshot into every widget instance (2x2 bitmap card and 2x1
 * capsule). Each update returns a short human-readable result that the refresh log stores.
 */
final class WidgetUpdater {

    private static final String TAG = "TrafficWidget";
    private static final int MAX_BITMAP_EDGE = 600;
    /** Suffix of a successful per-widget result line. */
    private static final String SUCCESS_SUFFIX = " 成功";

    private WidgetUpdater() {
    }

    /** Result of pushing one snapshot to every widget instance. */
    static final class UpdateSummary {
        final String text;
        final int totalCount;
        final int successCount;

        UpdateSummary(String text, int totalCount, int successCount) {
            this.text = text;
            this.totalCount = totalCount;
            this.successCount = successCount;
        }

        boolean anySuccess() {
            return successCount > 0;
        }
    }

    /** True when at least one widget of either size is on the home screen. */
    static boolean hasAnyWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids2x2 = manager.getAppWidgetIds(
                new ComponentName(context, TrafficWidgetProvider.class));
        int[] ids2x1 = manager.getAppWidgetIds(
                new ComponentName(context, TrafficWidget2x1Provider.class));
        return (ids2x2 != null && ids2x2.length > 0)
                || (ids2x1 != null && ids2x1.length > 0);
    }

    /**
     * Updates every widget instance and returns a summary such as
     * {@code 2*2界面(ID: 28) 成功；2*1界面(ID: 35) 失败，原因：…}.
     */
    static UpdateSummary updateAll(Context context, StatsRepository.Snapshot snapshot) {
        Context app = context.getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(app);

        StringBuilder summary = new StringBuilder();
        int total = 0;
        int success = 0;
        int[] ids2x2 = manager.getAppWidgetIds(
                new ComponentName(app, TrafficWidgetProvider.class));
        int[] ids2x1 = manager.getAppWidgetIds(
                new ComponentName(app, TrafficWidget2x1Provider.class));

        if (ids2x2 != null) {
            for (int id : ids2x2) {
                String result = updateSingleWidget(app, id, snapshot);
                appendResult(summary, result);
                total++;
                if (result.endsWith(SUCCESS_SUFFIX)) {
                    success++;
                }
            }
        }
        if (ids2x1 != null) {
            for (int id : ids2x1) {
                String result = updateSingleWidget(app, id, snapshot);
                appendResult(summary, result);
                total++;
                if (result.endsWith(SUCCESS_SUFFIX)) {
                    success++;
                }
            }
        }
        String text = summary.length() == 0 ? "当前没有小部件" : summary.toString();
        return new UpdateSummary(text, total, success);
    }

    /** Updates one widget and returns its result line fragment. */
    static String updateSingleWidget(Context context, int widgetId,
                                     StatsRepository.Snapshot snapshot) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        String type = "2*2";
        try {
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(widgetId);
            boolean capsule = info != null && info.initialLayout == R.layout.widget_root_2x1;
            type = capsule ? "2*1" : "2*2";
            if (capsule) {
                build2x1Views(context, manager, widgetId, snapshot);
            } else {
                updateOne2x2(context, manager, widgetId, snapshot,
                        manager.getAppWidgetOptions(widgetId));
            }
            return type + "界面(ID:" + widgetId + ")" + SUCCESS_SUFFIX;
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to update widget " + widgetId, e);
            return type + "界面(ID:" + widgetId + ") 失败，原因：" + describe(e);
        }
    }

    private static void appendResult(StringBuilder summary, String result) {
        if (summary.length() > 0) {
            summary.append("；");
        }
        summary.append(result);
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        String text = e.getClass().getSimpleName() + (message == null ? "" : "：" + message);
        return text.length() > 160 ? text.substring(0, 160) + "…" : text;
    }

    // ==================== 2x1 胶囊小部件渲染逻辑 ====================
    private static void build2x1Views(Context context, AppWidgetManager manager, int widgetId,
                                      StatsRepository.Snapshot snapshot) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_root_2x1);

        String valueText = "0.0";
        String unitText = "MB";
        if (snapshot.hasUsageAccess && snapshot.error == null) {
            String fullText = StatsFormat.spaced(snapshot.todayBytes);
            int spaceIndex = fullText.lastIndexOf(' ');
            if (spaceIndex > 0) {
                valueText = fullText.substring(0, spaceIndex);
                unitText = fullText.substring(spaceIndex + 1);
            } else {
                valueText = fullText;
            }
        }

        views.setTextViewText(R.id.tv_today_value, valueText);
        views.setTextViewText(R.id.tv_today_unit, unitText);

        String description = snapshot.hasUsageAccess && snapshot.error == null
                ? context.getString(R.string.widget_accessibility,
                StatsFormat.spaced(snapshot.monthlyBytes),
                StatsFormat.spaced(snapshot.todayBytes))
                : context.getString(R.string.widget_accessibility_missing);
        views.setContentDescription(R.id.widget_bg_2x1, description);

        views.setOnClickPendingIntent(R.id.widget_bg_2x1, createClickIntent(context));

        manager.updateAppWidget(widgetId, views);
    }

    // ==================== 2x2 纯代码绘图渲染逻辑 ====================
    private static void updateOne2x2(Context context, AppWidgetManager manager, int widgetId,
                                     StatsRepository.Snapshot snapshot, Bundle options) {
        int[] size = bitmapSize(context, options);

        RemoteViews views = build2x2Views(context, snapshot, size[0], size[1]);
        try {
            manager.updateAppWidget(widgetId, views);
        } catch (RuntimeException firstFailure) {
            Log.w(TAG, "Full-size bitmap was rejected; retrying smaller", firstFailure);
            RemoteViews fallback = build2x2Views(context, snapshot, 360, 360);
            manager.updateAppWidget(widgetId, fallback);
        }
    }

    private static RemoteViews build2x2Views(Context context, StatsRepository.Snapshot snapshot,
                                             int widthPx, int heightPx) {
        Bitmap bitmap = WidgetRenderer.render(snapshot, widthPx, heightPx);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_root);
        views.setImageViewBitmap(R.id.widget_image, bitmap);

        String description = snapshot.hasUsageAccess && snapshot.error == null
                ? context.getString(R.string.widget_accessibility,
                StatsFormat.spaced(snapshot.monthlyBytes),
                StatsFormat.spaced(snapshot.todayBytes))
                : context.getString(R.string.widget_accessibility_missing);
        views.setContentDescription(R.id.widget_root, description);

        views.setOnClickPendingIntent(R.id.widget_root, createClickIntent(context));
        return views;
    }

    /** 点击事件创建逻辑，供两个小部件复用 */
    private static PendingIntent createClickIntent(Context context) {
        Intent click = new Intent(context, MonthlyUsageActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return PendingIntent.getActivity(
                context, 0, click,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int[] bitmapSize(Context context, Bundle options) {
        float density = context.getResources().getDisplayMetrics().density;
        int widthDp = options == null ? 0
                : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int heightDp = options == null ? 0
                : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        if (widthDp <= 0) {
            widthDp = 180;
        }
        if (heightDp <= 0) {
            heightDp = 180;
        }

        int width = Math.round(widthDp * density);
        int height = Math.round(heightDp * density);
        width = Math.max(300, Math.min(MAX_BITMAP_EDGE, width));
        height = Math.max(300, Math.min(MAX_BITMAP_EDGE, height));
        return new int[]{width, height};
    }
}
