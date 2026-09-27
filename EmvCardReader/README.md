# EMV Chip Reader (Test)

App Android thử nghiệm đọc dữ liệu thẻ chip EMV (contactless/NFC) — mục tiêu ban đầu: kiểm tra
xem thẻ sinh viên NLU tích hợp BIDV/Napas có lưu **Họ tên chủ thẻ (tag `5F20`)** trong chip hay
không, để đối chiếu với tên in trên thẻ.

## Chức năng
- Bật NFC reader mode, bắt thẻ ISO-DEP (ISO 14443 A/B).
- Chạy đúng luồng EMV: `SELECT PPSE` → lấy danh sách AID → `SELECT AID` →
  `GET PROCESSING OPTIONS` (tự điền PDOL bằng 0x00) → `READ RECORD` theo AFL.
- Parser BER-TLV tổng quát, hiển thị toàn bộ cây tag kèm tên gợi nhớ.
- Hiện tóm tắt: PAN (che bớt theo PCI-DSS, chỉ hiện 6 số đầu + 4 số cuối), hạn dùng, tên ứng
  dụng, và đặc biệt là tag `5F20`.

## Cách build
```bash
cd EmvCardReader
./gradlew assembleDebug
```
APK debug nằm ở `app/build/outputs/apk/debug/app-debug.apk`.

## Cách test
1. Cài APK lên điện thoại Android có NFC.
2. Bật NFC, mở app.
3. Áp mặt thẻ vào mặt sau điện thoại, giữ yên 1-2 giây.
4. Đọc log kết quả trên màn hình.

## ⚠️ Lưu ý quan trọng
- **Chỉ dùng để tự kiểm tra thẻ của chính mình**, mục đích nghiên cứu/kiểm thử nội bộ.
- App không lưu trữ, không gửi PAN hay dữ liệu thẻ ra ngoài thiết bị dưới bất kỳ hình thức nào.
- Không phải thẻ nào cũng lưu tên chủ thẻ trong chip — nhiều ngân hàng (đặc biệt thẻ nội địa
  Napas) để trống tag `5F20`. Nếu trống, hướng "so khớp tên chip vs tên in" cần giải pháp khác.
- Nếu điện thoại không bắt được tag NFC nào khi áp thẻ, khả năng thẻ là loại **contact-only**
  (không có ăng-ten contactless) — cần đầu đọc chip tiếp xúc (USB PC/SC reader) thay vì NFC.
- PAN/dữ liệu thẻ là "Cardholder Data" theo PCI-DSS và dữ liệu cá nhân theo Nghị định
  13/2023/NĐ-CP — cân nhắc kỹ trước khi mở rộng phạm vi sử dụng ngoài mục đích kiểm thử nội bộ.
