package vn.edu.hcmuaf.nlu.emvreader

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ReportSection(
    val heading: String,
    val rows: List<Pair<String, String>> = emptyList(),
    val text: String = "",
    val photo: ByteArray? = null
)

class Report(
    val title: String,
    val sections: List<ReportSection>,
    val sensitive: String = SENSITIVE,
    val generated: String = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date())
) {
    fun toText(): String {
        val sb = StringBuilder()
        sb.append(title).append('\n').append("Thời điểm tạo: ").append(generated).append("\n\n")
        sb.append("!! ").append(sensitive).append("\n\n")
        for (s in sections) {
            sb.append("=".repeat(60)).append('\n').append(s.heading.uppercase()).append('\n').append("=".repeat(60)).append('\n')
            s.rows.forEach { (k, v) -> sb.append(k.padEnd(32)).append(": ").append(v).append('\n') }
            if (s.text.isNotEmpty()) sb.append('\n').append(s.text).append('\n')
            if (s.photo != null) sb.append("(có ảnh chân dung, chỉ hiển thị trong file PDF)\n")
            sb.append('\n')
        }
        return sb.toString()
    }

    companion object {
        const val SENSITIVE = "TÀI LIỆU CHỨA DỮ LIỆU CÁ NHÂN NHẠY CẢM. Chỉ dùng cho chủ thẻ hoặc khi có sự đồng ý của " +
            "chủ thẻ; bảo quản, không chia sẻ và hủy khi không còn cần thiết."
    }
}

/** Thông tin rút ra từ chip thẻ ngân hàng để dựng báo cáo. */
class EmvSummary(
    val pan: String, val expiryYymm: String?, val name: String, val label: String,
    val aid: String, val serviceCode: String, val country: String, val aids: List<String>, val log: String
)

object EmvReport {

    /** Rút thông tin từ các node TLV của một ứng dụng thẻ (log là văn bản kỹ thuật kèm theo). */
    fun summarize(nodesByAid: Map<String, List<TlvNode>>, aids: List<String>, log: String): EmvSummary? {
        for ((aid, nodes) in nodesByAid) {
            val pan5a = TlvParser.findFirst(nodes, "5A")?.value?.let { TlvParser.toHex(it).trimEnd('F', 'f') }
            val t2 = TlvParser.findFirst(nodes, "57")?.value?.let { TlvParser.toHex(it) }
            if (pan5a == null && t2 == null) continue
            var pan = pan5a.orEmpty()
            var exp: String? = null
            var service = ""
            if (t2 != null && t2.contains('D')) {
                val (p, rest) = t2.split('D', limit = 2)
                if (pan.isEmpty()) pan = p
                exp = rest.take(4)
                service = rest.drop(4).take(3)
            }
            TlvParser.findFirst(nodes, "5F24")?.value?.let { exp = TlvParser.toHex(it).take(4) }
            var name = ""
            for (tag in listOf("5F20", "9F0B")) {
                val v = TlvParser.findFirst(nodes, tag)?.value
                if (v != null && v.any { it != 0.toByte() && it != 0x20.toByte() }) { name = String(v, Charsets.US_ASCII).trim(); break }
            }
            if (name.isEmpty()) {
                TlvParser.findFirst(nodes, "56")?.value?.let {
                    val parts = String(it, Charsets.US_ASCII).split("^")
                    if (parts.size >= 3) name = parts[1].trim()
                }
            }
            val label = TlvParser.findFirst(nodes, "50")?.value?.let { String(it, Charsets.US_ASCII) }.orEmpty()
            val country = TlvParser.findFirst(nodes, "5F28")?.value?.let { TlvParser.toHex(it) }.orEmpty()
            return EmvSummary(pan, exp, name, label, aid, service, country, aids, log)
        }
        return null
    }

    fun maskPan(pan: String) = if (pan.length < 11) pan else pan.take(6) + "*".repeat(pan.length - 10) + pan.takeLast(4)

    /** Che PAN ở mọi nơi nó xuất hiện (TLV, Track2, dạng hex ASCII trong Track1). */
    fun maskInText(text: String, pan: String): String {
        if (pan.isEmpty()) return text
        val masked = maskPan(pan)
        val asciiHex = { s: String -> s.toByteArray(Charsets.US_ASCII).joinToString("") { "%02X".format(it) } }
        return text.replace(pan, masked).replace(asciiHex(pan), asciiHex(masked))
    }

