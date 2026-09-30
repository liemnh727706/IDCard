import Foundation

/// Trạng thái dùng chung giữa các tab (thẻ ngân hàng, CCCD, FaceID) để dựng báo cáo kết hợp.
final class AppState: ObservableObject {
    // Thẻ ngân hàng / thẻ sinh viên
    @Published var emvSummary: EmvSummary?
    @Published var ocr: OcrCardInfo?
    @Published var ocrImage: Data?
    @Published var fullPan = false

    // CCCD
    @Published var cccdReport: Report?
    @Published var cccdFields: [String: String] = [:]
    @Published var cccdPhoto: Data?

    // FaceID
    @Published var faceResult: FaceVerifyResult?
    @Published var refPhoto: Data?
    @Published var livePhoto: Data?
}
