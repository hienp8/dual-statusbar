package com.dualsb.oneui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Cài đặt + ảnh lưu ở bộ nhớ "device protected" để SystemUI đọc được ngay khi máy vừa khởi động
 * (trước khi mở khoá lần đầu). Lưu ở bộ nhớ thường thì sau reboot SystemUI không đọc được -> rơi về mặc định.
 */
final class Storage {
    private Storage() {}

    static Context dp(Context c) {
        return c.isDeviceProtectedStorage() ? c : c.createDeviceProtectedStorageContext();
    }

    static String prefsName(Context c) { return c.getPackageName() + "_preferences"; }

    static SharedPreferences prefs(Context c) {
        return dp(c).getSharedPreferences(prefsName(c), Context.MODE_PRIVATE);
    }

    static File dir(Context c) { return dp(c).getFilesDir(); }

    /** Chuyển cài đặt + ảnh từ bộ nhớ thường (bản cũ) sang bộ nhớ device protected. */
    static void migrate(Context c) {
        try {
            UserManager um = c.getSystemService(UserManager.class);
            if (um != null && !um.isUserUnlocked()) return;
            Context dp = dp(c);
            if (dp == c) return;
            String name = prefsName(c);
            File src = new File(c.getDataDir(), "shared_prefs/" + name + ".xml");
            File dst = new File(dp.getDataDir(), "shared_prefs/" + name + ".xml");
            if (src.exists() && !dst.exists()) dp.moveSharedPreferencesFrom(c, name);
            for (String n : Config.IMAGES) {
                File s = new File(c.getFilesDir(), n + ".png");
                File d = new File(dp.getFilesDir(), n + ".png");
                if (s.exists() && !d.exists()) {
                    try (FileInputStream in = new FileInputStream(s); FileOutputStream out = new FileOutputStream(d)) {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    }
                    d.setLastModified(s.lastModified());
                    s.delete();
                }
            }
        } catch (Throwable ignored) {}
    }
}
