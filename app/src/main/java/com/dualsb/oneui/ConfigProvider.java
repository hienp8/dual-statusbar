package com.dualsb.oneui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.Map;

/** SystemUI đọc cấu hình + ảnh qua provider này (SystemUI không đọc được thư mục private của app). */
public class ConfigProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        SharedPreferences sp = getContext().getSharedPreferences(
                getContext().getPackageName() + "_preferences", 0);
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) out.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof Integer) out.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Long) out.putLong(e.getKey(), (Long) v);
            else if (v instanceof String) out.putString(e.getKey(), (String) v);
        }
        for (String n : Config.IMAGES) {
            File f = new File(getContext().getFilesDir(), n + ".png");
            out.putLong("img_" + n, f.exists() ? Math.max(1, f.lastModified()) : 0L);
        }
        return out;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !Arrays.asList(Config.IMAGES).contains(name)) throw new FileNotFoundException();
        File f = new File(getContext().getFilesDir(), name + ".png");
        if (!f.exists()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
