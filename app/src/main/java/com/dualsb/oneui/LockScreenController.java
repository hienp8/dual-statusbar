package com.dualsb.oneui;

import android.content.res.Resources;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * Điều khiển cửa sổ màn hình khóa:
 *  - gắn lớp tiện ích (LockWidgetLayer: ảnh, GIF, đồng hồ, nút ứng dụng, hiệu ứng...);
 *  - dịch vị trí các mục CÓ SẴN của Samsung (theo tên id, hoặc theo đường dẫn view nếu không có tên) và cho kéo thả
 *    trực tiếp trong chế độ chỉnh. Phần dịch được cộng thêm vào giá trị Samsung đang đặt (không ghi đè hoạt ảnh).
 */
final class LockScreenController {

    static final class StockInfo {
        final String key, label;
        final int l, t, r, b;
        StockInfo(String key, String label, int l, int t, int r, int b) {
            this.key = key; this.label = label; this.l = l; this.t = t; this.r = r; this.b = b;
        }
    }

    final ViewGroup root;
    private boolean on, editing, needPaths;
    private final Map<String, float[]> want = new HashMap<>();        // khoá -> {dx px, dy px}
    private final Map<String, View> found = new HashMap<>();
    private final Map<String, View> editViews = new HashMap<>();
    private final Map<View, float[]> st = new WeakHashMap<>();        // view -> {nền X, nền Y, đã ghi X, đã ghi Y}
    private final Set<String> reported = new HashSet<>();
    private long lastScan;
    private int scans, dumps, lastDumpSeen;
    private float[] dragBase;
    private LockWidgetLayer layer;
    private static final Map<Integer, String> NAMES = new HashMap<>();

