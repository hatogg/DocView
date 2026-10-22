package ua.x44to.docview.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/** RTF: текст із форматуванням, таблиці, картинки PNG/JPEG, кодування кирилиці (\ansicpg, \fcharset). */
class Rtf(private val file: File) {
    private val s: String = run {
        if (file.length() > 80L * 1024 * 1024) throw DocException("File is too large")
        String(file.readBytes(), Charsets.ISO_8859_1)
    }
    private var pos = 0

    private class State(
        var b: Boolean = false, var i: Boolean = false, var u: Boolean = false, var st: Boolean = false,
        var fs: Int = 24, var cf: Int = 0, var hl: Int = 0, var uc: Int = 1, var font: Int = -1,
        var skip: Boolean = false, var dest: String? = null, var sup: Boolean = false, var sub: Boolean = false,
        var align: Align = Align.START, var inTable: Boolean = false, var style: Int = -1, var li: Int = 0, var caps: Boolean = false,
    ) {
        fun copy() = State(b, i, u, st, fs, cf, hl, uc, font, skip, dest, sup, sub, align, inTable, style, li, caps)
    }

    private var st = State()
    private val stack = ArrayList<State>()
    private var codepage: Charset = cs(1252)
    private val fontCharset = HashMap<Int, Charset>()
    private val colors = ArrayList<Int?>()
    private val styleHeading = HashMap<Int, Int>()
    private var title: String? = null

    private val blocks = ArrayList<Block>()
    private val toc = ArrayList<TocItem>()
    private val buf = RunBuf()
    private val bytes = ByteArrayOutputStream()
    private val images = HashMap<String, ByteArray>()

    // таблиця
    private var rows = ArrayList<Row>()
    private var cells = ArrayList<Cell>()
    private var cellBlocks = ArrayList<Block>()

    // тимчасові буфери для службових груп
    private val destText = StringBuilder()
    private var pictHex: StringBuilder? = null
    private var pictType = ""
    private var pictW = 0
    private var pictH = 0

    private fun cs(cp: Int): Charset = runCatching {
        when (cp) {
            65001 -> Charsets.UTF_8
            932 -> Charset.forName("Shift_JIS"); 936 -> Charset.forName("GBK"); 950 -> Charset.forName("Big5"); 949 -> Charset.forName("x-windows-949")
            10000 -> Charset.forName("x-MacRoman")
            else -> Charset.forName("windows-$cp")
        }
    }.getOrDefault(Charsets.ISO_8859_1)

    private fun charsetFor(fcharset: Int): Charset? = when (fcharset) {
        0 -> cs(1252); 204 -> cs(1251); 238 -> cs(1250); 161 -> cs(1253); 162 -> cs(1254); 186 -> cs(1257)
        177 -> cs(1255); 178 -> cs(1256); 222 -> cs(874); 128 -> cs(932); 134 -> cs(936); 136 -> cs(950); 129 -> cs(949)
        163 -> cs(1258)
        else -> null
    }

    private fun curCharset(): Charset = fontCharset[st.font] ?: codepage

    fun parse(): FlowDoc {
        if (!s.startsWith("{\\rtf")) throw DocException("Not RTF")
        while (pos < s.length) {
            val c = s[pos]
            when (c) {
                '{' -> { flushBytes(); stack.add(st); st = st.copy(); pos++ }
                '}' -> { flushBytes(); endGroup(); pos++ }
                '\\' -> control()
                '\r', '\n' -> pos++
                else -> {
                    if (st.skip) { pos++; continue }
                    if (st.dest == "pict" || st.dest == "colortbl") { destChar(c); pos++; continue }
                    // звичайний текст: 8-бітні символи — у кодуванні документа
                    bytes.write(c.code and 0xFF)
                    pos++
                }
            }
        }
        flushBytes()
        endPara()
        flushTable()
        return FlowDoc(title, blocks, Resources.map(images), FlowKind.DOCUMENT, toc, basePt = 12f)
    }

