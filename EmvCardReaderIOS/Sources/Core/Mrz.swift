import Foundation

public struct MrzResult {
    public let doc: String?
    public let dob: String?
    public let doe: String?
    public let note: String
}

/// Trích 3 khóa BAC từ văn bản OCR của dòng MRZ TD1 (CCCD). Chỉ nhận khi chữ số kiểm tra khớp.
public enum Mrz {
    private static let fix: [Character: Character] = ["O": "0", "Q": "0", "D": "0", "I": "1", "L": "1",
                                                       "Z": "2", "S": "5", "B": "8", "G": "6"]

    private static func digits(_ s: Substring) -> String? {
        var out = ""
        for ch in s {
            if ch.isNumber { out.append(ch) }
            else if let f = fix[ch] { out.append(f) }
            else { return nil }
        }
        return out
    }

    private static func validDate(_ d: String) -> Bool {
        let m = Int(d.dropFirst(2).prefix(2)) ?? 0
        let day = Int(d.dropFirst(4).prefix(2)) ?? 0
        return (1...12).contains(m) && (1...31).contains(day)
    }

    private static func norm(_ t: String) -> String {
        t.uppercased()
            .replacingOccurrences(of: "«", with: "<")
            .replacingOccurrences(of: "‹", with: "<")
            .replacingOccurrences(of: "〈", with: "<")
            .filter { !$0.isWhitespace }
    }

    private struct Scan { var doc: String?; var docScore = -1; var dob: String?; var doe: String?; var dateScore = -1 }

    private static func scan(_ s: String) -> Scan {
        var r = Scan()
        let chars = Array(s)
        var i = 0
        while i + 15 <= chars.count {
            let w = String(chars[i..<i + 15])
            let wChars = Array(w)
            if let b = digits(Substring(String(wChars[0..<6]))), let bc = digits(Substring(String(wChars[6..<7]))),
               let e = digits(Substring(String(wChars[8..<14]))), let ec = digits(Substring(String(wChars[14..<15]))),
               "MF<".contains(wChars[7]),
               checkDigitStr(b) == Int(bc), checkDigitStr(e) == Int(ec), validDate(b), validDate(e) {
                let score = (i + 18 <= chars.count && String(chars[(i + 15)..<(i + 18)]) == "VNM") ? 2 : 1
                if score > r.dateScore { r.dateScore = score; r.dob = b; r.doe = e }
            }
            i += 1
        }
        var searchFrom = s.startIndex
        while let range = s.range(of: "VNM", range: searchFrom..<s.endIndex) {
            let idx = s.distance(from: s.startIndex, to: range.lowerBound)
            if idx + 13 <= chars.count {
                let raw = String(chars[(idx + 3)..<(idx + 12)])
                if let cd = digits(Substring(String(chars[(idx + 12)..<(idx + 13)]))) {
                    var cand: String?
                    if checkDigitStr(raw) == Int(cd) { cand = raw.replacingOccurrences(of: "<", with: "") }
                    else if let d = digits(Substring(raw)), checkDigitStr(d) == Int(cd) { cand = d }
                    if let c = cand {
                        let pre = String(chars[max(idx - 2, 0)..<idx])
                        let score = (["ID", "1D", "I<", "10"].contains(pre) ? 3 : 0) + (c.allSatisfy { $0.isNumber } ? 1 : 0)
                        if score > r.docScore { r.docScore = score; r.doc = c }
                    }
                }
            }
            searchFrom = s.index(after: range.lowerBound)
        }
        return r
    }

    private static func checkDigitStr(_ s: String) -> Int { Bac.checkDigit(s) }

    /// Quét từng dòng OCR trước (tránh ghép sai qua ranh giới dòng), chỉ quét chuỗi ghép khi còn thiếu.
    public static func parse(_ ocrText: String) -> MrzResult? {
        var doc: String?; var best = -1
        var dob: String?; var doe: String?; var dscore = -1
        let joined = norm(ocrText)
        var chunks = ocrText.components(separatedBy: .newlines).map { norm($0) }
        chunks.append(joined)
        for chunk in chunks {
            if doc != nil, dob != nil, chunk == joined { break }
            let r = scan(chunk)
            if let d = r.doc, r.docScore > best { doc = d; best = r.docScore }
            if let d = r.dob, r.dateScore > dscore { dob = d; doe = r.doe; dscore = r.dateScore }
        }
        if doc == nil && dob == nil { return nil }
        let note: String
        if let doc = doc, dob != nil, doe != nil {
            _ = doc
            note = "Đã đọc đủ số giấy tờ, ngày sinh, ngày hết hạn (chữ số kiểm tra khớp)."
        } else {
            note = "Mới đọc được một phần (số giấy tờ: \(doc != nil ? "có" : "thiếu"), ngày tháng: \(dob != nil && doe != nil ? "có" : "thiếu")). Hãy chụp lại rõ hơn hoặc nhập tay phần thiếu."
        }
        return MrzResult(doc: doc, dob: dob, doe: doe, note: note)
    }
}
