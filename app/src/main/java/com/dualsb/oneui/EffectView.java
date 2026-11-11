package com.dualsb.oneui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/** Hiệu ứng toàn màn hình: tuyết rơi, mưa, sao lấp lánh, bong bóng. Không nhận cảm ứng. */
final class EffectView extends View {
    static final int SNOW = 0, RAIN = 1, STAR = 2, BUBBLE = 3;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private int type, count = 60;
    private float speed = 1f;
    private int color = Color.WHITE;
    private float[] px, py, pv, pr, pp;
    private long last;

    EffectView(Context c) {
        super(c);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    void configure(int type, int count, float speed, int color) {
        this.type = type;
        this.count = Math.max(5, Math.min(400, count));
        this.speed = Math.max(0.2f, Math.min(4f, speed));
        this.color = color;
        px = new float[this.count]; py = new float[this.count]; pv = new float[this.count];
        pr = new float[this.count]; pp = new float[this.count];
        for (int i = 0; i < this.count; i++) reset(i, true);
        invalidate();
    }

    private void reset(int i, boolean anywhere) {
        px[i] = rnd.nextFloat();
        py[i] = anywhere ? rnd.nextFloat() : (type == BUBBLE ? 1.05f : -0.05f);
        pp[i] = rnd.nextFloat() * 6.28f;
        switch (type) {
            case RAIN: pv[i] = 0.9f + rnd.nextFloat() * 0.8f; pr[i] = 0.5f + rnd.nextFloat(); break;
            case STAR: pv[i] = 0.5f + rnd.nextFloat() * 1.5f; pr[i] = 0.6f + rnd.nextFloat() * 1.2f; break;
            case BUBBLE: pv[i] = 0.05f + rnd.nextFloat() * 0.12f; pr[i] = 0.5f + rnd.nextFloat(); break;
            default: pv[i] = 0.05f + rnd.nextFloat() * 0.1f; pr[i] = 0.4f + rnd.nextFloat() * 0.9f;
        }
    }

    @Override protected void onDraw(Canvas c) {
        if (px == null) return;
        long now = SystemClock.uptimeMillis();
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
        last = now;
        int w = getWidth(), h = getHeight();
        float d = getResources().getDisplayMetrics().density;
        paint.setColor(color);
        paint.setStyle(type == BUBBLE ? Paint.Style.STROKE : Paint.Style.FILL);
        for (int i = 0; i < count; i++) {
            pp[i] += dt * (type == STAR ? 2.5f * pv[i] : 1.2f);
            switch (type) {
                case RAIN:
                    py[i] += pv[i] * speed * dt * 1.6f;
                    px[i] -= 0.04f * speed * dt;
                    paint.setStrokeWidth(1.2f * d * pr[i]);
                    paint.setAlpha(150);
                    c.drawLine(px[i] * w, py[i] * h, px[i] * w - 6 * d, py[i] * h + 22 * d * pr[i], paint);
                    if (py[i] > 1.05f || px[i] < -0.05f) reset(i, false);
                    break;
                case STAR:
                    paint.setAlpha((int) (60 + 195 * (0.5f + 0.5f * Math.sin(pp[i]))));
                    c.drawCircle(px[i] * w, py[i] * h, 1.6f * d * pr[i], paint);
                    break;
                case BUBBLE:
                    py[i] -= pv[i] * speed * dt;
                    px[i] += Math.sin(pp[i]) * 0.0006f;
                    paint.setStrokeWidth(1.3f * d);
                    paint.setAlpha(140);
                    c.drawCircle(px[i] * w, py[i] * h, 9 * d * pr[i], paint);
                    if (py[i] < -0.08f) reset(i, false);
                    break;
                default:
                    py[i] += pv[i] * speed * dt;
                    px[i] += Math.sin(pp[i]) * 0.0004f;
                    paint.setAlpha(210);
                    c.drawCircle(px[i] * w, py[i] * h, 2.6f * d * pr[i], paint);
                    if (py[i] > 1.05f) reset(i, false);
            }
        }
        postInvalidateOnAnimation();
    }
}
