package com.dualsb.oneui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.view.ViewTreeObserver;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewStub;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * Điều khiển 1 PhoneStatusBarView.
 *
 * Cấu trúc One UI 7 (suy ra từ status_bar.xml):
 *   PhoneStatusBarView (root, vertical)
 *     ├─ ImageView notification_lights_out
 *     └─ RelativeLayout status_bar_contents
 *          ├─ trái: clock + notification icons
 *          ├─ giữa: cutout space
 *          └─ phải: system icons + pin
 * Không reparent view gốc; chỉ dịch bằng translationX/Y vào lưới 3x3, và thêm một lớp phủ
 * (overlay) chứa: đồng hồ analog, nhãn, giờ, ngày (dương/âm), pin dạng vòng.
 */
final class BarController {
    private static final String PKG = "com.android.systemui";
    private static final String TAG = SysUiHook.TAG;
    private static final int[] HG = {Gravity.START, Gravity.CENTER_HORIZONTAL, Gravity.END};
    private static final int[] VG = {Gravity.TOP, Gravity.CENTER_VERTICAL, Gravity.BOTTOM};

    final ViewGroup root;
    private final Context ctx;
    private final Drawable origBg;
    private final int origHeight;

    private RelativeLayout contents;
    private View clock, clockBox, notif, sysGroup, stockBattery;

    private FrameLayout overlay;
    private AnalogClockView analog;
    private TextView label;
    private TextClock timeClock;
    private TextView dateText;
    private BatteryRingView ring;

    private int labelMode;
    private String labelText = "";
    private boolean dateOn;
    private int dateMode, lunarFmt;
    private String dateFmt = "EEE, d MMM", dateCustom = "";
    private boolean lunarNewline;

    private Bundle cfg;
    private int lastAuto;
    private boolean netRegistered;
    private int wifiLevel = -1, sigLevel = 0;
    private boolean inService = true;
    private BatteryLineDrawable batLine;
    private int batLevel = 100;
    private boolean batCharging, batSaver;

    private int heightPx;
    private final int[] cells = {-1, -1, -1, -1};   // đồng hồ, thông báo, biểu tượng, Now Bar
    private boolean dumped;
    private boolean ringOn, hideBattery, hideWifi, hideSig;
    private final Set<View> hidden = new HashSet<>();
    private final List<View> wifiViews = new ArrayList<>(), mobileViews = new ArrayList<>();
    private String lastIcons = "";
    private String netType = "";
    private boolean chipFwd;
    private final List<ProxyView> proxies = new ArrayList<>();
    private String lastChips = "";
    private final Map<String, Object[]> imgCache = new HashMap<>();

