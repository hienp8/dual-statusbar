package com.dualsb.oneui;

import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * Điều khiển cửa sổ màn hình khóa:
 *  - gắn lớp tiện ích (LockWidgetLayer);
 *  - dịch vị trí các mục CÓ SẴN của Samsung. Mỗi mục được chọn ("chủ") kéo theo các view nằm trọn trong khung của nó
 *    (chữ, biểu tượng trên một nền mờ...) nên cả khối di chuyển cùng nhau;
 *  - khi thả, tự đẩy các mục khác đang chồng lên ra xa theo chiều dọc.
 * Phần dịch được cộng thêm vào giá trị Samsung đang đặt (không ghi đè hoạt ảnh của hệ thống).
 */
final class LockScreenController {

    /** Một mục có sẵn của Samsung có thể chọn / kéo, cùng các view đi kèm nằm trong khung của nó. */
    static final class Target {
        final String key, label;
        final View view;
        final List<String> memberKeys = new ArrayList<>();
        final List<View> memberViews = new ArrayList<>();
        boolean prepared;
        Target(String key, String label, View view) { this.key = key; this.label = label; this.view = view; }
    }

    final ViewGroup root;
    private boolean on, editing, needPaths;
    private final Map<String, float[]> want = new HashMap<>();        // khoá -> {dx px, dy px} (chủ và thành viên dùng chung mảng)
    private final Map<String, View> found = new HashMap<>();
    private final Map<String, View> editViews = new HashMap<>();
    private final Set<String> leaders = new LinkedHashSet<>();
    private final Map<String, List<String>> groups = new HashMap<>();
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
        leaders.clear();
        groups.clear();
        needPaths = edit;
        if (on) {
            for (String id : Config.getString(b, Config.LS_SELECTED).split(",")) {
                id = id.trim();
                if (id.isEmpty()) continue;
                float[] off = new float[]{Config.getInt(b, "ls_dx_" + id) * d, Config.getInt(b, "ls_dy_" + id) * d};
                want.put(id, off);
                leaders.add(id);
                if (id.startsWith("p:")) needPaths = true;
                String g = Config.getString(b, "ls_grp_" + id);
                if (!g.isEmpty()) {
                    List<String> mem = new ArrayList<>();
                    for (String mk : g.split(",")) {
                        mk = mk.trim();
                        if (mk.isEmpty()) continue;
                        mem.add(mk);
                        want.put(mk, off);                                  // thành viên dùng chung độ dịch với "chủ"
                        if (mk.startsWith("p:")) needPaths = true;
                    }
                    groups.put(id, mem);
                }
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

    /** Mục có thể chọn trong chế độ chỉnh: đang hiện, đủ lớn nhưng không phải khung phủ gần hết màn hình. */
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

    // ------------------------------------------------------------------ khoá của một view
    private String pathOf(View v) {
        StringBuilder sb = new StringBuilder();
        View c = v;
        while (c != root && c.getParent() instanceof ViewGroup) {
            ViewGroup p = (ViewGroup) c.getParent();
            int i = p.indexOfChild(c);
            sb.insert(0, sb.length() == 0 ? String.valueOf(i) : i + ".");
            c = p;
        }
        return sb.toString();
    }

    private String keyFor(View v) {
        String n = nameOf(v);
        return n != null ? n : "p:" + pathOf(v);
    }

    Rect rectOf(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
    }

    private static boolean isIn(View v, Set<View> set) {
        View c = v;
        while (c != null) {
            if (set.contains(c)) return true;
            c = c.getParent() instanceof View ? (View) c.getParent() : null;
        }
        return false;
    }

    // ------------------------------------------------------------------ chọn mục
    /** Các mục chứa điểm chạm (toạ độ màn hình), từ nhỏ nhất đến lớn nhất. */
    List<Target> candidatesAt(float sx, float sy) {
        List<Target> out = new ArrayList<>();
        final Map<Target, Long> area = new HashMap<>();
        for (Map.Entry<String, View> e : editViews.entrySet()) {
            View v = e.getValue();
            if (!v.isShown()) continue;
            Rect r = rectOf(v);
            if (r.contains((int) sx, (int) sy)) {
                String key = e.getKey();
                String label = key.startsWith("p:") ? "Mục không tên · " + v.getClass().getSimpleName() : LockLabels.shortLabel(key);
                Target t = new Target(key, label, v);
                out.add(t);
                area.put(t, (long) r.width() * r.height());
            }
        }
        Collections.sort(out, new Comparator<Target>() {
            @Override public int compare(Target a, Target b) { return Long.compare(area.get(a), area.get(b)); }
        });
        return out;
    }

    /** Tìm các view nằm trọn trong khung của mục (chữ, biểu tượng trên một nền mờ...) để di chuyển cùng. */
    void prepare(Target t) {
        if (t.prepared) return;
        t.prepared = true;
        Rect frame = rectOf(t.view);
        int pad = Math.round(8 * density());
        frame.inset(-pad, -pad);
        Set<View> anc = new HashSet<>();
        View c = t.view;
        while (c.getParent() instanceof View) {
            c = (View) c.getParent();
            anc.add(c);
        }
        collectMembers(root, frame, t.view, anc, t.memberViews, 0);
        for (View m : t.memberViews) {
            String k = keyFor(m);
            t.memberKeys.add(k);
            if (k.startsWith("p:")) needPaths = true;
        }
    }

    private void collectMembers(View v, Rect frame, View leader, Set<View> anc, List<View> out, int depth) {
        if (depth > 14 || v == layer || v == leader || v.getVisibility() != View.VISIBLE) return;
        if (!anc.contains(v)) {                                    // tổ tiên của mục thì chỉ duyệt tiếp các con
            int w = v.getWidth(), h = v.getHeight();
            if (w > 0 && h > 0 && v.getAlpha() > 0f) {
                Rect r = rectOf(v);
                if (frame.contains(r)) { out.add(v); return; }     // lấy khối ngoài cùng, không lấy con cháu của nó
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectMembers(g.getChildAt(i), frame, leader, anc, out, depth + 1);
        }
    }

    // ------------------------------------------------------------------ kéo
    void beginDrag(Target t) {
        prepare(t);
        float[] off = want.get(t.key);
        if (off == null) { off = new float[]{0f, 0f}; want.put(t.key, off); }
        dragBase = off.clone();
        register(t, off);
        on = true;
    }

    private void register(Target t, float[] off) {
        found.put(t.key, t.view);
        for (int i = 0; i < t.memberKeys.size(); i++) {
            want.put(t.memberKeys.get(i), off);                   // cùng một mảng: chủ dịch bao nhiêu, thành viên dịch bấy nhiêu
            found.put(t.memberKeys.get(i), t.memberViews.get(i));
        }
        leaders.add(t.key);
        groups.put(t.key, new ArrayList<>(t.memberKeys));
    }

    void dragStock(Target t, float dx, float dy) {
        float[] off = want.get(t.key);
        if (off == null || dragBase == null) return;
        off[0] = dragBase[0] + dx;
        off[1] = dragBase[1] + dy;
        applyOffsets();
    }

    /**
     * Đẩy các mục có sẵn đang chồng lên vùng D (toạ độ màn hình) ra xa theo chiều dọc (cả khối đi kèm).
     * Mục nằm trọn trong D (thành viên) hoặc chứa D (khung nền) thì bỏ qua. Trả về các khoá "chủ" đã đổi.
     */
    Set<String> avoidOverlap(Rect D, Target moved, int screenH) {
        Set<String> changed = new LinkedHashSet<>();
        int gap = Math.round(8 * density());
        Set<View> skip = new HashSet<>();
        if (moved != null) { skip.add(moved.view); skip.addAll(moved.memberViews); }
        long dArea = (long) D.width() * D.height();
        for (int pass = 0; pass < 2; pass++) {
            List<Target> cs = new ArrayList<>();
            final Map<Target, Long> area = new HashMap<>();
            for (Map.Entry<String, View> e : editViews.entrySet()) {
                View v = e.getValue();
                if (!v.isShown() || isIn(v, skip)) continue;
                Rect r = rectOf(v);
                long ar = (long) r.width() * r.height();
                if (ar <= 0) continue;
                Rect inter = new Rect(r);
                if (!inter.intersect(D)) continue;
                long ia = (long) inter.width() * inter.height();
                if (ia >= 0.8 * ar) continue;                      // nằm trong vùng vừa thả
                if (dArea > 0 && ia >= 0.8 * dArea) continue;      // vùng vừa thả nằm trong mục này (khung chứa)
                Target t = new Target(e.getKey(), "", v);
                cs.add(t);
                area.put(t, ar);
            }
            Collections.sort(cs, new Comparator<Target>() {        // khung lớn trước, các mục nhỏ bên trong sẽ đi theo
                @Override public int compare(Target a, Target b) { return Long.compare(area.get(b), area.get(a)); }
            });
            Set<View> covered = new HashSet<>();
            boolean any = false;
            for (Target t : cs) {
                if (isIn(t.view, covered)) continue;
                prepare(t);
                Rect r = rectOf(t.view);
                int dy;
                if (r.centerY() >= D.centerY()) {
                    dy = D.bottom + gap - r.top;
                    if (r.bottom + dy > screenH) dy = -(r.bottom + gap - D.top);
                } else {
                    dy = -(r.bottom + gap - D.top);
                    if (r.top + dy < 0) dy = D.bottom + gap - r.top;
                }
                if (r.top + dy < 0 || r.bottom + dy > screenH) continue;
                float[] off = want.get(t.key);
                if (off == null) { off = new float[]{0f, 0f}; want.put(t.key, off); }
                off[1] += dy;
                register(t, off);
                covered.add(t.view);
                covered.addAll(t.memberViews);
                changed.add(t.key);
                any = true;
            }
            if (!any) break;
            applyOffsets();
        }
        on = true;
        return changed;
    }

    void reset(Target t) {
        float[] off = want.get(t.key);
        if (off == null) return;
        off[0] = 0f;
        off[1] = 0f;
        applyOffsets();
        Set<String> one = new LinkedHashSet<>();
        one.add(t.key);
        commit(one);
    }

    private static String join(Collection<String> c) {
        StringBuilder sb = new StringBuilder();
        if (c != null) for (String s : c) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }

    /** Lưu một lần duy nhất: độ dịch (dp), nhóm đi kèm, danh sách mục đã chỉnh và bật tính năng. */
    void commit(Collection<String> changedLeaders) {
        if (changedLeaders == null || changedLeaders.isEmpty()) return;
        float d = density();
        Bundle kv = new Bundle();
        for (String k : changedLeaders) {
            float[] w = want.get(k);
            if (w == null) continue;
            kv.putString("ls_dx_" + k, String.valueOf(Math.round(w[0] / d)));
            kv.putString("ls_dy_" + k, String.valueOf(Math.round(w[1] / d)));
            kv.putString("ls_grp_" + k, join(groups.get(k)));
        }
        kv.putString(Config.LS_SELECTED, join(leaders));
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
