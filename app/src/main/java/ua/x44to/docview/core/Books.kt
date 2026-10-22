package ua.x44to.docview.core

import java.io.File
import java.util.Base64
import java.util.zip.ZipFile

/** Електронна книга EPUB 2/3: порядок розділів зі spine, зміст із nav або NCX. */
class Epub(private val file: File) {
    fun parse(): FlowDoc = Pkg(file).use { pkg ->
        val container = pkg.xml("META-INF/container.xml")
        var opfPath: String? = null
        if (container != null) {
            while (container.next() != XmlReader.T.EOF) {
                if (container.type == XmlReader.T.START && container.local == "rootfile") { opfPath = container.attr("full-path"); break }
            }
        }
        val opf = opfPath ?: pkg.entries.firstOrNull { it.lowercase().endsWith(".opf") } ?: throw DocException("No OPF")
        val opfDir = opf.substringBeforeLast('/', "")
        val x = pkg.xml(opf) ?: throw DocException("No OPF")
        var title: String? = null
        var author: String? = null
        var coverId: String? = null
        class Item(val href: String, val type: String, val props: String)
        val manifest = HashMap<String, Item>()
        val spine = ArrayList<String>()
        var ncxId: String? = null
        while (x.next() != XmlReader.T.EOF) {
            if (x.type != XmlReader.T.START) continue
            when (x.local) {
                "title" -> if (title == null) title = x.readText().trim().takeIf { it.isNotEmpty() }
                "creator" -> if (author == null) author = x.readText().trim().takeIf { it.isNotEmpty() }
                "meta" -> if (x.attr("name") == "cover") coverId = x.attr("content")
                "item" -> {
                    val id = x.attr("id") ?: continue
                    val href = x.attr("href") ?: continue
                    manifest[id] = Item(Pkg.resolve(opfDir, href), x.attr("media-type") ?: "", x.attr("properties") ?: "")
                }
                "spine" -> ncxId = x.attr("toc")
                "itemref" -> x.attr("idref")?.let { spine.add(it) }
            }
        }
        val blocks = ArrayList<Block>()
        val chapterStart = HashMap<String, Int>()
        val anchors = HashMap<String, Int>()
        for (id in spine) {
            val it = manifest[id] ?: continue
            if (!it.type.contains("html") && !it.href.lowercase().endsWith("html") && !it.href.lowercase().endsWith(".htm")) continue
            val text = pkg.text(it.href) ?: continue
            val conv = HtmlConv(it.href.substringBeforeLast('/', ""))
            conv.feed(text)
            chapterStart[it.href] = blocks.size
            for ((k, v) in conv.ids) anchors["${it.href}#$k"] = blocks.size + v
            blocks.addAll(conv.blocks)
            if (blocks.isNotEmpty() && blocks.last() !is Rule) blocks.add(Rule())
        }
        if (blocks.lastOrNull() is Rule) blocks.removeAt(blocks.lastIndex)

        fun target(href: String, base: String): Int? {
            val full = Pkg.resolve(base, href.substringBefore('#')) + (href.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" })
            return anchors[full] ?: chapterStart[full.substringBefore('#')]
        }

        val toc = ArrayList<TocItem>()
        // EPUB 3: nav-документ
        manifest.values.firstOrNull { it.props.split(' ').contains("nav") }?.let { nav ->
            val nx = pkg.xml(nav.href, html = true) ?: return@let
            val navBase = nav.href.substringBeforeLast('/', "")
            var inToc = false
            var depth = 0
            var navDepth = -1
            while (nx.next() != XmlReader.T.EOF) {
                when (nx.type) {
                    XmlReader.T.START -> when (nx.local) {
                        "nav" -> { val t = nx.attr("type") ?: ""; inToc = t.contains("toc") || (navDepth < 0 && toc.isEmpty() && t.isEmpty()); navDepth = nx.depth }
                        "ol" -> if (inToc) depth++
                        "a" -> if (inToc) {
                            val href = nx.attr("href") ?: ""
                            val t = nx.readText().trim().replace(Regex("\\s+"), " ")
                            target(href, navBase)?.let { if (t.isNotEmpty()) toc.add(TocItem(t, it, (depth - 1).coerceAtLeast(0))) }
                        }
                    }
                    XmlReader.T.END -> when (nx.local) {
                        "ol" -> if (inToc) depth--
                        "nav" -> inToc = false
                    }
                    else -> {}
                }
            }
        }
        // EPUB 2: NCX
        if (toc.isEmpty()) {
            val ncx = ncxId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.type.contains("ncx") }
            if (ncx != null) {
                val nx = pkg.xml(ncx.href)
                val ncxBase = ncx.href.substringBeforeLast('/', "")
                var depth = 0
                var label: String? = null
                if (nx != null) while (nx.next() != XmlReader.T.EOF) {
                    when (nx.type) {
                        XmlReader.T.START -> when (nx.local) {
                            "navPoint" -> depth++
                            "text" -> label = nx.readText().trim()
                            "content" -> {
                                val l = label
                                val src = nx.attr("src")
                                if (l != null && src != null) target(src, ncxBase)?.let { toc.add(TocItem(l, it, depth - 1)) }
                                label = null
                            }
                        }
                        XmlReader.T.END -> if (nx.local == "navPoint") depth--
                        else -> {}
                    }
                }
            }
        }
        val cover = manifest.values.firstOrNull { it.props.split(' ').contains("cover-image") }?.href ?: coverId?.let { manifest[it]?.href }
        FlowDoc(title, blocks, Resources.zip(file), FlowKind.BOOK, toc, author, cover, basePt = 12f)
    }
}

