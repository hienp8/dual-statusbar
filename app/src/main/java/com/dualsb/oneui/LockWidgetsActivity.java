package com.dualsb.oneui;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Quản lý các tiện ích thêm vào màn hình khóa: thêm / sửa / xoá. Dữ liệu lưu thành JSON trong cài đặt (lw_items). */
public class LockWidgetsActivity extends AppCompatActivity {

    private static final String[] TYPES = {"image", "text", "clock_d", "clock_a", "app", "effect"};
    private static final String[] TYPE_NAMES = {
            "Ảnh / GIF động", "Chữ", "Đồng hồ số (giờ, dương lịch, âm lịch)", "Đồng hồ analog",
            "Ứng dụng (biểu tượng, chạm để mở)", "Hiệu ứng toàn màn hình (tuyết, mưa, sao, bong bóng)"};
    private static final String[] COLOR_NAMES = {"Tự động (theo hệ thống)", "Trắng", "Đen", "Đỏ", "Vàng", "Xanh lá", "Xanh dương", "Cam"};
    private static final String[] COLOR_VALUES = {"auto", "#FFFFFFFF", "#FF000000", "#FFE53935", "#FFFFC107", "#FF4CAF50", "#FF2196F3", "#FFFF9800"};
    private static final String[] FX_NAMES = {"Tuyết rơi", "Mưa", "Sao lấp lánh", "Bong bóng"};
    private static final String[] FX_VALUES = {"snow", "rain", "star", "bubble"};
    private static final String[] ACT_NAMES = {"Không làm gì", "Mở ứng dụng (chọn ở dưới)", "Quay lại", "Về màn hình chính",
            "Ứng dụng gần đây", "Mở thanh thông báo", "Mở cài đặt nhanh", "Khoá màn hình", "Chụp màn hình",
            "Dọn RAM (tối ưu hệ thống)", "Tăng âm lượng", "Giảm âm lượng", "Tắt / bật tiếng", "Bật / tắt đèn pin"};
    private static final int[] ACT_VALUES = {0, 100, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    private final List<JSONObject> items = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ActivityResultLauncher<String[]> picker;
    private String pendingImageId;
    private TextView activeInfo;
    private String[] appLabels, appPkgs;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setTitle("Tiện ích màn hình khóa");
        picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null && pendingImageId != null) saveImage(uri, pendingImageId);
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView hint = new TextView(this);
        hint.setText("Thêm ảnh, GIF, chữ, đồng hồ, nút ứng dụng hoặc hiệu ứng lên màn hình khóa.\n"
                + "Bật \"Bật tiện ích trên màn hình khóa\" trong menu Màn hình khóa. Muốn kéo thả vị trí: "
                + "bật \"Chế độ chỉnh trực tiếp\" rồi khoá máy.\nBấm vào một mục để sửa, nhấn giữ để xoá.");
        hint.setPadding(0, 0, 0, dp(8));
        Button add = new Button(this);
        add.setText("＋ Thêm tiện ích");
        add.setOnClickListener(v -> addDialog());
        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> edit(pos));
        list.setOnItemLongClickListener((p, v, pos, id) -> { confirmDelete(pos); return true; });
        root.addView(hint);
        root.addView(add);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        load();
        refresh();
    }

    /** Vị trí có thể vừa được kéo thả trên màn hình khóa (SystemUI ghi lại): luôn đọc lại, tránh ghi đè bằng bản cũ. */
    @Override protected void onResume() {
        super.onResume();
        load();
        refresh();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ------------------------------------------------------------------ dữ liệu
    private void load() {
        items.clear();
        try {
            JSONArray a = new JSONArray(Storage.prefs(this).getString(Config.LW_ITEMS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) items.add(o);
            }
        } catch (Exception ignored) {}
    }

    private void save() {
        JSONArray a = new JSONArray();
        for (JSONObject o : items) a.put(o);
        Storage.prefs(this).edit().putString(Config.LW_ITEMS, a.toString()).apply();
        getContentResolver().notifyChange(Uri.parse(Config.CONFIG_URI), null);
    }

    private void refresh() {
        labels.clear();
        for (JSONObject o : items) labels.add(describe(o));
        if (labels.isEmpty()) labels.add("(chưa có tiện ích nào — bấm “Thêm tiện ích”)");
        adapter.notifyDataSetChanged();
    }

    private static String typeName(String t) {
        for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equals(t)) return TYPE_NAMES[i];
        return t;
    }

    private String describe(JSONObject o) {
        String t = o.optString("t");
        String extra = "";
        if (t.equals("text")) extra = " “" + o.optString("txt") + "”";
        else if (t.equals("app")) extra = " " + o.optString("pkg");
        else if (t.equals("effect")) extra = " " + o.optString("fx");
        String pos = t.equals("effect") ? "toàn màn hình"
                : "(" + Math.round(o.optDouble("x", 50)) + "%, " + Math.round(o.optDouble("y", 40)) + "%)";
        return typeName(t) + extra + "\n" + pos;
    }

    private static String randomId() {
        String s = Long.toString(Math.abs(new Random().nextLong()), 36);
        return s.length() > 6 ? s.substring(0, 6) : s;
    }

    private static int defaultSize(String t) {
        switch (t) {
            case "image": return 120;
            case "text": return 28;
            case "clock_d": return 56;
            case "clock_a": return 150;
            case "app": return 56;
            default: return 0;
        }
    }

    // ------------------------------------------------------------------ thêm / xoá
    private void addDialog() {
        new AlertDialog.Builder(this).setTitle("Thêm tiện ích").setItems(TYPE_NAMES, (d, w) -> {
            String t = TYPES[w];
            JSONObject o = new JSONObject();
            try {
                o.put("id", randomId());
                o.put("t", t);
                o.put("x", 50);
                o.put("y", t.equals("clock_d") ? 22 : 40);
                o.put("a", 100);
                o.put("s", defaultSize(t));
                if (t.equals("text")) o.put("txt", "Xin chào");
                if (t.equals("clock_d")) { o.put("tf", "HH:mm"); o.put("df", "{t}, d/M ({am})"); }
                if (t.equals("app")) o.put("act", 100);
                if (t.equals("effect")) { o.put("fx", "snow"); o.put("n", 60); o.put("sp", 100); o.put("col", "#FFFFFFFF"); }
            } catch (Exception ignored) {}
            items.add(o);
            save();
            refresh();
            edit(items.size() - 1);
        }).show();
    }

    private void confirmDelete(final int pos) {
        if (pos < 0 || pos >= items.size()) return;
        new AlertDialog.Builder(this).setTitle("Xoá tiện ích này?")
                .setMessage(describe(items.get(pos)))
                .setNegativeButton("Huỷ", null)
                .setPositiveButton("Xoá", (d, w) -> {
                    JSONObject o = items.remove(pos);
                    new File(Storage.dir(this), "lw_" + o.optString("id") + ".png").delete();
                    save();
                    refresh();
                }).show();
    }

    // ------------------------------------------------------------------ ảnh
    private String imageStatus(String id) {
        File f = new File(Storage.dir(this), "lw_" + id + ".png");
        return f.exists() ? "Đã có ảnh (" + (f.length() / 1024) + " KB)" : "Chưa chọn ảnh";
    }

    private void saveImage(Uri uri, String id) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(new File(Storage.dir(this), "lw_" + id + ".png"))) {
            byte[] buf = new byte[8192];
            long total = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                total += r;
                if (total > 12L * 1024 * 1024) throw new Exception("Ảnh quá lớn (tối đa 12 MB)");
                out.write(buf, 0, r);
            }
            new File(Storage.dir(this), "lw_" + id + ".png").setLastModified(System.currentTimeMillis());
            getContentResolver().notifyChange(Uri.parse(Config.CONFIG_URI), null);
            if (activeInfo != null) activeInfo.setText(imageStatus(id));
            Toast.makeText(this, "Đã lưu ảnh", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ bộ dựng giao diện sửa
    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private Spinner spinner(String[] names, int sel) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        s.setSelection(Math.max(0, Math.min(names.length - 1, sel)));
        return s;
    }

    private void addNumber(LinearLayout box, List<Runnable> savers, final JSONObject o, final String key,
                           String title, double def) {
        box.addView(label(title));
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        double v = o.optDouble(key, def);
        e.setText(v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v));
        box.addView(e);
        savers.add(() -> {
            try { o.put(key, Double.parseDouble(e.getText().toString().trim())); } catch (Exception ignored) {}
        });
    }

    private void addText(LinearLayout box, List<Runnable> savers, final JSONObject o, final String key,
                         String title, String def) {
        box.addView(label(title));
        final EditText e = new EditText(this);
        e.setText(o.optString(key, def));
        box.addView(e);
        savers.add(() -> {
            try { o.put(key, e.getText().toString()); } catch (Exception ignored) {}
        });
    }

    private void addSpinner(LinearLayout box, List<Runnable> savers, final JSONObject o, final String key,
                            String title, String[] names, final String[] values, String current) {
        box.addView(label(title));
        int sel = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) sel = i;
        final Spinner sp2 = spinner(names, sel);
        box.addView(sp2);
        savers.add(() -> {
            try { o.put(key, values[sp2.getSelectedItemPosition()]); } catch (Exception ignored) {}
        });
    }

    private void loadApps() {
        if (appLabels != null) return;
        PackageManager pm = getPackageManager();
        Intent it = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(it, 0);
        final List<String[]> rows = new ArrayList<>();
        for (ResolveInfo ri : list) rows.add(new String[]{String.valueOf(ri.loadLabel(pm)), ri.activityInfo.packageName});
        Collections.sort(rows, new Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) { return a[0].compareToIgnoreCase(b[0]); }
        });
        appLabels = new String[rows.size() + 1];
        appPkgs = new String[rows.size() + 1];
        appLabels[0] = "(Chưa chọn)";
        appPkgs[0] = "";
        for (int i = 0; i < rows.size(); i++) { appLabels[i + 1] = rows.get(i)[0]; appPkgs[i + 1] = rows.get(i)[1]; }
    }

    private void edit(final int index) {
        if (index < 0 || index >= items.size()) return;
        final JSONObject o = items.get(index);
        final String t = o.optString("t");
        final String id = o.optString("id");
        final List<Runnable> savers = new ArrayList<>();

        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        sv.addView(box);

        if (!t.equals("effect")) {
            addNumber(box, savers, o, "x", "Vị trí ngang (% màn hình: 0 = trái, 100 = phải)", 50);
            addNumber(box, savers, o, "y", "Vị trí dọc (% màn hình: 0 = trên, 100 = dưới)", 40);
            addNumber(box, savers, o, "s",
                    t.equals("text") ? "Cỡ chữ (sp)" : t.equals("clock_d") ? "Cỡ chữ giờ (sp)" : "Kích thước (dp)",
                    defaultSize(t));
        }
        addNumber(box, savers, o, "a", "Độ đậm (%)", 100);

        switch (t) {
            case "image": {
                activeInfo = new TextView(this);
                activeInfo.setText(imageStatus(id));
                activeInfo.setPadding(0, dp(12), 0, dp(4));
                box.addView(activeInfo);
                Button b = new Button(this);
                b.setText("Chọn ảnh / GIF...");
                b.setOnClickListener(v -> { pendingImageId = id; picker.launch(new String[]{"image/*"}); });
                box.addView(b);
                break;
            }
            case "text": {
                addText(box, savers, o, "txt", "Nội dung", "Xin chào");
                addSpinner(box, savers, o, "col", "Màu chữ", COLOR_NAMES, COLOR_VALUES, o.optString("col", "auto"));
                box.addView(label("In đậm"));
                final CheckBox cb = new CheckBox(this);
                cb.setChecked(o.optBoolean("b", false));
                box.addView(cb);
                savers.add(() -> { try { o.put("b", cb.isChecked()); } catch (Exception ignored) {} });
                break;
            }
            case "clock_d": {
                String[] tn = getResources().getStringArray(R.array.time_fmt_entries);
                String[] tv = getResources().getStringArray(R.array.time_fmt_values);
                addSpinner(box, savers, o, "tf", "Kiểu giờ", tn, tv, o.optString("tf", "HH:mm"));
                String[] dn0 = getResources().getStringArray(R.array.date_fmt_entries);
                String[] dv0 = getResources().getStringArray(R.array.date_fmt_values);
                String[] dn = new String[dn0.length + 1];
                String[] dv = new String[dv0.length + 1];
                dn[0] = "Không hiện dương lịch";
                dv[0] = "";
                System.arraycopy(dn0, 0, dn, 1, dn0.length);
                System.arraycopy(dv0, 0, dv, 1, dv0.length);
                addSpinner(box, savers, o, "df", "Kiểu dương lịch", dn, dv, o.optString("df", ""));
                box.addView(label("Hiện âm lịch"));
                final CheckBox cb = new CheckBox(this);
                cb.setChecked(o.optBoolean("lunar", false));
                box.addView(cb);
                savers.add(() -> { try { o.put("lunar", cb.isChecked()); } catch (Exception ignored) {} });
                box.addView(label("Kiểu âm lịch"));
                final Spinner ls = spinner(getResources().getStringArray(R.array.lunar_fmt_entries), o.optInt("lf", 0));
                box.addView(ls);
                savers.add(() -> { try { o.put("lf", ls.getSelectedItemPosition()); } catch (Exception ignored) {} });
                addSpinner(box, savers, o, "col", "Màu chữ", COLOR_NAMES, COLOR_VALUES, o.optString("col", "auto"));
                break;
            }
            case "effect": {
                addSpinner(box, savers, o, "fx", "Loại hiệu ứng", FX_NAMES, FX_VALUES, o.optString("fx", "snow"));
                addNumber(box, savers, o, "n", "Mật độ (số hạt, 5 – 400)", 60);
                addNumber(box, savers, o, "sp", "Tốc độ (%, 20 – 400)", 100);
                addSpinner(box, savers, o, "col", "Màu", COLOR_NAMES, COLOR_VALUES, o.optString("col", "#FFFFFFFF"));
                break;
            }
            default:
                break;
        }

        if (!t.equals("effect")) {
            String[] actValues = new String[ACT_VALUES.length];
            for (int i = 0; i < ACT_VALUES.length; i++) actValues[i] = String.valueOf(ACT_VALUES[i]);
            box.addView(label("Khi chạm vào"));
            int sel = 0;
            int cur = o.optInt("act", t.equals("app") ? 100 : 0);
            for (int i = 0; i < ACT_VALUES.length; i++) if (ACT_VALUES[i] == cur) sel = i;
            final Spinner as = spinner(ACT_NAMES, sel);
            box.addView(as);
            savers.add(() -> { try { o.put("act", ACT_VALUES[as.getSelectedItemPosition()]); } catch (Exception ignored) {} });

            loadApps();
            addSpinner(box, savers, o, "pkg", t.equals("app") ? "Ứng dụng" : "Ứng dụng sẽ mở (khi chọn “Mở ứng dụng”)",
                    appLabels, appPkgs, o.optString("pkg", ""));
        }

        new AlertDialog.Builder(this)
                .setTitle(typeName(t))
                .setView(sv)
                .setPositiveButton("Lưu", (d, w) -> {
                    for (Runnable r : savers) r.run();
                    save();
                    refresh();
                })
                .setNegativeButton("Huỷ", null)
                .setNeutralButton("Xoá", (d, w) -> confirmDelete(index))
                .show();
    }
}
