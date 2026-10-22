package ua.x44to.docview.core

import java.io.File
import java.nio.charset.Charset

/** Excel 97–2003 (.xls, BIFF8; частково BIFF5). Беремо значення комірок, формати чисел і просте оформлення. */
class Xls(private val file: File) {
    private lateinit var b: ByteArray
    private var date1904 = false
    private var biff5 = false
    private var codepage: Charset = Charsets.ISO_8859_1
    private val formats = HashMap<Int, String>()
    private class XfRec(val font: Int, val fmt: Int, val align: Align?, val wrap: Boolean, val fill: Int?)
    private val xfs = ArrayList<XfRec>()
    private class FontRec(val b: Boolean, val i: Boolean, val u: Boolean, val color: Int)
    private val fonts = ArrayList<FontRec>()
    private val palette = EXCEL_INDEXED.copyOf()
    private val sst = ArrayList<String>()
    private var truncated = false

    private class Rec(val type: Int, val off: Int, val len: Int)

    fun parse(): SheetDoc {
        val cfb = Cfb.open(file)
        b = cfb.stream("Workbook") ?: cfb.stream("Book") ?: throw DocException("No Workbook stream")
        if (cfb.has("EncryptionInfo") || cfb.has("encryption")) throw DocException("Encrypted", needsPassword = true)
        val sheets = ArrayList<Pair<String, Int>>()
        var p = 0
        var first = true
        while (p + 4 <= b.size) {
            val t = le16(b, p)
            val len = le16(b, p + 2)
            val o = p + 4
            if (o + len > b.size) break
            when (t) {
                0x0809 -> if (first) { biff5 = le16(b, o) == 0x0500; first = false }
                0x002F -> throw DocException("Encrypted", needsPassword = true) // FILEPASS
                0x0022 -> date1904 = le16(b, o) == 1
                0x0042 -> codepage = cp(le16(b, o))
                0x041E -> {
                    val id = le16(b, o)
                    formats[id] = if (biff5) str8(o + 2, b[o + 2].toInt() and 0xFF, o + 3) else xlString(o + 2).first
                }
                0x0031 -> {
                    val grbit = le16(b, o + 2)
                    val icv = le16(b, o + 4)
                    val bls = le16(b, o + 6)
                    val uls = b[o + 10].toInt()
                    fonts.add(FontRec(bls >= 700, grbit and 0x02 != 0, uls != 0, icv))
                }
                0x00E0 -> {
                    val ifnt = le16(b, o)
                    val ifmt = le16(b, o + 2)
                    val alc = b[o + 6].toInt() and 0x07
                    val wrap = b[o + 6].toInt() and 0x08 != 0
                    var fill: Int? = null
                    if (!biff5 && len >= 20) {
                        val fls = (le32(b, o + 14) ushr 26) and 0x3F
                        val icvFore = le16(b, o + 18) and 0x7F
                        if (fls == 1) fill = icvFore
                    }
                    val al = when (alc) { 1 -> Align.START; 2, 6 -> Align.CENTER; 3 -> Align.END; 5 -> Align.JUSTIFY; else -> null }
                    xfs.add(XfRec(ifnt, ifmt, al, wrap, fill))
                }
                0x0092 -> {
                    val n = le16(b, o)
                    for (i in 0 until n) if (8 + i < palette.size) {
                        val q = o + 2 + i * 4
                        palette[8 + i] = ((b[q].toInt() and 0xFF) shl 16) or ((b[q + 1].toInt() and 0xFF) shl 8) or (b[q + 2].toInt() and 0xFF)
                    }
                }
                0x0085 -> {
                    val pos = le32(b, o)
                    val hidden = b[o + 4].toInt() and 0x03
                    val dt = b[o + 5].toInt()
                    val name = if (biff5) str8(o + 7, b[o + 6].toInt() and 0xFF, o + 7) else shortXlString(o + 6)
                    if (hidden == 0 && dt == 0) sheets.add(name to pos)
                }
                0x00FC -> readSst(p)
                0x000A -> {
                    // кінець глобальної частини
                    p = o + len
                    if (sheets.isNotEmpty()) break
                    continue
                }
            }
            p = o + len
        }
        val out = sheets.mapNotNull { (n, pos) -> runCatching { sheet(n, pos) }.getOrNull() }
        return SheetDoc(null, out, note = if (truncated) "large" else null)
    }

    private fun cp(v: Int): Charset = runCatching {
        when (v) {
            1200 -> Charsets.UTF_16LE
            0x8000, 10000 -> Charset.forName("x-MacRoman")
            else -> Charset.forName("windows-$v")
        }
    }.getOrDefault(Charsets.ISO_8859_1)

    private fun str8(off: Int, len: Int, start: Int): String = if (start + len <= b.size) String(b, start, len, codepage) else ""

