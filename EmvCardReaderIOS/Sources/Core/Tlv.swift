import Foundation

public struct TlvNode {
    public let tag: String
    public let value: Data
    public let depth: Int
    public let children: [TlvNode]
    public var isConstructed: Bool { !children.isEmpty }
}

public let emvTagNames: [String: String] = [
    "4F": "AID (Application Identifier)", "50": "Application Label", "56": "Track1 Data",
    "57": "Track2 Equivalent Data", "5A": "PAN (số thẻ)", "5F20": "Cardholder Name (Họ tên chủ thẻ)",
    "5F24": "Application Expiration Date (YYMMDD)", "5F25": "Application Effective Date (YYMMDD)",
    "5F28": "Issuer Country Code", "5F30": "Service Code", "5F34": "PAN Sequence Number",
    "9F1F": "Track1 Discretionary Data", "9F0B": "Cardholder Name Extended",
    "82": "Application Interchange Profile (AIP)", "94": "Application File Locator (AFL)",
    "9F38": "PDOL", "88": "Short File Identifier (SFI)", "84": "DF Name / AID",
    "A5": "FCI Proprietary Template", "6F": "FCI Template", "BF0C": "FCI Issuer Discretionary Data",
    "61": "Application Template", "70": "Application Elementary File (Record)",
    "77": "Response Message Template Format 2", "80": "Response Message Template Format 1",
    "9F12": "Application Preferred Name",
]

public enum TlvParser {
    public static func parse(_ data: Data, depth: Int = 0) -> [TlvNode] {
        var nodes: [TlvNode] = []
        let bytes = [UInt8](data)
        var offset = 0
        while offset < bytes.count {
            if bytes[offset] == 0x00 || bytes[offset] == 0xFF { offset += 1; continue }
            let tagStart = offset
            let first = bytes[offset]
            offset += 1
            if first & 0x1F == 0x1F {
                repeat {
                    if offset >= bytes.count { return nodes }
                    offset += 1
                } while bytes[offset - 1] & 0x80 == 0x80
            }
            let tagHex = bytes[tagStart..<offset].map { String(format: "%02X", $0) }.joined()
            if offset >= bytes.count { break }
            var lengthByte = Int(bytes[offset]); offset += 1
            var length: Int
            if lengthByte & 0x80 == 0x80 {
                let n = lengthByte & 0x7F
                if offset + n > bytes.count { break }
                length = 0
                for _ in 0..<n { length = (length << 8) | Int(bytes[offset]); offset += 1 }
            } else { length = lengthByte }
            _ = lengthByte
            if offset + length > bytes.count { length = bytes.count - offset }
            let value = Data(bytes[offset..<offset + length])
            offset += length
            let isConstructed = first & 0x20 == 0x20
            let children = isConstructed ? parse(value, depth: depth + 1) : []
            nodes.append(TlvNode(tag: tagHex, value: value, depth: depth, children: children))
        }
        return nodes
    }

    public static func flatten(_ nodes: [TlvNode]) -> [TlvNode] {
        var out: [TlvNode] = []
        for n in nodes {
            out.append(n)
            if !n.children.isEmpty { out.append(contentsOf: flatten(n.children)) }
        }
        return out
    }

    public static func findAll(_ nodes: [TlvNode], _ tag: String) -> [TlvNode] {
        flatten(nodes).filter { $0.tag.uppercased() == tag.uppercased() }
    }

    public static func findFirst(_ nodes: [TlvNode], _ tag: String) -> TlvNode? {
        findAll(nodes, tag).first
    }

    public static func render(_ nodes: [TlvNode], into sb: inout String) {
        for n in nodes {
            let indent = String(repeating: "  ", count: n.depth)
            sb += indent + n.tag
            if let name = emvTagNames[n.tag.uppercased()] { sb += " (\(name))" }
            sb += " len=\(n.value.count)"
            if !n.isConstructed {
                sb += " = " + n.value.hexString
                if let s = asciiIfPrintable(n.value) { sb += "  \"\(s)\"" }
            }
            sb += "\n"
            if !n.children.isEmpty { render(n.children, into: &sb) }
        }
    }

    private static func asciiIfPrintable(_ d: Data) -> String? {
        guard !d.isEmpty, d.allSatisfy({ $0 >= 0x20 && $0 <= 0x7E }) else { return nil }
        return String(bytes: d, encoding: .ascii)
    }
}