    fun build(s: EmvSummary, ocr: OcrCardInfo?, fullPan: Boolean): Report {
        val pan = if (fullPan) s.pan else maskPan(s.pan)
        val exp = s.expiryYymm?.let { it.substring(2) + "/" + it.substring(0, 2) } ?: "(không có)"
        val country = if (s.country == "0704") "Việt Nam (704)" else s.country.ifEmpty { "(không có)" }
        val main = mutableListOf(
            "Loại thẻ / ứng dụng" to "${s.label} (AID ${s.aid})",
            "Số thẻ (PAN)" to pan + if (fullPan) "" else "  [đã che bớt]",
            "Hạn dùng (MM/YY)" to exp
        )
        if (ocr?.name != null) main.add("Họ tên (OCR, có dấu)" to ocr.name)
        main.add("Họ tên chủ thẻ (theo chip, không dấu)" to s.name.ifEmpty { "(chip không lưu tên)" })
        main.addAll(listOf(
            "Ngân hàng / nhà phát hành" to Banks.describe(s.pan),
            "Service code" to s.serviceCode.ifEmpty { "(không có)" },
            "Quốc gia phát hành" to country
        ))
        val sections = mutableListOf(ReportSection("Thông tin chính", main))
        if (ocr != null) {
            val v = CardMatcher.compare(ocr, ChipCardInfo(s.pan, s.expiryYymm, s.name))
            sections.add(ReportSection("Đối chiếu với mặt thẻ (OCR)", listOf("Kết luận" to v.overall),
                v.lines.joinToString("\n") { "- $it" }))
        }
        val log = if (fullPan) s.log else maskInText(s.log, s.pan)
        sections.add(ReportSection("Thông tin kèm theo (kỹ thuật)", listOf("Số ứng dụng (AID)" to s.aids.joinToString(", ")), log))
        val note = Report.SENSITIVE + if (fullPan) " Số thẻ hiển thị ĐẦY ĐỦ." else " Số thẻ đã được che bớt."
        return Report("Báo cáo đọc thẻ ngân hàng gắn chip", sections, note)
    }
}

object CccdReport {
    private val SEX = mapOf("M" to "Nam", "F" to "Nữ", "<" to "Không xác định")
    private val COUNTRY = mapOf("VNM" to "Việt Nam")
    private val DG11 = mapOf(
        "5F0E" to "Họ tên đầy đủ", "5F0F" to "Tên khác", "5F10" to "Số định danh cá nhân", "5F2B" to "Ngày sinh (đầy đủ)",
        "5F11" to "Nơi sinh", "5F42" to "Địa chỉ", "5F12" to "Số điện thoại", "5F13" to "Nghề nghiệp",
        "5F14" to "Chức danh", "5F15" to "Tóm tắt cá nhân", "5F16" to "Giấy tờ chứng minh quốc tịch",
        "5F17" to "Số khác", "5F18" to "Thông tin người giám hộ")
    private val DG12 = mapOf("5F19" to "Cơ quan cấp", "5F26" to "Ngày cấp", "5F1B" to "Ghi chú", "5F1C" to "Chi tiết khác",
        "5F55" to "Thời điểm cá nhân hóa")
    private val DATE_TAGS = setOf("5F2B", "5F26")

    /** YYMMDD sang dd/MM/yyyy. Ngày sinh có năm lớn hơn năm hiện tại được hiểu là thế kỷ 20. */
    fun fmtYymmdd(s: String, dob: Boolean, nowYear: Int = Calendar.getInstance().get(Calendar.YEAR)): String {
        if (s.length != 6 || !s.all { it.isDigit() }) return s
        val yy = s.substring(0, 2).toInt()
        val year = if (dob && yy > nowYear % 100) 1900 + yy else 2000 + yy
        return "${s.substring(4, 6)}/${s.substring(2, 4)}/$year"
    }

    private fun value(tag: String, v: ByteArray): String {
        if (tag in DATE_TAGS) {
            val h = TlvParser.toHex(v)
            if (h.length == 8 && h.all { it.isDigit() }) return "${h.substring(6, 8)}/${h.substring(4, 6)}/${h.substring(0, 4)}"
            val t = String(v, Charsets.US_ASCII)
            if (t.length == 8 && t.all { it.isDigit() }) return "${t.substring(6, 8)}/${t.substring(4, 6)}/${t.substring(0, 4)}"
        }
        val t = String(v, Charsets.UTF_8)
        val text = if (t.isNotEmpty() && t.none { it.isISOControl() } && !t.contains('\uFFFD')) t else TlvParser.toHex(v)
        return text.replace('<', ' ').trim()
    }

