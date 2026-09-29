package vn.edu.hcmuaf.nlu.emvreader

import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class FaceVerifyResult(val similarity: Double, val decision: String, val matchThreshold: Double, val rejectThreshold: Double) {
    val label: String
        get() = when (decision) {
            "match" -> "KHỚP"
            "no_match" -> "KHÔNG KHỚP"
            else -> "KHÔNG CHẮC CHẮN - cần kiểm tra thủ công"
        }
}

class FaceIdException(message: String) : Exception(message)

/**
 * Gọi FaceID service tự host của bạn (InsightFace, FastAPI) để so khớp 1:1 hai ảnh khuôn mặt.
 * KHÔNG gửi dữ liệu tới bên thứ ba nào - đây là server do bạn dựng và tự vận hành.
 */
object FaceIdClient {
    private const val USER_AGENT = "EmvCardReaderApp/1.0 (Android)"

    fun verify(baseUrl: String, apiKey: String, imageA: ByteArray, imageB: ByteArray): FaceVerifyResult {
        val url = URL(baseUrl.trimEnd('/') + "/face/verify")
        val boundary = "----EmvCardReader" + UUID.randomUUID().toString()
        val bodyLength = partLength(boundary, "image_a", "a.jpg", imageA) +
            partLength(boundary, "image_b", "b.jpg", imageB) +
            "--$boundary--\r\n".toByteArray().size.toLong()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            // Lần gọi đầu tiên sau khi server khởi động có thể mất 10-20s để nạp model InsightFace.
            readTimeout = 45000
            setRequestProperty("X-API-Key", apiKey)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            // Biết trước kích thước nên stream thẳng ra socket, không đệm toàn bộ ảnh trong RAM.
            setFixedLengthStreamingMode(bodyLength)
        }
        try {
            conn.outputStream.use { out ->
                writePart(out, boundary, "image_a", "a.jpg", imageA)
                writePart(out, boundary, "image_b", "b.jpg", imageB)
                out.write("--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val detail = try { JSONObject(body).optString("detail", body) } catch (e: Exception) { body }
                throw FaceIdException("Server trả lỗi ($code): $detail")
            }
            val json = JSONObject(body)
            val th = json.getJSONObject("thresholds")
            return FaceVerifyResult(
                similarity = json.getDouble("similarity"),
                decision = json.getString("decision"),
                matchThreshold = th.getDouble("match"),
                rejectThreshold = th.getDouble("reject")
            )
        } catch (e: FaceIdException) {
            throw e
        } catch (e: Exception) {
            throw FaceIdException("Không kết nối được tới FaceID service ($baseUrl): ${e.message}. " +
                "Kiểm tra điện thoại có Internet, địa chỉ server đúng chưa, và server có đang chạy không.")
        } finally {
            conn.disconnect()
        }
    }

    private fun partHeader(boundary: String, field: String, filename: String): ByteArray =
        ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"$field\"; filename=\"$filename\"\r\n" +
            "Content-Type: image/jpeg\r\n\r\n").toByteArray()

    private fun partLength(boundary: String, field: String, filename: String, data: ByteArray): Long =
        partHeader(boundary, field, filename).size.toLong() + data.size + 2L // + "\r\n"

    private fun writePart(out: OutputStream, boundary: String, field: String, filename: String, data: ByteArray) {
        out.write(partHeader(boundary, field, filename))
        out.write(data)
        out.write("\r\n".toByteArray())
    }
}
