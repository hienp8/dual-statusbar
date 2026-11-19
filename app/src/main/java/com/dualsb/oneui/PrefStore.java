package com.dualsb.oneui;

import android.content.SharedPreferences;

import androidx.preference.PreferenceDataStore;

import java.util.Set;

/**
 * Cho giao diện cài đặt (androidx.preference) đọc / ghi THẲNG vào đối tượng SharedPreferences duy nhất của Storage,
 * không tự chọn nơi lưu riêng. Nhờ vậy giao diện, nút khôi phục và provider (SystemUI đọc) luôn dùng chung một dữ liệu.
 */
final class PrefStore extends PreferenceDataStore {
    private final SharedPreferences sp;

    PrefStore(SharedPreferences sp) { this.sp = sp; }

    @Override public void putString(String key, String value) { sp.edit().putString(key, value).apply(); }
    @Override public void putStringSet(String key, Set<String> values) { sp.edit().putStringSet(key, values).apply(); }
    @Override public void putInt(String key, int value) { sp.edit().putInt(key, value).apply(); }
    @Override public void putLong(String key, long value) { sp.edit().putLong(key, value).apply(); }
    @Override public void putFloat(String key, float value) { sp.edit().putFloat(key, value).apply(); }
    @Override public void putBoolean(String key, boolean value) { sp.edit().putBoolean(key, value).apply(); }

    @Override public String getString(String key, String def) {
        try { return sp.getString(key, def); } catch (ClassCastException e) { return def; }
    }
    @Override public Set<String> getStringSet(String key, Set<String> def) {
        try { return sp.getStringSet(key, def); } catch (ClassCastException e) { return def; }
    }
    @Override public int getInt(String key, int def) {
        try { return sp.getInt(key, def); } catch (ClassCastException e) { return def; }
    }
    @Override public long getLong(String key, long def) {
        try { return sp.getLong(key, def); } catch (ClassCastException e) { return def; }
    }
    @Override public float getFloat(String key, float def) {
        try { return sp.getFloat(key, def); } catch (ClassCastException e) { return def; }
    }
    @Override public boolean getBoolean(String key, boolean def) {
        try { return sp.getBoolean(key, def); } catch (ClassCastException e) { return def; }
    }
}
