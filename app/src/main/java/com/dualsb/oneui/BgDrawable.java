package com.dualsb.oneui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** mode: 0 kéo giãn, 1 cắt lấp đầy, 2 vừa chiều cao căn giữa, 3 lặp ngang. */
final class BgDrawable extends Drawable {
    private final Bitmap bm;
    private final int mode;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    BgDrawable(Bitmap bm, int mode, int alphaPercent) {
        this.bm = bm;
        this.mode = mode;
        paint.setAlpha(Math.max(0, Math.min(100, alphaPercent)) * 255 / 100);
    }

    @Override public void draw(Canvas c) {
        Rect r = getBounds();
        int w = r.width(), h = r.height();
        int bw = bm.getWidth(), bh = bm.getHeight();
        if (w <= 0 || h <= 0 || bw <= 0 || bh <= 0) return;
        c.save();
        c.clipRect(r);
        switch (mode) {
            case 1: {
                float s = Math.max(w / (float) bw, h / (float) bh);
                float dw = bw * s, dh = bh * s;
                float l = r.left + (w - dw) / 2f, t = r.top + (h - dh) / 2f;
                c.drawBitmap(bm, null, new android.graphics.RectF(l, t, l + dw, t + dh), paint);
                break;
            }
            case 2: {
                float s = h / (float) bh;
                float dw = bw * s;
                float l = r.left + (w - dw) / 2f;
                c.drawBitmap(bm, null, new android.graphics.RectF(l, r.top, l + dw, r.bottom), paint);
                break;
            }
            case 3: {
                float s = h / (float) bh;
                float dw = Math.max(1f, bw * s);
                for (float x = r.left; x < r.right; x += dw)
                    c.drawBitmap(bm, null, new android.graphics.RectF(x, r.top, x + dw, r.bottom), paint);
                break;
            }
            default:
                c.drawBitmap(bm, null, r, paint);
        }
        c.restore();
    }

    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