/** FictionBook 2 (.fb2, .fb2.zip) — популярний формат книжок. */
class Fb2(private val file: File) {
    private val binaries = HashMap<String, String>()
    private val blocks = ArrayList<Block>()
    private val toc = ArrayList<TocItem>()

    fun parse(): FlowDoc {
        val bytes = if (isZip()) {
            ZipFile(file).use { z ->
                val e = z.entries().asSequence().first { !it.isDirectory }
                z.getInputStream(e).use { it.readBytes() }
            }
        } else file.readBytes()
        val x = XmlReader(XmlReader.decodeBytes(bytes))
        var title: String? = null
        var author: String? = null
        var cover: String? = null
        var bodyIndex = 0
        while (x.next() != XmlReader.T.EOF) {
            if (x.type != XmlReader.T.START) continue
            when (x.local) {
                "title-info" -> x.each { n ->
                    when (n) {
                        "book-title" -> title = x.readText().trim()
                        "author" -> if (author == null) {
                            val parts = ArrayList<String>()
                            x.each { an -> if (an == "first-name" || an == "last-name" || an == "middle-name") parts.add(x.readText().trim()) else x.skip() }
                            author = parts.filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }
                        }
                        "coverpage" -> x.each { cn -> if (cn == "image") { cover = href(x); x.skip() } else x.skip() }
                        else -> x.skip()
                    }
                }
                "body" -> {
                    if (bodyIndex > 0) blocks.add(Rule())
                    val notes = x.attr("name") == "notes" || bodyIndex > 0
                    if (bodyIndex == 0) cover?.let { blocks.add(Image(it)) }
                    section(x, if (notes) 1 else 0)
                    bodyIndex++
                }
                "binary" -> x.attr("id")?.let { id -> binaries[id] = x.readText() }
                "description" -> {}
            }
        }
        val res = Resources { key ->
            binaries[key]?.let { runCatching { Base64.getMimeDecoder().decode(it) }.getOrNull() }
        }
        return FlowDoc(title, blocks, res, FlowKind.BOOK, toc, author, cover, basePt = 12f)
    }

    private fun isZip() = file.inputStream().use { val h = ByteArray(2); it.read(h); h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() }

    private fun href(x: XmlReader): String? = (x.attr("href"))?.removePrefix("#")

    private fun section(x: XmlReader, depth: Int) {
        x.each { n ->
            when (n) {
                "section" -> section(x, depth + 1)
                "title" -> {
                    val start = blocks.size
                    val level = (depth + 1).coerceIn(1, 4)
                    x.each { tn -> if (tn == "p") blocks.add(Para(runs(x), heading = level, align = Align.CENTER)) else x.skip() }
                    val t = blocks.subList(start, blocks.size).filterIsInstance<Para>().joinToString(" ") { it.text }.trim()
                    if (t.isNotEmpty()) toc.add(TocItem(t, start, (depth - 1).coerceAtLeast(0)))
                }
                "p" -> blocks.add(Para(runs(x), firstLine = true, align = Align.JUSTIFY))
                "subtitle" -> blocks.add(Para(runs(x), heading = 5, align = Align.CENTER))
                "empty-line" -> { blocks.add(Para(listOf(Run("")))); x.skip() }
                "image" -> { href(x)?.let { blocks.add(Image(it)) }; x.skip() }
                "epigraph", "cite" -> quoted(x)
                "poem" -> x.each { pn ->
                    when (pn) {
                        "title" -> x.each { tn -> if (tn == "p") blocks.add(Para(runs(x), heading = 5, align = Align.CENTER)) else x.skip() }
                        "stanza" -> {
                            x.each { sn -> if (sn == "v") blocks.add(Para(runs(x), indent = 2)) else x.skip() }
                            blocks.add(Para(listOf(Run(""))))
                        }
                        "text-author" -> blocks.add(Para(runs(x).map { Run(it.text, i = true) }, align = Align.END))
                        else -> x.skip()
                    }
                }
                "table" -> {
                    val rows = ArrayList<Row>()
                    x.each { tr ->
                        if (tr == "tr") {
                            val cells = ArrayList<Cell>()
                            x.each { tc -> if (tc == "td" || tc == "th") cells.add(Cell(listOf(Para(runs(x))), x.attr("colspan")?.toIntOrNull() ?: 1)) else x.skip() }
                            rows.add(Row(cells))
                        } else x.skip()
                    }
                    if (rows.isNotEmpty()) blocks.add(Table(rows))
                }
                "annotation" -> quoted(x)
                else -> x.skip()
            }
        }
    }

    private fun quoted(x: XmlReader) {
        x.each { n ->
            when (n) {
                "p" -> blocks.add(Para(runs(x), quote = true, indent = 1))
                "text-author" -> blocks.add(Para(runs(x).map { Run(it.text, i = true) }, align = Align.END, quote = true))
                "empty-line" -> { blocks.add(Para(listOf(Run("")))); x.skip() }
                "poem" -> x.each { pn -> if (pn == "stanza") x.each { sn -> if (sn == "v") blocks.add(Para(runs(x), indent = 2, quote = true)) else x.skip() } else x.skip() }
                else -> x.skip()
            }
        }
    }

    /** Текст абзацу з вкладеними strong/emphasis/a/sup… */
    private fun runs(x: XmlReader): List<Run> {
        val buf = RunBuf()
        val d = x.depth
        var b = 0; var i = 0; var s = 0; var sup = 0; var sub = 0; var mono = 0
        val links = ArrayList<String?>()
        val stack = ArrayList<String>()
        while (true) {
            val t = x.next()
            if (t == XmlReader.T.EOF) break
            if (t == XmlReader.T.END) {
                if (x.depth < d) break
                when (stack.removeLastOrNull()) {
                    "strong" -> b--; "emphasis" -> i--; "strikethrough" -> s--; "sup" -> sup--; "sub" -> sub--; "code" -> mono--
                    "a" -> links.removeLastOrNull()
                }
                continue
            }
            if (t == XmlReader.T.TEXT) {
                val txt = x.text.replace(Regex("[ \\t\\r\\n]+"), " ")
                buf.add(Run(txt, b > 0, i > 0, links.lastOrNull() != null, s > 0, link = links.lastOrNull(), sup = sup > 0, sub = sub > 0, mono = mono > 0))
                continue
            }
            val n = x.local
            if (n == "image") { x.skip(); continue }
            stack.add(n)
            when (n) {
                "strong" -> b++; "emphasis" -> i++; "strikethrough" -> s++; "sup" -> sup++; "sub" -> sub++; "code" -> mono++
                "a" -> links.add(x.attr("href"))
            }
        }
        val r = buf.take().toMutableList()
        if (r.isNotEmpty()) {
            r[0] = r[0].copy(r[0].text.trimStart())
            r[r.lastIndex] = r.last().copy(r.last().text.trimEnd())
        }
        return r
    }
}

