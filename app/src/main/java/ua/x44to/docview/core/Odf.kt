package ua.x44to.docview.core

import java.io.File

/** OpenDocument: ODT (текст), ODS (таблиці), ODP (презентації) з LibreOffice. */
class Odf(private val file: File) {
    private val pkg = Pkg(file)

    private class St(
        val parent: String?,
        val r: RProps,
        val align: Align?,
        val bg: Int?,
        val fill: Int?,
        val noFill: Boolean,
        val stroke: Int?,
        val noStroke: Boolean,
        val valign: VAlign?,
        val colWidth: Float?,
        val listStyle: String?,
        val outline: Int?,
    )

    private val styles = HashMap<String, St>()

    /** Рівні стилю списку: (нумерований?, формат, суфікс, маркер). */
    private class ListLvl(val number: Boolean, val fmt: String, val suffix: String, val bullet: String)
    private val listStyles = HashMap<String, Array<ListLvl?>>()
    private var pageW = 0f
    private var pageH = 0f

    private fun readStyles(x: XmlReader, automatic: Boolean) {
        var firstLayout = true
        while (x.next() != XmlReader.T.EOF) {
            if (x.type != XmlReader.T.START) continue
            when (x.local) {
                "style", "default-style" -> {
                    val name = x.attr("name") ?: (if (x.local == "default-style") "__default_" + x.attr("family") else null) ?: continue
                    val parent = x.attr("parent-style-name")
                    val listStyle = x.attr("list-style-name")
                    val outline = x.attr("default-outline-level")?.toIntOrNull()
                    var r = RProps(); var align: Align? = null; var bg: Int? = null; var fill: Int? = null; var noFill = false
                    var stroke: Int? = null; var noStroke = false; var valign: VAlign? = null; var colW: Float? = null
                    x.each { n ->
                        when (n) {
                            "text-properties" -> {
                                x.attr("font-weight")?.let { r = r.copy(b = it == "bold" || (it.toIntOrNull() ?: 400) >= 600) }
                                x.attr("font-style")?.let { r = r.copy(i = it == "italic" || it == "oblique") }
                                x.attr("text-underline-style")?.let { r = r.copy(u = it != "none") }
                                x.attr("text-line-through-style")?.let { r = r.copy(s = it != "none") }
                                cssColor(x.attr("color"))?.let { r = r.copy(color = it) }
                                x.attr("background-color")?.let { if (it != "transparent") r = r.copy(bg = cssColor(it)) }
                                x.attr("font-size")?.let { v -> if (v.endsWith("%")) null else lengthPt(v) }?.let { r = r.copy(pt = it) }
                                x.attr("text-position")?.let { if (it.startsWith("super")) r = r.copy(sup = true) else if (it.startsWith("sub")) r = r.copy(sub = true) }
                                x.attr("font-name")?.lowercase()?.let { f -> if (listOf("courier", "mono", "consolas").any { f.contains(it) }) r = r.copy(mono = true) }
                            }
                            "paragraph-properties" -> {
                                align = when (x.attr("text-align")) { "center" -> Align.CENTER; "end", "right" -> Align.END; "justify" -> Align.JUSTIFY; "start", "left" -> Align.START; else -> null }
                                x.attr("background-color")?.let { if (it != "transparent") bg = cssColor(it) }
                            }
                            "table-cell-properties" -> { x.attr("background-color")?.let { if (it != "transparent") bg = cssColor(it) } }
                            "graphic-properties", "drawing-page-properties" -> {
                                when (x.attr("fill")) {
                                    "none" -> noFill = true
                                    "solid" -> fill = cssColor(x.attr("fill-color"))
                                    "gradient" -> fill = cssColor(x.attr("fill-color"))
                                    null -> x.attr("fill-color")?.let { fill = cssColor(it) }
                                }
                                when (x.attr("stroke")) {
                                    "none" -> noStroke = true
                                    "solid", "dash" -> stroke = cssColor(x.attr("stroke-color")) ?: 0xFF3465A4.toInt()
                                }
                                valign = when (x.attr("textarea-vertical-align")) { "middle" -> VAlign.MIDDLE; "bottom" -> VAlign.BOTTOM; "top" -> VAlign.TOP; else -> null }
                            }
                            "table-column-properties" -> colW = lengthPt(x.attr("column-width"))
                        }
                        if (n != "style") x.skip()
                    }
                    styles[name] = St(parent, r, align, bg, fill, noFill, stroke, noStroke, valign, colW, listStyle, outline)
                }
                "list-style" -> {
                    val name = x.attr("name") ?: continue
                    val lv = arrayOfNulls<ListLvl>(10)
                    x.each { n ->
                        val level = (x.attr("level")?.toIntOrNull() ?: 1) - 1
                        when (n) {
                            "list-level-style-number" -> if (level in 0..9) lv[level] = ListLvl(true, x.attr("num-format") ?: "1", x.attr("num-suffix") ?: ".", "")
                            "list-level-style-bullet" -> if (level in 0..9) lv[level] = ListLvl(false, "", "", x.attr("bullet-char")?.let { bulletFix(it) } ?: "•")
                        }
                        x.skip()
                    }
                    listStyles[name] = lv
                }
                "page-layout-properties" -> if (firstLayout && !automatic || pageW == 0f) {
                    lengthPt(x.attr("page-width"))?.let { pageW = it }
                    lengthPt(x.attr("page-height"))?.let { pageH = it }
                    if (pageW > 0) firstLayout = false
                }
                "body" -> return
            }
        }
    }

