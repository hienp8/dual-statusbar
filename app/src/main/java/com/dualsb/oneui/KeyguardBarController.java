package com.dualsb.oneui;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * Thanh trạng thái của màn hình khóa (KeyguardStatusBarView) là view riêng:
 *  - dịch các biểu tượng gốc về hàng trên / hàng dưới của thanh 2 dòng (khỏi đè lên đường kẻ của hình nền);
 *  - ẩn pin / Wi-Fi / sóng gốc khi đang dùng khối pin vòng, và ẩn các biểu tượng đã chọn trong menu ẩn / hiện.
 */
final class KeyguardBarController {
    final ViewGroup view;
    private int row;          // 0 giữ nguyên, 1 trên, 2 dưới
    private boolean dumped;

    private boolean moduleOn, ringOn, hideBattery, hideWifi, hideSig;
    private final Set<String> hiddenSlots = new HashSet<>();
    private final Set<View> hidden = new HashSet<>();
    private final List<View> batteryViews = new ArrayList<>(), wifiViews = new ArrayList<>(),
            mobileViews = new ArrayList<>();
    private final Map<String, View> slotViews = new HashMap<>();
    private long lastScan;

    KeyguardBarController(ViewGroup v) {
        view = v;
        View p = v;
        for (int i = 0; p != null && i < 6; i++) {
            if (p instanceof ViewGroup) ((ViewGroup) p).setClipChildren(false);
            ViewParent pp = p.getParent();
            p = pp instanceof View ? (View) pp : null;
        }
        v.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                relayout();
                scan();
                applyHides();
            }
        });
        v.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (moduleOn || !hidden.isEmpty()) applyHides();      // Samsung hay đặt lại alpha
                return true;
            }
        });
    }

    void apply(Bundle b) {
        boolean enabled = Config.getBool(b, Config.ENABLED);
        boolean dual = enabled && Config.getBool(b, Config.DUAL);
        row = dual ? Config.getInt(b, Config.KG_ROW) : 0;

        moduleOn = enabled;
        ringOn = Config.getBool(b, Config.RING_ENABLED);
        hideBattery = Config.getBool(b, Config.RING_HIDE_STOCK);
        hideWifi = Config.getBool(b, Config.RING_HIDE_WIFI);
        hideSig = Config.getBool(b, Config.RING_HIDE_SIG);
        hiddenSlots.clear();
        for (String sl : Config.getString(b, Config.HIDDEN_SLOTS).split(",")) {
            sl = sl.trim();
            if (!sl.isEmpty()) hiddenSlots.add(sl);
        }
        lastScan = 0;
        relayout();
        scan();
        applyHides();
    }

    // ------------------------------------------------------------------ ẩn biểu tượng
    private static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return "-";
        try { return v.getResources().getResourceEntryName(id); }
        catch (Throwable t) { return "?"; }
    }

    private void scan() {
        long now = SystemClock.uptimeMillis();
        if (now - lastScan < 400) return;
        lastScan = now;
        batteryViews.clear();
        wifiViews.clear();
        mobileViews.clear();
        slotViews.clear();
        collect(view, 0);
    }

    private void collect(View v, int depth) {
        if (depth > 12) return;
        String id = idName(v), cls = v.getClass().getSimpleName();
        String n = (id + " " + cls).toLowerCase();
        if (n.contains("carrier")) return;                       // chữ tên nhà mạng giữ nguyên
        String slot = BarController.slotOf(v);
        if (slot != null) slotViews.put(slot, v);
        if (cls.contains("BatteryMeterView") || "battery".equals(id)) { batteryViews.add(v); return; }
        if (n.contains("airplane") || n.contains("hotspot")) return;
        if (n.contains("wifi")) { wifiViews.add(v); return; }
        if (n.contains("mobile") || n.contains("signal") || n.contains("cellular")) { mobileViews.add(v); return; }
        if (slot != null) return;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), depth + 1);
        }
    }

    private void setHidden(View v, boolean hide) {
        if (hide) {
            if (v.getAlpha() != 0f) v.setAlpha(0f);
            hidden.add(v);
        } else if (hidden.remove(v)) {
            v.setAlpha(1f);
        }
    }

    private void applyHides() {
        if (!moduleOn && hidden.isEmpty()) return;
        Set<View> want = new HashSet<>();
        if (moduleOn) {
            if (ringOn && hideBattery) want.addAll(batteryViews);
            if (ringOn && hideWifi) want.addAll(wifiViews);
            if (ringOn && hideSig) want.addAll(mobileViews);
            for (Map.Entry<String, View> e : slotViews.entrySet())
                if (hiddenSlots.contains(e.getKey())) want.add(e.getValue());
        }
        Set<View> all = new HashSet<>(hidden);
        all.addAll(want);
        for (View v : all) setHidden(v, want.contains(v));
    }

    // ------------------------------------------------------------------ dịch hàng
    void relayout() {
        int h = view.getHeight();
        if (h <= 0) return;
        if (!dumped) {
            dumped = true;
            StringBuilder sb = new StringBuilder(SysUiHook.TAG + "KeyguardStatusBarView h=" + h + ":");
            for (int i = 0; i < view.getChildCount(); i++) {
                View c = view.getChildAt(i);
                sb.append("\n  ").append(c.getClass().getSimpleName()).append('#').append(idName(c))
                        .append(" vis=").append(c.getVisibility()).append(' ').append(c.getWidth()).append('x').append(c.getHeight());
            }
            XposedBridge.log(sb.toString());
        }
        float top = SysUiHook.rowTopPx > 0 ? SysUiHook.rowTopPx : h * 0.25f;
        float bottom = SysUiHook.rowBottomPx > 0 ? SysUiHook.rowBottomPx : h * 0.75f;
        for (int i = 0; i < view.getChildCount(); i++) {
            View c = view.getChildAt(i);
            if (row == 0) { c.setTranslationY(0); continue; }
            float target = row == 1 ? top : bottom;
            c.setTranslationY(target - (c.getTop() + c.getHeight() / 2f));
        }
    }
}
