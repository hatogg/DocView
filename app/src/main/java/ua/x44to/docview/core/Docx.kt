package ua.x44to.docview.core

import java.io.File

/** Збирач рядка: зливає сусідні шматки з однаковим оформленням. */
class RunBuf {
    val runs = ArrayList<Run>()
    fun add(r: Run) {
        if (r.text.isEmpty()) return
        val last = runs.lastOrNull()
        if (last != null && last.same(r)) runs[runs.lastIndex] = last.copy(last.text + r.text) else runs.add(r)
    }
    fun isEmpty() = runs.isEmpty()
    fun take(): List<Run> = ArrayList(runs).also { runs.clear() }
}

/** Оформлення символів (розгорнуте з усіх рівнів стилів). */
data class RProps(
    val b: Boolean? = null, val i: Boolean? = null, val u: Boolean? = null, val s: Boolean? = null,
    val color: Int? = null, val bg: Int? = null, val pt: Float? = null, val sup: Boolean? = null, val sub: Boolean? = null,
    val mono: Boolean? = null, val caps: Boolean? = null,
) {
    /** накласти [o] поверх цього */
    fun over(o: RProps?): RProps = if (o == null) this else RProps(
        o.b ?: b, o.i ?: i, o.u ?: u, o.s ?: s, o.color ?: color, o.bg ?: bg, o.pt ?: pt, o.sup ?: sup, o.sub ?: sub, o.mono ?: mono, o.caps ?: caps,
    )

    fun run(text: String, link: String? = null) = Run(
        text, b == true, i == true, u == true || link != null, s == true, color, bg, pt, link, sup == true, sub == true, mono == true, caps == true,
    )
}

private val MONO_FONTS = listOf("courier", "consolas", "mono", "menlo", "monaco", "lucida console")

/** Word 2007+ (.docx, .docm, .dotx). */
class Docx(private val file: File) {
    private val pkg = Pkg(file)
    private val rels = pkg.rels("word/document.xml")

    private class Style(val name: String, val basedOn: String?, val r: RProps?, val heading: Int?, val align: Align?, val numId: String?, val ilvl: Int?)
    private val styles = HashMap<String, Style>()
    private var defaults = RProps()
    private var defaultParaStyle: String? = null
    private var basePt = 11f

    private class Lvl(val fmt: String, val text: String, val start: Int)
    private val abstracts = HashMap<String, HashMap<Int, Lvl>>()
    private val nums = HashMap<String, String>()
    private val counters = HashMap<String, IntArray>()

    private val blocks = ArrayList<Block>()
    private val toc = ArrayList<TocItem>()

    fun parse(): FlowDoc = pkg.use {
        readStyles()
        readNumbering()
        val x = pkg.xml("word/document.xml") ?: throw DocException("No document.xml")
        while (x.next() != XmlReader.T.EOF) {
            if (x.type == XmlReader.T.START && x.local == "body") {
                body(x, blocks)
                break
            }
        }
        val title = pkg.xml("docProps/core.xml")?.let { c ->
            var t: String? = null
            while (c.next() != XmlReader.T.EOF) if (c.type == XmlReader.T.START && c.local == "title") { t = c.readText().trim(); break }
            t?.takeIf { it.isNotEmpty() }
        }
        FlowDoc(title, blocks, Resources.zip(file), FlowKind.DOCUMENT, toc, basePt = basePt)
    }

    // ───────── стилі ─────────

    private fun readRPr(x: XmlReader): RProps {
        var r = RProps()
        x.each { n ->
            fun on() = x.attr("val").let { it == null || (it != "0" && it != "false" && it != "off" && it != "none") }
            when (n) {
                "b" -> r = r.copy(b = on())
                "i" -> r = r.copy(i = on())
                "u" -> r = r.copy(u = x.attr("val").let { it != null && it != "none" && it != "0" } || x.attr("val") == null)
                "strike", "dstrike" -> r = r.copy(s = on())
                "caps", "smallCaps" -> r = r.copy(caps = on())
                "color" -> hexColor(x.attr("val"))?.let { r = r.copy(color = it) }
                "sz" -> x.attr("val")?.toFloatOrNull()?.let { r = r.copy(pt = it / 2f) }
                "highlight" -> namedColor(x.attr("val"))?.let { r = r.copy(bg = it) }
                "shd" -> hexColor(x.attr("fill"))?.takeIf { (it and 0xFFFFFF) != 0xFFFFFF }?.let { r = r.copy(bg = it) }
                "vertAlign" -> when (x.attr("val")) {
                    "superscript" -> r = r.copy(sup = true, sub = false)
                    "subscript" -> r = r.copy(sub = true, sup = false)
                    "baseline" -> r = r.copy(sup = false, sub = false)
                }
                "rFonts" -> {
                    val f = (x.attr("ascii") ?: x.attr("hAnsi") ?: "").lowercase()
                    if (f.isNotEmpty()) r = r.copy(mono = MONO_FONTS.any { f.contains(it) })
                }
                "rStyle" -> styles[x.attr("val")]?.let { r = resolveR(x.attr("val")).over(r) }
            }
            x.skip()
        }
        return r
    }

