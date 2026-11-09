package com.dualsb.oneui;

import android.content.res.Resources;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Tuỳ chỉnh màn hình khóa: cho phép dịch tự do (ngang / dọc) từng mục đang hiển thị trong cửa sổ shade
 * (đồng hồ, ngày, ổ khóa, phím tắt, thông báo...). Các mục được nhận diện theo tên id; mỗi lần quét, module báo
 * danh sách tên đang hiển thị về app để bạn chọn trong menu.
 *
 * Phần dịch được cộng thêm vào giá trị Samsung đang đặt (không ghi đè hoạt ảnh của hệ thống).
 */
final class LockScreenController {
    final ViewGroup root;
    private boolean on;
    private final Map<String, float[]> want = new HashMap<>();        // tên id -> {dx px, dy px}
    private final Map<String, View> found = new HashMap<>();
    private final Map<View, float[]> st = new WeakHashMap<>();        // view -> {nền X, nền Y, đã ghi X, đã ghi Y}
    private final Set<String> reported = new HashSet<>();
    private long lastScan;
    private static final Map<Integer, String> NAMES = new HashMap<>();

    LockScreenController(ViewGroup v) {
        root = v;
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

    void apply(Bundle b) {
        float d = root.getResources().getDisplayMetrics().density;
        on = Config.getBool(b, Config.ENABLED) && Config.getBool(b, Config.LS_ENABLED);
        want.clear();
        if (on) {
            for (String id : Config.getString(b, Config.LS_SELECTED).split(",")) {
                id = id.trim();
                if (id.isEmpty()) continue;
                want.put(id, new float[]{Config.getInt(b, "ls_dx_" + id) * d, Config.getInt(b, "ls_dy_" + id) * d});
            }
        }
        lastScan = 0;
        scan();
        applyOffsets();
    }

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
        if (now - lastScan < 500) return;
        lastScan = now;
        found.clear();
        Set<String> seen = new HashSet<>();
        walk(root, 0, seen);
        StringBuilder add = new StringBuilder();
        for (String n : seen) {
            if (reported.size() < 400 && reported.add(n)) {
                if (add.length() > 0) add.append(',');
                add.append(n);
            }
        }
        if (add.length() > 0) SysUiHook.reportLockItems(root.getContext(), add.toString());
    }

    private void walk(View v, int depth, Set<String> seen) {
        if (depth > 14) return;
        String n = nameOf(v);
        if (n != null) {
            if (want.containsKey(n)) found.put(n, v);
            if (v.getVisibility() == View.VISIBLE && v.getWidth() > 0 && v.getHeight() > 0 && v.isShown()) seen.add(n);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), depth + 1, seen);
        }
    }

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
                if (cx != s[2]) s[0] = cx;              // ai đó (hoạt ảnh của Samsung) vừa đổi giá trị: lấy làm nền mới
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
