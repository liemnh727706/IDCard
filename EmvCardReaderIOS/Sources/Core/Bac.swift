import CommonCrypto
import Foundation
import Security

/// Hàm gửi một APDU thô tới thẻ và nhận phản hồi. Không phụ thuộc CoreNFC để dễ kiểm thử.
public typealias Transceiver = (Data) throws -> Data

public enum BacError: Error, CustomStringConvertible {
    case message(String)
    public var description: String {
        switch self { case .message(let m): return m }
    }
}

/// BAC + Secure Messaging theo ICAO 9303 cho CCCD/hộ chiếu điện tử.
public enum Bac {
    public static let aidEmrtd = Data(hex: "A0000002471001")

    public static func checkDigit(_ s: String) -> Int {
        let w = [7, 3, 1]
        var total = 0
        for (i, ch) in s.enumerated() {
            let v: Int
            if let d = ch.wholeNumberValue, ch.isNumber { v = d }
            else if ch.isASCII, ch.isUppercase { v = Int(ch.asciiValue! - 65) + 10 }
            else { v = 0 }
            total += v * w[i % 3]
        }
        return total % 10
    }

    public static func mrzInfo(doc: String, dob: String, doe: String) -> Data {
        let d = doc.uppercased().padding(toLength: max(9, doc.count), withPad: "<", startingAt: 0)
        let s = d + String(checkDigit(d)) + dob + String(checkDigit(dob)) + doe + String(checkDigit(doe))
        return Data(s.utf8)
    }

