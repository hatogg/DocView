package ua.x44to.docview.core

import java.io.File

/** Стандартна палітра Excel (індексовані кольори 0–63). */
val EXCEL_INDEXED = intArrayOf(
    0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
    0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
    0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
    0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
    0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
    0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
    0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
    0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
)

/** «AB12» → (рядок 11, стовпець 27). */
fun cellRef(ref: String): Pair<Int, Int>? {
    var i = 0
    var col = 0
    while (i < ref.length && ref[i].isLetter()) { col = col * 26 + (ref[i].uppercaseChar() - 'A' + 1); i++ }
    if (i == 0) return null
    val row = ref.substring(i).replace("$", "").toIntOrNull() ?: return null
    return (row - 1) to (col - 1)
}

fun colName(c: Int): String = Docx.letters(c + 1)

/** Excel 2007+ (.xlsx, .xlsm). */
class Xlsx(private val file: File) {
    private val pkg = Pkg(file)
    private val shared = ArrayList<String>()
    private val numFmts = HashMap<Int, String>()
    private class Xf(val fmt: Int, val font: Int, val fill: Int, val align: Align?, val wrap: Boolean)
    private class Font(val b: Boolean, val i: Boolean, val u: Boolean, val color: Int?)
    private val xfs = ArrayList<Xf>()
    private val fonts = ArrayList<Font>()
    private val fills = ArrayList<Int?>()
    private val theme = IntArray(12) { -1 }
    private var date1904 = false
    private val styleCache = HashMap<Int, CellStyle?>()
    var truncated = false

    companion object {
        const val MAX_ROWS = 50_000
        const val MAX_COLS = 400
    }

    fun parse(): SheetDoc = pkg.use {
        readTheme()
        readShared()
        readStyles()
        val wbRels = pkg.rels("xl/workbook.xml")
        val x = pkg.xml("xl/workbook.xml") ?: throw DocException("No workbook.xml")
        val list = ArrayList<Pair<String, String>>()
        while (x.next() != XmlReader.T.EOF) {
            if (x.type != XmlReader.T.START) continue
            when (x.local) {
                "workbookPr" -> date1904 = x.attr("date1904").let { it == "1" || it == "true" }
                "sheet" -> {
                    val state = x.attr("state")
                    val rid = x.attrQ("r:id") ?: x.attr("id")
                    val target = rid?.let { wbRels[it]?.target }
                    if (target != null && state != "hidden" && state != "veryHidden") list.add((x.attr("name") ?: "Sheet") to target)
                }
            }
        }
        val sheets = list.mapNotNull { (name, path) -> if (pkg.exists(path)) sheet(name, path) else null }
        SheetDoc(null, sheets, note = if (truncated) "large" else null)
    }

    private fun color(x: XmlReader): Int? {
        x.attr("rgb")?.let { return hexColor(it) }
        x.attr("indexed")?.toIntOrNull()?.let { if (it in EXCEL_INDEXED.indices) return EXCEL_INDEXED[it] or 0xFF000000.toInt() else return null }
        x.attr("theme")?.toIntOrNull()?.let { t ->
            val base = theme.getOrNull(t)?.takeIf { it >= 0 } ?: return null
            val tint = x.attr("tint")?.toDoubleOrNull() ?: 0.0
            return tint(base, tint)
        }
        return null
    }

    private fun tint(rgb: Int, t: Double): Int {
        fun ch(v: Int): Int = (if (t < 0) v * (1 + t) else v + (255 - v) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch((rgb shr 16) and 0xFF) shl 16) or (ch((rgb shr 8) and 0xFF) shl 8) or ch(rgb and 0xFF)
    }

    private fun readTheme() {
        val x = pkg.xml("xl/theme/theme1.xml") ?: return
        // порядок у файлі: dk1 lt1 dk2 lt2 accent1..6 hlink folHlink; індекси Excel: lt1 dk1 lt2 dk2 …
        val order = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
        var cur: String? = null
        while (x.next() != XmlReader.T.EOF) {
            if (x.type != XmlReader.T.START) continue
            val n = x.local
            if (n in order) cur = n
            else if (cur != null && (n == "srgbClr" || n == "sysClr")) {
                val v = hexColor(x.attr("val")?.takeIf { n == "srgbClr" } ?: x.attr("lastClr"))
                if (v != null) theme[order.indexOf(cur)] = v
                cur = null
            }
            if (n == "clrScheme" && theme.all { it >= 0 }) break
        }
    }

