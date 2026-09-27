import Foundation

public struct OcrCardInfo { public let pans: [String]; public let expiries: [String]; public let rawText: String }
public struct ChipCardInfo { public let pan: String; public let expiryYymm: String? }

public enum OcrParser {
    public static func parse(_ text: String) -> OcrCardInfo {
        var pans: [String] = []
        var expiries: [String] = []
        let fix: [Character: Character] = ["O": "0", "o": "0", "I": "1", "l": "1", "S": "5", "B": "8"]

        for line in text.components(separatedBy: .newlines) {
            let digitLike = line.filter { $0.isNumber || "OoIlSB".contains($0) }.count
            if digitLike >= 12 {
                let fixed = String(line.map { fix[$0] ?? $0 })
                if let re = try? NSRegularExpression(pattern: "(?:\\d[ \\-]?){13,19}") {
                    let ns = fixed as NSString
                    for m in re.matches(in: fixed, range: NSRange(location: 0, length: ns.length)) {
                        let digits = ns.substring(with: m.range).filter { $0.isNumber }
                        if (13...19).contains(digits.count), !pans.contains(digits) { pans.append(digits) }
                    }
                }
            }
        }
        if let re = try? NSRegularExpression(pattern: "(0[1-9]|1[0-2])\\s*[/\\-]\\s*(\\d{2})(?!\\d)") {
            let ns = text as NSString
            for m in re.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
                let mm = ns.substring(with: m.range(at: 1)); let yy = ns.substring(with: m.range(at: 2))
                let v = yy + mm
                if !expiries.contains(v) { expiries.append(v) }
            }
        }
        return OcrCardInfo(pans: pans, expiries: expiries, rawText: text)
    }
}

public enum CardMatcher {
    public enum Level { case match, partial, mismatch, missing }
    public struct Verdict {
        public let panLevel: Level; public let expiryLevel: Level; public let bankLevel: Level; public let lines: [String]
        public var overall: String {
            switch true {
            case panLevel == .mismatch || expiryLevel == .mismatch || bankLevel == .mismatch:
                return "KHONG KHOP: du lieu in khac voi chip, nghi ngo the bi chinh sua hoac OCR sai"
            case panLevel == .match && expiryLevel == .match:
                return "KHOP: so the va han dung in tren the trung voi chip"
            default:
                return "CHUA KET LUAN: OCR chua du du lieu, thu chup lai ro hon"
            }
        }
    }

    private static func mask(_ pan: String) -> String {
        pan.count < 11 ? pan : String(pan.prefix(6)) + String(repeating: "*", count: pan.count - 10) + String(pan.suffix(4))
    }
    private static func fmt(_ yymm: String) -> String { String(yymm.suffix(2)) + "/" + String(yymm.prefix(2)) }
    private static func samePrefixSuffix(_ a: String, _ b: String) -> Bool {
        a.count >= 10 && b.count >= 10 && a.prefix(6) == b.prefix(6) && a.suffix(4) == b.suffix(4)
    }

    public static func compare(_ ocr: OcrCardInfo, _ chip: ChipCardInfo) -> Verdict {
        var lines: [String] = []

        let panLevel: Level
        if ocr.pans.isEmpty {
            lines.append("So the in: OCR khong tim thay day 13-19 chu so"); panLevel = .missing
        } else if ocr.pans.contains(chip.pan) {
            lines.append("So the in: \(mask(chip.pan)) = chip (khop toan bo)"); panLevel = .match
        } else if let p = ocr.pans.first(where: { samePrefixSuffix($0, chip.pan) }) {
            lines.append("So the in: \(mask(p)) khop 6 dau + 4 cuoi nhung khac do dai/giua (\(p.count) vs \(chip.pan.count) so)")
            panLevel = .partial
        } else {
            lines.append("So the in: \(ocr.pans.map(mask).joined(separator: ", ")) khac chip \(mask(chip.pan))")
            panLevel = .mismatch
        }

        let expiryLevel: Level
        if chip.expiryYymm == nil {
            lines.append("Han dung chip: khong co"); expiryLevel = .missing
        } else if ocr.expiries.isEmpty {
            lines.append("Han dung in: OCR khong tim thay dang MM/YY"); expiryLevel = .missing
        } else if ocr.expiries.contains(chip.expiryYymm!) {
            lines.append("Han dung in: \(fmt(chip.expiryYymm!)) = chip (khop)"); expiryLevel = .match
        } else {
            lines.append("Han dung in: \(ocr.expiries.map(fmt).joined(separator: ", ")) khac chip \(fmt(chip.expiryYymm!))")
            expiryLevel = .mismatch
        }

        let cb = Banks.lookup(chip.pan)
        let mentioned = Banks.mentionedIn(ocr.rawText)
        let bankLevel: Level
        if cb == nil {
            lines.append("Ngan hang: BIN \(chip.pan.prefix(6)) chua co trong bang tra"); bankLevel = .missing
        } else if mentioned.contains(where: { $0.bin == cb!.bin }) {
            lines.append("Ngan hang: chip la \(cb!.name), ten in tren the cung la \(cb!.name) (khop)"); bankLevel = .match
        } else if !mentioned.isEmpty {
            lines.append("Ngan hang: chip la \(cb!.name) nhung the in ten \(mentioned.map { $0.name }.joined(separator: ", "))")
            bankLevel = .mismatch
        } else {
            lines.append("Ngan hang: chip la \(cb!.name), OCR khong thay ten ngan hang in (co the chi co logo)")
            bankLevel = .missing
        }

        return Verdict(panLevel: panLevel, expiryLevel: expiryLevel, bankLevel: bankLevel, lines: lines)
    }
}
