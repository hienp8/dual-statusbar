package com.dualsb.oneui;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Lớp tiện ích đặt TRÊN CÙNG của cửa sổ màn hình khóa: ảnh / GIF, chữ, đồng hồ số, đồng hồ analog,
 * nút ứng dụng và hiệu ứng toàn màn hình. Chỉ hiện khi máy đang khoá và màn hình sáng.
 *
 * Chế độ chỉnh (lw_edit): các mục hiện viền + nhãn; nhấn giữ một mục rồi kéo để di chuyển. Viền xanh = tiện ích của
 * bạn, viền vàng = mục có sẵn của Samsung. Bấm "Xong" để lưu và thoát.
 */
final class LockWidgetLayer extends FrameLayout {

    private static final class Holder {
        String id, type, pkg;
        float x, y;                 // tâm, % kích thước màn hình
        boolean fullscreen;
        int act;
        JSONObject o;
    }

    private final LockScreenController owner;
    private final Context ctx;
    private final float density;
    private final List<Holder> holders = new ArrayList<>();
    private final List<TextView> autoTexts = new ArrayList<>();
    private final List<Runnable> updaters = new ArrayList<>();
    private JSONArray src = new JSONArray();
    private boolean enabled, editing;
    private String signature = "", stampPart = "";
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect doneRect = new Rect();

    // chế độ chỉnh
    private float downX, downY, rawX0, rawY0, offX, offY;
    private boolean dragging;
    private final Rect btnSmall = new Rect(), btnBig = new Rect(), btnReset = new Rect();

    LockWidgetLayer(LockScreenController owner, Context c) {
        super(c);
        this.owner = owner;
        this.ctx = c;
        this.density = c.getResources().getDisplayMetrics().density;
        setClipChildren(false);
        setWillNotDraw(false);
        setVisibility(GONE);
    }

    // ------------------------------------------------------------------ cấu hình
    void apply(Bundle b) {
        boolean master = Config.getBool(b, Config.ENABLED);
        enabled = master && Config.getBool(b, Config.LW_ENABLED);
        editing = enabled && Config.getBool(b, Config.LW_EDIT);
        if (!editing) { sel = null; armed = false; dragging = false; cands = new ArrayList<>(); }
        String json = Config.getString(b, Config.LW_ITEMS);
        String stamps = stampsOf(b, json);
        String sig = json + "|" + stamps;
        if (!sig.equals(signature)) {
            signature = sig;
            stampPart = stamps;
            rebuild(json, b);
        }
        refresh();
        invalidate();
    }