    private fun readStyles() {
        val x = pkg.xml("word/styles.xml") ?: return
        while (x.next() != XmlReader.T.EOF) {
            if (x.type != XmlReader.T.START) continue
            when (x.local) {
                "rPrDefault" -> x.each { n -> if (n == "rPr") { defaults = readRPr(x); defaults.pt?.let { basePt = it } } else x.skip() }
                "style" -> {
                    val id = x.attr("styleId") ?: continue
                    val isDefault = x.attr("default") == "1" && x.attr("type") == "paragraph"
                    var name = id
                    var based: String? = null
                    var r: RProps? = null
                    var heading: Int? = null
                    var align: Align? = null
                    var numId: String? = null
                    var ilvl: Int? = null
                    x.each { n ->
                        when (n) {
                            "name" -> { name = x.attr("val") ?: id; x.skip() }
                            "basedOn" -> { based = x.attr("val"); x.skip() }
                            "rPr" -> r = readRPr(x)
                            "pPr" -> x.each { pn ->
                                when (pn) {
                                    "outlineLvl" -> heading = x.attr("val")?.toIntOrNull()?.let { if (it in 0..5) it + 1 else null }
                                    "jc" -> align = jc(x.attr("val"))
                                    "numPr" -> x.each { nn ->
                                        when (nn) { "numId" -> numId = x.attr("val"); "ilvl" -> ilvl = x.attr("val")?.toIntOrNull() }
                                        x.skip()
                                    }
                                }
                                if (pn != "numPr") x.skip()
                            }
                            else -> x.skip()
                        }
                    }
                    val lname = name.lowercase()
                    if (heading == null) heading = when {
                        lname == "title" || lname == "назва" || lname == "заголовок" -> 1
                        lname == "subtitle" || lname == "підзаголовок" -> 2
                        else -> Regex("^(heading|заголовок)\\s*(\\d)$").find(lname)?.groupValues?.get(2)?.toIntOrNull()
                    }
                    styles[id] = Style(name, based, r, heading, align, numId, ilvl)
                    if (isDefault) defaultParaStyle = id
                }
            }
        }
    }

    private fun resolveR(id: String?): RProps {
        var r = RProps()
        var cur = id
        val chain = ArrayList<Style>()
        var guard = 0
        while (cur != null && guard++ < 20) {
            val st = styles[cur] ?: break
            chain.add(st)
            cur = st.basedOn
        }
        for (st in chain.asReversed()) r = r.over(st.r)
        return r
    }

    private fun <T> styleProp(id: String?, f: (Style) -> T?): T? {
        var cur = id
        var guard = 0
        while (cur != null && guard++ < 20) {
            val st = styles[cur] ?: return null
            f(st)?.let { return it }
            cur = st.basedOn
        }
        return null
    }

    private fun jc(v: String?) = when (v) {
        "center" -> Align.CENTER; "right", "end" -> Align.END; "both", "distribute" -> Align.JUSTIFY; "left", "start" -> Align.START
        else -> null
    }

    // ───────── нумерація списків ─────────

    private fun readNumbering() {
        val x = pkg.xml("word/numbering.xml") ?: return
        while (x.next() != XmlReader.T.EOF) {
            if (x.type != XmlReader.T.START) continue
            when (x.local) {
                "abstractNum" -> {
                    val id = x.attr("abstractNumId") ?: continue
                    val lv = HashMap<Int, Lvl>()
                    x.each { n ->
                        if (n == "lvl") {
                            val il = x.attr("ilvl")?.toIntOrNull() ?: 0
                            var fmt = "decimal"; var txt = "%1."; var start = 1
                            x.each { ln ->
                                when (ln) {
                                    "numFmt" -> fmt = x.attr("val") ?: fmt
                                    "lvlText" -> txt = x.attr("val") ?: txt
                                    "start" -> start = x.attr("val")?.toIntOrNull() ?: 1
                                }
                                x.skip()
                            }
                            lv[il] = Lvl(fmt, txt, start)
                        } else x.skip()
                    }
                    abstracts[id] = lv
                }
                "num" -> {
                    val id = x.attr("numId") ?: continue
                    x.each { n -> if (n == "abstractNumId") nums[id] = x.attr("val") ?: ""; x.skip() }
                }
            }
        }
    }