    private fun bulletFix(c: String): String = c.firstOrNull()?.let { if (it.code in 0xE000..0xF8FF) "•" else it.toString() } ?: "•"

    private fun chain(name: String?): List<St> {
        val out = ArrayList<St>()
        var cur = name
        var guard = 0
        while (cur != null && guard++ < 20) {
            val s = styles[cur] ?: break
            out.add(s)
            cur = s.parent
        }
        return out
    }

    private fun rOf(name: String?): RProps {
        var r = RProps()
        for (s in chain(name).asReversed()) r = r.over(s.r)
        return r
    }

    private fun <T> prop(name: String?, f: (St) -> T?): T? {
        for (s in chain(name)) f(s)?.let { return it }
        return null
    }

    /** Ім'я стилю → відображуване (у файлі пробіли кодуються як _20_). */
    private fun headingOf(name: String?): Int? {
        var cur = name
        var guard = 0
        while (cur != null && guard++ < 20) {
            val n = cur.replace("_20_", " ").lowercase()
            Regex("^heading (\\d)").find(n)?.let { return it.groupValues[1].toInt() }
            if (n == "title") return 1
            if (n == "subtitle") return 2
            val s = styles[cur] ?: return null
            s.outline?.let { if (it in 1..6) return it }
            cur = s.parent
        }
        return null
    }

    private fun loadStyles() {
        pkg.xml("styles.xml")?.let { readStyles(it, false) }
        pkg.xml("content.xml")?.let { readStyles(it, true) }
    }

    private fun bodyReader(): XmlReader {
        val x = pkg.xml("content.xml") ?: throw DocException("No content.xml")
        while (x.next() != XmlReader.T.EOF) {
            if (x.type == XmlReader.T.START && (x.local == "text" || x.local == "spreadsheet" || x.local == "presentation") && x.depth >= 3) return x
        }
        throw DocException("Empty document")
    }

    private fun meta(): String? = pkg.xml("meta.xml")?.let { m ->
        var t: String? = null
        while (m.next() != XmlReader.T.EOF) if (m.type == XmlReader.T.START && m.local == "title") { t = m.readText().trim(); break }
        t?.takeIf { it.isNotEmpty() }
    }

    // ───────── ODT ─────────

    private val toc = ArrayList<TocItem>()

