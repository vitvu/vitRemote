# vitRemote

Ứng dụng Android (Kotlin) dùng **Dịch vụ Trợ năng** để điều khiển điện thoại từ trình duyệt trong mạng LAN.

## Tính năng
- Phím điều hướng: **Back / Home / Đa nhiệm**
- Hệ thống: thông báo, cài đặt nhanh, đóng thanh thông báo, khoá màn hình, menu nguồn, chia đôi màn hình, chụp màn hình lưu vào máy, đánh thức màn hình, đèn pin, ngăn ứng dụng
- Âm thanh: tăng/giảm/tắt tiếng nhạc, âm lượng chuông, phát/tạm dừng/bài trước/bài sau
- **Xem màn hình trực tiếp** + chạm / nhấn giữ / vuốt ngay trên ảnh (Android 11+)
- **Tải ảnh chụp màn hình** chất lượng cao về máy tính (Android 11+)
- Nhập chữ vào ô đang chọn, Enter, xoá ô
- Thông tin thiết bị: model, Android, bản vá, CPU, pin (%, nhiệt độ, điện áp, sạc), RAM, bộ nhớ, màn hình, âm lượng, ứng dụng đang mở, IP, thời gian bật máy
- Phím tắt trên web: `Esc` Back · `H` Home · `R` Đa nhiệm · `N` Thông báo · `+`/`-` Âm lượng · `M` Tắt tiếng · `Space` Phát/Dừng
- Bảo vệ bằng **mã PIN 6 số**, đổi cổng tuỳ ý

## Nhẹ & nhanh
- Máy chủ HTTP + WebSocket tự viết (không thư viện), I/O chặn → **~0% CPU khi rảnh**
- Lệnh là chuỗi vài byte (`a:home`, `t:5000,5000`…), `TCP_NODELAY` → phản hồi tức thì (~4 ms trong LAN)
- Trang web nén gzip (~5 KB), chỉ chụp màn hình khi có người xem; **khung hình giống trước không gửi lại**
- APK ~1.4 MB

## Cách dùng
1. Cài APK, mở app → **Mở cài đặt Trợ năng** → bật **vitRemote**.
2. Trên máy tính cùng Wi-Fi mở địa chỉ hiển thị trong app (vd. `http://192.168.1.17:8080`), nhập PIN.
3. Qua USB: `adb forward tcp:8080 tcp:8080` rồi mở `http://localhost:8080`.

## Build
```
./gradlew assembleRelease
```
APK: `app/build/outputs/apk/release/app-release.apk`

## Giao thức WebSocket (`/ws?k=PIN`)
| Lệnh | Ý nghĩa |
|---|---|
| `a:back\|home\|recents\|notif\|qs\|dismiss\|power\|lock\|shot\|split\|apps` | Hành động hệ thống |
| `t:x,y` / `l:x,y` | Chạm / nhấn giữ (toạ độ 0..10000) |
| `s:x1,y1,x2,y2,ms` | Vuốt |
| `v:up\|down\|mute\|rup\|rdown` · `m:play\|next\|prev\|stop` | Âm lượng · media |
| `x:text` · `X` · `e` | Nhập chữ · xoá ô · Enter |
| `f` · `w` · `i` · `p` | Đèn pin · đánh thức · thông tin · ping |
| `c` · `L:width` | Ảnh chụp đầy đủ · xem trực tiếp (0 = tắt) |

Ảnh gửi dạng nhị phân: byte đầu `1` = khung trực tiếp, `2` = ảnh chụp; phần còn lại là JPEG.

> ⚠️ Chỉ dùng trong mạng tin cậy: kết nối là HTTP/WS không mã hoá, chỉ có PIN bảo vệ.
