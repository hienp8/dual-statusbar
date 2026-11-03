package com.dualsb.oneui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.TypedValue;
import android.view.WindowManager;
import android.widget.TextView;
import java.util.List;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * Điều khiển 1 PhoneStatusBarView.
 *
 * Cấu trúc One UI 7 (suy ra từ status_bar.xml bạn gửi):
 *   PhoneStatusBarView (root, vertical)
 *     ├─ ImageView notification_lights_out
 *     └─ RelativeLayout status_bar_contents
 *          ├─ IndicatorGardenMaxWidthFrameLayout  (trái: clock + notification icons)
 *          ├─ IndicatorGardenMaxWidthLinearLayout (giữa: cutout space)
 *          └─ IndicatorGardenMaxWidthLinearLayout (phải: system icons + pin)
 * Không reparent view nào (để controller của Samsung vẫn giữ tham chiếu đúng); chỉ dịch bằng
 * translationX/Y vào lưới 3x3 của status_bar_contents.
 */
final class BarController {
    private static final String PKG = "com.android.systemui";
    private static final String TAG = SysUiHook.TAG;

    final ViewGroup root;
    private final Context ctx;
    private final Drawable origBg;
    private final int origHeight;

    private RelativeLayout contents;
    private View clock, clockBox, notif, sysGroup;
    private FrameLayout overlay;
    private AnalogClockView analog;
    private TextView label;
    private int labelMode;
    private String labelText = "";
    private BatteryLineDrawable batLine;
    private int batLevel = 100;
    private boolean batCharging;
    private final BroadcastReceiver rcv = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())) readBattery(i);
            else refreshLabel();
        }
    };

    private int heightPx;
    private final int[] cells = {-1, -1, -1};
    private final Map<String, Object[]> imgCache = new HashMap<>();

    BarController(ViewGroup root) {
        this.root = root;
        this.ctx = root.getContext();
        this.origBg = root.getBackground();
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        this.origHeight = lp == null ? ViewGroup.LayoutParams.WRAP_CONTENT : lp.height;

        View c = byName("status_bar_contents");
        if (!(c instanceof RelativeLayout)) {
            c = null;
            for (int i = 0; i < root.getChildCount(); i++)
                if (root.getChildAt(i) instanceof RelativeLayout) { c = root.getChildAt(i); break; }
        }
        contents = (RelativeLayout) c;

        if (contents != null) {
            clock = byName("clock");
            clockBox = byName("left_clock_container");
            if (clockBox == null && clock != null && clock.getParent() instanceof View)
                clockBox = (View) clock.getParent();
            notif = byName("notification_icon_area");
            sysGroup = byName("system_icon_area");
            if (sysGroup == null && contents.getChildCount() >= 3)
                sysGroup = contents.getChildAt(contents.getChildCount() - 1);
            noClip(contents);
        }
        XposedBridge.log(TAG + "controller: contents=" + (contents != null) + " clock=" + (clock != null)
                + " clockBox=" + (clockBox != null) + " notif=" + (notif != null) + " sys=" + (sysGroup != null));

        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            f.addAction("android.intent.action.SERVICE_STATE");
            f.addAction("android.intent.action.SIM_STATE_CHANGED");
            Intent sticky = ctx.registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED);
            if (sticky != null) readBattery(sticky);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "registerReceiver: " + t);
        }

        root.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() { onLayout(); }
        });
    }

    private View byName(String name) {
        int id = root.getResources().getIdentifier(name, "id", PKG);
        return id == 0 ? null : root.findViewById(id);
    }

    private void noClip(View from) {
        View v = from;
        for (int depth = 0; v != null && depth < 8; depth++) {
            if (v instanceof ViewGroup) {
                ((ViewGroup) v).setClipChildren(false);
                ((ViewGroup) v).setClipToPadding(false);
            }
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
    }

    // ------------------------------------------------------------------ áp cấu hình

    void apply(Bundle b) {
        boolean enabled = Config.getBool(b, Config.ENABLED);
        boolean dual = enabled && Config.getBool(b, Config.DUAL);
        float d = ctx.getResources().getDisplayMetrics().density;

        // chiều cao
        heightPx = dual ? Math.round(Config.getInt(b, Config.HEIGHT) * d) : 0;
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        if (lp != null) {
            int want = heightPx > 0 ? heightPx : origHeight;
            if (lp.height != want) { lp.height = want; root.setLayoutParams(lp); }
        }

        pushWindow();

        // vị trí 3 nhóm view
        cells[0] = dual ? Config.getInt(b, Config.POS_CLOCK) : -1;
        cells[1] = dual ? Config.getInt(b, Config.POS_NOTIF) : -1;
        cells[2] = dual ? Config.getInt(b, Config.POS_ICONS) : -1;

        // hình nền
        Bitmap bg = (enabled && Config.getBool(b, Config.BG_ENABLED)) ? img("bg", b) : null;
        root.setBackground(bg != null
                ? new BgDrawable(bg, Config.getInt(b, Config.BG_SCALE), Config.getInt(b, Config.BG_ALPHA))
                : origBg);

        // lớp phủ: đồng hồ analog + nhãn
        boolean an = enabled && Config.getBool(b, Config.AN_ENABLED);
        labelMode = enabled ? Config.getInt(b, Config.LABEL_MODE) : 0;
        labelText = Config.getString(b, Config.LABEL_TEXT);
        boolean lb = labelMode != 0;
        if (an || lb) ensureOverlay();
        if (overlay != null) {
            overlay.setVisibility(an || lb ? View.VISIBLE : View.GONE);
            analog.setVisibility(an ? View.VISIBLE : View.GONE);
            label.setVisibility(lb ? View.VISIBLE : View.GONE);
            int[] hg = {Gravity.START, Gravity.CENTER_HORIZONTAL, Gravity.END};
            int[] vg = {Gravity.TOP, Gravity.CENTER_VERTICAL, Gravity.BOTTOM};
            if (an) {
                int size = Math.round(Config.getInt(b, Config.AN_SIZE) * d);
                int pos = Math.max(0, Math.min(8, Config.getInt(b, Config.AN_POS)));
                FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(size, size);
                alp.gravity = hg[pos % 3] | vg[pos / 3];
                analog.setLayoutParams(alp);
                analog.setTranslationX(Config.getInt(b, Config.AN_DX) * d);
                analog.setTranslationY(Config.getInt(b, Config.AN_DY) * d);
                analog.configure(img("dial", b), img("hour", b), img("minute", b), img("second", b),
                        Config.getBool(b, Config.AN_SEC), parseColor(Config.getString(b, Config.AN_COLOR), Color.WHITE));
            }
            if (lb) {
                int pos = Math.max(0, Math.min(8, Config.getInt(b, Config.LABEL_POS)));
                FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                llp.gravity = hg[pos % 3] | vg[pos / 3];
                label.setLayoutParams(llp);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, Config.getInt(b, Config.LABEL_SIZE));
                label.setTextColor(parseColor(Config.getString(b, Config.LABEL_COLOR), Color.WHITE));
                label.setTranslationX(Config.getInt(b, Config.LABEL_DX) * d);
                label.setTranslationY(Config.getInt(b, Config.LABEL_DY) * d);
                refreshLabel();
            }
        }
        if (clock != null) clock.setAlpha(an && Config.getBool(b, Config.AN_HIDE) ? 0f : 1f);

        // đường pin ở viền dưới (foreground của root)
        if (enabled && Config.getBool(b, Config.BAT_LINE)) {
            if (batLine == null) batLine = new BatteryLineDrawable();
            batLine.configure(Math.max(1, Math.round(Config.getInt(b, Config.BAT_H) * d)),
                    parseColor(Config.getString(b, Config.BAT_COL), 0xFFE53935),
                    parseColor(Config.getString(b, Config.BAT_COL_CHG), 0xFF4CAF50));
            batLine.setState(batLevel, batCharging);
            root.setForeground(batLine);
        } else {
            root.setForeground(null);
        }

        root.requestLayout();
        relayout();
    }

    private void ensureOverlay() {
        if (overlay != null || contents == null) return;
        overlay = new FrameLayout(ctx);
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);
        analog = new AnalogClockView(ctx);
        overlay.addView(analog, new FrameLayout.LayoutParams(1, 1));
        label = new TextView(ctx);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        overlay.addView(label, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contents.addView(overlay, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        noClip(contents);
    }

    private Bitmap img(String name, Bundle b) {
        long stamp = b == null ? 0 : b.getLong("img_" + name, 0);
        if (stamp == 0) { imgCache.remove(name); return null; }
        Object[] e = imgCache.get(name);
        if (e != null && (Long) e[0] == stamp) return (Bitmap) e[1];
        Bitmap bm = null;
        try (ParcelFileDescriptor pfd = ctx.getContentResolver()
                .openFileDescriptor(Uri.parse(Config.IMG_URI + name), "r")) {
            if (pfd != null) bm = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
        } catch (Throwable t) {
            XposedBridge.log(TAG + "đọc ảnh " + name + ": " + Log.getStackTraceString(t));
        }
        imgCache.put(name, new Object[]{stamp, bm});
        return bm;
    }

    // ------------------------------------------------------------------ pin, nhãn, cửa sổ

    private static int parseColor(String s, int def) {
        try { return Color.parseColor(s); } catch (Exception e) { return def; }
    }

    private void readBattery(Intent i) {
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) batLevel = level * 100 / scale;
        batCharging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        if (batLine != null) batLine.setState(batLevel, batCharging);
    }

    private void refreshLabel() {
        if (label == null) return;
        label.setText(labelMode == 1 ? carrierName() : labelMode == 2 ? labelText : "");
    }

    private String carrierName() {
        try {
            SubscriptionManager sm = (SubscriptionManager) ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> l = sm.getActiveSubscriptionInfoList();
            if (l != null) {
                StringBuilder sb = new StringBuilder();
                for (SubscriptionInfo si : l) {
                    CharSequence n = si.getCarrierName();
                    if (n != null && n.length() > 0) {
                        if (sb.length() > 0) sb.append(" | ");
                        sb.append(n);
                    }
                }
                if (sb.length() > 0) return sb.toString();
            }
        } catch (Throwable ignored) {}
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            String n = tm.getNetworkOperatorName();
            if (n == null || n.isEmpty()) n = tm.getSimOperatorName();
            return n == null ? "" : n;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Cập nhật chiều cao của chính cửa sổ status bar (hook WindowManagerGlobal sẽ chỉnh lp). */
    private void pushWindow() {
        try {
            View rv = root.getRootView();
            ViewGroup.LayoutParams wl = rv.getLayoutParams();
            XposedBridge.log(TAG + "window root=" + rv.getClass().getName() + " lp="
                    + (wl == null ? "null" : wl.getClass().getSimpleName()) + " h=" + (wl == null ? -1 : wl.height));
            if (wl instanceof WindowManager.LayoutParams && rv.isAttachedToWindow()) {
                SysUiHook.adjustWindow(rv, wl);
                ctx.getSystemService(WindowManager.class).updateViewLayout(rv, wl);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "pushWindow: " + Log.getStackTraceString(t));
        }
    }

    // ------------------------------------------------------------------ bố cục lưới 3x3

    private void onLayout() {
        if (heightPx > 0) {
            ViewGroup.LayoutParams lp = root.getLayoutParams();
            if (lp != null && lp.height != heightPx) { lp.height = heightPx; root.setLayoutParams(lp); }
        }
        relayout();
    }

    private void relayout() {
        if (contents == null) return;
        int w = contents.getWidth(), h = contents.getHeight();
        if (w == 0 || h == 0) return;
        place(clockBox != null ? clockBox : clock, cells[0], false);
        place(notif, cells[1], true);
        place(sysGroup, cells[2], false);
    }

    /** cell = hàng*3 + cột (cột 0 trái/1 giữa/2 phải; hàng 0 trên/1 giữa/2 dưới). */
    private void place(View v, int cell, boolean measureContent) {
        if (v == null) return;
        if (cell < 0 || cell > 8) { v.setTranslationX(0); v.setTranslationY(0); return; }
        int col = cell % 3, row = cell / 3;
        int W = contents.getWidth(), H = contents.getHeight();
        int pl = contents.getPaddingLeft(), pr = contents.getPaddingRight();
        int pt = contents.getPaddingTop(), pb = contents.getPaddingBottom();

        int[] off = offsetIn(contents, v, false);
        int minX = 0, maxX = v.getWidth();
        if (measureContent) {
            int[] mm = {Integer.MAX_VALUE, Integer.MIN_VALUE};
            extent(v, v, mm);
            if (mm[1] <= mm[0]) { v.setTranslationX(0); v.setTranslationY(0); return; }
            minX = mm[0]; maxX = mm[1];
        }
        int cw = maxX - minX;
        float targetLeft = col == 0 ? pl : col == 1 ? pl + (W - pl - pr - cw) / 2f : W - pr - cw;
        float tx = targetLeft - (off[0] + minX);

        float usableH = H - pt - pb;
        float cy = pt + usableH * (row == 0 ? 0.25f : row == 1 ? 0.5f : 0.75f);
        float vcy = off[1] + v.getHeight() / 2f;

        v.setTranslationX(tx);
        v.setTranslationY(cy - vcy);
    }

    /** Vị trí (left, top) của v trong hệ toạ độ của anc, theo layout (không tính translation của v). */
    private static int[] offsetIn(View anc, View v, boolean withTranslation) {
        float x = 0, y = 0;
        View c = v;
        while (c != anc && c.getParent() instanceof View) {
            x += c.getLeft() + (withTranslation ? c.getTranslationX() : 0);
            y += c.getTop() + (withTranslation ? c.getTranslationY() : 0);
            c = (View) c.getParent();
        }
        return new int[]{Math.round(x), Math.round(y)};
    }

    /** Khoảng [minX, maxX] mà các view lá đang hiển thị chiếm trong top (có tính translationX của icon). */
    private static void extent(View top, View v, int[] mm) {
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() == 0f) return;
        if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) extent(top, g.getChildAt(i), mm);
        } else if (v.getWidth() > 0 && v != top) {
            int[] o = offsetIn(top, v, true);
            mm[0] = Math.min(mm[0], o[0]);
            mm[1] = Math.max(mm[1], o[0] + v.getWidth());
        }
    }
}
