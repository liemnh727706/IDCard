import Foundation

public struct CccdResult {
    public let text: String
    public let photo: Data?
    public let report: Report
}

/// Đọc CCCD gắn chip (eMRTD) qua NFC: BAC, rồi DG1 và tùy chọn DG11/12/13, DG2.
public enum CccdReader {
    private static let files: [String: UInt16] = ["COM": 0x011E, "DG1": 0x0101, "DG2": 0x0102,
                                                   "DG11": 0x010B, "DG12": 0x010C, "DG13": 0x010D]
    private static let dgNames: [UInt8: String] = [0x61: "DG1", 0x75: "DG2", 0x6B: "DG11", 0x6C: "DG12",
                                                   0x6D: "DG13", 0x6E: "DG14", 0x6F: "DG15", 0x77: "SOD"]

    public static func isJpeg(_ d: Data) -> Bool { d.count > 3 && d[d.startIndex] == 0xFF && d[d.index(after: d.startIndex)] == 0xD8 }

    public static func stripHead(_ f: Data) -> Data {
        guard f.count >= 2 else { return Data() }
        let l = Int(f[f.index(f.startIndex, offsetBy: 1)])
        return l < 0x80 ? f.suffix(from: f.index(f.startIndex, offsetBy: 2))
                        : f.suffix(from: f.index(f.startIndex, offsetBy: 2 + (l & 0x7F)))
    }

    private static func findImage(_ d: Data) -> Data? {
        let sigs: [[UInt8]] = [[0xFF, 0xD8, 0xFF], [0, 0, 0, 0x0C, 0x6A, 0x50, 0x20, 0x20], [0xFF, 0x4F, 0xFF, 0x51]]
        let b = [UInt8](d)
        for sig in sigs {
            if sig.count > b.count { continue }
            for i in 0...(b.count - sig.count) {
                if Array(b[i..<i + sig.count]) == sig { return Data(b[i...]) }
            }
        }
        return nil
    }

    private static func formatDg1(_ dg1: Data) -> (text: String, fields: [String: String], mrzLines: [String]) {
        var mrz = ""
        for n in TlvParser.parse(stripHead(dg1)) {
            if n.tag == "5F1F" { mrz = String(data: n.value, encoding: .ascii) ?? "" }
            for c in n.children where c.tag == "5F1F" { mrz = String(data: c.value, encoding: .ascii) ?? "" }
        }
        var sb = "=== DG1: thong tin MRZ ===\n"
        var fields: [String: String] = [:]
        var lines: [String] = []
        if mrz.count == 90 {
            let chars = Array(mrz)
            let l1 = String(chars[0..<30]); let l2 = String(chars[30..<60]); let l3 = String(chars[60..<90])
            lines = [l1, l2, l3]
            sb += l1 + "\n" + l2 + "\n" + l3 + "\n\n"
            func f(_ k: String, _ v: String) {
                fields[k] = v
                sb += k.padding(toLength: max(24, k.count), withPad: " ", startingAt: 0) + ": " + v + "\n"
            }
            let c1 = Array(l1); let c2 = Array(l2)
            f("Loai giay to", String(c1[0..<2]).replacingOccurrences(of: "<", with: ""))
            f("Quoc gia cap", String(c1[2..<5]))
            f("So giay to", String(c1[5..<14]).replacingOccurrences(of: "<", with: ""))
            f("Truong tuy chon 1", String(c1[15..<30]).replacingOccurrences(of: "<", with: ""))
            f("Ngay sinh (YYMMDD)", String(c2[0..<6]))
            f("Gioi tinh", String(c2[7..<8]))
            f("Het han (YYMMDD)", String(c2[8..<14]))
            f("Quoc tich", String(c2[15..<18]))
            f("Truong tuy chon 2", String(c2[18..<29]).replacingOccurrences(of: "<", with: ""))
            let name = l3.replacingOccurrences(of: "<<", with: "|").replacingOccurrences(of: "<", with: " ")
                .components(separatedBy: "|").filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
                .joined(separator: " ").trimmingCharacters(in: .whitespaces)
            f("Ho ten (MRZ, khong dau)", name)
        } else {
            sb += mrz + "\n"
        }
        return (sb, fields, lines)
    }

