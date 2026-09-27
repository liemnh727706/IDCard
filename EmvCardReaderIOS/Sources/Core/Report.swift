import Foundation

public struct ReportSection {
    public let heading: String
    public let rows: [(String, String)]
    public let text: String
    public let photo: Data?
    public init(heading: String, rows: [(String, String)] = [], text: String = "", photo: Data? = nil) {
        self.heading = heading; self.rows = rows; self.text = text; self.photo = photo
    }
}

public struct Report {
    public static let sensitive = "TAI LIEU CHUA DU LIEU CA NHAN NHAY CAM. Chi dung cho chu the hoac khi co su dong y " +
        "cua chu the; bao quan, khong chia se va huy khi khong con can thiet."

    public let title: String
    public let sections: [ReportSection]
    public let sensitiveNote: String
    public let generated: String

    public init(title: String, sections: [ReportSection], sensitiveNote: String = Report.sensitive) {
        self.title = title
        self.sections = sections
        self.sensitiveNote = sensitiveNote
        let df = DateFormatter()
        df.dateFormat = "dd/MM/yyyy HH:mm:ss"
        df.locale = Locale(identifier: "en_US_POSIX")
        self.generated = df.string(from: Date())
    }

    public func toText() -> String {
        var sb = title + "\nThoi diem tao: " + generated + "\n\n!! " + sensitiveNote + "\n\n"
        for s in sections {
            sb += String(repeating: "=", count: 60) + "\n" + s.heading.uppercased() + "\n" + String(repeating: "=", count: 60) + "\n"
            for (k, v) in s.rows { sb += k.padding(toLength: max(32, k.count), withPad: " ", startingAt: 0) + ": " + v + "\n" }
            if !s.text.isEmpty { sb += "\n" + s.text + "\n" }
            if s.photo != nil { sb += "(co anh chan dung, chi hien thi trong file PDF)\n" }
            sb += "\n"
        }
        return sb
    }
}

/// Thong tin rut ra tu chip the ngan hang de dung bao cao.
public struct EmvSummary {
    public let pan: String; public let expiryYymm: String?; public let name: String; public let label: String
    public let aid: String; public let serviceCode: String; public let country: String; public let aids: [String]; public let log: String
}

public enum EmvReport {
    public static func summarize(nodesByAid: [String: [TlvNode]], aids: [String], log: String) -> EmvSummary? {
        for (aid, nodes) in nodesByAid {
            let pan5a = TlvParser.findFirst(nodes, "5A")?.value.hexString.trimmingTrailingF()
            let t2 = TlvParser.findFirst(nodes, "57")?.value.hexString
            guard pan5a != nil || t2 != nil else { continue }
            var pan = pan5a ?? ""
            var exp: String?
            var service = ""
            if let t2 = t2, let dIdx = t2.firstIndex(of: "D") {
                let p = String(t2[t2.startIndex..<dIdx])
                let rest = String(t2[t2.index(after: dIdx)...])
                if pan.isEmpty { pan = p }
                exp = String(rest.prefix(4))
                service = String(rest.dropFirst(4).prefix(3))
            }
            if let e = TlvParser.findFirst(nodes, "5F24")?.value.hexString { exp = String(e.prefix(4)) }
            var name = ""
            for tag in ["5F20", "9F0B"] {
                if let v = TlvParser.findFirst(nodes, tag)?.value, v.contains(where: { $0 != 0 && $0 != 0x20 }) {
                    name = String(data: v, encoding: .ascii)?.trimmingCharacters(in: .whitespaces) ?? ""
                    break
                }
            }
            if name.isEmpty, let t1 = TlvParser.findFirst(nodes, "56")?.value, let s = String(data: t1, encoding: .ascii) {
                let parts = s.components(separatedBy: "^")
                if parts.count >= 3 { name = parts[1].trimmingCharacters(in: .whitespaces) }
            }
            let label = TlvParser.findFirst(nodes, "50").flatMap { String(data: $0.value, encoding: .ascii) } ?? ""
            let country = TlvParser.findFirst(nodes, "5F28")?.value.hexString ?? ""
            return EmvSummary(pan: pan, expiryYymm: exp, name: name, label: label, aid: aid,
                              serviceCode: service, country: country, aids: aids, log: log)
        }
        return nil
    }

