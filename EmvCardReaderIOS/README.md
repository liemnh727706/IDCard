# EMV Chip Reader (iOS)

Bản iOS của app đọc thẻ chip EMV (thẻ ngân hàng) và CCCD gắn chip (eMRTD, ICAO 9303) qua NFC,
dùng SwiftUI + CoreNFC. Cùng logic (BAC, TLV, MRZ, đối chiếu OCR, báo cáo PDF) với bản Android
(`../EmvCardReader`) và Windows (`../EmvCardReaderWin`), viết lại bằng Swift.

## Vì sao không có sẵn file .ipa/.xcodeproj build sẵn

Việc biên dịch app iOS bắt buộc cần Xcode chạy trên macOS — không thể build hay chạy trực tiếp
trên máy Windows. Project này được thiết kế để:

1. **Tự build & test bằng CI** (không cần Mac cá nhân): xem
   [.github/workflows/build-ios.yml](../.github/workflows/build-ios.yml) — chạy trên runner
   macOS của GitHub Actions, dùng [XcodeGen](https://github.com/yonaskolb/XcodeGen) để sinh
   `.xcodeproj` từ [project.yml](project.yml), biên dịch cho iOS Simulator, và **chạy thật bộ
   unit test** (BAC theo vector chuẩn ICAO 9303, MRZ, dựng báo cáo) — không chỉ compile-check.
2. **Mở trên Mac khi bạn có sẵn**: cài Xcode + XcodeGen (`brew install xcodegen`), chạy
   `xcodegen generate` trong thư mục này, mở `EmvCardReader.xcodeproj`, chọn team ký ứng dụng của
   bạn (Signing & Capabilities), rồi build lên iPhone thật để đọc NFC (Simulator không có NFC).

## Kiến trúc

- `Sources/Core/` — thuần Swift, không phụ thuộc UIKit/CoreNFC, dùng chung logic:
  `Bac.swift` (BAC + Secure Messaging, dùng CommonCrypto cho 3DES/MAC), `Tlv.swift` (BER-TLV),
  `EmvReader.swift`, `CccdReader.swift`, `Mrz.swift`, `Banks.swift`, `CardMatch.swift`,
  `Report.swift` (dựng nội dung báo cáo, giống `Report.kt`/`report.py`).
- `Sources/NFC/NfcSession.swift` — bọc `NFCTagReaderSession`/`NFCISO7816Tag` của CoreNFC thành
  một hàm gửi APDU đồng bộ (`Transceiver`), để `Core/` không cần biết gì về CoreNFC.
- `Sources/OCR/TextRecognizer.swift` — OCR bằng Vision framework, chạy trên máy, không gửi ảnh
  đi đâu.
- `Sources/App/` — giao diện SwiftUI 4 tab (`EmvTabView`, `CccdTabView`, `FaceIdTabView`,
  `CombinedTabView`), chia sẻ dữ liệu qua `AppState` (ObservableObject), xuất PDF
  (`PdfBuilder.swift`, dùng `UIGraphicsPDFRenderer`) + in qua `UIPrintInteractionController`.
- `Sources/Core/FaceIdClient.swift` — gọi FaceID service tự host (InsightFace) tại
  `cropnlu.duckdns.org/faceid` (cùng service dùng cho bản Android/Windows) để so khớp 1:1 ảnh
  chân dung với ảnh chụp live. Không gửi dữ liệu tới bên thứ ba nào khác.
- `Sources/Core/CombinedReport.swift` — gộp thông tin thẻ ngân hàng/SV + CCCD + FaceID (kèm ảnh
  mặt thẻ OCR, ảnh CCCD, ảnh tham chiếu/live FaceID) thành một báo cáo, cùng logic với
  `CombinedReport.kt`/`combined_report.py`.
- `Tests/` — XCTest, gồm vector mẫu ICAO 9303 Part 11 Appendix D cho BAC (cùng vector đã dùng để
  kiểm chứng bản Android/Windows), test MRZ, test dựng báo cáo, test so khớp tên có dấu/không dấu
  và test dựng báo cáo kết hợp.

## So khớp họ tên có dấu (OCR) với tên trên chip (không dấu)

Chip chỉ lưu tên không dấu (ASCII, chuẩn Track1/MRZ). Khi chụp OCR mặt thẻ sinh viên, app tìm
dòng có nhãn "Họ và tên"/"Full name" (hàm `extractOcrName` trong `CardMatch.swift`) để lấy tên
**có dấu** nguyên bản, so khớp với tên chip sau khi bỏ dấu cả hai (`namesMatch`). Áp dụng cho cả
màn hình đối chiếu trực tiếp lẫn báo cáo PDF/kết hợp.

## Tab FaceID và tab Kết hợp

- **FaceID**: chụp/chọn ảnh chân dung tham chiếu (hoặc lấy tự động từ DG2 của CCCD) và ảnh chụp
  live, gọi `POST /face/verify`. Đây không phải kiểm tra chống giả mạo (liveness).
- **Kết hợp**: xuất PDF/in gộp toàn bộ thông tin đã đọc/kiểm tra ở 3 tab kia, không tự đọc thẻ.

## Cấu hình NFC cần thiết (đã có sẵn trong `project.yml`)

- `NFCReaderUsageDescription` trong Info.plist.
- `com.apple.developer.nfc.readersession.iso7816.select-identifiers`: danh sách AID (Visa,
  Mastercard, JCB, Napas, eMRTD...) mà app được phép chọn qua NFC.
- Capability "Near Field Communication Tag Reading" (entitlement
  `com.apple.developer.nfc.readersession.formats: [TAG]`).

Đọc CCCD/thẻ ngân hàng qua NFC **chỉ chạy được trên iPhone thật** (7 trở lên, chip NFC hỗ trợ
đọc thẻ), không chạy được trên Simulator. Cần tài khoản Apple Developer (kể cả tài khoản miễn
phí) để ký ứng dụng chạy thử trên máy thật; để phát hành lên App Store cần tài khoản trả phí.

## Giới hạn đã biết

- Chưa xác thực chữ ký số SOD của CCCD (giống các bản Android/Windows).
- Không đọc dữ liệu bảo vệ bằng EAC (vân tay).
- Chưa chạy thử trên iPhone thật — CI chỉ biên dịch cho Simulator và chạy unit test phần logic
  thuần Swift; phần CoreNFC/Vision/PDF cần bạn tự kiểm tra trên thiết bị thật.
