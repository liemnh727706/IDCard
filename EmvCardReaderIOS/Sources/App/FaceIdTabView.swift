import SwiftUI

struct FaceIdTabView: View {
    @EnvironmentObject var appState: AppState

    @State private var url = "https://cropnlu.duckdns.org/faceid"
    @State private var apiKey = ""
    @State private var status = "Chon anh chan dung tham chieu va anh chup live roi bam Kiem tra chinh danh."
    @State private var showRefPicker = false
    @State private var showLivePicker = false
    @State private var refSource: UIImagePickerController.SourceType = .camera
    @State private var liveSource: UIImagePickerController.SourceType = .camera
    @State private var isVerifying = false

    private var report: Report? {
        appState.faceResult == nil ? nil : CombinedReport.build(
            emv: nil, ocr: nil, fullPan: false, ocrImage: nil, cccd: nil, cccdPhoto: nil,
            faceResult: appState.faceResult, refPhoto: appState.refPhoto, livePhoto: appState.livePhoto)
    }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text(status).font(.headline)

                    GroupBox("Cau hinh FaceID service (tu host, khong phai ben thu ba)") {
                        VStack(alignment: .leading, spacing: 6) {
                            TextField("URL", text: $url).textInputAutocapitalization(.never).disableAutocorrection(true)
                            SecureField("API key", text: $apiKey)
                        }
                    }

                    HStack(spacing: 20) {
                        VStack {
                            Text("Anh chan dung tham chieu\n(CCCD hoac the SV)").multilineTextAlignment(.center).font(.caption)
                            photoBox(appState.refPhoto)
                            HStack {
                                Button("CCCD") { if let p = appState.cccdPhoto { appState.refPhoto = p } }
                                Button("Anh") { refSource = .photoLibrary; showRefPicker = true }
                                Button("Cam") { refSource = .camera; showRefPicker = true }
                            }.font(.caption)
                        }
                        VStack {
                            Text("Anh chup truc tiep\n(live)").multilineTextAlignment(.center).font(.caption)
                            photoBox(appState.livePhoto)
                            HStack {
                                Button("Cam") { liveSource = .camera; showLivePicker = true }
                                Button("Anh") { liveSource = .photoLibrary; showLivePicker = true }
                            }.font(.caption)
                        }
                    }

                    Button("Kiem tra chinh danh", action: verify).disabled(isVerifying)
                    ReportButtons(report: report, filePrefix: "FaceID")

                    if let fr = appState.faceResult {
                        let color: Color = fr.decision == "match" ? .green : (fr.decision == "no_match" ? .red : .orange)
                        Text("\(fr.label) (do giong \(String(format: "%.1f", fr.similarity * 100))%)")
                            .font(.headline).foregroundColor(color)
                        Text("Nguong khop: \(Int(fr.matchThreshold * 100))%  |  Nguong loai: \(Int(fr.rejectThreshold * 100))%")
                            .font(.caption).foregroundColor(.secondary)
                    }

                    Text("Luu y: day khong phai kiem tra chong gia mao (liveness) - chi dam bao anh la chup moi, " +
                        "khong chong duoc viec chup lai mot tam anh in. Anh khuon mat la du lieu sinh trac hoc " +
                        "nhay cam - chi dung voi nguoi da dong y, khong luu/chia se anh ngoai muc dich nay.")
                        .font(.footnote).foregroundColor(.red)
                }
                .padding()
            }
            .navigationTitle("Xac thuc khuon mat")
        }
        .navigationViewStyle(.stack)
        .sheet(isPresented: $showRefPicker) {
            ImagePicker(sourceType: refSource) { img in
                appState.refPhoto = img.jpegData(compressionQuality: 0.85)
                appState.faceResult = nil
            }
        }
        .sheet(isPresented: $showLivePicker) {
            ImagePicker(sourceType: liveSource) { img in
                appState.livePhoto = img.jpegData(compressionQuality: 0.85)
                appState.faceResult = nil
            }
        }
    }

    @ViewBuilder private func photoBox(_ data: Data?) -> some View {
        if let data = data, let img = UIImage(data: data) {
            Image(uiImage: img).resizable().scaledToFit().frame(width: 130, height: 160).background(Color(.systemGray5))
        } else {
            Rectangle().fill(Color(.systemGray5)).frame(width: 130, height: 160)
        }
    }

    private func verify() {
        guard let ref = appState.refPhoto, let live = appState.livePhoto else {
            status = "Can ca anh tham chieu va anh live."; return
        }
        guard !url.isEmpty else { status = "Nhap dia chi FaceID service."; return }
        isVerifying = true
        status = "Dang gui anh va so khop... (lan dau sau khi server khoi dong co the mat den 1 phut)"
        FaceIdClient.verify(url: url, apiKey: apiKey, imageA: ref, imageB: live) { result in
            DispatchQueue.main.async {
                isVerifying = false
                switch result {
                case .success(let r):
                    appState.faceResult = r
                    status = "Da kiem tra xong."
                case .failure(let e):
                    status = e.description
                }
            }
        }
    }
}
