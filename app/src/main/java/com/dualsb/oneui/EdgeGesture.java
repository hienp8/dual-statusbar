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
 * Hai dải cảm ứng mỏng (cạnh trái, cạnh phải), cấu hình độc lập. Mỗi thao tác (chạm, nhấn giữ, vuốt lên,
 * vuốt xuống, vuốt vào trong) gán một hành động trong {@link Actions}.
 * Khi người dùng đang chỉnh vị trí trong app (edge_preview), dải hiện mờ xám nhạt để dễ canh.
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
    private boolean registered, haptic, skipSys, preview;
    private float density = 1f;
    private final Side[] sides = {new Side(0), new Side(1)};

    private final ComponentCallbacks cb = new ComponentCallbacks() {
        @Override public void onConfigurationChanged(Configuration c) {
            SysUiHook.UI.post(new Runnable() {
                @Override public void run() { if (ctx != null && cfg != null) apply(ctx, cfg); }
            });
        }
        @Override public void onLowMemory() {}
    };

    private static final class Side {
        final int index;                       // 0 trái, 1 phải
        Strip view;
        WindowManager.LayoutParams lp;
        boolean added, enabled;
        int alphaPct;
        final int[] actions = new int[5];      // chạm, giữ, lên, xuống, vào trong
        Side(int i) { index = i; }
    }

    void apply(Context c, Bundle b) {
        try {
            Context app = c.getApplicationContext();
            ctx = app != null ? app : c;
            cfg = b;
            boolean master = Config.getBool(b, Config.ENABLED);
            haptic = Config.getBool(b, Config.EDGE_HAPTIC);
            skipSys = Config.getBool(b, Config.KILL_SKIP_SYS);
            long ts = b.getLong(Config.EDGE_PREVIEW_TS, 0);
            preview = Config.getBool(b, Config.EDGE_PREVIEW) && System.currentTimeMillis() - ts < 5 * 60 * 1000L;
            if (!registered) { ctx.registerComponentCallbacks(cb); registered = true; }
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            density = dm.density;
            for (Side s : sides) applySide(s, b, master, dm);
        } catch (Throwable t) {
            XposedBridge.log(SysUiHook.TAG + "EdgeGesture.apply: " + t);
        }
    }

    private void applySide(Side s, Bundle b, boolean master, DisplayMetrics dm) {
        s.enabled = master && Config.getBool(b, Config.edgeKey(s.index, "enabled"));
        if (!s.enabled) { removeSide(s); return; }

        s.alphaPct = Config.getInt(b, Config.edgeKey(s.index, "alpha"));
        s.actions[0] = Config.getInt(b, Config.edgeKey(s.index, "tap"));
        s.actions[1] = Config.getInt(b, Config.edgeKey(s.index, "long"));
        s.actions[2] = Config.getInt(b, Config.edgeKey(s.index, "up"));
        s.actions[3] = Config.getInt(b, Config.edgeKey(s.index, "down"));
        s.actions[4] = Config.getInt(b, Config.edgeKey(s.index, "in"));

        int w = Math.max(1, Math.round(Config.getInt(b, Config.edgeKey(s.index, "w")) * density));
        int h = Math.max(1, Math.round(dm.heightPixels * Config.getInt(b, Config.edgeKey(s.index, "h")) / 100f));
        int cy = Math.round(dm.heightPixels * Config.getInt(b, Config.edgeKey(s.index, "y")) / 100f);
        int y = Math.max(0, Math.min(dm.heightPixels - h, cy - h / 2));

        if (s.view == null) {
            s.view = new Strip(ctx, s);
            s.lp = new WindowManager.LayoutParams();
            s.lp.type = 2024;                                        // TYPE_NAVIGATION_BAR_PANEL
            s.lp.format = PixelFormat.TRANSLUCENT;
            s.lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            s.lp.setTitle(s.index == 0 ? "DualSB-EdgeL" : "DualSB-EdgeR");
        }
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (preview) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;   // chỉ xem, không kích hoạt nhầm
        s.lp.flags = flags;
        s.lp.width = w;
        s.lp.height = h;
        s.lp.gravity = Gravity.TOP | (s.index == 0 ? Gravity.LEFT : Gravity.RIGHT);
        s.lp.x = 0;
        s.lp.y = y;
        s.view.invalidate();

        if (!s.added) {
            try {
                wm.addView(s.view, s.lp);
                s.added = true;
            } catch (Throwable t) {
                s.lp.type = 2038;                                    // TYPE_APPLICATION_OVERLAY
                try {
                    wm.addView(s.view, s.lp);
                    s.added = true;
                } catch (Throwable t2) {
                    XposedBridge.log(SysUiHook.TAG + "không thêm được dải cử chỉ: " + t2);
                }
            }
        } else {
            wm.updateViewLayout(s.view, s.lp);
        }
    }

    private void removeSide(Side s) {
        try {
            if (s.added && s.view != null) wm.removeView(s.view);
        } catch (Throwable ignored) {}
        s.added = false;
    }

    private void trigger(Side s, int idx) {
        int a = s.actions[idx];
        if (a == 0) return;
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) return;       // không hoạt động trên màn hình khoá
        } catch (Throwable ignored) {}
        if (haptic && s.view != null) s.view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        Actions.run(ctx, a, skipSys);
    }

    private final class Strip extends View {
        private final Side side;
        private float x0, y0;
        private boolean fired;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable longPress = new Runnable() {
            @Override public void run() { if (!fired) { fired = true; trigger(side, 1); } }
        };

        Strip(Context c, Side side) {
            super(c);
            this.side = side;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h) / 2f;
            if (preview) {                                           // vùng xám nhạt khi đang chỉnh vị trí
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(90, 170, 170, 170));
                c.drawRoundRect(0, 0, w, h, r, r, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, density));
                paint.setColor(Color.argb(170, 255, 255, 255));
                c.drawRoundRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, r, r, paint);
                return;
            }
            if (side.alphaPct <= 0) return;
            int col = SysUiHook.lastAutoColor;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.min(255, side.alphaPct * 255 / 100),
                    Color.red(col), Color.green(col), Color.blue(col)));
            c.drawRoundRect(0, 0, w, h, r, r, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getX(); y0 = e.getY(); fired = false;
                    removeCallbacks(longPress);
                    if (side.actions[1] != 0) postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
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
                        trigger(side, 0);
                    } else if (Math.abs(dy) >= Math.abs(dx)) {
                        if (Math.abs(dy) >= thr) trigger(side, dy < 0 ? 2 : 3);
                    } else {
                        boolean inward = side.index == 0 ? dx > 0 : dx < 0;
                        if (inward && Math.abs(dx) >= thr) trigger(side, 4);
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
