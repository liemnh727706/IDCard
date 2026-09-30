import Foundation

public struct OcrCardInfo { public let pans: [String]; public let expiries: [String]; public let rawText: String; public let name: String? }
public struct ChipCardInfo { public let pan: String; public let expiryYymm: String?; public let name: String
    public init(pan: String, expiryYymm: String?, name: String = "") {
        self.pan = pan; self.expiryYymm = expiryYymm; self.name = name
    }
}

/// Bo dau tieng Viet (NFD roi loai ky tu combining), dung de so khop ten chip (khong dau) voi
/// ten OCR (co dau).
public func stripVietnameseDiacritics(_ s: String) -> String {
    let folded = s.folding(options: .diacriticInsensitive, locale: Locale(identifier: "vi_VN"))
    return folded.replacingOccurrences(of: "\u{0110}", with: "D").replacingOccurrences(of: "\u{0111}", with: "d")
}

public func namesMatch(_ chipNameNoDiacritics: String, _ ocrNameWithDiacritics: String) -> Bool {
    let a = stripVietnameseDiacritics(chipNameNoDiacritics).uppercased()
        .trimmingCharacters(in: .whitespaces).components(separatedBy: .whitespaces).filter { !$0.isEmpty }
    let b = stripVietnameseDiacritics(ocrNameWithDiacritics).uppercased()
        .trimmingCharacters(in: .whitespaces).components(separatedBy: .whitespaces).filter { !$0.isEmpty }
    return a.sorted() == b.sorted()
}

private let nameLabelRe = try! NSRegularExpression(
    pattern: "(?:ho\\s*va\\s*ten|ho\\s*ten|full\\s*name|name)\\s*[:\\-]?\\s*", options: [.caseInsensitive])

/// Tim dong chua ho ten co dau tren anh OCR (the sinh vien/CCCD), theo nhan "Ho va ten"/"Full name".
public func extractOcrName(_ text: String) -> String? {
    for raw in text.components(separatedBy: .newlines) {
        let line = raw.trimmingCharacters(in: .whitespaces)
        if line.isEmpty { continue }
        let stripped = stripVietnameseDiacritics(line)
        let ns = stripped as NSString
        guard let m = nameLabelRe.firstMatch(in: stripped, range: NSRange(location: 0, length: ns.length)) else { continue }
        let end = m.range.location + m.range.length
        guard end <= (line as NSString).length else { continue }
        var candidate = (line as NSString).substring(from: end).trimmingCharacters(in: .whitespaces)
        candidate = candidate.replacingOccurrences(of: "[^A-Za-zÀ-ỹ\\s]", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        candidate = candidate.replacingOccurrences(of: "\\s{2,}", with: " ", options: .regularExpression)
        if candidate.isEmpty { continue }
        let words = candidate.components(separatedBy: " ")
        if (2...6).contains(words.count), words.allSatisfy({ !$0.isEmpty && $0.first!.isLetter }) { return candidate }
    }
    return nil
}

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
        return OcrCardInfo(pans: pans, expiries: expiries, rawText: text, name: extractOcrName(text))
    }
}

public enum CardMatcher {
    public enum Level { case match, partial, mismatch, missing }
    public struct Verdict {
        public let panLevel: Level; public let expiryLevel: Level; public let bankLevel: Level; public let nameLevel: Level; public let lines: [String]
        public var overall: String {
            switch true {
            case panLevel == .mismatch || expiryLevel == .mismatch || bankLevel == .mismatch || nameLevel == .mismatch:
                return "KHONG KHOP: du lieu in khac voi chip, nghi ngo the bi chinh sua hoac OCR sai"
            case panLevel == .match && expiryLevel == .match:
                let base = "KHOP: so the va han dung in tren the trung voi chip"
                return nameLevel == .match ? base + ", ca ho ten" : base
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

        let nameLevel: Level
        if chip.name.trimmingCharacters(in: .whitespaces).isEmpty {
            lines.append("Ho ten: chip khong luu ten, khong so khop duoc"); nameLevel = .missing
        } else if let ocrName = ocr.name {
            if namesMatch(chip.name, ocrName) {
                lines.append("Ho ten: OCR doc \"\(ocrName)\" (co dau), khop voi ten chip \"\(chip.name)\" (khong dau)")
                nameLevel = .match
            } else {
                lines.append("Ho ten: OCR doc \"\(ocrName)\", KHONG khop voi ten chip \"\(chip.name)\"")
                nameLevel = .mismatch
            }
        } else {
            let toks = stripVietnameseDiacritics(chip.name).uppercased().components(separatedBy: .whitespaces).filter { !$0.isEmpty }
            let printed = stripVietnameseDiacritics(ocr.rawText).uppercased()
            let hit = toks.filter { t in
                (try? NSRegularExpression(pattern: "(?<![A-Z])" + NSRegularExpression.escapedPattern(for: t) + "(?![A-Z])"))?
                    .firstMatch(in: printed, range: NSRange(location: 0, length: (printed as NSString).length)) != nil
            }
            if !toks.isEmpty && hit.count == toks.count {
                lines.append("Ho ten: ten trong chip xuat hien du tren mat the (khop, chua xac dinh duoc dang co dau)")
                nameLevel = .match
            } else if !hit.isEmpty {
                lines.append("Ho ten: chi thay \(hit.count)/\(toks.count) tu cua ten chip tren mat the")
                nameLevel = .partial
            } else {
                lines.append("Ho ten: ten trong chip khong thay tren mat the (hoac OCR chua doc duoc ten)")
                nameLevel = .mismatch
            }
        }

        return Verdict(panLevel: panLevel, expiryLevel: expiryLevel, bankLevel: bankLevel, nameLevel: nameLevel, lines: lines)
    }
}
