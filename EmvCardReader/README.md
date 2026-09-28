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
- Đọc CCCD gắn chip (eMRTD, BAC), OCR MRZ tự điền khóa, xuất báo cáo PDF, so khớp OCR mặt thẻ
  ngân hàng.
- **Kiểm tra chính danh bằng khuôn mặt**: chụp ảnh chân dung trên thẻ (hoặc lấy tự động từ DG2
  của CCCD) + ảnh chụp trực tiếp người cầm thẻ, gửi tới FaceID service tự host của bạn
  (`E:\AI\claude\FaceID`, InsightFace) để so khớp 1:1. Xem mục riêng bên dưới.

## Kiểm tra chính danh bằng khuôn mặt

Tính năng này gọi `POST /face/verify` của FaceID service ở `E:\AI\claude\FaceID` (service riêng
của bạn, tự host, không phải bên thứ ba). App KHÔNG bundle mô hình nhận diện khuôn mặt nào — tận
dụng InsightFace đã dựng sẵn để có độ chính xác cao hơn nhiều so với tự viết.

**Chuẩn bị trước khi test:**
1. Service mặc định chỉ bind `127.0.0.1` (không tới được từ điện thoại). Sửa
   `E:\AI\claude\FaceID\.env`: đổi `FACEID_HOST=127.0.0.1` thành `FACEID_HOST=0.0.0.0`, rồi chạy
   lại `run.bat`.
2. Lấy IP LAN của máy chạy service (`ipconfig`, dạng `192.168.x.x`).
3. Trong app, mục "Kiểm tra chính danh", nhập `http://192.168.x.x:8001` và API key (giá trị
   `FACEID_API_KEY` trong `.env`).
4. Điện thoại và máy chạy service phải **cùng một mạng Wi-Fi tin cậy** — traffic là HTTP thường
   (không TLS), nên tuyệt đối không dùng trên Wi-Fi công cộng/không tin cậy.

**Luồng dùng:**
- Ảnh chân dung trên thẻ: tự điền nếu vừa đọc CCCD có DG2, hoặc bấm "Chụp ảnh thẻ SV" để chụp ảnh
  in trên thẻ sinh viên (không có chip lưu ảnh).
- Bấm "Chụp ảnh live" để chụp trực tiếp người đang cầm thẻ.
- Bấm "Kiểm tra chính danh" → app hiện % độ giống và kết luận KHỚP / KHÔNG CHẮC CHẮN / KHÔNG KHỚP
  theo ngưỡng cấu hình trong FaceID service (mặc định 0.32 / 0.20).

**Giới hạn:** đây không phải kiểm tra chống giả mạo (liveness) — ảnh "live" chỉ đảm bảo là ảnh
chụp mới từ camera, không ngăn được việc chụp lại một tấm ảnh in sẵn. Chỉ dùng làm bước hỗ trợ,
không thay thế xác minh bằng mắt của người có thẩm quyền.

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
- App không lưu trữ, không tự gửi PAN hay dữ liệu thẻ ra ngoài thiết bị. **Ngoại lệ duy nhất**:
  tính năng kiểm tra chính danh bằng khuôn mặt (mục riêng bên dưới) chủ động gửi ảnh khuôn mặt tới
  địa chỉ FaceID service do chính bạn nhập và tự host — không gửi đi đâu khác, và chỉ gửi khi bạn
  bấm nút và xác nhận cảnh báo dữ liệu nhạy cảm.
- Không phải thẻ nào cũng lưu tên chủ thẻ trong chip — nhiều ngân hàng (đặc biệt thẻ nội địa
  Napas) để trống tag `5F20`. Nếu trống, hướng "so khớp tên chip vs tên in" cần giải pháp khác.
- Nếu điện thoại không bắt được tag NFC nào khi áp thẻ, khả năng thẻ là loại **contact-only**
  (không có ăng-ten contactless) — cần đầu đọc chip tiếp xúc (USB PC/SC reader) thay vì NFC.
- PAN/dữ liệu thẻ là "Cardholder Data" theo PCI-DSS và dữ liệu cá nhân theo Nghị định
  13/2023/NĐ-CP — cân nhắc kỹ trước khi mở rộng phạm vi sử dụng ngoài mục đích kiểm thử nội bộ.