    private fun destChar(c: Char) {
        when (st.dest) {
            "pict" -> if (c.isLetterOrDigit()) pictHex?.append(c)
            "colortbl" -> if (c == ';') {
                colors.add(if (colorHas) colorAcc or 0xFF000000.toInt() else null)
                colorAcc = 0
                colorHas = false
            }
            else -> destText.append(c)
        }
    }

    private fun endGroup() {
        val ending = st
        st = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else State()
        if (ending.dest != null && ending.dest != st.dest) finishDest(ending.dest!!)
    }

    private fun finishDest(dest: String) {
        when (dest) {
            "fonttblentry" -> {}
            "colortbl" -> {}
            "title" -> title = destText.toString().trim().takeIf { it.isNotEmpty() }
            "stylesheet-entry" -> {
                val name = destText.toString().trim().trimEnd(';').lowercase()
                Regex("(heading|заголовок)\\s*(\\d)").find(name)?.let { m -> if (pendingStyle >= 0) styleHeading[pendingStyle] = m.groupValues[2].toInt() }
                if (name == "title" && pendingStyle >= 0) styleHeading[pendingStyle] = 1
                pendingStyle = -1
            }
            "pict" -> {
                val hex = pictHex?.toString() ?: ""
                if ((pictType == "png" || pictType == "jpeg") && hex.length > 16) {
                    val data = ByteArray(hex.length / 2) { k -> ((Character.digit(hex[k * 2], 16) shl 4) or Character.digit(hex[k * 2 + 1], 16)).toByte() }
                    val key = "rtfimg:${images.size}"
                    images[key] = data
                    flushRuns()
                    if (!st.inTable) flushTable()
                    val img = Image(key, pictW.takeIf { it > 0 }?.div(20f), pictH.takeIf { it > 0 }?.div(20f))
                    if (st.inTable) cellBlocks.add(img) else blocks.add(img)
                }
                pictHex = null
            }
        }
        destText.setLength(0)
    }

    private var pendingStyle = -1

    private fun flushBytes() {
        if (bytes.size() == 0) return
        val text = String(bytes.toByteArray(), curCharset())
        bytes.reset()
        if (st.skip || st.dest != null) {
            if (st.dest != null && st.dest != "pict") destText.append(text)
            return
        }
        addText(text)
    }

    private fun addText(t: String) {
        val color = colors.getOrNull(st.cf)
        val bg = colors.getOrNull(st.hl)
        buf.add(Run(if (st.caps) t.uppercase() else t, st.b, st.i, st.u, st.st, color, bg, st.fs / 2f, sup = st.sup, sub = st.sub))
    }

    private fun flushRuns() {
        flushBytes()
        if (!buf.isEmpty()) endPara()
    }

    private fun endPara() {
        flushBytes()
        val runs = buf.take()
        val heading = styleHeading[st.style] ?: 0
        val p = Para(runs, heading, st.align, (st.li / 720).coerceIn(0, 6))
        if (st.inTable) {
            cellBlocks.add(p)
        } else {
            flushTable()
            if (heading > 0 && p.text.isNotBlank()) toc.add(TocItem(p.text.trim(), blocks.size, heading - 1))
            blocks.add(p)
        }
    }

    private fun flushTable() {
        if (cells.isNotEmpty()) { rows.add(Row(cells)); cells = ArrayList() }
        if (rows.isNotEmpty()) { blocks.add(Table(rows)); rows = ArrayList() }
    }

