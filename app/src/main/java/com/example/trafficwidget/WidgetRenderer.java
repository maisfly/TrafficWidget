package com.example.trafficwidget;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * Paints the complete widget card into one bitmap.
 *
 * <p>All geometry below is expressed on a 1000 x 1000 design canvas and scaled to the
 * bitmap's actual size. Keeping the card as a single image means RemoteViews only needs
 * one ImageView, while gradients, blur and the smooth curve remain under our control.</p>
 */
final class WidgetRenderer {

    private static final float CARD_LEFT = 26f;
    private static final float CARD_TOP = 26f;
    private static final float CARD_RIGHT = 974f;
    private static final float CARD_BOTTOM = 974f;
    private static final float CARD_RADIUS = 172f;

    private static final float CHART_TOP = 380f;
    private static final float DATA_TOP = 430f;
    private static final float DATA_BOTTOM = 850f;
    private static final float FILL_BOTTOM = 870f;

    /** Edge, first line, midpoint, second line, midpoint, third line. */
    private static final float[] POINT_X = {CARD_LEFT, 104f, 219f, 335f, 450f, 565f};
    private static final float[] LINE_X = {104f, 335f, 565f};

    private static final int TEXT_COLOR = 0xFFF7FFFB;
    private static final Typeface DISPLAY =
            Typeface.create("sans-serif-black", Typeface.BOLD);
    private static final Typeface LABEL =
            Typeface.create("sans-serif-medium", Typeface.BOLD);

    private WidgetRenderer() {
    }

    static Bitmap sample(int widthPx, int heightPx) {
        long mb = 1024L * 1024L;
        long[] daily = {
                Math.round(42.0 * mb), Math.round(48.0 * mb), Math.round(16.0 * mb),
                Math.round(35.0 * mb), Math.round(25.0 * mb), Math.round(35.7 * mb)
        };
        return render(new StatsRepository.Snapshot(
                Math.round(243.9 * mb), Math.round(35.7 * mb), daily, true, null),
                widthPx, heightPx);
    }