    private fun bullet(numId: String, ilvl: Int): String? {
        if (numId == "0") return null
        val lvls = abstracts[nums[numId]] ?: return "•"
        val lvl = lvls[ilvl] ?: return "•"
        val c = counters.getOrPut(numId) { IntArray(9) { -1 } }
        val il = ilvl.coerceIn(0, 8)
        c[il] = if (c[il] < 0) lvl.start else c[il] + 1
        for (k in il + 1 until 9) c[k] = -1
        if (lvl.fmt == "bullet" || lvl.fmt == "none") {
            if (lvl.fmt == "none") return null
            val ch = lvl.text.firstOrNull()
            return when {
                ch == null -> "•"
                ch.code in 0xF000..0xF0FF || ch == '' -> if (ilvl % 3 == 1) "◦" else if (ilvl % 3 == 2) "▪" else "•"
                ch == 'o' -> "◦"
                ch == '§' -> "▪"
                else -> ch.toString()
            }
        }
        var out = lvl.text
        for (k in 0..il) {
            val l = lvls[k] ?: continue
            val v = if (c[k] < 0) l.start else c[k]
            out = out.replace("%${k + 1}", fmtNum(v, if (k == il) lvl.fmt else l.fmt))
        }
        return out
    }

    // ───────── тіло документа ─────────

    private fun body(x: XmlReader, out: MutableList<Block>) {
        x.each { n ->
            when (n) {
                "p" -> para(x, out)
                "tbl" -> table(x, out)
                "sdtPr", "sectPr", "bookmarkStart", "bookmarkEnd", "proofErr" -> x.skip()
                "AlternateContent" -> alternate(x) { body(x, out) }
                // sdt, sdtContent, customXml… — спускаємось усередину
            }
        }
    }

    /** mc:AlternateContent — беремо лише перший варіант (Choice), щоб не дублювати. */
    private inline fun alternate(x: XmlReader, f: () -> Unit) {
        var done = false
        x.each { n ->
            if (n == "Choice" && !done) { done = true; f() } else if (n == "Fallback" && !done) { done = true; f() } else x.skip()
        }
    }

    private fun para(x: XmlReader, out: MutableList<Block>) {
        var styleId: String? = null
        var align: Align? = null
        var numId: String? = null
        var ilvl: Int? = null
        var indentTw = 0
        var outline: Int? = null
        var pageBreakBefore = false
        val buf = RunBuf()
        val after = ArrayList<Block>() // картинки, текстові поля
        var pendingBreak = false
        val start = out.size

        fun base() = defaults.over(resolveR(styleId ?: defaultParaStyle))

        fun flushText() {
            if (!buf.isEmpty()) {
                // перший шматок абзацу формуємо в кінці; тут — проміжний розрив (картинка посеред тексту)
                emitPara(out, buf.take(), styleId, align, numId, ilvl, indentTw, outline)
                numId = null
            }
        }

        fun child(n: String, link: String?) {
            when (n) {
                "r" -> run(x, base(), link, buf, after) { pendingBreak = true }
                "hyperlink" -> {
                    val rid = x.attr("id")?.let { rels[it] }
                    val target = rid?.target ?: x.attr("anchor")?.let { "#$it" }
                    x.each { c -> child(c, target) }
                }
                "del", "moveFrom", "rPr", "bookmarkStart", "bookmarkEnd", "proofErr", "commentRangeStart", "commentRangeEnd" -> x.skip()
                "AlternateContent" -> alternate(x) { x.each { c -> child(c, link) } }
                "fldSimple", "ins", "moveTo", "smartTag", "sdt", "sdtContent", "customXml", "dir", "bdo" -> x.each { c -> child(c, link) }
                "sdtPr", "sdtEndPr" -> x.skip()
                "oMath", "oMathPara" -> buf.add(base().run(x.readText()))
                else -> x.skip()
            }
            if (pendingBreak) {
                flushText()
                out.add(Rule(pageBreak = true))
                pendingBreak = false
            }
            if (after.isNotEmpty()) {
                flushText()
                out.addAll(after)
                after.clear()
            }
        }

        x.each { n ->
            when (n) {
                "pPr" -> x.each { pn ->
                    when (pn) {
                        "pStyle" -> { styleId = x.attr("val"); x.skip() }
                        "jc" -> { align = jc(x.attr("val")); x.skip() }
                        "numPr" -> x.each { nn ->
                            when (nn) { "numId" -> numId = x.attr("val"); "ilvl" -> ilvl = x.attr("val")?.toIntOrNull() }
                            x.skip()
                        }
                        "ind" -> { indentTw = (x.attr("left") ?: x.attr("start"))?.toIntOrNull() ?: 0; x.skip() }
                        "outlineLvl" -> { outline = x.attr("val")?.toIntOrNull(); x.skip() }
                        "pageBreakBefore" -> { pageBreakBefore = x.attr("val").let { it == null || it == "1" || it == "true" }; x.skip() }
                        else -> x.skip()
                    }
                }
                else -> child(n, null)
            }
        }
        if (pageBreakBefore) out.add(start, Rule(pageBreak = true))
        if (!buf.isEmpty() || out.size == start || (out.size == start + 1 && pageBreakBefore)) {
            emitPara(out, buf.take(), styleId, align, numId, ilvl, indentTw, outline)
        }
    }

