package vn.edu.hcmuaf.nlu.emvreader

/** Gộp thông tin đã đọc/xác thực từ thẻ ngân hàng (SV), CCCD và FaceID thành một báo cáo. */
object CombinedReport {

    fun build(
        emv: EmvSummary?, ocr: OcrCardInfo?, fullPan: Boolean, ocrImageBytes: ByteArray?,
        cccd: Report?, cccdPhotoBytes: ByteArray?,
        faceResult: FaceVerifyResult?, refPhotoBytes: ByteArray?, livePhotoBytes: ByteArray?
    ): Report? {
        if (emv == null && cccd == null) return null

        val sections = mutableListOf<ReportSection>()
        val namesFound = mutableListOf<Pair<String, String>>() // (nguồn, tên)

        ocr?.name?.let { namesFound.add("OCR ảnh thẻ (có dấu)" to it) }
        if (!emv?.name.isNullOrBlank()) namesFound.add("Chip thẻ ngân hàng/SV (không dấu)" to emv!!.name)
        val cccdName = cccd?.sections?.flatMap { it.rows }
            ?.firstOrNull { it.first == "Họ tên (theo MRZ, không dấu)" }?.second
        if (!cccdName.isNullOrBlank()) namesFound.add("Chip CCCD - MRZ (không dấu)" to cccdName)

        val identityRows = namesFound.map { (src, name) -> "Họ tên - $src" to name }
        val noDiacriticNames = namesFound.filter { it.first.contains("không dấu") }.map { it.second }
        val crossLines = mutableListOf<String>()
        if (noDiacriticNames.size >= 2) {
            val base = stripVietnameseDiacritics(noDiacriticNames[0]).uppercase().split(Regex("\\s+"))
            val allSame = noDiacriticNames.drop(1).all {
                stripVietnameseDiacritics(it).uppercase().split(Regex("\\s+")) == base
            }
            crossLines.add("Tên trên chip thẻ ngân hàng/SV và chip CCCD: " +
                if (allSame) "KHỚP nhau" else "KHÔNG khớp nhau - kiểm tra lại có đúng cùng một người không")
        }
        val ocrName = ocr?.name
        if (ocrName != null && noDiacriticNames.isNotEmpty()) {
            val ok = noDiacriticNames.any { namesMatch(it, ocrName) }
            crossLines.add("Tên OCR có dấu \"$ocrName\" so với tên trên chip: " +
                if (ok) "KHỚP" else "KHÔNG khớp hoặc chưa xác nhận được")
        }
        sections.add(ReportSection("Họ tên - đối chiếu nhiều nguồn", identityRows,
            crossLines.joinToString("\n") { "- $it" }))

        if (emv != null) {
            val pan = if (fullPan) emv.pan else EmvReport.maskPan(emv.pan)
            val exp = emv.expiryYymm?.let { it.substring(2) + "/" + it.substring(0, 2) } ?: "(không có)"
            sections.add(ReportSection("Thẻ ngân hàng / thẻ sinh viên", listOf(
                "Loại thẻ / ứng dụng" to "${emv.label} (AID ${emv.aid})",
                "Số thẻ (PAN)" to pan + if (fullPan) "" else "  [đã che bớt]",
                "Hạn dùng (MM/YY)" to exp,
                "Ngân hàng / nhà phát hành" to Banks.describe(emv.pan)
            )))
        }
        if (ocrImageBytes != null) {
            sections.add(ReportSection("Ảnh mặt thẻ sinh viên (dùng để OCR đối chiếu)", photo = ocrImageBytes))
        }

        cccd?.sections?.firstOrNull()?.let { first ->
            sections.add(ReportSection("Căn cước công dân", first.rows, photo = cccdPhotoBytes))
        }

        if (faceResult != null) {
            val label = when (faceResult.decision) {
                "match" -> "KHỚP"
                "no_match" -> "KHÔNG KHỚP"
                else -> "KHÔNG CHẮC CHẮN"
            }
            sections.add(ReportSection("Xác thực khuôn mặt (FaceID)", listOf(
                "Kết luận" to label,
                "Độ giống" to "%.1f%%".format(faceResult.similarity * 100)
            )))
        } else {
            sections.add(ReportSection("Xác thực khuôn mặt (FaceID)",
                text = "Chưa thực hiện kiểm tra chính danh bằng khuôn mặt (xem mục Kiểm tra chính danh)."))
        }
        if (refPhotoBytes != null) {
            sections.add(ReportSection("Ảnh chân dung tham chiếu (dùng để xác thực FaceID)", photo = refPhotoBytes))
        }
        if (livePhotoBytes != null) {
            sections.add(ReportSection("Ảnh chụp live (người cầm thẻ lúc xác thực)", photo = livePhotoBytes))
        }

        val note = Report.SENSITIVE + " Báo cáo kết hợp nhiều nguồn dữ liệu cá nhân (thẻ ngân hàng, " +
            "CCCD, khuôn mặt) - bảo quản đặc biệt cẩn thận."
        return Report("Báo cáo kết hợp: xác thực chính danh thẻ sinh viên + CCCD", sections, note)
    }
}