    LockScreenController(ViewGroup v) {
        root = v;
        XposedBridge.log(SysUiHook.TAG + "màn hình khóa: gắn vào " + v.getClass().getName());
        v.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() { scan(); }
        });
        v.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (on || !st.isEmpty()) applyOffsets();
                return true;
            }
        });
    }

    private float density() { return root.getResources().getDisplayMetrics().density; }

    void apply(Bundle b) {
        boolean master = Config.getBool(b, Config.ENABLED);
        boolean lsOn = master && Config.getBool(b, Config.LS_ENABLED);
        boolean wOn = master && Config.getBool(b, Config.LW_ENABLED);
        boolean edit = wOn && Config.getBool(b, Config.LW_EDIT);
        editing = edit;
        on = lsOn || edit;
        float d = density();
        want.clear();
        needPaths = edit;
        if (on) {
            for (String id : Config.getString(b, Config.LS_SELECTED).split(",")) {
                id = id.trim();
                if (id.isEmpty()) continue;
                want.put(id, new float[]{Config.getInt(b, "ls_dx_" + id) * d, Config.getInt(b, "ls_dy_" + id) * d});
                if (id.startsWith("p:")) needPaths = true;
            }
        }
        if (wOn && layer == null) createLayer();
        if (layer != null) layer.apply(b);
        lastScan = 0;
        scan();
        applyOffsets();
    }

    private void createLayer() {
        try {
            layer = new LockWidgetLayer(this, root.getContext());
            root.addView(layer, new ViewGroup.LayoutParams(-1, -1));
            XposedBridge.log(SysUiHook.TAG + "đã thêm lớp tiện ích vào màn hình khóa");
        } catch (Throwable t) {
            XposedBridge.log(SysUiHook.TAG + "không thêm được lớp tiện ích: " + t);
            layer = null;
        }
    }

    void setEditing(boolean e) {
        editing = e;
        if (!e) editViews.clear();
        lastScan = 0;
    }

    // ------------------------------------------------------------------ quét cây view
    private static String nameOf(View v) {
        int id = v.getId();
        if (id == View.NO_ID || (id >>> 24) != 0x7f) return null;
        String n = NAMES.get(id);
        if (n == null) {
            try {
                Resources r = v.getResources();
                n = "com.android.systemui".equals(r.getResourcePackageName(id)) ? r.getResourceEntryName(id) : "";
            } catch (Throwable t) {
                n = "";
            }
            NAMES.put(id, n);
        }
        return n.isEmpty() ? null : n;
    }

    private void scan() {
        long now = SystemClock.uptimeMillis();
        if (now - lastScan < (editing ? 300 : 500)) return;
        lastScan = now;
        found.clear();
        if (editing) editViews.clear();
        Set<String> seen = new HashSet<>();
        walk(root, 0, seen, "");
        StringBuilder add = new StringBuilder();
        for (String n : seen) {
            if (reported.size() < 400 && reported.add(n)) {
                if (add.length() > 0) add.append(',');
                add.append(n);
            }
        }
        if (add.length() > 0) SysUiHook.reportLockItems(root.getContext(), add.toString());

        scans++;
        if (dumps < 2 && (scans == 3 || (dumps == 1 && Math.abs(seen.size() - lastDumpSeen) >= 15))) {
            dumps++;
            lastDumpSeen = seen.size();
            StringBuilder sb = new StringBuilder(SysUiHook.TAG + "cây view cửa sổ shade (" + seen.size()
                    + " mục có tên): " + root.getClass().getName());
            dump(root, 0, sb, new int[]{0});
            XposedBridge.log(sb.toString());
        }
    }

    private void walk(View v, int depth, Set<String> seen, String path) {
        if (depth > 14 || v == layer) return;
        String n = nameOf(v);
        String key = n != null ? n : (needPaths ? "p:" + path : null);
        if (key != null) {
            if (want.containsKey(key)) found.put(key, v);
            if (n != null && v.getWidth() > 0 && v.getHeight() > 0) seen.add(n);
            if (editing) consider(key, v, n == null);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++)
                walk(g.getChildAt(i), depth + 1, seen, path.isEmpty() ? String.valueOf(i) : path + "." + i);
        }
    }

    /** Mục có thể kéo trong chế độ chỉnh: đang hiện, đủ lớn nhưng không phải khung phủ gần hết màn hình. */
    private void consider(String key, View v, boolean unnamed) {
        if (!v.isShown() || key.equals("p:")) return;
        float d = density();
        int w = v.getWidth(), h = v.getHeight();
        if (w < 24 * d || h < 12 * d) return;
        DisplayMetrics dm = v.getResources().getDisplayMetrics();
        if ((long) w * h > 0.6 * dm.widthPixels * dm.heightPixels) return;
        if (unnamed && v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0) return;
        editViews.put(key, v);
    }

    private void dump(View v, int depth, StringBuilder sb, int[] cnt) {
        if (depth > 7 || cnt[0] > 260 || v == layer) return;
        cnt[0]++;
        String n = nameOf(v);
        sb.append('\n');
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName()).append('#').append(n == null ? "-" : n)
                .append(" vis=").append(v.getVisibility()).append(' ').append(v.getWidth()).append('x').append(v.getHeight());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dump(g.getChildAt(i), depth + 1, sb, cnt);
        }
    }

    // ------------------------------------------------------------------ chỉnh bằng tay
    List<StockInfo> stockEntries() {
        List<StockInfo> out = new ArrayList<>();
        int[] loc = new int[2];
        for (Map.Entry<String, View> e : editViews.entrySet()) {
            View v = e.getValue();
            if (!v.isShown()) continue;
            v.getLocationOnScreen(loc);
            String key = e.getKey();
            String label = key.startsWith("p:") ? "Mục không tên · " + v.getClass().getSimpleName() : LockLabels.shortLabel(key);
            out.add(new StockInfo(key, label, loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight()));
        }
        return out;
    }

    /** Mục nhỏ nhất chứa điểm chạm (toạ độ màn hình). */
    String hitStock(float sx, float sy) {
        String best = null;
        long bestArea = Long.MAX_VALUE;
        int[] loc = new int[2];
        for (Map.Entry<String, View> e : editViews.entrySet()) {
            View v = e.getValue();
            if (!v.isShown()) continue;
            v.getLocationOnScreen(loc);
            if (sx >= loc[0] && sx <= loc[0] + v.getWidth() && sy >= loc[1] && sy <= loc[1] + v.getHeight()) {
                long area = (long) v.getWidth() * v.getHeight();
                if (area < bestArea) { bestArea = area; best = e.getKey(); }
            }
        }
        return best;
    }

    void beginDrag(String key) {
        View v = editViews.get(key);
        if (v != null) found.put(key, v);
        float[] w = want.get(key);
        dragBase = w == null ? new float[]{0f, 0f} : w.clone();
        if (w == null) want.put(key, new float[]{0f, 0f});
        on = true;
    }

    void dragStock(String key, float dx, float dy) {
        float[] w = want.get(key);
        if (w == null || dragBase == null) return;
        w[0] = dragBase[0] + dx;
        w[1] = dragBase[1] + dy;
        applyOffsets();
    }

    /** Lưu một lần duy nhất: độ dịch (dp), danh sách mục đã chỉnh và bật tính năng. */
    void endDrag(String key) {
        float[] w = want.get(key);
        if (w == null) return;
        float d = density();
        StringBuilder keys = new StringBuilder();
        for (String k : want.keySet()) {
            if (keys.length() > 0) keys.append(',');
            keys.append(k);
        }
        Bundle kv = new Bundle();
        kv.putString("ls_dx_" + key, String.valueOf(Math.round(w[0] / d)));
        kv.putString("ls_dy_" + key, String.valueOf(Math.round(w[1] / d)));
        kv.putString(Config.LS_SELECTED, keys.toString());
        kv.putString(Config.LS_ENABLED, "true");
        SysUiHook.setPrefs(root.getContext(), kv);
    }

    // ------------------------------------------------------------------ áp độ dịch (cộng thêm vào giá trị của Samsung)
    private void applyOffsets() {
        Map<View, float[]> desired = new HashMap<>();
        if (on) {
            for (Map.Entry<String, View> e : found.entrySet()) {
                float[] o = want.get(e.getKey());
                if (o != null) desired.put(e.getValue(), o);
            }
        }
        for (Map.Entry<View, float[]> e : desired.entrySet()) {
            View v = e.getKey();
            float[] o = e.getValue();
            float cx = v.getTranslationX(), cy = v.getTranslationY();
            float[] s = st.get(v);
            if (s == null) {
                s = new float[]{cx, cy, Float.NaN, Float.NaN};
                st.put(v, s);
            } else {
                if (cx != s[2]) s[0] = cx;              // Samsung (hoạt ảnh) vừa đổi giá trị: lấy làm nền mới
                if (cy != s[3]) s[1] = cy;
            }
            float nx = s[0] + o[0], ny = s[1] + o[1];
            if (cx != nx) v.setTranslationX(nx);
            if (cy != ny) v.setTranslationY(ny);
            s[2] = nx;
            s[3] = ny;
        }
        for (View v : new ArrayList<>(st.keySet())) {            // mục không còn được chọn: trả về như cũ
            if (desired.containsKey(v)) continue;
            float[] s = st.remove(v);
            if (s == null) continue;
            if (v.getTranslationX() == s[2]) v.setTranslationX(s[0]);
            if (v.getTranslationY() == s[3]) v.setTranslationY(s[1]);
        }
    }
}
