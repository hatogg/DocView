package ua.x44to.docview.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import ua.x44to.docview.core.Align
import ua.x44to.docview.core.Block
import ua.x44to.docview.core.Doc
import ua.x44to.docview.core.FlowDoc
import ua.x44to.docview.core.Image
import ua.x44to.docview.core.Para
import ua.x44to.docview.core.Rule
import ua.x44to.docview.core.SheetDoc
import ua.x44to.docview.core.SlideDoc
import ua.x44to.docview.core.Table
import ua.x44to.docview.core.colName
import ua.x44to.docview.render.Bitmaps
import ua.x44to.docview.render.SlideRender
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/** Перетворення будь-якого документа на PDF (для друку і «Зберегти як PDF»). */
object Export {
    private const val W = 595
    private const val H = 842
    private const val M = 48f

    fun toPdf(doc: Doc, docId: String, out: File, cancelled: () -> Boolean = { false }) {
        val pdf = PdfDocument()
        try {
            when (doc) {
                is FlowDoc -> Flow(pdf, doc, docId, cancelled).run()
                is SheetDoc -> sheets(pdf, doc, cancelled)
                is SlideDoc -> slides(pdf, doc, docId, cancelled)
                else -> throw IllegalArgumentException("unsupported")
            }
            FileOutputStream(out).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    fun image(bytes: ByteArray, out: File) {
        val bmp = Bitmaps.decode(bytes, 2400) ?: throw IllegalArgumentException("bad image")
        val pdf = PdfDocument()
        try {
            val land = bmp.width > bmp.height
            val pw = if (land) H else W
            val ph = if (land) W else H
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
            val k = min((pw - 2 * M) / bmp.width, (ph - 2 * M) / bmp.height)
            val w = bmp.width * k
            val h = bmp.height * k
            page.canvas.drawBitmap(bmp, null, RectF((pw - w) / 2, (ph - h) / 2, (pw + w) / 2, (ph + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            pdf.finishPage(page)
            FileOutputStream(out).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    // ───────── текст ─────────

    private class Flow(val pdf: PdfDocument, val doc: FlowDoc, val docId: String, val cancelled: () -> Boolean) {
        var page: PdfDocument.Page? = null
        var n = 0
        var y = M
        val cw = W - 2 * M

        fun canvas(): Canvas = (page ?: newPage()).canvas
        fun newPage(): PdfDocument.Page {
            page?.let { pdf.finishPage(it) }
            n++
            y = M
            return pdf.startPage(PdfDocument.PageInfo.Builder(W, H, n).create()).also { page = it }
        }

        fun run() {
            newPage()
            for (b in doc.blocks) {
                if (cancelled()) break
                block(b, M, cw)
            }
            page?.let { pdf.finishPage(it) }
            page = null
        }

        fun paraLayout(p: Para, width: Float, scale: Float = 1f): StaticLayout {
            val base = 10.5f * scale
            val hs = when (p.heading) { 1 -> 1.7f; 2 -> 1.45f; 3 -> 1.25f; 4 -> 1.1f; else -> 1f }
            val sb = SpannableStringBuilder()
            p.bullet?.let { sb.append(it).append("  ") }
            SlideRender.spans(p.runs.map { r ->
                val rel = r.pt?.let { (it / doc.basePt).coerceIn(0.6f, 3f) } ?: 1f
                ua.x44to.docview.core.Run(r.text, r.b || p.heading in 1..5, r.i, r.u, r.s, r.color?.takeIf { lum(it) < 0.85f }, r.bg, base * max(rel, hs), r.link, r.sup, r.sub, r.mono || p.mono, r.caps)
            }, 1f, base * hs, Color.BLACK, sb)
            if (sb.isEmpty()) sb.append(" ")
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = base * hs; color = Color.BLACK; if (p.mono) typeface = Typeface.MONOSPACE }
            val al = when (p.align) { Align.CENTER -> Layout.Alignment.ALIGN_CENTER; Align.END -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
            val bld = StaticLayout.Builder.obtain(sb, 0, sb.length, tp, width.toInt().coerceAtLeast(10)).setAlignment(al).setLineSpacing(0f, 1.15f)
            if (p.align == Align.JUSTIFY && Build.VERSION.SDK_INT >= 26) bld.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
            if (p.firstLine) bld.setIndents(intArrayOf((base * 1.5f).toInt()), null)
            return bld.build()
        }

        fun lum(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

        fun block(b: Block, x: Float, width: Float) {
            when (b) {
                is Para -> {
                    val indent = b.indent * 14f
                    val l = paraLayout(b, width - indent)
                    y += if (b.heading > 0) 10f else 4f
                    // построково, щоб абзац міг перейти на наступну сторінку
                    for (i in 0 until l.lineCount) {
                        val top = l.getLineTop(i).toFloat()
                        val bottom = l.getLineBottom(i).toFloat()
                        if (y + (bottom - top) > H - M) newPage()
                        val c = canvas()
                        c.save()
                        c.translate(x + indent, y - top)
                        c.clipRect(0f, top, width - indent, bottom)
                        l.draw(c)
                        c.restore()
                        y += bottom - top
                    }
                }
                is Table -> table(b, x, width)
                is Image -> {
                    val bmp = Bitmaps.get(docId, doc.res, b.key, 1600) ?: return
                    var w = min(width, b.w ?: (bmp.width * 0.75f))
                    var h = w * bmp.height / bmp.width
                    val maxH = H - 2 * M
                    if (h > maxH) { h = maxH; w = h * bmp.width / bmp.height }
                    if (y + h > H - M) newPage()
                    canvas().drawBitmap(bmp, null, RectF(x + (width - w) / 2, y + 4, x + (width + w) / 2, y + 4 + h), Paint(Paint.FILTER_BITMAP_FLAG))
                    y += h + 8
                }
                is Rule -> if (b.pageBreak) newPage() else {
                    y += 8
                    canvas().drawLine(x, y, x + width, y, Paint().apply { color = Color.LTGRAY; strokeWidth = 0.8f })
                    y += 8
                }
            }
        }

        fun table(t: Table, x: Float, width: Float) {
            val cols = t.cols.coerceAtLeast(1)
            val est = FloatArray(cols) { 4f }
            for (r in t.rows) {
                var c = 0
                for (cell in r.cells) {
                    if (c >= cols) break
                    if (cell.span == 1) est[c] = max(est[c], min(40f, (cell.text.lines().maxOfOrNull { it.length } ?: 0).toFloat()))
                    c += cell.span
                }
            }
            val sum = est.sum()
            val cw = FloatArray(cols) { est[it] / sum * width }
            val line = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 0.6f }
            y += 6
            for (r in t.rows) {
                var c = 0
                val cells = ArrayList<Triple<Float, Float, List<StaticLayout>>>()
                var cx = x
                for (cell in r.cells) {
                    if (c >= cols) break
                    val span = cell.span.coerceAtMost(cols - c)
                    var w = 0f
                    for (k in c until c + span) w += cw[k]
                    val ls = cell.blocks.filterIsInstance<Para>().map { paraLayout(it, w - 6, 0.85f) }
                    cells.add(Triple(cx, w, ls))
                    cx += w
                    c += span
                }
                val rowH = (cells.maxOfOrNull { (_, _, ls) -> ls.sumOf { it.height } } ?: 0).toFloat() + 6f
                if (y + rowH > H - M) newPage()
                val cv = canvas()
                for ((cx0, w, ls) in cells) {
                    cv.drawRect(cx0, y, cx0 + w, y + min(rowH, H - M - y), line)
                    var yy = y + 3
                    for (l in ls) {
                        cv.save(); cv.translate(cx0 + 3, yy); cv.clipRect(0f, 0f, w - 6, min(l.height.toFloat(), H - M - yy)); l.draw(cv); cv.restore()
                        yy += l.height
                    }
                }
                y += min(rowH, H - M - y)
            }
            y += 6
        }
    }

    // ───────── таблиці ─────────

    private fun sheets(pdf: PdfDocument, doc: SheetDoc, cancelled: () -> Boolean) {
        var n = 0
        val pw = H // альбомна
        val ph = W
        for (s in doc.sheets) {
            if (cancelled()) break
            if (s.rows.isEmpty()) continue
            val widths = FloatArray(s.cols) { c ->
                val w = s.widths?.getOrNull(c)
                when {
                    w != null && w == 0f -> 0f
                    w != null && w > 0f -> w * 5.25f
                    else -> {
                        var m = 0
                        for (r in 0 until minOf(s.rows.size, 300)) m = maxOf(m, s.rows[r].getOrNull(c)?.text?.length ?: 0)
                        (m * 5f + 10f).coerceIn(36f, 200f)
                    }
                }
            }
            // смуги стовпців, що влазять на сторінку
            val bands = ArrayList<IntRange>()
            var start = 0
            var acc = 0f
            val avail = pw - 2 * M - 26f
            for (c in widths.indices) {
                if (acc + widths[c] > avail && c > start) { bands.add(start until c); start = c; acc = 0f }
                acc += widths[c]
            }
            if (start < widths.size) bands.add(start until widths.size)
            val rowH = 14f
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = Color.BLACK }
            val hp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7.5f; color = Color.DKGRAY }
            val line = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 0.5f }
            val fill = Paint()
            for (band in bands) {
                var r = 0
                while (r < s.rows.size) {
                    if (cancelled()) return
                    n++
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, n).create())
                    val c = page.canvas
                    c.drawText(s.name, M, M - 14, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; isFakeBoldText = true })
                    var y = M
                    // заголовок стовпців
                    var x = M + 26f
                    for (col in band) { c.drawRect(x, y, x + widths[col], y + rowH, line); c.drawText(colName(col), x + 3, y + 10, hp); x += widths[col] }
                    y += rowH
                    while (r < s.rows.size && y + rowH <= ph - M) {
                        c.drawText("${r + 1}", M, y + 10, hp)
                        x = M + 26f
                        for (col in band) {
                            val w = widths[col]
                            val cell = s.rows[r].getOrNull(col)
                            cell?.style?.bg?.let { fill.color = it; c.drawRect(x, y, x + w, y + rowH, fill) }
                            c.drawRect(x, y, x + w, y + rowH, line)
                            if (cell != null && cell.text.isNotEmpty() && w > 0) {
                                tp.isFakeBoldText = cell.style?.b == true
                                tp.color = cell.style?.color ?: Color.BLACK
                                val txt = cell.text.replace('\n', ' ')
                                val cnt = tp.breakText(txt, true, w - 4, null)
                                val shown = txt.substring(0, cnt)
                                val tw = tp.measureText(shown)
                                val tx = when (cell.style?.align ?: if (cell.num) Align.END else Align.START) {
                                    Align.END -> x + w - 2 - tw; Align.CENTER -> x + (w - tw) / 2; else -> x + 2
                                }
                                c.drawText(shown, tx, y + 10, tp)
                            }
                            x += w
                        }
                        y += rowH
                        r++
                    }
                    pdf.finishPage(page)
                }
            }
        }
        if (n == 0) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
            pdf.finishPage(page)
        }
    }

    // ───────── слайди ─────────

    private fun slides(pdf: PdfDocument, doc: SlideDoc, docId: String, cancelled: () -> Boolean) {
        val w = doc.w.toInt().coerceAtLeast(100)
        val h = doc.h.toInt().coerceAtLeast(100)
        for ((i, s) in doc.slides.withIndex()) {
            if (cancelled()) break
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            SlideRender.draw(page.canvas, doc, s, docId, w.toFloat())
            pdf.finishPage(page)
        }
    }

    // ───────── друк ─────────

    fun print(ctx: Context, name: String, pdf: File) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(name, object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
                if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
                cb.onLayoutFinished(PrintDocumentInfo.Builder(name.substringBeforeLast('.') + ".pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
            }

            override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
                try {
                    pdf.inputStream().use { inp -> FileOutputStream(dest.fileDescriptor).use { inp.copyTo(it) } }
                    cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    cb.onWriteFailed(e.message)
                }
            }
        }, null)
    }
}
