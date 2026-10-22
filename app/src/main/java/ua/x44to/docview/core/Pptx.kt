package ua.x44to.docview.core

import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Кольори DrawingML: схема теми + модифікатори (lumMod, lumOff, tint, shade). */
class DmlColors(val scheme: Map<String, Int>, private val map: Map<String, String> = emptyMap()) {
    fun scheme(name: String): Int? {
        val n = map[name] ?: when (name) { "bg1" -> "lt1"; "tx1" -> "dk1"; "bg2" -> "lt2"; "tx2" -> "dk2"; else -> name }
        return scheme[n]
    }

    fun withMap(m: Map<String, String>) = DmlColors(scheme, m)

    /** Читач стоїть на елементі-кольорі (srgbClr, schemeClr…); з'їдає його разом із модифікаторами. */
    fun read(x: XmlReader, ph: Int? = null): Int? {
        var c: Int? = when (x.local) {
            "srgbClr" -> hexColor(x.attr("val"))
            "schemeClr" -> if (x.attr("val") == "phClr") ph else scheme(x.attr("val") ?: "")
            "sysClr" -> hexColor(x.attr("lastClr")) ?: if (x.attr("val") == "window") 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            "prstClr" -> namedColor(x.attr("val"))
            "scrgbClr" -> {
                fun p(a: String) = ((x.attr(a)?.toFloatOrNull() ?: 0f) / 100000f * 255).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (p("r") shl 16) or (p("g") shl 8) or p("b")
            }
            "hslClr" -> null
            else -> null
        }
        var lumMod = 1.0; var lumOff = 0.0; var tint = -1.0; var shade = -1.0; var alpha = 1.0
        x.each { m ->
            val v = (x.attr("val")?.toDoubleOrNull() ?: 100000.0) / 100000.0
            when (m) {
                "lumMod" -> lumMod = v; "lumOff" -> lumOff = v; "tint" -> tint = v; "shade" -> shade = v; "alpha" -> alpha = v
            }
            x.skip()
        }
        if (c == null) return null
        if (lumMod != 1.0 || lumOff != 0.0) c = lum(c, lumMod, lumOff)
        if (tint >= 0) c = mix(c, 0xFFFFFFFF.toInt(), 1 - tint)
        if (shade >= 0) c = mix(c, 0xFF000000.toInt(), 1 - shade)
        if (alpha < 0.15) return null
        return c
    }

