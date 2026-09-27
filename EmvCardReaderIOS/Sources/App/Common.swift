import PhotosUI
import SwiftUI
import UIKit

/// Trình chọn ảnh từ camera hoặc thư viện.
struct ImagePicker: UIViewControllerRepresentable {
    let sourceType: UIImagePickerController.SourceType
    let onPicked: (UIImage) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let vc = UIImagePickerController()
        vc.sourceType = sourceType
        vc.delegate = context.coordinator
        return vc
    }
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: ImagePicker
        init(_ p: ImagePicker) { parent = p }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let img = info[.originalImage] as? UIImage { parent.onPicked(img) }
            picker.dismiss(animated: true)
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { picker.dismiss(animated: true) }
    }
}

/// Chia sẻ / lưu / in một file PDF qua UIActivityViewController (bao gồm cả tùy chọn In của iOS).
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

/// Nút Xuất PDF / In dùng chung cho cả hai tab, kèm cảnh báo dữ liệu nhạy cảm.
struct ReportButtons: View {
    let report: Report?
    let filePrefix: String
    @State private var showConfirm = false
    @State private var pendingAction: (() -> Void)?
    @State private var shareURL: IdentifiableURL?

    var body: some View {
        HStack {
            Button("Xuất PDF...") { confirm { exportPdf() } }
                .disabled(report == nil)
            Button("In...") { confirm { printPdf() } }
                .disabled(report == nil)
        }
        .alert("Dữ liệu cá nhân nhạy cảm", isPresented: $showConfirm) {
            Button("Hủy", role: .cancel) {}
            Button("Tiếp tục") { pendingAction?() }
        } message: {
            Text("Báo cáo chứa dữ liệu cá nhân của chủ thẻ. Chỉ tiếp tục nếu đây là thẻ của bạn hoặc chủ thẻ đã đồng ý; hãy bảo quản file và bản in cẩn thận.")
        }
        .sheet(item: $shareURL) { u in ShareSheet(items: [u.url]) }
    }

    private func confirm(_ action: @escaping () -> Void) {
        pendingAction = action
        showConfirm = true
    }

    private func writeTempPdf() -> URL? {
        guard let report = report else { return nil }
        let data = PdfBuilder.render(report)
        let ts = DateFormatter()
        ts.dateFormat = "yyyyMMdd_HHmmss"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(filePrefix)_\(ts.string(from: Date())).pdf")
        try? data.write(to: url)
        return url
    }

    private func exportPdf() {
        if let url = writeTempPdf() { shareURL = IdentifiableURL(url: url) }
    }

    private func printPdf() {
        guard let report = report else { return }
        let data = PdfBuilder.render(report)
        let controller = UIPrintInteractionController.shared
        let info = UIPrintInfo(dictionary: nil)
        info.outputType = .general
        info.jobName = report.title
        controller.printInfo = info
        controller.printingItem = data
        controller.present(animated: true)
    }
}

struct IdentifiableURL: Identifiable { let url: URL; var id: String { url.path } }
