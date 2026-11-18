package com.example.trafficwidget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Registers a 15-minute JobScheduler heartbeat.
 *
 * <p>The heartbeat itself is allowed to run in the background, but
 * {@link RefreshController} exits immediately unless the screen is interactive and the
 * keyguard is gone. This keeps the data refresh tied to visible screen time while still
 * giving the widget a way to catch up shortly after an unlock if the system does not
 * deliver a manifest USER_PRESENT broadcast.</p>
 */
final class WidgetUpdateScheduler {

    private static final String TAG = "TrafficWidget";
    private static final long INTERVAL_MILLIS = 15L * 60L * 1000L;
    private static final long FLEX_MILLIS = 5L * 60L * 1000L;
    private static final int JOB_ID = 1001;

    private static final int LEGACY_ALARM_REQUEST_CODE = 42;

    private WidgetUpdateScheduler() {
    }

    static void schedule(Context context) {
        if (!hasWidgets(context)) {
            return;
        }
        cancelLegacyAlarm(context);

        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        if (jobScheduler == null) {
            return;
        }
        try {
            JobInfo job = new JobInfo.Builder(
                    JOB_ID, new ComponentName(context, WidgetRefreshJobService.class))
                    .setPeriodic(INTERVAL_MILLIS, FLEX_MILLIS)
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build();
            int result = jobScheduler.schedule(job);
            if (result != JobScheduler.RESULT_SUCCESS) {
                Log.w(TAG, "JobScheduler rejected widget heartbeat");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to schedule widget heartbeat", e);
        }
    }

    static void cancel(Context context) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        if (jobScheduler != null) {
            try {
                jobScheduler.cancel(JOB_ID);
            } catch (RuntimeException ignored) {
                // Nothing to cancel.
            }
        }
        cancelLegacyAlarm(context);
    }

    /** Removes the previous 20-minute one-shot alarm if this APK is replacing that version. */
    private static void cancelLegacyAlarm(Context context) {
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            return;
        }
        try {
            Intent intent = new Intent(context, TrafficWidgetProvider.class);
            intent.setAction(TrafficWidgetProvider.ACTION_REFRESH);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, LEGACY_ALARM_REQUEST_CODE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
        } catch (RuntimeException ignored) {
            // No legacy alarm is active.
        }
    }

    private static boolean hasWidgets(Context context) {
        return WidgetUpdater.hasAnyWidgets(context);
    }
}