    private fun emitPara(out: MutableList<Block>, runs: List<Run>, styleId: String?, align: Align?, numId: String?, ilvl: Int?, indentTw: Int, outline: Int?) {
        val sid = styleId ?: defaultParaStyle
        val heading = outline?.takeIf { it in 0..5 }?.plus(1) ?: styleProp(sid) { it.heading } ?: 0
        val nid = numId ?: styleProp(sid) { it.numId }
        val lvl = ilvl ?: styleProp(sid) { it.ilvl } ?: 0
        val bullet = nid?.let { bullet(it, lvl) }
        val al = align ?: styleProp(sid) { it.align } ?: Align.START
        val indent = if (bullet != null) lvl + 1 else (indentTw / 720).coerceIn(0, 6)
        val name = styles[sid]?.name?.lowercase() ?: ""
        val p = Para(
            runs, heading = heading, align = al, indent = indent, bullet = bullet,
            quote = name.contains("quote") || name.contains("цитата"),
            mono = name.contains("code") || name.contains("html preformatted"),
        )
        if (heading > 0 && p.text.isNotBlank()) toc.add(TocItem(p.text.trim(), out.size, heading - 1))
        out.add(p)
    }

    private fun run(x: XmlReader, base: RProps, link: String?, buf: RunBuf, after: MutableList<Block>, pageBreak: () -> Unit) {
        var r = base
        x.each { n ->
            when (n) {
                "rPr" -> r = base.over(readRPr(x))
                "t" -> buf.add(r.run(x.readText(), link))
                "tab", "ptab" -> { buf.add(r.run("\t", link)); x.skip() }
                "br" -> {
                    if (x.attr("type") == "page") pageBreak() else buf.add(r.run("\n", link))
                    x.skip()
                }
                "cr" -> { buf.add(r.run("\n", link)); x.skip() }
                "noBreakHyphen" -> { buf.add(r.run("-", link)); x.skip() }
                "sym" -> {
                    val code = x.attr("char")?.toIntOrNull(16)
                    if (code != null) buf.add(r.run(symbol(code), link))
                    x.skip()
                }
                "drawing", "pict", "object" -> drawing(x, after)
                "AlternateContent" -> alternate(x) { drawingInner(x, after) }
                "footnoteReference", "endnoteReference" -> { buf.add(r.copy(sup = true).run(x.attr("id") ?: "*", link)); x.skip() }
                "delText", "instrText", "fldChar", "lastRenderedPageBreak", "softHyphen", "commentReference", "annotationRef" -> x.skip()
                else -> x.skip()
            }
        }
    }

    private fun drawing(x: XmlReader, after: MutableList<Block>) = drawingInner(x, after)

    /** Картинки (a:blip, v:imagedata) і текстові поля (txbxContent) усередині малюнка. */
    private fun drawingInner(x: XmlReader, after: MutableList<Block>) {
        var cx: Float? = null
        var cy: Float? = null
        var alt: String? = null
        x.each { n ->
            when (n) {
                "extent" -> { cx = x.attr("cx")?.toFloatOrNull()?.div(EMU_PER_PT); cy = x.attr("cy")?.toFloatOrNull()?.div(EMU_PER_PT); x.skip() }
                "docPr" -> { alt = x.attr("descr")?.takeIf { it.isNotBlank() }; x.skip() }
                "blip" -> {
                    val rel = (x.attr("embed") ?: x.attr("link"))?.let { rels[it] }
                    if (rel != null && !rel.external) after.add(Image(rel.target, cx, cy, alt))
                    x.skip()
                }
                "imagedata" -> {
                    val rel = (x.attrQ("r:id") ?: x.attr("id"))?.let { rels[it] }
                    if (rel != null && !rel.external) after.add(Image(rel.target, cx, cy, alt))
                    x.skip()
                }
                "txbxContent" -> body(x, after)
                "Fallback" -> x.skip()
            }
        }
    }

