import XCTest
@testable import EmvCardReader

final class CombinedReportTests: XCTestCase {
    private func cccdReport(name: String) -> Report {
        Report(title: "Bao cao doc Can cuoc cong dan gan chip",
              sections: [ReportSection(heading: "Thong tin chinh (DG1)",
                                       rows: [("Ho ten (theo MRZ, khong dau)", name)])])
    }

    func testNilWhenNothingRead() {
        XCTAssertNil(CombinedReport.build(emv: nil, ocr: nil, fullPan: false, ocrImage: nil,
                                          cccd: nil, cccdPhoto: nil, faceResult: nil, refPhoto: nil, livePhoto: nil))
    }

    func testCrossChecksMatchingNamesAcrossSources() {
        let emv = EmvSummary(pan: "9704180000004883831", expiryYymm: "2801", name: "NGUYEN VAN A",
                             label: "NAPAS Debit", aid: "A0000007271010", serviceCode: "601",
                             country: "0704", aids: ["A0000007271010"], log: "")
        let ocr = OcrParser.parse("Ho va ten: NGUYỄN VĂN A\nMSSV: 21123456")
        let cccd = cccdReport(name: "NGUYEN VAN A")

        let rep = CombinedReport.build(emv: emv, ocr: ocr, fullPan: false, ocrImage: nil,
                                       cccd: cccd, cccdPhoto: nil, faceResult: nil, refPhoto: nil, livePhoto: nil)!
        let idSection = rep.sections.first { $0.heading.contains("Ho ten") }!
        XCTAssertTrue(idSection.text.contains("KHOP nhau"))
        XCTAssertTrue(idSection.text.contains("KHOP") && !idSection.text.contains("KHONG khop"))
        XCTAssertEqual(idSection.rows.count, 3)
    }

    func testFlagsMismatchBetweenChipSources() {
        let emv = EmvSummary(pan: "9704180000004883831", expiryYymm: "2801", name: "NGUYEN VAN A",
                             label: "NAPAS Debit", aid: "A0000007271010", serviceCode: "601",
                             country: "0704", aids: ["A0000007271010"], log: "")
        let cccd = cccdReport(name: "TRAN THI B")

        let rep = CombinedReport.build(emv: emv, ocr: nil, fullPan: false, ocrImage: nil,
                                       cccd: cccd, cccdPhoto: nil, faceResult: nil, refPhoto: nil, livePhoto: nil)!
        let idSection = rep.sections.first { $0.heading.contains("Ho ten") }!
        XCTAssertTrue(idSection.text.contains("KHONG khop nhau"))
    }

    func testIncludesPhotosInDedicatedSections() {
        let emv = EmvSummary(pan: "9704180000004883831", expiryYymm: "2801", name: "NGUYEN VAN A",
                             label: "NAPAS Debit", aid: "A0000007271010", serviceCode: "601",
                             country: "0704", aids: ["A0000007271010"], log: "")
        let ocrImg = Data([1, 2, 3]); let cccdImg = Data([4, 5, 6])
        let ref = Data([7, 8, 9]); let live = Data([10, 11, 12])
        let face = FaceVerifyResult(similarity: 0.9, decision: "match", matchThreshold: 0.32, rejectThreshold: 0.20)

        let rep = CombinedReport.build(emv: emv, ocr: nil, fullPan: false, ocrImage: ocrImg,
                                       cccd: cccdReport(name: "NGUYEN VAN A"), cccdPhoto: cccdImg,
                                       faceResult: face, refPhoto: ref, livePhoto: live)!
        let photosByHeading = Dictionary(uniqueKeysWithValues: rep.sections.map { ($0.heading, $0.photo) })
        XCTAssertEqual(photosByHeading["Anh mat the sinh vien (dung de OCR doi chieu)"] ?? nil, ocrImg)
        XCTAssertEqual(photosByHeading["Can cuoc cong dan"] ?? nil, cccdImg)
        XCTAssertEqual(photosByHeading["Anh chan dung tham chieu (dung de xac thuc FaceID)"] ?? nil, ref)
        XCTAssertEqual(photosByHeading["Anh chup live (nguoi cam the luc xac thuc)"] ?? nil, live)
    }
}
