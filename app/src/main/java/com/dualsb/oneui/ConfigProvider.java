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
        if ("report_slots".equals(method)) {
            if (arg != null && !arg.isEmpty()) {
                Set<String> cur = new HashSet<>(sp.getStringSet("known_slots", new HashSet<String>()));
                boolean changed = false;
                for (String s : arg.split(",")) {
                    s = s.trim();
                    if (!s.isEmpty() && cur.add(s)) changed = true;
                }
                if (changed) sp.edit().putStringSet("known_slots", cur).apply();
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
        return out;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !Arrays.asList(Config.IMAGES).contains(name)) throw new FileNotFoundException();
        File f = new File(Storage.dir(getContext()), name + ".png");
        if (!f.exists()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