    private fun tagRows(body: ByteArray, map: Map<String, String>): List<Pair<String, String>> =
        TlvParser.flatten(TlvParser.parse(body))
            .filter { !it.isConstructed && it.tag in map && it.value.any { b -> b != 0.toByte() && b != 0x20.toByte() } }
            .map { map.getValue(it.tag) to value(it.tag, it.value) }

    fun build(
        fields: Map<String, String>, mrzLines: List<String>, lds: String, present: List<String>,
        extras: Map<String, Pair<String, ByteArray>>, photo: ByteArray?
    ): Report {
        val main = mutableListOf<Pair<String, String>>()
        if (fields.isNotEmpty()) {
            fun f(k: String) = fields[k].orEmpty()
            main += "Họ tên (theo MRZ, không dấu)" to f("Họ tên (MRZ, không dấu)")
            main += "Số giấy tờ" to f("Số giấy tờ")
            main += "Ngày sinh" to fmtYymmdd(f("Ngày sinh (YYMMDD)"), true)
            main += "Giới tính" to (SEX[f("Giới tính")] ?: f("Giới tính"))
            main += "Ngày hết hạn" to fmtYymmdd(f("Hết hạn (YYMMDD)"), false)
            main += "Quốc tịch" to (COUNTRY[f("Quốc tịch")] ?: f("Quốc tịch"))
            main += "Nơi cấp (quốc gia)" to (COUNTRY[f("Quốc gia cấp")] ?: f("Quốc gia cấp"))
            main += "Loại giấy tờ (mã)" to f("Loại giấy tờ")
            val opt = listOf(f("Trường tùy chọn 1"), f("Trường tùy chọn 2")).filter { it.isNotEmpty() }.joinToString(" ")
            if (opt.isNotEmpty()) main += "Trường tùy chọn trong MRZ" to opt
        }
        val sections = mutableListOf(ReportSection("Thông tin chính (DG1)",
            main.filter { it.second.isNotEmpty() }.ifEmpty { listOf("Không đọc được DG1" to "") }, photo = photo))

        val extraRows = mutableListOf<Pair<String, String>>()
        var dg13 = ""
        for ((name, pair) in extras) {
            val (dump, data) = pair
            val body = stripHead(data)
            when (name) {
                "DG11" -> extraRows += tagRows(body, DG11).map { "${it.first} (DG11)" to it.second }
                "DG12" -> extraRows += tagRows(body, DG12).map { "${it.first} (DG12)" to it.second }
                "DG13" -> dg13 = dump
            }
        }
        if (extraRows.isNotEmpty()) sections += ReportSection("Thông tin kèm theo trên chip (DG11/DG12)", extraRows)
        if (dg13.isNotEmpty()) sections += ReportSection("Dữ liệu riêng của thẻ (DG13)", text = dg13)
        else if (extras.isNotEmpty()) sections += ReportSection("Thông tin kèm theo", text = "Các file bổ sung (DG11/12/13) bị khóa hoặc không có dữ liệu.")

        sections += ReportSection("Thông tin kỹ thuật", listOf(
            "Phiên bản LDS" to lds.ifEmpty { "(không rõ)" },
            "File dữ liệu có trên thẻ" to present.joinToString(", ").ifEmpty { "(không rõ)" },
            "Xác thực truy cập" to "BAC (Basic Access Control) thành công",
            "Kênh đọc" to "NFC (ISO-DEP)",
            "Xác thực chữ ký số (SOD)" to "CHƯA thực hiện - chưa chứng minh dữ liệu là nguyên bản"
        ), if (mrzLines.isNotEmpty()) "MRZ nguyên văn:\n" + mrzLines.joinToString("\n") else "")
        return Report("Báo cáo đọc Căn cước công dân gắn chip", sections)
    }

    fun stripHead(f: ByteArray): ByteArray {
        if (f.size < 2) return ByteArray(0)
        val l = f[1].toInt() and 0xFF
        return if (l < 0x80) f.copyOfRange(2, f.size) else f.copyOfRange(2 + (l and 0x7F), f.size)
    }
}
