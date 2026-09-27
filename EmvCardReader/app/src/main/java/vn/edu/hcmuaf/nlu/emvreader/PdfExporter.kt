package vn.edu.hcmuaf.nlu.emvreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream

/** Vẽ Report thành PDF A4 bằng PdfDocument (font hệ thống của Android hiển thị được tiếng Việt). */
object PdfExporter {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val M = 40f
    private const val KEY_W = 165f

    fun render(report: Report): ByteArray {
        val doc = PdfDocument()
        val r = Renderer(doc)
        r.draw(report)
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    private class Renderer(val doc: PdfDocument) {
        private val contentW = PAGE_W - 2 * M
        private var pageNo = 0
        private lateinit var page: PdfDocument.Page
        private lateinit var canvas: Canvas
        private var y = 0f

        private fun tp(size: Float, bold: Boolean = false, mono: Boolean = false, color: Int = Color.BLACK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.DEFAULT_BOLD
                else -> Typeface.DEFAULT
            }
        }

        private val pTitle = tp(18f, bold = true)
        private val pMeta = tp(9f, color = Color.GRAY)
        private val pWarn = tp(8.5f, bold = true, color = Color.rgb(0x8a, 0x1c, 0x1c))
        private val pHead = tp(12.5f, bold = true, color = Color.rgb(0x1f, 0x3a, 0x5f))
        private val pKey = tp(9.5f, bold = true)
        private val pVal = tp(9.5f)
        private val pMono = tp(7.5f, mono = true)
        private val line = Paint().apply { color = Color.rgb(0xcc, 0xcc, 0xcc); strokeWidth = 0.5f }

        private fun newPage() {
            if (pageNo > 0) finishPage()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page.canvas
            y = M
        }

        private fun finishPage() {
            canvas.drawText("Chip Card Reader - báo cáo tạo tự động, không thay thế giấy tờ gốc", M, PAGE_H - 22f, pMeta)
            val label = "Trang $pageNo"
            canvas.drawText(label, PAGE_W - M - pMeta.measureText(label), PAGE_H - 22f, pMeta)
            doc.finishPage(page)
        }

        private fun ensure(h: Float) {
            if (y + h > PAGE_H - 44f) newPage()
        }

        private fun layout(text: String, paint: TextPaint, width: Float): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(20))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()

        /** Vẽ khối chữ nhiều dòng, tự sang trang khi hết chỗ. */
        private fun drawText(text: String, paint: TextPaint, x: Float, width: Float, leading: Float = 1.15f) {
            val l = layout(text, paint, width)
            for (i in 0 until l.lineCount) {
                val h = (l.getLineBottom(i) - l.getLineTop(i)) * leading
                ensure(h)
                val s = text.substring(l.getLineStart(i), l.getLineEnd(i)).trimEnd('\n', '\r')
                canvas.drawText(s, x, y - paint.ascent(), paint)
                y += h
            }
        }

        fun draw(report: Report) {
            newPage()
            drawText(report.title, pTitle, M, contentW)
            y += 2
            drawText("Thời điểm tạo: ${report.generated}", pMeta, M, contentW)
            y += 6

            val warn = layout(report.sensitive, pWarn, contentW - 12)
            ensure(warn.height + 12f)
            canvas.drawRect(M, y, M + contentW, y + warn.height + 10f, Paint().apply { color = Color.rgb(0xfd, 0xec, 0xec) })
            canvas.drawRect(M, y, M + contentW, y + warn.height + 10f, Paint().apply {
                color = Color.rgb(0xd9, 0x53, 0x4f); style = Paint.Style.STROKE; strokeWidth = 0.8f
            })
            canvas.save()
            canvas.translate(M + 6, y + 5)
            warn.draw(canvas)
            canvas.restore()
            y += warn.height + 16f

            for (sec in report.sections) drawSection(sec)
            finishPage()
        }

        private fun drawSection(sec: ReportSection) {
            ensure(60f)
            y += 8
            drawText(sec.heading, pHead, M, contentW)
            y += 3

            var bmp: Bitmap? = null
            var photoBottom = y
            var valW = contentW - KEY_W
            sec.photo?.let {
                bmp = BitmapFactory.decodeByteArray(it, 0, it.size)
                bmp?.let { b ->
                    val w = 96f
                    val h = (w * b.height / b.width).coerceAtMost(130f)
                    val dst = android.graphics.RectF(M + contentW - w, y, M + contentW, y + h)
                    canvas.drawBitmap(b, null, dst, null)
                    photoBottom = y + h + 6
                    valW -= w + 8
                }
            }

            for ((k, v) in sec.rows) {
                val kl = layout(k, pKey, KEY_W - 8)
                val vl = layout(v.ifEmpty { " " }, pVal, valW)
                val h = maxOf(kl.height, vl.height) + 6f
                ensure(h)
                canvas.save(); canvas.translate(M, y + 3); kl.draw(canvas); canvas.restore()
                canvas.save(); canvas.translate(M + KEY_W, y + 3); vl.draw(canvas); canvas.restore()
                y += h
                canvas.drawLine(M, y, M + contentW - (if (bmp != null && y < photoBottom) 104f else 0f), y, line)
            }
            if (y < photoBottom) y = photoBottom
            if (sec.text.isNotEmpty()) {
                y += 4
                drawText(sec.text, pMono, M, contentW, leading = 1.1f)
            }
        }
    }
}

/** Đưa PDF của báo cáo vào hệ thống in của Android. */
class ReportPrintAdapter(private val report: Report, private val jobName: String) : PrintDocumentAdapter() {
    private var pdf: ByteArray? = null

    override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?,
                          callback: LayoutResultCallback, extras: Bundle?) {
        if (cancel?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?,
                         callback: WriteResultCallback) {
        try {
            val bytes = pdf ?: PdfExporter.render(report).also { pdf = it }
            FileOutputStream(dest.fileDescriptor).use { it.write(bytes) }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }

    override fun onFinish() {
        pdf = null
    }
}
