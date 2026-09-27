import SwiftUI

struct CccdTabView: View {
    @State private var mrz1 = ""
    @State private var mrz2 = ""
    @State private var doc = ""
    @State private var dob = ""
    @State private var doe = ""
    @State private var readExtra = true
    @State private var readPhoto = false
    @State private var status = "Nhập khóa mở thẻ (số giấy tờ, ngày sinh, ngày hết hạn) rồi bấm Đọc CCCD."
    @State private var resultText = ""
    @State private var report: Report?
    @State private var portrait: UIImage?
    @State private var showImagePicker = false
    @State private var nfcSession: NfcSession?

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text(status).font(.headline)

                    GroupBox("Khóa mở thẻ: 3 thông tin ở dòng MRZ mặt sau CCCD") {
                        VStack(alignment: .leading, spacing: 6) {
                            Button("Chụp mặt sau CCCD, OCR MRZ tự điền") { showImagePicker = true }
                            TextField("Dòng MRZ 1 (30 ký tự)", text: $mrz1).font(.system(.body, design: .monospaced)).textInputAutocapitalization(.characters)
                            TextField("Dòng MRZ 2 (30 ký tự)", text: $mrz2).font(.system(.body, design: .monospaced)).textInputAutocapitalization(.characters)
                            Button("Điền tự động từ MRZ", action: fillFromMrz)
                            TextField("Số giấy tờ (9 ký tự)", text: $doc).font(.system(.body, design: .monospaced)).textInputAutocapitalization(.characters)
                            TextField("Ngày sinh YYMMDD", text: $dob).font(.system(.body, design: .monospaced)).keyboardType(.numberPad)
                            TextField("Ngày hết hạn YYMMDD", text: $doe).font(.system(.body, design: .monospaced)).keyboardType(.numberPad)
                            Toggle("Đọc thêm DG11/DG12/DG13", isOn: $readExtra)
                            Toggle("Đọc ảnh chân dung DG2 (chậm hơn)", isOn: $readPhoto)
                        }
                    }

                    HStack {
                        Button("Đọc CCCD", action: readCccd)
                        Button("Xóa kết quả") { resultText = ""; report = nil; portrait = nil }
                    }
                    ReportButtons(report: report, filePrefix: "CCCD")

                    if let portrait = portrait {
                        Image(uiImage: portrait).resizable().scaledToFit().frame(maxWidth: 160, maxHeight: 200)
                    }
                    if !resultText.isEmpty {
                        Text(resultText).font(.system(size: 11, design: .monospaced)).textSelection(.enabled)
                    }
                }
                .padding()
            }
            .navigationTitle("Căn cước công dân")
        }
        .navigationViewStyle(.stack)
        .sheet(isPresented: $showImagePicker) {
            ImagePicker(sourceType: .camera) { image in
                status = "Đang OCR dòng MRZ..."
                TextRecognizer.recognizeMultiCrop(image) { text in
                    applyMrz(Mrz.parse(text))
                }
            }
        }
    }

    private func fillFromMrz() {
        let l1 = mrz1.replacingOccurrences(of: " ", with: "").uppercased()
        let l2 = mrz2.replacingOccurrences(of: " ", with: "").uppercased()
        if l1.count >= 14 {
            let c = Array(l1)
            doc = String(c[5..<14]).replacingOccurrences(of: "<", with: "")
        }
        if l2.count >= 14 {
            let c = Array(l2)
            dob = String(c[0..<6]); doe = String(c[8..<14])
        }
    }

    private func applyMrz(_ r: MrzResult?) {
        guard let r = r else {
            status = "Không nhận ra dòng MRZ. Chụp sát, đủ sáng, thấy rõ 3 dòng chữ dưới đáy mặt sau thẻ."
            return
        }
        if let d = r.doc { doc = d }
        if let d = r.dob { dob = d }
        if let d = r.doe { doe = d }
        status = r.note + " Kiểm tra lại các ô rồi bấm Đọc CCCD."
    }

    private func readCccd() {
        let d = doc.trimmingCharacters(in: .whitespaces).uppercased()
        let b = dob.trimmingCharacters(in: .whitespaces)
        let e = doe.trimmingCharacters(in: .whitespaces)
        guard !d.isEmpty, b.count == 6, e.count == 6, b.allSatisfy({ $0.isNumber }), e.allSatisfy({ $0.isNumber }) else {
            status = "Nhập đủ số giấy tờ, ngày sinh và ngày hết hạn (6 chữ số YYMMDD)."
            return
        }
        status = "Đang đọc CCCD, giữ thẻ yên..."
        resultText = ""; report = nil; portrait = nil
        let session = NfcSession(alertMessage: "Áp CCCD vào đỉnh trên của iPhone, giữ yên")
        nfcSession = session
        session.start(onTag: { tx, finish in
            do {
                let res = try CccdReader.read(tx: tx, doc: d, dob: b, doe: e, extra: readExtra, photo: readPhoto,
                                              progress: { p in DispatchQueue.main.async { self.status = p } })
                DispatchQueue.main.async {
                    resultText = "========== KẾT QUẢ ĐỌC CCCD ==========\n" + res.text
                    report = res.report
                    if let photo = res.photo, CccdReader.isJpeg(photo), let img = UIImage(data: photo) { portrait = img }
                    status = "Đã đọc xong CCCD."
                }
                finish("Đã đọc xong")
            } catch {
                DispatchQueue.main.async {
                    resultText = "LỖI: \(error)"
                    status = "Đọc CCCD thất bại."
                }
                finish(nil)
            }
        }, onError: { msg in
            DispatchQueue.main.async { status = "Lỗi đọc CCCD: \(msg)" }
        })
    }
}