    private fun table(x: XmlReader, out: MutableList<Block>) {
        val rows = ArrayList<Row>()
        x.each { n ->
            when (n) {
                "tr" -> {
                    val cells = ArrayList<Cell>()
                    var header = false
                    x.each { cn ->
                        when (cn) {
                            "trPr" -> x.each { tn -> if (tn == "tblHeader") header = true; x.skip() }
                            "tc" -> {
                                var span = 1
                                var bg: Int? = null
                                var cont = false
                                val content = ArrayList<Block>()
                                x.each { tc ->
                                    when (tc) {
                                        "tcPr" -> x.each { pn ->
                                            when (pn) {
                                                "gridSpan" -> span = x.attr("val")?.toIntOrNull() ?: 1
                                                "vMerge" -> cont = x.attr("val").let { it == null || it == "continue" }
                                                "shd" -> bg = hexColor(x.attr("fill"))?.takeIf { (it and 0xFFFFFF) != 0xFFFFFF }
                                            }
                                            x.skip()
                                        }
                                        "p" -> para(x, content)
                                        "tbl" -> table(x, content)
                                        "AlternateContent" -> alternate(x) { body(x, content) }
                                        "sdt", "sdtContent", "customXml" -> body(x, content)
                                        else -> x.skip()
                                    }
                                }
                                cells.add(Cell(if (cont) emptyList() else content, span.coerceAtLeast(1), bg = bg))
                            }
                            "sdt", "sdtContent", "customXml" -> {}
                            else -> x.skip()
                        }
                    }
                    rows.add(Row(cells, header))
                }
                "tblPr", "tblGrid" -> x.skip()
                "sdt", "sdtContent", "customXml" -> {}
                else -> x.skip()
            }
        }
        if (rows.isNotEmpty()) out.add(Table(rows))
    }

    companion object {
        /** Символи зі шрифтів Symbol/Wingdings, що трапляються найчастіше. */
        fun symbol(code: Int): String {
            val c = if (code in 0xF000..0xF0FF) code - 0xF000 else code
            return when (c) {
                0xB7, 0xA7, 0x6C -> "•"; 0xFC -> "✓"; 0xFB -> "✗"; 0xA8 -> "□"; 0xFE -> "☑"; 0x6E -> "■"; 0xD8 -> "➢"
                0xE0 -> "→"; 0xDF -> "←"; 0xAE -> "→"; 0xAC -> "←"; 0xB0 -> "°"; 0xB4 -> "×"; 0xB8 -> "÷"; 0xB1 -> "±"
                0x61 -> "α"; 0x62 -> "β"; 0x67 -> "γ"; 0x64 -> "δ"; 0x70 -> "π"; 0x73 -> "σ"; 0x77 -> "ω"; 0x6D -> "μ"
                else -> if (c in 0x20..0x7E) c.toChar().toString() else "•"
            }
        }

        fun fmtNum(n: Int, fmt: String): String = when (fmt) {
            "lowerLetter" -> letters(n).lowercase()
            "upperLetter" -> letters(n)
            "lowerRoman" -> roman(n).lowercase()
            "upperRoman" -> roman(n)
            "russianLower" -> cyr(n)
            "russianUpper" -> cyr(n).uppercase()
            "decimalZero" -> n.toString().padStart(2, '0')
            "ordinal" -> "$n."
            else -> n.toString()
        }

        fun letters(n: Int): String {
            if (n <= 0) return n.toString()
            var k = n
            val sb = StringBuilder()
            while (k > 0) { k--; sb.insert(0, 'A' + k % 26); k /= 26 }
            return sb.toString()
        }

        private fun cyr(n: Int): String {
            val a = "абвгдежзиклмнопрстуфхцчшщэюя"
            return if (n in 1..a.length) a[n - 1].toString() else n.toString()
        }

        fun roman(n: Int): String {
            if (n <= 0 || n > 3999) return n.toString()
            val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var k = n
            val sb = StringBuilder()
            for (i in v.indices) while (k >= v[i]) { sb.append(s[i]); k -= v[i] }
            return sb.toString()
        }
    }
}