    private static func sha1(_ d: Data) -> Data {
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA1_DIGEST_LENGTH))
        d.withUnsafeBytes { CC_SHA1($0.baseAddress, CC_LONG(d.count), &digest) }
        return Data(digest)
    }

    private static func parity(_ k: Data) -> Data {
        Data(k.map { b -> UInt8 in
            let v = b & 0xFE
            return v.nonzeroBitCount % 2 == 0 ? v | 1 : v
        })
    }

    public static func derive(seed: Data, c: UInt8) -> Data {
        let h = sha1(seed + Data([0, 0, 0, c]))
        return parity(h.prefix(16))
    }

    private static func pad(_ d: Data) -> Data {
        var out = d
        out.append(0x80)
        while out.count % 8 != 0 { out.append(0x00) }
        return out
    }

    private static func unpad(_ d: Data) throws -> Data {
        var i = d.count
        while i > 0 && d[d.index(d.startIndex, offsetBy: i - 1)] == 0 { i -= 1 }
        guard i > 0, d[d.index(d.startIndex, offsetBy: i - 1)] == 0x80 else {
            throw BacError.message("Padding không hợp lệ")
        }
        return d.prefix(i - 1)
    }

    private static func crypt(algorithm: CCAlgorithm, key: Data, iv: Data?, data: Data, encrypt: Bool) -> Data {
        var out = [UInt8](repeating: 0, count: data.count + kCCBlockSize3DES)
        var outLen = 0
        let op = encrypt ? CCOperation(kCCEncrypt) : CCOperation(kCCDecrypt)
        let ivBytes = iv.map { [UInt8]($0) } ?? [UInt8](repeating: 0, count: 8)
        _ = key.withUnsafeBytes { kPtr in
            data.withUnsafeBytes { dPtr in
                CCCrypt(op, algorithm, CCOptions(0), kPtr.baseAddress, key.count, ivBytes,
                       dPtr.baseAddress, data.count, &out, out.count, &outLen)
            }
        }
        return Data(out.prefix(outLen))
    }

    private static func des3(key16: Data, data: Data, encrypt: Bool) -> Data {
        let key24 = key16 + key16.prefix(8)
        return crypt(algorithm: CCAlgorithm(kCCAlgorithm3DES), key: key24, iv: Data(repeating: 0, count: 8), data: data, encrypt: encrypt)
    }

    /// ISO 9797-1 MAC algorithm 3, padding method 2.
    public static func mac(key: Data, data: Data) -> Data {
        let ka = key.prefix(8)
        let kb = key.subdata(in: key.index(key.startIndex, offsetBy: 8)..<key.index(key.startIndex, offsetBy: 16))
        let d = pad(data)
        let h = crypt(algorithm: CCAlgorithm(kCCAlgorithmDES), key: ka, iv: Data(repeating: 0, count: 8), data: d, encrypt: true).suffix(8)
        let dec = crypt(algorithm: CCAlgorithm(kCCAlgorithmDES), key: kb, iv: nil, data: Data(h), encrypt: false)
        return crypt(algorithm: CCAlgorithm(kCCAlgorithmDES), key: ka, iv: nil, data: dec, encrypt: true)
    }

    private static func xor(_ a: Data, _ b: Data) -> Data {
        Data(zip(a, b).map { $0 ^ $1 })
    }

    private static func berLen(_ n: Int) -> Data {
        if n < 0x80 { return Data([UInt8(n)]) }
        if n < 0x100 { return Data([0x81, UInt8(n)]) }
        return Data([0x82, UInt8(n >> 8), UInt8(n & 0xFF)])
    }

    /// Kênh bảo mật (Secure Messaging) sau khi BAC thành công.
    public final class Session {
        private let tx: Transceiver
        private let ksEnc: Data
        private let ksMac: Data
        var ssc: Data

        init(tx: @escaping Transceiver, ksEnc: Data, ksMac: Data, ssc: Data) {
            self.tx = tx; self.ksEnc = ksEnc; self.ksMac = ksMac; self.ssc = ssc
        }

        private func inc() {
            var b = [UInt8](ssc)
            var i = b.count - 1
            while i >= 0 {
                b[i] = b[i] &+ 1
                if b[i] != 0 { break }
                i -= 1
            }
            ssc = Data(b)
        }

        /// Gửi APDU được bảo vệ. Trả về (dữ liệu giải mã, SW 2 byte).
        func send(cla: UInt8, ins: UInt8, p1: UInt8, p2: UInt8, data: Data = Data(), le: UInt8? = nil) throws -> (Data, Data) {
            let header = Data([cla | 0x0C, ins, p1, p2])
            var do87 = Data()
            var do97 = Data()
            if !data.isEmpty {
                let enc = Bac.des3(key16: ksEnc, data: Bac.pad(data), encrypt: true)
                do87 = Data([0x87]) + Bac.berLen(enc.count + 1) + Data([0x01]) + enc
            }
            if let le = le { do97 = Data([0x97, 0x01, le]) }
            inc()
            let m = Bac.mac(key: ksMac, data: ssc + Bac.pad(header) + do87 + do97)
            let body = do87 + do97 + Data([0x8E, 0x08]) + m
            let apdu = header + Data([UInt8(body.count)]) + body + Data([0x00])
            let resp = try tx(apdu)
            guard resp.count >= 2 else { throw BacError.message("Không có phản hồi") }
            var sw = resp.suffix(2)
            let payload = resp.prefix(resp.count - 2)
            if payload.isEmpty { return (Data(), Data(sw)) }
            inc()
            let bytes = [UInt8](payload)
            var i = 0
            var r87 = Data(); var r99 = Data(); var r8e = Data(); var enc87 = Data()
            while i < bytes.count {
                let tagStart = i
                let tag = bytes[i]; i += 1
                var ln = Int(bytes[i]); i += 1
                if ln == 0x81 { ln = Int(bytes[i]); i += 1 }
                else if ln == 0x82 { ln = Int(bytes[i]) << 8 | Int(bytes[i + 1]); i += 2 }
                let vStart = i
                let vEnd = i + ln
                guard vEnd <= bytes.count else { break }
                let v = Data(bytes[vStart..<vEnd])
                let rawFull = Data(bytes[tagStart..<vEnd])
                switch tag {
                case 0x87: r87 = rawFull; enc87 = v.dropFirst()
                case 0x99: r99 = rawFull; sw = v
                case 0x8E: r8e = v
                default: break
                }
                i = vEnd
            }
            guard !r8e.isEmpty, Bac.mac(key: ksMac, data: ssc + r87 + r99) == r8e else {
                throw BacError.message("Sai MAC của thẻ (Secure Messaging)")
            }
            let plain = r87.isEmpty ? Data() : try Bac.unpad(Bac.des3(key16: ksEnc, data: enc87, encrypt: false))
            return (plain, Data(sw))
        }

        private func ok(_ sw: Data) -> Bool { sw.count == 2 && sw.first == 0x90 && sw.last == 0x00 }

        func readFile(fid: UInt16, chunk: Int = 0xC0, progress: ((Int, Int) -> Void)? = nil) throws -> Data? {
            let (_, swSel) = try send(cla: 0x00, ins: 0xA4, p1: 0x02, p2: 0x0C,
                                      data: Data([UInt8(fid >> 8), UInt8(fid & 0xFF)]))
            guard ok(swSel) else { return nil }
            let (head, swHead) = try send(cla: 0x00, ins: 0xB0, p1: 0, p2: 0, le: 4)
            guard ok(swHead), head.count >= 2 else { return nil }
            let h1 = Int(head[head.index(head.startIndex, offsetBy: 1)])
            let total: Int
            if h1 < 0x80 { total = 2 + h1 }
            else if h1 == 0x81 { total = 3 + Int(head[head.index(head.startIndex, offsetBy: 2)]) }
            else { total = 4 + Int(head[head.index(head.startIndex, offsetBy: 2)]) << 8 + Int(head[head.index(head.startIndex, offsetBy: 3)]) }
            var out = head
            while out.count < total {
                let off = out.count
                if off > 0x7FFF { throw BacError.message("File lớn hơn 32KB, chưa hỗ trợ") }
                let n = min(chunk, total - off)
                let (part, sw) = try send(cla: 0x00, ins: 0xB0, p1: UInt8(off >> 8), p2: UInt8(off & 0xFF), le: UInt8(n))
                guard ok(sw), !part.isEmpty else { throw BacError.message("Lỗi đọc file tại offset \(off): SW=\(sw.hexString)") }
                out.append(part)
                progress?(out.count, total)
            }
            return out.prefix(total)
        }
    }

    public static func authenticate(tx: @escaping Transceiver, doc: String, dob: String, doe: String) throws -> Session {
        let sel = try tx(Data([0x00, 0xA4, 0x04, 0x0C, UInt8(aidEmrtd.count)]) + aidEmrtd)
        guard sel.count >= 2, sel[sel.index(sel.endIndex, offsetBy: -2)] == 0x90 else {
            throw BacError.message("Thẻ không có ứng dụng eMRTD (SW=\(sel.suffix(2).hexString))")
        }
        let kSeed = sha1(mrzInfo(doc: doc, dob: dob, doe: doe)).prefix(16)
        let rnd = try tx(Data(hex: "0084000008"))
        guard rnd.count == 10, rnd[rnd.index(rnd.startIndex, offsetBy: 8)] == 0x90 else {
            throw BacError.message("GET CHALLENGE thất bại")
        }
        let rndIc = rnd.prefix(8)
        var rndIfd = Data(count: 8); var kIfd = Data(count: 16)
        _ = rndIfd.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 8, $0.baseAddress!) }
        _ = kIfd.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        return try mutualAuth(tx: tx, kEnc: derive(seed: kSeed, c: 1), kMac: derive(seed: kSeed, c: 2),
                              rndIc: Data(rndIc), rndIfd: rndIfd, kIfd: kIfd)
    }

    static func mutualAuth(tx: @escaping Transceiver, kEnc: Data, kMac: Data, rndIc: Data, rndIfd: Data, kIfd: Data) throws -> Session {
        let eIfd = des3(key16: kEnc, data: rndIfd + rndIc + kIfd, encrypt: true)
        let mIfd = mac(key: kMac, data: eIfd)
        let resp = try tx(Data([0, 0x82, 0, 0, 0x28]) + eIfd + mIfd + Data([0x28]))
        guard resp.count == 42, resp[resp.index(resp.startIndex, offsetBy: 40)] == 0x90,
              resp[resp.index(resp.startIndex, offsetBy: 41)] == 0x00 else {
            throw BacError.message("Xác thực BAC bị từ chối (SW=\(resp.suffix(2).hexString)). Kiểm tra lại số giấy tờ, ngày sinh, ngày hết hạn.")
        }
        let eIc = resp.prefix(32)
        let mIc = resp.subdata(in: resp.index(resp.startIndex, offsetBy: 32)..<resp.index(resp.startIndex, offsetBy: 40))
        guard mac(key: kMac, data: eIc) == mIc else { throw BacError.message("Sai MAC trong phản hồi BAC") }
        let r = des3(key16: kEnc, data: eIc, encrypt: false)
        guard r.prefix(8) == rndIc, r.subdata(in: r.index(r.startIndex, offsetBy: 8)..<r.index(r.startIndex, offsetBy: 16)) == rndIfd else {
            throw BacError.message("Số ngẫu nhiên không khớp, phiên BAC không hợp lệ")
        }
        let seed = xor(kIfd, r.suffix(16))
        let ssc = rndIc.suffix(4) + rndIfd.suffix(4)
        return Session(tx: tx, ksEnc: derive(seed: seed, c: 1), ksMac: derive(seed: seed, c: 2), ssc: ssc)
    }
}

extension Data {
    init(hex: String) {
        var d = Data()
        var s = hex[...]
        while s.count >= 2 {
            let byteStr = s.prefix(2)
            d.append(UInt8(byteStr, radix: 16) ?? 0)
            s = s.dropFirst(2)
        }
        self = d
    }
    var hexString: String { map { String(format: "%02X", $0) }.joined() }
}
