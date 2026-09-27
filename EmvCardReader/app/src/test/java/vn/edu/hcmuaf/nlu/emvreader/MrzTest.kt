package vn.edu.hcmuaf.nlu.emvreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MrzTest {
    // Dữ liệu giả, check digit tính đúng theo 7-3-1
    private fun sample(): Triple<String, String, String> = Triple("012345678", "900215", "300215")

    private fun lines(): Pair<String, String> {
        val (doc, dob, doe) = sample()
        val l1 = ("IDVNM" + doc + Bac.checkDigit(doc) + "012345678901<<<").padEnd(30, '<')
        val l2 = (dob + Bac.checkDigit(dob) + "M" + doe + Bac.checkDigit(doe) + "VNM<<<<<<<<<<<0").padEnd(30, '<')
        return l1 to l2
    }

    @Test fun cleanText() {
        val (l1, l2) = lines()
        val r = Mrz.parse("$l1\n$l2\nNGUYEN<<VAN<A<<<<<<<<<<<<<<<<<<<")!!
        assertEquals("012345678", r.doc); assertEquals("900215", r.dob); assertEquals("300215", r.doe)
    }

    @Test fun ocrNoiseLettersAndSpaces() {
        val (l1, l2) = lines()
        val noisy2 = l2.replace('0', 'O').replace('1', 'I')
        val r = Mrz.parse("$l1\n" + noisy2.chunked(5).joinToString(" "))!!
        assertEquals("900215", r.dob); assertEquals("300215", r.doe)
    }

    @Test fun garbageReturnsNull() { assertNull(Mrz.parse("HELLO WORLD 12345")) }
}
