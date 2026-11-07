package com.dualsb.oneui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

/**
 * Làm nền cho nút điều hướng, thay cho hiệu ứng nhấn mờ mặc định: khi nhấn, ảnh glow phóng từ nhỏ
 * đến lớn (đồng thời đậm dần) ở phía dưới biểu tượng; khi nhả thì mờ dần rồi thu về.
 */
final class GlowDrawable extends Drawable {
    private final Drawable img;
    private final int imgW, imgH;
    private final float maxAlpha;
    private final long durMs;
    private float scale, alpha;
    private boolean pressed;
    private ValueAnimator anim;

    GlowDrawable(Drawable img, int w, int h, float maxAlpha, long durMs) {
        this.img = img;
        this.imgW = Math.max(1, w);
        this.imgH = Math.max(1, h);
        this.maxAlpha = Math.max(0.05f, Math.min(1f, maxAlpha));
        this.durMs = Math.max(80L, durMs);
        img.setCallback(new Drawable.Callback() {          // để ảnh GIF động vẫn chạy
            @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
            @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
            @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
        });
    }

    @Override public boolean isStateful() { return true; }

    @Override protected boolean onStateChange(int[] state) {
        boolean p = false;
        for (int s : state) if (s == android.R.attr.state_pressed) p = true;
        if (p == pressed) return false;
        pressed = p;
        run(p);
        return true;
    }

    private void run(final boolean press) {
        if (anim != null) anim.cancel();
        final float s0 = scale, a0 = alpha;
        final float s1 = press ? 1f : scale;               // nhả: giữ nguyên kích thước, chỉ mờ dần
        final float a1 = press ? 1f : 0f;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(press ? durMs : Math.max(120L, durMs / 2));
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                scale = s0 + (s1 - s0) * t;
                alpha = a0 + (a1 - a0) * t;
                invalidateSelf();
            }
        });
        if (!press) {
            anim.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(Animator a) { cancelled = true; }
                @Override public void onAnimationEnd(Animator a) {
                    if (!cancelled) { scale = 0f; alpha = 0f; invalidateSelf(); }   // lần nhấn sau lại bắt đầu từ nhỏ
                }
            });
        }
        anim.start();
    }

    @Override public void draw(Canvas c) {
        if (alpha <= 0.01f || scale <= 0.01f) return;
        Rect b = getBounds();
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        int hw = Math.max(1, Math.round(imgW * scale / 2f));
        int hh = Math.max(1, Math.round(imgH * scale / 2f));
        img.setBounds(Math.round(cx) - hw, Math.round(cy) - hh, Math.round(cx) + hw, Math.round(cy) + hh);
        img.setAlpha(Math.round(255 * maxAlpha * alpha));
        img.draw(c);
    }

    @Override public void setAlpha(int a) {}
    @Override public void setColorFilter(ColorFilter cf) { img.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
