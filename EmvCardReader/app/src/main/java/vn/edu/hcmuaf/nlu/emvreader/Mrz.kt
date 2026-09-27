package vn.edu.hcmuaf.nlu.emvreader

data class MrzResult(val doc: String?, val dob: String?, val doe: String?, val note: String)

/** Trích 3 khóa BAC từ văn bản OCR của dòng MRZ TD1 (CCCD). Chỉ nhận khi chữ số kiểm tra khớp. */
object Mrz {
    private val FIX = mapOf('O' to '0', 'Q' to '0', 'D' to '0', 'I' to '1', 'L' to '1',
        'Z' to '2', 'S' to '5', 'B' to '8', 'G' to '6')

    private fun digits(s: String): String? {
        val r = s.map { if (it.isDigit()) it else FIX[it] ?: return null }.joinToString("")
        return r
    }

    private fun validDate(d: String): Boolean {
        val m = d.substring(2, 4).toInt()
        val day = d.substring(4, 6).toInt()
        return m in 1..12 && day in 1..31
    }

    private class Scan(val doc: String?, val docScore: Int, val dob: String?, val doe: String?, val dateScore: Int)

    private fun norm(t: String) = t.uppercase()
        .replace('«', '<').replace('‹', '<').replace('〈', '<')
        .filter { !it.isWhitespace() }

    private fun scan(s: String): Scan {
        var dob: String? = null
        var doe: String? = null
        var dscore = -1
        var i = 0
        while (i + 15 <= s.length) {
            val w = s.substring(i, i + 15)
            val b = digits(w.substring(0, 6)); val bc = digits(w.substring(6, 7))
            val e = digits(w.substring(8, 14)); val ec = digits(w.substring(14, 15))
            if (b != null && bc != null && e != null && ec != null && w[7] in "MF<" &&
                Bac.checkDigit(b) == bc.toInt() && Bac.checkDigit(e) == ec.toInt() && validDate(b) && validDate(e)) {
                val score = if (s.regionMatches(i + 15, "VNM", 0, 3)) 2 else 1
                if (score > dscore) { dscore = score; dob = b; doe = e }
            }
            i++
        }
        var doc: String? = null
        var best = -1
        var idx = s.indexOf("VNM")
        while (idx >= 0) {
            if (idx + 13 <= s.length) {
                val raw = s.substring(idx + 3, idx + 12)
                val cd = digits(s.substring(idx + 12, idx + 13))
                var cand: String? = null
                if (cd != null) {
                    if (Bac.checkDigit(raw) == cd.toInt()) cand = raw.replace("<", "")
                    else digits(raw)?.let { if (Bac.checkDigit(it) == cd.toInt()) cand = it }
                }
                val c = cand
                if (c != null) {
                    val pre = s.substring(maxOf(idx - 2, 0), idx)
                    val score = (if (pre in listOf("ID", "1D", "I<", "10")) 3 else 0) + (if (c.all { it.isDigit() }) 1 else 0)
                    if (score > best) { best = score; doc = c }
                }
            }
            idx = s.indexOf("VNM", idx + 1)
        }
        return Scan(doc, best, dob, doe, dscore)
    }

    /** Quét từng dòng OCR trước (tránh ghép sai qua ranh giới dòng), chỉ quét chuỗi ghép khi còn thiếu. */
    fun parse(ocrText: String): MrzResult? {
        var doc: String? = null; var best = -1
        var dob: String? = null; var doe: String? = null; var dscore = -1
        val joined = norm(ocrText)
        for (chunk in ocrText.lines().map { norm(it) } + joined) {
            if (doc != null && dob != null && chunk == joined) break
            val r = scan(chunk)
            if (r.doc != null && r.docScore > best) { doc = r.doc; best = r.docScore }
            if (r.dob != null && r.dateScore > dscore) { dob = r.dob; doe = r.doe; dscore = r.dateScore }
        }
        if (doc == null && dob == null) return null
        val note = if (doc != null && dob != null && doe != null)
            "Đã đọc đủ số giấy tờ, ngày sinh, ngày hết hạn (chữ số kiểm tra khớp)."
        else "Mới đọc được một phần (số giấy tờ: ${if (doc != null) "có" else "thiếu"}, ngày tháng: ${if (dob != null && doe != null) "có" else "thiếu"}). Hãy chụp lại rõ hơn hoặc nhập tay phần thiếu."
        return MrzResult(doc, dob, doe, note)
    }
}
