package com.dualsb.oneui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Pin + Wi-Fi + sóng trong MỘT vòng tròn:
 *  - Cung vòng phía trên (270°) là mức pin, chạy theo chiều kim đồng hồ từ dưới-trái.
 *  - Trên cùng, giữa: số % (hoặc tia sét khi sạc). Cung vòng được chừa khe hở đúng chỗ đặt số.
 *  - Phía dưới: 5 chấm xếp theo đường cong biểu thị sóng di động.
 *  - Giữa vòng: biểu tượng Wi-Fi (khi đang dùng Wi-Fi) hoặc chữ 5G/4G/3G/2G.
 */
final class BatteryRingView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint net = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path bolt = new Path();

    private int level = 100;
    private boolean charging, saver;
    private boolean showPercent = true, showBolt = true;
    private float stroke = 4f;
    private int colNormal = 0xFFFFFFFF, colChg = 0xFF4CAF50, colSaver = 0xFFFFC107, colLow = 0xFFE53935;
    private int lowLevel = 20;
    private int pctHeight = 80;                       // 0..100: độ cao của số % so với tâm vòng
    private boolean showWifi = true, showNetType = true, showDots = true;
    private int sigLevel = -1;                         // -1 = mất sóng, 0..4
    private int wifiLevel = -1;                        // -1 = không dùng Wi-Fi, 0..4
    private String netType = "";

    BatteryRingView(Context c) {
        super(c);
        track.setStyle(Paint.Style.STROKE);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text2.setTextAlign(Paint.Align.CENTER);
        text2.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        fill.setStyle(Paint.Style.FILL);
        net.setStyle(Paint.Style.FILL);
        net.setStrokeCap(Paint.Cap.ROUND);
    }

    void configure(float strokePx, boolean showPercent, boolean showBolt, int pctHeight,
                   int normal, int chg, int saver, int low, int lowLevel,
                   boolean showWifi, boolean showNetType, boolean showDots) {
        this.stroke = strokePx; this.showPercent = showPercent; this.showBolt = showBolt;
        this.pctHeight = Math.max(0, Math.min(100, pctHeight));
        this.colNormal = normal; this.colChg = chg; this.colSaver = saver; this.colLow = low;
        this.lowLevel = lowLevel;
        this.showWifi = showWifi; this.showNetType = showNetType; this.showDots = showDots;
        invalidate();
    }

    void setNormalColor(int c) { colNormal = c; invalidate(); }

    void setState(int level, boolean charging, boolean saver) {
        this.level = level; this.charging = charging; this.saver = saver;
        invalidate();
    }

    void setNet(int sig, int wifi, String type) {
        sigLevel = sig; wifiLevel = wifi; netType = type == null ? "" : type;
        invalidate();
    }

    private int ringColor() {
        if (charging) return colChg;
        if (saver) return colSaver;
        if (level <= lowLevel) return colLow;
        return colNormal;
    }

    @Override protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        if (s <= 0) return;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = s / 2f - stroke / 2f - s * 0.06f;
        if (r <= 0) return;
        int col = ringColor();

        // ---- nhãn trên cùng: số % hoặc tia sét
        boolean bolted = charging && showBolt;
        boolean label = bolted || showPercent;
        String t = String.valueOf(level);
        float textSize = s * (level >= 100 ? 0.27f : 0.34f);
        text.setTextSize(textSize);
        float tw = bolted ? s * 0.22f : text.measureText(t);
        float th = bolted ? s * 0.30f : textSize * 0.75f;
        float ly = cy - r * (pctHeight / 100f) * 1.1f;       // tâm của nhãn

        float gap = 0f;
        if (label && ly - th / 2f <= cy - r + stroke && ly + th / 2f >= cy - r - stroke) {
            gap = (float) Math.toDegrees(2 * Math.asin(Math.min(1f, (tw / 2f + stroke) / r)));
            gap = Math.min(gap, 120f);
        }

        // ---- cung pin (270°, chừa đáy cho chấm sóng, chừa đỉnh cho nhãn)
        oval.set(cx - r, cy - r, cx + r, cy + r);
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        track.setColor(col);
        track.setAlpha(60);
        arc.setColor(col);
        // chấm sóng to hơn; cung pin chừa khoảng trống rộng hơn quanh các chấm để không chạm nhau
        float dr = Math.max(1.8f, stroke * 0.8f);
        float step = Math.max(21f, (float) Math.toDegrees(2.9f * dr / r));
        float clear = (float) Math.toDegrees((stroke / 2f + dr) / r) + 7f;
        float halfOpen = Math.max(65f, Math.min(105f, 2f * step + clear));
        float seg = Math.max(5f, (360f - 2f * halfOpen) / 2f - gap / 2f);
        float s1 = 90f + halfOpen, s2 = 270f + gap / 2f;
        c.drawArc(oval, s1, seg, false, track);
        c.drawArc(oval, s2, seg, false, track);
        float fillDeg = 2f * seg * Math.max(0, Math.min(100, level)) / 100f;
        if (fillDeg > 0.5f) c.drawArc(oval, s1, Math.min(fillDeg, seg), false, arc);
        if (fillDeg > seg) c.drawArc(oval, s2, fillDeg - seg, false, arc);

        if (label) {
            if (bolted) {
                float bs = s * 0.30f;
                float left = cx - bs / 2f, top = ly - bs / 2f;
                bolt.reset();
                bolt.moveTo(left + bs * 0.62f, top);
                bolt.lineTo(left + bs * 0.05f, top + bs * 0.58f);
                bolt.lineTo(left + bs * 0.42f, top + bs * 0.58f);
                bolt.lineTo(left + bs * 0.30f, top + bs);
                bolt.lineTo(left + bs * 0.95f, top + bs * 0.38f);
                bolt.lineTo(left + bs * 0.56f, top + bs * 0.38f);
                bolt.close();
                fill.setColor(colChg);
                c.drawPath(bolt, fill);
            } else {
                text.setColor(col);
                Paint.FontMetrics fm = text.getFontMetrics();
                c.drawText(t, cx, ly - (fm.ascent + fm.descent) / 2f, text);
            }
        }

        // ---- 5 chấm sóng di động theo đường cong phía dưới
        if (showDots) {
            int lit = sigLevel < 0 ? 0 : Math.min(5, sigLevel + 1);
            net.setStyle(Paint.Style.FILL);
            net.setColor(colNormal);
            for (int i = 0; i < 5; i++) {
                double a = Math.toRadians(90 + (i - 2) * step);
                net.setAlpha(i < lit ? 255 : 70);
                c.drawCircle((float) (cx + r * Math.cos(a)), (float) (cy + r * Math.sin(a)), dr, net);
            }
        }

        // ---- giữa vòng: Wi-Fi hoặc loại mạng
        if (showWifi && wifiLevel >= 0) {
            drawWifi(c, cx, cy, s);
        } else if (showNetType && !netType.isEmpty()) {
            text2.setColor(colNormal);
            text2.setTextSize(s * 0.27f);
            Paint.FontMetrics fm = text2.getFontMetrics();
            c.drawText(netType, cx, cy + s * 0.05f - (fm.ascent + fm.descent) / 2f, text2);
        }
    }

    private void drawWifi(Canvas c, float cx, float cy, float s) {
        float oy = cy + 0.17f * s;
        int lit = Math.round(wifiLevel * 3f / 4f);
        net.setStyle(Paint.Style.STROKE);
        net.setStrokeWidth(Math.max(1.2f, s * 0.06f));
        net.setColor(colNormal);
        for (int k = 1; k <= 3; k++) {
            float rr = k * 0.095f * s + 0.01f * s;
            oval.set(cx - rr, oy - rr, cx + rr, oy + rr);
            net.setAlpha(k <= lit ? 255 : 70);
            c.drawArc(oval, -135f, 90f, false, net);
        }
        net.setStyle(Paint.Style.FILL);
        net.setAlpha(255);
        c.drawCircle(cx, oy, 0.04f * s, net);
    }
}
