package com.dualsb.oneui;

import android.os.Bundle;

/** Khoá cấu hình + giá trị mặc định, dùng chung cho app và hook. */
public final class Config {
    private Config() {}

    public static final String AUTHORITY = "com.dualsb.oneui.provider";
    public static final String CONFIG_URI = "content://" + AUTHORITY + "/config";
    public static final String IMG_URI = "content://" + AUTHORITY + "/img/";
    /** Ảnh nav_* được lưu nguyên bản (giữ GIF động); các ảnh còn lại được nén lại thành PNG. */
    public static final String[] IMAGES = {"bg", "dial", "hour", "minute", "second", "nav_back", "nav_home", "nav_recent"};
    public static final String SETTING_HEIGHT_PX = "dualsb_height_px";
    public static final String SETTING_NAV_PX = "dualsb_nav_px";

    public static final String ENABLED = "enabled";
    public static final String DUAL = "dual_enabled";
    public static final String HEIGHT = "bar_height_dp";
    public static final String POS_CLOCK = "pos_clock";
    public static final String POS_NOTIF = "pos_notif";
    public static final String POS_ICONS = "pos_icons";
    public static final String CHIP_FWD = "chip_forward_touch";
    public static final String KG_ROW = "kg_row";
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

    // giờ
    public static final String DIG_REPLACE = "digital_replace";
    public static final String TIME_ENABLED = "time_enabled";
    public static final String TIME_FMT = "time_format";
    public static final String TIME_POS = "time_pos";
    public static final String TIME_SIZE = "time_size";
    public static final String TIME_DX = "time_dx";
    public static final String TIME_DY = "time_dy";
    public static final String TIME_COLOR = "time_color";

    // dương lịch (tách riêng)
    public static final String SOLAR_ENABLED = "solar_enabled";
    public static final String SOLAR_FMT = "solar_format";
    public static final String SOLAR_CUSTOM = "solar_custom";
    public static final String SOLAR_POS = "solar_pos";
    public static final String SOLAR_SIZE = "solar_size";
    public static final String SOLAR_DX = "solar_dx";
    public static final String SOLAR_DY = "solar_dy";
    public static final String SOLAR_COLOR = "solar_color";

    // âm lịch (tách riêng)
    public static final String LUNAR_ENABLED = "lunar_enabled";
    public static final String LUNAR_FMT = "lunar_format";
    public static final String LUNAR_POS = "lunar_pos";
    public static final String LUNAR_SIZE = "lunar_size";
    public static final String LUNAR_DX = "lunar_dx";
    public static final String LUNAR_DY = "lunar_dy";
    public static final String LUNAR_COLOR = "lunar_color";

    // pin vòng
    public static final String RING_ENABLED = "ring_enabled";
    public static final String RING_POS = "ring_pos";
    public static final String RING_SIZE = "ring_size";
    public static final String RING_STROKE = "ring_stroke";
    public static final String RING_PERCENT = "ring_percent";
    public static final String RING_BOLT = "ring_bolt";
    public static final String RING_PCT_H = "ring_pct_height";
    public static final String RING_HIDE_STOCK = "ring_hide_stock";
    public static final String RING_HIDE_WIFI = "ring_hide_wifi";
    public static final String RING_HIDE_SIG = "ring_hide_signal";
    public static final String RING_DX = "ring_dx";
    public static final String RING_DY = "ring_dy";
    public static final String RING_COLOR = "ring_color";
    public static final String RING_COL_CHG = "ring_color_charging";
    public static final String RING_COL_SAVER = "ring_color_saver";
    public static final String RING_COL_LOW = "ring_color_low";
    public static final String RING_LOW_LEVEL = "ring_low_level";
    public static final String RING_WIFI = "ring_show_wifi";
    public static final String RING_NETTYPE = "ring_show_nettype";
    public static final String RING_SIGNAL = "ring_show_signal";

    // ẩn / hiện biểu tượng
    public static final String HIDE_NOTIF = "hide_notif_icons";
    public static final String HIDDEN_SLOTS = "hidden_slots";

    // thanh điều hướng
    public static final String NAV_ENABLED = "nav_enabled";
    public static final String NAV_HEIGHT = "nav_height_dp";
    public static final String NAV_ICON = "nav_icon_size";
    public static final String NAV_TINT = "nav_tint";
    public static final String NAV_BACK_KILL = "nav_back_long_kill";
    public static final String KILL_SKIP_SYS = "kill_skip_system";

    // phím cử chỉ cạnh màn hình
    public static final String EDGE_ENABLED = "edge_enabled";
    public static final String EDGE_SIDE = "edge_side";          // 0 trái, 1 phải
    public static final String EDGE_Y = "edge_y";                // tâm dải, % chiều cao màn hình
    public static final String EDGE_H = "edge_h";                // chiều cao dải, %
    public static final String EDGE_W = "edge_w";                // độ rộng dải, dp
    public static final String EDGE_ALPHA = "edge_alpha";        // độ hiện của dải chỉ báo, %
    public static final String EDGE_HAPTIC = "edge_haptic";
    public static final String EDGE_TAP = "edge_tap";
    public static final String EDGE_LONG = "edge_long";
    public static final String EDGE_UP = "edge_up";
    public static final String EDGE_DOWN = "edge_down";
    public static final String EDGE_IN = "edge_in";

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
            case KG_ROW: return 2;
            case BG_ALPHA: return 100;
            case AN_POS: return 4;
            case AN_SIZE: return 28;
            case BAT_H: return 2;
            case BAT_SPEED: return 4;
            case BAT_GAP: return 28;
            case LABEL_POS: return 1;
            case LABEL_SIZE: return 11;
            case TIME_POS: return 0;
            case TIME_SIZE: return 14;
            case SOLAR_POS: return 6;
            case SOLAR_SIZE: return 11;
            case LUNAR_POS: return 8;
            case LUNAR_SIZE: return 10;
            case RING_POS: return 5;
            case RING_SIZE: return 26;
            case RING_PCT_H: return 80;
            case RING_STROKE: return 2;
            case RING_LOW_LEVEL: return 20;
            case NAV_HEIGHT: return 48;
            case NAV_ICON: return 24;
            case EDGE_Y: return 50;
            case EDGE_H: return 30;
            case EDGE_W: return 16;
            case EDGE_ALPHA: return 15;
            case EDGE_UP: return 3;
            case EDGE_DOWN: return 4;
            case EDGE_IN: return 1;
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
            case TIME_ENABLED:
            case SOLAR_ENABLED:
            case LUNAR_ENABLED:
            case HIDE_NOTIF:
            case NAV_ENABLED:
            case NAV_BACK_KILL:
            case EDGE_ENABLED:
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
            case HIDDEN_SLOTS: return "";
            case SOLAR_CUSTOM: return "";
            case TIME_FMT: return "HH:mm";
            case SOLAR_FMT: return "EEE, d MMM";
            case NAV_TINT: return "orig";
            case RING_COL_CHG: return "#FF4CAF50";
            case RING_COL_SAVER: return "#FFFFC107";
            case RING_COL_LOW: return "#FFE53935";
            case TIME_COLOR:
            case SOLAR_COLOR:
            case LUNAR_COLOR:
            case LABEL_COLOR:
            case RING_COLOR:
            case AN_COLOR:
                return "auto";            // theo màu chữ của hệ thống (đổi sáng/tối theo nền)
            default: return "#FFFFFFFF";
        }
    }
}