    /** XLUnicodeString: cch(2) grbit(1) символи. Повертає рядок і довжину в байтах. */
    private fun xlString(o: Int): Pair<String, Int> {
        val cch = le16(b, o)
        val high = b[o + 2].toInt() and 0x01 != 0
        val n = if (high) cch * 2 else cch
        val s = if (o + 3 + n <= b.size) String(b, o + 3, n, if (high) Charsets.UTF_16LE else Charsets.ISO_8859_1) else ""
        return s to (3 + n)
    }

    private fun shortXlString(o: Int): String {
        val cch = b[o].toInt() and 0xFF
        val high = b[o + 1].toInt() and 0x01 != 0
        val n = if (high) cch * 2 else cch
        return if (o + 2 + n <= b.size) String(b, o + 2, n, if (high) Charsets.UTF_16LE else Charsets.ISO_8859_1) else ""
    }

    /** SST разом із записами CONTINUE: рядки можуть розриватися посередині, тоді на межі — новий байт grbit. */
    private fun readSst(recStart: Int) {
        val data = java.io.ByteArrayOutputStream()
        val bounds = ArrayList<Int>()
        var p = recStart
        var firstRec = true
        while (p + 4 <= b.size) {
            val t = le16(b, p)
            val len = le16(b, p + 2)
            if (!firstRec && t != 0x003C) break
            if (!firstRec) bounds.add(data.size())
            data.write(b, p + 4, minOf(len, b.size - p - 4))
            p += 4 + len
            firstRec = false
        }
        val d = data.toByteArray()
        val bset = bounds.toHashSet()
        val unique = le32(d, 4)
        var q = 8
        for (k in 0 until unique) {
            if (q + 3 > d.size) break
            val cch = le16(d, q)
            var grbit = d[q + 2].toInt()
            q += 3
            var high = grbit and 0x01 != 0
            val rich = grbit and 0x08 != 0
            val ext = grbit and 0x04 != 0
            var runs = 0
            var extLen = 0
            if (rich) { runs = le16(d, q); q += 2 }
            if (ext) { extLen = le32(d, q); q += 4 }
            val sb = StringBuilder(cch)
            var c = 0
            while (c < cch && q < d.size) {
                if (c > 0 && q in bset) {
                    grbit = d[q].toInt(); high = grbit and 0x01 != 0; q++
                    continue
                }
                if (high) {
                    if (q + 1 >= d.size) break
                    sb.append(((d[q].toInt() and 0xFF) or ((d[q + 1].toInt() and 0xFF) shl 8)).toChar()); q += 2
                } else {
                    sb.append((d[q].toInt() and 0xFF).toChar()); q++
                }
                c++
            }
            q += runs * 4 + extLen
            sst.add(sb.toString())
        }
    }

    private fun rk(v: Int): Double {
        val x = if (v and 0x02 != 0) (v shr 2).toDouble()
        else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        return if (v and 0x01 != 0) x / 100.0 else x
    }

    private fun color(icv: Int): Int? = when {
        icv in palette.indices -> palette[icv] or 0xFF000000.toInt()
        else -> null
    }

    private val styleCache = HashMap<Int, CellStyle?>()
    private fun style(ixfe: Int): CellStyle? = styleCache.getOrPut(ixfe) {
        val xf = xfs.getOrNull(ixfe) ?: return@getOrPut null
        val f = fonts.getOrNull(if (xf.font >= 4) xf.font - 1 else xf.font)
        val col = f?.color?.let { color(it) }?.takeIf { (it and 0xFFFFFF) != 0 && f.color != 0x7FFF }
        val bg = xf.fill?.let { color(it) }?.takeIf { (it and 0xFFFFFF) != 0xFFFFFF }
        if (f?.b != true && f?.i != true && f?.u != true && col == null && bg == null && xf.align == null && !xf.wrap) null
        else CellStyle(f?.b == true, f?.i == true, f?.u == true, col, bg, xf.align, xf.wrap)
    }

    private fun num(v: Double, ixfe: Int): String {
        val fid = xfs.getOrNull(ixfe)?.fmt ?: 0
        return NumFmt.format(v, formats[fid] ?: NumFmt.builtin(fid), date1904)
    }

