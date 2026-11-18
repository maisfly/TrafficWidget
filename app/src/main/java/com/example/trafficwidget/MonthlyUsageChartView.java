package com.example.trafficwidget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Month bar chart with one gray track per calendar day.
 *
 * <p>Tapping any track selects that day and paints a small white bubble above the blue bar.
 * The bubble contains the date and that day's mobile traffic.</p>
 */
public class MonthlyUsageChartView extends View {

    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * MB;
    private static final int TRACK_COLOR = 0xFFE8EAED;
    private static final int BAR_COLOR = 0xFF3A56D4;
    private static final int GRID_COLOR = 0xFFE3E6EA;
    private static final int TEXT_MUTED = 0xFF8A9098;
    private static final int TEXT_DARK = 0xFF1B1F24;
    private static final int[] LABEL_DAYS = {1, 10, 20, 30};
    /** Tallest bar in percent of the chart height; leaves room for the grid lines above it. */
    private static final double BAR_HEIGHT_RATIO = 0.67d;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleDatePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleValuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density;
    private final float scaledDensity;

    private int month = 1;
    private int days = 31;
    private long[] values = new long[31];
    private int selectedIndex = -1;

    private float chartLeft;
    private float chartRight;
    private float chartTop;
    private float chartBottom;
    private float slotWidth;
    private float barWidth;
    /** Value represented by the top edge of the chart; the tallest bar reaches 67% of it. */
    private long scaleMax = GB;
    /** Upper dashed line: 1.3x the tallest day, drawn below the top edge. */
    private long gridTop = GB;
    /** Lower dashed line: half of {@link #gridTop}, rounded to one significant digit. */
    private long gridMid = MB;

    public MonthlyUsageChartView(Context context) {
        this(context, null);
    }

