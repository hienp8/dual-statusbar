package com.dualsb.oneui;

import android.app.KeyguardManager;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import de.robv.android.xposed.XposedBridge;

/**
 * Dải cảm ứng mỏng ở cạnh trái / phải màn hình. Mỗi thao tác (chạm, nhấn giữ, vuốt lên, vuốt xuống,
 * vuốt vào trong) gán một hành động trong {@link Actions}.
 */
final class EdgeGesture {
    private static EdgeGesture inst;

    static synchronized EdgeGesture get() {
        if (inst == null) inst = new EdgeGesture();
        return inst;
    }

    private Context ctx;
    private Bundle cfg;
    private WindowManager wm;
    private Strip view;
    private WindowManager.LayoutParams lp;
    private boolean added, registered;
    private final int[] actions = new int[5];      // chạm, giữ, lên, xuống, vào trong
    private boolean haptic, skipSys;
    private int side, alphaPct;
    private float density = 1f;

    private final ComponentCallbacks cb = new ComponentCallbacks() {
        @Override public void onConfigurationChanged(Configuration c) {
            SysUiHook.UI.post(new Runnable() {
                @Override public void run() { if (ctx != null && cfg != null) apply(ctx, cfg); }
            });
        }
        @Override public void onLowMemory() {}
    };

    void apply(Context c, Bundle b) {
        try {
            Context app = c.getApplicationContext();
            ctx = app != null ? app : c;
            cfg = b;
            boolean on = Config.getBool(b, Config.ENABLED) && Config.getBool(b, Config.EDGE_ENABLED);
            if (!on) { remove(); return; }
            if (!registered) { ctx.registerComponentCallbacks(cb); registered = true; }

            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            density = dm.density;
            side = Config.getInt(b, Config.EDGE_SIDE);
            alphaPct = Config.getInt(b, Config.EDGE_ALPHA);
            haptic = Config.getBool(b, Config.EDGE_HAPTIC);
            skipSys = Config.getBool(b, Config.KILL_SKIP_SYS);
            actions[0] = Config.getInt(b, Config.EDGE_TAP);
            actions[1] = Config.getInt(b, Config.EDGE_LONG);
            actions[2] = Config.getInt(b, Config.EDGE_UP);
            actions[3] = Config.getInt(b, Config.EDGE_DOWN);
            actions[4] = Config.getInt(b, Config.EDGE_IN);

            int w = Math.max(1, Math.round(Config.getInt(b, Config.EDGE_W) * density));
            int h = Math.max(1, Math.round(dm.heightPixels * Config.getInt(b, Config.EDGE_H) / 100f));
            int cy = Math.round(dm.heightPixels * Config.getInt(b, Config.EDGE_Y) / 100f);
            int y = Math.max(0, Math.min(dm.heightPixels - h, cy - h / 2));

            if (view == null) {
                view = new Strip(ctx);
                wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                lp = new WindowManager.LayoutParams();
                lp.type = 2024;                                   // TYPE_NAVIGATION_BAR_PANEL
                lp.format = PixelFormat.TRANSLUCENT;
                lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                lp.setTitle("DualSB-Edge");
            }
            lp.width = w;
            lp.height = h;
            lp.gravity = Gravity.TOP | (side == 0 ? Gravity.LEFT : Gravity.RIGHT);
            lp.x = 0;
            lp.y = y;
            view.invalidate();

            if (!added) {
                try {
                    wm.addView(view, lp);
                    added = true;
                } catch (Throwable t) {
                    lp.type = 2038;                                // TYPE_APPLICATION_OVERLAY
                    try {
                        wm.addView(view, lp);
                        added = true;
                    } catch (Throwable t2) {
                        XposedBridge.log(SysUiHook.TAG + "không thêm được dải cử chỉ: " + t2);
                    }
                }
            } else {
                wm.updateViewLayout(view, lp);
            }
        } catch (Throwable t) {
            XposedBridge.log(SysUiHook.TAG + "EdgeGesture.apply: " + t);
        }
    }

    private void remove() {
        try {
            if (added && view != null) wm.removeView(view);
        } catch (Throwable ignored) {}
        added = false;
    }

    private void trigger(int idx) {
        int a = actions[idx];
        if (a == 0) return;
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) return;       // không hoạt động trên màn hình khoá
        } catch (Throwable ignored) {}
        if (haptic && view != null) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        Actions.run(ctx, a, skipSys);
    }

    private final class Strip extends View {
        private float x0, y0;
        private boolean fired;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable longPress = new Runnable() {
            @Override public void run() { if (!fired) { fired = true; trigger(1); } }
        };

        Strip(Context c) { super(c); }

        @Override protected void onDraw(Canvas c) {
            if (alphaPct <= 0) return;
            int col = SysUiHook.lastAutoColor;
            paint.setColor(Color.argb(Math.min(255, alphaPct * 255 / 100), Color.red(col), Color.green(col), Color.blue(col)));
            float r = Math.min(getWidth(), getHeight()) / 2f;
            c.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getX(); y0 = e.getY(); fired = false;
                    removeCallbacks(longPress);
                    if (actions[1] != 0) postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(e.getX() - x0, e.getY() - y0) > slop) removeCallbacks(longPress);
                    return true;
                case MotionEvent.ACTION_UP: {
                    removeCallbacks(longPress);
                    if (fired) return true;
                    float dx = e.getX() - x0, dy = e.getY() - y0;
                    float thr = 24 * density;
                    if (Math.abs(dx) < slop && Math.abs(dy) < slop) {
                        trigger(0);
                    } else if (Math.abs(dy) >= Math.abs(dx)) {
                        if (Math.abs(dy) >= thr) trigger(dy < 0 ? 2 : 3);
                    } else {
                        boolean inward = side == 0 ? dx > 0 : dx < 0;
                        if (inward && Math.abs(dx) >= thr) trigger(4);
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    return true;
                default:
                    return true;
            }
        }
    }
}
