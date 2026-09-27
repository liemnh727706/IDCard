package vn.edu.hcmuaf.nlu.emvreader

class CccdResult(val text: String, val photo: ByteArray?)

/** Đọc CCCD gắn chip (eMRTD) qua NFC: BAC, rồi DG1 và tùy chọn DG11/12/13, DG2. */
object CccdReader {

    private val FILES = mapOf("COM" to 0x011E, "DG1" to 0x0101, "DG2" to 0x0102,
        "DG11" to 0x010B, "DG12" to 0x010C, "DG13" to 0x010D)
    private val DG_NAMES = mapOf(0x61 to "DG1", 0x75 to "DG2", 0x6B to "DG11", 0x6C to "DG12",
        0x6D to "DG13", 0x6E to "DG14", 0x6F to "DG15", 0x77 to "SOD")

    fun read(tx: Transceiver, doc: String, dob: String, doe: String, extra: Boolean, photo: Boolean,
             progress: (String) -> Unit): CccdResult {
        val candidates = listOf(doc) + if (doc.length > 9) listOf(doc.takeLast(9)) else emptyList()
        var session: Bac.Session? = null
        var last: BacException? = null
        for (c in candidates) {
            progress("Xác thực BAC...")
            try {
                session = Bac.authenticate(tx, c, dob, doe)
                break
            } catch (e: BacException) {
                last = e
            }
        }
        val s = session ?: throw last!!

        val sb = StringBuilder("Xác thực BAC thành công.\n\n")
        val present = mutableListOf<String>()
        s.readFile(FILES.getValue("COM"))?.let { com ->
            for (n in TlvParser.parse(stripHead(com))) {
                if (n.tag == "5C") n.value.forEach { present.add(DG_NAMES[it.toInt() and 0xFF] ?: "%02X".format(it)) }
                if (n.tag == "5F01") sb.append("Phiên bản LDS: ").append(String(n.value, Charsets.US_ASCII)).append('\n')
            }
            sb.append("Các file dữ liệu có trên thẻ: ").append(present.joinToString(", ").ifEmpty { "(không rõ)" }).append("\n\n")
        }

        val dg1 = s.readFile(FILES.getValue("DG1"))
        if (dg1 != null) sb.append(formatDg1(dg1)) else sb.append("Không đọc được DG1.\n")

        if (extra) {
            for (name in listOf("DG11", "DG12", "DG13")) {
                if (present.isNotEmpty() && name !in present) continue
                progress("Đang đọc $name...")
                try {
                    val d = s.readFile(FILES.getValue(name))
                    if (d != null) {
                        sb.append("\n=== $name (${d.size} byte) ===\n")
                        dumpTlv(TlvParser.parse(stripHead(d)), 0, sb)
                    } else if (name in present) sb.append("\n=== $name: bị khóa hoặc không đọc được ===\n")
                } catch (e: BacException) {
                    sb.append("\n=== $name: lỗi ${e.message} ===\n")
                }
            }
        }

        var photoBytes: ByteArray? = null
        if (photo) {
            progress("Đang đọc ảnh chân dung DG2...")
            val dg2 = s.readFile(FILES.getValue("DG2")) { a, b -> progress("DG2: $a/$b byte") }
            photoBytes = dg2?.let { findImage(it) }
            sb.append("\n=== DG2: ảnh chân dung ===\n")
            sb.append(when {
                dg2 == null -> "không đọc được DG2\n"
                photoBytes == null -> "${dg2.size} byte, không tìm thấy ảnh JPEG/JPEG2000\n"
                else -> "${dg2.size} byte, ảnh ${if (isJpeg(photoBytes)) "JPEG" else "JPEG2000 (Android không hiển thị được)"}\n"
            })
        }

        sb.append("\nLưu ý: chưa xác thực chữ ký số (SOD) nên chưa chứng minh dữ liệu là nguyên bản.\n")
        sb.append("Dữ liệu chỉ hiển thị trên màn hình này, không được lưu hay gửi đi.\n")
        return CccdResult(sb.toString(), photoBytes)
    }

    private fun stripHead(f: ByteArray): ByteArray {
        if (f.size < 2) return ByteArray(0)
        val l = f[1].toInt() and 0xFF
        return if (l < 0x80) f.copyOfRange(2, f.size) else f.copyOfRange(2 + (l and 0x7F), f.size)
    }

    private fun formatDg1(dg1: ByteArray): String {
        var mrz = ""
        for (n in TlvParser.parse(stripHead(dg1))) {
            if (n.tag == "5F1F") mrz = String(n.value, Charsets.US_ASCII)
            for (c in n.children) if (c.tag == "5F1F") mrz = String(c.value, Charsets.US_ASCII)
        }
        val sb = StringBuilder("=== DG1: thông tin MRZ ===\n")
        if (mrz.length == 90) {
            val l1 = mrz.substring(0, 30); val l2 = mrz.substring(30, 60); val l3 = mrz.substring(60, 90)
            sb.append(l1).append('\n').append(l2).append('\n').append(l3).append("\n\n")
            fun f(k: String, v: String) = sb.append("%-24s: %s\n".format(k, v))
            f("Loại giấy tờ", l1.substring(0, 2).replace("<", ""))
            f("Quốc gia cấp", l1.substring(2, 5))
            f("Số giấy tờ", l1.substring(5, 14).replace("<", ""))
            f("Trường tùy chọn 1", l1.substring(15, 30).replace("<", ""))
            f("Ngày sinh (YYMMDD)", l2.substring(0, 6))
            f("Giới tính", l2.substring(7, 8))
            f("Hết hạn (YYMMDD)", l2.substring(8, 14))
            f("Quốc tịch", l2.substring(15, 18))
            f("Trường tùy chọn 2", l2.substring(18, 29).replace("<", ""))
            f("Họ tên (MRZ, không dấu)", l3.replace("<<", "|").replace("<", " ").split("|").filter { it.isNotBlank() }.joinToString(" ").trim())
        } else {
            sb.append(mrz).append('\n')
        }
        return sb.toString()
    }

    private fun dumpTlv(nodes: List<TlvNode>, depth: Int, sb: StringBuilder) {
        for (n in nodes) {
            val pad = "  ".repeat(depth)
            if (n.isConstructed) {
                sb.append(pad).append(n.tag).append(":\n")
                dumpTlv(n.children, depth + 1, sb)
            } else {
                sb.append(pad).append(n.tag).append(" = ").append(asText(n.value)).append('\n')
            }
        }
    }

    private fun asText(v: ByteArray): String {
        val t = String(v, Charsets.UTF_8)
        return if (t.isNotEmpty() && t.none { it.isISOControl() && it != '\n' } && !t.contains('�')) t else TlvParser.toHex(v)
    }

    private fun findImage(d: ByteArray): ByteArray? {
        val sigs = listOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
            byteArrayOf(0, 0, 0, 0x0C, 'j'.code.toByte(), 'P'.code.toByte(), 0x20, 0x20),
            byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51))
        for (sig in sigs) {
            for (i in 0..d.size - sig.size) {
                var match = true
                for (k in sig.indices) if (d[i + k] != sig[k]) { match = false; break }
                if (match) return d.copyOfRange(i, d.size)
            }
        }
        return null
    }

    fun isJpeg(b: ByteArray) = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
}
