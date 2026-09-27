package vn.edu.hcmuaf.nlu.emvreader

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

typealias Transceiver = (ByteArray) -> ByteArray

class BacException(message: String) : Exception(message)

/** BAC + Secure Messaging theo ICAO 9303 cho CCCD/hộ chiếu điện tử. Không phụ thuộc Android. */
object Bac {
    val AID_EMRTD: ByteArray = hex("A0000002471001")

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    fun ByteArray.toHexStr(): String = joinToString("") { "%02X".format(it) }

    fun checkDigit(s: String): Int {
        val w = intArrayOf(7, 3, 1)
        var tot = 0
        s.forEachIndexed { i, c ->
            val v = when {
                c.isDigit() -> c - '0'
                c in 'A'..'Z' -> c - 'A' + 10
                else -> 0
            }
            tot += v * w[i % 3]
        }
        return tot % 10
    }

    fun mrzInfo(doc: String, dob: String, doe: String): ByteArray {
        val d = doc.uppercase().padEnd(9, '<')
        return (d + checkDigit(d) + dob + checkDigit(dob) + doe + checkDigit(doe)).toByteArray(Charsets.US_ASCII)
    }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b)

    private fun parity(k: ByteArray) = ByteArray(k.size) {
        val v = k[it].toInt() and 0xFE
        (if (Integer.bitCount(v) % 2 == 0) v or 1 else v).toByte()
    }

    fun derive(seed: ByteArray, c: Int): ByteArray {
        val h = sha1(seed + byteArrayOf(0, 0, 0, c.toByte()))
        return parity(h.copyOfRange(0, 16))
    }

    private fun pad(d: ByteArray): ByteArray {
        val n = 8 - (d.size + 1) % 8
        return d + byteArrayOf(0x80.toByte()) + ByteArray(if (n == 8) 0 else n)
    }

    private fun unpad(d: ByteArray): ByteArray {
        var i = d.size
        while (i > 0 && d[i - 1] == 0.toByte()) i--
        if (i == 0 || d[i - 1] != 0x80.toByte()) throw BacException("Padding không hợp lệ")
        return d.copyOfRange(0, i - 1)
    }

    private fun des3(key16: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val key24 = key16 + key16.copyOfRange(0, 8)
        val c = Cipher.getInstance("DESede/CBC/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key24, "DESede"), IvParameterSpec(ByteArray(8)))
        return c.doFinal(data)
    }

    /** ISO 9797-1 MAC algorithm 3, padding method 2. */
    fun mac(key: ByteArray, data: ByteArray): ByteArray {
        val ka = SecretKeySpec(key.copyOfRange(0, 8), "DES")
        val kb = SecretKeySpec(key.copyOfRange(8, 16), "DES")
        val cbc = Cipher.getInstance("DES/CBC/NoPadding")
        cbc.init(Cipher.ENCRYPT_MODE, ka, IvParameterSpec(ByteArray(8)))
        val h = cbc.doFinal(pad(data)).takeLast(8).toByteArray()
        val d = Cipher.getInstance("DES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, kb) }.doFinal(h)
        return Cipher.getInstance("DES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, ka) }.doFinal(d)
    }

    private fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }

    private fun berLen(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    class Session(private val tx: Transceiver, private val ksEnc: ByteArray, private val ksMac: ByteArray, var ssc: ByteArray) {

        private fun inc() {
            for (i in 7 downTo 0) {
                ssc[i] = (ssc[i] + 1).toByte()
                if (ssc[i] != 0.toByte()) break
            }
        }

        /** Gửi APDU được bảo vệ. Trả về (dữ liệu giải mã, SW 2 byte). */
        fun send(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray = ByteArray(0), le: Int? = null): Pair<ByteArray, ByteArray> {
            val header = byteArrayOf((cla or 0x0C).toByte(), ins.toByte(), p1.toByte(), p2.toByte())
            var do87 = ByteArray(0)
            var do97 = ByteArray(0)
            if (data.isNotEmpty()) {
                val enc = des3(ksEnc, pad(data), true)
                do87 = byteArrayOf(0x87.toByte()) + berLen(enc.size + 1) + byteArrayOf(1) + enc
            }
            if (le != null) do97 = byteArrayOf(0x97.toByte(), 1, le.toByte())
            inc()
            val m = mac(ksMac, ssc + pad(header) + do87 + do97)
            val body = do87 + do97 + byteArrayOf(0x8E.toByte(), 8) + m
            val resp = tx(header + byteArrayOf(body.size.toByte()) + body + byteArrayOf(0))
            if (resp.size < 2) throw BacException("Không có phản hồi")
            var sw = resp.copyOfRange(resp.size - 2, resp.size)
            val payload = resp.copyOfRange(0, resp.size - 2)
            if (payload.isEmpty()) return ByteArray(0) to sw
            inc()
            var i = 0
            var r87 = ByteArray(0)
            var r99 = ByteArray(0)
            var r8e = ByteArray(0)
            var enc87 = ByteArray(0)
            while (i < payload.size) {
                val tag = payload[i].toInt() and 0xFF
                var ln = payload[i + 1].toInt() and 0xFF
                var hl = 2
                if (ln == 0x81) { ln = payload[i + 2].toInt() and 0xFF; hl = 3 }
                else if (ln == 0x82) { ln = ((payload[i + 2].toInt() and 0xFF) shl 8) or (payload[i + 3].toInt() and 0xFF); hl = 4 }
                val v = payload.copyOfRange(i + hl, i + hl + ln)
                val raw = payload.copyOfRange(i, i + hl + ln)
                when (tag) {
                    0x87 -> { r87 = raw; enc87 = v.copyOfRange(1, v.size) }
                    0x99 -> { r99 = raw; sw = v }
                    0x8E -> r8e = v
                }
                i += hl + ln
            }
            if (r8e.isEmpty() || !mac(ksMac, ssc + r87 + r99).contentEquals(r8e)) throw BacException("Sai MAC của thẻ (Secure Messaging)")
            return (if (r87.isNotEmpty()) unpad(des3(ksEnc, enc87, false)) else ByteArray(0)) to sw
        }

        private fun ok(sw: ByteArray) = sw.size == 2 && sw[0] == 0x90.toByte() && sw[1] == 0.toByte()

        fun readFile(fid: Int, chunk: Int = 0xC0, progress: ((Int, Int) -> Unit)? = null): ByteArray? {
            val (_, swSel) = send(0x00, 0xA4, 0x02, 0x0C, byteArrayOf((fid shr 8).toByte(), fid.toByte()))
            if (!ok(swSel)) return null
            val (head, swHead) = send(0x00, 0xB0, 0, 0, le = 4)
            if (!ok(swHead) || head.size < 2) return null
            val h1 = head[1].toInt() and 0xFF
            val total = when {
                h1 < 0x80 -> 2 + h1
                h1 == 0x81 -> 3 + (head[2].toInt() and 0xFF)
                else -> 4 + (((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF))
            }
            val out = java.io.ByteArrayOutputStream()
            out.write(head)
            while (out.size() < total) {
                val off = out.size()
                if (off > 0x7FFF) throw BacException("File lớn hơn 32KB, chưa hỗ trợ")
                val n = minOf(chunk, total - off)
                val (part, sw) = send(0x00, 0xB0, off shr 8, off and 0xFF, le = n)
                if (!ok(sw) || part.isEmpty()) throw BacException("Lỗi đọc file tại offset $off: SW=${sw.toHexStr()}")
                out.write(part)
                progress?.invoke(out.size(), total)
            }
            return out.toByteArray().copyOf(total)
        }
    }

    fun authenticate(tx: Transceiver, doc: String, dob: String, doe: String): Session {
        val sel = tx(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x0C, AID_EMRTD.size.toByte()) + AID_EMRTD)
        if (sel.size < 2 || sel[sel.size - 2] != 0x90.toByte()) throw BacException("Thẻ không có ứng dụng eMRTD (SW=${sel.takeLast(2).toByteArray().toHexStr()})")
        val kSeed = sha1(mrzInfo(doc, dob, doe)).copyOfRange(0, 16)
        val rnd = tx(hex("0084000008"))
        if (rnd.size != 10 || rnd[8] != 0x90.toByte()) throw BacException("GET CHALLENGE thất bại")
        val rng = SecureRandom()
        return mutualAuth(tx, derive(kSeed, 1), derive(kSeed, 2), rnd.copyOfRange(0, 8),
            ByteArray(8).also { rng.nextBytes(it) }, ByteArray(16).also { rng.nextBytes(it) })
    }

    fun mutualAuth(tx: Transceiver, kEnc: ByteArray, kMac: ByteArray, rndIc: ByteArray, rndIfd: ByteArray, kIfd: ByteArray): Session {
        val eIfd = des3(kEnc, rndIfd + rndIc + kIfd, true)
        val mIfd = mac(kMac, eIfd)
        val resp = tx(byteArrayOf(0, 0x82.toByte(), 0, 0, 0x28) + eIfd + mIfd + byteArrayOf(0x28))
        if (resp.size != 42 || resp[40] != 0x90.toByte() || resp[41] != 0.toByte())
            throw BacException("Xác thực BAC bị từ chối (SW=${resp.takeLast(2).toByteArray().toHexStr()}). Kiểm tra lại số giấy tờ, ngày sinh, ngày hết hạn.")
        val eIc = resp.copyOfRange(0, 32)
        if (!mac(kMac, eIc).contentEquals(resp.copyOfRange(32, 40))) throw BacException("Sai MAC trong phản hồi BAC")
        val r = des3(kEnc, eIc, false)
        if (!r.copyOfRange(0, 8).contentEquals(rndIc) || !r.copyOfRange(8, 16).contentEquals(rndIfd))
            throw BacException("Số ngẫu nhiên không khớp, phiên BAC không hợp lệ")
        val seed = xor(kIfd, r.copyOfRange(16, 32))
        return Session(tx, derive(seed, 1), derive(seed, 2), rndIc.copyOfRange(4, 8) + rndIfd.copyOfRange(4, 8))
    }
}
