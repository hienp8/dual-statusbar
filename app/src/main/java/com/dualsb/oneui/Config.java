package com.dualsb.oneui;

import android.os.Bundle;

/** Khoá cấu hình + giá trị mặc định, dùng chung cho app và hook. */
public final class Config {
    private Config() {}

    public static final String AUTHORITY = "com.dualsb.oneui.provider";
    public static final String CONFIG_URI = "content://" + AUTHORITY + "/config";
    public static final String IMG_URI = "content://" + AUTHORITY + "/img/";
    public static final String[] IMAGES = {"bg", "dial", "hour", "minute", "second"};
    public static final String SETTING_HEIGHT_PX = "dualsb_height_px";

    public static final String ENABLED = "enabled";
    public static final String DUAL = "dual_enabled";
    public static final String HEIGHT = "bar_height_dp";
    public static final String POS_CLOCK = "pos_clock";
    public static final String POS_NOTIF = "pos_notif";
    public static final String POS_ICONS = "pos_icons";
    public static final String BG_ENABLED = "bg_enabled";
    public static final String BG_SCALE = "bg_scale";
    public static final String BG_ALPHA = "bg_alpha";
    public static final String AN_ENABLED = "analog_enabled";
    public static final String AN_POS = "analog_pos";
    public static final String AN_SIZE = "analog_size";
    public static final String AN_DX = "analog_dx";
    public static final String AN_DY = "analog_dy";
    public static final String AN_SEC = "analog_seconds";
    public static final String AN_HIDE = "analog_hide_digital";
    public static final String AN_COLOR = "analog_color";
    public static final String BAT_LINE = "battery_line";
    public static final String BAT_H = "battery_line_h";
    public static final String BAT_COL = "battery_color_normal";
    public static final String BAT_COL_CHG = "battery_color_charging";
    public static final String LABEL_MODE = "label_mode";
    public static final String LABEL_TEXT = "label_text";
    public static final String LABEL_POS = "label_pos";
    public static final String LABEL_SIZE = "label_size";
    public static final String LABEL_DX = "label_dx";
    public static final String LABEL_DY = "label_dy";
    public static final String LABEL_COLOR = "label_color";

    public static int getInt(Bundle b, String key) {
        Object o = b == null ? null : b.get(key);
        if (o instanceof Integer) return (Integer) o;
        if (o instanceof String) {
            try { return Integer.parseInt((String) o); } catch (NumberFormatException ignored) {}
        }
        switch (key) {
            case HEIGHT: return 44;
            case POS_CLOCK: return 0;
            case POS_NOTIF: return 6;
            case POS_ICONS: return 5;
            case BG_SCALE: return 0;
            case BG_ALPHA: return 100;
            case AN_POS: return 4;
            case AN_SIZE: return 28;
            case BAT_H: return 2;
            case LABEL_POS: return 1;
            case LABEL_SIZE: return 11;
            default: return 0;
        }
    }

    public static boolean getBool(Bundle b, String key) {
        Object o = b == null ? null : b.get(key);
        if (o instanceof Boolean) return (Boolean) o;
        switch (key) {
            case AN_HIDE: return false;
            default: return true;
        }
    }

    public static String getString(Bundle b, String key) {
        Object o = b == null ? null : b.get(key);
        if (o instanceof String) return (String) o;
        switch (key) {
            case BAT_COL: return "#FFE53935";
            case BAT_COL_CHG: return "#FF4CAF50";
            case LABEL_TEXT: return "";
            default: return "#FFFFFFFF";
        }
    }
}
