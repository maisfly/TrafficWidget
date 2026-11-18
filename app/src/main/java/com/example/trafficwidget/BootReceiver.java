package com.example.trafficwidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Re-registers the 15-minute heartbeat after boot or an app update. Actual data is not
 * queried here when the phone is still locked; the unlock receiver / next heartbeat will do
 * that once the screen is available.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            WidgetUpdateScheduler.schedule(context);
            RefreshController.refreshIfAllowed(context, "开机或应用更新");
            // Rebuild RemoteViews (and the click intent) from the cache right away; this does
            // not query anything and also works while the phone is still locked.
            RefreshController.redrawFromCache(context, "开机或应用更新后重建小部件");
        }
    }
}
