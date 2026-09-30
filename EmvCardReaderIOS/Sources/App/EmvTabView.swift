import SwiftUI

struct EmvTabView: View {
    @EnvironmentObject var appState: AppState
    @State private var status = "Chạm 'Đọc thẻ chip' rồi áp thẻ vào đỉnh trên của iPhone."
    @State private var summary: EmvSummary?
    @State private var ocr: OcrCardInfo?
    @State private var rawLog = ""
    @State private var showFull = false
    @State private var showImagePicker = false
    @State private var nfcSession: NfcSession?

    private var report: Report? { summary.map { EmvReport.build($0, ocr: ocr, fullPan: showFull) } }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text(status).font(.headline)

                    HStack {
                        Button("Đọc thẻ chip", action: readChip)
                        Button("Chụp mặt thẻ (OCR)") { showImagePicker = true }
                    }
                    Toggle("Hiện đầy đủ PAN/Track2", isOn: $showFull)
                    ReportButtons(report: report, filePrefix: "TheNganHang")

                    if let s = summary {
                        summaryView(s)
                    }
                    if let ocr = ocr {
                        Text("OCR: \(ocr.pans.count) dãy số thẻ, \(ocr.expiries.count) ngày MM/YY").font(.footnote)
                    }
                    if !rawLog.isEmpty {
                        Text(showFull ? rawLog : EmvReport.maskInText(rawLog, summary?.pan ?? ""))
                            .font(.system(size: 11, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }
                .padding()
            }
            .navigationTitle("Thẻ ngân hàng (EMV)")
        }
        .navigationViewStyle(.stack)
        .sheet(isPresented: $showImagePicker) {
            ImagePicker(sourceType: .camera) { image in
                status = "Đang OCR..."
                appState.ocrImage = image.jpegData(compressionQuality: 0.85)
                TextRecognizer.recognize(image) { result in
                    DispatchQueue.main.async {
                        switch result {
                        case .success(let text): ocr = OcrParser.parse(text); status = "Đã OCR xong."
                        case .failure(let e): status = "OCR lỗi: \(e.localizedDescription)"
                        }
                    }
                }
            }
        }
        .onChange(of: summary?.pan) { _ in appState.emvSummary = summary }
        .onChange(of: ocr?.rawText) { _ in appState.ocr = ocr }
        .onChange(of: showFull) { appState.fullPan = $0 }
    }

    @ViewBuilder private func summaryView(_ s: EmvSummary) -> some View {
        let pan = showFull ? s.pan : EmvReport.maskPan(s.pan)
        VStack(alignment: .leading, spacing: 3) {
            row("Ứng dụng", "\(s.label) (AID \(s.aid))")
            row("PAN", pan)
            row("Hạn dùng", s.expiryYymm.map { String($0.suffix(2)) + "/" + String($0.prefix(2)) } ?? "(không có)")
            row("Nhà phát hành", Banks.describe(s.pan))
            row("Họ tên (chip)", s.name.isEmpty ? "(chip không lưu tên)" : s.name)
            if let ocr = ocr {
                let v = CardMatcher.compare(ocr, ChipCardInfo(pan: s.pan, expiryYymm: s.expiryYymm, name: s.name))
                Divider()
                ForEach(v.lines, id: \.self) { Text("- " + $0).font(.footnote) }
                Text(v.overall).font(.subheadline).bold()
            }
        }
    }

    private func row(_ k: String, _ v: String) -> some View {
        HStack(alignment: .top) {
            Text(k).font(.caption).foregroundColor(.secondary).frame(width: 110, alignment: .leading)
            Text(v).font(.caption)
        }
    }

    private func readChip() {
        status = "Đang chờ NFC... Áp thẻ vào đỉnh trên của iPhone."
        let session = NfcSession(alertMessage: "Áp thẻ ngân hàng vào đỉnh trên của iPhone")
        nfcSession = session
        session.start(onTag: { tx, finish in
            let result = EmvReader.readAll(tx: tx)
            var sb = ""
            sb += "So AID tim thay: \(result.discoveredAids.count)\n"
            for aid in result.discoveredAids { sb += "  - \(aid)\n" }
            for (aid, nodes) in result.nodesByAid {
                sb += "\n---- AID \(aid) ----\n"
                TlvParser.render(nodes, into: &sb)
            }
            sb += "\n[Log APDU]\n"
            for step in result.steps {
                sb += "> \(step.title)\n  CMD: \(step.command.hexString)\n  RSP: \(step.response.hexString)\n"
            }
            DispatchQueue.main.async {
                summary = EmvReport.summarize(nodesByAid: result.nodesByAid, aids: result.discoveredAids, log: sb)
                rawLog = sb
                status = summary != nil ? "Đã đọc xong chip." : "Đọc được thẻ nhưng không thấy PAN."
            }
            finish("Đã đọc xong")
        }, onError: { msg in
            DispatchQueue.main.async { status = "Lỗi khi đọc thẻ: \(msg)" }
        })
    }
}
