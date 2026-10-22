package ua.x44to.docview.core

import java.io.File
import java.util.Base64

/**
 * HTML/XHTML → блоки. Поблажливий до незакритих тегів. Використовується для EPUB, .html і MHT-подібних файлів.
 * [base] — тека документа в архіві (для відносних шляхів картинок); [images] — куди класти data:-картинки.
 */
class HtmlConv(private val base: String?, private val images: MutableMap<String, ByteArray>? = null) {
    val blocks = ArrayList<Block>()
    /** id елемента → номер блоку (для змісту EPUB) */
    val ids = HashMap<String, Int>()
    var title: String? = null

    private class Inline(val tag: String, val b: Boolean?, val i: Boolean?, val u: Boolean?, val s: Boolean?, val color: Int?, val bg: Int?, val link: String?, val mono: Boolean?, val sup: Boolean?, val sub: Boolean?, val hidden: Boolean)
    private class Ctx(val tag: String, val align: Align?, val heading: Int, val quote: Boolean, val pre: Boolean, val list: Int)
    private class TableCtx(val rows: ArrayList<Row> = ArrayList(), var cells: ArrayList<Cell>? = null, var cellBlocks: ArrayList<Block>? = null, var cellSpan: Int = 1, var header: Boolean = false, var bg: Int? = null)

    private val inline = ArrayList<Inline>()
    private val ctx = ArrayList<Ctx>()
    private val tables = ArrayList<TableCtx>()
    private val lists = ArrayList<IntArray>() // [тип: 0 ul / 1 ol, лічильник]
    private val buf = RunBuf()
    private var bullet: String? = null
    private var skipDepth = 0

