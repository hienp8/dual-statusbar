package com.dualsb.oneui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Kho cài đặt + ảnh, nằm ở bộ nhớ "device protected" để SystemUI đọc được ngay khi máy vừa khởi động.
 *
 * - MỌI nơi (giao diện, khôi phục, provider cho SystemUI) dùng chung MỘT đối tượng SharedPreferences duy nhất,
 *   tránh trường hợp mỗi nơi giữ một bản riêng rồi ghi đè lên nhau.
 * - Mỗi thay đổi được chụp lại vào file config_snapshot.json (kèm số phiên bản). Nếu về sau dữ liệu cài đặt bị mất /
 *   quay về mặc định (cũ hơn bản chụp), tự nạp lại từ bản chụp khi ứng dụng khởi động.
 */
final class Storage {
    private Storage() {}

    private static SharedPreferences prefs;
    private static boolean inited, defaultsDone;
    private static SharedPreferences.OnSharedPreferenceChangeListener lsn;     // giữ tham chiếu mạnh
    private static Runnable snapTask;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final String REV = "cfg_rev";
    /** Trạng thái tạm / theo máy: không đưa vào bản chụp. */
    private static final Set<String> NOSNAP = new HashSet<>(Arrays.asList(
            "lw_edit", "edge_preview", "edge_preview_ts", "known_slots", "known_ls", REV));

    static Context dp(Context c) {
        return c.isDeviceProtectedStorage() ? c : c.createDeviceProtectedStorageContext();
    }

    static String prefsName(Context c) { return c.getPackageName() + "_preferences"; }

    /** Đối tượng SharedPreferences duy nhất của cả ứng dụng. */
    static synchronized SharedPreferences prefs(Context c) {
        if (prefs == null) {
            Context base = c.getApplicationContext() != null ? c.getApplicationContext() : c;
            prefs = dp(base).getSharedPreferences(prefsName(base), Context.MODE_PRIVATE);
        }
        return prefs;
    }

    static File dir(Context c) { return dp(c).getFilesDir(); }

    /** Gọi ở mọi điểm vào (provider, màn hình cài đặt...): chuyển dữ liệu cũ, tự phục hồi nếu mất, bật theo dõi. */
    static synchronized void init(Context c) {
        if (inited) return;
        inited = true;
        try { migrate(c); } catch (Throwable ignored) {}
        try { heal(c); } catch (Throwable ignored) {}
        try { watch(c); } catch (Throwable ignored) {}
    }

    /** Ghi các giá trị mặc định trong prefs.xml (chỉ cho khoá chưa có). Cần Context có giao diện (Activity). */
    static synchronized void ensureDefaults(Context c) {
        if (defaultsDone) return;
        try {
            PreferenceManager.setDefaultValues(dp(c), prefsName(c), Context.MODE_PRIVATE, R.xml.prefs, false);
            defaultsDone = true;
        } catch (Throwable ignored) {}
    }

    /** Chuyển cài đặt + ảnh từ bộ nhớ thường (bản cũ) sang bộ nhớ device protected. */
    private static void migrate(Context c) {
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

    // ------------------------------------------------------------------ bản chụp dự phòng
    private static File snapFile(Context c) { return new File(dir(c), "config_snapshot.json"); }

    private static void watch(Context c) {
        if (lsn != null) return;
        final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        final SharedPreferences sp = prefs(c);
        snapTask = new Runnable() { @Override public void run() { snapshotNow(app); } };
        lsn = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                if (key == null || NOSNAP.contains(key)) return;
                H.removeCallbacks(snapTask);
                H.postDelayed(snapTask, 1200);                     // gom nhiều thay đổi liên tiếp thành một lần chụp
            }
        };
        sp.registerOnSharedPreferenceChangeListener(lsn);
        H.postDelayed(snapTask, 4000);                              // chụp lần đầu
    }

    private static void snapshotNow(Context c) {
        try {
            SharedPreferences sp = prefs(c);
            int rev = sp.getInt(REV, 0) + 1;
            JSONObject v = new JSONObject();
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                String k = e.getKey();
                if (NOSNAP.contains(k)) continue;
                Object x = e.getValue();
                JSONArray t = new JSONArray();
                if (x instanceof Boolean) { t.put("b"); t.put(((Boolean) x).booleanValue()); }
                else if (x instanceof Integer) { t.put("i"); t.put(((Integer) x).intValue()); }
                else if (x instanceof Long) { t.put("l"); t.put(((Long) x).longValue()); }
                else if (x instanceof Float) { t.put("f"); t.put(((Float) x).doubleValue()); }
                else if (x instanceof String) { t.put("s"); t.put((String) x); }
                else if (x instanceof Set) {
                    t.put("a");
                    JSONArray a = new JSONArray();
                    for (Object s : (Set<?>) x) a.put(String.valueOf(s));
                    t.put(a);
                } else continue;
                v.put(k, t);
            }
            JSONObject o = new JSONObject();
            o.put("rev", rev);
            o.put("v", v);
            File snap = snapFile(c), tmp = new File(dir(c), "config_snapshot.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes("UTF-8"));
                out.getFD().sync();
            }
            if (!tmp.renameTo(snap)) { snap.delete(); tmp.renameTo(snap); }
            sp.edit().putInt(REV, rev).apply();
        } catch (Throwable ignored) {}
    }

    /** Nếu bản chụp mới hơn dữ liệu hiện có thì dữ liệu hiện có đã bị mất / lùi về cũ: nạp lại từ bản chụp. */
    private static void heal(Context c) throws Exception {
        File snap = snapFile(c);
        if (!snap.exists()) return;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(snap)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        }
        JSONObject o = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
        int srev = o.optInt("rev", 0);
        SharedPreferences sp = prefs(c);
        if (srev <= sp.getInt(REV, 0)) return;
        JSONObject v = o.getJSONObject("v");
        SharedPreferences.Editor ed = sp.edit();
        Iterator<String> it = v.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONArray t = v.getJSONArray(k);
            switch (t.getString(0)) {
                case "b": ed.putBoolean(k, t.getBoolean(1)); break;
                case "i": ed.putInt(k, t.getInt(1)); break;
                case "l": ed.putLong(k, t.getLong(1)); break;
                case "f": ed.putFloat(k, (float) t.getDouble(1)); break;
                case "s": ed.putString(k, t.getString(1)); break;
                case "a": {
                    Set<String> set = new HashSet<>();
                    JSONArray a = t.getJSONArray(1);
                    for (int i = 0; i < a.length(); i++) set.add(a.getString(i));
                    ed.putStringSet(k, set);
                    break;
                }
                default: break;
            }
        }
        ed.putInt(REV, srev);
        ed.commit();
    }
}
