package vn.edu.hcmuaf.nlu.emvreader

data class OcrCardInfo(
    val pans: List<String>,
    val expiries: List<String>,
    val rawText: String
)

data class ChipCardInfo(val pan: String, val expiryYymm: String?)

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

        return OcrCardInfo(pans.toList(), expiries.toList(), text)
    }
}

object CardMatcher {

    enum class Level { MATCH, PARTIAL, MISMATCH, MISSING }

    data class Verdict(val panLevel: Level, val expiryLevel: Level, val lines: List<String>) {
        val overall: String
            get() = when {
                panLevel == Level.MATCH && expiryLevel == Level.MATCH -> "KHỚP: số thẻ và hạn dùng in trên thẻ trùng với chip"
                panLevel == Level.MISMATCH || expiryLevel == Level.MISMATCH -> "KHÔNG KHỚP: dữ liệu in khác với chip, nghi ngờ thẻ bị chỉnh sửa hoặc OCR sai"
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

        return Verdict(panLevel, expiryLevel, lines)
    }

    fun mask(pan: String): String =
        if (pan.length < 11) pan else pan.take(6) + "*".repeat(pan.length - 10) + pan.takeLast(4)

    private fun fmt(yymm: String) = yymm.substring(2) + "/" + yymm.substring(0, 2)

    private fun samePrefixSuffix(a: String, b: String): Boolean =
        a.length >= 10 && b.length >= 10 && a.take(6) == b.take(6) && a.takeLast(4) == b.takeLast(4)
}