    static Bitmap render(StatsRepository.Snapshot snapshot, int widthPx, int heightPx) {
        int width = Math.max(96, widthPx);
        int height = Math.max(96, heightPx);
        if (snapshot == null) {
            snapshot = StatsRepository.Snapshot.noPermission();
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float scale = Math.min(width, height) / 1000f;
        canvas.translate((width - 1000f * scale) / 2f, (height - 1000f * scale) / 2f);
        canvas.scale(scale, scale);

        drawCard(canvas);
        Path cardClip = new Path();
        cardClip.addRoundRect(new RectF(CARD_LEFT, CARD_TOP, CARD_RIGHT, CARD_BOTTOM),
                CARD_RADIUS, CARD_RADIUS, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(cardClip);

        drawBackdrop(canvas);
        drawChart(canvas, snapshot);
        drawText(canvas, snapshot);

        canvas.restore();
        drawBorder(canvas);
        return bitmap;
    }

    private static void drawCard(Canvas canvas) {
        RectF card = new RectF(CARD_LEFT, CARD_TOP, CARD_RIGHT, CARD_BOTTOM);

        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x33000000);
        shadow.setShadowLayer(26f, 0f, 14f, 0x77000000);
        canvas.drawRoundRect(card, CARD_RADIUS, CARD_RADIUS, shadow);

        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setShader(new LinearGradient(
                CARD_LEFT, CARD_TOP, CARD_RIGHT, CARD_BOTTOM,
                new int[]{0xFFC4C7C7, 0xFFA7AAAA, 0xFF777B7C, 0xFF6A6E6F},
                new float[]{0f, 0.38f, 0.76f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(card, CARD_RADIUS, CARD_RADIUS, background);
    }

    private static void drawBackdrop(Canvas canvas) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        paint.setShader(new RadialGradient(250f, 70f, 720f,
                new int[]{0x70FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 1000, 1000, paint);

        paint.setShader(new LinearGradient(540f, 560f, 1000f, 1000f,
                new int[]{0x00000000, 0x30000000}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 1000, 1000, paint);
    }

    private static void drawChart(Canvas canvas, StatsRepository.Snapshot snapshot) {
        long[] daily = snapshot.dailyBytes;
        if (daily == null || daily.length != StatsRepository.CHART_DAYS) {
            daily = new long[StatsRepository.CHART_DAYS];
        }

        long max = 1L;
        for (long value : daily) {
            max = Math.max(max, value);
        }
        float[] pointY = new float[StatsRepository.CHART_DAYS];
        for (int i = 0; i < pointY.length; i++) {
            float ratio = Math.max(0f, Math.min(1f, daily[i] / (float) max));
            pointY[i] = DATA_BOTTOM - ratio * (DATA_BOTTOM - DATA_TOP);
        }

        Path line = smoothPath(POINT_X, pointY);
        Path fill = new Path(line);
        fill.lineTo(POINT_X[POINT_X.length - 1], FILL_BOTTOM);
        fill.lineTo(POINT_X[0], FILL_BOTTOM);
        fill.close();

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setShader(new LinearGradient(0f, DATA_TOP - 20f, 0f, FILL_BOTTOM,
                new int[]{0xE63DE2A0, 0x802FCB8C, 0x05247E5D},
                new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(fill, fillPaint);

        Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
        guide.setStyle(Paint.Style.STROKE);
        guide.setStrokeWidth(1.25f);
        guide.setColor(0x5CFFFFFF);
        for (float x : LINE_X) {
            canvas.drawLine(x, CHART_TOP, x, FILL_BOTTOM, guide);
        }

        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setStrokeJoin(Paint.Join.ROUND);
        glow.setMaskFilter(new BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL));
        glow.setStrokeWidth(24f);
        glow.setColor(0x9138EFA8);
        canvas.drawPath(line, glow);

        glow.setMaskFilter(new BlurMaskFilter(9f, BlurMaskFilter.Blur.NORMAL));
        glow.setStrokeWidth(11f);
        glow.setColor(0xB36CFFC4);
        canvas.drawPath(line, glow);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(4.6f);
        stroke.setColor(0xFFEAFFF7);
        canvas.drawPath(line, stroke);
    }

    private static Path smoothPath(float[] x, float[] y) {
        Path path = new Path();
        path.moveTo(x[0], y[0]);
        for (int i = 0; i < x.length - 1; i++) {
            int i0 = Math.max(0, i - 1);
            int i3 = Math.min(x.length - 1, i + 2);

            float c1x = clamp(x[i] + (x[i + 1] - x[i0]) / 6f, x[i], x[i + 1]);
            float c2x = clamp(x[i + 1] - (x[i3] - x[i]) / 6f, x[i], x[i + 1]);
            float c1y = clamp(y[i] + (y[i + 1] - y[i0]) / 6f, CHART_TOP - 20f, FILL_BOTTOM);
            float c2y = clamp(y[i + 1] - (y[i3] - y[i]) / 6f,
                    CHART_TOP - 20f, FILL_BOTTOM);

            path.cubicTo(c1x, c1y, c2x, c2y, x[i + 1], y[i + 1]);
        }
        return path;
    }

    private static void drawText(Canvas canvas, StatsRepository.Snapshot snapshot) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(TEXT_COLOR);
        paint.setSubpixelText(true);

        boolean showValues = snapshot.hasUsageAccess && snapshot.error == null;

        paint.setTypeface(LABEL);
        paint.setTextSize(52f);
        canvas.drawText("本月已用流量", 105f, 177f, paint);

        String monthNumber = showValues
                ? StatsFormat.number(snapshot.monthlyBytes) : "—";
        paint.setTypeface(DISPLAY);
        paint.setTextSize(158f);
        float numberWidth = paint.measureText(monthNumber);
        float maxNumberWidth = 548f;
        if (numberWidth > maxNumberWidth) {
            paint.setTextSize(158f * maxNumberWidth / numberWidth);
            numberWidth = paint.measureText(monthNumber);
        }
        canvas.drawText(monthNumber, 105f, 338f, paint);

        if (showValues) {
            paint.setTypeface(LABEL);
            paint.setTextSize(56f);
            canvas.drawText(StatsFormat.unit(snapshot.monthlyBytes),
                    105f + numberWidth + 22f, 330f, paint);
        }

        String todayText = showValues
                ? StatsFormat.compact(snapshot.todayBytes) : "—";
        paint.setTypeface(DISPLAY);
        paint.setTextSize(60f);
        float todayWidth = paint.measureText(todayText);
        if (todayWidth > 330f) {
            paint.setTextSize(60f * 330f / todayWidth);
        }
        canvas.drawText(todayText, 620f, 612f, paint);

        paint.setTypeface(LABEL);
        paint.setTextSize(52f);
        canvas.drawText("今日已用流量", 620f, 698f, paint);

        if (!snapshot.hasUsageAccess) {
            paint.setTypeface(LABEL);
            paint.setTextSize(29f);
            paint.setColor(0xD9E7FFF3);
            canvas.drawText("请先授予用量权限", 620f, 766f, paint);
        } else if (snapshot.error != null) {
            paint.setTypeface(LABEL);
            paint.setTextSize(27f);
            paint.setColor(0xD9E7FFF3);
            canvas.drawText("读取失败 · 请稍后刷新", 620f, 766f, paint);
        }

        drawIcon(canvas);
    }

    private static void drawIcon(Canvas canvas) {
        float cx = 852f;
        float cy = 158f;
        float radius = 64f;

        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setColor(0x8045F2AE);
        halo.setMaskFilter(new BlurMaskFilter(26f, BlurMaskFilter.Blur.NORMAL));
        canvas.drawCircle(cx, cy, radius + 10f, halo);

        Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        circle.setShader(new RadialGradient(cx - 22f, cy - 28f, radius * 1.6f,
                new int[]{0xFF8BFFD0, 0xFF4BF0AC, 0xFF2ED996}, null,
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, circle);

        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.4f);
        ring.setColor(0x66FFFFFF);
        canvas.drawCircle(cx, cy, radius - 1f, ring);

        Path mark = new Path();
        mark.moveTo(816f, 186f);
        mark.lineTo(834f, 132f);
        mark.lineTo(854f, 160f);
        mark.lineTo(874f, 132f);
        mark.lineTo(894f, 186f);

        Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        markPaint.setStyle(Paint.Style.STROKE);
        markPaint.setStrokeWidth(13f);
        markPaint.setStrokeCap(Paint.Cap.ROUND);
        markPaint.setStrokeJoin(Paint.Join.ROUND);
        markPaint.setColor(0xFF0A4B38);
        canvas.drawPath(mark, markPaint);
    }

    private static void drawBorder(Canvas canvas) {
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(1.5f);
        border.setColor(0x3DFFFFFF);
        canvas.drawRoundRect(new RectF(CARD_LEFT, CARD_TOP, CARD_RIGHT, CARD_BOTTOM),
                CARD_RADIUS, CARD_RADIUS, border);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
