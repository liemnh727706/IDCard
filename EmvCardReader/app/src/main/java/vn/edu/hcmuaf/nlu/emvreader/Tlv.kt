package vn.edu.hcmuaf.nlu.emvreader

/** Một node BER-TLV: tag dạng hex string, và value thô. */
data class TlvNode(
    val tag: String,
    val value: ByteArray,
    val depth: Int,
    val children: List<TlvNode> = emptyList()
) {
    val isConstructed: Boolean
        get() = children.isNotEmpty()
}

/** Tên gợi nhớ cho các tag EMV thường gặp, chỉ để hiển thị cho dễ đọc. */
val EMV_TAG_NAMES: Map<String, String> = mapOf(
    "4F" to "AID (Application Identifier)",
    "50" to "Application Label",
    "57" to "Track2 Equivalent Data",
    "5A" to "PAN (số thẻ)",
    "5F20" to "Cardholder Name (Họ tên chủ thẻ)",
    "5F24" to "Application Expiration Date (YYMMDD)",
    "5F25" to "Application Effective Date (YYMMDD)",
    "5F28" to "Issuer Country Code",
    "5F30" to "Service Code",
    "5F34" to "PAN Sequence Number",
    "9F1F" to "Track1 Discretionary Data",
    "9F0B" to "Cardholder Name Extended",
    "82" to "Application Interchange Profile (AIP)",
    "94" to "Application File Locator (AFL)",
    "9F38" to "PDOL (Processing Options Data Object List)",
    "88" to "Short File Identifier (SFI)",
    "84" to "DF Name / AID (trong FCI)",
    "A5" to "FCI Proprietary Template",
    "6F" to "FCI Template",
    "BF0C" to "FCI Issuer Discretionary Data",
    "61" to "Application Template (PPSE entry)",
    "4F".lowercase() to "AID",
    "70" to "Application Elementary File (Record)",
    "77" to "Response Message Template Format 2",
    "80" to "Response Message Template Format 1",
    "9F12" to "Application Preferred Name",
    "9F11" to "Issuer Code Table Index",
    "5F50" to "Issuer URL",
    "9F42" to "Application Currency Code",
    "9F44" to "Application Currency Exponent"
)

object TlvParser {

    /**
     * Parse một chuỗi byte BER-TLV, đệ quy vào các tag "constructed"
     * (bit 0x20 của byte tag đầu tiên được bật).
     */
    fun parse(data: ByteArray, depth: Int = 0): List<TlvNode> {
        val nodes = mutableListOf<TlvNode>()
        var offset = 0
        while (offset < data.size) {
            // bỏ qua byte đệm 0x00 / 0xFF giữa các TLV (thường gặp cuối record)
            if (data[offset] == 0x00.toByte() || data[offset] == 0xFF.toByte()) {
                offset++
                continue
            }

            val tagStart = offset
            val firstByte = data[offset].toInt() and 0xFF
            offset++
            if ((firstByte and 0x1F) == 0x1F) {
                // tag nhiều byte: các byte tiếp theo có bit 0x80 set trừ byte cuối
                do {
                    if (offset >= data.size) return nodes
                    offset++
                } while ((data[offset - 1].toInt() and 0x80) == 0x80)
            }
            val tagBytes = data.copyOfRange(tagStart, offset)
            val tagHex = tagBytes.joinToString("") { "%02X".format(it) }

            if (offset >= data.size) break
            var lengthByte = data[offset].toInt() and 0xFF
            offset++
            var length: Int
            if (lengthByte and 0x80 == 0x80) {
                val numLenBytes = lengthByte and 0x7F
                if (offset + numLenBytes > data.size) break
                length = 0
                repeat(numLenBytes) {
                    length = (length shl 8) or (data[offset].toInt() and 0xFF)
                    offset++
                }
            } else {
                length = lengthByte
            }

            if (offset + length > data.size) {
                // dữ liệu bị cắt/hỏng, lấy phần còn lại để tránh crash
                length = data.size - offset
            }
            val value = data.copyOfRange(offset, offset + length)
            offset += length

            val isConstructed = (firstByte and 0x20) == 0x20
            val children = if (isConstructed) parse(value, depth + 1) else emptyList()

            nodes.add(TlvNode(tagHex, value, depth, children))
        }
        return nodes
    }

    /** Duyệt phẳng toàn bộ cây TLV (bao gồm cả node cha lẫn con). */
    fun flatten(nodes: List<TlvNode>): List<TlvNode> {
        val result = mutableListOf<TlvNode>()
        for (n in nodes) {
            result.add(n)
            if (n.children.isNotEmpty()) result.addAll(flatten(n.children))
        }
        return result
    }

    /** Tìm tất cả node có tag khớp (không phân biệt hoa/thường) trong toàn cây. */
    fun findAll(nodes: List<TlvNode>, tag: String): List<TlvNode> =
        flatten(nodes).filter { it.tag.equals(tag, ignoreCase = true) }

    fun findFirst(nodes: List<TlvNode>, tag: String): TlvNode? =
        findAll(nodes, tag).firstOrNull()

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    /** In cây TLV ra dạng text thụt lề, kèm tên tag nếu biết. */
    fun render(nodes: List<TlvNode>, sb: StringBuilder) {
        for (n in nodes) {
            val indent = "  ".repeat(n.depth)
            val name = EMV_TAG_NAMES[n.tag.uppercase()]
            sb.append(indent).append(n.tag)
            if (name != null) sb.append(" (").append(name).append(")")
            sb.append(" len=").append(n.value.size)
            if (!n.isConstructed) {
                sb.append(" = ").append(toHex(n.value))
                asciiIfPrintable(n.value)?.let { sb.append("  \"").append(it).append("\"") }
            }
            sb.append("\n")
            if (n.children.isNotEmpty()) render(n.children, sb)
        }
    }

    private fun asciiIfPrintable(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val chars = bytes.map { it.toInt() and 0xFF }
        if (chars.any { it < 0x20 || it > 0x7E }) return null
        return chars.map { it.toChar() }.joinToString("")
    }
}
