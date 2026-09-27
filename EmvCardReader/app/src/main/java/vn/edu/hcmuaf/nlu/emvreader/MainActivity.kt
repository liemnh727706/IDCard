package vn.edu.hcmuaf.nlu.emvreader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.graphics.BitmapFactory
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvCompare: TextView
    private var photoFile: File? = null
    private lateinit var chkCccd: CheckBox
    private lateinit var chkExtra: CheckBox
    private lateinit var chkPhoto: CheckBox
    private lateinit var etMrz1: EditText
    private lateinit var etMrz2: EditText
    private lateinit var etDoc: EditText
    private lateinit var etDob: EditText
    private lateinit var etDoe: EditText
    private lateinit var ivPhoto: ImageView
    @Volatile private var cccdMode = false

    @Volatile private var chipInfo: ChipCardInfo? = null
    @Volatile private var ocrInfo: OcrCardInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        tvCompare = findViewById(R.id.tvCompare)
        findViewById<Button>(R.id.btnOcr).setOnClickListener { captureCard() }
        chkCccd = findViewById(R.id.chkCccd)
        chkExtra = findViewById(R.id.chkExtra)
        chkPhoto = findViewById(R.id.chkPhoto)
        etMrz1 = findViewById(R.id.etMrz1)
        etMrz2 = findViewById(R.id.etMrz2)
        etDoc = findViewById(R.id.etDoc)
        etDob = findViewById(R.id.etDob)
        etDoe = findViewById(R.id.etDoe)
        ivPhoto = findViewById(R.id.ivPhoto)
        val llCccd = findViewById<View>(R.id.llCccd)
        chkCccd.setOnCheckedChangeListener { _, checked ->
            cccdMode = checked
            llCccd.visibility = if (checked) View.VISIBLE else View.GONE
            tvStatus.text = if (checked) "Chế độ CCCD: nhập khóa rồi áp CCCD vào mặt sau điện thoại."
            else "Đang chờ NFC... Áp thẻ vào mặt sau điện thoại và giữ yên."
        }
        findViewById<Button>(R.id.btnFill).setOnClickListener { fillFromMrz() }
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

        if (cccdMode) {
            readCccd(isoDep)
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

    private fun fillFromMrz() {
        val l1 = etMrz1.text.toString().replace(" ", "").uppercase()
        val l2 = etMrz2.text.toString().replace(" ", "").uppercase()
        if (l1.length >= 14) etDoc.setText(l1.substring(5, 14).replace("<", ""))
        if (l2.length >= 14) {
            etDob.setText(l2.substring(0, 6))
            etDoe.setText(l2.substring(8, 14))
        }
    }

    private fun <T> onUiSync(block: () -> T): T {
        val task = FutureTask(Callable { block() })
        runOnUiThread(task)
        return task.get()
    }

    private fun readCccd(isoDep: IsoDep) {
        val input = onUiSync {
            arrayOf(etDoc.text.toString().trim().uppercase(), etDob.text.toString().trim(),
                etDoe.text.toString().trim(), chkExtra.isChecked.toString(), chkPhoto.isChecked.toString())
        }
        val (doc, dob, doe) = input
        if (doc.isEmpty() || dob.length != 6 || doe.length != 6 || !dob.all { it.isDigit() } || !doe.all { it.isDigit() }) {
            postLog("Nhập đủ số giấy tờ, ngày sinh và ngày hết hạn (6 chữ số YYMMDD) trước khi áp thẻ.")
            return
        }
        try {
            isoDep.connect()
            isoDep.timeout = 10000
            runOnUiThread { tvStatus.text = "Đang đọc CCCD, giữ thẻ yên..."; ivPhoto.visibility = View.GONE }
            val res = CccdReader.read({ isoDep.transceive(it) }, doc, dob, doe,
                input[3].toBoolean(), input[4].toBoolean()) { p -> runOnUiThread { tvStatus.text = p } }
            postLog("========== KẾT QUẢ ĐỌC CCCD ==========\n" + res.text)
            res.photo?.takeIf { CccdReader.isJpeg(it) }?.let { bytes ->
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) runOnUiThread { ivPhoto.setImageBitmap(bmp); ivPhoto.visibility = View.VISIBLE }
            }
            runOnUiThread { tvStatus.text = "Đã đọc xong CCCD." }
        } catch (e: Exception) {
            postLog("Lỗi đọc CCCD: ${e.message}")
            runOnUiThread { tvStatus.text = "Đọc CCCD thất bại." }
        } finally {
            try { isoDep.close() } catch (_: Exception) {}
        }
    }

    private fun captureCard() {
        val dir = File(cacheDir, "images").apply { mkdirs() }
        val file = File(dir, "card.jpg")
        photoFile = file
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(intent, REQ_CAPTURE)
        } catch (e: Exception) {
            Toast.makeText(this, "Không mở được camera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val file = photoFile
        if (requestCode != REQ_CAPTURE || resultCode != RESULT_OK || file == null || !file.exists()) return
        tvCompare.text = "Đang OCR..."
        val image = InputImage.fromFilePath(this, Uri.fromFile(file))
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(image)
            .addOnSuccessListener { text ->
                ocrInfo = OcrParser.parse(text.text)
                file.delete()
                updateComparison()
            }
            .addOnFailureListener { e ->
                file.delete()
                tvCompare.text = "OCR lỗi: ${e.message}"
            }
    }

    private fun updateComparison() {
        val ocr = ocrInfo
        val chip = chipInfo
        val sb = StringBuilder()
        if (ocr != null) {
            sb.append("OCR: ${ocr.pans.size} dãy số thẻ, ${ocr.expiries.size} ngày MM/YY\n")
        } else {
            sb.append("Chưa chụp ảnh mặt thẻ (bấm nút OCR).\n")
        }
        if (chip != null) {
            sb.append("Nhà phát hành theo chip: ${Banks.describe(chip.pan)}\n")
        } else {
            sb.append("Chưa đọc chip (áp thẻ vào mặt sau điện thoại).\n")
        }
        if (ocr != null && chip != null) {
            val v = CardMatcher.compare(ocr, chip)
            v.lines.forEach { sb.append("- ").append(it).append("\n") }
            sb.append("\n").append(v.overall)
        }
        runOnUiThread { tvCompare.text = sb.toString() }
    }

    private fun renderResult(result: EmvReader.EmvResult) {
        val allNodes = result.allTlvByAid.values.flatten()
        val pan = TlvParser.findFirst(allNodes, "5A")?.value?.let { TlvParser.toHex(it).trimEnd('F', 'f') }
        val exp = TlvParser.findFirst(allNodes, "5F24")?.value?.let { TlvParser.toHex(it) }
        if (pan != null) {
            chipInfo = ChipCardInfo(pan, exp?.take(4))
            updateComparison()
        }
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

    companion object {
        private const val REQ_CAPTURE = 1001
    }
}