    public static func maskPan(_ pan: String) -> String {
        pan.count < 11 ? pan : String(pan.prefix(6)) + String(repeating: "*", count: pan.count - 10) + String(pan.suffix(4))
    }

    public static func maskInText(_ text: String, _ pan: String) -> String {
        guard !pan.isEmpty else { return text }
        let masked = maskPan(pan)
        let asciiHex = { (s: String) -> String in Data(s.utf8).hexString }
        return text.replacingOccurrences(of: pan, with: masked)
            .replacingOccurrences(of: asciiHex(pan), with: asciiHex(masked))
    }

    public static func build(_ s: EmvSummary, ocr: OcrCardInfo?, fullPan: Bool) -> Report {
        let pan = fullPan ? s.pan : maskPan(s.pan)
        let exp = s.expiryYymm.map { String($0.suffix(2)) + "/" + String($0.prefix(2)) } ?? "(khong co)"
        let country = s.country == "0704" ? "Viet Nam (704)" : (s.country.isEmpty ? "(khong co)" : s.country)
        let main: [(String, String)] = [
            ("Loai the / ung dung", "\(s.label) (AID \(s.aid))"),
            ("So the (PAN)", pan + (fullPan ? "" : "  [da che bot]")),
            ("Han dung (MM/YY)", exp),
            ("Ho ten chu the (theo chip)", s.name.isEmpty ? "(chip khong luu ten)" : s.name),
            ("Ngan hang / nha phat hanh", Banks.describe(s.pan)),
            ("Service code", s.serviceCode.isEmpty ? "(khong co)" : s.serviceCode),
            ("Quoc gia phat hanh", country),
        ]
        var sections = [ReportSection(heading: "Thong tin chinh", rows: main)]
        if let ocr = ocr {
            let v = CardMatcher.compare(ocr, ChipCardInfo(pan: s.pan, expiryYymm: s.expiryYymm))
            sections.append(ReportSection(heading: "Doi chieu voi mat the (OCR)", rows: [("Ket luan", v.overall)],
                                          text: v.lines.map { "- " + $0 }.joined(separator: "\n")))
        }
        let log = fullPan ? s.log : maskInText(s.log, s.pan)
        sections.append(ReportSection(heading: "Thong tin kem theo (ky thuat)",
                                      rows: [("So ung dung (AID)", s.aids.joined(separator: ", "))], text: log))
        let note = Report.sensitive + (fullPan ? " So the hien thi DAY DU." : " So the da duoc che bot.")
        return Report(title: "Bao cao doc the ngan hang gan chip", sections: sections, sensitiveNote: note)
    }
}

public enum CccdReport {
    private static let sex = ["M": "Nam", "F": "Nu", "<": "Khong xac dinh"]
    private static let country = ["VNM": "Viet Nam"]
    private static let dg11 = ["5F0E": "Ho ten day du", "5F0F": "Ten khac", "5F10": "So dinh danh ca nhan",
                               "5F2B": "Ngay sinh (day du)", "5F11": "Noi sinh", "5F42": "Dia chi",
                               "5F12": "So dien thoai", "5F13": "Nghe nghiep", "5F14": "Chuc danh",
                               "5F15": "Tom tat ca nhan", "5F16": "Giay to chung minh quoc tich",
                               "5F17": "So khac", "5F18": "Thong tin nguoi giam ho"]
    private static let dg12 = ["5F19": "Co quan cap", "5F26": "Ngay cap", "5F1B": "Ghi chu",
                               "5F1C": "Chi tiet khac", "5F55": "Thoi diem ca nhan hoa"]
    private static let dateTags: Set<String> = ["5F2B", "5F26"]

    public static func fmtYymmdd(_ s: String, dob: Bool, nowYear: Int = Calendar.current.component(.year, from: Date())) -> String {
        guard s.count == 6, s.allSatisfy({ $0.isNumber }) else { return s }
        let chars = Array(s)
        let yy = Int(String(chars[0..<2])) ?? 0
        let year = (dob && yy > nowYear % 100) ? 1900 + yy : 2000 + yy
        return "\(String(chars[4..<6]))/\(String(chars[2..<4]))/\(year)"
    }

