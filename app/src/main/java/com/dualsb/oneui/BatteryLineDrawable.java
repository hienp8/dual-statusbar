package com.dualsb.oneui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Đường ngang sát viền dưới, căn giữa, độ dài = % pin. Dùng làm foreground của status bar. */
final class BatteryLineDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int level = 100;
    private boolean charging;
    private float thick = 4f;
    private int colNormal = 0xFFE53935, colCharging = 0xFF4CAF50;

    void configure(float thickPx, int normal, int chg) {
        thick = thickPx; colNormal = normal; colCharging = chg;
        invalidateSelf();
    }

    void setState(int level, boolean charging) {
        this.level = level; this.charging = charging;
        invalidateSelf();
    }

    @Override public void draw(Canvas c) {
        Rect r = getBounds();
        if (r.width() <= 0 || r.height() <= 0) return;
        float w = r.width() * Math.max(0, Math.min(100, level)) / 100f;
        float l = r.left + (r.width() - w) / 2f;
        paint.setColor(charging ? colCharging : colNormal);
        c.drawRect(l, r.bottom - thick, l + w, r.bottom, paint);
    }

    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