    private fun readShared() {
        val x = pkg.xml("xl/sharedStrings.xml") ?: return
        while (x.next() != XmlReader.T.EOF) {
            if (x.type == XmlReader.T.START && x.local == "si") {
                val sb = StringBuilder()
                x.each { n ->
                    when (n) {
                        "t" -> sb.append(x.readText())
                        "rPh", "phoneticPr" -> x.skip()
                        "r", "rPr" -> if (n == "rPr") x.skip()
                        else -> x.skip()
                    }
                }
                shared.add(sb.toString())
            }
        }
    }

    private fun readStyles() {
        val x = pkg.xml("xl/styles.xml") ?: return
        while (x.next() != XmlReader.T.EOF) {
            if (x.type != XmlReader.T.START) continue
            when (x.local) {
                "numFmt" -> x.attr("numFmtId")?.toIntOrNull()?.let { numFmts[it] = x.attr("formatCode") ?: "General" }
                "fonts" -> x.each { n ->
                    if (n == "font") {
                        var b = false; var i = false; var u = false; var c: Int? = null
                        x.each { fn ->
                            fun on() = x.attr("val").let { it == null || it == "1" || it == "true" }
                            when (fn) {
                                "b" -> b = on(); "i" -> i = on(); "u" -> u = x.attr("val") != "none"
                                "color" -> c = color(x)
                            }
                            x.skip()
                        }
                        fonts.add(Font(b, i, u, c))
                    } else x.skip()
                }
                "fills" -> x.each { n ->
                    if (n == "fill") {
                        var c: Int? = null
                        var solid = false
                        x.each { fn ->
                            when (fn) {
                                "patternFill" -> { solid = x.attr("patternType").let { it != null && it != "none" } }
                                "fgColor" -> if (solid) c = color(x)
                                "stop" -> {}
                                "color" -> if (c == null) c = color(x)
                            }
                            if (fn != "patternFill" && fn != "gradientFill" && fn != "stop") x.skip()
                        }
                        fills.add(c)
                    } else x.skip()
                }
                "cellXfs" -> x.each { n ->
                    if (n == "xf") {
                        val fmt = x.attr("numFmtId")?.toIntOrNull() ?: 0
                        val font = x.attr("fontId")?.toIntOrNull() ?: 0
                        val fill = x.attr("fillId")?.toIntOrNull() ?: 0
                        var al: Align? = null
                        var wrap = false
                        x.each { an ->
                            if (an == "alignment") {
                                al = when (x.attr("horizontal")) { "center", "centerContinuous" -> Align.CENTER; "right" -> Align.END; "left" -> Align.START; "justify" -> Align.JUSTIFY; else -> null }
                                wrap = x.attr("wrapText") == "1" || x.attr("wrapText") == "true"
                            }
                            x.skip()
                        }
                        xfs.add(Xf(fmt, font, fill, al, wrap))
                    } else x.skip()
                }
                "cellStyleXfs", "dxfs", "cellStyles", "colors", "extLst", "tableStyles" -> x.skip()
            }
        }
    }

    private fun style(s: Int): CellStyle? = styleCache.getOrPut(s) {
        val xf = xfs.getOrNull(s) ?: return@getOrPut null
        val f = fonts.getOrNull(xf.font)
        val bg = fills.getOrNull(xf.fill)?.takeIf { (it and 0xFFFFFF) != 0xFFFFFF || it == 0 }
        val col = f?.color?.takeIf { (it and 0xFFFFFF) != 0 }
        if (f?.b != true && f?.i != true && f?.u != true && col == null && bg == null && xf.align == null && !xf.wrap) null
        else CellStyle(f?.b == true, f?.i == true, f?.u == true, col, bg, xf.align, xf.wrap)
    }

    private fun fmtCode(s: Int): String {
        val id = xfs.getOrNull(s)?.fmt ?: 0
        return numFmts[id] ?: NumFmt.builtin(id)
    }

