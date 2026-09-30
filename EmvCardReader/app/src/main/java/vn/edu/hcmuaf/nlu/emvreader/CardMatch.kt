package vn.edu.hcmuaf.nlu.emvreader

import java.text.Normalizer

data class OcrCardInfo(
    val pans: List<String>,
    val expiries: List<String>,
    val rawText: String,
    val name: String? = null
)

data class ChipCardInfo(val pan: String, val expiryYymm: String?, val name: String = "")

/** Bỏ dấu tiếng Việt (NFD rồi loại ký tự combining), dùng để so khớp tên chip (không dấu) với
 * tên OCR (có dấu). Dùng chung cho cả đối chiếu thẻ ngân hàng và báo cáo kết hợp. */
fun stripVietnameseDiacritics(s: String): String {
    val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    return n.replace('Đ', 'D').replace('đ', 'd')
}

fun namesMatch(chipNameNoDiacritics: String, ocrNameWithDiacritics: String): Boolean {
    val a = stripVietnameseDiacritics(chipNameNoDiacritics).uppercase().trim().split(Regex("\\s+"))
    val b = stripVietnameseDiacritics(ocrNameWithDiacritics).uppercase().trim().split(Regex("\\s+"))
    return a.sorted() == b.sorted()
}

// Nhãn thường in trước họ tên trên thẻ sinh viên/CCCD. So khớp trên bản ĐÃ BỎ DẤU của dòng OCR
// (không phụ thuộc OCR có nhận đúng dấu ở phần nhãn hay không), rồi cắt lấy phần còn lại của
// DÒNG GỐC (có dấu) từ đúng vị trí đó - bỏ dấu bằng NFD không đổi số ký tự nên vị trí khớp 1-1.
private val NAME_LABEL_RE = Regex("(?:ho\\s*va\\s*ten|ho\\s*ten|full\\s*name|name)\\s*[:\\-]?\\s*", RegexOption.IGNORE_CASE)

/** Tìm dòng chứa họ tên có dấu trên ảnh OCR (thẻ sinh viên/CCCD), theo nhãn "Họ và tên"/"Full name". */
fun extractOcrName(text: String): String? {
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val m = NAME_LABEL_RE.find(stripVietnameseDiacritics(line)) ?: continue
        var candidate = line.substring(m.range.last + 1).trim()
        candidate = candidate.replace(Regex("[^A-Za-zÀ-ỹ\\s]"), " ").trim().replace(Regex("\\s{2,}"), " ")
        if (candidate.isEmpty()) continue
        val words = candidate.split(" ")
        if (words.size in 2..6 && words.all { it.isNotEmpty() && it[0].isLetter() }) return candidate
    }
    return null
}

object OcrParser {

    fun parse(text: String): OcrCardInfo {
        val pans = linkedSetOf<String>()
        val expiries = linkedSetOf<String>()

        for (line in text.lines()) {
            val digitLike = line.count { it.isDigit() || it in "OoIlSB" }
            if (digitLike >= 12) {
                val fixed = line.map {
                    when (it) {
                        'O', 'o' -> '0'
                        'I', 'l' -> '1'
                        'S' -> '5'
                        'B' -> '8'
                        else -> it
                    }
                }.joinToString("")
                Regex("(?:\\d[ \\-]?){13,19}").findAll(fixed).forEach { m ->
                    val digits = m.value.filter { it.isDigit() }
                    if (digits.length in 13..19) pans.add(digits)
                }
            }
        }

        Regex("(0[1-9]|1[0-2])\\s*[/\\-]\\s*(\\d{2})(?!\\d)").findAll(text).forEach { m ->
            expiries.add(m.groupValues[2] + m.groupValues[1]) // YYMM
        }

        return OcrCardInfo(pans.toList(), expiries.toList(), text, extractOcrName(text))
    }
}

object CardMatcher {

    enum class Level { MATCH, PARTIAL, MISMATCH, MISSING }

    data class Verdict(val panLevel: Level, val expiryLevel: Level, val bankLevel: Level, val nameLevel: Level, val lines: List<String>) {
        val overall: String
            get() = when {
                panLevel == Level.MISMATCH || expiryLevel == Level.MISMATCH || bankLevel == Level.MISMATCH || nameLevel == Level.MISMATCH ->
                    "KHÔNG KHỚP: dữ liệu in khác với chip, nghi ngờ thẻ bị chỉnh sửa hoặc OCR sai"
                panLevel == Level.MATCH && expiryLevel == Level.MATCH -> {
                    val base = "KHỚP: số thẻ và hạn dùng in trên thẻ trùng với chip"
                    if (nameLevel == Level.MATCH) "$base, cả họ tên" else base
                }
                else -> "CHƯA KẾT LUẬN: OCR chưa đủ dữ liệu, thử chụp lại rõ hơn"
            }
    }

