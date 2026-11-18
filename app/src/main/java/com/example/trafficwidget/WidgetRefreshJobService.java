package com.example.trafficwidget;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * 15-minute heartbeat. It does not query anything while the screen is off or the phone is
 * locked; in that case {@link RefreshController} returns immediately.
 */
public class WidgetRefreshJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        Thread worker = new Thread(() -> {
            try {
                RefreshController.refreshIfAllowedBlocking(this, "15分钟定时任务");
            } finally {
                jobFinished(params, false);
            }
        }, "traffic-widget-job");
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
