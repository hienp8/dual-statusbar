package com.dualsb.oneui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import de.robv.android.xposed.XposedBridge;

/**
 * Thanh trạng thái của màn hình khóa (KeyguardStatusBarView) là view riêng, các biểu tượng của nó căn giữa theo
 * chiều cao mới nên bị đè lên đường kẻ của hình nền. Controller này dịch các con trực tiếp của nó
 * về hàng trên hoặc hàng dưới của thanh 2 dòng.
 */
final class KeyguardBarController {
    final ViewGroup view;
    private int row;          // 0 giữ nguyên, 1 trên, 2 dưới
    private boolean dumped;

    KeyguardBarController(ViewGroup v) {
        view = v;
        View p = v;
        for (int i = 0; p != null && i < 6; i++) {
            if (p instanceof ViewGroup) ((ViewGroup) p).setClipChildren(false);
            ViewParent pp = p.getParent();
            p = pp instanceof View ? (View) pp : null;
        }
        v.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() { relayout(); }
        });
    }

    void apply(Bundle b) {
        boolean dual = Config.getBool(b, Config.ENABLED) && Config.getBool(b, Config.DUAL);
        row = dual ? Config.getInt(b, Config.KG_ROW) : 0;
        relayout();
    }

    void relayout() {
        int h = view.getHeight();
        if (h <= 0) return;
        if (!dumped) {
            dumped = true;
            StringBuilder sb = new StringBuilder(SysUiHook.TAG + "KeyguardStatusBarView h=" + h + ":");
            for (int i = 0; i < view.getChildCount(); i++) {
                View c = view.getChildAt(i);
                String n;
                try { n = c.getId() == View.NO_ID ? "-" : c.getResources().getResourceEntryName(c.getId()); }
                catch (Throwable t) { n = "?"; }
                sb.append("\n  ").append(c.getClass().getSimpleName()).append('#').append(n)
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
