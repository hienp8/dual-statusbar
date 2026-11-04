package com.dualsb.oneui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Một khối gồm: [sóng di động] [Wi-Fi] [vòng pin].
 * Vòng pin: % nằm giữa; xanh khi sạc (khe hở phía trên + tia sét), vàng khi tiết kiệm pin, đỏ khi yếu.
 * Wi-Fi: 3 cung sáng theo mức sóng; sóng di động: 4 thanh (hoặc 4 chấm) sáng theo mức sóng.
 */
final class BatteryRingView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint net = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final RectF rect = new RectF();
    private final Path bolt = new Path();

    private int level = 100;
    private boolean charging, saver, showPercent = true;
    private float stroke = 4f;
    private int colNormal = 0xFFFFFFFF, colChg = 0xFF4CAF50, colSaver = 0xFFFFC107, colLow = 0xFFE53935;
    private int lowLevel = 20;
    private boolean showWifi = true, showSig = true;
    private int sigStyle;
    private int sigLevel = 0;     // 0..4
    private int wifiLevel = -1;   // -1 = không kết nối (ẩn), 0..4

    BatteryRingView(Context c) {
        super(c);
        track.setStyle(Paint.Style.STROKE);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        fill.setStyle(Paint.Style.FILL);
        net.setStyle(Paint.Style.FILL);
        net.setStrokeCap(Paint.Cap.ROUND);
    }

    void configure(float strokePx, boolean showPercent, int normal, int chg, int saver, int low, int lowLevel,
                   boolean showWifi, boolean showSig, int sigStyle) {
        this.stroke = strokePx; this.showPercent = showPercent;
        this.colNormal = normal; this.colChg = chg; this.colSaver = saver; this.colLow = low;
        this.lowLevel = lowLevel;
        this.showWifi = showWifi; this.showSig = showSig; this.sigStyle = sigStyle;
        requestLayout();
        invalidate();
    }

    void setNormalColor(int c) { colNormal = c; invalidate(); }

    void setState(int level, boolean charging, boolean saver) {
        this.level = level; this.charging = charging; this.saver = saver;
        invalidate();
    }

    void setNet(int sig, int wifi) {
        boolean relayout = (wifi >= 0) != (wifiLevel >= 0);
        sigLevel = sig; wifiLevel = wifi;
        if (relayout) requestLayout();
        invalidate();
    }

    private boolean wifiShown() { return showWifi && wifiLevel >= 0; }
    private float sigW(float s) { return sigStyle == 1 ? 1.04f * s : 0.77f * s; }
    private float wifiW(float s) { return 0.85f * s; }
    private float gap(float s) { return 0.25f * s; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int h = MeasureSpec.getSize(hSpec);
        if (h <= 0) h = 48;
        float s = h;
        float w = s;
        if (showSig) w += sigW(s) + gap(s);
        if (wifiShown()) w += wifiW(s) + gap(s);
        setMeasuredDimension((int) Math.ceil(w), h);
    }

    private int ringColor() {
        if (charging) return colChg;
        if (saver) return colSaver;
        if (level <= lowLevel) return colLow;
        return colNormal;
    }

    @Override protected void onDraw(Canvas c) {
        float s = getHeight();
        if (s <= 0) return;
        float cy = s / 2f;
        float x = 0;

        if (showSig) {
            drawSignal(c, x, cy, s);
            x += sigW(s) + gap(s);
        }
        if (wifiShown()) {
            drawWifi(c, x, cy, s);
            x += wifiW(s) + gap(s);
        }
        drawRing(c, x + s / 2f, cy, s);
    }

    private void drawSignal(Canvas c, float x, float cy, float s) {
        net.setColor(colNormal);
        if (sigStyle == 1) {
            float d = 0.2f * s, g = 0.08f * s;
            for (int i = 0; i < 4; i++) {
                net.setAlpha(i < sigLevel ? 255 : 70);
                c.drawCircle(x + d / 2f + i * (d + g), cy, d / 2f, net);
            }
        } else {
            float bw = 0.14f * s, g = 0.07f * s, hs = 0.62f * s;
            for (int i = 0; i < 4; i++) {
                net.setAlpha(i < sigLevel ? 255 : 70);
                float hh = hs * (0.4f + 0.2f * i);
                float left = x + i * (bw + g);
                rect.set(left, cy + hs / 2f - hh, left + bw, cy + hs / 2f);
                c.drawRoundRect(rect, bw / 2f, bw / 2f, net);
            }
        }
    }

    private void drawWifi(Canvas c, float x, float cy, float s) {
        float ox = x + wifiW(s) / 2f, oy = cy + 0.3f * s;
        int lit = Math.round(wifiLevel * 3f / 4f);
        float sw = Math.max(1.5f, stroke * 0.9f);
        net.setStyle(Paint.Style.STROKE);
        net.setStrokeWidth(sw);
        net.setColor(colNormal);
        for (int k = 1; k <= 3; k++) {
            float r = k * 0.2f * s;
            oval.set(ox - r, oy - r, ox + r, oy + r);
            net.setAlpha(k <= lit ? 255 : 70);
            c.drawArc(oval, -135f, 90f, false, net);
        }
        net.setStyle(Paint.Style.FILL);
        net.setAlpha(255);
        c.drawCircle(ox, oy, 0.075f * s, net);
    }

    private void drawRing(Canvas c, float cx, float cy, float s) {
        float r = s / 2f - stroke / 2f - 0.5f;
        if (r <= 0) return;
        int col = ringColor();

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
