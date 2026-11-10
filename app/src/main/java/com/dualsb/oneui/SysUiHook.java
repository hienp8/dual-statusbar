package com.dualsb.oneui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.graphics.Insets;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class SysUiHook {
    private SysUiHook() {}

    static final String TAG = "[DualSB] ";
    static final Handler UI = new Handler(Looper.getMainLooper());
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Set<BarController> CONTROLLERS =
            Collections.newSetFromMap(new WeakHashMap<BarController, Boolean>());
    private static boolean observing;
    private static final Map<Integer, Integer> HEIGHT_IDS = new ConcurrentHashMap<>();
    static volatile int lastAutoColor = 0xFFFFFFFF;   // màu chữ hệ thống hiện tại (BarController cập nhật)

    public static void install(XC_LoadPackage.LoadPackageParam lpp) {
        ClassLoader cl = lpp.classLoader;
        HeightHook.install(cl);
        hookResources();
        hookWindow(cl);
        NavStyle.install(cl);

        Class<?> kg = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.phone.KeyguardStatusBarView", cl);
        if (kg == null) {
            XposedBridge.log(TAG + "KeyguardStatusBarView not found");
        } else {
            XposedBridge.hookAllConstructors(kg, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    final ViewGroup v = (ViewGroup) p.thisObject;
                    v.post(new Runnable() { @Override public void run() { attachKeyguard(v); } });
                }
            });
            XposedBridge.log(TAG + "hooked KeyguardStatusBarView");
        }

        Class<?> ns = null;
        for (String n : new String[]{"com.android.systemui.shade.NotificationShadeWindowView",
                "com.android.systemui.statusbar.phone.NotificationShadeWindowView"}) {
            ns = XposedHelpers.findClassIfExists(n, cl);
            if (ns != null) break;
        }
        if (ns == null) {
            XposedBridge.log(TAG + "NotificationShadeWindowView not found (tuỳ chỉnh màn hình khóa)");
        } else {
            XposedBridge.hookAllConstructors(ns, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    final ViewGroup v = (ViewGroup) p.thisObject;
                    v.post(new Runnable() { @Override public void run() { attachLock(v); } });
                }
            });
            XposedBridge.log(TAG + "hooked " + ns.getName());
        }

        Class<?> c = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.phone.PhoneStatusBarView", cl);
        if (c == null) { XposedBridge.log(TAG + "PhoneStatusBarView not found"); return; }
        // post() khi view chưa attach sẽ chạy sau khi inflate xong và attach vào window
        XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                final ViewGroup v = (ViewGroup) p.thisObject;
                v.post(new Runnable() { @Override public void run() { attach(v); } });
            }
        });
        XposedBridge.log(TAG + "hooked PhoneStatusBarView");
    }

    static volatile Bundle lastCfg;
    static volatile float rowTopPx, rowBottomPx;
    private static final Set<KeyguardBarController> KG =
            Collections.newSetFromMap(new WeakHashMap<KeyguardBarController, Boolean>());
    private static int retry;
    private static Runnable pendingRetry;

    /** Hàng trên/dưới (toạ độ px trong thanh) do BarController tính, để màn hình khóa dùng chung. */
    static void setRows(float top, float bottom) {
        if (Math.abs(top - rowTopPx) < 0.5f && Math.abs(bottom - rowBottomPx) < 0.5f) return;
        rowTopPx = top;
        rowBottomPx = bottom;
        for (KeyguardBarController k : new ArrayList<>(KG)) k.relayout();
    }

    private static void attach(ViewGroup v) {
        try {
            for (BarController b : new ArrayList<>(CONTROLLERS)) if (b.root == v) return;
            BarController b = new BarController(v);
            CONTROLLERS.add(b);
            ensureObserver(v.getContext());
            Bundle c = lastCfg;
            if (c != null) b.apply(c);
            refresh(v.getContext());
        } catch (Throwable t) {
            XposedBridge.log(TAG + "attach: " + Log.getStackTraceString(t));
        }
    }

    private static void attachKeyguard(ViewGroup v) {
        try {
            for (KeyguardBarController k : new ArrayList<>(KG)) if (k.view == v) return;
            KeyguardBarController k = new KeyguardBarController(v);
            KG.add(k);
            ensureObserver(v.getContext());
            Bundle c = lastCfg;
            if (c != null) k.apply(c);
            View rootV = v.getRootView();                       // cửa sổ chứa màn hình khóa
            if (rootV instanceof ViewGroup && rootV != v) attachLock((ViewGroup) rootV);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "attachKeyguard: " + Log.getStackTraceString(t));
        }
    }

    private static final Set<LockScreenController> LS =
            Collections.newSetFromMap(new WeakHashMap<LockScreenController, Boolean>());

    private static void attachLock(ViewGroup v) {
        try {
            for (LockScreenController l : new ArrayList<>(LS)) if (l.root == v) return;
            LockScreenController l = new LockScreenController(v);
            LS.add(l);
            ensureObserver(v.getContext());
            Bundle c = lastCfg;
            if (c != null) l.apply(c);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "attachLock: " + Log.getStackTraceString(t));
        }
    }

    /** Báo tên các mục đang hiển thị trên màn hình khóa về app để hiện trong menu chọn. */
    static void reportLockItems(final Context ctx, final String csv) {
        IO.execute(new Runnable() {
            @Override public void run() {
                try { ctx.getContentResolver().call(Uri.parse(Config.CONFIG_URI), "report_ls", csv, null); }
                catch (Throwable t) { XposedBridge.log(TAG + "báo mục màn hình khóa lỗi: " + t); }
            }
        });
    }

    private static void ensureObserver(final Context ctx) {
        if (observing) return;
        observing = true;
        ctx.getContentResolver().registerContentObserver(Uri.parse(Config.CONFIG_URI), true,
                new ContentObserver(UI) {
                    @Override public void onChange(boolean selfChange) { refresh(ctx); }
                });
        // Sau khi khởi động lại, SystemUI chạy trước khi mở khoá: đọc lại ngay khi người dùng mở khoá.
        try {
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { refresh(ctx); }
            }, new IntentFilter("android.intent.action.USER_UNLOCKED"), Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "register USER_UNLOCKED: " + t);
        }
    }

    static void refresh(final Context ctx) {
        IO.execute(new Runnable() {
            @Override public void run() {
                Bundle b = null;
                try {
                    b = ctx.getContentResolver().call(Uri.parse(Config.CONFIG_URI), "get", null, null);
                } catch (Throwable t) {
                    if (retry < 3) XposedBridge.log(TAG + "chưa đọc được cấu hình (sẽ thử lại): " + t);
                }
                final Bundle cfg = b;
                UI.post(new Runnable() {
                    @Override public void run() {
                        if (cfg == null) { scheduleRetry(ctx); return; }   // giữ nguyên giao diện, không áp mặc định
                        retry = 0;
                        lastCfg = cfg;
                        publishHeight(ctx, cfg);
                        for (BarController c : new ArrayList<>(CONTROLLERS)) {
                            try { c.apply(cfg); }
                            catch (Throwable t) { XposedBridge.log(TAG + "apply: " + Log.getStackTraceString(t)); }
                        }
                        for (KeyguardBarController k : new ArrayList<>(KG)) {
                            try { k.apply(cfg); }
                            catch (Throwable t) { XposedBridge.log(TAG + "apply kg: " + Log.getStackTraceString(t)); }
                        }
                        for (LockScreenController l : new ArrayList<>(LS)) {
                            try { l.apply(cfg); }
                            catch (Throwable t) { XposedBridge.log(TAG + "apply ls: " + Log.getStackTraceString(t)); }
                        }
                        try { NavStyle.setConfig(cfg); }
                        catch (Throwable t) { XposedBridge.log(TAG + "apply nav: " + Log.getStackTraceString(t)); }
                        try { EdgeGesture.get().apply(ctx, cfg); }
                        catch (Throwable t) { XposedBridge.log(TAG + "apply edge: " + Log.getStackTraceString(t)); }
                    }
                });
            }
        });
    }

    private static void scheduleRetry(final Context ctx) {
        if (pendingRetry != null) UI.removeCallbacks(pendingRetry);
        long delay = Math.min(30000L, 2000L << Math.min(retry, 4));
        retry++;
        pendingRetry = new Runnable() {
            @Override public void run() { pendingRetry = null; refresh(ctx); }
        };
        UI.postDelayed(pendingRetry, delay);
    }

    private static void publishHeight(Context ctx, Bundle b) {
        boolean on = Config.getBool(b, Config.ENABLED) && Config.getBool(b, Config.DUAL);
        float density = ctx.getResources().getDisplayMetrics().density;
        int px = on ? Math.round(Config.getInt(b, Config.HEIGHT) * density) : 0;
        HeightHook.targetPx = px;
        boolean navOn = Config.getBool(b, Config.ENABLED) && Config.getBool(b, Config.NAV_ENABLED);
        int npx = navOn ? Math.round(Config.getInt(b, Config.NAV_HEIGHT) * density) : 0;
        navPx = npx;
        try {
            if (Settings.Global.getInt(ctx.getContentResolver(), Config.SETTING_HEIGHT_PX, -1) != px)
                Settings.Global.putInt(ctx.getContentResolver(), Config.SETTING_HEIGHT_PX, px);
            if (Settings.Global.getInt(ctx.getContentResolver(), Config.SETTING_NAV_PX, -1) != npx)
                Settings.Global.putInt(ctx.getContentResolver(), Config.SETTING_NAV_PX, npx);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "ghi Settings.Global lỗi: " + t);
        }
    }

    /** Báo danh sách biểu tượng (slot) đã thấy về app module để hiện trong menu ẩn / hiện. */
    static void reportSlots(final Context ctx, final String csv) {
        IO.execute(new Runnable() {
            @Override public void run() {
                try { ctx.getContentResolver().call(Uri.parse(Config.CONFIG_URI), "report_slots", csv, null); }
                catch (Throwable t) { XposedBridge.log(TAG + "báo slot lỗi: " + t); }
            }
        });
    }

    /** Trả về chiều cao mới cho status_bar_height* và navigation_bar_height mà SystemUI đọc runtime. */
    private static void hookResources() {
        try {
            XposedHelpers.findAndHookMethod(Resources.class, "getDimensionPixelSize", int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            int sp = HeightHook.targetPx, np = navPx;
                            if (sp <= 0 && np <= 0) return;
                            int id = (Integer) p.args[0];
                            if ((id >>> 24) != 0x7f) return;
                            Integer kind = HEIGHT_IDS.get(id);
                            if (kind == null) {
                                int k = 0;
                                try {
                                    Resources r = (Resources) p.thisObject;
                                    if ("com.android.systemui".equals(r.getResourcePackageName(id))) {
                                        String n = r.getResourceEntryName(id);
                                        if (n.startsWith("status_bar_height")) k = 1;
                                        else if (n.equals("navigation_bar_height") || n.equals("navigation_bar_frame_height")) k = 2;
                                    }
                                } catch (Throwable t) { k = 0; }
                                kind = k;
                                HEIGHT_IDS.put(id, kind);
                            }
                            if (kind == 1 && sp > 0) p.setResult(sp);
                            else if (kind == 2 && np > 0) p.setResult(np);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hookResources: " + t);
        }
    }

    // ------------------------------------------------------------------ cửa sổ status bar

    private static final Map<View, Integer> ORIG_WIN_H = Collections.synchronizedMap(new WeakHashMap<View, Integer>());
    private static final Map<Object, Object> ORIG_INS = Collections.synchronizedMap(new IdentityHashMap<Object, Object>());

    /** Ép chiều cao cửa sổ status bar mỗi khi SystemUI add/update (Samsung tự cập nhật lại lp). */
    private static void hookWindow(ClassLoader cl) {
        try {
            Class<?> g = XposedHelpers.findClassIfExists("android.view.WindowManagerGlobal", cl);
            if (g == null) { XposedBridge.log(TAG + "WindowManagerGlobal not found"); return; }
            XC_MethodHook h = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length >= 2 && p.args[0] instanceof View
                                && p.args[1] instanceof ViewGroup.LayoutParams)
                            adjustWindow((View) p.args[0], (ViewGroup.LayoutParams) p.args[1]);
                    } catch (Throwable ignored) {}
                }
            };
            XposedBridge.hookAllMethods(g, "addView", h);
            XposedBridge.hookAllMethods(g, "updateViewLayout", h);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hookWindow: " + t);
        }
    }

    private static final Map<View, Integer> ORIG_NAV_H = Collections.synchronizedMap(new WeakHashMap<View, Integer>());
    static volatile int navPx = -1;          // -1 chưa biết (đọc Settings.Global), 0 tắt, >0 px
    private static long navRead;
    private static int navCached;

    static int readNavPx(Context ctx) {
        int t = navPx;
        if (t >= 0) return t;
        long now = SystemClock.uptimeMillis();
        if (now - navRead < 1000) return navCached;
        navRead = now;
        try { navCached = Settings.Global.getInt(ctx.getContentResolver(), Config.SETTING_NAV_PX, 0); }
        catch (Throwable ignored) {}
        return navCached;
    }

    static void adjustWindow(View v, ViewGroup.LayoutParams lp) {
        if (!(lp instanceof WindowManager.LayoutParams)) return;
        WindowManager.LayoutParams w = (WindowManager.LayoutParams) lp;
        String cn = v.getClass().getName();

        if (w.type == WindowManager.LayoutParams.TYPE_STATUS_BAR && cn.contains("StatusBarWindow")) {
            synchronized (ORIG_WIN_H) {
                if (!ORIG_WIN_H.containsKey(v)) {
                    ORIG_WIN_H.put(v, w.height);
                    XposedBridge.log(TAG + "status bar window: " + cn + " h=" + w.height);
                }
            }
            int px = HeightHook.targetPx;
            if (px > 0) {
                w.height = px;
                fixInsets(w, WindowInsets.Type.statusBars(), 1, px);
            } else if (px == 0) {
                Integer o = ORIG_WIN_H.get(v);
                if (o != null) w.height = o;
                fixInsets(w, WindowInsets.Type.statusBars(), 1, 0);
            }
        } else if (w.type == 2019 && cn.contains("NavigationBar")                      // TYPE_NAVIGATION_BAR
                && (w.gravity & Gravity.BOTTOM) == Gravity.BOTTOM) {
            synchronized (ORIG_NAV_H) {
                if (!ORIG_NAV_H.containsKey(v)) {
                    ORIG_NAV_H.put(v, w.height);
                    XposedBridge.log(TAG + "navigation bar window: " + cn + " h=" + w.height);
                }
            }
            int px = readNavPx(v.getContext());
            if (px > 0) {
                w.height = px;
                fixInsets(w, WindowInsets.Type.navigationBars(), 3, px);
            } else if (px == 0) {
                Integer o = ORIG_NAV_H.get(v);
                if (o != null) w.height = o;
                fixInsets(w, WindowInsets.Type.navigationBars(), 3, 0);
            }
        }
    }

    /** Cập nhật kích thước inset mà cửa sổ cung cấp cho hệ thống. side: 1 = trên (status), 3 = dưới (nav). */
    private static void fixInsets(WindowManager.LayoutParams w, int typeMask, int side, int px) {
        try {
            Object arr = XposedHelpers.getObjectField(w, "providedInsets");
            if (arr == null) return;
            int n = java.lang.reflect.Array.getLength(arr);
            for (int i = 0; i < n; i++) {
                Object p = java.lang.reflect.Array.get(arr, i);
                int type = (Integer) XposedHelpers.callMethod(p, "getType");
                if ((type & typeMask) == 0) continue;
                synchronized (ORIG_INS) {
                    if (!ORIG_INS.containsKey(p)) ORIG_INS.put(p, XposedHelpers.callMethod(p, "getInsetsSize"));
                }
                Insets o = (Insets) ORIG_INS.get(p);
                Insets ni;
                if (px > 0) {
                    int l = o == null ? 0 : o.left, t = o == null ? 0 : o.top;
                    int r = o == null ? 0 : o.right, b = o == null ? 0 : o.bottom;
                    ni = side == 1 ? Insets.of(l, px, r, b) : Insets.of(l, t, r, px);
                } else {
                    ni = o;
                }
                if (ni != null) XposedHelpers.callMethod(p, "setInsetsSize", ni);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "fixInsets: " + t);
        }
    }
}
