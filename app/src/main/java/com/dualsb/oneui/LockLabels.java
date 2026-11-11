package com.dualsb.oneui;

import java.util.HashMap;
import java.util.Map;

/** Tên tiếng Việt cho các mục của màn hình khóa (dùng chung cho app và SystemUI). */
final class LockLabels {
    private LockLabels() {}

    private static final Map<String, String> M = new HashMap<>();
    static {
        M.put("left_shortcut_area", "Phím tắt bên trái");
        M.put("right_shortcut_area", "Phím tắt bên phải");
        M.put("start_button_effectview", "Hiệu ứng nút bên trái (Samsung)");
        M.put("end_button_effectview", "Hiệu ứng nút bên phải (Samsung)");
        M.put("keyguard_edit_mode_container", "Khung chỉnh sửa của Samsung");
        M.put("keyguard_edit_mode_blur_effect", "Lớp mờ chế độ chỉnh sửa (Samsung)");
        M.put("keyguard_edit_mode_wallpaper", "Hình nền chế độ chỉnh sửa (Samsung)");
        M.put("keyguard_edit_round_layout", "Khung bo tròn chế độ chỉnh sửa (Samsung)");
        M.put("lockscreen_clock_view", "Đồng hồ (nhỏ)");
        M.put("lockscreen_clock_view_large", "Đồng hồ (lớn)");
        M.put("keyguard_clock_container", "Khung đồng hồ");
        M.put("keyguard_slice_view", "Ngày / thông tin dưới đồng hồ");
        M.put("keyguard_status_area", "Khu vực trạng thái dưới đồng hồ");
        M.put("lock_icon_view", "Ổ khóa");
        M.put("lock_icon", "Ổ khóa");
        M.put("keyguard_indication_area", "Dòng gợi ý (vân tay, sạc...)");
        M.put("keyguard_indication_text", "Chữ gợi ý");
        M.put("keyguard_bottom_area", "Khu vực phím tắt phía dưới");
        M.put("notification_stack_scroller", "Danh sách thông báo");
        M.put("notification_container_parent", "Khung thông báo");
        M.put("notification_panel", "Bảng thông báo");
        M.put("media_container", "Trình phát nhạc");
    }

    /** Tên hiển thị; mục chưa biết thì hiện tên kỹ thuật. */
    static String label(String key) {
        if (key.startsWith("p:")) return "Mục không có tên [" + key.substring(2) + "]";
        String l = M.get(key);
        return l != null ? l + " (" + key + ")" : key;
    }

    /** Nhãn ngắn vẽ trên màn hình khi đang chỉnh. */
    static String shortLabel(String key) {
        String l = M.get(key);
        return l != null ? l : key;
    }
}
