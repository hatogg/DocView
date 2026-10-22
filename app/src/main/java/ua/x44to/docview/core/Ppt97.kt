package ua.x44to.docview.core

import java.io.File

/**
 * PowerPoint 97–2003 (.ppt). Через каталог збережень (persist directory) знаходимо слайди в порядку показу,
 * з їхніх малюнків (OfficeArt) — позиції фігур, заливки, картинки, а текст — зі SlideListWithText і текстових полів.
 */
class Ppt97(private val file: File) {
    private lateinit var b: ByteArray
    private var pics: ByteArray? = null
    private val persist = HashMap<Int, Int>()
    private val blips = ArrayList<Int>() // зсуви в потоці Pictures (або -1)
    private var scheme: IntArray? = null
    private var slideW = 720f
    private var slideH = 540f

    private class Hdr(val ver: Int, val inst: Int, val type: Int, val len: Int, val off: Int) {
        val data get() = off + 8
        val end get() = off + 8 + len
        val container get() = ver == 0xF
    }

    private fun hdr(o: Int): Hdr? {
        if (o < 0 || o + 8 > b.size) return null
        val vi = le16(b, o)
        val len = le32(b, o + 4)
        if (len < 0 || o + 8 + len > b.size) return null
        return Hdr(vi and 0xF, vi ushr 4, le16(b, o + 2), len, o)
    }

    private inline fun children(h: Hdr, f: (Hdr) -> Unit) {
        var p = h.data
        var guard = 0
        while (p + 8 <= h.end && guard++ < 100_000) {
            val c = hdr(p) ?: return
            f(c)
            p = c.end
        }
    }

    private class TextItem(val type: Int, val text: String)
    private class SlideRef(val persistId: Int, val texts: ArrayList<TextItem> = ArrayList())

    fun parse(): SlideDoc {
        val cfb = Cfb.open(file)
        b = cfb.stream("PowerPoint Document") ?: throw DocException("No PowerPoint Document")
        if (cfb.has("EncryptedSummary")) throw DocException("Encrypted", needsPassword = true)
        pics = cfb.stream("Pictures")
        val cu = cfb.stream("Current User")
        var edit = if (cu != null && cu.size >= 20) le32(cu, 16) else -1
        if (edit <= 0 || edit >= b.size) edit = findLastUserEdit()
        var docRef = -1
        var guard = 0
        while (edit > 0 && guard++ < 1000) {
            val h = hdr(edit) ?: break
            if (h.type != 0x0FF5) break
            val lastEdit = le32(b, h.data + 8)
            val dirOff = le32(b, h.data + 12)
            if (docRef < 0) docRef = le32(b, h.data + 16)
            readPersistDir(dirOff)
            if (lastEdit == 0 || lastEdit == edit) break
            edit = lastEdit
        }
        val docOff = persist[docRef] ?: findRecord(0x03E8) ?: throw DocException("No document")
        val doc = hdr(docOff) ?: throw DocException("No document")
        val slides = ArrayList<SlideRef>()
        var masterRefs = ArrayList<Int>()
        children(doc) { c ->
            when (c.type) {
                0x03E9 -> { slideW = le32(b, c.data) / 8f; slideH = le32(b, c.data + 4) / 8f }
                0x0FF0 -> {
                    var cur: SlideRef? = null
                    var type = 4
                    children(c) { a ->
                        when (a.type) {
                            0x03F3 -> {
                                val ref = SlideRef(le32(b, a.data))
                                if (c.inst == 0) slides.add(ref) else if (c.inst == 1) masterRefs.add(ref.persistId)
                                cur = ref
                            }
                            0x0F9F -> type = le32(b, a.data)
                            0x0FA0 -> cur?.texts?.add(TextItem(type, String(b, a.data, a.len, Charsets.UTF_16LE)))
                            0x0FA8 -> cur?.texts?.add(TextItem(type, String(b, a.data, a.len, Charsets.ISO_8859_1)))
                        }
                    }
                }
                0x040B -> readBStore(c)
            }
        }
        // кольорова схема головного майстра: фон, текст, заголовок
        masterRefs.firstOrNull()?.let { persist[it] }?.let { mo -> hdr(mo)?.let { m -> findScheme(m) } }
        val out = slides.mapNotNull { s -> runCatching { slide(s) }.getOrNull() }
        if (out.isEmpty() && slides.isEmpty()) throw DocException("No slides")
        @Suppress("UNUSED_VALUE") masterRefs = ArrayList()
        return SlideDoc(Ole.summaryTitle(cfb), slideW, slideH, out, Resources { key -> key.removePrefix("blip:").toIntOrNull()?.let { blip(it) } })
    }