    private final BroadcastReceiver rcv = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(a)) readBattery(i);
            else if ("android.os.action.POWER_SAVE_MODE_CHANGED".equals(a)) readPower();
            else { refreshLabel(); refreshDate(); }
        }
    };

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
            stockBattery = byName("battery");
            if (stockBattery == null) stockBattery = findByClass(contents, "BatteryMeterView");
            noClip(contents);
        }
        XposedBridge.log(TAG + "controller: contents=" + (contents != null) + " clock=" + (clock != null)
                + " clockBox=" + (clockBox != null) + " notif=" + (notif != null)
                + " sys=" + (sysGroup != null) + " battery=" + (stockBattery != null));

        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            f.addAction("android.os.action.POWER_SAVE_MODE_CHANGED");
            f.addAction("android.intent.action.SERVICE_STATE");
            f.addAction("android.intent.action.SIM_STATE_CHANGED");
            f.addAction(Intent.ACTION_TIME_TICK);
            f.addAction(Intent.ACTION_DATE_CHANGED);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            Intent sticky = ctx.registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED);
            if (sticky != null) readBattery(sticky);
            readPower();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "registerReceiver: " + t);
        }

        root.getViewTreeObserver().addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
            @Override public void onDraw() {
                int c = autoColor();
                if (c != lastAuto) {
                    lastAuto = c;
                    root.post(new Runnable() { @Override public void run() { applyColors(); } });
                }
            }
        });

        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (ringOn || !hidden.isEmpty()) applyHides();
                return true;
            }
        });

        root.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() { onLayout(); }
        });
    }

    private View byName(String name) {
        int id = root.getResources().getIdentifier(name, "id", PKG);
        return id == 0 ? null : root.findViewById(id);
    }

    private static View findByClass(View v, String part) {
        if (v.getClass().getName().contains(part)) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByClass(g.getChildAt(i), part);
                if (r != null) return r;
            }
        }
        return null;
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
        cfg = b;
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

        // vị trí 3 nhóm view gốc
        cells[0] = dual ? Config.getInt(b, Config.POS_CLOCK) : -1;
        cells[1] = dual ? Config.getInt(b, Config.POS_NOTIF) : -1;
        cells[2] = dual ? Config.getInt(b, Config.POS_ICONS) : -1;
        cells[3] = dual ? Config.getInt(b, Config.POS_CHIP) : -1;

        // hình nền
        Bitmap bg = (enabled && Config.getBool(b, Config.BG_ENABLED)) ? img("bg", b) : null;
        root.setBackground(bg != null
                ? new BgDrawable(bg, Config.getInt(b, Config.BG_SCALE), Config.getInt(b, Config.BG_ALPHA))
                : origBg);

        // các lớp phủ
        boolean an = enabled && Config.getBool(b, Config.AN_ENABLED);
        labelMode = enabled ? Config.getInt(b, Config.LABEL_MODE) : 0;
        labelText = Config.getString(b, Config.LABEL_TEXT);
        boolean lb = labelMode != 0;
        boolean tm = enabled && Config.getBool(b, Config.TIME_ENABLED);
        dateOn = enabled && Config.getBool(b, Config.DATE_ENABLED);
        boolean rg = enabled && Config.getBool(b, Config.RING_ENABLED);

        chipFwd = dual && Config.getBool(b, Config.CHIP_FWD);
        boolean needOverlay = an || lb || tm || dateOn || rg || chipFwd;
        if (needOverlay) ensureOverlay();
        if (overlay != null) {
            overlay.setVisibility(needOverlay ? View.VISIBLE : View.GONE);
            analog.setVisibility(an ? View.VISIBLE : View.GONE);
            label.setVisibility(lb ? View.VISIBLE : View.GONE);
            timeClock.setVisibility(tm ? View.VISIBLE : View.GONE);
            dateText.setVisibility(dateOn ? View.VISIBLE : View.GONE);
            ring.setVisibility(rg ? View.VISIBLE : View.GONE);

            if (an) {
                int size = Math.round(Config.getInt(b, Config.AN_SIZE) * d);
                put(analog, Config.getInt(b, Config.AN_POS), size, size,
                        Config.getInt(b, Config.AN_DX) * d, Config.getInt(b, Config.AN_DY) * d);
                analog.configure(img("dial", b), img("hour", b), img("minute", b), img("second", b),
                        Config.getBool(b, Config.AN_SEC), colorOf(b, Config.AN_COLOR));
            }
            if (lb) {
                put(label, Config.getInt(b, Config.LABEL_POS), -2, -2,
                        Config.getInt(b, Config.LABEL_DX) * d, Config.getInt(b, Config.LABEL_DY) * d);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, Config.getInt(b, Config.LABEL_SIZE));
                label.setTextColor(colorOf(b, Config.LABEL_COLOR));
                refreshLabel();
            }
            if (tm) {
                put(timeClock, Config.getInt(b, Config.TIME_POS), -2, -2,
                        Config.getInt(b, Config.TIME_DX) * d, Config.getInt(b, Config.TIME_DY) * d);
                String fmt = Config.getString(b, Config.TIME_FMT);
                timeClock.setFormat24Hour(fmt);
                timeClock.setFormat12Hour(fmt);
                timeClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, Config.getInt(b, Config.TIME_SIZE));
                timeClock.setTextColor(colorOf(b, Config.TIME_COLOR));
            }
            if (dateOn) {
                dateMode = Config.getInt(b, Config.DATE_MODE);
                dateFmt = Config.getString(b, Config.DATE_FMT);
                dateCustom = Config.getString(b, Config.DATE_CUSTOM);
                lunarFmt = Config.getInt(b, Config.LUNAR_FMT);
                lunarNewline = Config.getBool(b, Config.LUNAR_NEWLINE);
                put(dateText, Config.getInt(b, Config.DATE_POS), -2, -2,
                        Config.getInt(b, Config.DATE_DX) * d, Config.getInt(b, Config.DATE_DY) * d);
                dateText.setTextSize(TypedValue.COMPLEX_UNIT_SP, Config.getInt(b, Config.DATE_SIZE));
                dateText.setTextColor(colorOf(b, Config.DATE_COLOR));
                boolean two = lunarNewline && dateMode == 2;
                dateText.setSingleLine(!two);
                if (two) dateText.setMaxLines(2);
                dateText.setGravity(Gravity.CENTER_HORIZONTAL);
                refreshDate();
            }
            if (rg) {
                int size = Math.round(Config.getInt(b, Config.RING_SIZE) * d);
                put(ring, Config.getInt(b, Config.RING_POS), size, size,
                        Config.getInt(b, Config.RING_DX) * d, Config.getInt(b, Config.RING_DY) * d);
                boolean showWifi = Config.getBool(b, Config.RING_WIFI);
                boolean showType = Config.getBool(b, Config.RING_NETTYPE);
                boolean showDots = Config.getBool(b, Config.RING_SIGNAL);
                ring.configure(Math.max(1f, Config.getInt(b, Config.RING_STROKE) * d),
                        Config.getBool(b, Config.RING_PERCENT), Config.getBool(b, Config.RING_BOLT),
                        Config.getInt(b, Config.RING_PCT_H),
                        colorOf(b, Config.RING_COLOR),
                        parseColor(Config.getString(b, Config.RING_COL_CHG), 0xFF4CAF50),
                        parseColor(Config.getString(b, Config.RING_COL_SAVER), 0xFFFFC107),
                        parseColor(Config.getString(b, Config.RING_COL_LOW), 0xFFE53935),
                        Config.getInt(b, Config.RING_LOW_LEVEL),
                        showWifi, showType, showDots);
                ring.setState(batLevel, batCharging, batSaver);
                if (showWifi || showType || showDots) ensureNet();
                pushNet();
            }
        }

        // ẩn đồng hồ số gốc / pin gốc (chỉ làm mờ, không đổi cấu trúc view của Samsung)
        boolean hideClock = enabled && (Config.getBool(b, Config.DIG_REPLACE)
                || (an && Config.getBool(b, Config.AN_HIDE)));
        if (clock != null) clock.setAlpha(hideClock ? 0f : 1f);
        ringOn = rg;
        hideBattery = Config.getBool(b, Config.RING_HIDE_STOCK);
        hideWifi = Config.getBool(b, Config.RING_HIDE_WIFI);
        hideSig = Config.getBool(b, Config.RING_HIDE_SIG);
        if (rg && (hideWifi || hideSig)) scanSysIcons();
        applyHides();

        // đường pin ở viền dưới (foreground của root)
        if (enabled && Config.getBool(b, Config.BAT_LINE)) {
            if (batLine == null) batLine = new BatteryLineDrawable();
            batLine.configure(Math.max(1, Math.round(Config.getInt(b, Config.BAT_H) * d)),
                    parseColor(Config.getString(b, Config.BAT_COL), 0xFFE53935),
                    parseColor(Config.getString(b, Config.BAT_COL_CHG), 0xFF4CAF50),
                    Config.getBool(b, Config.BAT_ANIM),
                    Config.getInt(b, Config.BAT_SPEED) * 25f * d,
                    Config.getInt(b, Config.BAT_GAP) * d);
            batLine.setState(batLevel, batCharging);
            root.setForeground(batLine);
        } else {
            root.setForeground(null);
        }

        root.requestLayout();
        relayout();
    }

    private TextView newText() {
        TextView t = new TextView(ctx);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
    }

    private void ensureOverlay() {
        if (overlay != null || contents == null) return;
        overlay = new FrameLayout(ctx);
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);
        analog = new AnalogClockView(ctx);
        label = newText();
        timeClock = new TextClock(ctx);
        timeClock.setSingleLine(true);
        timeClock.setIncludeFontPadding(false);
        dateText = newText();
        ring = new BatteryRingView(ctx);
        overlay.addView(analog, new FrameLayout.LayoutParams(1, 1));
        overlay.addView(label, new FrameLayout.LayoutParams(-2, -2));
        overlay.addView(timeClock, new FrameLayout.LayoutParams(-2, -2));
        overlay.addView(dateText, new FrameLayout.LayoutParams(-2, -2));
        overlay.addView(ring, new FrameLayout.LayoutParams(1, 1));
        contents.addView(overlay, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        noClip(contents);
    }

    /** Đặt view vào ô pos (0..8) của overlay; w/h = -2 là wrap_content. */
    private void put(View v, int pos, int w, int h, float dx, float dy) {
        pos = Math.max(0, Math.min(8, pos));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = HG[pos % 3] | VG[pos / 3];
        v.setLayoutParams(lp);
        v.setTranslationX(dx);
        v.setTranslationY(dy);
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

    /** Màu chữ của hệ thống trên status bar (tự đổi sáng/tối theo nền). */
    private int autoColor() {
        if (clock instanceof TextView) {
            int c = ((TextView) clock).getCurrentTextColor();
            return 0xFF000000 | (c & 0x00FFFFFF);
        }
        boolean night = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return night ? Color.WHITE : Color.BLACK;
    }

    private int colorOf(Bundle b, String key) {
        String v = Config.getString(b, key);
        return "auto".equals(v) ? autoColor() : parseColor(v, Color.WHITE);
    }

    /** Cập nhật lại các màu "tự động" khi hệ thống đổi màu chữ. */
    private void applyColors() {
        Bundle b = cfg;
        if (b == null || overlay == null) return;
        label.setTextColor(colorOf(b, Config.LABEL_COLOR));
        timeClock.setTextColor(colorOf(b, Config.TIME_COLOR));
        dateText.setTextColor(colorOf(b, Config.DATE_COLOR));
        ring.setNormalColor(colorOf(b, Config.RING_COLOR));
        analog.setHandColor(colorOf(b, Config.AN_COLOR));
    }

    // ---- ẩn biểu tượng Wi-Fi / sóng / pin gốc của hệ thống khi dùng khối vòng
    private void scanSysIcons() {
        wifiViews.clear();
        mobileViews.clear();
        if (sysGroup != null) collectIcons(sysGroup);
        StringBuilder sb = new StringBuilder();
        for (View v : wifiViews) sb.append("wifi:").append(idName(v)).append('/').append(v.getClass().getSimpleName()).append(' ');
        for (View v : mobileViews) sb.append("mobile:").append(idName(v)).append('/').append(v.getClass().getSimpleName()).append(' ');
        String sig = sb.toString();
        if (!sig.equals(lastIcons)) {
            lastIcons = sig;
            XposedBridge.log(TAG + "biểu tượng hệ thống sẽ ẩn: " + (sig.isEmpty() ? "(không tìm thấy)" : sig));
        }
    }

    private void collectIcons(View v) {
        String n = (idName(v) + " " + v.getClass().getSimpleName()).toLowerCase();
        if (n.contains("airplane") || n.contains("hotspot")) return;
        if (n.contains("wifi")) { wifiViews.add(v); return; }
        if (n.contains("mobile") || n.contains("signal") || n.contains("cellular")) { mobileViews.add(v); return; }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectIcons(g.getChildAt(i));
        }
    }

    private void setHidden(View v, boolean hide) {
        if (v == null) return;
        if (hide) {
            if (v.getAlpha() != 0f) v.setAlpha(0f);
            hidden.add(v);
        } else if (hidden.remove(v)) {
            v.setAlpha(1f);
        }
    }

    private void applyHides() {
        setHidden(stockBattery, ringOn && hideBattery);
        for (View v : wifiViews) setHidden(v, ringOn && hideWifi);
        for (View v : mobileViews) setHidden(v, ringOn && hideSig);
        if (!ringOn && !hidden.isEmpty()) {          // tắt khối vòng: trả lại hết
            for (View v : new ArrayList<>(hidden)) { v.setAlpha(1f); hidden.remove(v); }
        }
    }

    // ---- Wi-Fi + sóng di động cho khối pin vòng
    private void pushNet() {
        if (ring != null) ring.setNet(inService ? sigLevel : -1, wifiLevel, netType);
    }

    private void ensureNet() {
        if (netRegistered) return;
        netRegistered = true;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            final WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            cm.registerNetworkCallback(
                    new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    new ConnectivityManager.NetworkCallback() {
                        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                            Object ti = caps.getTransportInfo();
                            if (ti instanceof WifiInfo) {
                                int max = Math.max(1, wm.getMaxSignalLevel());
                                wifiLevel = Math.min(4, wm.calculateSignalLevel(((WifiInfo) ti).getRssi()) * 4 / max);
                                pushNet();
                            }
                        }
                        @Override public void onLost(Network n) { wifiLevel = -1; pushNet(); }
                    }, SysUiHook.UI);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "wifi callback: " + Log.getStackTraceString(t));
        }
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            int sub = SubscriptionManager.getDefaultDataSubscriptionId();
            if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) tm = tm.createForSubscriptionId(sub);
            try { netType = typeName(tm.getDataNetworkType(), TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE); }
            catch (Throwable ignored) {}
            tm.registerTelephonyCallback(ctx.getMainExecutor(), new CellCb());
        } catch (Throwable t) {
            XposedBridge.log(TAG + "cell callback: " + Log.getStackTraceString(t));
        }
    }

    private static String typeName(int type, int override) {
        if (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA
                || override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) return "5G";
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_NR: return "5G";
            case TelephonyManager.NETWORK_TYPE_LTE: return "4G";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
                return "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
            case TelephonyManager.NETWORK_TYPE_IDEN:
                return "2G";
            default: return "";
        }
    }

    private final class CellCb extends TelephonyCallback implements
            TelephonyCallback.SignalStrengthsListener, TelephonyCallback.ServiceStateListener,
            TelephonyCallback.DisplayInfoListener {
        @Override public void onDisplayInfoChanged(TelephonyDisplayInfo info) {
            netType = typeName(info.getNetworkType(), info.getOverrideNetworkType());
            pushNet();
        }
        @Override public void onSignalStrengthsChanged(SignalStrength s) {
            sigLevel = Math.max(0, Math.min(4, s.getLevel()));
            pushNet();
        }
        @Override public void onServiceStateChanged(ServiceState ss) {
            inService = ss.getState() == ServiceState.STATE_IN_SERVICE;
            pushNet();
        }
    }

    private static int parseColor(String s, int def) {
        try { return Color.parseColor(s); } catch (Exception e) { return def; }
    }

    // ------------------------------------------------------------------ pin, nhãn, ngày, cửa sổ

    private void readBattery(Intent i) {
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) batLevel = level * 100 / scale;
        batCharging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        pushBattery();
    }

    private void readPower() {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            batSaver = pm != null && pm.isPowerSaveMode();
        } catch (Throwable ignored) {}
        pushBattery();
    }

    private void pushBattery() {
        if (batLine != null) batLine.setState(batLevel, batCharging);
        if (ring != null) ring.setState(batLevel, batCharging, batSaver);
    }

    private void refreshLabel() {
        if (label == null) return;
        label.setText(labelMode == 1 ? carrierName() : labelMode == 2 ? labelText : "");
    }

    private void refreshDate() {
        if (dateText == null || !dateOn) return;
        Calendar cal = Calendar.getInstance();
        String solar = null, lunar = null;
        if (dateMode != 1) {
            String pat = dateCustom == null || dateCustom.trim().isEmpty() ? dateFmt : dateCustom.trim();
            try {
                Locale loc = ctx.getResources().getConfiguration().getLocales().get(0);
                solar = new SimpleDateFormat(pat, loc).format(cal.getTime());
            } catch (Exception e) {
                solar = pat;
            }
        }
        if (dateMode != 0) {
            int[] l = LunarCalendar.solarToLunar(cal.get(Calendar.DAY_OF_MONTH),
                    cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR));
            lunar = LunarCalendar.format(l, lunarFmt);
        }
        String out = solar != null && lunar != null
                ? solar + (lunarNewline ? "\n" : "  ·  ") + lunar
                : solar != null ? solar : lunar;
        dateText.setText(out);
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
        if (ringOn && (hideWifi || hideSig)) scanSysIcons();
        if (heightPx > 0) {
            ViewGroup.LayoutParams lp = root.getLayoutParams();
            if (lp != null && lp.height != heightPx) { lp.height = heightPx; root.setLayoutParams(lp); }
        }
        relayout();
    }

    private static final class Item {
        final View v;
        final int minX, w;
        Item(View v, int minX, int w) { this.v = v; this.minX = minX; this.w = w; }
    }

    private static void reset(View v) { v.setTranslationX(0); v.setTranslationY(0); }

    private void relayout() {
        if (contents == null) return;
        int W = contents.getWidth(), H = contents.getHeight();
        if (W == 0 || H == 0) return;
        {
            int pt = contents.getPaddingTop(), pb = contents.getPaddingBottom();
            float uh = H - pt - pb;
            SysUiHook.setRows(pt + uh * 0.25f, pt + uh * 0.75f);   // cho thanh trạng thái màn hình khóa dùng chung
        }

        List<Item> clockItems = new ArrayList<>(), sysItems = new ArrayList<>();
        List<Item> notifItems = new ArrayList<>(), chipItems = new ArrayList<>();
        View cv = clockBox != null ? clockBox : clock;
        if (cv != null) clockItems.add(new Item(cv, 0, cv.getWidth()));
        if (sysGroup != null) sysItems.add(new Item(sysGroup, 0, sysGroup.getWidth()));
        if (notif != null) {
            int[] mm = {Integer.MAX_VALUE, Integer.MIN_VALUE};
            extent(notif, notif, mm);
            if (mm[1] > mm[0]) notifItems.add(new Item(notif, mm[0], mm[1] - mm[0])); else reset(notif);
        }
        collectChips(chipItems);

        placeRow(clockItems, cells[0]);
        placeRow(sysItems, cells[2]);
        int nc = cells[1];
        int cc = cells[3] == -2 ? nc : cells[3];
        if (nc >= 0 && cc == nc) {                       // Now Bar đi cùng nhóm thông báo: [Now Bar][icon thông báo]
            List<Item> row = new ArrayList<>(chipItems);
            row.addAll(notifItems);
            placeRow(row, nc);
        } else {
            placeRow(notifItems, nc);
            placeRow(chipItems, cc);
        }
        syncProxies(chipItems, chipFwd && cc >= 0);
    }

    /**
     * Now Bar đã bị dịch bằng translation nên đặt một "vùng chạm" trong suốt đúng chỗ nó đang hiển thị,
     * chuyển tiếp toàn bộ thao tác chạm tới chip gốc (để bấm vẫn mở rộng Now Bar như bình thường).
     */
    private void syncProxies(List<Item> chips, boolean on) {
        if (overlay == null) return;
        int n = on ? chips.size() : 0;
        while (proxies.size() < n) {
            ProxyView p = new ProxyView(ctx);
            overlay.addView(p, new FrameLayout.LayoutParams(1, 1));
            proxies.add(p);
        }
        for (int i = 0; i < proxies.size(); i++) {
            ProxyView p = proxies.get(i);
            if (i < n) {
                Item it = chips.get(i);
                int[] off = offsetIn(contents, it.v, true);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) p.getLayoutParams();
                int hh = it.v.getHeight();
                if (lp.width != it.w || lp.height != hh || lp.gravity != (Gravity.TOP | Gravity.START)) {
                    lp.width = it.w; lp.height = hh; lp.gravity = Gravity.TOP | Gravity.START;
                    p.setLayoutParams(lp);
                }
                p.target = it.v;
                p.setTranslationX(off[0] - overlay.getLeft());
                p.setTranslationY(off[1] - overlay.getTop());
                p.setVisibility(View.VISIBLE);
            } else {
                p.target = null;
                p.setVisibility(View.GONE);
            }
        }
    }

    private static final class ProxyView extends View {
        View target;
        private int logged;
        ProxyView(Context c) { super(c); }
        @Override public boolean onTouchEvent(MotionEvent e) {
            View t = target;
            if (t == null || t.getVisibility() != View.VISIBLE) return false;
            MotionEvent c = MotionEvent.obtain(e);            // toạ độ proxy == toạ độ chip (cùng kích thước)
            boolean r = t.dispatchTouchEvent(c);
            c.recycle();
            if (logged < 4 && e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                logged++;
                XposedBridge.log(SysUiHook.TAG + "chạm Now Bar: " + t.getClass().getSimpleName() + " -> " + r);
            }
            return r;
        }
    }

    /** Xếp các view liên tiếp nhau vào ô cell (0..8); cell < 0 thì trả về vị trí gốc. */
    private void placeRow(List<Item> items, int cell) {
        if (items.isEmpty()) return;
        if (cell < 0 || cell > 8) { for (Item it : items) reset(it.v); return; }
        int col = cell % 3, row = cell / 3;
        int W = contents.getWidth(), H = contents.getHeight();
        int pl = contents.getPaddingLeft(), pr = contents.getPaddingRight();
        int pt = contents.getPaddingTop(), pb = contents.getPaddingBottom();
        int gap = Math.round(4 * ctx.getResources().getDisplayMetrics().density);

        int total = gap * (items.size() - 1);
        for (Item it : items) total += it.w;
        float x = col == 0 ? pl : col == 1 ? pl + (W - pl - pr - total) / 2f : W - pr - total;
        float usableH = H - pt - pb;
        float cy = pt + usableH * (row == 0 ? 0.25f : row == 1 ? 0.5f : 0.75f);

        for (Item it : items) {
            int[] off = offsetIn(contents, it.v, false);
            it.v.setTranslationX(x - (off[0] + it.minX));
            it.v.setTranslationY(cy - (off[1] + it.v.getHeight() / 2f));
            x += it.w + gap;
        }
    }

    // ---- Now Bar / chip hoạt động: các view anh em của vùng thông báo trong cột trái
    private String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return "-";
        try { return v.getResources().getResourceEntryName(id); }
        catch (Throwable t) { return "0x" + Integer.toHexString(id); }
    }

    private void collectChips(List<Item> out) {
        if (clockBox == null || !(clockBox.getParent() instanceof ViewGroup)) return;
        ViewGroup left = (ViewGroup) clockBox.getParent();
        if (!dumped && left.getWidth() > 0) {
            dumped = true;
            StringBuilder sb = new StringBuilder(TAG + "cột trái:");
            dump(left, 0, sb);
            XposedBridge.log(sb.toString());
        }
        Set<View> path = new HashSet<>();
        View c = notif;
        while (c != null && c != left) {
            path.add(c);
            c = c.getParent() instanceof View ? (View) c.getParent() : null;
        }
        if (c != left) path.clear();
        scan(left, path, out);

        StringBuilder sig = new StringBuilder();
        for (Item it : out) sig.append(idName(it.v)).append('/').append(it.v.getClass().getSimpleName()).append(' ');
        String s = sig.toString();
        if (!s.equals(lastChips)) {
            lastChips = s;
            XposedBridge.log(TAG + "Now Bar/chip: " + (s.isEmpty() ? "(không có)" : s));
        }
    }

    private void scan(ViewGroup parent, Set<View> path, List<Item> out) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View ch = parent.getChildAt(i);
            if (ch == clockBox || ch == notif || ch instanceof ViewStub) continue;
            if (path.contains(ch)) {                       // tổ tiên của vùng thông báo: tìm tiếp bên trong
                if (ch instanceof ViewGroup) scan((ViewGroup) ch, path, out);
                continue;
            }
            if (ch.getVisibility() != View.VISIBLE || ch.getWidth() <= 0 || ch.getAlpha() == 0f) continue;
            String n = idName(ch).toLowerCase() + " " + ch.getClass().getSimpleName().toLowerCase();
            if (n.contains("heads_up") || n.contains("headsup")) continue;
            out.add(new Item(ch, 0, ch.getWidth()));
        }
    }

    private void dump(View v, int depth, StringBuilder sb) {
        sb.append("\n");
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName()).append('#').append(idName(v))
                .append(" vis=").append(v.getVisibility()).append(" ").append(v.getWidth()).append('x').append(v.getHeight());
        if (v instanceof ViewGroup && depth < 4) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dump(g.getChildAt(i), depth + 1, sb);
        }
    }

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
