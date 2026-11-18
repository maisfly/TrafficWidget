package com.example.trafficwidget;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import java.time.YearMonth;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Full-screen traffic view opened by tapping the widget.
 *
 * <p>Three tabs share one card: 日 (this month, day by day), 周 (this week plus the nine
 * before it) and 月 (this month plus the five before it). A tab queries its data once when
 * it is opened; historical buckets come from {@link PeriodUsageRepository}'s cache.</p>
 */
public class MonthlyUsageActivity extends Activity {

    private static final String TAG = "TrafficWidget";

    private static final int TAB_DAY = 0;
    private static final int TAB_WEEK = 1;
    private static final int TAB_MONTH = 2;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();

    private MonthlyUsageChartView chart;
    private View periodBlock;
    private PeriodBarChartView periodChart;
    private TextView periodAverage;
    private TextView periodSummary;
    private TextView monthLabel;
    private TextView monthAmount;
    private TextView monthStatus;
    private TextView tabDay;
    private TextView tabWeek;
    private TextView tabMonth;
    private View appDetailsRow;
    private TextView appDetailsTitle;
    private View cardContent;

    private int tab = TAB_DAY;
    private boolean loading;
    private boolean loaded;
    /** True while the slide-out / slide-in animation of a tab switch is running. */
    private boolean animating;
    /** Traffic mode used by the data currently on screen; -1 until the first load. */
    private int loadedMode = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_monthly_usage);
        applyWindowInsets(findViewById(R.id.root));

        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(getColor(R.color.page_bg));

        chart = findViewById(R.id.month_chart);
        periodBlock = findViewById(R.id.period_block);
        periodChart = findViewById(R.id.period_chart);
        periodAverage = findViewById(R.id.period_average);
        periodSummary = findViewById(R.id.period_summary);
        monthLabel = findViewById(R.id.month_label);
        monthAmount = findViewById(R.id.month_amount);
        monthStatus = findViewById(R.id.month_status);
        tabDay = findViewById(R.id.tab_day);
        tabWeek = findViewById(R.id.tab_week);
        tabMonth = findViewById(R.id.tab_month);
        appDetailsRow = findViewById(R.id.app_details_row);
        appDetailsTitle = findViewById(R.id.app_details_title);
        cardContent = findViewById(R.id.card_content);

        YearMonth now = YearMonth.now();
        chart.setMonth(now.getMonthValue(), now.lengthOfMonth());
        monthLabel.setText(getString(R.string.month_used_label, now.getMonthValue()));
        monthAmount.setText("—");

        findViewById(R.id.btn_settings).setOnClickListener(v -> openSettings());
        appDetailsRow.setOnClickListener(v ->
                startActivity(new Intent(this, DataUsageProxyActivity.class)));
        tabDay.setOnClickListener(v -> selectTab(TAB_DAY));
        tabWeek.setOnClickListener(v -> selectTab(TAB_WEEK));
        tabMonth.setOnClickListener(v -> selectTab(TAB_MONTH));
        // Swipe left/right on the card to move between 日 / 周 / 月 as well.
        SwipeTabLayout card = findViewById(R.id.month_card);
        card.setOnSwipeListener(direction ->
                selectTab(Math.max(TAB_DAY, Math.min(TAB_MONTH, tab + direction))));
        monthStatus.setOnClickListener(v -> {
            if (!UsageAccess.isGranted(this)) {
                try {
                    startActivity(Intents.usageAccessSettings(this));
                } catch (Exception e) {
                    Log.w(TAG, "Unable to open usage access settings", e);
                }
            }
        });

        updateTabUi();
        loadCurrentTab();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The settings screen can change the traffic scope while this page is paused.
        if (!loading && (!loaded || TrafficSettings.getMode(this) != loadedMode)) {
            loadCurrentTab();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resetCardAnimation();
    }

    @Override
    protected void onDestroy() {
        generation.incrementAndGet();
        executor.shutdownNow();
        super.onDestroy();
    }

    /** The gear opens the setup screen, which now owns the traffic-scope setting. */
    private void openSettings() {
        // Same task: Back from the settings screen returns to this page.
        startActivity(new Intent(this, MainActivity.class));
    }

    private void selectTab(int next) {
        if (next == tab || animating) {
            return;
        }
        animateToTab(next, next > tab ? 1 : -1);
    }

    /**
     * Slides the current content out to one side, swaps the tab (which shows the skeleton of
     * the new tab) and slides the new content in from the other side.
     */
    private void animateToTab(int next, int direction) {
        animating = true;
        float width = cardContent.getWidth();
        if (width <= 0f) {
            width = getResources().getDisplayMetrics().widthPixels * 0.8f;
        }
        final float distance = width;
        cardContent.animate()
                .translationX(-direction * distance)
                .alpha(0f)
                .setDuration(150L)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    switchTab(next);
                    cardContent.setTranslationX(direction * distance);
                    cardContent.setAlpha(0f);
                    cardContent.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(220L)
                            .setInterpolator(new DecelerateInterpolator())
                            .withEndAction(() -> animating = false)
                            .start();
                })
                .start();
    }

    private void switchTab(int next) {
        tab = next;
        // Drop whatever query is still running for the previous tab.
        generation.incrementAndGet();
        loading = false;
        loaded = false;
        updateTabUi();
        loadCurrentTab();
    }

    /** Cancels a running slide when the screen goes away and restores the content position. */
    private void resetCardAnimation() {
        if (!animating) {
            return;
        }
        cardContent.animate().cancel();
        cardContent.setTranslationX(0f);
        cardContent.setAlpha(1f);
        animating = false;
    }

    private void updateTabUi() {
        boolean day = tab == TAB_DAY;
        chart.setVisibility(day ? View.VISIBLE : View.GONE);
        periodBlock.setVisibility(day ? View.GONE : View.VISIBLE);
        appDetailsTitle.setText(day ? R.string.today_app_details : R.string.traffic_ranking);
        periodSummary.setVisibility(View.GONE);
        monthAmount.setText("—");
        if (day) {
            YearMonth month = YearMonth.now();
            // Empty bars are the skeleton shown while the cached month data loads.
            chart.setMonth(month.getMonthValue(), month.lengthOfMonth());
            monthLabel.setText(getString(R.string.month_used_label, month.getMonthValue()));
        } else {
            monthLabel.setText(tab == TAB_WEEK
                    ? R.string.weeks_total_label : R.string.months_total_label);
            periodChart.setData(new String[0], new long[0], 0L);
            periodAverage.setText("");
        }

        TextView[] tabs = {tabDay, tabWeek, tabMonth};
        for (int i = 0; i < tabs.length; i++) {
            boolean selected = i == tab;
            tabs[i].setBackgroundResource(selected ? R.drawable.bg_tab_selected : 0);
            tabs[i].setTextColor(getColor(selected ? R.color.ink : R.color.ink_muted));
            tabs[i].setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void loadCurrentTab() {
        if (tab == TAB_DAY) {
            loadData();
        } else {
            loadPeriod(tab);
        }
    }

    private void loadData() {
        if (loading) {
            return;
        }
        if (!UsageAccess.isGranted(this)) {
            showPermissionNeeded();
            return;
        }

        loading = true;
        loaded = false;
        loadedMode = TrafficSettings.getMode(this);
        int request = generation.incrementAndGet();
        // No "loading" line: the empty chart plus "—" is the skeleton, and keeping this row
        // hidden keeps the card height identical on every tab and while data arrives.
        monthStatus.setClickable(false);
        monthStatus.setVisibility(View.GONE);

        executor.execute(() -> {
            MonthlyUsageRepository.MonthUsage usage = MonthlyUsageRepository.load(this);
            runOnUiThread(() -> {
                if (request != generation.get() || isFinishing() || isDestroyed()) {
                    return;
                }
                loading = false;
                render(usage);
            });
        });
    }

    private void loadPeriod(int kind) {
        if (loading) {
            return;
        }
        if (!UsageAccess.isGranted(this)) {
            showPermissionNeeded();
            return;
        }

        loading = true;
        loaded = false;
        loadedMode = TrafficSettings.getMode(this);
        int request = generation.incrementAndGet();
        monthStatus.setClickable(false);
        monthStatus.setVisibility(View.GONE);

        Context app = getApplicationContext();
        executor.execute(() -> {
            PeriodUsageRepository.PeriodUsage usage = kind == TAB_WEEK
                    ? PeriodUsageRepository.loadWeeks(app)
                    : PeriodUsageRepository.loadMonths(app);
            runOnUiThread(() -> {
                if (request != generation.get() || isFinishing() || isDestroyed()) {
                    return;
                }
                loading = false;
                renderPeriod(usage);
            });
        });
    }

    private void render(MonthlyUsageRepository.MonthUsage usage) {
        chart.setMonth(usage.month, usage.daysInMonth);
        chart.setData(usage.dailyBytes);
        monthLabel.setText(getString(R.string.month_used_label, usage.month));
        monthAmount.setText(MonthlyUsageRepository.formatMonthAmount(usage.monthlyBytes));

        if (!usage.hasUsageAccess) {
            showPermissionNeeded();
            return;
        }
        if (usage.error != null) {
            loaded = false;
            monthStatus.setClickable(true);
            monthStatus.setText(usage.error);
            monthStatus.setVisibility(View.VISIBLE);
            monthStatus.setOnClickListener(v -> loadCurrentTab());
            return;
        }

        loaded = true;
        monthStatus.setVisibility(View.GONE);
    }

    private void renderPeriod(PeriodUsageRepository.PeriodUsage usage) {
        if (!usage.hasUsageAccess) {
            showPermissionNeeded();
            return;
        }
        if (usage.error != null) {
            loaded = false;
            monthStatus.setClickable(true);
            monthStatus.setText(usage.error);
            monthStatus.setVisibility(View.VISIBLE);
            monthStatus.setOnClickListener(v -> loadCurrentTab());
            periodSummary.setVisibility(View.GONE);
            return;
        }

        int count = usage.buckets.size();
        String[] labels = new String[count];
        long[] values = new long[count];
        for (int i = 0; i < count; i++) {
            PeriodUsageRepository.Bucket bucket = usage.buckets.get(i);
            labels[i] = bucket.label;
            values[i] = bucket.bytes;
        }
        periodChart.setData(labels, values, usage.averageBytes);
        monthAmount.setText(MonthlyUsageRepository.formatMonthAmount(usage.totalBytes));

        boolean week = usage.kind == PeriodUsageRepository.KIND_WEEK;
        periodAverage.setText(getString(
                week ? R.string.period_average_week : R.string.period_average_month,
                MonthlyUsageRepository.formatMonthAmount(usage.averageBytes)));
        PeriodUsageRepository.Bucket max = usage.maxBucket;
        if (max != null && max.bytes > 0L) {
            periodSummary.setVisibility(View.VISIBLE);
            periodSummary.setText(getString(
                    week ? R.string.period_max_week : R.string.period_max_month,
                    max.label, MonthlyUsageRepository.formatMonthAmount(max.bytes)));
        } else {
            periodSummary.setVisibility(View.GONE);
        }

        loaded = true;
        monthStatus.setVisibility(View.GONE);
    }

    private void showPermissionNeeded() {
        loaded = false;
        monthStatus.setClickable(true);
        monthStatus.setText("请授予“使用情况访问权限”后查看流量统计。");
        monthStatus.setVisibility(View.VISIBLE);
        monthStatus.setOnClickListener(v -> {
            try {
                startActivity(Intents.usageAccessSettings(this));
            } catch (Exception e) {
                Log.w(TAG, "Unable to open usage access settings", e);
            }
        });
    }

    private void applyWindowInsets(View root) {
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();

        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int left;
            int top;
            int right;
            int bottom;
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