    private fun findLastUserEdit(): Int {
        var p = 0
        var last = -1
        while (p + 8 <= b.size) {
            val h = hdr(p) ?: break
            if (h.type == 0x0FF5) last = p
            p = if (h.container) h.data else h.end
        }
        return last
    }

    private fun findRecord(type: Int): Int? {
        var p = 0
        while (p + 8 <= b.size) {
            val h = hdr(p) ?: return null
            if (h.type == type) return p
            p = h.end
        }
        return null
    }

    private fun readPersistDir(off: Int) {
        val h = hdr(off) ?: return
        if (h.type != 0x1772) return
        var p = h.data
        while (p + 4 <= h.end) {
            val x = le32(b, p)
            val id = x and 0xFFFFF
            val n = x ushr 20
            p += 4
            for (k in 0 until n) {
                if (p + 4 > h.end) break
                persist.putIfAbsent(id + k, le32(b, p))
                p += 4
            }
        }
    }

    private fun readBStore(h: Hdr) {
        // DrawingGroup → OfficeArtDggContainer → BStoreContainer → FBSE
        fun walk(x: Hdr) {
            children(x) { c ->
                when {
                    c.type == 0xF007 -> blips.add(le32(b, c.data + 28))
                    c.container -> walk(c)
                }
            }
        }
        walk(h)
    }

