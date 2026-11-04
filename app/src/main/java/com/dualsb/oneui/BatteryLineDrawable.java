package com.dualsb.oneui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * Đường ngang sát viền dưới, căn giữa, độ dài = % pin (foreground của status bar).
 * Khi sạc: các chấm xanh chạy từ hai lề ngoài vào hai đầu thanh pin.
 */
final class BatteryLineDrawable extends Drawable {
    private static final long FRAME_MS = 33;   // ~30 fps, chỉ khi đang sạc

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect dirty = new Rect();
    private int level = 100;
    private boolean charging;
    private float thick = 4f;
    private int colNormal = 0xFFE53935, colCharging = 0xFF4CAF50;
    private boolean anim = true;
    private float speed = 100f;     // px / giây
    private float spacing = 60f;    // px
    private boolean visible = true;
    private final long t0 = SystemClock.uptimeMillis();

    private final Runnable frame = new Runnable() {
        @Override public void run() { invalidateSelf(); }
    };

    void configure(float thickPx, int normal, int chg, boolean anim, float speedPxPerSec, float spacingPx) {
        thick = thickPx; colNormal = normal; colCharging = chg;
        this.anim = anim; speed = Math.max(1f, speedPxPerSec); spacing = Math.max(4f, spacingPx);
        invalidateSelf();
    }

    void setState(int level, boolean charging) {
        this.level = level;
        this.charging = charging;
        if (!charging) unscheduleSelf(frame);
        invalidateSelf();
    }

    /** Chỉ vẽ lại dải sát đáy để tiết kiệm. */
    @Override public Rect getDirtyBounds() {
        Rect b = getBounds();
        int h = (int) Math.ceil(thick * 1.6f) + 2;
        dirty.set(b.left, Math.max(b.top, b.bottom - h), b.right, b.bottom);
        return dirty;
    }

    @Override public boolean setVisible(boolean visible, boolean restart) {
        this.visible = visible;
        if (!visible) unscheduleSelf(frame); else invalidateSelf();
        return super.setVisible(visible, restart);
    }

    @Override public void draw(Canvas c) {
        Rect r = getBounds();
        if (r.width() <= 0 || r.height() <= 0) return;
        float w = r.width() * Math.max(0, Math.min(100, level)) / 100f;
        float l = r.left + (r.width() - w) / 2f;
        float rr = l + w;

        paint.setColor(charging ? colCharging : colNormal);
        c.drawRect(l, r.bottom - thick, rr, r.bottom, paint);

        if (charging && anim && visible) {
            float travel = l - r.left;                       // quãng đường từ lề tới đầu thanh pin
            if (travel > 1f) {
                float rad = thick * 0.8f;
                float cy = r.bottom - rad;
                float off = ((SystemClock.uptimeMillis() - t0) / 1000f * speed) % spacing;
                dot.setColor(colCharging);
                for (float x = off; x < travel; x += spacing) {
                    float t = x / travel;                    // 0 ở lề, 1 ở đầu thanh
                    float f = Math.min(1f, Math.min(t * 6f, (1f - t) * 6f));
                    dot.setAlpha((int) (255 * Math.max(0f, f)));
                    c.drawCircle(r.left + x, cy, rad, dot);            // bên trái
                    c.drawCircle(r.right - x, cy, rad, dot);           // bên phải
                }
                unscheduleSelf(frame);
                scheduleSelf(frame, SystemClock.uptimeMillis() + FRAME_MS);
            }
        }
    }

    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
