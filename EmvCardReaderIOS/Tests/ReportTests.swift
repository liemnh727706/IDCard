import XCTest
@testable import EmvCardReader

final class ReportTests: XCTestCase {
    private let pan = "9704180000004883831"

    private func tlv(_ tag: String, _ v: Data) -> Data { Data(hex: tag) + Data([UInt8(v.count)]) + v }

    private func sampleNodes() -> [String: [TlvNode]] {
        let rec = tlv("70",
            tlv("57", Data(hex: pan + "D28016010000041300F")) + tlv("5F24", Data(hex: "280131")) +
            tlv("5A", Data(hex: pan + "F")) + tlv("5F20", Data("NGUYEN/VAN A".utf8)) +
            tlv("50", Data("NAPAS Debit".utf8)) + tlv("5F28", Data(hex: "0704")))
        return ["A0000007271010": TlvParser.parse(rec)]
    }

    func testSummarizeExtractsFields() {
        let s = EmvReport.summarize(nodesByAid: sampleNodes(), aids: ["A0000007271010"], log: "log")!
        XCTAssertEqual(s.pan, pan); XCTAssertEqual(s.expiryYymm, "2801"); XCTAssertEqual(s.name, "NGUYEN/VAN A")
        XCTAssertEqual(s.serviceCode, "601"); XCTAssertEqual(s.label, "NAPAS Debit")
    }

    func testEmvReportMasksPanEverywhereUnlessFull() {
        let hexPan = Data(pan.utf8).hexString
        let log = "5A = \(pan)F\n57 = \(pan)D2801\nRSP: \(hexPan)"
        let s = EmvReport.summarize(nodesByAid: sampleNodes(), aids: ["A0000007271010"], log: log)!
        let masked = EmvReport.build(s, ocr: nil, fullPan: false).toText()
        XCTAssertFalse(masked.contains(pan))
        XCTAssertTrue(masked.contains("970418*********3831"))
        XCTAssertFalse(masked.contains(hexPan))
        XCTAssertTrue(EmvReport.build(s, ocr: nil, fullPan: true).toText().contains(pan))
    }

    func testEmvReportIncludesOcrComparison() {
        let s = EmvReport.summarize(nodesByAid: sampleNodes(), aids: [], log: "")!
        let ocr = OcrParser.parse("BIDV\n9704 1800 0000 4883 831\nVALID THRU 01/28")
        let txt = EmvReport.build(s, ocr: ocr, fullPan: false).toText()
        XCTAssertTrue(txt.contains("DOI CHIEU VOI MAT THE") || txt.uppercased().contains("DOI CHIEU"))
        XCTAssertTrue(txt.contains("KHOP"))
    }

    func testDateFormatting() {
        XCTAssertEqual(CccdReport.fmtYymmdd("900215", dob: true, nowYear: 2026), "15/02/1990")
        XCTAssertEqual(CccdReport.fmtYymmdd("100215", dob: true, nowYear: 2026), "15/02/2010")
        XCTAssertEqual(CccdReport.fmtYymmdd("300215", dob: false, nowYear: 2026), "15/02/2030")
    }

    func testCccdReportHasMainAndExtraSections() {
        let fields = ["So giay to": "012345678", "Ngay sinh (YYMMDD)": "900215", "Gioi tinh": "M",
                     "Het han (YYMMDD)": "300215", "Quoc tich": "VNM", "Ho ten (MRZ, khong dau)": "NGUYEN VAN A"]
        let body = tlv("5F0E", Data("Nguyen Van A".utf8)) + tlv("5F42", Data("12 Duong ABC, Thu Duc".utf8)) + tlv("5F2B", Data(hex: "19900215"))
        let dg11 = tlv("6B", body)
        let r = CccdReport.build(fields: fields, mrzLines: ["L1", "L2", "L3"], lds: "0107", present: ["DG1", "DG11"],
                                 extras: ["DG11": ("", dg11)], photo: nil)
        let txt = r.toText()
        XCTAssertTrue(txt.contains("15/02/1990")); XCTAssertTrue(txt.contains("Nam")); XCTAssertTrue(txt.contains("Viet Nam"))
        XCTAssertTrue(txt.contains("Nguyen Van A")); XCTAssertTrue(txt.contains("12 Duong ABC, Thu Duc"))
        XCTAssertTrue(txt.contains("CHUA thuc hien"))
        XCTAssertTrue(r.sections.contains { $0.heading.contains("DG11") })
    }
}
