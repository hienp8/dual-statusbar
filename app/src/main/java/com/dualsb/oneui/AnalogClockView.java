package com.dualsb.oneui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Calendar;

/** Đồng hồ analog: mặt + 3 kim có thể là ảnh (vuông, kim hướng lên 12h, tâm ảnh = trục quay). */
public class AnalogClockView extends View {
    private Bitmap dial, hour, minute, second;
    private boolean showSeconds = true;
    private int color = Color.WHITE;
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private boolean attached, screenOn = true;

    private final Runnable ticker = new Runnable() {
        @Override public void run() { invalidate(); scheduleNext(); }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) { screenOn = false; removeCallbacks(ticker); }
            else {
                if (Intent.ACTION_SCREEN_ON.equals(a)) screenOn = true;
                invalidate();
                scheduleNext();
            }
        }
    };

    public AnalogClockView(Context c) {
        super(c);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void configure(Bitmap dial, Bitmap hour, Bitmap minute, Bitmap second, boolean showSeconds, int color) {
        this.dial = dial; this.hour = hour; this.minute = minute; this.second = second;
        this.showSeconds = showSeconds; this.color = color;
        invalidate();
        scheduleNext();
    }

    private void scheduleNext() {
        removeCallbacks(ticker);
        if (!attached || !screenOn) return;
        long step = showSeconds ? 1000 : 60000;
        postDelayed(ticker, step - System.currentTimeMillis() % step + 5);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        getContext().registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        scheduleNext();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
        removeCallbacks(ticker);
        try { getContext().unregisterReceiver(receiver); } catch (Throwable ignored) {}
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float s = Math.min(w, h);
        if (s <= 0) return;
        float cx = w / 2f, cy = h / 2f, r = s / 2f;
        dst.set(cx - r, cy - r, cx + r, cy + r);

        Calendar cal = Calendar.getInstance();
        float sec = cal.get(Calendar.SECOND);
        float min = cal.get(Calendar.MINUTE) + sec / 60f;
        float hr = cal.get(Calendar.HOUR) + min / 60f;

        if (dial != null) c.drawBitmap(dial, null, dst, bmpPaint);
        else drawDefaultFace(c, cx, cy, r);

        hand(c, hour, hr * 30f, cx, cy, r, 0.50f, 0.07f, color);
        hand(c, minute, min * 6f, cx, cy, r, 0.78f, 0.05f, color);
        if (showSeconds) hand(c, second, sec * 6f, cx, cy, r, 0.88f, 0.025f, 0xFFFF5252);
    }

    private void drawDefaultFace(Canvas c, float cx, float cy, float r) {
        linePaint.setColor(color);
        linePaint.setAlpha(150);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Math.max(1f, r * 0.06f));
        c.drawCircle(cx, cy, r - linePaint.getStrokeWidth(), linePaint);
        for (int i = 0; i < 12; i++) {
            c.save();
            c.rotate(i * 30f, cx, cy);
            c.drawLine(cx, cy - r * 0.80f, cx, cy - r * (i % 3 == 0 ? 0.64f : 0.72f), linePaint);
            c.restore();
        }
    }

    private void hand(Canvas c, Bitmap bm, float deg, float cx, float cy, float r,
                      float len, float width, int col) {
        c.save();
        c.rotate(deg, cx, cy);
        if (bm != null) {
            c.drawBitmap(bm, null, dst, bmpPaint);
        } else {
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setColor(col);
            linePaint.setStrokeWidth(Math.max(1.5f, r * width * 2f));
            c.drawLine(cx, cy, cx, cy - r * len, linePaint);
        }
        c.restore();
    }
}
