package com.example.trafficwidget;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/** System-screen intents, with HyperOS/MIUI fallbacks. */
final class Intents {

    private Intents() {
    }

    static Intent usageAccessSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        if (intent.resolveActivity(context.getPackageManager()) == null) {
            intent.setData(null);
        }
        return intent;
    }

    /**
     * Opens the closest system "mobile data usage" screen.
     *
     * <p>On HyperOS the traffic-ranking activity can be launched directly with its own
     * action/component, avoiding a stop on the Network Assistant dashboard. Older MIUI and
     * AOSP builds fall back to the general data-usage screens.</p>
     */
    static boolean openDataUsage(Context context) {
        List<Intent> candidates = new ArrayList<>();
        candidates.add(new Intent("miui.intent.action.NETWORKASSISTANT_TRAFFIC_SORTED")
                .setPackage("com.miui.securitycenter"));
        candidates.add(new Intent("miui.intent.action.NETWORKASSISTANT_TRAFFIC_SORTED")
                .setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.networkassistant.ui.activity.TrafficSortedActivity")));
        candidates.add(new Intent(Settings.ACTION_DATA_USAGE_SETTINGS)
                .setComponent(new ComponentName("com.android.settings",
                        "com.android.settings.Settings$DataUsageSummaryActivity")));
        candidates.add(new Intent("miui.intent.action.NETWORKASSISTANT_ENTRANCE")
                .setPackage("com.miui.securitycenter"));
        candidates.add(new Intent(Settings.ACTION_DATA_USAGE_SETTINGS));
        candidates.add(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        candidates.add(new Intent(Settings.ACTION_SETTINGS));

        for (Intent intent : candidates) {
            if (intent.resolveActivity(context.getPackageManager()) == null) {
                continue;
            }
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Try the next candidate.
            }
        }
        return false;
    }
}
