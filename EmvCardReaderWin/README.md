# EMV Chip Reader (Windows)

Đọc thẻ chip EMV qua đầu đọc USB (PC/SC), OCR ảnh mặt thẻ và so khớp số thẻ, hạn dùng,
ngân hàng, họ tên với dữ liệu trong chip. Cùng logic với bản Android trong `../EmvCardReader`.

## Chạy
```
pip install -r requirements.txt
python app.py
```
Hoặc `build.bat` để tạo `dist\EmvCardReaderWin.exe` (một file, không cần cài Python).

## Tab Căn cước công dân
Đọc CCCD gắn chip qua đầu đọc USB bằng BAC (ICAO 9303). Cần 3 thông tin in ở dòng MRZ mặt sau
thẻ: số giấy tờ, ngày sinh, ngày hết hạn (hoặc dán 2 dòng MRZ đầu rồi bấm "Điền tự động").
Đọc được DG1 (thông tin MRZ), tùy chọn DG11/12/13 và ảnh chân dung DG2. Không đọc dữ liệu bảo vệ
bằng EAC (vân tay). Chưa xác thực chữ ký số SOD. Dữ liệu chỉ hiển thị, không lưu hay gửi đi.
Chỉ đọc thẻ của chính bạn hoặc khi chủ thẻ đồng ý (Nghị định 13/2023/NĐ-CP).

## Xuất báo cáo và in
Mỗi tab có nút **Xuất PDF/TXT/JSON...** và **In...**. Báo cáo gồm thông tin chính, thông tin kèm theo
(DG11/12/13 với CCCD; đối chiếu OCR với thẻ ngân hàng) và thông tin kỹ thuật (ATR, log). PDF dùng font
Arial/Courier New của Windows để hiển thị tiếng Việt. Số thẻ ngân hàng được che bớt trừ khi tick
"Hiện đầy đủ PAN/Track2". In dùng lệnh in của trình đọc PDF đang cài (Foxit, Adobe...); nếu không có
sẽ mở file PDF để in thủ công. File PDF tạm khi in được xóa sau vài phút. Báo cáo chứa dữ liệu cá nhân
nhạy cảm nên hãy bảo quản cẩn thận.

## So khớp họ tên có dấu (OCR) với tên trên chip (không dấu)
Chip (thẻ ngân hàng/SV lẫn CCCD) chỉ lưu tên không dấu (ASCII, chuẩn Track1/MRZ). Khi chụp OCR
mặt thẻ sinh viên, app tìm dòng có nhãn "Họ và tên"/"Full name" để lấy tên **có dấu** nguyên bản,
rồi so khớp với tên chip sau khi bỏ dấu cả hai bên. Tên có dấu này được dùng làm tên hiển thị
chính trong báo cáo PDF/in, thay vì chỉ hiện bản không dấu từ chip.

## Tab Xác thực khuôn mặt (FaceID)
Gọi `POST /face/verify` của FaceID service tự host (InsightFace) tại `cropnlu.duckdns.org/faceid`
(cùng service dùng cho bản Android) — không phải bên thứ ba. Cấu hình URL/API key lưu vào
`faceid_local.json` cạnh app (file này **không lên Git**, xem `.gitignore`).

- Ảnh chân dung tham chiếu: lấy tự động từ DG2 của CCCD đã đọc, hoặc chọn file, hoặc chụp webcam.
- Ảnh live: chụp webcam hoặc chọn file (máy không có webcam thì dùng ảnh có sẵn).
- Ảnh JPEG2000 (định dạng phổ biến của DG2 trong CCCD) được Pillow giải mã và chuyển sang JPEG
  chuẩn trước khi gửi, vì OpenCV trên server chưa chắc đọc trực tiếp JPEG2000.
- Đây **không phải kiểm tra chống giả mạo (liveness)** — chỉ đảm bảo ảnh vừa chụp, không chống
  được việc chụp lại ảnh in. Ảnh khuôn mặt là dữ liệu sinh trắc học nhạy cảm, app luôn hỏi xác
  nhận trước khi gửi và không lưu ảnh.

## Xuất báo cáo kết hợp
Nút "Xuất PDF/TXT/JSON..." ở thanh dưới cùng cửa sổ gộp thông tin đã đọc/xác thực ở cả ba tab:
họ tên đối chiếu nhiều nguồn (OCR có dấu, chip thẻ ngân hàng/SV, chip CCCD - có báo KHỚP/KHÔNG
KHỚP giữa các nguồn), thông tin thẻ ngân hàng/SV, thông tin CCCD, và kết quả FaceID nếu đã kiểm
tra. Cần đọc/OCR ở các tab tương ứng trước, nút này chỉ gộp lại chứ không tự đọc thẻ.

## Ghi chú
- Cần dịch vụ Windows "Smart Card" đang chạy và một đầu đọc thẻ tương thích PC/SC.
- Chế độ contact thường trả cả họ tên chủ thẻ (tag 5F20 hoặc Track1), khác với NFC contactless.
- OCR dùng Windows.Media.Ocr trên máy, ảnh không gửi đi đâu. Cần gói ngôn ngữ OCR của Windows.
- PAN và Track2 được che mặc định ở cả phần tóm tắt lẫn log; chỉ hiện đủ khi tick ô "Hiện đầy đủ".
- Chỉ dùng với thẻ của chính bạn hoặc khi chủ thẻ đồng ý. Không lưu hay gửi dữ liệu thẻ ra ngoài.