    private val blockTags = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "section", "article", "header", "footer",
        "main", "aside", "nav", "figure", "figcaption", "dl", "dt", "dd", "center", "address", "body", "ul", "ol", "caption",
        "table", "tr", "td", "th", "thead", "tbody", "tfoot", "hr", "form", "fieldset", "legend", "details", "summary",
    )

    private fun out(): MutableList<Block> = tables.lastOrNull()?.cellBlocks ?: blocks

    fun feed(html: String) {
        val x = XmlReader(html, html = true)
        var inHead = false
        var inTitle = false
        while (x.next() != XmlReader.T.EOF) {
            when (x.type) {
                XmlReader.T.START -> {
                    val n = x.local.lowercase()
                    x.attr("id")?.let { ids[it] = blocks.size }
                    x.attr("name")?.let { if (n == "a") ids.putIfAbsent(it, blocks.size) }
                    when {
                        n == "head" -> inHead = true
                        n == "title" -> inTitle = true
                        skipDepth > 0 -> { if (n !in VOID_HTML) skipDepth++ }
                        inHead -> {}
                        n == "br" -> buf.add(cur("\n"))
                        n == "img" || n == "image" -> image(x)
                        n == "hr" -> { flush(); out().add(Rule()) }
                        n in blockTags -> startBlock(n, x)
                        else -> startInline(n, x)
                    }
                    if (hiddenStyle(x.attr("style")) && n !in VOID_HTML && skipDepth == 0) skipDepth = 1
                }
                XmlReader.T.END -> {
                    val n = x.local.lowercase()
                    when {
                        n == "head" -> inHead = false
                        n == "title" -> inTitle = false
                        skipDepth > 0 -> skipDepth--
                        n in blockTags -> endBlock(n)
                        else -> endInline(n)
                    }
                }
                XmlReader.T.TEXT -> {
                    if (inTitle) { title = (title ?: "") + x.text.trim(); continue }
                    if (inHead || skipDepth > 0) continue
                    val pre = ctx.any { it.pre }
                    var t = x.text
                    if (!pre) t = t.replace(Regex("[ \\t\\r\\n]+"), " ")
                    if (t.isEmpty()) continue
                    if (!pre && buf.isEmpty() && t.isBlank()) continue
                    if (inline.lastOrNull()?.hidden == true) continue
                    buf.add(cur(t))
                }
                else -> {}
            }
        }
        flush()
        closeTables()
    }

    private fun hiddenStyle(st: String?) = st != null && Regex("display\\s*:\\s*none", RegexOption.IGNORE_CASE).containsMatchIn(st)

    private fun css(st: String?, key: String): String? =
        st?.split(';')?.map { it.split(':', limit = 2) }?.firstOrNull { it.size == 2 && it[0].trim().equals(key, true) }?.get(1)?.trim()

    private fun cur(t: String): Run {
        var b = false; var i = false; var u = false; var s = false; var color: Int? = null; var bg: Int? = null
        var link: String? = null; var mono = false; var sup = false; var sub = false
        for (st in inline) {
            st.b?.let { b = it }; st.i?.let { i = it }; st.u?.let { u = it }; st.s?.let { s = it }
            st.color?.let { color = it }; st.bg?.let { bg = it }; st.link?.let { link = it }; st.mono?.let { mono = it }
            st.sup?.let { sup = it }; st.sub?.let { sub = it }
        }
        if (ctx.any { it.pre }) mono = true
        return Run(t, b, i, u || link != null, s, color, bg, null, link, sup, sub, mono)
    }

    private fun startInline(n: String, x: XmlReader) {
        val style = x.attr("style")
        val weight = css(style, "font-weight")
        val fstyle = css(style, "font-style")
        val deco = css(style, "text-decoration")
        val b = when (n) { "b", "strong" -> true; else -> weight?.let { it == "bold" || it == "bolder" || (it.toIntOrNull() ?: 400) >= 600 } }
        val i = when (n) { "i", "em", "cite", "dfn", "var" -> true; else -> fstyle?.let { it == "italic" || it == "oblique" } }
        val u = when (n) { "u", "ins" -> true; else -> deco?.let { if (it.contains("underline")) true else null } }
        val s = when (n) { "s", "strike", "del" -> true; else -> deco?.let { if (it.contains("line-through")) true else null } }
        val color = cssColor(css(style, "color")) ?: if (n == "font") cssColor(x.attr("color")) else null
        val bg = cssColor(css(style, "background-color") ?: css(style, "background")) ?: if (n == "mark") 0xFFFFF59D.toInt() else null
        val link = if (n == "a") x.attr("href")?.takeIf { it.isNotBlank() } else null
        val mono = if (n == "code" || n == "tt" || n == "kbd" || n == "samp") true else null
        val sup = if (n == "sup") true else null
        val sub = if (n == "sub") true else null
        if (n in VOID_HTML) return
        inline.add(Inline(n, b, i, u, s, color, bg, link, mono, sup, sub, false))
    }

    private fun endInline(n: String) {
        val k = inline.indexOfLast { it.tag == n }
        if (k >= 0) while (inline.size > k) inline.removeAt(inline.lastIndex)
    }

    private fun startBlock(n: String, x: XmlReader) {
        flush()
        val style = x.attr("style")
        val al = when ((css(style, "text-align") ?: x.attr("align"))?.lowercase()) {
            "center" -> Align.CENTER; "right", "end" -> Align.END; "justify" -> Align.JUSTIFY; "left", "start" -> Align.START
            else -> if (n == "center") Align.CENTER else null
        }
        when (n) {
            "ul", "ol" -> lists.add(intArrayOf(if (n == "ol") 1 else 0, (x.attr("start")?.toIntOrNull() ?: 1) - 1))
            "li" -> {
                val l = lists.lastOrNull()
                bullet = if (l != null && l[0] == 1) { l[1]++; "${l[1]}." } else "•"
            }
            "table" -> tables.add(TableCtx())
            "tr" -> tables.lastOrNull()?.let { t -> closeRow(t); t.cells = ArrayList() }
            "td", "th" -> tables.lastOrNull()?.let { t ->
                closeCell(t)
                if (t.cells == null) t.cells = ArrayList()
                t.cellBlocks = ArrayList()
                t.cellSpan = x.attr("colspan")?.toIntOrNull()?.coerceIn(1, 50) ?: 1
                t.header = n == "th"
                t.bg = cssColor(css(style, "background-color") ?: css(style, "background") ?: x.attr("bgcolor"))
                if (n == "th") inline.add(Inline("th", true, null, null, null, null, null, null, null, null, null, false))
            }
        }
        val heading = when (n) { "h1" -> 1; "h2" -> 2; "h3" -> 3; "h4" -> 4; "h5" -> 5; "h6" -> 6; else -> 0 }
        ctx.add(Ctx(n, al, heading, n == "blockquote", n == "pre", lists.size))
    }

    private fun endBlock(n: String) {
        flush()
        when (n) {
            "ul", "ol" -> if (lists.isNotEmpty()) lists.removeAt(lists.lastIndex)
            "td", "th" -> tables.lastOrNull()?.let { closeCell(it) }
            "tr" -> tables.lastOrNull()?.let { closeRow(it) }
            "table" -> tables.lastOrNull()?.let { t ->
                closeRow(t)
                tables.removeAt(tables.lastIndex)
                if (t.rows.isNotEmpty()) out().add(Table(t.rows))
            }
        }
        if (n == "th") endInline("th")
        val k = ctx.indexOfLast { it.tag == n }
        if (k >= 0) while (ctx.size > k) ctx.removeAt(ctx.lastIndex)
    }

    private fun closeCell(t: TableCtx) {
        flush()
        val cb = t.cellBlocks ?: return
        (t.cells ?: ArrayList<Cell>().also { t.cells = it }).add(Cell(cb, t.cellSpan, bg = t.bg))
        t.cellBlocks = null
    }

    private fun closeRow(t: TableCtx) {
        closeCell(t)
        val c = t.cells ?: return
        if (c.isNotEmpty()) t.rows.add(Row(c, c.isNotEmpty() && t.header))
        t.cells = null
    }

    private fun closeTables() {
        while (tables.isNotEmpty()) {
            val t = tables.removeAt(tables.lastIndex)
            closeRow(t)
            if (t.rows.isNotEmpty()) out().add(Table(t.rows))
        }
    }

    private fun flush() {
        if (buf.isEmpty()) return
        val runs = buf.take().toMutableList()
        val pre = ctx.any { it.pre }
        if (!pre) {
            // прибираємо пробіли на краях абзацу
            while (runs.isNotEmpty() && runs.first().text.isBlank()) runs.removeAt(0)
            while (runs.isNotEmpty() && runs.last().text.isBlank()) runs.removeAt(runs.lastIndex)
            if (runs.isEmpty()) { bullet = null; return }
            runs[0] = runs[0].copy(runs[0].text.trimStart())
            runs[runs.lastIndex] = runs.last().copy(runs.last().text.trimEnd())
        }
        val heading = ctx.lastOrNull { it.heading > 0 }?.heading ?: 0
        val align = ctx.lastOrNull { it.align != null }?.align ?: Align.START
        val quote = ctx.any { it.quote }
        val depth = lists.size
        if (pre) {
            // пре-блок: кожен рядок окремим абзацом, щоб зберегти перенос
            val text = runs.joinToString("") { it.text }.trimEnd('\n')
            for (line in text.split('\n')) out().add(Para(listOf(Run(line, mono = true)), mono = true))
        } else {
            out().add(Para(runs, heading, align, if (bullet != null) depth else if (quote) 1 else 0, bullet, quote))
        }
        bullet = null
    }

    private fun image(x: XmlReader) {
        val src = x.attr("src") ?: x.attr("href") ?: return
        val alt = x.attr("alt")
        val w = x.attr("width")?.toFloatOrNull()?.times(0.75f)
        val h = x.attr("height")?.toFloatOrNull()?.times(0.75f)
        val key: String = if (src.startsWith("data:")) {
            val m = images ?: return
            val data = src.substringAfter(',', "")
            val bytes = runCatching { Base64.getMimeDecoder().decode(data) }.getOrNull() ?: return
            "data:${m.size}".also { m[it] = bytes }
        } else if (src.startsWith("http:") || src.startsWith("https:")) {
            return // без інтернету
        } else Pkg.resolve(base ?: "", src)
        flush()
        out().add(Image(key, w, h, alt))
    }

    companion object {
        val VOID_HTML = setOf("br", "img", "hr", "meta", "link", "input", "col", "area", "base", "wbr", "source", "param", "embed", "track", "image")

        fun file(f: File, name: String): FlowDoc {
            val bytes = f.readBytes()
            if (bytes.size > 40 * 1024 * 1024) throw DocException("File is too large")
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            val cs = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
            val text = cs?.let { runCatching { String(bytes, java.nio.charset.Charset.forName(it)) }.getOrNull() } ?: Text.decode(bytes)
            val imgs = HashMap<String, ByteArray>()
            val c = HtmlConv(null, imgs)
            c.feed(text)
            val toc = c.blocks.withIndex().filter { (_, b) -> b is Para && b.heading in 1..3 }.map { (i, b) -> TocItem((b as Para).text, i, b.heading - 1) }
            return FlowDoc(c.title ?: name, c.blocks, Resources.map(imgs), FlowKind.DOCUMENT, toc)
        }
    }
}