    private static func dumpTlv(_ nodes: [TlvNode], depth: Int, into sb: inout String) {
        for n in nodes {
            let pad = String(repeating: "  ", count: depth)
            if n.isConstructed { sb += pad + n.tag + ":\n"; dumpTlv(n.children, depth: depth + 1, into: &sb) }
            else { sb += pad + n.tag + " = " + asText(n.value) + "\n" }
        }
    }

    private static func asText(_ v: Data) -> String {
        if let t = String(data: v, encoding: .utf8), !t.isEmpty, t.unicodeScalars.allSatisfy({ !($0.value < 0x20) || $0 == "\n" }) {
            return t
        }
        return v.hexString
    }

    public static func read(tx: @escaping Transceiver, doc: String, dob: String, doe: String,
                            extra: Bool, photo: Bool, progress: @escaping (String) -> Void) throws -> CccdResult {
        let candidates = [doc] + (doc.count > 9 ? [String(doc.suffix(9))] : [])
        var session: Bac.Session?
        var lastErr: Error?
        for c in candidates {
            progress("Xac thuc BAC...")
            do { session = try Bac.authenticate(tx: tx, doc: c, dob: dob, doe: doe); break }
            catch { lastErr = error }
        }
        guard let s = session else { throw lastErr ?? BacError.message("Khong xac thuc duoc") }

        var sb = "Xac thuc BAC thanh cong.\n\n"
        var present: [String] = []
        var lds = ""
        if let com = try s.readFile(fid: files["COM"]!) {
            for n in TlvParser.parse(stripHead(com)) {
                if n.tag == "5C" { present = [UInt8](n.value).map { dgNames[$0] ?? String(format: "%02X", $0) } }
                if n.tag == "5F01" { lds = String(data: n.value, encoding: .ascii) ?? ""; sb += "Phien ban LDS: \(lds)\n" }
            }
            sb += "Cac file du lieu co tren the: \((present.isEmpty ? "(khong ro)" : present.joined(separator: ", ")))\n\n"
        }

        var fields: [String: String] = [:]
        var mrzLines: [String] = []
        if let dg1 = try s.readFile(fid: files["DG1"]!) {
            let (text, f, lines) = formatDg1(dg1)
            sb += text; fields = f; mrzLines = lines
        } else { sb += "Khong doc duoc DG1.\n" }

        var extras: [String: (String, Data)] = [:]
        if extra {
            for name in ["DG11", "DG12", "DG13"] {
                if !present.isEmpty && !present.contains(name) { continue }
                progress("Dang doc \(name)...")
                do {
                    if let d = try s.readFile(fid: files[name]!) {
                        var dump = ""
                        dumpTlv(TlvParser.parse(stripHead(d)), depth: 0, into: &dump)
                        extras[name] = (dump, d)
                        sb += "\n=== \(name) (\(d.count) byte) ===\n" + dump
                    } else if present.contains(name) { sb += "\n=== \(name): bi khoa hoac khong doc duoc ===\n" }
                } catch { sb += "\n=== \(name): loi \(error) ===\n" }
            }
        }

        var photoBytes: Data?
        if photo {
            progress("Dang doc anh chan dung DG2...")
            let dg2 = try s.readFile(fid: files["DG2"]!) { a, b in progress("DG2: \(a)/\(b) byte") }
            photoBytes = dg2.flatMap { findImage($0) }
            sb += "\n=== DG2: anh chan dung ===\n"
            if dg2 == nil { sb += "khong doc duoc DG2\n" }
            else if let p = photoBytes { sb += "\(dg2!.count) byte, anh \(isJpeg(p) ? "JPEG" : "JPEG2000 (chua hien thi duoc)")\n" }
            else { sb += "\(dg2!.count) byte, khong tim thay anh JPEG/JPEG2000\n" }
        }

        sb += "\nLuu y: chua xac thuc chu ky so (SOD) nen chua chung minh du lieu la nguyen ban.\n"
        sb += "Du lieu chi hien thi tren man hinh nay, khong duoc luu hay gui di.\n"
        let report = CccdReport.build(fields: fields, mrzLines: mrzLines, lds: lds, present: present, extras: extras, photo: photoBytes)
        return CccdResult(text: sb, photo: photoBytes, report: report)
    }
}
