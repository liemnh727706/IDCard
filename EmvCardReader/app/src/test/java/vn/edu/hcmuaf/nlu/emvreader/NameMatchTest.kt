package vn.edu.hcmuaf.nlu.emvreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMatchTest {
    @Test fun extractsNameWithLabelUnaccented() {
        assertEquals("NGUYEN VAN A", extractOcrName("Ho va ten: NGUYEN VAN A\nMSSV: 21123456"))
    }

    @Test fun extractsNameWithLabelAccented() {
        assertEquals("NGUYỄN VĂN A", extractOcrName("TRUONG DAI HOC\nHọ và tên: NGUYỄN VĂN A\nMSSV: 21123456"))
    }

    @Test fun extractsNameFullNameLabel() {
        assertEquals("TRAN VAN B", extractOcrName("FULL NAME: TRAN VAN B\nID: 123"))
    }

    @Test fun returnsNullWithoutLabel() {
        assertNull(extractOcrName("khong co nhan gi ca\n1234567890"))
    }

    @Test fun namesMatchIgnoringDiacriticsAndOrder() {
        assertTrue(namesMatch("NGUYEN VAN A", "Nguyễn Văn A"))
        assertTrue(namesMatch("DANG THI BICH NGOC", "Đặng Thị Bích Ngọc"))
    }

    @Test fun namesMismatchDifferentPerson() {
        assertTrue(!namesMatch("NGUYEN VAN A", "Nguyễn Văn B"))
    }
}
