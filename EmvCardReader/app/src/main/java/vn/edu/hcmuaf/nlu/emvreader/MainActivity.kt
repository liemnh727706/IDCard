package vn.edu.hcmuaf.nlu.emvreader

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            tvLog.text = ""
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            tvStatus.text = "Thiết bị này không có NFC."
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) {
            tvStatus.text = "NFC đang tắt. Vào Cài đặt để bật NFC."
            return
        }
        // A + B để bắt được cả thẻ EMV kiểu A (đa số Visa/Napas) lẫn kiểu B (một số JCB/CMND gắn chip)
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        adapter.enableReaderMode(this, this, flags, null)
        tvStatus.text = "Đang chờ NFC... Áp thẻ vào mặt sau điện thoại và giữ yên."
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            postLog("Phát hiện tag NFC nhưng không hỗ trợ ISO-DEP (không phải chip EMV/ISO7816). " +
                "UID=${tag.id.joinToString("") { "%02X".format(it) }}")
            return
        }

        try {
            isoDep.connect()
            val reader = EmvReader(isoDep)
            val result = reader.readAll()
            renderResult(result)
        } catch (e: Exception) {
            postLog("Lỗi khi đọc thẻ: ${e.message}")
        } finally {
            try { isoDep.close() } catch (_: Exception) {}
        }
    }

    private fun renderResult(result: EmvReader.EmvResult) {
        val sb = StringBuilder()
        sb.append("========== KẾT QUẢ ĐỌC THẺ ==========\n")
        sb.append("Thời điểm: ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}\n\n")

        if (result.error != null) {
            sb.append("⚠ ").append(result.error).append("\n\n")
        }

        sb.append("Số AID tìm thấy: ${result.discoveredAids.size}\n")
        result.discoveredAids.forEach { sb.append("  - $it\n") }
        sb.append("\n")

        for ((aid, nodes) in result.allTlvByAid) {
            sb.append("---- Ứng dụng AID: $aid ----\n")
            sb.append(buildSummary(nodes))
            sb.append("\n[Chi tiết TLV thô]\n")
            TlvParser.render(nodes, sb)
            sb.append("\n")
        }

        sb.append("\n[Log APDU chi tiết]\n")
        for (step in result.steps) {
            sb.append("> ${step.title}\n")
            sb.append("  CMD: ${TlvParser.toHex(step.command)}\n")
            sb.append("  RSP: ${TlvParser.toHex(step.response)}\n")
        }

        postLog(sb.toString())
    }

    private fun buildSummary(nodes: List<TlvNode>): String {
        val sb = StringBuilder()
        val pan = TlvParser.findFirst(nodes, "5A")?.value?.let { TlvParser.toHex(it) }
        val name = TlvParser.findFirst(nodes, "5F20")?.value
        val expiry = TlvParser.findFirst(nodes, "5F24")?.value?.let { TlvParser.toHex(it) }
        val label = TlvParser.findFirst(nodes, "50")?.value
        val prefName = TlvParser.findFirst(nodes, "9F12")?.value

        sb.append("  Tên ứng dụng (tag 50): ").append(bytesToText(label) ?: "(không có)").append("\n")
        sb.append("  Tên ưu tiên (tag 9F12): ").append(bytesToText(prefName) ?: "(không có)").append("\n")
        sb.append("  PAN (tag 5A): ").append(maskPan(pan) ?: "(không đọc được)").append("\n")
        sb.append("  Hạn dùng (tag 5F24, YYMMDD): ").append(expiry ?: "(không có)").append("\n")
        sb.append("  Họ tên chủ thẻ (tag 5F20): ")
            .append(bytesToText(name)?.takeIf { it.isNotBlank() } ?: "(TRỐNG - thẻ này không lưu tên trong chip)")
            .append("\n")
        return sb.toString()
    }

    private fun bytesToText(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            String(bytes, Charsets.US_ASCII).trim()
        } catch (e: Exception) {
            null
        }
    }

    /** Che bớt PAN khi hiển thị: chỉ lộ 6 số đầu + 4 số cuối, theo thông lệ PCI-DSS. */
    private fun maskPan(panHex: String?): String? {
        if (panHex == null) return null
        val digits = panHex.trimEnd('F', 'f')
        if (digits.length < 10) return digits
        val first = digits.substring(0, 6)
        val last = digits.substring(digits.length - 4)
        val maskedLen = digits.length - 10
        return first + "*".repeat(maskedLen.coerceAtLeast(0)) + last
    }

    private fun postLog(text: String) {
        runOnUiThread {
            tvLog.append(text)
            tvLog.append("\n\n")
        }
    }
}
