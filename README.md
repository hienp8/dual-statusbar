# DualLine SB (One UI 7) – module LSPosed

Thanh trạng thái 2 dòng + hình nền tuỳ chọn + đồng hồ analog (tuỳ chọn ảnh mặt/kim, vị trí mặc định ở giữa)
cho Samsung One UI 7 (Android 15). Không dùng smali/overlay như repo gốc; hook bằng Xposed API ở runtime.

## Build
**Cách 1 – GitHub Actions (không cần cài gì):** đẩy thư mục này lên một repo GitHub → tab *Actions* → *build-apk* → tải artifact `DualLineSB-OneUI7-apk`.
**Cách 2 – Android Studio:** mở thư mục, Sync, `Build > Build APK(s)` (JDK 17).
Nếu repo `https://api.xposed.info/` không tải được: tải `XposedBridgeAPI-82.jar` vào `app/libs/` và đổi dòng `compileOnly` trong `app/build.gradle` (đã có sẵn dòng comment).

## Cài đặt
1. Cài APK, **mở app một lần** (để tạo cấu hình mặc định).
2. LSPosed → Modules → bật module, scope: **System UI** và **System Framework (android)**.
3. Khởi động lại máy (cần cho chiều cao thanh ở system_server). Đổi các tuỳ chọn khác áp dụng ngay, không cần reboot.

## Debug
LSPosed → Logs → lọc `[DualSB]`. Dòng `controller: contents=… clock=… clockBox=… notif=… sys=…` cho biết tìm được view nào.
Nếu có `false`, gửi log đó + `res/values/public.xml` (và `ids.xml`) của SystemUI để chỉnh tên id.

## Quy ước ảnh đồng hồ
Mặt đồng hồ, kim giờ/phút/giây: ảnh **vuông cùng kích thước**, kim hướng lên 12 giờ, **tâm ảnh là trục quay**.
Không chọn ảnh kim → dùng kim vẽ sẵn (màu theo cấu hình).
