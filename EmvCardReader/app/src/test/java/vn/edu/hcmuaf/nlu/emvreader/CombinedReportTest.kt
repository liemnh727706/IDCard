package vn.edu.hcmuaf.nlu.emvreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedReportTest {
    private fun cccdReportWithName(name: String): Report =
        Report("Báo cáo đọc Căn cước công dân gắn chip",
            listOf(ReportSection("Thông tin chính (DG1)", listOf("Họ tên (theo MRZ, không dấu)" to name))))

    @Test fun nullWhenNothingRead() {
        assertNull(CombinedReport.build(null, null, false, null, null, null, null, null, null))
    }

    @Test fun crossChecksMatchingNamesAcrossSources() {
        val emv = EmvSummary("9704180000004883831", "2801", "NGUYEN VAN A", "NAPAS Debit",
            "A0000007271010", "601", "0704", listOf("A0000007271010"), "")
        val ocr = OcrParser.parse("Ho va ten: NGUYỄN VĂN A\nMSSV: 21123456")
        val cccd = cccdReportWithName("NGUYEN VAN A")

        val rep = CombinedReport.build(emv, ocr, false, null, cccd, null, null, null, null)!!
        val idSection = rep.sections.first { it.heading.contains("Họ tên") }
        assertTrue(idSection.text.contains("KHỚP nhau"))
        assertTrue(idSection.text.contains("KHỚP") && !idSection.text.contains("KHÔNG khớp"))
        assertEquals(3, idSection.rows.size)
    }

    @Test fun flagsMismatchBetweenChipSources() {
        val emv = EmvSummary("9704180000004883831", "2801", "NGUYEN VAN A", "NAPAS Debit",
            "A0000007271010", "601", "0704", listOf("A0000007271010"), "")
        val cccd = cccdReportWithName("TRAN THI B")

        val rep = CombinedReport.build(emv, null, false, null, cccd, null, null, null, null)!!
        val idSection = rep.sections.first { it.heading.contains("Họ tên") }
        assertTrue(idSection.text.contains("KHÔNG khớp nhau"))
    }

    @Test fun includesPhotosInDedicatedSections() {
        val emv = EmvSummary("9704180000004883831", "2801", "NGUYEN VAN A", "NAPAS Debit",
            "A0000007271010", "601", "0704", listOf("A0000007271010"), "")
        val ocrImg = byteArrayOf(1, 2, 3)
        val cccdImg = byteArrayOf(4, 5, 6)
        val ref = byteArrayOf(7, 8, 9)
        val live = byteArrayOf(10, 11, 12)
        val face = FaceVerifyResult(0.9, "match", 0.32, 0.20)

        val rep = CombinedReport.build(emv, null, false, ocrImg, cccdReportWithName("NGUYEN VAN A"), cccdImg,
            face, ref, live)!!
        val photosByHeading = rep.sections.associate { it.heading to it.photo }
        assertEquals(ocrImg, photosByHeading["Ảnh mặt thẻ sinh viên (dùng để OCR đối chiếu)"])
        assertEquals(cccdImg, photosByHeading["Căn cước công dân"])
        assertEquals(ref, photosByHeading["Ảnh chân dung tham chiếu (dùng để xác thực FaceID)"])
        assertEquals(live, photosByHeading["Ảnh chụp live (người cầm thẻ lúc xác thực)"])
    }
}
