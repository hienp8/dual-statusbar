package com.dualsb.oneui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        PreferenceManager.setDefaultValues(this, R.xml.prefs, false);
        if (s == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new SettingsFragment()).commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat
            implements SharedPreferences.OnSharedPreferenceChangeListener {

        private String pending;
        private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null && pending != null) save(uri, pending);
                });

        @Override
        public void onCreatePreferences(Bundle b, String rootKey) {
            setPreferencesFromResource(R.xml.prefs, rootKey);
            for (final String n : Config.IMAGES) {
                Preference pick = findPreference("pick_" + n);
                if (pick != null) pick.setOnPreferenceClickListener(p -> {
                    pending = n;
                    picker.launch(new String[]{"image/*"});
                    return true;
                });
                Preference clear = findPreference("clear_" + n);
                if (clear != null) clear.setOnPreferenceClickListener(p -> {
                    new File(requireContext().getFilesDir(), n + ".png").delete();
                    notifyChanged(requireContext());
                    Toast.makeText(getContext(), "Đã xoá", Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
        }

        private void save(Uri uri, String name) {
            Context c = requireContext();
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                int s = 1;
                while (o.outWidth / s > 1600 || o.outHeight / s > 1600) s *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = s;
                Bitmap bm;
                try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                    bm = BitmapFactory.decodeStream(in, null, o2);
                }
                if (bm == null) throw new IOException("decode failed");
                File f = new File(c.getFilesDir(), name + ".png");
                try (FileOutputStream out = new FileOutputStream(f)) {
                    bm.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                f.setLastModified(System.currentTimeMillis());
                notifyChanged(c);
                Toast.makeText(c, "Đã lưu ảnh", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(c, "Lỗi đọc ảnh: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        static void notifyChanged(Context c) {
            c.getContentResolver().notifyChange(Uri.parse(Config.CONFIG_URI), null);
        }

        @Override public void onResume() {
            super.onResume();
            getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        }
        @Override public void onPause() {
            super.onPause();
            getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        }
        @Override public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            notifyChanged(requireContext());
        }
    }
}
