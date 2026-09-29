package vn.edu.hcmuaf.nlu.emvreader

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.print.PrintManager
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
import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream

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
    @Volatile private var lastEmv: EmvSummary? = null
    @Volatile private var lastCccd: Report? = null
    @Volatile private var lastKind = ""
    private lateinit var chkFullPan: CheckBox
    private var pendingSave: Report? = null

    @Volatile private var chipInfo: ChipCardInfo? = null
    @Volatile private var ocrInfo: OcrCardInfo? = null

    // ---- Kiểm tra chính danh (đối chiếu khuôn mặt qua FaceID service tự host) ----
    private lateinit var etFaceUrl: EditText
    private lateinit var etFaceKey: EditText
    private lateinit var ivRefPhoto: ImageView
    private lateinit var ivLivePhoto: ImageView
    private lateinit var tvFaceResult: TextView
    @Volatile private var refPhotoBytes: ByteArray? = null
    @Volatile private var livePhotoBytes: ByteArray? = null

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
        findViewById<Button>(R.id.btnMrzOcr).setOnClickListener { captureCard(REQ_MRZ) }
        chkFullPan = findViewById(R.id.chkFullPan)
        findViewById<Button>(R.id.btnExport).setOnClickListener { withReport { exportPdf(it) } }
        findViewById<Button>(R.id.btnPrint).setOnClickListener { withReport { printReport(it) } }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            tvLog.text = ""
        }

        etFaceUrl = findViewById(R.id.etFaceUrl)
        etFaceKey = findViewById(R.id.etFaceKey)
        ivRefPhoto = findViewById(R.id.ivRefPhoto)
        ivLivePhoto = findViewById(R.id.ivLivePhoto)
        tvFaceResult = findViewById(R.id.tvFaceResult)
        val facePrefs = getSharedPreferences("faceid", MODE_PRIVATE)
        etFaceUrl.setText(facePrefs.getString("url", DEFAULT_FACE_URL))
        etFaceKey.setText(facePrefs.getString("key", BuildConfig.FACEID_API_KEY))
        findViewById<Button>(R.id.btnCaptureRef).setOnClickListener { captureCard(REQ_CAPTURE_REF) }
        findViewById<Button>(R.id.btnCaptureLive).setOnClickListener { captureCard(REQ_CAPTURE_LIVE) }
        findViewById<Button>(R.id.btnVerifyFace).setOnClickListener { verifyFace() }

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

    /** Báo cáo của lần đọc gần nhất (CCCD hoặc thẻ ngân hàng). */
    private fun currentReport(): Report? = when (lastKind) {
        "cccd" -> lastCccd
        "emv" -> lastEmv?.let { EmvReport.build(it, ocrInfo, chkFullPan.isChecked) }
        else -> null
    }

    private fun withReport(action: (Report) -> Unit) {
        val report = currentReport()
        if (report == null) {
            Toast.makeText(this, "Hãy đọc thẻ trước khi xuất báo cáo.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Dữ liệu cá nhân nhạy cảm")
            .setMessage("Báo cáo chứa dữ liệu cá nhân của chủ thẻ. Chỉ tiếp tục nếu đây là thẻ của bạn hoặc chủ thẻ đã đồng ý; hãy bảo quản file và bản in cẩn thận.")
            .setPositiveButton("Tiếp tục") { _, _ -> action(report) }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun exportPdf(report: Report) {
        pendingSave = report
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val prefix = if (lastKind == "cccd") "CCCD" else "TheNganHang"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_TITLE, "${prefix}_$ts.pdf")
        try {
            startActivityForResult(intent, REQ_SAVE)
        } catch (e: Exception) {
            Toast.makeText(this, "Không mở được hộp thoại lưu file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveReportTo(resultCode: Int, data: Intent?) {
        val report = pendingSave
        pendingSave = null
        val uri = data?.data
        if (resultCode != RESULT_OK || report == null || uri == null) return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(PdfExporter.render(report)) }
            Toast.makeText(this, "Đã lưu báo cáo PDF.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi khi lưu PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun printReport(report: Report) {
        val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val name = if (lastKind == "cccd") "Bao cao CCCD" else "Bao cao the ngan hang"
        pm.print(name, ReportPrintAdapter(report, name), null)
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
            lastCccd = res.report
            lastKind = "cccd"
            postLog("========== KẾT QUẢ ĐỌC CCCD ==========\n" + res.text)
            res.photo?.takeIf { CccdReader.isJpeg(it) }?.let { bytes ->
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) runOnUiThread {
                    ivPhoto.setImageBitmap(bmp); ivPhoto.visibility = View.VISIBLE
                    refPhotoBytes = bytes
                    ivRefPhoto.setImageBitmap(bmp)
                    tvFaceResult.text = "Đã tự điền ảnh chân dung trên thẻ từ DG2. Chụp thêm ảnh live rồi bấm Kiểm tra chính danh."
                }
            }
            runOnUiThread { tvStatus.text = "Đã đọc xong CCCD." }
        } catch (e: Exception) {
            postLog("Lỗi đọc CCCD: ${e.message}")
            runOnUiThread { tvStatus.text = "Đọc CCCD thất bại." }
        } finally {
            try { isoDep.close() } catch (_: Exception) {}
        }
    }

    private fun captureCard(req: Int = REQ_CAPTURE) {
        val dir = File(cacheDir, "images").apply { mkdirs() }
        val file = File(dir, "card.jpg")
        photoFile = file
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(intent, req)
        } catch (e: Exception) {
            Toast.makeText(this, "Không mở được camera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAVE) {
            saveReportTo(resultCode, data)
            return
        }
        if (requestCode == REQ_CAPTURE_REF || requestCode == REQ_CAPTURE_LIVE) {
            val f = photoFile
            if (resultCode == RESULT_OK && f != null && f.exists()) {
                val bytes = downscaleJpeg(f)
                f.delete()
                if (requestCode == REQ_CAPTURE_REF) {
                    refPhotoBytes = bytes
                    ivRefPhoto.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                } else {
                    livePhotoBytes = bytes
                    ivLivePhoto.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                }
                tvFaceResult.text = ""
            }
            return
        }
        val file = photoFile
        if ((requestCode != REQ_CAPTURE && requestCode != REQ_MRZ) || resultCode != RESULT_OK || file == null || !file.exists()) return
        val forMrz = requestCode == REQ_MRZ
        if (forMrz) tvStatus.text = "Đang OCR dòng MRZ..." else tvCompare.text = "Đang OCR..."
        val image = InputImage.fromFilePath(this, Uri.fromFile(file))
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(image)
            .addOnSuccessListener { text ->
                file.delete()
                if (forMrz) {
                    applyMrz(Mrz.parse(text.text))
                } else {
                    ocrInfo = OcrParser.parse(text.text)
                    updateComparison()
                }
            }
            .addOnFailureListener { e ->
                file.delete()
                if (forMrz) tvStatus.text = "OCR lỗi: ${e.message}" else tvCompare.text = "OCR lỗi: ${e.message}"
            }
    }

    private fun applyMrz(r: MrzResult?) {
        if (r == null) {
            tvStatus.text = "Không nhận ra dòng MRZ. Chụp sát, đủ sáng, thấy rõ 3 dòng chữ dưới đáy mặt sau thẻ."
            return
        }
        r.doc?.let { etDoc.setText(it) }
        r.dob?.let { etDob.setText(it) }
        r.doe?.let { etDoe.setText(it) }
        tvStatus.text = r.note + " Kiểm tra lại các ô rồi áp thẻ để đọc."
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

        lastEmv = EmvReport.summarize(result.allTlvByAid, result.discoveredAids, sb.toString())
        if (lastEmv != null) lastKind = "emv"
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

    /** Nén và giới hạn kích thước ảnh chụp (camera thường ra ảnh vài MB) trước khi gửi đi. */
    private fun downscaleJpeg(file: File, maxDim: Int = 1024): ByteArray {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > maxDim * 2 || opts.outHeight / sample > maxDim * 2) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return file.readBytes()
        val scale = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val baos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        return baos.toByteArray()
    }

    private fun verifyFace() {
        val url = etFaceUrl.text.toString().trim()
        val key = etFaceKey.text.toString().trim()
        val ref = refPhotoBytes
        val live = livePhotoBytes
        if (url.isEmpty()) {
            Toast.makeText(this, "Nhập địa chỉ FaceID service.", Toast.LENGTH_LONG).show()
            return
        }
        if (ref == null || live == null) {
            Toast.makeText(this, "Cần cả ảnh chân dung trên thẻ và ảnh chụp live.", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences("faceid", MODE_PRIVATE).edit()
            .putString("url", url).putString("key", key).apply()

        AlertDialog.Builder(this)
            .setTitle("Dữ liệu sinh trắc học nhạy cảm")
            .setMessage("Ảnh khuôn mặt sẽ được gửi tới FaceID service bạn tự host tại '$url' qua HTTP thường (không mã hóa). " +
                "Chỉ thực hiện trên mạng bạn tin cậy (Wi-Fi riêng, không phải Wi-Fi công cộng), và chỉ khi có sự đồng ý của người được chụp ảnh.")
            .setPositiveButton("Tiếp tục") { _, _ -> doVerifyFace(url, key, ref, live) }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun doVerifyFace(url: String, key: String, ref: ByteArray, live: ByteArray) {
        tvFaceResult.text = "Đang gửi ảnh và so khớp..."
        tvFaceResult.setTextColor(Color.DKGRAY)
        Thread {
            try {
                val r = FaceIdClient.verify(url, key, ref, live)
                runOnUiThread {
                    val pct = "%.1f".format(r.similarity * 100)
                    tvFaceResult.text = "${r.label} (độ giống $pct%, ngưỡng khớp ${(r.matchThreshold * 100).toInt()}%, " +
                        "ngưỡng loại ${(r.rejectThreshold * 100).toInt()}%)"
                    tvFaceResult.setTextColor(when (r.decision) {
                        "match" -> Color.rgb(0, 140, 0)
                        "no_match" -> Color.RED
                        else -> Color.rgb(200, 120, 0)
                    })
                }
            } catch (e: FaceIdException) {
                runOnUiThread { tvFaceResult.text = e.message; tvFaceResult.setTextColor(Color.RED) }
            } catch (e: Exception) {
                runOnUiThread { tvFaceResult.text = "Lỗi: ${e.message}"; tvFaceResult.setTextColor(Color.RED) }
            }
        }.start()
    }

    private fun postLog(text: String) {
        runOnUiThread {
            tvLog.append(text)
            tvLog.append("\n\n")
        }
    }

    companion object {
        private const val REQ_CAPTURE = 1001
        private const val REQ_MRZ = 1002
        private const val REQ_SAVE = 1003
        private const val REQ_CAPTURE_REF = 1004
        private const val REQ_CAPTURE_LIVE = 1005
        private const val DEFAULT_FACE_URL = "https://cropnlu.duckdns.org/faceid"
    }
}
