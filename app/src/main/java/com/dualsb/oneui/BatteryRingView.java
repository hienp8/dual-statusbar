package com.dualsb.oneui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Pin dạng vòng: % nằm giữa vòng; xanh khi sạc (có tia sét trong khe hở phía trên), vàng khi tiết kiệm pin, đỏ khi yếu. */
final class BatteryRingView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path bolt = new Path();

    private int level = 100;
    private boolean charging, saver, showPercent = true;
    private float stroke = 4f;
    private int colNormal = 0xFFFFFFFF, colChg = 0xFF4CAF50, colSaver = 0xFFFFC107, colLow = 0xFFE53935;
    private int lowLevel = 20;

    BatteryRingView(Context c) {
        super(c);
        track.setStyle(Paint.Style.STROKE);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        fill.setStyle(Paint.Style.FILL);
    }

    void configure(float strokePx, boolean showPercent, int normal, int chg, int saver, int low, int lowLevel) {
        this.stroke = strokePx; this.showPercent = showPercent;
        this.colNormal = normal; this.colChg = chg; this.colSaver = saver; this.colLow = low;
        this.lowLevel = lowLevel;
        invalidate();
    }

    void setState(int level, boolean charging, boolean saver) {
        this.level = level; this.charging = charging; this.saver = saver;
        invalidate();
    }

    private int color() {
        if (charging) return colChg;
        if (saver) return colSaver;
        if (level <= lowLevel) return colLow;
        return colNormal;
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float s = Math.min(w, h);
        if (s <= 0) return;
        float cx = w / 2f, cy = h / 2f;
        float r = s / 2f - stroke / 2f - 0.5f;
        if (r <= 0) return;
        int col = color();

        oval.set(cx - r, cy - r, cx + r, cy + r);
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        track.setColor(col);
        track.setAlpha(60);
        arc.setColor(col);

        float gap = charging ? 24f : 0f;               // khe hở phía trên khi sạc
        float start = -90f + gap;
        float span = 360f - 2f * gap;
        c.drawArc(oval, start, span, false, track);
        float sweep = span * Math.max(0, Math.min(100, level)) / 100f;
        if (sweep > 0.5f) c.drawArc(oval, start, sweep, false, arc);

        if (showPercent) {
            String t = String.valueOf(level);
            text.setColor(col);
            text.setTextSize(s * (t.length() >= 3 ? 0.30f : 0.40f));
            Paint.FontMetrics fm = text.getFontMetrics();
            c.drawText(t, cx, cy - (fm.ascent + fm.descent) / 2f, text);
        }

        if (charging) {                                  // tia sét nằm trong khe hở phía trên
            float bs = s * 0.24f;
            float left = cx - bs * 0.5f, top = cy - r - bs * 0.7f;
            bolt.reset();
            bolt.moveTo(left + bs * 0.58f, top);
            bolt.lineTo(left, top + bs * 0.82f);
            bolt.lineTo(left + bs * 0.44f, top + bs * 0.82f);
            bolt.lineTo(left + bs * 0.32f, top + bs * 1.4f);
            bolt.lineTo(left + bs, top + bs * 0.5f);
            bolt.lineTo(left + bs * 0.56f, top + bs * 0.5f);
            bolt.close();
            fill.setColor(colChg);
            c.drawPath(bolt, fill);
        }
    }
}