    private fun sheet(name: String, path: String): Sheet {
        val x = pkg.xml(path) ?: return Sheet(name, emptyList(), 0)
        val rows = ArrayList<Array<XCell?>?>()
        val widths = HashMap<Int, Float>()
        val merges = ArrayList<Merge>()
        var maxCol = 0
        var nextRow = 0
        while (x.next() != XmlReader.T.EOF) {
            if (x.type != XmlReader.T.START) continue
            when (x.local) {
                "col" -> {
                    val min = x.attr("min")?.toIntOrNull() ?: continue
                    val max = (x.attr("max")?.toIntOrNull() ?: min).coerceAtMost(min + MAX_COLS)
                    val hidden = x.attr("hidden") == "1" || x.attr("hidden") == "true"
                    val w = if (hidden) 0f else x.attr("width")?.toFloatOrNull() ?: continue
                    for (c in min..max) widths[c - 1] = w
                }
                "row" -> {
                    val r = (x.attr("r")?.toIntOrNull()?.minus(1)) ?: nextRow
                    nextRow = r + 1
                    if (r >= MAX_ROWS) { truncated = true; x.skip(); continue }
                    val cells = HashMap<Int, XCell>()
                    var nextCol = 0
                    x.each { n ->
                        if (n != "c") { x.skip(); return@each }
                        val ref = x.attr("r")?.let { cellRef(it) }
                        val c = ref?.second ?: nextCol
                        nextCol = c + 1
                        val t = x.attr("t")
                        val s = x.attr("s")?.toIntOrNull() ?: 0
                        var v: String? = null
                        var inline: String? = null
                        x.each { cn ->
                            when (cn) {
                                "v" -> v = x.readText()
                                "is" -> inline = x.readText()
                                else -> x.skip()
                            }
                        }
                        if (c >= MAX_COLS) { truncated = true; return@each }
                        val st = style(s)
                        val cell: XCell? = when (t) {
                            "s" -> v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }?.let { XCell(it, false, st) }
                            "inlineStr" -> inline?.let { XCell(it, false, st) }
                            "str" -> v?.let { XCell(it, false, st) }
                            "b" -> v?.let { XCell(if (it.trim() == "1") "TRUE" else "FALSE", false, st?.let { s2 -> CellStyle(s2.b, s2.i, s2.u, s2.color, s2.bg, s2.align ?: Align.CENTER, s2.wrap) } ?: CellStyle(align = Align.CENTER)) }
                            "e" -> v?.let { XCell(it, false, st) }
                            "d" -> v?.let { XCell(it.replace('T', ' ').removeSuffix("Z"), true, st) }
                            else -> v?.trim()?.toDoubleOrNull()?.let { num ->
                                val code = fmtCode(s)
                                XCell(NumFmt.format(num, code, date1904), true, st)
                            } ?: v?.let { XCell(it, false, st) }
                        }
                        if (cell != null && cell.text.isNotEmpty() || st?.bg != null) {
                            cells[c] = cell ?: XCell("", false, st)
                            if (c + 1 > maxCol) maxCol = c + 1
                        }
                    }
                    while (rows.size <= r) rows.add(null)
                    if (cells.isNotEmpty()) {
                        val arr = arrayOfNulls<XCell>(cells.keys.max() + 1)
                        for ((k, v) in cells) arr[k] = v
                        rows[r] = arr
                    }
                }
                "mergeCell" -> {
                    val ref = x.attr("ref") ?: continue
                    val a = cellRef(ref.substringBefore(':'))
                    val b = cellRef(ref.substringAfter(':'))
                    if (a != null && b != null && a.first < MAX_ROWS) merges.add(Merge(a.first, a.second, b.first, b.second))
                }
                "sheetPr", "dimension", "sheetViews", "sheetFormatPr", "pageMargins", "pageSetup", "headerFooter", "drawing",
                "conditionalFormatting", "dataValidations", "extLst", "legacyDrawing", "printOptions", "hyperlinks", "autoFilter", "tableParts" -> x.skip()
            }
        }
        // обрізаємо порожні хвости
        while (rows.isNotEmpty() && rows.last() == null) rows.removeAt(rows.lastIndex)
        val cols = maxCol.coerceAtMost(MAX_COLS)
        val norm = rows.map { r -> if (r == null) arrayOfNulls(0) else r }
        val w = if (widths.isEmpty()) null else FloatArray(cols) { widths[it] ?: -1f }
        return Sheet(name, norm, cols, w, merges)
    }
}
