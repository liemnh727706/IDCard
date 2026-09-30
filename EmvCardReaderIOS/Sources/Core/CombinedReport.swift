import Foundation

/// Gop thong tin da doc/xac thuc tu the ngan hang (SV), CCCD va FaceID thanh mot bao cao.
public enum CombinedReport {
    public static func build(
        emv: EmvSummary?, ocr: OcrCardInfo?, fullPan: Bool, ocrImage: Data?,
        cccd: Report?, cccdPhoto: Data?,
        faceResult: FaceVerifyResult?, refPhoto: Data?, livePhoto: Data?
    ) -> Report? {
        if emv == nil && cccd == nil { return nil }

        var sections: [ReportSection] = []
        var namesFound: [(String, String)] = []

        if let n = ocr?.name { namesFound.append(("OCR anh the (co dau)", n)) }
        if let emv = emv, !emv.name.isEmpty { namesFound.append(("Chip the ngan hang/SV (khong dau)", emv.name)) }
        let cccdName = cccd?.sections.flatMap { $0.rows }.first { $0.0 == "Ho ten (theo MRZ, khong dau)" }?.1
        if let n = cccdName, !n.isEmpty { namesFound.append(("Chip CCCD - MRZ (khong dau)", n)) }

        let identityRows = namesFound.map { ("Ho ten - \($0.0)", $0.1) }
        let noDiacriticNames = namesFound.filter { $0.0.contains("khong dau") }.map { $0.1 }
        var crossLines: [String] = []
        if noDiacriticNames.count >= 2 {
            let base = stripVietnameseDiacritics(noDiacriticNames[0]).uppercased().components(separatedBy: .whitespaces)
            let allSame = noDiacriticNames.dropFirst().allSatisfy {
                stripVietnameseDiacritics($0).uppercased().components(separatedBy: .whitespaces) == base
            }
            crossLines.append("Ten tren chip the ngan hang/SV va chip CCCD: " +
                              (allSame ? "KHOP nhau" : "KHONG khop nhau - kiem tra lai co dung cung mot nguoi khong"))
        }
        if let ocrName = ocr?.name, !noDiacriticNames.isEmpty {
            let ok = noDiacriticNames.contains { namesMatch($0, ocrName) }
            crossLines.append("Ten OCR co dau \"\(ocrName)\" so voi ten tren chip: " + (ok ? "KHOP" : "KHONG khop hoac chua xac nhan duoc"))
        }
        sections.append(ReportSection(heading: "Ho ten - doi chieu nhieu nguon", rows: identityRows,
                                      text: crossLines.map { "- " + $0 }.joined(separator: "\n")))

        if let emv = emv {
            let pan = fullPan ? emv.pan : EmvReport.maskPan(emv.pan)
            let exp = emv.expiryYymm.map { String($0.suffix(2)) + "/" + String($0.prefix(2)) } ?? "(khong co)"
            sections.append(ReportSection(heading: "The ngan hang / the sinh vien", rows: [
                ("Loai the / ung dung", "\(emv.label) (AID \(emv.aid))"),
                ("So the (PAN)", pan + (fullPan ? "" : "  [da che bot]")),
                ("Han dung (MM/YY)", exp),
                ("Ngan hang / nha phat hanh", Banks.describe(emv.pan)),
            ]))
        }
        if let ocrImage = ocrImage {
            sections.append(ReportSection(heading: "Anh mat the sinh vien (dung de OCR doi chieu)", photo: ocrImage))
        }
        if let cccdSection = cccd?.sections.first {
            sections.append(ReportSection(heading: "Can cuoc cong dan", rows: cccdSection.rows, photo: cccdPhoto))
        }

        if let fr = faceResult {
            let label = fr.decision == "match" ? "KHOP" : (fr.decision == "no_match" ? "KHONG KHOP" : "KHONG CHAC CHAN")
            sections.append(ReportSection(heading: "Xac thuc khuon mat (FaceID)", rows: [
                ("Ket luan", label),
                ("Do giong", String(format: "%.1f%%", fr.similarity * 100)),
            ]))
        } else {
            sections.append(ReportSection(heading: "Xac thuc khuon mat (FaceID)",
                                          text: "Chua thuc hien kiem tra chinh danh bang khuon mat."))
        }
        if let refPhoto = refPhoto {
            sections.append(ReportSection(heading: "Anh chan dung tham chieu (dung de xac thuc FaceID)", photo: refPhoto))
        }
        if let livePhoto = livePhoto {
            sections.append(ReportSection(heading: "Anh chup live (nguoi cam the luc xac thuc)", photo: livePhoto))
        }

        let note = Report.sensitive + " Bao cao ket hop nhieu nguon du lieu ca nhan (the ngan hang, " +
            "CCCD, khuon mat) - bao quan dac biet can than."
        return Report(title: "Bao cao ket hop: xac thuc chinh danh the sinh vien + CCCD", sections: sections, sensitiveNote: note)
    }
}
