package vn.edu.hcmuaf.nlu.emvreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.edu.hcmuaf.nlu.emvreader.Bac.hex

class ReportTest {
    private val pan = "9704180000004883831"

    private fun tlv(tag: String, v: ByteArray) = hex(tag) + byteArrayOf(v.size.toByte()) + v

    private fun sampleNodes(): Map<String, List<TlvNode>> {
        val rec = tlv("70",
            tlv("57", hex(pan + "D28016010000041300F")) + tlv("5F24", hex("280131")) +
                tlv("5A", hex(pan + "F")) + tlv("5F20", "NGUYEN/VAN A".toByteArray()) +
                tlv("50", "NAPAS Debit".toByteArray()) + tlv("5F28", hex("0704")))
        return mapOf("A0000007271010" to TlvParser.parse(rec))
    }

    @Test fun summarizeExtractsFields() {
        val s = EmvReport.summarize(sampleNodes(), listOf("A0000007271010"), "log")!!
        assertEquals(pan, s.pan); assertEquals("2801", s.expiryYymm); assertEquals("NGUYEN/VAN A", s.name)
        assertEquals("601", s.serviceCode); assertEquals("NAPAS Debit", s.label)
    }

    @Test fun emvReportMasksPanEverywhereUnlessFull() {
        val log = "5A = ${pan}F\n57 = ${pan}D2801\nRSP: " + pan.toByteArray().joinToString("") { "%02X".format(it) }
        val s = EmvReport.summarize(sampleNodes(), listOf("A0000007271010"), log)!!
        val masked = EmvReport.build(s, null, false).toText()
        assertFalse(masked.contains(pan)); assertTrue(masked.contains("970418*********3831"))
        assertFalse(masked.contains(pan.toByteArray().joinToString("") { "%02X".format(it) }))
        assertTrue(EmvReport.build(s, null, true).toText().contains(pan))
    }

    @Test fun emvReportIncludesOcrComparison() {
        val s = EmvReport.summarize(sampleNodes(), emptyList(), "")!!
        val ocr = OcrParser.parse("BIDV\n9704 1800 0000 4883 831\nVALID THRU 01/28")
        val txt = EmvReport.build(s, ocr, false).toText()
        assertTrue(txt.contains("ĐỐI CHIẾU VỚI MẶT THẺ")); assertTrue(txt.contains("KHỚP"))
    }

    @Test fun dateFormatting() {
        assertEquals("15/02/1990", CccdReport.fmtYymmdd("900215", true, 2026))
        assertEquals("15/02/2010", CccdReport.fmtYymmdd("100215", true, 2026))
        assertEquals("15/02/2030", CccdReport.fmtYymmdd("300215", false, 2026))
    }

    @Test fun cccdReportHasMainAndExtraSections() {
        val fields = linkedMapOf("Số giấy tờ" to "012345678", "Ngày sinh (YYMMDD)" to "900215", "Giới tính" to "M",
            "Hết hạn (YYMMDD)" to "300215", "Quốc tịch" to "VNM", "Họ tên (MRZ, không dấu)" to "NGUYEN VAN A")
        val body = tlv("5F0E", "Nguyễn Văn A".toByteArray()) + tlv("5F42", "12 Đường ABC, Thủ Đức".toByteArray()) +
            tlv("5F2B", hex("19900215"))
        val dg11 = tlv("6B", body)
        val r = CccdReport.build(fields, listOf("L1", "L2", "L3"), "0107", listOf("DG1", "DG11"),
            mapOf("DG11" to ("" to dg11)), null)
        val txt = r.toText()
        assertTrue(txt.contains("15/02/1990")); assertTrue(txt.contains("Nam")); assertTrue(txt.contains("Việt Nam"))
        assertTrue(txt.contains("Nguyễn Văn A")); assertTrue(txt.contains("12 Đường ABC, Thủ Đức"))
        assertTrue(txt.contains("CHƯA thực hiện")); assertNotNull(r.sections.firstOrNull { it.heading.contains("DG11") })
    }
}
