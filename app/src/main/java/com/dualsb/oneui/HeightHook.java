package com.dualsb.oneui;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hook SystemBarUtils.getStatusBarHeight* (chạy ở cả SystemUI và system_server).
 * Giá trị đích: SystemUI ghi vào Settings.Global (Config.SETTING_HEIGHT_PX), system_server đọc lại.
 */
final class HeightHook {
    private HeightHook() {}

    /** -1 = chưa biết (đọc Settings.Global), 0 = tắt, >0 = px. Chỉ SystemUI set. */
    static volatile int targetPx = -1;
    private static long lastRead;
    private static int cached;

    static void install(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists("com.android.internal.policy.SystemBarUtils", cl);
        if (c == null) { XposedBridge.log("[DualSB] SystemBarUtils not found"); return; }
        XC_MethodHook h = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!(p.getResult() instanceof Integer)) return;
                int px = read();
                if (px > 0) p.setResult(px);
            }
        };
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().startsWith("getStatusBarHeight") && m.getReturnType() == int.class) {
                XposedBridge.hookMethod(m, h);
                n++;
            }
        }
        XposedBridge.log("[DualSB] hooked SystemBarUtils methods: " + n);
    }

    static int read() {
        int t = targetPx;
        if (t >= 0) return t;
        long now = SystemClock.uptimeMillis();
        if (now - lastRead < 1000) return cached;
        lastRead = now;
        try {
            Object at = XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentActivityThread");
            Context ctx = (Context) XposedHelpers.callMethod(at, "getSystemContext");
            if (ctx != null) cached = Settings.Global.getInt(ctx.getContentResolver(), Config.SETTING_HEIGHT_PX, 0);
        } catch (Throwable ignored) {}
        return cached;
    }
}
