package vn.edu.hcmuaf.nlu.emvreader

import org.junit.Assert.assertEquals
import org.junit.Test
import vn.edu.hcmuaf.nlu.emvreader.Bac.hex
import vn.edu.hcmuaf.nlu.emvreader.Bac.toHexStr

// Vector mẫu từ ICAO Doc 9303 Part 11, Appendix D
class BacTest {
    @Test
    fun keysMatchIcaoExample() {
        val seed = java.security.MessageDigest.getInstance("SHA-1")
            .digest(Bac.mrzInfo("L898902C<", "690806", "940623")).copyOfRange(0, 16)
        assertEquals("AB94FDECF2674FDFB9B391F85D7F76F2", Bac.derive(seed, 1).toHexStr())
        assertEquals("7962D9ECE03D1ACD4C76089DCE131543", Bac.derive(seed, 2).toHexStr())
    }

    @Test
    fun mutualAuthAndSecureMessagingMatchIcaoExample() {
        val kEnc = hex("AB94FDECF2674FDFB9B391F85D7F76F2")
        val kMac = hex("7962D9ECE03D1ACD4C76089DCE131543")
        val sent = mutableListOf<String>()
        val tx: Transceiver = { apdu ->
            sent.add(apdu.toHexStr())
            if (apdu[1] == 0x82.toByte())
                hex("46B9342A41396CD7386BF5803104D7CEDC122B9132139BAF2EEDC94EE178534F2F2D235D074D7449" + "9000")
            else hex("9000")
        }
        val s = Bac.mutualAuth(tx, kEnc, kMac, hex("4608F91988702212"), hex("781723860C06C226"),
            hex("0B795240CB7049B01C19B33E32804F0B"))
        assertEquals("008200002872C29C2371CC9BDB65B779B8E8D37B29ECC154AA56A8799FAE2F498F76ED92F25F1448EEA8AD90A728", sent[0])
        assertEquals("887022120C06C226", s.ssc.toHexStr())
        sent.clear()
        try { s.send(0x00, 0xA4, 0x02, 0x0C, hex("011E")) } catch (_: Exception) {}
        assertEquals("0CA4020C158709016375432908C044F68E08BF8B92D635FF24F800", sent[0])
    }
}
