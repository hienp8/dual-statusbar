package com.dualsb.oneui;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Các hành động dùng cho phím cử chỉ cạnh và nhấn giữ nút Back. */
final class Actions {
    private Actions() {}

    static final int NONE = 0, BACK = 1, HOME = 2, RECENTS = 3, NOTIF = 4, QS = 5, LOCK = 6, SHOT = 7,
            KILL = 8, VOL_UP = 9, VOL_DOWN = 10, MUTE = 11, TORCH = 12;

    private static boolean torchOn, torchRegistered;

    static void run(final Context ctx, int action, final boolean skipSystem) {
        try {
            switch (action) {
                case BACK: key(KeyEvent.KEYCODE_BACK); break;
                case HOME: key(KeyEvent.KEYCODE_HOME); break;
                case RECENTS: key(KeyEvent.KEYCODE_APP_SWITCH); break;
                case NOTIF: statusBar(ctx, "expandNotificationsPanel"); break;
                case QS: statusBar(ctx, "expandSettingsPanel"); break;
                case LOCK: lock(ctx); break;
                case SHOT: key(KeyEvent.KEYCODE_SYSRQ); break;
                case VOL_UP: volume(ctx, AudioManager.ADJUST_RAISE); break;
                case VOL_DOWN: volume(ctx, AudioManager.ADJUST_LOWER); break;
                case MUTE: volume(ctx, AudioManager.ADJUST_TOGGLE_MUTE); break;
                case TORCH: torch(ctx); break;
                case KILL:
                    SysUiHook.IO.execute(new Runnable() {
                        @Override public void run() { killBackground(ctx, skipSystem); }
                    });
                    break;
                default: break;
            }
        } catch (Throwable t) {
            XposedBridge.log(SysUiHook.TAG + "hành động " + action + " lỗi: " + t);
        }
    }

    // ------------------------------------------------------------------ phím
    private static void key(int code) {
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < 2; i++) {
            KeyEvent e = new KeyEvent(now, now, i == 0 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY, InputDevice.SOURCE_KEYBOARD);
            inject(e);
        }
    }

    private static void inject(InputEvent e) {
        try {   // Android 14+
            Class<?> g = Class.forName("android.hardware.input.InputManagerGlobal");
            Object inst = XposedHelpers.callStaticMethod(g, "getInstance");
            XposedHelpers.callMethod(inst, "injectInputEvent", e, 0);
            return;
        } catch (Throwable ignored) {}
        try {
            Object im = XposedHelpers.callStaticMethod(Class.forName("android.hardware.input.InputManager"), "getInstance");
            XposedHelpers.callMethod(im, "injectInputEvent", e, 0);
        } catch (Throwable t) {
            XposedBridge.log(SysUiHook.TAG + "inject lỗi: " + t);
        }
    }

    // ------------------------------------------------------------------ hệ thống
    private static void statusBar(Context ctx, String method) {
        Object sb = ctx.getSystemService("statusbar");
        XposedHelpers.callMethod(sb, method);
    }

    private static void lock(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        XposedHelpers.callMethod(pm, "goToSleep", SystemClock.uptimeMillis());
    }

    private static void volume(Context ctx, int dir) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        am.adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
    }

    private static void torch(Context ctx) throws Exception {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (!torchRegistered) {
            torchRegistered = true;
            cm.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String id, boolean enabled) { torchOn = enabled; }
            }, null);
        }
        for (String id : cm.getCameraIdList()) {
            Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (f != null && f) {
                cm.setTorchMode(id, !torchOn);
                torchOn = !torchOn;
                return;
            }
        }
    }

    // ------------------------------------------------------------------ đóng ứng dụng chạy nền
    /** Đóng các tiến trình chạy ẩn (dịch vụ / bộ nhớ đệm). Không đụng tới ứng dụng đang dùng, launcher, bàn phím, SystemUI. */
    static void killBackground(final Context ctx, boolean skipSystem) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        PackageManager pm = ctx.getPackageManager();

        Set<String> keep = new HashSet<>();
        keep.add(ctx.getPackageName());
        keep.add("android");
        keep.add("com.android.systemui");
        keep.add("com.dualsb.oneui");
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) keep.add(ri.activityInfo.packageName);
        } catch (Throwable ignored) {}
        try {
            String ime = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null) {
                int i = ime.indexOf('/');
                keep.add(i > 0 ? ime.substring(0, i) : ime);
            }
        } catch (Throwable ignored) {}

        Set<String> kill = new LinkedHashSet<>();
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs != null) {
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) continue;
                if (p.pkgList == null) continue;
                for (String pkg : p.pkgList) {
                    if (keep.contains(pkg)) continue;
                    if (skipSystem && isSystem(pm, pkg)) continue;
                    kill.add(pkg);
                }
            }
        }

        int n = 0;
        for (String pkg : kill) {
            try {
                am.killBackgroundProcesses(pkg);
                n++;
            } catch (Throwable t) {
                try {
                    XposedHelpers.callMethod(am, "forceStopPackage", pkg);
                    n++;
                } catch (Throwable t2) {
                    XposedBridge.log(SysUiHook.TAG + "không đóng được " + pkg + ": " + t2);
                }
            }
        }
        final int count = n;
        XposedBridge.log(SysUiHook.TAG + "đóng ứng dụng nền: " + count + "/" + kill.size());
        SysUiHook.UI.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(ctx, "Đã đóng " + count + " ứng dụng nền", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static boolean isSystem(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (Throwable t) {
            return true;
        }
    }
}
