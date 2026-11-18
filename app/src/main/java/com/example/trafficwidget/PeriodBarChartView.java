package com.example.trafficwidget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * Plain bar chart used by the 周 / 月 tabs: one bar per bucket, the value above each bar,
 * a dashed average line and a label under each bar. The tallest bar uses 72% of the plot
 * height so the value labels and the average line always fit inside the view.
 */
public class PeriodBarChartView extends View {

    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * MB;
    private static final double BAR_HEIGHT_RATIO = 0.72d;

    private static final int BAR_COLOR = 0xFF3A56D4;
    private static final int BASELINE_COLOR = 0xFFE3E6EA;
    private static final int AVERAGE_COLOR = 0xFF9AA6C0;
    private static final int TEXT_DARK = 0xFF1B1F24;
    private static final int TEXT_MUTED = 0xFF8A9098;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baselinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint averagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density;
    private final float scaledDensity;

    private String[] labels = new String[0];
    private long[] values = new long[0];
    private long averageBytes;

    public PeriodBarChartView(Context context) {
        this(context, null);
    }

    public PeriodBarChartView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PeriodBarChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;

        barPaint.setColor(BAR_COLOR);
        baselinePaint.setColor(BASELINE_COLOR);
        baselinePaint.setStrokeWidth(dp(1f));
        averagePaint.setColor(AVERAGE_COLOR);
        averagePaint.setStyle(Paint.Style.STROKE);
        averagePaint.setStrokeWidth(dp(1f));
        averagePaint.setPathEffect(new DashPathEffect(new float[]{dp(4f), dp(3f)}, 0f));
        valuePaint.setColor(TEXT_DARK);
        valuePaint.setTextSize(sp(9f));
        valuePaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(TEXT_MUTED);
        labelPaint.setTextSize(sp(10f));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        setWillNotDraw(false);
    }

    public void setData(String[] labels, long[] values, long averageBytes) {
        this.labels = labels == null ? new String[0] : labels;
        this.values = values == null ? new long[0] : values;
        this.averageBytes = averageBytes;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int count = Math.min(labels.length, values.length);
        if (count <= 0) {
            return;
        }

        float left = dp(6f);
        float right = getWidth() - dp(6f);
        float top = dp(26f);
        float bottom = getHeight() - dp(26f);
        if (right <= left || bottom <= top) {
            return;
        }
        float height = bottom - top;

        long max = 0L;
        for (long value : values) {
            max = Math.max(max, value);
        }
        max = Math.max(max, averageBytes);
        double scale = max <= 0L ? MB : max / BAR_HEIGHT_RATIO;

        canvas.drawLine(left, bottom, right, bottom, baselinePaint);
        if (averageBytes > 0L) {
            float y = (float) (bottom - height * (averageBytes / scale));
            canvas.drawLine(left, y, right, y, averagePaint);
        }

        float slot = (right - left) / count;
        float barWidth = Math.min(dp(20f), slot * 0.46f);
        float radius = Math.min(dp(4f), barWidth / 2f);
        for (int i = 0; i < count; i++) {
            float center = left + slot * (i + 0.5f);
            long value = values[i];
            float barTop = (float) (bottom - height * (Math.max(0L, value) / scale));
            if (value > 0L) {
                RectF bar = new RectF(center - barWidth / 2f, barTop,
                        center + barWidth / 2f, bottom);
                canvas.drawRoundRect(bar, radius, radius, barPaint);
                canvas.drawText(shortValue(value), center,
                        Math.max(top, barTop - dp(4f)), valuePaint);
            }
            canvas.drawText(labels[i], center, bottom + dp(16f), labelPaint);
        }
    }

    /** Short bar label: {@code 1.2G}, {@code 92G}, {@code 860M}. */
    private String shortValue(long bytes) {
        if (bytes >= GB) {
            double gb = bytes / (double) GB;
            return gb >= 10d
                    ? String.format(Locale.CHINA, "%.0fG", gb)
                    : String.format(Locale.CHINA, "%.1fG", gb);
        }
        if (bytes >= MB) {
            return String.format(Locale.CHINA, "%.0fM", bytes / (double) MB);
        }
        return "0";
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return value * scaledDensity;
    }
}
