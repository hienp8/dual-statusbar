package com.dualsb.oneui;

import android.content.Context;
import android.content.Intent;
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
import androidx.preference.PreferenceCategory;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeSet;
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

            Preference lwManage = findPreference("lw_manage");
            if (lwManage != null) lwManage.setOnPreferenceClickListener(p -> {
                startActivity(new Intent(requireContext(), LockWidgetsActivity.class));
                return true;
            });

            MultiSelectListPreference ls = findPreference("ls_selected");
            if (ls != null) { refreshLock(ls); buildLockItems(); }
            Preference lsReset = findPreference("ls_reset");
            if (lsReset != null) lsReset.setOnPreferenceClickListener(p -> {
                SharedPreferences.Editor ed = prefs().edit();
                for (String k : new ArrayList<>(prefs().getAll().keySet()))
                    if (k.startsWith("ls_dx_") || k.startsWith("ls_dy_")) ed.remove(k);
                ed.putStringSet("ls_selected", new HashSet<String>());
                ed.apply();
                MultiSelectListPreference m = findPreference("ls_selected");
                if (m != null) m.setValues(new HashSet<String>());
                buildLockItems();
                Toast.makeText(getContext(), "Đã đặt lại", Toast.LENGTH_SHORT).show();
                return true;
            });

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

        private static final Map<String, String> LOCK_LABELS = new HashMap<>();
        static {
            LOCK_LABELS.put("lockscreen_clock_view", "Đồng hồ (nhỏ)");
            LOCK_LABELS.put("lockscreen_clock_view_large", "Đồng hồ (lớn)");
            LOCK_LABELS.put("keyguard_clock_container", "Khung đồng hồ");
            LOCK_LABELS.put("keyguard_slice_view", "Ngày / thông tin dưới đồng hồ");
            LOCK_LABELS.put("keyguard_status_area", "Khu vực trạng thái dưới đồng hồ");
            LOCK_LABELS.put("lock_icon_view", "Ổ khóa");
            LOCK_LABELS.put("lock_icon", "Ổ khóa");
            LOCK_LABELS.put("keyguard_indication_area", "Dòng gợi ý (vân tay, sạc...)");
            LOCK_LABELS.put("keyguard_indication_text", "Chữ gợi ý");
            LOCK_LABELS.put("keyguard_bottom_area", "Khu vực phím tắt phía dưới");
            LOCK_LABELS.put("notification_stack_scroller", "Danh sách thông báo");
            LOCK_LABELS.put("notification_container_parent", "Khung thông báo");
            LOCK_LABELS.put("notification_panel", "Bảng thông báo");
            LOCK_LABELS.put("media_container", "Trình phát nhạc");
        }

        private static String lockLabel(String id) {
            return LockLabels.label(id);
        }

        /** Danh sách mục màn hình khóa đã được SystemUI báo về. */
        private void refreshLock(MultiSelectListPreference ms) {
            Map<String, String> m = new java.util.TreeMap<>();
            Set<String> known = prefs().getStringSet("known_ls", null);
            if (known != null) for (String k : known) m.put(k, lockLabel(k));
            Set<String> sel = prefs().getStringSet("ls_selected", null);
            if (sel != null) for (String k : sel) if (!m.containsKey(k)) m.put(k, lockLabel(k));
            ms.setEntries(m.values().toArray(new CharSequence[0]));
            ms.setEntryValues(m.keySet().toArray(new CharSequence[0]));
        }

        /** Tạo hai thanh trượt (ngang, dọc) cho từng mục đã chọn. */
        private void buildLockItems() {
            PreferenceCategory cat = findPreference("ls_dynamic");
            if (cat == null) return;
            cat.removeAll();
            Set<String> sel = prefs().getStringSet("ls_selected", null);
            if (sel == null || sel.isEmpty()) return;
            for (String id : new TreeSet<>(sel)) {
                cat.addPreference(lockSlider("ls_dx_" + id, lockLabel(id) + " — ngang (dp)"));
                cat.addPreference(lockSlider("ls_dy_" + id, lockLabel(id) + " — dọc (dp)"));
            }
        }

        private NumberSeekBarPreference lockSlider(String key, String title) {
            NumberSeekBarPreference p = new NumberSeekBarPreference(requireContext());
            p.setKey(key);
            p.setTitle(title);
            p.setMin(-500);
            p.setMax(500);
            p.setShowSeekBarValue(true);
            p.setDefaultValue(0);
            p.setIconSpaceReserved(false);
            return p;
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

        /** Những khoá chỉ là trạng thái tạm / theo máy: không sao lưu, không khôi phục. */
        private static final Set<String> TRANSIENT = new HashSet<>(Arrays.asList(
                "lw_edit", "edge_preview", "edge_preview_ts", "known_slots", "known_ls"));

        private static boolean isWidgetImage(String base) {
            return base.matches("lw_[a-z0-9]{1,16}");
        }

        private void exportBackup(Uri uri) {
            Context c = requireContext();
            try (OutputStream os = c.getContentResolver().openOutputStream(uri);
                 ZipOutputStream z = new ZipOutputStream(os)) {
                JSONObject o = new JSONObject();
                for (Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
                    if (TRANSIENT.contains(e.getKey())) continue;
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
                File[] files = Storage.dir(c).listFiles();                 // ảnh cố định + ảnh của các tiện ích màn hình khóa
                if (files != null) {
                    for (File f : files) {
                        String fn = f.getName();
                        if (!fn.endsWith(".png")) continue;
                        String base = fn.substring(0, fn.length() - 4);
                        if (!Arrays.asList(Config.IMAGES).contains(base) && !isWidgetImage(base)) continue;
                        z.putNextEntry(new ZipEntry(fn));
                        try (FileInputStream in = new FileInputStream(f)) {
                            byte[] buf = new byte[8192];
                            int r;
                            while ((r = in.read(buf)) > 0) z.write(buf, 0, r);
                        }
                        z.closeEntry();
                    }
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
                        if (known.contains(base) || isWidgetImage(base)) imgs.put(base, bos.toByteArray());
                    }
                }
                if (json == null) throw new IOException("Không phải file sao lưu hợp lệ");

                // ghi ảnh trước, rồi ghi cài đặt một lần duy nhất (không nháy từng khoá, không xoá ảnh đang có)
                for (Map.Entry<String, byte[]> im : imgs.entrySet()) {
                    File f = new File(Storage.dir(c), im.getKey() + ".png");
                    try (FileOutputStream out = new FileOutputStream(f)) { out.write(im.getValue()); }
                    f.setLastModified(System.currentTimeMillis());
                }

                SharedPreferences sp = prefs();
                Map<String, ?> cur = sp.getAll();
                sp.unregisterOnSharedPreferenceChangeListener(this);
                SharedPreferences.Editor ed = sp.edit();
                Iterator<String> it = json.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (TRANSIENT.contains(k)) continue;
                    Object v = json.get(k);
                    Object old = cur.get(k);                           // giữ đúng kiểu dữ liệu của cài đặt hiện có
                    if (v instanceof Boolean) {
                        ed.putBoolean(k, (Boolean) v);
                    } else if (v instanceof Number) {
                        Number nv = (Number) v;
                        if (old instanceof Long) ed.putLong(k, nv.longValue());
                        else if (old instanceof String) ed.putString(k, String.valueOf(nv.longValue()));
                        else ed.putInt(k, nv.intValue());
                    } else if (v instanceof String) {
                        String sv = (String) v;
                        if (old instanceof Integer) {
                            try { ed.putInt(k, Integer.parseInt(sv.trim())); } catch (NumberFormatException ex) { ed.putString(k, sv); }
                        } else {
                            ed.putString(k, sv);
                        }
                    } else if (v instanceof JSONArray) {
                        Set<String> set = new HashSet<>();
                        JSONArray a = (JSONArray) v;
                        for (int i = 0; i < a.length(); i++) set.add(a.getString(i));
                        ed.putStringSet(k, set);
                    }
                }
                ed.putBoolean("lw_edit", false);                       // khôi phục xong không tự bật chế độ chỉnh
                boolean ok = ed.commit();                              // ghi ngay xuống đĩa, không để bị mất khi tiến trình bị đóng băng
                sp.registerOnSharedPreferenceChangeListener(this);
                if (!ok) throw new IOException("Không ghi được cài đặt");
                notifyChanged(c);
                Toast.makeText(c, "Đã khôi phục", Toast.LENGTH_SHORT).show();
                requireActivity().recreate();
            } catch (Exception e) {
                try { prefs().registerOnSharedPreferenceChangeListener(this); } catch (Throwable ignored) {}
                Toast.makeText(c, "Khôi phục lỗi: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        /** Đang ở trong mục phím cử chỉ (hoặc mục con của nó). */
        private boolean isEdgeScreen() {
            Bundle a = getArguments();
            String rk = a == null ? null : a.getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT);
            return rk != null && rk.startsWith("scr_edge");
        }

        /** Bật / tắt vùng xám nhạt trên màn hình để canh vị trí dải cử chỉ. */
        private void setPreview(boolean on) {
            prefs().edit().putBoolean(Config.EDGE_PREVIEW, on)
                    .putLong(Config.EDGE_PREVIEW_TS, System.currentTimeMillis()).apply();
        }

        static void notifyChanged(Context c) {
            c.getContentResolver().notifyChange(Uri.parse(Config.CONFIG_URI), null);
        }

        @Override public void onResume() {
            super.onResume();
            PreferenceScreen ps = getPreferenceScreen();
            if (ps != null && ps.getTitle() != null) requireActivity().setTitle(ps.getTitle());
            prefs().registerOnSharedPreferenceChangeListener(this);
            if (isEdgeScreen()) setPreview(true);
        }
        @Override public void onPause() {
            super.onPause();
            if (isEdgeScreen()) setPreview(false);
            prefs().unregisterOnSharedPreferenceChangeListener(this);
        }
        @Override public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            if ("known_slots".equals(key) || "known_ls".equals(key)) return;
            if ("ls_selected".equals(key)) buildLockItems();
            if (key != null && key.startsWith("edge_") && !Config.EDGE_PREVIEW.equals(key)
                    && !Config.EDGE_PREVIEW_TS.equals(key) && isEdgeScreen()) {
                sp.edit().putLong(Config.EDGE_PREVIEW_TS, System.currentTimeMillis()).apply();   // giữ vùng xem trước
            }
            notifyChanged(requireContext());
        }
    }
}