    private fun findScheme(h: Hdr) {
        children(h) { c ->
            if (c.type == 0x07F0 && c.len >= 32 && scheme == null) {
                scheme = IntArray(8) { i ->
                    val o = c.data + i * 4
                    (0xFF shl 24) or ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)
                }
            } else if (c.container && scheme == null) findScheme(c)
        }
    }

    private fun color(v: Int, localScheme: IntArray?): Int? {
        val flags = (v ushr 24) and 0xFF
        if (flags and 0x08 != 0) return (localScheme ?: scheme)?.getOrNull(v and 0xFF)
        if (flags and 0x10 != 0) return null // системний колір
        return (0xFF shl 24) or ((v and 0xFF) shl 16) or (v and 0xFF00) or ((v shr 16) and 0xFF)
    }

    private fun blip(index: Int): ByteArray? {
        val off = blips.getOrNull(index - 1) ?: return null
        val p = pics ?: return null
        if (off < 0 || off + 8 > p.size) return null
        val len = le32(p, off + 4)
        val type = le16(p, off + 2)
        val end = minOf(p.size, off + 8 + len)
        if (type == 0xF01F) return dib(p, off, end)
        return Ole.findImage(p, off + 8, end)
    }

    /** DIB без заголовка файлу → BMP. */
    private fun dib(p: ByteArray, off: Int, end: Int): ByteArray? {
        val inst = le16(p, off) ushr 4
        val start = off + 8 + (if (inst == 0x7A9) 32 else 16) + 1
        if (start + 40 > end) return null
        val hsz = le32(p, start)
        val bits = le16(p, start + 14)
        val clr = le32(p, start + 32).let { if (it == 0 && bits <= 8) 1 shl bits else it }
        val dataOff = 14 + hsz + clr * 4
        val total = 14 + (end - start)
        val out = java.io.ByteArrayOutputStream()
        out.write('B'.code); out.write('M'.code)
        for (v in intArrayOf(total, 0, dataOff)) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        out.write(p, start, end - start)
        return out.toByteArray()
    }

    // ───────── слайд ─────────

    private class Anchor(val l: Float, val t: Float, val r: Float, val bm: Float)
    private class GroupTx(val src: Anchor, val dst: Anchor) {
        fun map(a: Anchor): Anchor {
            val sx = if (src.r - src.l != 0f) (dst.r - dst.l) / (src.r - src.l) else 1f
            val sy = if (src.bm - src.t != 0f) (dst.bm - dst.t) / (src.bm - src.t) else 1f
            return Anchor(dst.l + (a.l - src.l) * sx, dst.t + (a.t - src.t) * sy, dst.l + (a.r - src.l) * sx, dst.t + (a.bm - src.t) * sy)
        }
    }

    private fun slide(ref: SlideRef): Slide {
        val off = persist[ref.persistId] ?: return fallback(ref)
        val sh = hdr(off) ?: return fallback(ref)
        var localScheme: IntArray? = null
        var drawing: Hdr? = null
        children(sh) { c ->
            when (c.type) {
                0x040C -> drawing = c
                0x07F0 -> if (c.len >= 32) localScheme = IntArray(8) { i ->
                    val o = c.data + i * 4
                    (0xFF shl 24) or ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)
                }
            }
        }
        val sch = localScheme ?: scheme
        val shapes = ArrayList<Shape>()
        var bg: Int? = sch?.get(0)
        val usedText = HashSet<Int>()
        drawing?.let { d ->
            fun walkGroup(g: Hdr, tx: GroupTx?) {
                var first = true
                var groupTx = tx
                children(g) { c ->
                    when (c.type) {
                        0xF003 -> walkGroup(c, groupTx)
                        0xF004 -> {
                            val s = spContainer(c, ref, sch, usedText, groupTx)
                            if (first && s.groupRect != null && s.anchor != null) {
                                // перша фігура групи задає її систему координат
                                val dst = groupTx?.map(s.anchor) ?: s.anchor
                                groupTx = GroupTx(s.groupRect, dst)
                            }
                            first = false
                            if (s.background) { s.fill?.let { bg = it } } else s.shape?.let { shapes.add(it) }
                        }
                    }
                }
            }
            fun walk(x: Hdr) {
                children(x) { c ->
                    when (c.type) {
                        0xF002 -> walk(c)
                        0xF003 -> walkGroup(c, null)
                        0xF004 -> { val s = spContainer(c, ref, sch, usedText, null); if (s.background) s.fill?.let { bg = it } }
                    }
                }
            }
            walk(d)
        }
        // текст, на який не посилається жодна фігура (буває, що малюнок порожній) — простою розкладкою
        val rest = ref.texts.withIndex().filter { it.index !in usedText }
        if (rest.isNotEmpty() && shapes.none { it.paras.isNotEmpty() }) {
            var y = slideH * 0.08f
            for ((_, t) in rest) {
                val title = t.type == 0 || t.type == 6
                val h = if (title) slideH * 0.18f else slideH * 0.6f
                shapes.add(Shape(slideW * 0.07f, y, slideW * 0.86f, h, paras(t, sch), anchor = if (title) VAlign.MIDDLE else VAlign.TOP, defPt = defPt(t.type)))
                y += h + slideH * 0.03f
            }
        }
        return Slide(shapes, bg ?: 0xFFFFFFFF.toInt())
    }

    private fun fallback(ref: SlideRef): Slide {
        val shapes = ArrayList<Shape>()
        var y = slideH * 0.08f
        for (t in ref.texts) {
            val title = t.type == 0 || t.type == 6
            val h = if (title) slideH * 0.18f else slideH * 0.6f
            shapes.add(Shape(slideW * 0.07f, y, slideW * 0.86f, h, paras(t, scheme), anchor = if (title) VAlign.MIDDLE else VAlign.TOP, defPt = defPt(t.type)))
            y += h + slideH * 0.03f
        }
        return Slide(shapes, scheme?.get(0) ?: 0xFFFFFFFF.toInt())
    }

    private fun defPt(type: Int) = when (type) { 0 -> 40f; 6 -> 44f; 1 -> 28f; 5 -> 28f; 7 -> 24f; 8 -> 20f; else -> 18f }

    private fun paras(t: TextItem, sch: IntArray?): List<SPara> {
        val title = t.type == 0 || t.type == 6
        val body = t.type == 1 || t.type == 7 || t.type == 8
        val color = if (title) sch?.get(3) else sch?.get(1)
        return t.text.replace('\u000B', '\n').split('\r').map { line ->
            val clean = line.replace(Regex("[\\u0000-\\u0008\\u000E-\\u001F]"), "")
            SPara(
                listOf(Run(clean, color = color)),
                if (title || t.type == 5) Align.CENTER else Align.START,
                if (body && clean.isNotBlank()) "•" else null,
            )
        }
    }

    private class SpResult(val shape: Shape?, val background: Boolean, val fill: Int?, val anchor: Anchor?, val groupRect: Anchor?)

    private fun spContainer(c: Hdr, ref: SlideRef, sch: IntArray?, used: MutableSet<Int>, tx: GroupTx?): SpResult {
        var spType = 0
        var flags = 0
        var anchor: Anchor? = null
        var groupRect: Anchor? = null
        var fill: Int? = null
        var filled: Boolean? = null
        var lineColor: Int? = null
        var lined: Boolean? = null
        var pib = -1
        var text: TextItem? = null
        var textIdx = -1
        children(c) { r ->
            when (r.type) {
                0xF00A -> { spType = r.inst; flags = le32(b, r.data + 4) }
                0xF009 -> groupRect = Anchor(le32(b, r.data) / 8f, le32(b, r.data + 4) / 8f, le32(b, r.data + 8) / 8f, le32(b, r.data + 12) / 8f)
                0xF00B, 0xF122 -> {
                    val n = r.inst
                    for (i in 0 until n) {
                        val p = r.data + i * 6
                        if (p + 6 > r.end) break
                        val id = le16(b, p) and 0x3FFF
                        val v = le32(b, p + 2)
                        when (id) {
                            0x0181 -> fill = color(v, sch)
                            0x01BF -> if (v and 0x100000 != 0) filled = v and 0x10 != 0
                            0x01C0 -> lineColor = color(v, sch)
                            0x01FF -> if (v and 0x080000 != 0) lined = v and 0x08 != 0
                            0x0104 -> pib = v
                        }
                    }
                }
                0xF010 -> anchor = if (r.len >= 16) Anchor(le32(b, r.data + 4) / 8f, le32(b, r.data) / 8f, le32(b, r.data + 8) / 8f, le32(b, r.data + 12) / 8f)
                else Anchor(s16(r.data + 2) / 8f, s16(r.data) / 8f, s16(r.data + 4) / 8f, s16(r.data + 6) / 8f)
                0xF00F -> anchor = Anchor(le32(b, r.data) / 8f, le32(b, r.data + 4) / 8f, le32(b, r.data + 8) / 8f, le32(b, r.data + 12) / 8f)
                0xF00D -> {
                    var type = 4
                    children(r) { a ->
                        when (a.type) {
                            0x0F9E -> textIdx = le32(b, a.data)
                            0x0F9F -> type = le32(b, a.data)
                            0x0FA0 -> text = TextItem(type, String(b, a.data, a.len, Charsets.UTF_16LE))
                            0x0FA8 -> text = TextItem(type, String(b, a.data, a.len, Charsets.ISO_8859_1))
                        }
                    }
                }
            }
        }
        val background = flags and 0x400 != 0
        if (background) return SpResult(null, true, if (filled != false) fill else null, null, null)
        if (flags and 0x01 != 0 && groupRect != null) return SpResult(null, false, null, anchor, groupRect) // сама група
        val a0 = anchor ?: return SpResult(null, false, null, null, groupRect)
        val a = tx?.map(a0) ?: a0
        if (textIdx >= 0) {
            text = ref.texts.getOrNull(textIdx)
            used.add(textIdx)
        }
        val t = text
        val paras = t?.let { paras(it, sch) } ?: emptyList()
        val geom = when (spType) { 2 -> Geom.ROUND; 3 -> Geom.ELLIPSE; 20, 32, 33, 34, 38 -> Geom.LINE; else -> Geom.RECT }
        val isPic = spType == 75 || pib > 0
        val shapeFill = if (filled == true || (filled == null && spType != 202 && spType != 75 && t == null && !isPic)) fill else null
        val shapeLine = if (lined == true || geom == Geom.LINE) (lineColor ?: 0xFF000000.toInt()) else null
        if (!isPic && paras.all { it.text.isBlank() } && shapeFill == null && shapeLine == null) return SpResult(null, false, null, anchor, groupRect)
        val shape = Shape(
            a.l, a.t, a.r - a.l, a.bm - a.t, paras, shapeFill, shapeLine, geom,
            if (isPic && pib > 0) "blip:$pib" else null,
            if (t != null && (t.type == 0 || t.type == 6)) VAlign.MIDDLE else VAlign.TOP,
            0f, null, t?.let { defPt(it.type) } ?: 18f,
        )
        return SpResult(shape, false, null, anchor, groupRect)
    }

    private fun s16(o: Int): Int = le16(b, o).toShort().toInt()
}