    companion object {
        fun mix(a: Int, b: Int, t: Double): Int {
            fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        fun lum(c: Int, mod: Double, off: Double): Int {
            val r = ((c shr 16) and 0xFF) / 255.0; val g = ((c shr 8) and 0xFF) / 255.0; val b = (c and 0xFF) / 255.0
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            var h = 0.0; var s = 0.0
            var l = (mx + mn) / 2
            if (mx != mn) {
                val d = mx - mn
                s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
                h = when (mx) { r -> (g - b) / d + (if (g < b) 6 else 0); g -> (b - r) / d + 2; else -> (r - g) / d + 4 } / 6
            }
            l = (l * mod + off).coerceIn(0.0, 1.0)
            fun hue(p: Double, q: Double, t0: Double): Double {
                var t = t0
                if (t < 0) t += 1.0
                if (t > 1) t -= 1.0
                return when { t < 1.0 / 6 -> p + (q - p) * 6 * t; t < 0.5 -> q; t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6; else -> p }
            }
            val (rr, gg, bb) = if (s == 0.0) Triple(l, l, l) else {
                val q = if (l < 0.5) l * (1 + s) else l + s - l * s
                val p = 2 * l - q
                Triple(hue(p, q, h + 1.0 / 3), hue(p, q, h), hue(p, q, h - 1.0 / 3))
            }
            return (0xFF shl 24) or ((rr * 255).toInt().coerceIn(0, 255) shl 16) or ((gg * 255).toInt().coerceIn(0, 255) shl 8) or (bb * 255).toInt().coerceIn(0, 255)
        }

        fun theme(pkg: Pkg, path: String?): Map<String, Int> {
            val out = HashMap<String, Int>()
            val x = path?.let { pkg.xml(it) } ?: return defaultScheme
            val names = setOf("dk1", "lt1", "dk2", "lt2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
            var cur: String? = null
            while (x.next() != XmlReader.T.EOF) {
                if (x.type == XmlReader.T.END && x.local == "clrScheme") break
                if (x.type != XmlReader.T.START) continue
                when {
                    x.local in names -> cur = x.local
                    cur != null && x.local == "srgbClr" -> { hexColor(x.attr("val"))?.let { out[cur!!] = it }; cur = null }
                    cur != null && x.local == "sysClr" -> { hexColor(x.attr("lastClr"))?.let { out[cur!!] = it }; cur = null }
                }
            }
            return if (out.isEmpty()) defaultScheme else defaultScheme + out
        }

        val defaultScheme = mapOf(
            "dk1" to 0xFF000000.toInt(), "lt1" to 0xFFFFFFFF.toInt(), "dk2" to 0xFF44546A.toInt(), "lt2" to 0xFFE7E6E6.toInt(),
            "accent1" to 0xFF4472C4.toInt(), "accent2" to 0xFFED7D31.toInt(), "accent3" to 0xFFA5A5A5.toInt(), "accent4" to 0xFFFFC000.toInt(),
            "accent5" to 0xFF5B9BD5.toInt(), "accent6" to 0xFF70AD47.toInt(), "hlink" to 0xFF0563C1.toInt(), "folHlink" to 0xFF954F72.toInt(),
        )
    }
}

/** PowerPoint 2007+ (.pptx, .ppsx). */
class Pptx(private val file: File) {
    private val pkg = Pkg(file)

    /** Оформлення рівня списку: розмір, маркер, вирівнювання, колір. */
    private data class Lvl(val sz: Float? = null, val bu: String? = null, val algn: Align? = null, val color: Int? = null, val b: Boolean? = null, val i: Boolean? = null) {
        fun over(o: Lvl?) = if (o == null) this else Lvl(o.sz ?: sz, o.bu ?: bu, o.algn ?: algn, o.color ?: color, o.b ?: b, o.i ?: i)
    }
    private class Levels(val l: Array<Lvl?> = arrayOfNulls(9)) {
        fun over(o: Levels?): Levels = if (o == null) this else Levels(Array(9) { k -> (l[k] ?: Lvl()).over(o.l[k]) })
        operator fun get(i: Int) = l[i.coerceIn(0, 8)]
    }

    private class Ph(val type: String, val idx: String?)
    private class PhInfo(val xfrm: FloatArray?, val levels: Levels?, val anchor: VAlign?, val insets: FloatArray?)
    private class Master(val colors: DmlColors, val title: Levels, val body: Levels, val other: Levels, val ph: List<Pair<Ph, PhInfo>>, val shapes: List<Shape>, val bg: Bg?)
    private class Layout(val ph: List<Pair<Ph, PhInfo>>, val shapes: List<Shape>, val bg: Bg?, val showMaster: Boolean)
    private class Bg(val color: Int?, val image: String?)

    private val masters = HashMap<String, Master>()
    private val layouts = HashMap<String, Pair<Layout, Master>>()

    fun parse(): SlideDoc = pkg.use {
        val pres = "ppt/presentation.xml"
        val rels = pkg.rels(pres)
        val x = pkg.xml(pres) ?: throw DocException("No presentation.xml")
        var w = 720f
        var h = 540f
        val ids = ArrayList<String>()
        while (x.next() != XmlReader.T.EOF) {
            if (x.type != XmlReader.T.START) continue
            when (x.local) {
                "sldSz" -> { w = (x.attr("cx")?.toFloatOrNull() ?: 9144000f) / EMU_PER_PT; h = (x.attr("cy")?.toFloatOrNull() ?: 6858000f) / EMU_PER_PT }
                "sldId" -> (x.attrQ("r:id") ?: x.attr("id"))?.let { ids.add(it) }
                "notesSz", "defaultTextStyle", "extLst" -> x.skip()
            }
        }
        val slides = ids.mapNotNull { id -> rels[id]?.target?.let { p -> runCatching { slide(p) }.getOrNull() } }
        val title = pkg.xml("docProps/core.xml")?.let { c ->
            var t: String? = null
            while (c.next() != XmlReader.T.EOF) if (c.type == XmlReader.T.START && c.local == "title") { t = c.readText().trim(); break }
            t?.takeIf { it.isNotEmpty() }
        }
        SlideDoc(title, w, h, slides, Resources.zip(file))
    }

    // ───────── майстер і макет ─────────

    private fun master(path: String): Master = masters.getOrPut(path) {
        val rels = pkg.rels(path)
        val themePath = rels.values.firstOrNull { it.type.endsWith("/theme") }?.target
        var colors = DmlColors(DmlColors.theme(pkg, themePath))
        val x = pkg.xml(path) ?: return@getOrPut Master(colors, Levels(), Levels(), Levels(), emptyList(), emptyList(), null)
        var title = Levels(); var body = Levels(); var other = Levels()
        var ph = emptyList<Pair<Ph, PhInfo>>()
        var shapes = emptyList<Shape>()
        var bg: Bg? = null
        // clrMap іде після cSld, а кольори потрібні раніше — тому спершу шукаємо його окремо
        pkg.xml(path)?.let { y ->
            while (y.next() != XmlReader.T.EOF) {
                if (y.type == XmlReader.T.START && y.local == "clrMap") {
                    val m = HashMap<String, String>()
                    for (i in 0 until y.attrCount) m[y.attrName(i)] = y.attrValue(i)
                    colors = colors.withMap(m)
                    break
                }
            }
        }
        while (x.next() != XmlReader.T.EOF) {
            if (x.type != XmlReader.T.START) continue
            when (x.local) {
                "cSld" -> { val r = tree(x, colors, rels, true); ph = r.first; shapes = r.second; bg = r.third }
                "titleStyle" -> title = levels(x, colors)
                "bodyStyle" -> body = levels(x, colors)
                "otherStyle" -> other = levels(x, colors)
            }
        }
        Master(colors, title, body, other, ph, shapes, bg)
    }

    private fun layout(path: String): Pair<Layout, Master> = layouts.getOrPut(path) {
        val rels = pkg.rels(path)
        val mPath = rels.values.firstOrNull { it.type.endsWith("/slideMaster") }?.target
        val m = mPath?.let { master(it) } ?: Master(DmlColors(DmlColors.defaultScheme), Levels(), Levels(), Levels(), emptyList(), emptyList(), null)
        val x = pkg.xml(path) ?: return@getOrPut Layout(emptyList(), emptyList(), null, true) to m
        var showMaster = true
        var res: Triple<List<Pair<Ph, PhInfo>>, List<Shape>, Bg?> = Triple(emptyList(), emptyList(), null)
        while (x.next() != XmlReader.T.EOF) {
            if (x.type != XmlReader.T.START) continue
            if (x.local == "sldLayout") showMaster = x.attr("showMasterSp") != "0"
            if (x.local == "cSld") { res = tree(x, m.colors, rels, true); break }
        }
        Layout(res.first, res.second, res.third, showMaster) to m
    }

    private fun levels(x: XmlReader, colors: DmlColors): Levels {
        val out = arrayOfNulls<Lvl>(9)
        x.each { n ->
            val m = Regex("^lvl(\\d)pPr$").find(n)
            if (m != null) out[m.groupValues[1].toInt() - 1] = lvl(x, colors) else x.skip()
        }
        return Levels(out)
    }

    /** a:lvlNpPr або a:pPr → Lvl. */
    private fun lvl(x: XmlReader, colors: DmlColors): Lvl {
        var l = Lvl(algn = algn(x.attr("algn")))
        x.each { n ->
            when (n) {
                "buNone" -> { l = l.copy(bu = ""); x.skip() }
                "buChar" -> { l = l.copy(bu = bulletChar(x.attr("char"))); x.skip() }
                "buAutoNum" -> { l = l.copy(bu = "#" + (x.attr("type") ?: "arabicPeriod")); x.skip() }
                "defRPr" -> {
                    l = l.copy(sz = x.attr("sz")?.toFloatOrNull()?.div(100f), b = x.attr("b")?.let { it == "1" }, i = x.attr("i")?.let { it == "1" })
                    x.each { c ->
                        if (c == "solidFill") x.each { l = l.copy(color = colors.read(x)) } else x.skip()
                    }
                }
                else -> x.skip()
            }
        }
        return l
    }

    private fun bulletChar(c: String?): String = when {
        c.isNullOrEmpty() -> "•"
        c[0].code in 0xF000..0xF0FF -> Docx.symbol(c[0].code)
        c == "§" || c == "Ø" || c == "ü" || c == "q" || c == "Ÿ" || c == "n" || c == "l" -> "•"
        else -> c
    }

    private fun algn(v: String?) = when (v) { "ctr" -> Align.CENTER; "r" -> Align.END; "just", "dist" -> Align.JUSTIFY; "l" -> Align.START; else -> null }

    // ───────── слайд ─────────

    private fun slide(path: String): Slide {
        val rels = pkg.rels(path)
        val lPath = rels.values.firstOrNull { it.type.endsWith("/slideLayout") }?.target
        val (layout, master) = lPath?.let { layout(it) } ?: (Layout(emptyList(), emptyList(), null, true) to Master(DmlColors(DmlColors.defaultScheme), Levels(), Levels(), Levels(), emptyList(), emptyList(), null))
        val ctx = Ctx(master, layout)
        val x = pkg.xml(path) ?: return Slide(emptyList())
        var showMaster = true
        var res: Triple<List<Pair<Ph, PhInfo>>, List<Shape>, Bg?> = Triple(emptyList(), emptyList(), null)
        while (x.next() != XmlReader.T.EOF) {
            if (x.type != XmlReader.T.START) continue
            if (x.local == "sld") showMaster = x.attr("showMasterSp") != "0"
            if (x.local == "cSld") { res = tree(x, master.colors, rels, false, ctx); break }
        }
        val shapes = ArrayList<Shape>()
        if (showMaster && layout.showMaster) shapes.addAll(master.shapes)
        if (showMaster) shapes.addAll(layout.shapes)
        shapes.addAll(res.second)
        val bg = res.third ?: layout.bg ?: master.bg
        val notes = rels.values.firstOrNull { it.type.endsWith("/notesSlide") }?.target?.let { notes(it) }
        return Slide(shapes, bg?.color ?: 0xFFFFFFFF.toInt(), bg?.image, notes)
    }

    private fun notes(path: String): String? {
        val x = pkg.xml(path) ?: return null
        val sb = StringBuilder()
        var inBody = false
        var depthSp = -1
        while (x.next() != XmlReader.T.EOF) {
            when (x.type) {
                XmlReader.T.START -> when (x.local) {
                    "sp" -> { depthSp = x.depth; inBody = false }
                    "ph" -> inBody = x.attr("type") == "body"
                    "t" -> if (inBody) sb.append(x.readText())
                    "p" -> if (inBody && sb.isNotEmpty()) sb.append('\n')
                }
                XmlReader.T.END -> if (x.local == "sp" && x.depth < depthSp) inBody = false
                else -> {}
            }
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    private class Ctx(val master: Master, val layout: Layout)

    private fun findPh(list: List<Pair<Ph, PhInfo>>, ph: Ph): PhInfo? =
        list.firstOrNull { (p, _) -> ph.idx != null && p.idx == ph.idx && (p.type == ph.type || ph.type == "body" || p.type == "body") }?.second
            ?: list.firstOrNull { (p, _) -> p.type == ph.type || (isBody(p.type) && isBody(ph.type) && ph.idx == null) }?.second
            ?: if (ph.type == "ctrTitle") list.firstOrNull { it.first.type == "title" }?.second else null
            ?: if (ph.type == "subTitle") list.firstOrNull { it.first.type == "body" }?.second else null

    private fun isBody(t: String) = t == "body" || t == "obj" || t == "subTitle"

    /**
     * Дерево фігур (p:spTree) і фон. Для майстра/макета ([template]) повертає також плейсхолдери,
     * а у фігури потрапляє лише «декор» без плейсхолдерів.
     */
    private fun tree(x: XmlReader, colors: DmlColors, rels: Map<String, Pkg.Rel>, template: Boolean, ctx: Ctx? = null): Triple<List<Pair<Ph, PhInfo>>, List<Shape>, Bg?> {
        val phs = ArrayList<Pair<Ph, PhInfo>>()
        val shapes = ArrayList<Shape>()
        var bg: Bg? = null
        x.each { n ->
            when (n) {
                "bg" -> bg = background(x, colors, rels)
                "spTree" -> group(x, colors, rels, template, ctx, phs, shapes, null)
                else -> x.skip()
            }
        }
        return Triple(phs, shapes, bg)
    }

    private fun background(x: XmlReader, colors: DmlColors, rels: Map<String, Pkg.Rel>): Bg? {
        var color: Int? = null
        var image: String? = null
        x.each { n ->
            when (n) {
                "bgPr" -> x.each { f ->
                    when (f) {
                        "solidFill" -> x.each { color = colors.read(x) }
                        "gradFill" -> { var first: Int? = null; x.each { g -> if (g == "gs" && first == null) x.each { first = colors.read(x) } else if (g != "gsLst") x.skip() }; color = first }
                        "blipFill" -> x.each { b -> if (b == "blip") { image = (x.attr("embed"))?.let { rels[it]?.target }; x.skip() } else if (b != "blip") x.skip() }
                        else -> x.skip()
                    }
                }
                "bgRef" -> { val ph = null as Int?; x.each { color = colors.read(x, ph) } }
                else -> x.skip()
            }
        }
        return if (color == null && image == null) null else Bg(color, image)
    }

    /** Перетворення координат групи: (off, ext, chOff, chExt). */
    private class Tx(val ox: Float, val oy: Float, val sx: Float, val sy: Float, val cx: Float, val cy: Float) {
        fun x(v: Float) = ox + (v - cx) * sx
        fun y(v: Float) = oy + (v - cy) * sy
    }

    private fun group(
        x: XmlReader, colors: DmlColors, rels: Map<String, Pkg.Rel>, template: Boolean, ctx: Ctx?,
        phs: MutableList<Pair<Ph, PhInfo>>, out: MutableList<Shape>, tx: Tx?,
    ) {
        x.each { n ->
            when (n) {
                "sp", "cxnSp" -> shape(x, colors, rels, template, ctx, phs, out, tx, n == "cxnSp")
                "pic" -> picture(x, rels, out, tx, ctx)
                "graphicFrame" -> frame(x, colors, out, tx)
                "grpSp" -> groupInner(x, colors, rels, template, ctx, phs, out, tx)
                "AlternateContent" -> {}
                "Choice" -> {}
                "Fallback" -> x.skip()
                "nvGrpSpPr", "grpSpPr", "extLst" -> x.skip()
                else -> x.skip()
            }
        }
    }

    /** Вкладена група: x стоїть на grpSp — обробляємо її як дочірню. */
    private fun groupInner(x: XmlReader, colors: DmlColors, rels: Map<String, Pkg.Rel>, template: Boolean, ctx: Ctx?, phs: MutableList<Pair<Ph, PhInfo>>, out: MutableList<Shape>, tx: Tx?) {
        // обгортаємо в той самий обробник: розбираємо grpSp так, ніби це дерево з одним елементом
        var gtx: Tx? = tx
        x.each { g ->
            when (g) {
                "grpSpPr" -> {
                    val f = xfrm(x)
                    if (f != null && f.size >= 8) {
                        val sx = if (f[6] > 0) f[2] / f[6] else 1f
                        val sy = if (f[7] > 0) f[3] / f[7] else 1f
                        val inner = Tx(f[0], f[1], sx, sy, f[4], f[5])
                        gtx = if (tx == null) inner else Tx(tx.x(inner.ox), tx.y(inner.oy), inner.sx * tx.sx, inner.sy * tx.sy, inner.cx, inner.cy)
                    }
                }
                "sp", "cxnSp" -> shape(x, colors, rels, template, ctx, phs, out, gtx, g == "cxnSp")
                "pic" -> picture(x, rels, out, gtx, ctx)
                "graphicFrame" -> frame(x, colors, out, gtx)
                "grpSp" -> groupInner(x, colors, rels, template, ctx, phs, out, gtx)
                else -> x.skip()
            }
        }
    }

    /** a:xfrm / p:xfrm → [x, y, w, h, chX, chY, chW, chH] у пунктах; rot окремо не повертаємо. */
    private var lastRot = 0f
    private fun xfrm(x: XmlReader): FloatArray? {
        var res: FloatArray? = null
        x.each { n ->
            if (n == "xfrm") {
                lastRot = (x.attr("rot")?.toFloatOrNull() ?: 0f) / 60000f
                val a = FloatArray(8)
                var have = false
                x.each { k ->
                    fun v(n2: String) = (x.attr(n2)?.toFloatOrNull() ?: 0f) / EMU_PER_PT
                    when (k) {
                        "off" -> { a[0] = v("x"); a[1] = v("y"); have = true }
                        "ext" -> { a[2] = v("cx"); a[3] = v("cy") }
                        "chOff" -> { a[4] = v("x"); a[5] = v("y") }
                        "chExt" -> { a[6] = v("cx"); a[7] = v("cy") }
                    }
                    x.skip()
                }
                if (have) res = a
            } else x.skip()
        }
        return res
    }

    private fun place(f: FloatArray, tx: Tx?): FloatArray =
        if (tx == null) f else floatArrayOf(tx.x(f[0]), tx.y(f[1]), f[2] * tx.sx, f[3] * tx.sy)

    private fun shape(
        x: XmlReader, colors: DmlColors, rels: Map<String, Pkg.Rel>, template: Boolean, ctx: Ctx?,
        phs: MutableList<Pair<Ph, PhInfo>>, out: MutableList<Shape>, tx: Tx?, connector: Boolean,
    ) {
        var ph: Ph? = null
        var xf: FloatArray? = null
        var rot = 0f
        var fill: Int? = null
        var noFill = false
        var line: Int? = null
        var noLine = false
        var geom = if (connector) Geom.LINE else Geom.RECT
        var styleFill: Int? = null
        var styleLine: Int? = null
        var styleFont: Int? = null
        var anchor: VAlign? = null
        var insets: FloatArray? = null
        var fontScale = 1f
        var lst: Levels? = null
        val paras = ArrayList<Triple<Lvl, Int, List<Run>>>() // (pPr, рівень, шматки)
        var hidden = false
        x.each { n ->
            when (n) {
                "nvSpPr", "nvCxnSpPr" -> x.each { nv ->
                    when (nv) {
                        "cNvPr" -> { hidden = x.attr("hidden") == "1"; x.skip() }
                        "ph" -> { ph = Ph(x.attr("type") ?: "body", x.attr("idx")); x.skip() }
                        "nvPr", "cNvSpPr", "cNvCxnSpPr" -> {}
                        else -> x.skip()
                    }
                }
                "spPr" -> x.each { sp ->
                    when (sp) {
                        "xfrm" -> {
                            rot = (x.attr("rot")?.toFloatOrNull() ?: 0f) / 60000f
                            val a = FloatArray(4)
                            x.each { k ->
                                fun v(n2: String) = (x.attr(n2)?.toFloatOrNull() ?: 0f) / EMU_PER_PT
                                when (k) { "off" -> { a[0] = v("x"); a[1] = v("y") }; "ext" -> { a[2] = v("cx"); a[3] = v("cy") } }
                                x.skip()
                            }
                            xf = a
                        }
                        "prstGeom" -> {
                            geom = when (x.attr("prst")) {
                                "ellipse", "circle", "donut", "pie", "chord" -> Geom.ELLIPSE
                                "roundRect", "round1Rect", "round2SameRect", "snipRoundRect", "flowChartAlternateProcess", "wedgeRoundRectCallout" -> Geom.ROUND
                                "line", "straightConnector1", "bentConnector3", "curvedConnector3" -> Geom.LINE
                                else -> if (connector) Geom.LINE else Geom.RECT
                            }
                            x.skip()
                        }
                        "solidFill" -> x.each { fill = colors.read(x) }
                        "gradFill" -> { var first: Int? = null; x.each { g -> if (g == "gs" && first == null) x.each { first = colors.read(x) } else if (g != "gsLst") x.skip() }; fill = first }
                        "noFill" -> { noFill = true; x.skip() }
                        "ln" -> x.each { l ->
                            when (l) {
                                "solidFill" -> x.each { line = colors.read(x) }
                                "noFill" -> { noLine = true; x.skip() }
                                else -> x.skip()
                            }
                        }
                        else -> x.skip()
                    }
                }
                "style" -> x.each { st ->
                    when (st) {
                        "fillRef" -> { val idx = x.attr("idx")?.toIntOrNull() ?: 0; var c: Int? = null; x.each { c = colors.read(x) }; if (idx > 0) styleFill = c }
                        "lnRef" -> { val idx = x.attr("idx")?.toIntOrNull() ?: 0; var c: Int? = null; x.each { c = colors.read(x) }; if (idx > 0) styleLine = c }
                        "fontRef" -> { var c: Int? = null; x.each { c = colors.read(x) }; styleFont = c }
                        else -> x.skip()
                    }
                }
                "txBody" -> x.each { tb ->
                    when (tb) {
                        "bodyPr" -> {
                            anchor = when (x.attr("anchor")) { "ctr" -> VAlign.MIDDLE; "b" -> VAlign.BOTTOM; "t" -> VAlign.TOP; else -> null }
                            fun ins(a: String, d: Float) = x.attr(a)?.toFloatOrNull()?.div(EMU_PER_PT) ?: d
                            if (x.attr("lIns") != null || x.attr("tIns") != null) insets = floatArrayOf(ins("lIns", 7.2f), ins("tIns", 3.6f), ins("rIns", 7.2f), ins("bIns", 3.6f))
                            x.each { bp ->
                                if (bp == "normAutofit") fontScale = (x.attr("fontScale")?.toFloatOrNull() ?: 100000f) / 100000f
                                x.skip()
                            }
                        }
                        "lstStyle" -> lst = levels(x, colors)
                        "p" -> paras.add(paragraph(x, colors, rels))
                        else -> x.skip()
                    }
                }
                else -> x.skip()
            }
        }
        if (hidden) return
        val p = ph
        if (template && p != null) {
            // плейсхолдер майстра/макета: запам'ятовуємо позицію й стилі, але не малюємо
            phs.add(p to PhInfo(xf, lst, anchor, insets))
            return
        }
        // успадкування з макета й майстра
        var lvls = Levels()
        var anc = anchor
        var ins = insets
        var frame = xf
        if (p != null && ctx != null) {
            val fromMaster = findPh(ctx.master.ph, p)
            val fromLayout = findPh(ctx.layout.ph, p)
            val base = when {
                p.type == "title" || p.type == "ctrTitle" -> ctx.master.title
                isBody(p.type) -> ctx.master.body
                else -> ctx.master.other
            }
            lvls = base.over(fromMaster?.levels).over(fromLayout?.levels)
            if (frame == null) frame = fromLayout?.xfrm ?: fromMaster?.xfrm
            if (anc == null) anc = fromLayout?.anchor ?: fromMaster?.anchor
            if (ins == null) ins = fromLayout?.insets ?: fromMaster?.insets
        } else if (ctx != null) {
            lvls = ctx.master.other
        }
        val baseLvls = lvls
        lvls = lvls.over(lst)
        val f = frame ?: return
        val pos = place(f, tx)
        val isTitle = p?.type == "title" || p?.type == "ctrTitle"
        val defPt = lvls[0]?.sz ?: if (isTitle) 44f else if (p != null) 28f else 18f
        val counters = IntArray(9)
        val sparas = paras.map { (ppr, lvl, runs) ->
            val L = (lvls[lvl] ?: Lvl()).over(ppr)
            // колір: шматок → абзац → стиль фігури (lstStyle) → fontRef фігури → майстер
            val color = ppr.color ?: lst?.get(lvl)?.color ?: (if (p == null) styleFont else null) ?: baseLvls[lvl]?.color ?: styleFont
            val rr = runs.map { r ->
                Run(r.text, r.b || L.b == true, r.i || L.i == true, r.u, r.s, r.color ?: color, r.bg, r.pt ?: L.sz ?: defPt, r.link, r.sup, r.sub, r.mono, r.caps)
            }
            val hasText = rr.any { it.text.isNotBlank() }
            val bu = L.bu
            val bullet = when {
                !hasText || bu == null || bu == "" -> { counters[lvl] = 0; null }
                bu.startsWith("#") -> { counters[lvl]++; autoNum(bu.drop(1), counters[lvl]) }
                else -> bu
            }
            SPara(rr, L.algn ?: if (isTitle && anchor == null) Align.START else Align.START, bullet, lvl)
        }
        val shapeFill = if (noFill) null else fill ?: styleFill
        val shapeLine = if (noLine) null else line ?: if (geom == Geom.LINE) (styleLine ?: 0xFF000000.toInt()) else styleLine
        // порожній плейсхолдер без тексту на слайді не малюємо (у PowerPoint там підказка «Клацніть…»)
        if (sparas.all { it.text.isBlank() } && shapeFill == null && shapeLine == null) return
        out.add(
            Shape(
                pos[0], pos[1], pos[2], pos[3], sparas, shapeFill, shapeLine, geom, null,
                anc ?: if (isTitle) VAlign.MIDDLE else VAlign.TOP, rot, null, defPt,
                ins?.get(0) ?: 7.2f, fontScale,
            ),
        )
    }

    private fun autoNum(type: String, n: Int): String = when {
        type.startsWith("alphaLc") -> Docx.letters(n).lowercase() + suffix(type)
        type.startsWith("alphaUc") -> Docx.letters(n) + suffix(type)
        type.startsWith("romanLc") -> Docx.roman(n).lowercase() + suffix(type)
        type.startsWith("romanUc") -> Docx.roman(n) + suffix(type)
        else -> "$n" + suffix(type)
    }

    private fun suffix(t: String) = when {
        t.endsWith("ParenR") -> ")"; t.endsWith("ParenBoth") -> ")"; t.endsWith("Plain") -> ""; else -> "."
    }

    private fun paragraph(x: XmlReader, colors: DmlColors, rels: Map<String, Pkg.Rel>): Triple<Lvl, Int, List<Run>> {
        var ppr = Lvl()
        var lvl = 0
        val buf = RunBuf()
        x.each { n ->
            when (n) {
                "pPr" -> { lvl = x.attr("lvl")?.toIntOrNull() ?: 0; ppr = lvl(x, colors) }
                "r", "fld" -> {
                    var b = false; var i = false; var u = false; var s = false; var sz: Float? = null; var c: Int? = null; var link: String? = null
                    var sup = false; var sub = false; var caps = false
                    var text = ""
                    x.each { rn ->
                        when (rn) {
                            "rPr" -> {
                                b = x.attr("b") == "1" || x.attr("b") == "true"
                                i = x.attr("i") == "1" || x.attr("i") == "true"
                                u = x.attr("u").let { it != null && it != "none" }
                                s = x.attr("strike").let { it != null && it != "noStrike" }
                                sz = x.attr("sz")?.toFloatOrNull()?.div(100f)
                                caps = x.attr("cap") == "all"
                                x.attr("baseline")?.toIntOrNull()?.let { if (it > 0) sup = true else if (it < 0) sub = true }
                                x.each { rp ->
                                    when (rp) {
                                        "solidFill" -> x.each { c = colors.read(x) }
                                        "hlinkClick" -> { link = (x.attrQ("r:id") ?: x.attr("id"))?.let { rels[it]?.target }; x.skip() }
                                        else -> x.skip()
                                    }
                                }
                            }
                            "t" -> text = x.readText()
                            else -> x.skip()
                        }
                    }
                    buf.add(Run(if (caps) text.uppercase() else text, b, i, u || link != null, s, c, null, sz, link, sup, sub))
                }
                "br" -> { buf.add(Run("\n")); x.skip() }
                else -> x.skip()
            }
        }
        return Triple(ppr, lvl, buf.take())
    }

    private fun picture(x: XmlReader, rels: Map<String, Pkg.Rel>, out: MutableList<Shape>, tx: Tx?, ctx: Ctx?) {
        var img: String? = null
        var xf: FloatArray? = null
        var rot = 0f
        var ph: Ph? = null
        x.each { n ->
            when (n) {
                "nvPicPr" -> x.each { nv -> if (nv == "ph") { ph = Ph(x.attr("type") ?: "pic", x.attr("idx")); x.skip() } else if (nv != "nvPr") x.skip() }
                "blipFill" -> x.each { b -> if (b == "blip") { img = (x.attr("embed") ?: x.attr("link"))?.let { rels[it] }?.takeIf { !it.external }?.target; x.skip() } else x.skip() }
                "spPr" -> x.each { sp ->
                    if (sp == "xfrm") {
                        rot = (x.attr("rot")?.toFloatOrNull() ?: 0f) / 60000f
                        val a = FloatArray(4)
                        x.each { k ->
                            fun v(n2: String) = (x.attr(n2)?.toFloatOrNull() ?: 0f) / EMU_PER_PT
                            when (k) { "off" -> { a[0] = v("x"); a[1] = v("y") }; "ext" -> { a[2] = v("cx"); a[3] = v("cy") } }
                            x.skip()
                        }
                        xf = a
                    } else x.skip()
                }
                else -> x.skip()
            }
        }
        val p = ph
        if (xf == null && p != null && ctx != null) xf = findPh(ctx.layout.ph, p)?.xfrm ?: findPh(ctx.master.ph, p)?.xfrm
        val f = xf ?: return
        val i = img ?: return
        val pos = place(f, tx)
        out.add(Shape(pos[0], pos[1], pos[2], pos[3], image = i, rot = rot))
    }

    private fun frame(x: XmlReader, colors: DmlColors, out: MutableList<Shape>, tx: Tx?) {
        var xf: FloatArray? = null
        var table: ArrayList<List<String>>? = null
        var chart = false
        x.each { n ->
            when (n) {
                "xfrm" -> {
                    val a = FloatArray(4)
                    x.each { k ->
                        fun v(n2: String) = (x.attr(n2)?.toFloatOrNull() ?: 0f) / EMU_PER_PT
                        when (k) { "off" -> { a[0] = v("x"); a[1] = v("y") }; "ext" -> { a[2] = v("cx"); a[3] = v("cy") } }
                        x.skip()
                    }
                    xf = a
                }
                "graphic", "graphicData" -> {}
                "tbl" -> {
                    val rows = ArrayList<List<String>>()
                    x.each { tr ->
                        if (tr == "tr") {
                            val cells = ArrayList<String>()
                            x.each { tc -> if (tc == "tc") cells.add(paraTexts(x)) else x.skip() }
                            rows.add(cells)
                        } else x.skip()
                    }
                    table = rows
                }
                "chart" -> { chart = true; x.skip() }
                else -> x.skip()
            }
        }
        val f = xf ?: return
        val pos = place(f, tx)
        val t = table
        if (t != null) out.add(Shape(pos[0], pos[1], pos[2], pos[3], table = t, defPt = 14f))
        else if (chart) out.add(Shape(pos[0], pos[1], pos[2], pos[3], listOf(SPara(listOf(Run("📊"), Run(" chart", i = true)), Align.CENTER)), line = 0xFFBDBDBD.toInt(), anchor = VAlign.MIDDLE))
        @Suppress("UNUSED_VARIABLE") val unused = colors
    }

    private fun paraTexts(x: XmlReader): String {
        val sb = StringBuilder()
        x.each { n ->
            when (n) {
                "t" -> sb.append(x.readText())
                "p" -> if (sb.isNotEmpty()) sb.append('\n')
                "tcPr", "rPr", "pPr", "bodyPr", "lstStyle" -> x.skip()
            }
        }
        return sb.toString()
    }
}