    private fun sheet(name: String, pos: Int): Sheet {
        val cells = HashMap<Int, HashMap<Int, XCell>>()
        val merges = ArrayList<Merge>()
        val widths = HashMap<Int, Float>()
        var maxRow = -1
        var maxCol = -1
        var p = pos
        var pendingFormula: Triple<Int, Int, Int>? = null
        fun put(r: Int, c: Int, cell: XCell) {
            if (r >= Xlsx.MAX_ROWS || c >= Xlsx.MAX_COLS) { truncated = true; return }
            cells.getOrPut(r) { HashMap() }[c] = cell
            if (r > maxRow) maxRow = r
            if (c > maxCol) maxCol = c
        }
        var first = true
        while (p + 4 <= b.size) {
            val t = le16(b, p)
            val len = le16(b, p + 2)
            val o = p + 4
            if (o + len > b.size) break
            if (first && t != 0x0809) break
            first = false
            when (t) {
                0x00FD -> {
                    val r = le16(b, o); val c = le16(b, o + 2); val x = le16(b, o + 4)
                    sst.getOrNull(le32(b, o + 6))?.let { put(r, c, XCell(it, false, style(x))) }
                }
                0x0204, 0x00D6 -> {
                    val r = le16(b, o); val c = le16(b, o + 2); val x = le16(b, o + 4)
                    val s = if (biff5) str8(0, le16(b, o + 6), o + 8) else xlString(o + 6).first
                    put(r, c, XCell(s, false, style(x)))
                }
                0x0203 -> {
                    val r = le16(b, o); val c = le16(b, o + 2); val x = le16(b, o + 4)
                    put(r, c, XCell(num(le64d(b, o + 6), x), true, style(x)))
                }
                0x027E -> {
                    val r = le16(b, o); val c = le16(b, o + 2); val x = le16(b, o + 4)
                    put(r, c, XCell(num(rk(le32(b, o + 6)), x), true, style(x)))
                }
                0x00BD -> {
                    val r = le16(b, o); val c0 = le16(b, o + 2)
                    val n = (len - 6) / 6
                    for (i in 0 until n) {
                        val q = o + 4 + i * 6
                        val x = le16(b, q)
                        put(r, c0 + i, XCell(num(rk(le32(b, q + 2)), x), true, style(x)))
                    }
                }
                0x0205 -> {
                    val r = le16(b, o); val c = le16(b, o + 2); val x = le16(b, o + 4)
                    val v = b[o + 6].toInt() and 0xFF
                    val err = b[o + 7].toInt() != 0
                    put(r, c, XCell(if (err) errText(v) else if (v != 0) "TRUE" else "FALSE", false, style(x)))
                }
                0x0006 -> {
                    val r = le16(b, o); val c = le16(b, o + 2); val x = le16(b, o + 4)
                    if ((b[o + 12].toInt() and 0xFF) == 0xFF && (b[o + 13].toInt() and 0xFF) == 0xFF) {
                        when (b[o + 6].toInt()) {
                            0 -> pendingFormula = Triple(r, c, x)
                            1 -> put(r, c, XCell(if (b[o + 8].toInt() != 0) "TRUE" else "FALSE", false, style(x)))
                            2 -> put(r, c, XCell(errText(b[o + 8].toInt() and 0xFF), false, style(x)))
                        }
                    } else put(r, c, XCell(num(le64d(b, o + 6), x), true, style(x)))
                }
                0x0207 -> pendingFormula?.let { (r, c, x) ->
                    val s = if (biff5) str8(0, le16(b, o), o + 2) else xlString(o).first
                    put(r, c, XCell(s, false, style(x)))
                    pendingFormula = null
                }
                0x00E5 -> {
                    val n = le16(b, o)
                    for (i in 0 until n) {
                        val q = o + 2 + i * 8
                        if (q + 8 > o + len) break
                        merges.add(Merge(le16(b, q), le16(b, q + 4), le16(b, q + 2), le16(b, q + 6)))
                    }
                }
                0x007D -> {
                    val c1 = le16(b, o); val c2 = minOf(le16(b, o + 2), c1 + Xlsx.MAX_COLS)
                    val w = le16(b, o + 4) / 256f
                    val hidden = le16(b, o + 8) and 0x01 != 0
                    for (c in c1..c2) widths[c] = if (hidden) 0f else w
                }
                0x000A -> break
            }
            p = o + len
        }
        val cols = (maxCol + 1).coerceAtMost(Xlsx.MAX_COLS)
        val rows = (0..maxRow).map { r ->
            val m = cells[r] ?: return@map arrayOfNulls<XCell>(0)
            val arr = arrayOfNulls<XCell>(m.keys.max() + 1)
            for ((k, v) in m) arr[k] = v
            arr
        }
        val w = if (widths.isEmpty()) null else FloatArray(cols) { widths[it] ?: -1f }
        return Sheet(name, rows, cols, w, merges)
    }

    private fun errText(v: Int) = when (v) {
        0x00 -> "#NULL!"; 0x07 -> "#DIV/0!"; 0x0F -> "#VALUE!"; 0x17 -> "#REF!"; 0x1D -> "#NAME?"; 0x24 -> "#NUM!"; 0x2A -> "#N/A"
        else -> "#ERR"
    }
}
