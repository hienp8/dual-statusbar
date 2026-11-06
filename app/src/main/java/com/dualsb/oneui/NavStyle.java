package com.dualsb.oneui;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Size;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Tuỳ chỉnh thanh điều hướng 3 nút:
 *  - thay biểu tượng Back / Home / Recents bằng ảnh của bạn (Home hỗ trợ GIF động) — vẽ chồng lên nút bằng
 *    ViewOverlay, giữ nguyên biểu tượng gốc ở dưới (độ trong suốt 0) để Samsung vẫn điều khiển bình thường;
 *  - nhấn giữ Back: đóng toàn bộ ứng dụng chạy nền;
 *  - đổi chiều cao (qua SysUiHook.adjustWindow).
 */
final class NavStyle {
    private NavStyle() {}

    private static final String TAG = SysUiHook.TAG;
    private static final String[] KEY_CLASSES = {
            "com.android.systemui.navigationbar.views.buttons.KeyButtonView",
            "com.android.systemui.navigationbar.buttons.KeyButtonView",
            "com.android.systemui.statusbar.phone.KeyButtonView"};

    private static volatile Bundle cfg;
    private static Class<?> kbClass;
    private static final Set<View> BUTTONS = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static final Map<View, Holder> HOLDERS = new WeakHashMap<>();
    private static final Map<View, Press> PRESS = new WeakHashMap<>();

    private static final class Holder {
        Drawable icon;
        long stamp;
        int px;
        float dark;
        String tint = "orig";
    }

    private static final class Press {
        boolean down, fired;
        Runnable r;
        float x, y;
    }

