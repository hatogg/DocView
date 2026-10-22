package ua.x44to.docview.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

/** Системний рендерер PDF (pdfium): швидко й точно малює сторінки. Один потік — PdfRenderer не потокобезпечний. */
class PdfEngine(val file: File) : AutoCloseable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (e: Throwable) {
        pfd.close()
        throw e
    }
    val count: Int = renderer.pageCount
    /** розміри сторінок у пунктах; заповнюються поступово */
    val sizes = arrayOfNulls<IntArray>(count)
    @Volatile var closed = false

    fun size(i: Int): IntArray = sizes[i] ?: synchronized(this) {
        sizes[i] ?: renderer.openPage(i).use { p -> intArrayOf(p.width, p.height) }.also { sizes[i] = it }
    }

    /** Уся сторінка шириною [w] пікселів. */
    fun render(i: Int, w: Int): Bitmap? = synchronized(this) {
        if (closed) return null
        renderer.openPage(i).use { p ->
            sizes[i] = intArrayOf(p.width, p.height)
            val h = (w.toLong() * p.height / p.width).toInt().coerceIn(1, 8000)
            val bmp = Bitmap.createBitmap(w.coerceIn(1, 4000), h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    /**
     * Фрагмент сторінки у великому масштабі: [region] — у пунктах сторінки, [scale] — пікселів на пункт.
     */
    fun tile(i: Int, region: RectF, scale: Float): Bitmap? = synchronized(this) {
        if (closed) return null
        val w = (region.width() * scale).toInt().coerceIn(1, 4096)
        val h = (region.height() * scale).toInt().coerceIn(1, 4096)
        renderer.openPage(i).use { p ->
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix()
            m.postTranslate(-region.left, -region.top)
            m.postScale(scale, scale)
            p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    fun renderForPrint(i: Int, w: Int): Bitmap? = synchronized(this) {
        if (closed) return null
        renderer.openPage(i).use { p ->
            val h = (w.toLong() * p.height / p.width).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            bmp
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            try { renderer.close() } catch (_: Throwable) {}
            try { pfd.close() } catch (_: Throwable) {}
        }
    }

    companion object {
        val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf").apply { priority = Thread.NORM_PRIORITY - 1 } }.asCoroutineDispatcher()
    }
}

/** Пошук, зміст і паролі — через PdfBox (системний рендерер цього не вміє). */
object PdfText {
    @Volatile private var inited = false

    fun init(c: Context) {
        if (!inited) synchronized(this) {
            if (!inited) { PDFBoxResourceLoader.init(c.applicationContext); inited = true }
        }
    }

    class Hit(val page: Int, val rects: List<RectF>, val snippet: String)
    class TocEntry(val title: String, val page: Int, val level: Int)

    /** Зняти пароль: розшифрована копія для системного рендерера. Повертає null, якщо пароль невірний. */
    fun decrypt(c: Context, src: File, password: String, out: File): Boolean {
        init(c)
        return try {
            PDDocument.load(src, password).use { d ->
                d.isAllSecurityToBeRemoved = true
                d.save(out)
            }
            true
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            false
        }
    }

    fun outline(c: Context, f: File): List<TocEntry> {
        init(c)
        if (f.length() > 150L * 1024 * 1024) return emptyList()
        return try {
            PDDocument.load(f).use { d ->
                val out = ArrayList<TocEntry>()
                val pages = d.pages
                fun walk(n: PDOutlineNode, level: Int) {
                    var item: PDOutlineItem? = n.firstChild
                    var guard = 0
                    while (item != null && guard++ < 5000 && out.size < 3000) {
                        val cur: PDOutlineItem = item
                        val page = try { cur.findDestinationPage(d)?.let { p -> pages.indexOf(p) } ?: -1 } catch (_: Throwable) { -1 }
                        val title = cur.title?.trim().orEmpty()
                        if (title.isNotEmpty() && page >= 0) out.add(TocEntry(title, page, level))
                        if (cur.hasChildren()) walk(cur, level + 1)
                        item = cur.nextSibling
                    }
                }
                d.documentCatalog?.documentOutline?.let { walk(it, 0) }
                out
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Пошук тексту з координатами збігів (пункти, початок — лівий верхній кут). */
    fun search(c: Context, f: File, query: String, progress: (Int, Int) -> Unit, cancelled: () -> Boolean): List<Hit> {
        init(c)
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        PDDocument.load(f).use { d ->
            val n = d.numberOfPages
            for (i in 0 until n) {
                if (cancelled()) break
                progress(i, n)
                val chars = StringBuilder()
                val pos = ArrayList<TextPosition?>()
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                        if (textPositions == null) return
                        for (tp in textPositions) {
                            val u = tp.unicode ?: continue
                            for (ch in u) { chars.append(ch); pos.add(tp) }
                        }
                    }

                    override fun writeWordSeparator() { chars.append(' '); pos.add(null) }
                    override fun writeLineSeparator() { chars.append(' '); pos.add(null) }
                }
                stripper.sortByPosition = true
                stripper.startPage = i + 1
                stripper.endPage = i + 1
                try { stripper.getText(d) } catch (_: Throwable) { continue }
                val text = chars.toString().lowercase()
                var from = 0
                while (hits.size < 2000) {
                    val k = text.indexOf(q, from)
                    if (k < 0) break
                    val rects = ArrayList<RectF>()
                    for (j in k until k + q.length) {
                        val tp = pos.getOrNull(j) ?: continue
                        val r = RectF(tp.xDirAdj, tp.yDirAdj - tp.heightDir * 1.15f, tp.xDirAdj + tp.widthDirAdj, tp.yDirAdj + tp.heightDir * 0.25f)
                        val last = rects.lastOrNull()
                        if (last != null && kotlin.math.abs(last.top - r.top) < 2f && r.left - last.right < tp.widthDirAdj * 2) last.union(r) else rects.add(r)
                    }
                    val s0 = maxOf(0, k - 30)
                    val s1 = minOf(chars.length, k + q.length + 50)
                    hits.add(Hit(i, rects, (if (s0 > 0) "…" else "") + chars.substring(s0, s1).trim() + if (s1 < chars.length) "…" else ""))
                    from = k + q.length
                }
            }
            progress(n, n)
        }
        return hits
    }
}