    fun text(): FlowDoc = pkg.use {
        loadStyles()
        val x = bodyReader()
        val blocks = ArrayList<Block>()
        textBody(x, blocks, null, 0)
        FlowDoc(meta(), blocks, Resources.zip(file), FlowKind.DOCUMENT, toc, basePt = rOf("__default_paragraph").pt ?: prop("Standard") { it.r.pt } ?: 12f)
    }

    private fun textBody(x: XmlReader, out: MutableList<Block>, list: String?, level: Int) {
        x.each { n ->
            when (n) {
                "p", "h" -> paragraph(x, out, n == "h", null, level)
                "list" -> {
                    val ls = x.attr("style-name") ?: list
                    val counter = IntArray(10)
                    x.each { li ->
                        if (li == "list-item" || li == "list-header") {
                            var first = true
                            x.each { c ->
                                when (c) {
                                    "p", "h" -> {
                                        val bullet = if (first && li == "list-item") bulletFor(ls, level, counter) else null
                                        paragraph(x, out, c == "h", bullet ?: "", level + 1)
                                        first = false
                                    }
                                    "list" -> {
                                        val sub = ArrayList<Block>()
                                        // вкладений список: той самий стиль, наступний рівень
                                        nestedList(x, sub, x.attr("style-name") ?: ls, level + 1)
                                        out.addAll(sub)
                                    }
                                    "table" -> table(x, out)
                                    else -> x.skip()
                                }
                            }
                        } else x.skip()
                    }
                }
                "table" -> table(x, out)
                "section", "index-body", "table-of-content", "alphabetical-index", "illustration-index", "bibliography", "user-index", "object-index", "table-index", "text-content", "change-region" -> {}
                "index-title" -> {}
                "table-of-content-source", "sequence-decls", "variable-decls", "user-field-decls", "tracked-changes", "forms", "soft-page-break", "bookmark", "annotation" -> x.skip()
                "frame" -> frame(x, out)
                else -> x.skip()
            }
        }
    }

    private fun nestedList(x: XmlReader, out: MutableList<Block>, ls: String?, level: Int) {
        val counter = IntArray(10)
        x.each { li ->
            if (li == "list-item" || li == "list-header") {
                var first = true
                x.each { c ->
                    when (c) {
                        "p", "h" -> {
                            val bullet = if (first && li == "list-item") bulletFor(ls, level, counter) else null
                            paragraph(x, out, c == "h", bullet ?: "", level + 1)
                            first = false
                        }
                        "list" -> nestedList(x, out, x.attr("style-name") ?: ls, level + 1)
                        else -> x.skip()
                    }
                }
            } else x.skip()
        }
    }

    private fun bulletFor(ls: String?, level: Int, counter: IntArray): String {
        val lv = ls?.let { listStyles[it] }?.getOrNull(level.coerceIn(0, 9))
        if (lv == null || !lv.number) return lv?.bullet ?: "•"
        counter[level.coerceIn(0, 9)]++
        val n = counter[level.coerceIn(0, 9)]
        val num = when (lv.fmt) { "a" -> Docx.letters(n).lowercase(); "A" -> Docx.letters(n); "i" -> Docx.roman(n).lowercase(); "I" -> Docx.roman(n); "" -> ""; else -> n.toString() }
        return num + lv.suffix
    }