    private fun control() {
        pos++
        if (pos >= s.length) return
        val c = s[pos]
        if (!c.isLetter()) {
            pos++
            when (c) {
                '\'' -> {
                    val h = s.substring(pos, minOf(pos + 2, s.length))
                    pos += 2
                    h.toIntOrNull(16)?.let { if (!st.skip) { if (st.dest == "pict") {} else bytes.write(it) } }
                }
                '*' -> starPending = true
                '~' -> text(" ")
                '-' -> {}
                '_' -> text("‑")
                '\\', '{', '}' -> { if (!st.skip) { if (st.dest != null) destText.append(c) else bytes.write(c.code) } }
                '\n', '\r' -> word("par", null)
                else -> {}
            }
            return
        }
        val start = pos
        while (pos < s.length && s[pos].isLetter()) pos++
        val w = s.substring(start, pos)
        var param: Int? = null
        if (pos < s.length && (s[pos] == '-' || s[pos].isDigit())) {
            val ps = pos
            pos++
            while (pos < s.length && s[pos].isDigit()) pos++
            param = s.substring(ps, pos).toIntOrNull()
        }
        if (pos < s.length && s[pos] == ' ') pos++
        word(w, param)
    }

    private var starPending = false

    private fun text(t: String) {
        flushBytes()
        if (st.skip) return
        if (st.dest != null) { destText.append(t); return }
        addText(t)
    }

    private val skipDest = setOf(
        "header", "footer", "headerl", "headerr", "headerf", "footerl", "footerr", "footerf", "footnote", "annotation",
        "fldinst", "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "themedata", "colorschememapping",
        "datastore", "latentstyles", "mmathPr", "pgdsctbl", "nonshppict", "objdata", "objclass", "bkmkstart", "bkmkend",
        "author", "operator", "keywords", "subject", "comment", "doccomm", "company", "category", "revtbl", "pnseclvl",
        "xe", "tc", "atnid", "atnauthor", "falt", "panose", "fname", "listtext", "pntext", "pntxta", "pntxtb", "userprops",
        "wgrffmtfilter", "factoidname", "protusertbl", "ftnsep", "ftnsepc", "aftnsep", "aftnsepc", "sp", "sn", "sv",
    )

