package vn.edu.hcmuaf.nlu.emvreader

import android.nfc.tech.IsoDep
import java.io.IOException

/**
 * Đọc dữ liệu thẻ chip EMV qua contactless (NFC/IsoDep).
 *
 * LƯU Ý PHÁP LÝ/BẢO MẬT:
 * - Chỉ dùng để tự kiểm tra thẻ CỦA CHÍNH BẠN, mục đích nghiên cứu/kiểm thử nội bộ.
 * - Không lưu, không truyền PAN/dữ liệu thẻ ra ngoài thiết bị dưới bất kỳ hình thức nào.
 * - Không phải mọi thẻ đều lộ tên chủ thẻ (tag 5F20) qua chip - nhiều ngân hàng để trống.
 */
class EmvReader(private val isoDep: IsoDep) {

    data class StepLog(val title: String, val command: ByteArray, val response: ByteArray)

    class EmvResult {
        val steps = mutableListOf<StepLog>()
        val discoveredAids = mutableListOf<String>()
        val allTlvByAid = linkedMapOf<String, MutableList<TlvNode>>()
        var error: String? = null
    }

    private val PPSE = hexToBytes("325041592E5359532E4444463031") // "2PAY.SYS.DDF01"

    // Vài AID phổ biến dùng làm fallback nếu không lấy được danh sách qua PPSE.
    private val FALLBACK_AIDS = listOf(
        "A0000007270101" to "NAPAS (đoán - domestic debit)",
        "A0000007271010" to "NAPAS (đoán - biến thể)",
        "A0000000031010" to "Visa Debit/Credit",
        "A0000000041010" to "Mastercard",
        "A0000000651010" to "JCB"
    )

    fun readAll(): EmvResult {
        val result = EmvResult()
        isoDep.timeout = 5000

        val ppseResp = transceiveLogged(result, "SELECT PPSE (2PAY.SYS.DDF01)", buildSelectByName(PPSE))
        var aids = mutableListOf<String>()

        if (isSuccess(ppseResp)) {
            val tlv = TlvParser.parse(stripSw(ppseResp))
            TlvParser.findAll(tlv, "4F").forEach { node ->
                aids.add(TlvParser.toHex(node.value))
            }
        }

        if (aids.isEmpty()) {
            result.error = "Không lấy được danh sách AID qua PPSE (thẻ có thể là contact-only, " +
                "hoặc không hỗ trợ PPSE). Thử fallback với danh sách AID phổ biến."
            aids = FALLBACK_AIDS.map { it.first }.toMutableList()
        }

        result.discoveredAids.addAll(aids)

        for (aid in aids) {
            try {
                readApplication(result, aid)
            } catch (e: IOException) {
                result.allTlvByAid.getOrPut(aid) { mutableListOf() }
                result.steps.add(StepLog("Lỗi khi đọc AID $aid", ByteArray(0), e.message.orEmpty().toByteArray()))
            }
        }

        return result
    }

    private fun readApplication(result: EmvResult, aidHex: String) {
        val selectResp = transceiveLogged(result, "SELECT AID $aidHex", buildSelectByName(hexToBytes(aidHex)))
        if (!isSuccess(selectResp)) return

        val fciTlv = TlvParser.parse(stripSw(selectResp))
        val nodes = result.allTlvByAid.getOrPut(aidHex) { mutableListOf() }
        nodes.addAll(fciTlv)

        val pdolNode = TlvParser.findFirst(fciTlv, "9F38")
        val gpoData = buildGpoCommandData(pdolNode?.value)
        val gpoResp = transceiveLogged(result, "GET PROCESSING OPTIONS ($aidHex)", buildGpo(gpoData))
        if (!isSuccess(gpoResp)) return

        val gpoPayload = stripSw(gpoResp)
        val (aip, afl) = parseGpoResponse(gpoPayload) ?: return
        nodes.addAll(TlvParser.parse(gpoPayload))

        if (afl.isEmpty()) return

        var i = 0
        while (i + 4 <= afl.size) {
            val sfi = (afl[i].toInt() and 0xFF) shr 3
            val firstRec = afl[i + 1].toInt() and 0xFF
            val lastRec = afl[i + 2].toInt() and 0xFF
            i += 4

            for (rec in firstRec..lastRec) {
                val p2 = (sfi shl 3) or 0x04
                val readResp = transceiveLogged(
                    result,
                    "READ RECORD sfi=$sfi rec=$rec ($aidHex)",
                    byteArrayOf(0x00, 0xB2.toByte(), rec.toByte(), p2.toByte(), 0x00)
                )
                if (isSuccess(readResp)) {
                    nodes.addAll(TlvParser.parse(stripSw(readResp)))
                }
            }
        }
    }

