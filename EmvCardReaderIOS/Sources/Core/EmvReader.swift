import Foundation

/// Đọc dữ liệu thẻ chip EMV qua contactless (NFC). Chỉ dùng để tự kiểm tra thẻ của chính bạn.
public enum EmvReader {
    private static let ppse = Data("2PAY.SYS.DDF01".utf8)
    private static let fallbackAids = [
        "A0000007270010", "A0000007271010", "A0000007270101",
        "A0000000031010", "A0000000041010", "A0000000651010", "A000000333010101",
    ]

    public struct StepLog { public let title: String; public let command: Data; public let response: Data }

    public final class Result {
        public var steps: [StepLog] = []
        public var discoveredAids: [String] = []
        public var nodesByAid: [String: [TlvNode]] = [:]
        public var error: String?
    }

    private static func ok(_ r: Data) -> Bool { r.count >= 2 && r[r.index(r.endIndex, offsetBy: -2)] == 0x90 && r.last == 0x00 }
    private static func body(_ r: Data) -> Data { r.count >= 2 ? r.prefix(r.count - 2) : Data() }
    private static func selectByName(_ name: Data) -> Data { Data([0x00, 0xA4, 0x04, 0x00, UInt8(name.count)]) + name + Data([0x00]) }

    private static func zeroDol(_ dol: Data) -> Data {
        var out = Data()
        let b = [UInt8](dol)
        var i = 0
        while i < b.count {
            let first = b[i]; i += 1
            if first & 0x1F == 0x1F {
                while i < b.count { let v = b[i]; i += 1; if v & 0x80 != 0x80 { break } }
            }
            guard i < b.count else { break }
            let len = Int(b[i]); i += 1
            out.append(Data(repeating: 0, count: len))
        }
        return out
    }

    private static func parseGpoResponse(_ data: Data) -> (Data, Data)? {
        guard let first = data.first else { return nil }
        if first == 0x80 {
            guard data.count >= 4 else { return nil }
            let len = Int(data[data.index(data.startIndex, offsetBy: 1)])
            let payload = data.subdata(in: data.index(data.startIndex, offsetBy: 2)..<data.index(data.startIndex, offsetBy: min(2 + len, data.count)))
            guard payload.count >= 2 else { return nil }
            return (payload.prefix(2), payload.suffix(payload.count - 2))
        }
        if first == 0x77 {
            let tlv = TlvParser.parse(data)
            guard let aip = TlvParser.findFirst(tlv, "82")?.value else { return nil }
            let afl = TlvParser.findFirst(tlv, "94")?.value ?? Data()
            return (aip, afl)
        }
        return nil
    }

    public static func readAll(tx: @escaping Transceiver) -> Result {
        let r = Result()

        func run(_ title: String, _ apdu: Data) -> Data {
            let resp = (try? tx(apdu)) ?? Data("LOI".utf8)
            r.steps.append(StepLog(title: title, command: apdu, response: resp))
            return resp
        }

        var aids: [String] = []
        func addAid(_ h: String) { if !h.isEmpty, !aids.contains(h) { aids.append(h) } }

        let ppseResp = run("SELECT PPSE (2PAY.SYS.DDF01)", selectByName(ppse))
        if ok(ppseResp) {
            for n in TlvParser.findAll(TlvParser.parse(body(ppseResp)), "4F") { addAid(n.value.hexString) }
        }
        if aids.isEmpty {
            r.error = "Không lấy được danh sách AID qua PPSE (thẻ có thể là contact-only, hoặc không hỗ trợ PPSE). Thử fallback với danh sách AID phổ biến."
            aids = fallbackAids
        }
        r.discoveredAids = aids

        for aid in aids {
            let selectResp = run("SELECT AID \(aid)", selectByName(Data(hex: aid)))
            guard ok(selectResp) else { continue }
            var nodes = TlvParser.parse(body(selectResp))

            let fciTlv = nodes
            let pdolNode = TlvParser.findFirst(fciTlv, "9F38")
            let dol = pdolNode != nil ? zeroDol(pdolNode!.value) : Data()
            let cmdData = Data([0x83, UInt8(dol.count)]) + dol
            let gpoResp = run("GET PROCESSING OPTIONS (\(aid))",
                              Data([0x80, 0xA8, 0x00, 0x00, UInt8(cmdData.count)]) + cmdData + Data([0x00]))
            guard ok(gpoResp), let (_, afl) = parseGpoResponse(body(gpoResp)) else {
                r.nodesByAid[aid] = nodes
                continue
            }
            nodes += TlvParser.parse(body(gpoResp))

            let aflBytes = [UInt8](afl)
            var k = 0
            while k + 4 <= aflBytes.count {
                let sfi = Int(aflBytes[k]) >> 3
                let first = Int(aflBytes[k + 1]); let last = Int(aflBytes[k + 2])
                k += 4
                guard first <= last, last - first < 32 else { continue }
                for rec in first...last {
                    let p2 = UInt8((sfi << 3) | 0x04)
                    let readResp = run("READ RECORD sfi=\(sfi) rec=\(rec) (\(aid))",
                                       Data([0x00, 0xB2, UInt8(rec), p2, 0x00]))
                    if ok(readResp) { nodes += TlvParser.parse(body(readResp)) }
                }
            }
            r.nodesByAid[aid] = nodes
        }
        return r
    }
}
