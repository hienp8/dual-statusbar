package com.dualsb.oneui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** SystemUI đọc cấu hình + ảnh qua provider này; đồng thời báo lên danh sách biểu tượng đã thấy. */
public class ConfigProvider extends ContentProvider {
    @Override public boolean onCreate() {
        try { Storage.migrate(getContext()); } catch (Throwable ignored) {}
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        SharedPreferences sp = Storage.prefs(getContext());
        if ("set_prefs".equals(method)) {                          // SystemUI lưu vị trí sau khi người dùng kéo thả
            if (extras != null) {
                SharedPreferences.Editor ed = sp.edit();
                for (String key : extras.keySet()) {
                    String val = extras.getString(key);
                    if (val == null || !allowed(key)) continue;
                    if (key.equals("lw_edit") || key.equals("lw_enabled") || key.equals("ls_enabled")) {
                        ed.putBoolean(key, Boolean.parseBoolean(val));
                    } else if (key.equals("ls_selected")) {
                        Set<String> set = new HashSet<>();
                        for (String p : val.split(",")) if (!p.trim().isEmpty()) set.add(p.trim());
                        ed.putStringSet(key, set);
                    } else if (key.startsWith("ls_dx_") || key.startsWith("ls_dy_")) {
                        try { ed.putInt(key, Integer.parseInt(val.trim())); } catch (NumberFormatException ignored) {}
                    } else {
                        ed.putString(key, val);
                    }
                }
                ed.apply();
                getContext().getContentResolver().notifyChange(Uri.parse(Config.CONFIG_URI), null);
            }
            return new Bundle();
        }
        if ("report_slots".equals(method) || "report_ls".equals(method)) {
            String setKey = "report_ls".equals(method) ? "known_ls" : "known_slots";
            if (arg != null && !arg.isEmpty()) {
                Set<String> cur = new HashSet<>(sp.getStringSet(setKey, new HashSet<String>()));
                boolean changed = false;
                for (String s : arg.split(",")) {
                    s = s.trim();
                    if (!s.isEmpty() && cur.add(s)) changed = true;
                }
                if (changed) sp.edit().putStringSet(setKey, cur).apply();
            }
            return new Bundle();
        }
        Bundle out = new Bundle();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) out.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof Integer) out.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Long) out.putLong(e.getKey(), (Long) v);
            else if (v instanceof String) out.putString(e.getKey(), (String) v);
            else if (v instanceof Set) out.putString(e.getKey(), TextUtils.join(",", (Set<?>) v));
        }
        for (String n : Config.IMAGES) {
            File f = new File(Storage.dir(getContext()), n + ".png");
            out.putLong("img_" + n, f.exists() ? Math.max(1, f.lastModified()) : 0L);
        }
        File[] all = Storage.dir(getContext()).listFiles();            // ảnh của các tiện ích màn hình khóa
        if (all != null) {
            for (File f : all) {
                String fn = f.getName();
                if (fn.matches("lw_[a-z0-9]{1,16}\\.png")) {
                    String base = fn.substring(0, fn.length() - 4);
                    out.putLong("img_" + base, Math.max(1, f.lastModified()));
                }
            }
        }
        return out;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !(Arrays.asList(Config.IMAGES).contains(name) || name.matches("lw_[a-z0-9]{1,16}")))
            throw new FileNotFoundException();
        File f = new File(Storage.dir(getContext()), name + ".png");
        if (!f.exists()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** Khoá mà SystemUI được phép ghi. */
    private static boolean allowed(String key) {
        return key.equals("lw_edit") || key.equals("lw_enabled") || key.equals("lw_items")
                || key.equals("ls_selected") || key.equals("ls_enabled")
                || key.startsWith("ls_dx_") || key.startsWith("ls_dy_") || key.startsWith("ls_grp_");
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