    public MonthlyUsageChartView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MonthlyUsageChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        trackPaint.setColor(TRACK_COLOR);
        barPaint.setColor(BAR_COLOR);
        gridPaint.setColor(GRID_COLOR);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1f));
        gridPaint.setPathEffect(new DashPathEffect(new float[]{dp(3f), dp(4f)}, 0f));
        labelPaint.setColor(TEXT_MUTED);
        labelPaint.setTextSize(sp(11f));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        bubblePaint.setColor(Color.WHITE);
        bubbleStroke.setColor(0xFFDCE1E7);
        bubbleStroke.setStyle(Paint.Style.STROKE);
        bubbleStroke.setStrokeWidth(dp(1f));
        bubbleDatePaint.setColor(TEXT_MUTED);
        bubbleDatePaint.setTextSize(sp(12f));
        bubbleDatePaint.setTextAlign(Paint.Align.CENTER);
        bubbleValuePaint.setColor(TEXT_DARK);
        bubbleValuePaint.setTextSize(sp(16f));
        bubbleValuePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bubbleValuePaint.setTextAlign(Paint.Align.CENTER);
        setWillNotDraw(false);
    }

    /** Call before data arrives so the correct number of gray tracks is visible. */
    public void setMonth(int month, int daysInMonth) {
        month = Math.max(1, Math.min(12, month));
        days = Math.max(28, Math.min(31, daysInMonth));
        this.month = month;
        values = new long[days];
        selectedIndex = -1;
        invalidate();
    }

    public void setData(long[] dailyBytes) {
        if (dailyBytes == null) {
            values = new long[days];
        } else {
            values = new long[days];
            System.arraycopy(dailyBytes, 0, values, 0, Math.min(days, dailyBytes.length));
        }
        selectedIndex = -1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (days <= 0) {
            return;
        }

        float axisWidth = dp(42f);
        chartLeft = dp(2f);
        chartRight = getWidth() - axisWidth;
        chartTop = dp(2f);
        chartBottom = getHeight() - dp(36f);
        if (chartRight <= chartLeft || chartBottom <= chartTop) {
            return;
        }

        slotWidth = (chartRight - chartLeft) / days;
        barWidth = Math.min(dp(13f), slotWidth * 0.58f);

        long max = 0L;
        for (long value : values) {
            max = Math.max(max, value);
        }
        if (max <= 0L) {
            scaleMax = MB;
            gridTop = 0L;
            gridMid = 0L;
        } else {
            // Bars keep their own scale: the tallest day occupies 67% of the chart height.
            scaleMax = Math.max(MB, Math.round(max / BAR_HEIGHT_RATIO));
            // Grid lines are independent of that scale: 1.3x the tallest day, then half of it.
            gridTop = Math.max(MB, Math.round(max * 1.3d));
            gridMid = midGridValue(gridTop);
        }

        drawGrid(canvas);
        drawBars(canvas);
        // Axis labels are painted last so no bar or track can ever cover them.
        drawAxisLabels(canvas);
        drawDayLabels(canvas);
        if (selectedIndex >= 0 && selectedIndex < days) {
            drawBubble(canvas, selectedIndex);
        }
    }

    /** True when the line value fits between the bars' baseline and the chart top. */
    private boolean hasGridLine(long value) {
        return value > 0L && value < scaleMax;
    }

    private void drawGrid(Canvas canvas) {
        if (hasGridLine(gridTop)) {
            drawGridLine(canvas, gridTop);
        }
        if (hasGridLine(gridMid)) {
            drawGridLine(canvas, gridMid);
        }
    }

    /** Second line: half of the top line, rounded to one significant digit in display units. */
    private long midGridValue(long top) {
        long half = top / 2L;
        if (half <= 0L) {
            return 0L;
        }
        if (half >= GB) {
            return Math.round(oneSignificantDigit(half / (double) GB) * GB);
        }
        return Math.max(MB, Math.round(oneSignificantDigit(half / (double) MB) * MB));
    }

    private double oneSignificantDigit(double value) {
        if (value <= 0d) {
            return 0d;
        }
        double magnitude = Math.pow(10d, Math.floor(Math.log10(value)));
        return Math.round(value / magnitude) * magnitude;
    }

    /** Y-axis values, vertically centred on their dashed line. */
    private void drawAxisLabels(Canvas canvas) {
        Paint axisText = new Paint(Paint.ANTI_ALIAS_FLAG);
        axisText.setColor(TEXT_MUTED);
        axisText.setTextSize(sp(10f));
        axisText.setTextAlign(Paint.Align.LEFT);
        Paint.FontMetrics metrics = axisText.getFontMetrics();
        float offset = (metrics.ascent + metrics.descent) / 2f;

        if (hasGridLine(gridTop)) {
            canvas.drawText(formatAxis(gridTop), chartRight + dp(6f),
                    yFor(gridTop) - offset, axisText);
        }
        if (hasGridLine(gridMid)) {
            canvas.drawText(formatAxis(gridMid), chartRight + dp(6f),
                    yFor(gridMid) - offset, axisText);
        }
    }

    private void drawGridLine(Canvas canvas, long value) {
        float y = yFor(value);
        canvas.drawLine(chartLeft, y, chartRight, y, gridPaint);
    }

    private void drawBars(Canvas canvas) {
        float radius = Math.min(dp(5f), barWidth / 2f);
        for (int i = 0; i < days; i++) {
            float center = chartLeft + slotWidth * (i + 0.5f);
            RectF track = new RectF(center - barWidth / 2f, chartTop,
                    center + barWidth / 2f, chartBottom);
            canvas.drawRoundRect(track, radius, radius, trackPaint);

            long value = values[i];
            if (value <= 0L) {
                continue;
            }
            float top = yFor(value);
            if (top < chartTop) {
                top = chartTop;
            }
            RectF bar = new RectF(track.left, top, track.right, chartBottom);
            canvas.drawRoundRect(bar, radius, radius, barPaint);
        }
    }

    private void drawDayLabels(Canvas canvas) {
        float baseline = chartBottom + dp(20f);
        for (int day : LABEL_DAYS) {
            if (day > days) {
                continue;
            }
            float center = chartLeft + slotWidth * (day - 0.5f);
            canvas.drawText(day + "日", center, baseline, labelPaint);
        }
    }

    private void drawBubble(Canvas canvas, int index) {
        float center = chartLeft + slotWidth * (index + 0.5f);
        float barTop = values[index] > 0L ? yFor(values[index]) : chartBottom;
        String date = String.format(Locale.CHINA, "%d月%d日", month, index + 1);
        String value = StatsFormat.spaced(values[index]);

        float width = Math.max(bubbleDatePaint.measureText(date),
                bubbleValuePaint.measureText(value)) + dp(28f);
        float height = dp(59f);
        float left = clamp(center - width / 2f, dp(4f), getWidth() - width - dp(4f));
        float top = barTop - height - dp(11f);
        if (top < dp(3f)) {
            top = dp(3f);
        }
        RectF bubble = new RectF(left, top, left + width, top + height);

        canvas.drawRoundRect(bubble, dp(10f), dp(10f), bubblePaint);
        canvas.drawRoundRect(bubble, dp(10f), dp(10f), bubbleStroke);

        float pointerX = clamp(center, left + dp(14f), left + width - dp(14f));
        Path arrow = new Path();
        arrow.moveTo(pointerX - dp(6f), bubble.bottom - dp(1f));
        arrow.lineTo(pointerX, bubble.bottom + dp(7f));
        arrow.lineTo(pointerX + dp(6f), bubble.bottom - dp(1f));
        arrow.close();
        canvas.drawPath(arrow, bubblePaint);

        canvas.drawText(date, bubble.centerX(), bubble.top + dp(23f), bubbleDatePaint);
        canvas.drawText(value, bubble.centerX(), bubble.top + dp(46f), bubbleValuePaint);
    }

    private float yFor(long value) {
        float fraction = Math.min(1f, value / (float) scaleMax);
        return chartBottom - fraction * (chartBottom - chartTop);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float x = event.getX();
            float y = event.getY();
            if (x >= chartLeft && x <= chartRight
                    && y >= chartTop - dp(6f) && y <= chartBottom + dp(28f)) {
                int index = (int) ((x - chartLeft) / slotWidth);
                index = Math.max(0, Math.min(days - 1, index));
                selectedIndex = index;
                invalidate();
                performClick();
                return true;
            }
            selectedIndex = -1;
            invalidate();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private String formatAxis(long bytes) {
        if (bytes >= GB) {
            double gb = bytes / (double) GB;
            if (gb >= 10d || Math.abs(gb - Math.rint(gb)) < 0.01d) {
                return String.format(Locale.CHINA, "%.0f GB", gb);
            }
            return String.format(Locale.CHINA, "%.1f GB", gb);
        }
        double mb = bytes / (double) MB;
        return String.format(Locale.CHINA, "%.0f MB", mb);
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return value * scaledDensity;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
