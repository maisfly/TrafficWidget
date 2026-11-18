package com.example.trafficwidget;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Process;

/** Checks the special "usage access" app-op that NetworkStatsManager requires. */
final class UsageAccess {

    private UsageAccess() {
    }

    static boolean isGranted(Context context) {
        try {
            AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
            if (appOps == null) {
                return false;
            }
            int mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }
}
