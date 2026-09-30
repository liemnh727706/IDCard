import SwiftUI

/// Gộp thông tin đã đọc/kiểm tra ở các tab khác thành một báo cáo PDF/in.
struct CombinedTabView: View {
    @EnvironmentObject var appState: AppState

    private var report: Report? {
        CombinedReport.build(
            emv: appState.emvSummary, ocr: appState.ocr, fullPan: appState.fullPan, ocrImage: appState.ocrImage,
            cccd: appState.cccdReport, cccdPhoto: appState.cccdPhoto,
            faceResult: appState.faceResult, refPhoto: appState.refPhoto, livePhoto: appState.livePhoto)
    }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Xuất báo cáo kết hợp: thẻ ngân hàng/SV + CCCD + FaceID (đã đọc/kiểm tra ở các tab trên).")
                        .font(.headline)

                    if report == nil {
                        Text("Chưa có dữ liệu. Đọc thẻ ngân hàng/SV hoặc CCCD ở tab tương ứng trước.")
                            .foregroundColor(.secondary)
                    } else {
                        ReportButtons(report: report, filePrefix: "KetHop")
                        Divider()
                        Text(report!.toText()).font(.system(size: 11, design: .monospaced)).textSelection(.enabled)
                    }
                }
                .padding()
            }
            .navigationTitle("Kết hợp")
        }
        .navigationViewStyle(.stack)
    }
}