    private static func value(_ tag: String, _ v: Data) -> String {
        if dateTags.contains(tag) {
            let h = v.hexString
            if h.count == 8, h.allSatisfy({ $0.isNumber }) {
                return String(h.suffix(2)) + "/" + String(h.dropFirst(4).prefix(2)) + "/" + String(h.prefix(4))
            }
        }
        let t = String(data: v, encoding: .utf8) ?? v.hexString
        return t.replacingOccurrences(of: "<", with: " ").trimmingCharacters(in: .whitespaces)
    }

    private static func tagRows(_ body: Data, _ map: [String: String]) -> [(String, String)] {
        TlvParser.flatten(TlvParser.parse(body))
            .filter { !$0.isConstructed && map[$0.tag] != nil && $0.value.contains(where: { $0 != 0 && $0 != 0x20 }) }
            .map { (map[$0.tag]!, value($0.tag, $0.value)) }
    }

    public static func build(fields: [String: String], mrzLines: [String], lds: String, present: [String],
                             extras: [String: (String, Data)], photo: Data?) -> Report {
        var main: [(String, String)] = []
        if !fields.isEmpty {
            func f(_ k: String) -> String { fields[k] ?? "" }
            main.append(("Ho ten (theo MRZ, khong dau)", f("Ho ten (MRZ, khong dau)")))
            main.append(("So giay to", f("So giay to")))
            main.append(("Ngay sinh", fmtYymmdd(f("Ngay sinh (YYMMDD)"), dob: true)))
            main.append(("Gioi tinh", sex[f("Gioi tinh")] ?? f("Gioi tinh")))
            main.append(("Ngay het han", fmtYymmdd(f("Het han (YYMMDD)"), dob: false)))
            main.append(("Quoc tich", country[f("Quoc tich")] ?? f("Quoc tich")))
            main.append(("Noi cap (quoc gia)", country[f("Quoc gia cap")] ?? f("Quoc gia cap")))
            main.append(("Loai giay to (ma)", f("Loai giay to")))
            let opt = [f("Truong tuy chon 1"), f("Truong tuy chon 2")].filter { !$0.isEmpty }.joined(separator: " ")
            if !opt.isEmpty { main.append(("Truong tuy chon trong MRZ", opt)) }
        }
        let mainFiltered = main.filter { !$0.1.isEmpty }
        var sections = [ReportSection(heading: "Thong tin chinh (DG1)",
                                      rows: mainFiltered.isEmpty ? [("Khong doc duoc DG1", "")] : mainFiltered, photo: photo)]

        var extraRows: [(String, String)] = []
        var dg13 = ""
        for (name, pair) in extras {
            let (dump, data) = pair
            let body = CccdReader.stripHead(data)
            switch name {
            case "DG11": extraRows += tagRows(body, dg11).map { ($0.0 + " (DG11)", $0.1) }
            case "DG12": extraRows += tagRows(body, dg12).map { ($0.0 + " (DG12)", $0.1) }
            case "DG13": dg13 = dump
            default: break
            }
        }
        if !extraRows.isEmpty { sections.append(ReportSection(heading: "Thong tin kem theo tren chip (DG11/DG12)", rows: extraRows)) }
        if !dg13.isEmpty { sections.append(ReportSection(heading: "Du lieu rieng cua the (DG13)", text: dg13)) }
        else if !extras.isEmpty { sections.append(ReportSection(heading: "Thong tin kem theo", text: "Cac file bo sung (DG11/12/13) bi khoa hoac khong co du lieu.")) }

        sections.append(ReportSection(heading: "Thong tin ky thuat", rows: [
            ("Phien ban LDS", lds.isEmpty ? "(khong ro)" : lds),
            ("File du lieu co tren the", present.isEmpty ? "(khong ro)" : present.joined(separator: ", ")),
            ("Xac thuc truy cap", "BAC (Basic Access Control) thanh cong"),
            ("Kenh doc", "NFC (ISO7816 qua CoreNFC)"),
            ("Xac thuc chu ky so (SOD)", "CHUA thuc hien - chua chung minh du lieu la nguyen ban"),
        ], text: mrzLines.isEmpty ? "" : "MRZ nguyen van:\n" + mrzLines.joined(separator: "\n")))
        return Report(title: "Bao cao doc Can cuoc cong dan gan chip", sections: sections)
    }
}

private extension String {
    func trimmingTrailingF() -> String {
        var s = Substring(self)
        while s.hasSuffix("F") || s.hasSuffix("f") { s = s.dropLast() }
        return String(s)
    }
}