    private fun word(w: String, p: Int?) {
        val star = starPending
        starPending = false
        // службові групи
        when (w) {
            "fonttbl" -> { flushBytes(); st.dest = "fonttbl"; return }
            "colortbl" -> { flushBytes(); st.dest = "colortbl"; colorAcc = 0; colorHas = false; return }
            "stylesheet" -> { flushBytes(); st.dest = "stylesheet"; return }
            "info" -> { flushBytes(); st.dest = "info"; return }
            "title" -> if (st.dest == "info") { st.dest = "title"; return }
            "pict" -> { flushBytes(); st.dest = "pict"; pictHex = StringBuilder(); pictType = ""; pictW = 0; pictH = 0; return }
            "pngblip" -> { pictType = "png"; return }
            "jpegblip" -> { pictType = "jpeg"; return }
            "picwgoal" -> { pictW = p ?: 0; return }
            "pichgoal" -> { pictH = p ?: 0; return }
            "shppict" -> return
            "fldrslt" -> return
        }
        if (w in skipDest || (star && w != "shppict" && st.dest == null)) {
            flushBytes()
            st.skip = true
            return
        }
        if (st.dest == "fonttbl") {
            when (w) {
                "f" -> curFont = p ?: 0
                "fcharset" -> charsetFor(p ?: 0)?.let { fontCharset[curFont] = it }
            }
            return
        }
        if (st.dest == "colortbl") {
            when (w) {
                "red" -> { colorAcc = (colorAcc and 0x00FFFF) or ((p ?: 0) shl 16); colorHas = true }
                "green" -> { colorAcc = (colorAcc and 0xFF00FF) or ((p ?: 0) shl 8); colorHas = true }
                "blue" -> { colorAcc = (colorAcc and 0xFFFF00) or (p ?: 0); colorHas = true }
            }
            return
        }
        if (st.dest == "stylesheet") {
            if (w == "s") { pendingStyle = p ?: -1; st.dest = "stylesheet-entry" }
            return
        }
        if (st.dest == "stylesheet-entry") return
        if (st.dest != null || st.skip) {
            if (w == "u" && p != null && st.dest != null && st.dest != "pict") { flushBytes(); destText.append(unicode(p)); ucSkip = st.uc; skipFallback() }
            return
        }
        when (w) {
            "ansicpg" -> codepage = cs(p ?: 1252)
            "par", "sect" -> endPara()
            "line" -> text("\n")
            "tab" -> text("\t")
            "page" -> { endPara(); blocks.add(Rule(pageBreak = true)) }
            "pard" -> { flushBytes(); st.align = Align.START; st.inTable = false; st.style = -1; st.li = 0 }
            "plain" -> { flushBytes(); st.b = false; st.i = false; st.u = false; st.st = false; st.fs = 24; st.cf = 0; st.hl = 0; st.sup = false; st.sub = false; st.caps = false }
            "b" -> { flushBytes(); st.b = p != 0 }
            "i" -> { flushBytes(); st.i = p != 0 }
            "ul", "uld", "uldb", "ulw", "ulth" -> { flushBytes(); st.u = p != 0 }
            "ulnone" -> { flushBytes(); st.u = false }
            "strike", "striked" -> { flushBytes(); st.st = p != 0 }
            "caps", "scaps" -> { flushBytes(); st.caps = p != 0 }
            "fs" -> { flushBytes(); st.fs = p ?: 24 }
            "cf" -> { flushBytes(); st.cf = p ?: 0 }
            "highlight", "cb", "chcbpat" -> { flushBytes(); st.hl = p ?: 0 }
            "f" -> { flushBytes(); st.font = p ?: -1 }
            "super" -> { flushBytes(); st.sup = true; st.sub = false }
            "sub" -> { flushBytes(); st.sub = true; st.sup = false }
            "nosupersub" -> { flushBytes(); st.sup = false; st.sub = false }
            "qc" -> st.align = Align.CENTER
            "qr" -> st.align = Align.END
            "qj" -> st.align = Align.JUSTIFY
            "ql" -> st.align = Align.START
            "li" -> st.li = p ?: 0
            "s" -> st.style = p ?: -1
            "intbl" -> st.inTable = true
            "cell", "nestcell" -> {
                flushBytes()
                if (!buf.isEmpty() || cellBlocks.isEmpty()) { st.inTable = true; endPara() }
                cells.add(Cell(cellBlocks))
                cellBlocks = ArrayList()
            }
            "row", "nestrow" -> {
                flushBytes()
                if (cells.isNotEmpty()) rows.add(Row(cells))
                cells = ArrayList()
            }
            "uc" -> st.uc = p ?: 1
            "u" -> {
                flushBytes()
                text(unicode(p ?: 0))
                ucSkip = st.uc
                skipFallback()
            }
            "emdash" -> text("—"); "endash" -> text("–"); "bullet" -> text("•")
            "lquote" -> text("‘"); "rquote" -> text("’"); "ldblquote" -> text("“"); "rdblquote" -> text("”")
            "emspace" -> text(" "); "enspace" -> text(" "); "qmspace" -> text(" ")
        }
    }

    private var ucSkip = 0
    private var curFont = 0
    private var colorAcc = 0
    private var colorHas = false

    /** Після \uN пропускаємо N «запасних» символів (зазвичай «?» або \'hh). */
    private fun skipFallback() {
        var n = ucSkip
        while (n > 0 && pos < s.length) {
            val c = s[pos]
            when {
                c == '\\' && pos + 1 < s.length && s[pos + 1] == '\'' -> { pos += 4; n-- }
                c == '\\' || c == '{' || c == '}' -> return
                c == '\r' || c == '\n' -> pos++
                else -> { pos++; n-- }
            }
        }
        ucSkip = 0
    }

    private fun unicode(p: Int): String {
        val code = if (p < 0) p + 65536 else p
        return code.toChar().toString()
    }
}