    private String stampsOf(Bundle b, String json) {
        StringBuilder sb = new StringBuilder();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && "image".equals(o.optString("t")))
                    sb.append(b.getLong("img_lw_" + o.optString("id"), 0)).append(',');
            }
        } catch (Exception ignored) {}
        for (String n : new String[]{"dial", "hour", "minute", "second"}) sb.append(b.getLong("img_" + n, 0)).append(',');
        return sb.toString();
    }

    private void rebuild(String json, Bundle b) {
        removeAllViews();
        holders.clear();
        autoTexts.clear();
        updaters.clear();
        try { src = new JSONArray(json); } catch (Exception e) { src = new JSONArray(); return; }
        for (int i = 0; i < src.length(); i++) {
            JSONObject o = src.optJSONObject(i);
            if (o == null || o.optBoolean("off", false)) continue;
            try {
                View v = build(o, b);
                if (v != null) addView(v, v.getLayoutParams());
            } catch (Throwable t) {
                XposedBridge.log(SysUiHook.TAG + "tạo tiện ích lỗi: " + t);
            }
        }
    }

    private int colorOf(String s) {
        if (s == null || s.equals("auto")) return SysUiHook.lastAutoColor;
        try { return Color.parseColor(s); } catch (Exception e) { return Color.WHITE; }
    }

    private void color(TextView tv, String s) {
        if (s == null || s.equals("auto")) autoTexts.add(tv);
        tv.setTextColor(colorOf(s));
    }

    private Bitmap bmp(Bundle b, String name) {
        if (b.getLong("img_" + name, 0) == 0) return null;
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(Uri.parse(Config.IMG_URI + name), "r")) {
            return pfd == null ? null : BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
        } catch (Throwable t) {
            return null;
        }
    }

    private View build(JSONObject o, Bundle b) {
        final Holder h = new Holder();
        h.o = o;
        h.id = o.optString("id");
        h.type = o.optString("t");
        h.x = (float) o.optDouble("x", 50);
        h.y = (float) o.optDouble("y", 40);
        h.act = o.optInt("act", 0);
        h.pkg = o.optString("pkg", "");
        float alpha = Math.max(0f, Math.min(1f, o.optInt("a", 100) / 100f));
        int s = Math.max(4, o.optInt("s", 100));
        View v;
        switch (h.type) {
            case "image": {
                int[] sz = new int[2];
                Drawable dr = NavStyle.decode(ctx, "lw_" + h.id, Math.round(s * density), sz);
                if (dr == null) return null;
                ImageView iv = new ImageView(ctx);
                iv.setImageDrawable(dr);
                iv.setLayoutParams(new FrameLayout.LayoutParams(Math.max(1, sz[0]), Math.max(1, sz[1])));
                v = iv;
                break;
            }
            case "text": {
                TextView tv = new TextView(ctx);
                tv.setText(o.optString("txt", ""));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, s);
                tv.setIncludeFontPadding(false);
                tv.setTypeface(o.optBoolean("b", false) ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                tv.setShadowLayer(3f * density, 0f, density, 0x66000000);
                color(tv, o.optString("col", "auto"));
                tv.setLayoutParams(new FrameLayout.LayoutParams(-2, -2));
                v = tv;
                break;
            }
            case "clock_d": {
                LinearLayout box = new LinearLayout(ctx);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER_HORIZONTAL);
                String col = o.optString("col", "auto");
                TextClock tc = new TextClock(ctx);
                String tf = o.optString("tf", "HH:mm");
                tc.setFormat24Hour(tf);
                tc.setFormat12Hour(tf);
                tc.setTextSize(TypedValue.COMPLEX_UNIT_SP, s);
                tc.setIncludeFontPadding(false);
                tc.setTypeface(Typeface.DEFAULT_BOLD);
                tc.setShadowLayer(3f * density, 0f, density, 0x66000000);
                color(tc, col);
                box.addView(tc, new LinearLayout.LayoutParams(-2, -2));
                final String df = o.optString("df", "");
                final boolean lunar = o.optBoolean("lunar", false);
                final int lf = o.optInt("lf", 0);
                if (!df.isEmpty() || lunar) {
                    final TextView dt = new TextView(ctx);
                    dt.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(8f, s * 0.4f));
                    dt.setGravity(Gravity.CENTER_HORIZONTAL);
                    dt.setIncludeFontPadding(false);
                    dt.setShadowLayer(3f * density, 0f, density, 0x66000000);
                    color(dt, col);
                    box.addView(dt, new LinearLayout.LayoutParams(-2, -2));
                    Runnable up = new Runnable() {
                        @Override public void run() {
                            Calendar cal = Calendar.getInstance();
                            Locale loc = ctx.getResources().getConfiguration().getLocales().get(0);
                            StringBuilder sb = new StringBuilder();
                            if (!df.isEmpty()) sb.append(DateFmt.format(df, cal, loc));
                            if (lunar) {
                                int[] l = LunarCalendar.solarToLunar(cal.get(Calendar.DAY_OF_MONTH),
                                        cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR));
                                if (sb.length() > 0) sb.append('\n');
                                sb.append(LunarCalendar.format(l, lf));
                            }
                            String t = sb.toString();
                            if (!t.contentEquals(dt.getText())) dt.setText(t);
                        }
                    };
                    updaters.add(up);
                    up.run();
                }
                box.setLayoutParams(new FrameLayout.LayoutParams(-2, -2));
                v = box;
                break;
            }
            case "clock_a": {
                AnalogClockView ac = new AnalogClockView(ctx);
                ac.configure(bmp(b, "dial"), bmp(b, "hour"), bmp(b, "minute"), bmp(b, "second"), true, Color.WHITE);
                int px = Math.round(s * density);
                ac.setLayoutParams(new FrameLayout.LayoutParams(px, px));
                v = ac;
                break;
            }
            case "app": {
                Drawable ic = null;
                try { ic = ctx.getPackageManager().getApplicationIcon(h.pkg); } catch (Throwable ignored) {}
                if (ic == null) return null;
                int px = Math.round(s * density);
                ImageView iv = new ImageView(ctx);
                iv.setImageDrawable(ic);
                iv.setLayoutParams(new FrameLayout.LayoutParams(px, px));
                if (h.act == 0) h.act = 100;
                v = iv;
                break;
            }
            case "effect": {
                String fx = o.optString("fx", "snow");
                int type = "rain".equals(fx) ? EffectView.RAIN : "star".equals(fx) ? EffectView.STAR
                        : "bubble".equals(fx) ? EffectView.BUBBLE : EffectView.SNOW;
                EffectView ev = new EffectView(ctx);
                ev.configure(type, o.optInt("n", 60), o.optInt("sp", 100) / 100f, colorOf(o.optString("col", "#FFFFFFFF")));
                ev.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
                h.fullscreen = true;
                v = ev;
                break;
            }
            default:
                return null;
        }
        v.setAlpha(alpha);
        v.setTag(h);
        holders.add(h);
        if (!h.fullscreen && h.act != 0) {
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) { if (!editing) runAct(h); }
            });
        }
        return v;
    }

    private void runAct(Holder h) {
        if (h.act == 100) launch(h.pkg);
        else Actions.run(ctx, h.act, true);
    }

    /** Mở ứng dụng; đang khoá máy thì nhờ SystemUI yêu cầu mở khoá trước. */
    private void launch(String pkg) {
        try {
            Intent it = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (it == null) return;
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ClassLoader cl = ctx.getClassLoader();
                Class<?> starterClz = Class.forName("com.android.systemui.plugins.ActivityStarter", false, cl);
                Class<?> dep = Class.forName("com.android.systemui.Dependency", false, cl);
                Object starter = XposedHelpers.callStaticMethod(dep, "get", starterClz);
                XposedHelpers.callMethod(starter, "postStartActivityDismissingKeyguard", it, 0);
                return;
            } catch (Throwable t) {
                XposedBridge.log(SysUiHook.TAG + "mở app qua ActivityStarter lỗi, dùng cách thường: " + t);
            }
            ctx.startActivity(it);
        } catch (Throwable t) {
            XposedBridge.log(SysUiHook.TAG + "mở app lỗi: " + t);
        }
    }

    // ------------------------------------------------------------------ hiện / ẩn theo trạng thái khoá máy
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            postDelayed(this, 700);
        }
    };

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(tick);
        post(tick);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(tick);
    }

    private void refresh() {
        boolean show = false;
        if (enabled) {
            try {
                KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                show = km != null && km.isKeyguardLocked() && pm != null && pm.isInteractive();
            } catch (Throwable ignored) {}
        }
        int vis = show ? VISIBLE : GONE;
        if (getVisibility() != vis) setVisibility(vis);
        if (!show) return;
        if (getParent() instanceof ViewGroup) {                      // luôn nằm trên cùng
            ViewGroup p = (ViewGroup) getParent();
            if (p.indexOfChild(this) != p.getChildCount() - 1) bringToFront();
        }
        for (Runnable r : new ArrayList<>(updaters)) r.run();
        int col = SysUiHook.lastAutoColor;
        for (TextView t : autoTexts) t.setTextColor(col);
        if (editing) invalidate();
    }

    // ------------------------------------------------------------------ bố cục: x, y theo % màn hình
    @Override protected void onMeasure(int ws, int hs) {
        int W = MeasureSpec.getSize(ws), H = MeasureSpec.getSize(hs);
        setMeasuredDimension(W, H);
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            int cw = lp.width > 0 ? MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY)
                    : lp.width == -1 ? MeasureSpec.makeMeasureSpec(W, MeasureSpec.EXACTLY)
                    : MeasureSpec.makeMeasureSpec(W, MeasureSpec.AT_MOST);
            int ch = lp.height > 0 ? MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
                    : lp.height == -1 ? MeasureSpec.makeMeasureSpec(H, MeasureSpec.EXACTLY)
                    : MeasureSpec.makeMeasureSpec(H, MeasureSpec.AT_MOST);
            c.measure(cw, ch);
        }
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int W = r - l, H = b - t;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            Holder h = (Holder) c.getTag();
            int w = c.getMeasuredWidth(), hh = c.getMeasuredHeight();
            if (h == null || h.fullscreen) {
                c.layout(0, 0, W, H);
            } else {
                int x = Math.round(h.x / 100f * W - w / 2f), y = Math.round(h.y / 100f * H - hh / 2f);
                c.layout(x, y, x + w, y + hh);
            }
        }
    }

    // ------------------------------------------------------------------ chế độ chỉnh: chạm để chọn, kéo để di chuyển
    /** Một mục có thể chọn: tiện ích của bạn (h) hoặc mục có sẵn của Samsung (t). */
    private static final class Cand {
        Holder h;
        View wv;
        LockScreenController.Target t;
    }

    private List<Cand> cands = new ArrayList<>();
    private int candIdx;
    private Cand sel;
    private boolean armed;

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        return editing && getVisibility() == VISIBLE;
    }

    private Rect screenRect(View v) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return new Rect(l[0], l[1], l[0] + v.getWidth(), l[1] + v.getHeight());
    }

    private Rect selRect() {
        if (sel == null) return new Rect();
        return sel.h != null ? screenRect(sel.wv) : owner.rectOf(sel.t.view);
    }

    /** Chọn mục dưới ngón tay (toạ độ màn hình): tiện ích của bạn trước, rồi các mục của Samsung từ nhỏ đến lớn. */
    private void selectAt(float rx, float ry) {
        cands = new ArrayList<>();
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View c = getChildAt(i);
            Holder h = (Holder) c.getTag();
            if (h == null || h.fullscreen) continue;
            if (screenRect(c).contains((int) rx, (int) ry)) {
                Cand k = new Cand();
                k.h = h;
                k.wv = c;
                cands.add(k);
            }
        }
        for (LockScreenController.Target t : owner.candidatesAt(rx, ry)) {
            Cand k = new Cand();
            k.t = t;
            cands.add(k);
        }
        candIdx = 0;
        sel = cands.isEmpty() ? null : cands.get(0);
        if (sel != null && sel.t != null) owner.prepare(sel.t);
    }

    private void ladder(int dir) {
        if (cands.isEmpty()) return;
        candIdx = Math.max(0, Math.min(cands.size() - 1, candIdx + dir));
        sel = cands.get(candIdx);
        if (sel.t != null) owner.prepare(sel.t);
        invalidate();
    }

    private final Runnable longSel = new Runnable() {
        @Override public void run() {
            if (!armed) selectAt(rawX0, rawY0);          // nhấn giữ: chọn mục dưới ngón tay rồi kéo luôn
            if (sel != null) beginDragSel();
            invalidate();
        }
    };

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!editing) return false;
        float slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY(); rawX0 = e.getRawX(); rawY0 = e.getRawY();
                dragging = false;
                armed = sel != null && selRect().contains((int) rawX0, (int) rawY0);   // chạm vào mục đang chọn: kéo là di chuyển
                removeCallbacks(longSel);
                postDelayed(longSel, 380);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    if (Math.hypot(e.getX() - downX, e.getY() - downY) > slop) {
                        removeCallbacks(longSel);
                        if (armed) beginDragSel();
                    }
                    if (!dragging) return true;
                }
                updateDrag(e);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longSel);
                if (dragging) {
                    drop();
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                        && Math.hypot(e.getX() - downX, e.getY() - downY) <= slop) {
                    handleTap(e.getX(), e.getY(), e.getRawX(), e.getRawY());
                }
                dragging = false;
                armed = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void handleTap(float x, float y, float rawX, float rawY) {
        int xi = (int) x, yi = (int) y;
        if (doneRect.contains(xi, yi)) { exitEdit(); return; }
        if (sel != null) {
            if (btnSmall.contains(xi, yi)) { ladder(-1); return; }
            if (btnBig.contains(xi, yi)) { ladder(1); return; }
            if (btnReset.contains(xi, yi)) { if (sel.t != null) owner.reset(sel.t); invalidate(); return; }
            if (selRect().contains((int) rawX, (int) rawY)) return;      // chạm lại vào mục đang chọn: giữ nguyên
        }
        selectAt(rawX, rawY);
    }

    private void beginDragSel() {
        if (sel == null) return;
        if (sel.h != null) {
            offX = (sel.wv.getLeft() + sel.wv.getRight()) / 2f - downX;
            offY = (sel.wv.getTop() + sel.wv.getBottom()) / 2f - downY;
        } else {
            owner.beginDrag(sel.t);
        }
        dragging = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private void updateDrag(MotionEvent e) {
        if (sel.h != null) {
            float W = Math.max(1, getWidth()), H = Math.max(1, getHeight());
            sel.h.x = Math.max(0f, Math.min(100f, (e.getX() + offX) / W * 100f));
            sel.h.y = Math.max(0f, Math.min(100f, (e.getY() + offY) / H * 100f));
            requestLayout();
        } else {
            owner.dragStock(sel.t, e.getRawX() - rawX0, e.getRawY() - rawY0);
        }
        invalidate();
    }

    /** Thả: đẩy các mục khác đang chồng lên ra xa rồi lưu tất cả một lần. */
    private void drop() {
        Rect D = selRect();
        int H = getResources().getDisplayMetrics().heightPixels;
        Set<String> changed = owner.avoidOverlap(D, sel.t, H);
        boolean widgetsMoved = pushWidgets(D, sel.wv, H);
        if (sel.t != null) changed.add(sel.t.key);
        if (sel.h != null || widgetsMoved) saveItems();
        owner.commit(changed);
    }

    private boolean pushWidgets(Rect D, View except, int screenH) {
        boolean any = false;
        int gap = Math.round(8 * density);
        long dArea = (long) D.width() * D.height();
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            Holder h = (Holder) c.getTag();
            if (h == null || h.fullscreen || c == except) continue;
            Rect r = screenRect(c);
            long ar = (long) r.width() * r.height();
            if (ar <= 0) continue;
            Rect inter = new Rect(r);
            if (!inter.intersect(D)) continue;
            long ia = (long) inter.width() * inter.height();
            if (ia >= 0.8 * ar || (dArea > 0 && ia >= 0.8 * dArea)) continue;
            int dy;
            if (r.centerY() >= D.centerY()) {
                dy = D.bottom + gap - r.top;
                if (r.bottom + dy > screenH) dy = -(r.bottom + gap - D.top);
            } else {
                dy = -(r.bottom + gap - D.top);
                if (r.top + dy < 0) dy = D.bottom + gap - r.top;
            }
            if (r.top + dy < 0 || r.bottom + dy > screenH) continue;
            h.y = Math.max(0f, Math.min(100f, h.y + dy / (float) Math.max(1, getHeight()) * 100f));
            any = true;
        }
        if (any) requestLayout();
        return any;
    }

    private void saveItems() {
        try {
            for (Holder h : holders) {
                h.o.put("x", Math.round(h.x * 10f) / 10.0);
                h.o.put("y", Math.round(h.y * 10f) / 10.0);
            }
            String json = src.toString();
            signature = json + "|" + stampPart;                       // đã khớp với trạng thái hiện tại, khỏi dựng lại
            Bundle kv = new Bundle();
            kv.putString(Config.LW_ITEMS, json);
            SysUiHook.setPrefs(ctx, kv);
        } catch (Throwable t) {
            XposedBridge.log(SysUiHook.TAG + "lưu vị trí tiện ích lỗi: " + t);
        }
    }

    private void exitEdit() {
        editing = false;
        sel = null;
        owner.setEditing(false);
        Bundle kv = new Bundle();
        kv.putString(Config.LW_EDIT, "false");
        SysUiHook.setPrefs(ctx, kv);
        invalidate();
    }

    // ------------------------------------------------------------------ vẽ: chỉ khung của mục đang chọn
    private static String typeName(String t) {
        switch (t) {
            case "image": return "Ảnh / GIF";
            case "text": return "Chữ";
            case "clock_d": return "Đồng hồ số";
            case "clock_a": return "Đồng hồ analog";
            case "app": return "Ứng dụng";
            default: return t;
        }
    }

    private void label(Canvas c, String text, float x, float y, int bg) {
        float tw = paint.measureText(text);
        float th = paint.getTextSize();
        float pad = 3 * density;
        float top = Math.max(y, th + pad);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(bg);
        c.drawRect(x, top - th - pad, x + tw + 2 * pad, top + pad / 2, paint);
        paint.setColor(Color.BLACK);
        c.drawText(text, x + pad, top - pad / 2, paint);
    }

    private void drawBtn(Canvas c, Rect r, String text, int bg) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(bg);
        c.drawRoundRect(new RectF(r), 21 * density, 21 * density, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(13 * density);
        float tw = paint.measureText(text);
        c.drawText(text, r.centerX() - tw / 2f, r.centerY() + 5 * density, paint);
    }

    @Override protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (!editing) return;
        int W = getWidth(), H = getHeight();
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(11 * density);
        paint.setPathEffect(null);

        // chỉ vẽ khung quanh mục đang chọn
        if (sel != null) {
            Rect r = selRect();
            r.offset(-loc[0], -loc[1]);
            int col = sel.h != null ? 0xFF00E5FF : 0xFFFFC107;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f * density);
            paint.setColor(col);
            paint.setPathEffect(new DashPathEffect(new float[]{10 * density, 6 * density}, 0));
            c.drawRect(r, paint);
            paint.setPathEffect(null);
            String lab = sel.h != null ? "Tiện ích: " + typeName(sel.h.type)
                    : sel.t.label + (sel.t.memberViews.isEmpty() ? "" : " + " + sel.t.memberViews.size() + " mục đi kèm");
            label(c, lab, r.left, r.top, col);
        }

        // nút Xong
        doneRect.set(W / 2 - Math.round(56 * density), Math.round(70 * density),
                W / 2 + Math.round(56 * density), Math.round(112 * density));
        drawBtn(c, doneRect, "Xong", 0xFF00A86B);

        // nút chọn khung khi đã có mục được chọn
        if (sel != null) {
            int bw = Math.round(104 * density), bh = Math.round(42 * density), gap = Math.round(8 * density);
            int left = (W - (3 * bw + 2 * gap)) / 2, top = H - Math.round(150 * density);
            btnSmall.set(left, top, left + bw, top + bh);
            btnBig.set(left + bw + gap, top, left + 2 * bw + gap, top + bh);
            btnReset.set(left + 2 * (bw + gap), top, left + 3 * bw + 2 * gap, top + bh);
            drawBtn(c, btnSmall, "‹ Nhỏ hơn", 0xFF455A64);
            drawBtn(c, btnBig, "Lớn hơn ›", 0xFF455A64);
            drawBtn(c, btnReset, sel.t != null ? "Hoàn lại" : "—", sel.t != null ? 0xFFB26A00 : 0x66455A64);
        } else {
            btnSmall.setEmpty();
            btnBig.setEmpty();
            btnReset.setEmpty();
        }

        // hướng dẫn
        paint.setTextSize(11 * density);
        String hint = sel == null
                ? "Chạm vào một mục để chọn rồi kéo • Hoặc nhấn giữ một mục để kéo luôn"
                : "Kéo mục đang chọn • Chưa đúng khung? Bấm Nhỏ hơn / Lớn hơn • Chạm chỗ khác để chọn mục khác";
        float hw = paint.measureText(hint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC000000);
        c.drawRect(W / 2f - hw / 2f - 6 * density, doneRect.bottom + 6 * density, W / 2f + hw / 2f + 6 * density,
                doneRect.bottom + 26 * density, paint);
        paint.setColor(Color.WHITE);
        c.drawText(hint, W / 2f - hw / 2f, doneRect.bottom + 21 * density, paint);
    }
}
