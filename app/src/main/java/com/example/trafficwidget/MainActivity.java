package com.example.trafficwidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Setup screen: permission, traffic scope settings and shortcuts. */
public class MainActivity extends Activity {

    private static final String TAG = "TrafficWidget";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();

    private TextView permissionStatus;
    private TextView dataSummary;
    private TextView trafficModeValue;
    private View statusDot;
    private Button permissionButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        applyWindowInsets(findViewById(R.id.root));

        permissionStatus = findViewById(R.id.permission_status);
        dataSummary = findViewById(R.id.data_summary);
        trafficModeValue = findViewById(R.id.traffic_mode_value);
        statusDot = findViewById(R.id.status_dot);
        permissionButton = findViewById(R.id.btn_permission);

        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(getColor(R.color.page_bg));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        permissionButton.setOnClickListener(v -> openUsageAccess());
        findViewById(R.id.row_traffic_mode).setOnClickListener(v -> showTrafficModeDialog());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> refreshNow());
        findViewById(R.id.btn_view_logs).setOnClickListener(v -> showRefreshLogs());
        findViewById(R.id.btn_data_usage).setOnClickListener(v -> {
            if (!Intents.openDataUsage(this)) {
                Toast.makeText(this, "这台手机没有找到数据用量页面", Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionState();
        updateTrafficModeValue();
        WidgetUpdateScheduler.schedule(this);

        if (UsageAccess.isGranted(this)) {
            loadLiveSnapshot();
        } else {
            generation.incrementAndGet();
            dataSummary.setText(R.string.no_permission_summary);
        }
    }

    @Override
    protected void onDestroy() {
        generation.incrementAndGet();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void updatePermissionState() {
        boolean granted = UsageAccess.isGranted(this);
        permissionStatus.setText(granted
                ? R.string.permission_granted : R.string.permission_missing);
        int color = getColor(granted ? R.color.status_ok : R.color.status_warn);
        permissionStatus.setTextColor(color);
        statusDot.setBackgroundTintList(ColorStateList.valueOf(color));
        permissionButton.setText(granted
                ? R.string.btn_manage_permission : R.string.btn_grant);
    }

    /** Shows the current traffic scope on the right side of the settings row. */
    private void updateTrafficModeValue() {
        int mode = TrafficSettings.getMode(this);
        int text;
        if (mode == TrafficSettings.MODE_WLAN) {
            text = R.string.traffic_mode_wlan;
        } else if (mode == TrafficSettings.MODE_ALL) {
            text = R.string.traffic_mode_all;
        } else {
            text = R.string.traffic_mode_mobile;
        }
        trafficModeValue.setText(text);
    }

    private void loadLiveSnapshot() {
        int request = generation.incrementAndGet();
        Context app = getApplicationContext();
        executor.execute(() -> {
            StatsRepository.Snapshot snapshot = StatsRepository.load(app);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || request != generation.get()) {
                    return;
                }
                // 仅更新文字摘要，不再渲染预览图
                if (!snapshot.hasUsageAccess) {
                    dataSummary.setText(R.string.no_permission_summary);
                } else if (snapshot.error != null) {
                    dataSummary.setText(snapshot.error + "，请稍后刷新");
                } else {
                    dataSummary.setText("本月 " + StatsFormat.spaced(snapshot.monthlyBytes)
                            + " · 今日 " + StatsFormat.spaced(snapshot.todayBytes));
                }
            });
        });
    }

    private void refreshNow() {
        if (!UsageAccess.isGranted(this)) {
            openUsageAccess();
            return;
        }
        WidgetUpdateScheduler.schedule(this);
        RefreshController.refreshIfAllowed(this, "手动刷新");
        loadLiveSnapshot();
        Toast.makeText(this, R.string.refreshing, Toast.LENGTH_SHORT).show();
    }

    /** Shows the refresh log (trigger time, per-widget result, failure reason). */
    private void showRefreshLogs() {
        TextView content = new TextView(this);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, padding / 2);
        content.setTextSize(12f);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextIsSelectable(true);
        content.setText(WidgetLogger.readLogs(this));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        new AlertDialog.Builder(this)
                .setTitle(R.string.refresh_logs_title)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .setNegativeButton(R.string.clear_logs, (dialog, which) -> {
                    WidgetLogger.clear(this);
                    Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void openUsageAccess() {
        try {
            startActivity(Intents.usageAccessSettings(this));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    /** Lets the user pick which transports the widget and both screens should count. */
    private void showTrafficModeDialog() {
        final int current = TrafficSettings.getMode(this);
        final String[] labels = {
                getString(R.string.traffic_mode_mobile),
                getString(R.string.traffic_mode_wlan),
                getString(R.string.traffic_mode_all)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.traffic_mode_settings)
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == current) {
                        return;
                    }
                    applyTrafficMode(which, labels[which]);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyTrafficMode(int mode, String label) {
        TrafficSettings.setMode(this, mode);
        updateTrafficModeValue();
        StatsCache.clear(this);
        PeriodUsageRepository.clear(this);
        MonthlyUsageRepository.clearCache(this);
        WidgetUpdateScheduler.schedule(this);
        RefreshController.refreshIfAllowed(this, "切换流量统计范围后刷新");
        loadLiveSnapshot();
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
    }

    private void applyWindowInsets(View root) {
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();

        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int left = 0;
            int top = 0;
            int right = 0;
            int bottom = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = windowInsets.getSystemWindowInsetLeft();
                top = windowInsets.getSystemWindowInsetTop();
                right = windowInsets.getSystemWindowInsetRight();
                bottom = windowInsets.getSystemWindowInsetBottom();
            }
            view.setPadding(baseLeft + left, baseTop + top,
                    baseRight + right, baseBottom + bottom);
            return windowInsets;
        });
        root.requestApplyInsets();
    }
}