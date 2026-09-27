import Foundation

public struct BankInfo { public let bin: String; public let name: String; public let aliases: [String] }

public enum Banks {
    public static let napas: [BankInfo] = [
        BankInfo(bin: "970400", name: "SaigonBank", aliases: ["SAIGONBANK", "SGB"]),
        BankInfo(bin: "970403", name: "Sacombank", aliases: ["SACOMBANK"]),
        BankInfo(bin: "970405", name: "Agribank", aliases: ["AGRIBANK"]),
        BankInfo(bin: "970406", name: "DongA Bank", aliases: ["DONGA BANK", "DONGABANK"]),
        BankInfo(bin: "970407", name: "Techcombank", aliases: ["TECHCOMBANK"]),
        BankInfo(bin: "970408", name: "GPBank", aliases: ["GPBANK"]),
        BankInfo(bin: "970409", name: "BacABank", aliases: ["BACABANK", "BAC A BANK"]),
        BankInfo(bin: "970412", name: "PVcomBank", aliases: ["PVCOMBANK"]),
        BankInfo(bin: "970415", name: "VietinBank", aliases: ["VIETINBANK"]),
        BankInfo(bin: "970416", name: "ACB", aliases: ["ACB"]),
        BankInfo(bin: "970418", name: "BIDV", aliases: ["BIDV", "DAU TU VA PHAT TRIEN"]),
        BankInfo(bin: "970419", name: "NCB", aliases: ["NCB", "QUOC DAN"]),
        BankInfo(bin: "970422", name: "MB (Quân đội)", aliases: ["MBBANK", "MB BANK", "QUAN DOI"]),
        BankInfo(bin: "970423", name: "TPBank", aliases: ["TPBANK", "TIEN PHONG"]),
        BankInfo(bin: "970424", name: "Shinhan Bank VN", aliases: ["SHINHAN"]),
        BankInfo(bin: "970425", name: "ABBank", aliases: ["ABBANK", "AN BINH"]),
        BankInfo(bin: "970426", name: "MSB", aliases: ["MSB", "HANG HAI"]),
        BankInfo(bin: "970427", name: "VietABank", aliases: ["VIETABANK"]),
        BankInfo(bin: "970428", name: "NamABank", aliases: ["NAMABANK", "NAM A BANK"]),
        BankInfo(bin: "970429", name: "SCB", aliases: ["SCB"]),
        BankInfo(bin: "970430", name: "PGBank", aliases: ["PGBANK"]),
        BankInfo(bin: "970431", name: "Eximbank", aliases: ["EXIMBANK"]),
        BankInfo(bin: "970432", name: "VPBank", aliases: ["VPBANK"]),
        BankInfo(bin: "970433", name: "VietBank", aliases: ["VIETBANK"]),
        BankInfo(bin: "970436", name: "Vietcombank", aliases: ["VIETCOMBANK", "NGOAI THUONG"]),
        BankInfo(bin: "970437", name: "HDBank", aliases: ["HDBANK"]),
        BankInfo(bin: "970438", name: "BaoVietBank", aliases: ["BAOVIETBANK"]),
        BankInfo(bin: "970440", name: "SeABank", aliases: ["SEABANK"]),
        BankInfo(bin: "970441", name: "VIB", aliases: ["VIB", "QUOC TE"]),
        BankInfo(bin: "970443", name: "SHB", aliases: ["SHB", "SAI GON HA NOI"]),
        BankInfo(bin: "970444", name: "CBBank", aliases: ["CBBANK"]),
        BankInfo(bin: "970446", name: "Co-opBank", aliases: ["CO-OPBANK", "COOPBANK", "HOP TAC XA"]),
        BankInfo(bin: "970448", name: "OCB", aliases: ["OCB", "PHUONG DONG"]),
        BankInfo(bin: "970449", name: "LPBank", aliases: ["LPBANK", "LIENVIETPOSTBANK", "BUU DIEN LIEN VIET"]),
        BankInfo(bin: "970452", name: "KienLongBank", aliases: ["KIENLONGBANK"]),
        BankInfo(bin: "970454", name: "BVBank (Bản Việt)", aliases: ["BVBANK", "VIETCAPITALBANK", "BAN VIET"]),
        BankInfo(bin: "970457", name: "Woori Bank VN", aliases: ["WOORI"]),
        BankInfo(bin: "970458", name: "UOB VN", aliases: ["UOB"]),
    ]

    private static let byBin: [String: BankInfo] = Dictionary(uniqueKeysWithValues: napas.map { ($0.bin, $0) })

    public static func describe(_ pan: String) -> String {
        guard pan.count >= 6 else { return "khong xac dinh" }
        let bin = String(pan.prefix(6))
        if let b = byBin[bin] { return "\(b.name) (BIN \(bin), the noi dia Napas)" }
        if pan.hasPrefix("97") { return "the noi dia Napas, BIN \(bin) chua co trong bang tra" }
        let two = Int(pan.prefix(2)) ?? 0
        let four = Int(pan.prefix(4)) ?? 0
        if pan.hasPrefix("4") { return "Visa (BIN \(bin)), chua tra duoc ngan hang" }
        if (51...55).contains(two) || (2221...2720).contains(four) { return "Mastercard (BIN \(bin)), chua tra duoc ngan hang" }
        if pan.hasPrefix("35") { return "JCB (BIN \(bin)), chua tra duoc ngan hang" }
        if pan.hasPrefix("62") { return "UnionPay (BIN \(bin)), chua tra duoc ngan hang" }
        return "BIN \(bin) chua co trong bang tra"
    }

    public static func lookup(_ pan: String) -> BankInfo? { pan.count >= 6 ? byBin[String(pan.prefix(6))] : nil }

    private static func normalize(_ s: String) -> String {
        let folded = s.folding(options: .diacriticInsensitive, locale: Locale(identifier: "vi_VN"))
        return folded.replacingOccurrences(of: "\u{0110}", with: "D")
                     .replacingOccurrences(of: "\u{0111}", with: "d")
                     .uppercased()
    }

    public static func mentionedIn(_ text: String) -> [BankInfo] {
        let t = normalize(text)
        return napas.filter { b in
            b.aliases.contains { alias in
                guard let re = try? NSRegularExpression(pattern: "(?<![A-Z0-9])" + NSRegularExpression.escapedPattern(for: alias) + "(?![A-Z0-9])") else { return false }
                return re.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) != nil
            }
        }
    }
}