    static void install(ClassLoader cl) {
        Class<?> kb = null;
        for (String n : KEY_CLASSES) {
            kb = XposedHelpers.findClassIfExists(n, cl);
            if (kb != null) break;
        }
        if (kb == null) { XposedBridge.log(TAG + "KeyButtonView not found (thanh điều hướng)"); return; }
        kbClass = kb;

        // Bỏ qua bước vẽ biểu tượng gốc của những nút đã được thay ảnh (Samsung bỏ qua cách làm mờ nên chặn hẳn bước vẽ).
        // Phần nền / hiệu ứng nhấn và overlay (biểu tượng mới) vẫn được vẽ bình thường.
        XC_MethodHook skipDraw = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Class<?> k = kbClass;
                Object o = p.thisObject;
                if (k != null && k.isInstance(o) && HOLDERS.containsKey(o)) p.setResult(null);
            }
        };
        try {
            XposedHelpers.findAndHookMethod(ImageView.class, "onDraw", Canvas.class, skipDraw);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "không hook được ImageView.onDraw: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(kb, "onDraw", Canvas.class, skipDraw);   // nếu nút tự override onDraw
        } catch (Throwable ignored) {}

        XposedBridge.hookAllConstructors(kb, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                final View v = (View) p.thisObject;
                v.post(new Runnable() { @Override public void run() { attach(v); } });
            }
        });
        try {
            XposedHelpers.findAndHookMethod(kb, "onTouchEvent", MotionEvent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try { onTouch(p); } catch (Throwable t) { XposedBridge.log(TAG + "onTouch: " + t); }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "không hook được KeyButtonView.onTouchEvent: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(kb, "setDarkIntensity", float.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { onDark((View) p.thisObject, (Float) p.args[0]); } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "không hook được KeyButtonView.setDarkIntensity: " + t);
        }
        XposedBridge.log(TAG + "hooked " + kb.getName());
    }

    // ------------------------------------------------------------------ nhận diện nút
    private static String nameOf(View v) {
        int code = -1;
        try { code = XposedHelpers.getIntField(v, "mCode"); } catch (Throwable ignored) {}
        switch (code) {
            case 4: return "nav_back";
            case 3: return "nav_home";
            case 187: return "nav_recent";
            default: break;
        }
        try {
            String n = v.getResources().getResourceEntryName(v.getId());
            if ("back".equals(n)) return "nav_back";
            if ("home".equals(n)) return "nav_home";
            if ("recent_apps".equals(n)) return "nav_recent";
        } catch (Throwable ignored) {}
        return null;
    }

    private static String sizeKey(String name) {
        if ("nav_back".equals(name)) return Config.NAV_BACK_SIZE;
        if ("nav_home".equals(name)) return Config.NAV_HOME_SIZE;
        return Config.NAV_RECENT_SIZE;
    }

    private static void attach(final View v) {
        if (!(v instanceof ImageView)) return;
        BUTTONS.add(v);
        v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int l, int t, int r, int b,
                                                 int ol, int ot, int or2, int ob) {
                Holder h = HOLDERS.get(view);
                if (h != null) layoutIcon(view, h);
            }
        });
        refresh(v);
    }

    // ------------------------------------------------------------------ biểu tượng
    static void setConfig(Bundle b) {
        cfg = b;
        for (View v : new ArrayList<>(BUTTONS)) {
            try { refresh(v); } catch (Throwable t) { XposedBridge.log(TAG + "refresh nút: " + t); }
        }
        pushWindow();
    }

    private static void refresh(View v) {
        Bundle b = cfg;
        String name = nameOf(v);
        if (name == null || !(v instanceof ImageView)) return;
        ImageView iv = (ImageView) v;
        boolean on = b != null && Config.getBool(b, Config.ENABLED) && Config.getBool(b, Config.NAV_ENABLED)
                && b.getLong("img_" + name, 0) != 0;
        Holder h = HOLDERS.get(v);
        if (!on) {
            if (h != null) {
                v.getOverlay().remove(h.icon);
                v.invalidate();
                HOLDERS.remove(v);
            }
            return;
        }
        long stamp = b.getLong("img_" + name, 0);
        float d = v.getResources().getDisplayMetrics().density;
        int px = Math.max(1, Math.round(Config.getInt(b, sizeKey(name)) * d));
        if (h == null || h.stamp != stamp || h.px != px) {
            if (h != null) v.getOverlay().remove(h.icon);
            Drawable icon = decode(v.getContext(), name, px);
            if (icon == null) { HOLDERS.remove(v); return; }
            float keepDark = h == null ? 0f : h.dark;
            h = new Holder();
            h.icon = icon; h.stamp = stamp; h.px = px; h.dark = keepDark;
            v.getOverlay().add(icon);
            HOLDERS.put(v, h);
            v.invalidate();                               // biểu tượng gốc sẽ bị bỏ qua khi vẽ (hook onDraw)
        }
        h.tint = Config.getString(b, Config.NAV_TINT);
        applyTint(h);
        layoutIcon(v, h);
    }

    private static Drawable decode(Context ctx, String name, final int px) {
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(ctx.getContentResolver(),
                    Uri.parse(Config.IMG_URI + name));
            Drawable d = ImageDecoder.decodeDrawable(src, new ImageDecoder.OnHeaderDecodedListener() {
                @Override public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info,
                                                      ImageDecoder.Source s) {
                    Size sz = info.getSize();
                    float sc = Math.min((float) px / sz.getWidth(), (float) px / sz.getHeight());
                    decoder.setTargetSize(Math.max(1, Math.round(sz.getWidth() * sc)),
                            Math.max(1, Math.round(sz.getHeight() * sc)));
                }
            });
            if (d instanceof AnimatedImageDrawable) {                    // GIF / WebP động
                AnimatedImageDrawable a = (AnimatedImageDrawable) d;
                a.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                a.start();
            }
            return d;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "giải mã ảnh " + name + " lỗi: " + t);
            return null;
        }
    }

    private static void layoutIcon(View v, Holder h) {
        int iw = h.icon.getIntrinsicWidth() > 0 ? h.icon.getIntrinsicWidth() : h.px;
        int ih = h.icon.getIntrinsicHeight() > 0 ? h.icon.getIntrinsicHeight() : h.px;
        int l = (v.getWidth() - iw) / 2, t = (v.getHeight() - ih) / 2;
        h.icon.setBounds(l, t, l + iw, t + ih);
        v.invalidate();
    }

    private static void applyTint(Holder h) {
        if ("orig".equals(h.tint)) { h.icon.setColorFilter(null); return; }
        int color;
        if ("auto".equals(h.tint)) {
            // dark = 0 (nền tối) -> biểu tượng trắng; dark = 1 (nền sáng) -> biểu tượng đen
            color = (Integer) new ArgbEvaluator().evaluate(Math.max(0f, Math.min(1f, h.dark)), 0xFFFFFFFF, 0xFF000000);
        } else {
            try { color = Color.parseColor(h.tint); } catch (Exception e) { color = Color.WHITE; }
        }
        h.icon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
    }

    private static void onDark(View v, float intensity) {
        Holder h = HOLDERS.get(v);
        if (h == null) return;
        h.dark = intensity;
        if ("auto".equals(h.tint)) { applyTint(h); v.invalidate(); }
    }

    // ------------------------------------------------------------------ nhấn giữ Back
    private static void onTouch(final XC_MethodHook.MethodHookParam p) {
        final Bundle b = cfg;
        final View v = (View) p.thisObject;
        if (b == null || !Config.getBool(b, Config.ENABLED) || !Config.getBool(b, Config.NAV_BACK_KILL)) return;
        if (!"nav_back".equals(nameOf(v))) return;

        MotionEvent e = (MotionEvent) p.args[0];
        Press st = PRESS.get(v);
        if (st == null) { st = new Press(); PRESS.put(v, st); }
        final Press fst = st;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                fst.down = true;
                fst.fired = false;
                fst.x = e.getX();
                fst.y = e.getY();
                final Method m = (Method) p.method;
                final long downTime = e.getDownTime();
                if (fst.r != null) v.removeCallbacks(fst.r);
                fst.r = new Runnable() {
                    @Override public void run() {
                        if (!fst.down || fst.fired) return;
                        fst.fired = true;
                        // huỷ thao tác Back đang chờ để Samsung không gửi phím Back
                        MotionEvent c = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                                MotionEvent.ACTION_CANCEL, fst.x, fst.y, 0);
                        try { XposedBridge.invokeOriginalMethod(m, v, new Object[]{c}); }
                        catch (Throwable t) { XposedBridge.log(TAG + "huỷ Back lỗi: " + t); }
                        c.recycle();
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        Actions.run(v.getContext(), Actions.KILL, Config.getBool(b, Config.KILL_SKIP_SYS));
                    }
                };
                v.postDelayed(fst.r, ViewConfiguration.getLongPressTimeout());
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (fst.fired) { p.setResult(true); break; }
                float slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop() * 2f;
                if (fst.r != null && (Math.abs(e.getX() - fst.x) > slop || Math.abs(e.getY() - fst.y) > slop))
                    v.removeCallbacks(fst.r);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (fst.r != null) v.removeCallbacks(fst.r);
                boolean was = fst.fired;
                fst.down = false;
                fst.fired = false;
                if (was) p.setResult(true);                      // nuốt cú nhả sau khi đã đóng ứng dụng
                break;
            }
            default:
                if (fst.fired) p.setResult(true);
        }
    }

    // ------------------------------------------------------------------ cửa sổ thanh điều hướng
    private static void pushWindow() {
        for (View v : new ArrayList<>(BUTTONS)) {
            try {
                View rv = v.getRootView();
                ViewGroup.LayoutParams wl = rv.getLayoutParams();
                if (wl instanceof WindowManager.LayoutParams && rv.isAttachedToWindow()) {
                    SysUiHook.adjustWindow(rv, wl);
                    ((WindowManager) v.getContext().getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(rv, wl);
                    return;
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "pushWindow nav: " + t);
            }
        }
    }
}