/** Вміст zip-архіву: список файлів; відкрити файл — розпакувати й показати окремо. */
object Archive {
    fun list(f: File, name: String): ArchiveDoc {
        val entries = ArrayList<ArcEntry>()
        try {
            ZipFile(f).use { z ->
                for (e in z.entries()) entries.add(ArcEntry(e.name, e.size, e.compressedSize, e.isDirectory, e.time))
            }
        } catch (_: Exception) {
            java.util.zip.ZipInputStream(f.inputStream().buffered()).use { zs ->
                while (true) {
                    val e = zs.nextEntry ?: break
                    entries.add(ArcEntry(e.name, e.size, e.compressedSize, e.isDirectory, e.time))
                }
            }
        }
        return ArchiveDoc(name, entries.filter { !it.dir }.sortedBy { it.path.lowercase() })
    }

    /** Розпакувати один файл у [dest]. */
    fun extract(f: File, path: String, dest: File): Boolean {
        try {
            ZipFile(f).use { z ->
                val e = z.getEntry(path) ?: return false
                z.getInputStream(e).use { inp -> dest.outputStream().use { inp.copyTo(it) } }
                return true
            }
        } catch (_: Exception) {
            java.util.zip.ZipInputStream(f.inputStream().buffered()).use { zs ->
                while (true) {
                    val e = zs.nextEntry ?: break
                    if (e.name == path) { dest.outputStream().use { zs.copyTo(it) }; return true }
                }
            }
        }
        return false
    }
}