    // ---- Xây dựng APDU ----

    private fun buildSelectByName(name: ByteArray): ByteArray {
        return byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, name.size.toByte()) + name + byteArrayOf(0x00)
    }

    private fun buildGpo(dataField: ByteArray): ByteArray {
        return byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, dataField.size.toByte()) + dataField + byteArrayOf(0x00)
    }

    /** PDOL là một Data Object List (chuỗi tag+length, KHÔNG có value). Điền value = 0x00. */
    private fun buildGpoCommandData(pdol: ByteArray?): ByteArray {
        val dol = if (pdol != null) buildZeroFilledDol(pdol) else ByteArray(0)
        return byteArrayOf(0x83.toByte(), dol.size.toByte()) + dol
    }

    private fun buildZeroFilledDol(dol: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        var offset = 0
        while (offset < dol.size) {
            val tagStart = offset
            val first = dol[offset].toInt() and 0xFF
            offset++
            if ((first and 0x1F) == 0x1F) {
                do {
                    if (offset >= dol.size) break
                    offset++
                } while (offset <= dol.size && (dol[offset - 1].toInt() and 0x80) == 0x80)
            }
            if (offset >= dol.size) break
            val len = dol[offset].toInt() and 0xFF
            offset++
            repeat(len) { out.add(0x00) }
        }
        return out.toByteArray()
    }

    /** Trả về (AIP, AFL) từ response của GPO, hỗ trợ cả format1 (tag 80) và format2 (tag 77). */
    private fun parseGpoResponse(data: ByteArray): Pair<ByteArray, ByteArray>? {
        if (data.isEmpty()) return null
        val firstTag = data[0].toInt() and 0xFF
        return when (firstTag) {
            0x80 -> {
                // 80 LEN AIP(2) AFL(...)
                if (data.size < 4) return null
                val len = data[1].toInt() and 0xFF
                val payload = data.copyOfRange(2, minOf(2 + len, data.size))
                if (payload.size < 2) return null
                val aip = payload.copyOfRange(0, 2)
                val afl = payload.copyOfRange(2, payload.size)
                aip to afl
            }
            0x77 -> {
                val tlv = TlvParser.parse(data)
                val aip = TlvParser.findFirst(tlv, "82")?.value ?: return null
                val afl = TlvParser.findFirst(tlv, "94")?.value ?: ByteArray(0)
                aip to afl
            }
            else -> null
        }
    }

    // ---- Tiện ích ----

    private fun transceiveLogged(result: EmvResult, title: String, command: ByteArray): ByteArray {
        val resp = try {
            isoDep.transceive(command)
        } catch (e: IOException) {
            val err = "IOException: ${e.message}".toByteArray()
            result.steps.add(StepLog(title, command, err))
            return ByteArray(0)
        }
        result.steps.add(StepLog(title, command, resp))
        return resp
    }

    private fun isSuccess(resp: ByteArray): Boolean {
        if (resp.size < 2) return false
        return resp[resp.size - 2] == 0x90.toByte() && resp[resp.size - 1] == 0x00.toByte()
    }

    private fun stripSw(resp: ByteArray): ByteArray {
        if (resp.size < 2) return ByteArray(0)
        return resp.copyOfRange(0, resp.size - 2)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
