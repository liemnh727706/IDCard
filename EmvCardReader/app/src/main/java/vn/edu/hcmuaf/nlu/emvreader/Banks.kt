package vn.edu.hcmuaf.nlu.emvreader

import java.text.Normalizer

data class BankInfo(val bin: String, val name: String, val aliases: List<String>)

object Banks {

    // BIN 6 số của thẻ nội địa Napas (97xxxx). Danh sách tham khảo, cần cập nhật khi Napas đổi.
    private val napas: List<BankInfo> = listOf(
        BankInfo("970400", "SaigonBank", listOf("SAIGONBANK", "SGB")),
        BankInfo("970403", "Sacombank", listOf("SACOMBANK")),
        BankInfo("970405", "Agribank", listOf("AGRIBANK")),
        BankInfo("970406", "DongA Bank", listOf("DONGA BANK", "DONGABANK")),
        BankInfo("970407", "Techcombank", listOf("TECHCOMBANK")),
        BankInfo("970408", "GPBank", listOf("GPBANK")),
        BankInfo("970409", "BacABank", listOf("BACABANK", "BAC A BANK")),
        BankInfo("970412", "PVcomBank", listOf("PVCOMBANK")),
        BankInfo("970415", "VietinBank", listOf("VIETINBANK")),
        BankInfo("970416", "ACB", listOf("ACB")),
        BankInfo("970418", "BIDV", listOf("BIDV", "DAU TU VA PHAT TRIEN")),
        BankInfo("970419", "NCB", listOf("NCB", "QUOC DAN")),
        BankInfo("970422", "MB (Quân đội)", listOf("MBBANK", "MB BANK", "QUAN DOI")),
        BankInfo("970423", "TPBank", listOf("TPBANK", "TIEN PHONG")),
        BankInfo("970424", "Shinhan Bank VN", listOf("SHINHAN")),
        BankInfo("970425", "ABBank", listOf("ABBANK", "AN BINH")),
        BankInfo("970426", "MSB", listOf("MSB", "HANG HAI")),
        BankInfo("970427", "VietABank", listOf("VIETABANK")),
        BankInfo("970428", "NamABank", listOf("NAMABANK", "NAM A BANK")),
        BankInfo("970429", "SCB", listOf("SCB")),
        BankInfo("970430", "PGBank", listOf("PGBANK")),
        BankInfo("970431", "Eximbank", listOf("EXIMBANK")),
        BankInfo("970432", "VPBank", listOf("VPBANK")),
        BankInfo("970433", "VietBank", listOf("VIETBANK")),
        BankInfo("970436", "Vietcombank", listOf("VIETCOMBANK", "NGOAI THUONG")),
        BankInfo("970437", "HDBank", listOf("HDBANK")),
        BankInfo("970438", "BaoVietBank", listOf("BAOVIETBANK")),
        BankInfo("970440", "SeABank", listOf("SEABANK")),
        BankInfo("970441", "VIB", listOf("VIB", "QUOC TE")),
        BankInfo("970443", "SHB", listOf("SHB", "SAI GON HA NOI")),
        BankInfo("970444", "CBBank", listOf("CBBANK")),
        BankInfo("970446", "Co-opBank", listOf("CO-OPBANK", "COOPBANK", "HOP TAC XA")),
        BankInfo("970448", "OCB", listOf("OCB", "PHUONG DONG")),
        BankInfo("970449", "LPBank", listOf("LPBANK", "LIENVIETPOSTBANK", "BUU DIEN LIEN VIET")),
        BankInfo("970452", "KienLongBank", listOf("KIENLONGBANK")),
        BankInfo("970454", "BVBank (Bản Việt)", listOf("BVBANK", "VIETCAPITALBANK", "BAN VIET")),
        BankInfo("970457", "Woori Bank VN", listOf("WOORI")),
        BankInfo("970458", "UOB VN", listOf("UOB"))
    )

    private val byBin = napas.associateBy { it.bin }

    /** Mô tả nhà phát hành theo PAN: ngân hàng nội địa Napas, hoặc mạng thẻ quốc tế. */
    fun describe(pan: String): String {
        if (pan.length < 6) return "không xác định"
        val bin = pan.take(6)
        byBin[bin]?.let { return "${it.name} (BIN $bin, thẻ nội địa Napas)" }
        if (pan.startsWith("97")) return "thẻ nội địa Napas, BIN $bin chưa có trong bảng tra"
        return when {
            pan.startsWith("4") -> "Visa (BIN $bin), ngân hàng cụ thể chưa tra được"
            pan.take(2).toIntOrNull() in 51..55 || pan.take(4).toIntOrNull() in 2221..2720 ->
                "Mastercard (BIN $bin), ngân hàng cụ thể chưa tra được"
            pan.startsWith("35") -> "JCB (BIN $bin), ngân hàng cụ thể chưa tra được"
            pan.startsWith("62") -> "UnionPay (BIN $bin), ngân hàng cụ thể chưa tra được"
            else -> "BIN $bin chưa có trong bảng tra"
        }
    }

    fun lookup(pan: String): BankInfo? = if (pan.length >= 6) byBin[pan.take(6)] else null

    /** Ngân hàng được nhắc tên trong văn bản OCR (không phân biệt dấu/hoa thường). */
    fun mentionedIn(text: String): List<BankInfo> {
        val t = normalize(text)
        return napas.filter { b ->
            b.aliases.any { Regex("(?<![A-Z0-9])" + Regex.escape(it) + "(?![A-Z0-9])").containsMatchIn(t) }
        }
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('Đ', 'D').replace('đ', 'd')
            .uppercase()
}
