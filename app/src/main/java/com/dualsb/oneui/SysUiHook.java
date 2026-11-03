package com.dualsb.oneui;

import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collections;
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
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Set<BarController> CONTROLLERS =
            Collections.newSetFromMap(new WeakHashMap<BarController, Boolean>());
    private static boolean observing;
    private static final Map<Integer, Boolean> HEIGHT_IDS = new ConcurrentHashMap<>();

    public static void install(XC_LoadPackage.LoadPackageParam lpp) {
        ClassLoader cl = lpp.classLoader;
        HeightHook.install(cl);
        hookResources();

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

    private static void attach(ViewGroup v) {
        try {
            for (BarController b : new ArrayList<>(CONTROLLERS)) if (b.root == v) return;
            BarController b = new BarController(v);
            CONTROLLERS.add(b);
            ensureObserver(v.getContext());
            refresh(v.getContext());
        } catch (Throwable t) {
            XposedBridge.log(TAG + "attach: " + Log.getStackTraceString(t));
        }
    }

    private static void ensureObserver(final Context ctx) {
        if (observing) return;
        observing = true;
        ctx.getContentResolver().registerContentObserver(Uri.parse(Config.CONFIG_URI), true,
                new ContentObserver(UI) {
                    @Override public void onChange(boolean selfChange) { refresh(ctx); }
                });
    }

    static void refresh(final Context ctx) {
        IO.execute(new Runnable() {
            @Override public void run() {
                Bundle b = null;
                try {
                    b = ctx.getContentResolver().call(Uri.parse(Config.CONFIG_URI), "get", null, null);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "đọc cấu hình lỗi (đã mở app module lần nào chưa?): " + t);
                }
                final Bundle cfg = b != null ? b : new Bundle();
                UI.post(new Runnable() {
                    @Override public void run() {
                        publishHeight(ctx, cfg);
                        for (BarController c : new ArrayList<>(CONTROLLERS)) {
                            try { c.apply(cfg); }
                            catch (Throwable t) { XposedBridge.log(TAG + "apply: " + Log.getStackTraceString(t)); }
                        }
                    }
                });
            }
        });
    }

    private static void publishHeight(Context ctx, Bundle b) {
        boolean on = Config.getBool(b, Config.ENABLED) && Config.getBool(b, Config.DUAL);
        int px = on ? Math.round(Config.getInt(b, Config.HEIGHT)
                * ctx.getResources().getDisplayMetrics().density) : 0;
        HeightHook.targetPx = px;
        try {
            if (Settings.Global.getInt(ctx.getContentResolver(), Config.SETTING_HEIGHT_PX, -1) != px)
                Settings.Global.putInt(ctx.getContentResolver(), Config.SETTING_HEIGHT_PX, px);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "ghi Settings.Global lỗi: " + t);
        }
    }

    /** Trả về chiều cao mới cho các dimen status_bar_height* mà SystemUI đọc runtime. */
    private static void hookResources() {
        try {
            XposedHelpers.findAndHookMethod(Resources.class, "getDimensionPixelSize", int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            int tp = HeightHook.targetPx;
                            if (tp <= 0) return;
                            int id = (Integer) p.args[0];
                            if ((id >>> 24) != 0x7f) return;
                            Boolean hit = HEIGHT_IDS.get(id);
                            if (hit == null) {
                                try {
                                    Resources r = (Resources) p.thisObject;
                                    hit = "com.android.systemui".equals(r.getResourcePackageName(id))
                                            && r.getResourceEntryName(id).startsWith("status_bar_height");
                                } catch (Throwable t) { hit = false; }
                                HEIGHT_IDS.put(id, hit);
                            }
                            if (hit) p.setResult(tp);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hookResources: " + t);
        }
    }
}