    private fun paragraph(x: XmlReader, out: MutableList<Block>, isH: Boolean, bullet: String?, indent: Int) {
        val style = x.attr("style-name")
        val outline = x.attr("outline-level")?.toIntOrNull()
        val base = rOf(style)
        val buf = RunBuf()
        val after = ArrayList<Block>()
        // текст безпосередньо в абзаці: readText-подібний обхід із розбором дітей
        val d = x.depth
        val stack = ArrayList<Pair<RProps, String?>>()
        stack.add(base to null)
        while (true) {
            val t = x.next()
            if (t == XmlReader.T.EOF) break
            if (t == XmlReader.T.END) {
                if (x.depth < d) break
                if (stack.size > 1) stack.removeAt(stack.lastIndex)
                continue
            }
            val (r, link) = stack.last()
            if (t == XmlReader.T.TEXT) { buf.add(r.run(x.text.replace(Regex("\\s+"), " "), link)); continue }
            when (x.local) {
                "span" -> stack.add(r.over(rOf(x.attr("style-name"))) to link)
                "a" -> stack.add(r to x.attr("href"))
                "s" -> { buf.add(r.run(" ".repeat((x.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 50)), link)); x.skip() }
                "tab" -> { buf.add(r.run("\t", link)); x.skip() }
                "line-break" -> { buf.add(r.run("\n", link)); x.skip() }
                "frame" -> frame(x, after)
                "note" -> {
                    var cit = "*"
                    x.each { nn -> if (nn == "note-citation") cit = x.readText() else x.skip() }
                    buf.add(r.copy(sup = true).run(cit, link))
                }
                "annotation", "bookmark", "bookmark-start", "bookmark-end", "reference-mark", "reference-mark-start", "reference-mark-end",
                "soft-page-break", "change-start", "change-end", "change", "toc-mark", "alphabetical-index-mark", "ruby-text" -> x.skip()
                else -> stack.add(r to link) // поле — його текст іде як є; закриття зніме зі стеку
            }
        }
        val heading = if (isH) (outline ?: headingOf(style) ?: 1).coerceIn(1, 6) else headingOf(style)?.takeIf { style?.lowercase()?.contains("heading") == true || style?.lowercase() == "title" } ?: 0
        // пробіли по краях у ODF не значущі
        val runs = buf.take().toMutableList()
        if (runs.isNotEmpty()) {
            runs[0] = runs[0].copy(runs[0].text.trimStart())
            runs[runs.lastIndex] = runs.last().copy(runs.last().text.trimEnd())
        }
        val p = Para(runs, heading, prop(style) { it.align } ?: Align.START, indent, bullet?.takeIf { it.isNotEmpty() })
        if (heading > 0 && p.text.isNotBlank()) toc.add(TocItem(p.text.trim(), out.size, heading - 1))
        out.add(p)
        out.addAll(after)
    }

    private fun frame(x: XmlReader, out: MutableList<Block>) {
        val w = lengthPt(x.attr("width"))
        val h = lengthPt(x.attr("height"))
        var done = false
        x.each { n ->
            when (n) {
                "image" -> {
                    val href = x.attr("href")
                    if (!done && href != null && !href.startsWith("http")) { out.add(Image(href.removePrefix("./"), w, h)); done = true }
                    x.skip()
                }
                "text-box" -> textBody(x, out, null, 0)
                else -> x.skip()
            }
        }
    }

    private fun table(x: XmlReader, out: MutableList<Block>) {
        val rows = ArrayList<Row>()
        x.each { n ->
            when (n) {
                "table-header-rows", "table-rows", "table-row-group" -> {}
                "table-row" -> {
                    val cells = ArrayList<Cell>()
                    x.each { c ->
                        when (c) {
                            "table-cell" -> {
                                val span = x.attr("number-columns-spanned")?.toIntOrNull() ?: 1
                                val bg = prop(x.attr("style-name")) { it.bg }
                                val content = ArrayList<Block>()
                                textBody(x, content, null, 0)
                                cells.add(Cell(content, span, bg = bg))
                            }
                            "covered-table-cell" -> x.skip()
                            else -> x.skip()
                        }
                    }
                    rows.add(Row(cells))
                }
                else -> x.skip()
            }
        }
        if (rows.isNotEmpty()) out.add(Table(rows))
    }

    // ───────── ODS ─────────

