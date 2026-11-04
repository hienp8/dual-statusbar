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
    public static final String POS_CHIP = "pos_chip";            // -2 = theo thông báo, -1 = không di chuyển
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
    public static final String BAT_ANIM = "battery_anim";
    public static final String BAT_SPEED = "battery_anim_speed";
    public static final String BAT_GAP = "battery_anim_gap";
    public static final String LABEL_MODE = "label_mode";
    public static final String LABEL_TEXT = "label_text";
    public static final String LABEL_POS = "label_pos";
    public static final String LABEL_SIZE = "label_size";
    public static final String LABEL_DX = "label_dx";
    public static final String LABEL_DY = "label_dy";
    public static final String LABEL_COLOR = "label_color";

    // đồng hồ số tách giờ / ngày
    public static final String DIG_REPLACE = "digital_replace";
    public static final String TIME_ENABLED = "time_enabled";
    public static final String TIME_FMT = "time_format";
    public static final String TIME_POS = "time_pos";
    public static final String TIME_SIZE = "time_size";
    public static final String TIME_DX = "time_dx";
    public static final String TIME_DY = "time_dy";
    public static final String TIME_COLOR = "time_color";
    public static final String DATE_ENABLED = "date_enabled";
    public static final String DATE_MODE = "date_mode";          // 0 dương, 1 âm, 2 cả hai
    public static final String DATE_FMT = "date_format";
    public static final String DATE_CUSTOM = "date_custom";
    public static final String LUNAR_FMT = "lunar_format";
    public static final String LUNAR_NEWLINE = "lunar_newline";
    public static final String DATE_POS = "date_pos";
    public static final String DATE_SIZE = "date_size";
    public static final String DATE_DX = "date_dx";
    public static final String DATE_DY = "date_dy";
    public static final String DATE_COLOR = "date_color";

    // pin dạng vòng
    public static final String RING_ENABLED = "ring_enabled";
    public static final String RING_POS = "ring_pos";
    public static final String RING_SIZE = "ring_size";
    public static final String RING_STROKE = "ring_stroke";
    public static final String RING_PERCENT = "ring_percent";
    public static final String RING_HIDE_STOCK = "ring_hide_stock";
    public static final String RING_DX = "ring_dx";
    public static final String RING_DY = "ring_dy";
    public static final String RING_COLOR = "ring_color";
    public static final String RING_COL_CHG = "ring_color_charging";
    public static final String RING_COL_SAVER = "ring_color_saver";
    public static final String RING_COL_LOW = "ring_color_low";
    public static final String RING_LOW_LEVEL = "ring_low_level";
    public static final String RING_WIFI = "ring_show_wifi";
    public static final String RING_SIGNAL = "ring_show_signal";
    public static final String RING_SIG_STYLE = "ring_signal_style";   // 0 thanh, 1 chấm

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
            case POS_CHIP: return -2;
            case BAT_SPEED: return 4;
            case BAT_GAP: return 28;
            case BG_ALPHA: return 100;
            case AN_POS: return 4;
            case AN_SIZE: return 28;
            case BAT_H: return 2;
            case LABEL_POS: return 1;
            case LABEL_SIZE: return 11;
            case TIME_POS: return 0;
            case TIME_SIZE: return 14;
            case DATE_POS: return 6;
            case DATE_SIZE: return 11;
            case RING_POS: return 5;
            case RING_SIZE: return 22;
            case RING_STROKE: return 2;
            case RING_LOW_LEVEL: return 20;
            default: return 0;
        }
    }

    public static boolean getBool(Bundle b, String key) {
        Object o = b == null ? null : b.get(key);
        if (o instanceof Boolean) return (Boolean) o;
        switch (key) {
            case AN_HIDE:
            case DIG_REPLACE:
            case RING_ENABLED:
            case LUNAR_NEWLINE:
            case TIME_ENABLED:
            case DATE_ENABLED:
                return false;
            default:
                return true;
        }
    }

    public static String getString(Bundle b, String key) {
        Object o = b == null ? null : b.get(key);
        if (o instanceof String) return (String) o;
        switch (key) {
            case BAT_COL: return "#FFE53935";
            case BAT_COL_CHG: return "#FF4CAF50";
            case LABEL_TEXT: return "";
            case TIME_FMT: return "HH:mm";
            case DATE_FMT: return "EEE, d MMM";
            case DATE_CUSTOM: return "";
            case RING_COL_CHG: return "#FF4CAF50";
            case RING_COL_SAVER: return "#FFFFC107";
            case RING_COL_LOW: return "#FFE53935";
            case TIME_COLOR:
            case DATE_COLOR:
            case LABEL_COLOR:
            case RING_COLOR:
            case AN_COLOR:
                return "auto";            // theo màu chữ của hệ thống (đổi sáng/tối theo nền)
            default: return "#FFFFFFFF";
        }
    }
}
