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
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class SettingsActivity extends AppCompatActivity
        implements PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        Storage.migrate(this);
        PreferenceManager.setDefaultValues(Storage.dp(this), Storage.prefsName(this), Context.MODE_PRIVATE,
                R.xml.prefs, false);
        if (s == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new SettingsFragment()).commit();
        }
        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            ActionBar ab = getSupportActionBar();
            if (ab != null) ab.setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount() > 0);
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (getSupportFragmentManager().popBackStackImmediate()) return true;
        return super.onSupportNavigateUp();
    }

    /** Bấm vào một mục con (PreferenceScreen lồng) thì mới mở trang của mục đó. */
    @Override
    public boolean onPreferenceStartScreen(PreferenceFragmentCompat caller, PreferenceScreen pref) {
        SettingsFragment f = new SettingsFragment();
        Bundle args = new Bundle();
        args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.getKey());
        f.setArguments(args);
        getSupportFragmentManager().beginTransaction()
                .replace(android.R.id.content, f, pref.getKey())
                .addToBackStack(pref.getKey())
                .commit();
        return true;
    }

    public static class SettingsFragment extends PreferenceFragmentCompat
            implements SharedPreferences.OnSharedPreferenceChangeListener {

        private String pending;
        private final ActivityResultLauncher<String[]> picker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null && pending != null) save(uri, pending);
                });
        private final ActivityResultLauncher<String> exporter = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
                    if (uri != null) exportBackup(uri);
                });
        private final ActivityResultLauncher<String[]> importer = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) importBackup(uri);
                });

        @Override
        public void onCreatePreferences(Bundle b, String rootKey) {
            getPreferenceManager().setStorageDeviceProtected();   // cùng nơi SystemUI đọc, dùng được ngay sau reboot
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
                    new File(Storage.dir(requireContext()), n + ".png").delete();
                    notifyChanged(requireContext());
                    Toast.makeText(getContext(), "Đã xoá", Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
            MultiSelectListPreference ms = findPreference("hidden_slots");
            if (ms != null) refreshSlots(ms);

            Preference ex = findPreference("backup_export");
            if (ex != null) ex.setOnPreferenceClickListener(p -> {
                exporter.launch("dualsb_backup.zip");
                return true;
            });
            Preference im = findPreference("backup_import");
            if (im != null) im.setOnPreferenceClickListener(p -> {
                importer.launch(new String[]{"application/zip", "application/octet-stream", "*/*"});
                return true;
            });
        }

        /** Gộp danh sách biểu tượng mặc định với những biểu tượng SystemUI đã báo về. */
        private void refreshSlots(MultiSelectListPreference ms) {
            Map<String, String> m = new LinkedHashMap<>();
            CharSequence[] ev = ms.getEntryValues(), en = ms.getEntries();
            if (ev != null)
                for (int i = 0; i < ev.length; i++)
                    m.put(ev[i].toString(), en != null && i < en.length ? en[i].toString() : ev[i].toString());
            Set<String> known = prefs().getStringSet("known_slots", null);
            if (known != null) for (String k : known) if (!m.containsKey(k)) m.put(k, k);
            ms.setEntries(m.values().toArray(new CharSequence[0]));
            ms.setEntryValues(m.keySet().toArray(new CharSequence[0]));
        }

        // ------------------------------------------------------------------ ảnh

        private void save(Uri uri, String name) {
            Context c = requireContext();
            try {
                if (name.startsWith("nav_")) {                 // giữ nguyên file gốc để GIF vẫn chuyển động
                    File out = new File(Storage.dir(c), name + ".png");
                    long total = 0;
                    try (InputStream in = c.getContentResolver().openInputStream(uri);
                         FileOutputStream fo = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            total += r;
                            if (total > 12L * 1024 * 1024) throw new IOException("Ảnh quá lớn (tối đa 12 MB)");
                            fo.write(buf, 0, r);
                        }
                    }
                    out.setLastModified(System.currentTimeMillis());
                    notifyChanged(c);
                    Toast.makeText(c, "Đã lưu ảnh", Toast.LENGTH_SHORT).show();
                    return;
                }
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
                File f = new File(Storage.dir(c), name + ".png");
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

        // ------------------------------------------------------------------ sao lưu / khôi phục

        private SharedPreferences prefs() {
            return getPreferenceManager().getSharedPreferences();
        }

        private void exportBackup(Uri uri) {
            Context c = requireContext();
            try (OutputStream os = c.getContentResolver().openOutputStream(uri);
                 ZipOutputStream z = new ZipOutputStream(os)) {
                JSONObject o = new JSONObject();
                for (Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Set) {
                        JSONArray a = new JSONArray();
                        for (Object x : (Set<?>) v) a.put(x);
                        o.put(e.getKey(), a);
                    } else {
                        o.put(e.getKey(), v);
                    }
                }
                z.putNextEntry(new ZipEntry("settings.json"));
                z.write(o.toString(2).getBytes("UTF-8"));
                z.closeEntry();
                for (String n : Config.IMAGES) {
                    File f = new File(Storage.dir(c), n + ".png");
                    if (!f.exists()) continue;
                    z.putNextEntry(new ZipEntry(n + ".png"));
                    try (FileInputStream in = new FileInputStream(f)) {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) > 0) z.write(buf, 0, r);
                    }
                    z.closeEntry();
                }
                z.finish();
                Toast.makeText(c, "Đã sao lưu", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(c, "Sao lưu lỗi: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        private void importBackup(Uri uri) {
            Context c = requireContext();
            try (InputStream is = c.getContentResolver().openInputStream(uri);
                 ZipInputStream z = new ZipInputStream(is)) {
                JSONObject json = null;
                Map<String, byte[]> imgs = new HashMap<>();
                List<String> known = Arrays.asList(Config.IMAGES);
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = z.read(buf)) > 0) bos.write(buf, 0, r);
                    String name = e.getName();
                    if (name.equals("settings.json")) {
                        json = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
                    } else if (name.endsWith(".png")) {
                        String base = name.substring(0, name.length() - 4);
                        if (known.contains(base)) imgs.put(base, bos.toByteArray());
                    }
                }
                if (json == null) throw new IOException("Không phải file sao lưu hợp lệ");

                SharedPreferences.Editor ed = prefs().edit();
                Iterator<String> it = json.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Object v = json.get(k);
                    if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
                    else if (v instanceof Number) ed.putInt(k, ((Number) v).intValue());
                    else if (v instanceof String) ed.putString(k, (String) v);
                    else if (v instanceof JSONArray) {
                        Set<String> set = new HashSet<>();
                        JSONArray a = (JSONArray) v;
                        for (int i = 0; i < a.length(); i++) set.add(a.getString(i));
                        ed.putStringSet(k, set);
                    }
                }
                ed.apply();

                for (String n : Config.IMAGES) {
                    File f = new File(Storage.dir(c), n + ".png");
                    byte[] d = imgs.get(n);
                    if (d != null) {
                        try (FileOutputStream out = new FileOutputStream(f)) { out.write(d); }
                        f.setLastModified(System.currentTimeMillis());
                    } else {
                        f.delete();
                    }
                }
                notifyChanged(c);
                Toast.makeText(c, "Đã khôi phục", Toast.LENGTH_SHORT).show();
                requireActivity().recreate();
            } catch (Exception e) {
                Toast.makeText(c, "Khôi phục lỗi: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        static void notifyChanged(Context c) {
            c.getContentResolver().notifyChange(Uri.parse(Config.CONFIG_URI), null);
        }

        @Override public void onResume() {
            super.onResume();
            PreferenceScreen ps = getPreferenceScreen();
            if (ps != null && ps.getTitle() != null) requireActivity().setTitle(ps.getTitle());
            prefs().registerOnSharedPreferenceChangeListener(this);
        }
        @Override public void onPause() {
            super.onPause();
            prefs().unregisterOnSharedPreferenceChangeListener(this);
        }
        @Override public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            if ("known_slots".equals(key)) return;
            notifyChanged(requireContext());
        }
    }
}