    fun sheets(): SheetDoc = pkg.use {
        loadStyles()
        val x = bodyReader()
        val out = ArrayList<Sheet>()
        var truncated = false
        x.each { n ->
            if (n != "table") { x.skip(); return@each }
            val name = x.attr("name") ?: "Sheet"
            val rows = ArrayList<Array<XCell?>>()
            val widths = ArrayList<Float>()
            val merges = ArrayList<Merge>()
            var maxCol = 0
            var r = 0
            fun row() {
                val rep = (x.attr("number-rows-repeated")?.toIntOrNull() ?: 1)
                val cells = HashMap<Int, XCell>()
                var c = 0
                x.each { cn ->
                    if (cn != "table-cell" && cn != "covered-table-cell") { x.skip(); return@each }
                    val crep = (x.attr("number-columns-repeated")?.toIntOrNull() ?: 1)
                    val cs = x.attr("number-columns-spanned")?.toIntOrNull() ?: 1
                    val rs = x.attr("number-rows-spanned")?.toIntOrNull() ?: 1
                    val type = x.attr("value-type")
                    val st = x.attr("style-name")
                    val text = if (cn == "table-cell") cellText(x) else { x.skip(); "" }
                    if (text.isNotEmpty() || prop(st) { it.bg } != null) {
                        val rp = rOf(st)
                        val style = CellStyle(rp.b == true, rp.i == true, rp.u == true, rp.color, prop(st) { it.bg }, prop(st) { it.align }, false)
                        val num = type == "float" || type == "percentage" || type == "currency" || type == "date" || type == "time"
                        for (k in 0 until crep.coerceAtMost(Xlsx.MAX_COLS)) {
                            if (c + k < Xlsx.MAX_COLS) cells[c + k] = XCell(text, num, style)
                        }
                        if ((cs > 1 || rs > 1) && r < Xlsx.MAX_ROWS) merges.add(Merge(r, c, r + rs - 1, c + cs - 1))
                    }
                    c += crep
                }
                if (cells.isNotEmpty()) {
                    val arr = arrayOfNulls<XCell>(cells.keys.max() + 1)
                    for ((k, v) in cells) arr[k] = v
                    maxCol = maxOf(maxCol, arr.size)
                    // повтори порожніх рядків не розгортаємо, а заповнених — до розумної межі
                    for (k in 0 until rep.coerceAtMost(1000)) {
                        if (r >= Xlsx.MAX_ROWS) { truncated = true; break }
                        while (rows.size < r) rows.add(arrayOfNulls(0))
                        rows.add(arr); r++
                    }
                    if (rep > 1000) r += rep - 1000
                } else r += rep
            }
            x.each { tn ->
                when (tn) {
                    "table-column" -> {
                        val rep = (x.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceAtMost(Xlsx.MAX_COLS)
                        val w = prop(x.attr("style-name")) { it.colWidth }
                        repeat(rep) { if (widths.size < Xlsx.MAX_COLS) widths.add(w?.div(5.25f) ?: -1f) }
                        x.skip()
                    }
                    "table-columns", "table-header-columns", "table-column-group", "table-header-rows", "table-rows", "table-row-group" -> {}
                    "table-row" -> row()
                    else -> x.skip()
                }
            }
            val cols = maxCol.coerceAtMost(Xlsx.MAX_COLS)
            val w = if (widths.isEmpty()) null else FloatArray(cols) { widths.getOrNull(it) ?: -1f }
            out.add(Sheet(name, rows, cols, w, merges))
        }
        SheetDoc(meta(), out, note = if (truncated) "large" else null)
    }

    private fun cellText(x: XmlReader): String {
        val sb = StringBuilder()
        x.each { n ->
            when (n) {
                "p" -> {
                    if (sb.isNotEmpty()) sb.append('\n')
                    val d = x.depth
                    while (true) {
                        val t = x.next()
                        if (t == XmlReader.T.EOF) break
                        if (t == XmlReader.T.END && x.depth < d) break
                        if (t == XmlReader.T.TEXT) sb.append(x.text)
                        if (t == XmlReader.T.START) when (x.local) {
                            "s" -> sb.append(" ".repeat((x.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 50)))
                            "tab" -> sb.append('\t')
                            "line-break" -> sb.append('\n')
                            "annotation" -> x.skip()
                        }
                    }
                }
                "annotation" -> x.skip()
                else -> x.skip()
            }
        }
        return sb.toString()
    }

    // ───────── ODP ─────────

    fun slides(): SlideDoc = pkg.use {
        loadStyles()
        val x = bodyReader()
        val slides = ArrayList<Slide>()
        x.each { n ->
            if (n != "page") { x.skip(); return@each }
            val bg = prop(x.attr("style-name")) { it.fill }
            val shapes = ArrayList<Shape>()
            var notes: String? = null
            x.each { sn ->
                when (sn) {
                    "notes" -> notes = notesText(x)
                    "forms" -> x.skip()
                    else -> drawShape(x, sn, shapes, 0f, 0f)
                }
            }
            slides.add(Slide(shapes, bg ?: 0xFFFFFFFF.toInt(), null, notes))
        }
        val w = if (pageW > 0) pageW else 793.7f
        val h = if (pageH > 0) pageH else 446.4f
        SlideDoc(meta(), w, h, slides, Resources.zip(file))
    }

    private fun notesText(x: XmlReader): String? {
        val sb = StringBuilder()
        x.each { n ->
            when (n) {
                "frame" -> if (x.attr("class") == "notes") {
                    x.each { tb -> if (tb == "text-box") x.each { p -> if (p == "p" || p == "h") { if (sb.isNotEmpty()) sb.append('\n'); sb.append(x.readText()) } else x.skip() } else x.skip() }
                } else x.skip()
                else -> x.skip()
            }
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun drawShape(x: XmlReader, n: String, out: MutableList<Shape>, dx: Float, dy: Float) {
        when (n) {
            "frame", "custom-shape", "rect", "ellipse", "circle", "line", "connector", "polygon", "path", "caption" -> {
                val px = (lengthPt(x.attr("x")) ?: 0f) + dx
                val py = (lengthPt(x.attr("y")) ?: 0f) + dy
                var w = lengthPt(x.attr("width")) ?: 0f
                var h = lengthPt(x.attr("height")) ?: 0f
                if (n == "line" || n == "connector") {
                    val x1 = lengthPt(x.attr("x1")) ?: 0f; val y1 = lengthPt(x.attr("y1")) ?: 0f
                    val x2 = lengthPt(x.attr("x2")) ?: 0f; val y2 = lengthPt(x.attr("y2")) ?: 0f
                    out.add(Shape(minOf(x1, x2) + dx, minOf(y1, y2) + dy, kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1), line = 0xFF3465A4.toInt(), geom = Geom.LINE))
                    x.skip()
                    return
                }
                val gstyle = x.attr("style-name")
                val pclass = x.attr("class")
                val pstyle = x.attr("style-name")?.let { s -> styles[s] }
                val isTitle = pclass == "title"
                val defPt = rOf(gstyle).pt ?: if (isTitle) 44f else if (pclass == "outline" || pclass == "subtitle") 28f else 18f
                var image: String? = null
                val paras = ArrayList<SPara>()
                var geom = when (n) { "ellipse", "circle" -> Geom.ELLIPSE; else -> Geom.RECT }
                var table: List<List<String>>? = null
                x.each { c ->
                    when (c) {
                        "image" -> { if (image == null) x.attr("href")?.takeIf { !it.startsWith("http") }?.let { image = it.removePrefix("./") }; x.skip() }
                        "text-box" -> shapeText(x, paras, rOf(gstyle), 0)
                        "p", "h" -> paras.add(spara(x, rOf(gstyle), 0, null))
                        "list" -> shapeList(x, paras, rOf(gstyle), 0)
                        "enhanced-geometry" -> {
                            val t = x.attr("type") ?: ""
                            if (t == "ellipse" || t == "circle") geom = Geom.ELLIPSE
                            if (t.contains("round")) geom = Geom.ROUND
                            x.skip()
                        }
                        "table" -> {
                            val rows = ArrayList<List<String>>()
                            x.each { tr -> if (tr == "table-row") { val cells = ArrayList<String>(); x.each { tc -> if (tc == "table-cell") cells.add(cellText(x)) else x.skip() }; rows.add(cells) } else if (tr != "table-rows" && tr != "table-header-rows") x.skip() }
                            table = rows
                        }
                        "object", "object-ole" -> { paras.add(SPara(listOf(Run("📊"), Run(" object", i = true)), Align.CENTER)); x.skip() }
                        else -> x.skip()
                    }
                }
                if (w <= 0f && h <= 0f) return
                // у рамок (текстові блоки) заливку беремо лише з їхнього власного стилю, а не з успадкованого «стандартного»
                val own = gstyle?.let { styles[it] }
                val fill = if (n == "frame") own?.takeIf { !it.noFill }?.fill else prop(gstyle) { if (it.noFill) 0 else it.fill }?.takeIf { it != 0 }
                val stroke = if (n == "frame") own?.takeIf { !it.noStroke }?.stroke else prop(gstyle) { if (it.noStroke) 0 else it.stroke }?.takeIf { it != 0 }
                val va = prop(gstyle) { it.valign } ?: if (n != "frame" || isTitle) VAlign.MIDDLE else VAlign.TOP
                @Suppress("UNUSED_VARIABLE") val unused = pstyle
                if (image == null && paras.all { it.text.isBlank() } && fill == null && stroke == null && table == null) return
                out.add(Shape(px, py, w, h, paras, fill, stroke, geom, image, va, 0f, table, defPt))
                if (w < 0) w = 0f
                if (h < 0) h = 0f
            }
            "g" -> x.each { c -> drawShape(x, c, out, dx, dy) }
            else -> x.skip()
        }
    }

    private fun shapeText(x: XmlReader, out: MutableList<SPara>, base: RProps, level: Int) {
        x.each { n ->
            when (n) {
                "p", "h" -> out.add(spara(x, base, level, null))
                "list" -> shapeList(x, out, base, level)
                else -> x.skip()
            }
        }
    }

    private fun shapeList(x: XmlReader, out: MutableList<SPara>, base: RProps, level: Int) {
        val ls = x.attr("style-name")
        val counter = IntArray(10)
        x.each { li ->
            if (li == "list-item" || li == "list-header") {
                x.each { c ->
                    when (c) {
                        "p", "h" -> out.add(spara(x, base, level, if (li == "list-item") bulletFor(ls, level, counter) else null))
                        "list" -> shapeList(x, out, base, level + 1)
                        else -> x.skip()
                    }
                }
            } else x.skip()
        }
    }

    private fun spara(x: XmlReader, base: RProps, level: Int, bullet: String?): SPara {
        val st = x.attr("style-name")
        val r0 = base.over(rOf(st))
        val buf = RunBuf()
        val d = x.depth
        val stack = arrayListOf(r0)
        while (true) {
            val t = x.next()
            if (t == XmlReader.T.EOF) break
            if (t == XmlReader.T.END) {
                if (x.depth < d) break
                if (stack.size > 1) stack.removeAt(stack.lastIndex)
                continue
            }
            val r = stack.last()
            if (t == XmlReader.T.TEXT) { buf.add(r.run(x.text)); continue }
            when (x.local) {
                "span" -> stack.add(r.over(rOf(x.attr("style-name"))))
                "s" -> { buf.add(r.run(" ".repeat((x.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 50)))); x.skip() }
                "tab" -> { buf.add(r.run("\t")); x.skip() }
                "line-break" -> { buf.add(r.run("\n")); x.skip() }
                else -> stack.add(r)
            }
        }
        return SPara(buf.take(), prop(st) { it.align } ?: Align.START, bullet?.takeIf { it.isNotEmpty() }, level)
    }
}
