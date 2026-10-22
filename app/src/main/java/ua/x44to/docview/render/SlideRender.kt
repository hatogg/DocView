package ua.x44to.docview.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import ua.x44to.docview.core.Align
import ua.x44to.docview.core.Geom
import ua.x44to.docview.core.Run
import ua.x44to.docview.core.Shape
import ua.x44to.docview.core.Slide
import ua.x44to.docview.core.SlideDoc
import ua.x44to.docview.core.VAlign
import kotlin.math.max
import kotlin.math.min

/** Малює слайд на звичайному Canvas — і для показу, і для збереження в PDF. */
object SlideRender {
    private fun lum(c: Int): Float = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    fun bitmap(doc: SlideDoc, s: Slide, docId: String, widthPx: Int): Bitmap {
        val h = (widthPx * doc.h / doc.w).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), h, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), doc, s, docId, widthPx.toFloat())
        return bmp
    }

    fun draw(c: Canvas, doc: SlideDoc, s: Slide, docId: String, widthPx: Float) {
        val k = widthPx / doc.w
        val bg = s.bg ?: Color.WHITE
        c.drawColor(bg)
        s.bgImage?.let { key ->
            Bitmaps.get(docId, doc.res, key, 1600)?.let { c.drawBitmap(it, null, RectF(0f, 0f, widthPx, doc.h * k), Paint(Paint.FILTER_BITMAP_FLAG)) }
        }
        for (sh in s.shapes) {
            try { shape(c, doc, sh, k, docId, bg) } catch (_: Throwable) {}
        }
    }

    private fun shape(c: Canvas, doc: SlideDoc, sh: Shape, k: Float, docId: String, slideBg: Int) {
        val r = RectF(sh.x * k, sh.y * k, (sh.x + sh.w) * k, (sh.y + sh.h) * k)
        c.save()
        if (sh.rot != 0f) c.rotate(sh.rot, r.centerX(), r.centerY())
        val radius = min(r.width(), r.height()) * 0.12f
        sh.fill?.let { col ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.FILL }
            when (sh.geom) {
                Geom.ELLIPSE -> c.drawOval(r, p)
                Geom.ROUND -> c.drawRoundRect(r, radius, radius, p)
                Geom.LINE -> {}
                Geom.RECT -> c.drawRect(r, p)
            }
        }
        sh.line?.let { col ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.STROKE; strokeWidth = max(1f, 1.2f * k) }
            when (sh.geom) {
                Geom.ELLIPSE -> c.drawOval(r, p)
                Geom.ROUND -> c.drawRoundRect(r, radius, radius, p)
                Geom.LINE -> c.drawLine(r.left, r.top, r.right, r.bottom, p)
                Geom.RECT -> c.drawRect(r, p)
            }
        }
        sh.image?.let { key ->
            val maxSide = (max(r.width(), r.height()) * 1.2f).toInt().coerceIn(64, 2048)
            Bitmaps.get(docId, doc.res, key, maxSide)?.let { c.drawBitmap(it, null, r, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)) }
        }
        val under = sh.fill ?: slideBg
        val defColor = if (lum(under) < 0.45f) Color.WHITE else Color.BLACK
        sh.table?.let { table(c, it, r, sh.defPt * k, defColor) }
        if (sh.paras.isNotEmpty()) text(c, sh, r, k, defColor)
        c.restore()
    }

    private fun table(c: Canvas, rows: List<List<String>>, r: RectF, size: Float, color: Int) {
        if (rows.isEmpty()) return
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        val cw = r.width() / cols
        val rh = r.height() / rows.size
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(140, Color.red(color), Color.green(color), Color.blue(color)); style = Paint.Style.STROKE; strokeWidth = 1f }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = min(size, rh * 0.6f).coerceAtLeast(6f) }
        for ((ri, row) in rows.withIndex()) {
            for ((ci, cell) in row.withIndex()) {
                val x = r.left + ci * cw
                val y = r.top + ri * rh
                c.drawRect(x, y, x + cw, y + rh, line)
                if (ri == 0) tp.isFakeBoldText = true else tp.isFakeBoldText = false
                val l = layout(SpannableStringBuilder(cell), tp, (cw - 8).toInt().coerceAtLeast(1), Layout.Alignment.ALIGN_NORMAL, false)
                c.save()
                c.clipRect(x, y, x + cw, y + rh)
                c.translate(x + 4, y + max(2f, (rh - l.height) / 2))
                l.draw(c)
                c.restore()
            }
        }
    }

    private fun layout(text: CharSequence, p: TextPaint, w: Int, align: Layout.Alignment, justify: Boolean): StaticLayout {
        val b = StaticLayout.Builder.obtain(text, 0, text.length, p, w).setAlignment(align).setLineSpacing(0f, 1.05f).setIncludePad(false)
        if (justify && Build.VERSION.SDK_INT >= 26) b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return b.build()
    }

    fun spans(runs: List<Run>, scalePx: Float, defPt: Float, defColor: Int, sb: SpannableStringBuilder = SpannableStringBuilder()): SpannableStringBuilder {
        for (r in runs) {
            if (r.text.isEmpty()) continue
            val s = sb.length
            sb.append(r.text)
            val e = sb.length
            val f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            sb.setSpan(AbsoluteSizeSpan(((r.pt ?: defPt) * scalePx).toInt().coerceAtLeast(4)), s, e, f)
            if (r.b && r.i) sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), s, e, f)
            else if (r.b) sb.setSpan(StyleSpan(Typeface.BOLD), s, e, f)
            else if (r.i) sb.setSpan(StyleSpan(Typeface.ITALIC), s, e, f)
            if (r.u) sb.setSpan(UnderlineSpan(), s, e, f)
            if (r.s) sb.setSpan(StrikethroughSpan(), s, e, f)
            sb.setSpan(ForegroundColorSpan(r.color ?: defColor), s, e, f)
            r.bg?.let { sb.setSpan(BackgroundColorSpan(it), s, e, f) }
            if (r.sup) sb.setSpan(SuperscriptSpan(), s, e, f)
            if (r.sub) sb.setSpan(SubscriptSpan(), s, e, f)
            if (r.mono) sb.setSpan(TypefaceSpan("monospace"), s, e, f)
        }
        return sb
    }

    private fun text(c: Canvas, sh: Shape, r: RectF, k: Float, defColor: Int) {
        val inset = sh.inset * k
        val vin = 3.6f * k
        val w = (r.width() - inset * 2).toInt().coerceAtLeast(8)
        val availH = r.height() - vin * 2
        fun build(scale: Float): List<Pair<StaticLayout, Float>> {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sh.defPt * k * scale; color = defColor }
            return sh.paras.map { p ->
                val sb = SpannableStringBuilder()
                val indent = p.level * 24f * k * scale
                if (p.bullet != null) {
                    val bs = sb.length
                    sb.append(p.bullet).append("  ")
                    val first = p.runs.firstOrNull()
                    sb.setSpan(AbsoluteSizeSpan(((first?.pt ?: sh.defPt) * k * scale).toInt().coerceAtLeast(4)), bs, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(first?.color ?: defColor), bs, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                spans(p.runs, k * scale, sh.defPt, defColor, sb)
                if (sb.isEmpty()) sb.append(" ").setSpan(AbsoluteSizeSpan((sh.defPt * k * scale * 0.8f).toInt().coerceAtLeast(4)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val al = when (p.align) { Align.CENTER -> Layout.Alignment.ALIGN_CENTER; Align.END -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
                val lw = (w - indent).toInt().coerceAtLeast(8)
                layout(sb, tp, lw, al, p.align == Align.JUSTIFY) to indent
            }
        }
        var scale = sh.autofit.coerceIn(0.2f, 1f)
        var ls = build(scale)
        var total = ls.sumOf { it.first.height }.toFloat()
        // текст не влазить — зменшуємо (як «автоприлаштування» в PowerPoint)
        var guard = 0
        while (total > availH * 1.02f && scale > 0.45f && guard++ < 8) {
            scale *= 0.88f
            ls = build(scale)
            total = ls.sumOf { it.first.height }.toFloat()
        }
        var y = r.top + vin + when (sh.anchor) {
            VAlign.MIDDLE -> max(0f, (availH - total) / 2)
            VAlign.BOTTOM -> max(0f, availH - total)
            VAlign.TOP -> 0f
        }
        for ((l, indent) in ls) {
            c.save()
            c.translate(r.left + inset + indent, y)
            l.draw(c)
            c.restore()
            y += l.height
        }
    }
}