    fun compare(ocr: OcrCardInfo, chip: ChipCardInfo): Verdict {
        val lines = mutableListOf<String>()

        val panLevel = when {
            ocr.pans.isEmpty() -> {
                lines.add("Số thẻ in: OCR không tìm thấy dãy số 13-19 chữ số")
                Level.MISSING
            }
            ocr.pans.any { it == chip.pan } -> {
                lines.add("Số thẻ in: ${mask(ocr.pans.first { it == chip.pan })} = chip (khớp toàn bộ)")
                Level.MATCH
            }
            ocr.pans.any { samePrefixSuffix(it, chip.pan) } -> {
                val p = ocr.pans.first { samePrefixSuffix(it, chip.pan) }
                lines.add("Số thẻ in: ${mask(p)} khớp 6 số đầu + 4 số cuối nhưng khác độ dài/giữa (${p.length} vs ${chip.pan.length} số)")
                Level.PARTIAL
            }
            else -> {
                lines.add("Số thẻ in: ${ocr.pans.joinToString { mask(it) }} khác chip ${mask(chip.pan)}")
                Level.MISMATCH
            }
        }

        val expiryLevel = when {
            chip.expiryYymm == null -> {
                lines.add("Hạn dùng chip: không có")
                Level.MISSING
            }
            ocr.expiries.isEmpty() -> {
                lines.add("Hạn dùng in: OCR không tìm thấy dạng MM/YY")
                Level.MISSING
            }
            chip.expiryYymm in ocr.expiries -> {
                lines.add("Hạn dùng in: ${fmt(chip.expiryYymm)} = chip (khớp)")
                Level.MATCH
            }
            else -> {
                lines.add("Hạn dùng in: ${ocr.expiries.joinToString { fmt(it) }} khác chip ${fmt(chip.expiryYymm)}")
                Level.MISMATCH
            }
        }

        val chipBank = Banks.lookup(chip.pan)
        val mentioned = Banks.mentionedIn(ocr.rawText)
        val bankLevel = when {
            chipBank == null -> {
                lines.add("Ngân hàng: BIN ${chip.pan.take(6)} chưa có trong bảng tra, không so khớp được")
                Level.MISSING
            }
            mentioned.any { it.bin == chipBank.bin } -> {
                lines.add("Ngân hàng: chip là ${chipBank.name}, tên in trên thẻ cũng là ${chipBank.name} (khớp)")
                Level.MATCH
            }
            mentioned.isNotEmpty() -> {
                lines.add("Ngân hàng: chip là ${chipBank.name} nhưng thẻ in tên ${mentioned.joinToString { it.name }}")
                Level.MISMATCH
            }
            else -> {
                lines.add("Ngân hàng: chip là ${chipBank.name}, OCR không thấy tên ngân hàng in trên thẻ (có thể chỉ có logo)")
                Level.MISSING
            }
        }

        val nameLevel = when {
            chip.name.isBlank() -> {
                lines.add("Họ tên: chip không lưu tên, không so khớp được")
                Level.MISSING
            }
            ocr.name != null -> {
                if (namesMatch(chip.name, ocr.name)) {
                    lines.add("Họ tên: OCR đọc \"${ocr.name}\" (có dấu), khớp với tên chip \"${chip.name}\" (không dấu)")
                    Level.MATCH
                } else {
                    lines.add("Họ tên: OCR đọc \"${ocr.name}\", KHÔNG khớp với tên chip \"${chip.name}\"")
                    Level.MISMATCH
                }
            }
            else -> {
                val toks = stripVietnameseDiacritics(chip.name).uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val printed = stripVietnameseDiacritics(ocr.rawText).uppercase()
                val hit = toks.filter { Regex("(?<![A-Z])$it(?![A-Z])").containsMatchIn(printed) }
                when {
                    toks.isNotEmpty() && hit.size == toks.size -> {
                        lines.add("Họ tên: tên trong chip xuất hiện đủ trên mặt thẻ (khớp, chưa xác định được dạng có dấu)")
                        Level.MATCH
                    }
                    hit.isNotEmpty() -> {
                        lines.add("Họ tên: chỉ thấy ${hit.size}/${toks.size} từ của tên chip trên mặt thẻ")
                        Level.PARTIAL
                    }
                    else -> {
                        lines.add("Họ tên: tên trong chip không thấy trên mặt thẻ (hoặc OCR chưa đọc được tên)")
                        Level.MISMATCH
                    }
                }
            }
        }

        return Verdict(panLevel, expiryLevel, bankLevel, nameLevel, lines)
    }

    fun mask(pan: String): String =
        if (pan.length < 11) pan else pan.take(6) + "*".repeat(pan.length - 10) + pan.takeLast(4)

    private fun fmt(yymm: String) = yymm.substring(2) + "/" + yymm.substring(0, 2)

    private fun samePrefixSuffix(a: String, b: String): Boolean =
        a.length >= 10 && b.length >= 10 && a.take(6) == b.take(6) && a.takeLast(4) == b.takeLast(4)
}
