package ua.x44to.docview.core

import java.io.File
import java.nio.charset.Charset

/**
 * Word 97–2003 (.doc). Текст через таблицю шматків (piece table), абзаци й таблиці — через PAPX,
 * жирний/курсив/колір/розмір — через CHPX, заголовки — за вбудованими стилями, картинки — з потоку Data.
 */
class Doc97(private val file: File) {
    private lateinit var wd: ByteArray
    private lateinit var tbl: ByteArray
    private var data: ByteArray? = null

    private class Piece(val cpStart: Int, val cpEnd: Int, val fc: Int, val compressed: Boolean)
    private val pieces = ArrayList<Piece>()

    /** Діапазон байтів у WordDocument із властивостями. */
    private class FcRange<T>(val start: Int, val end: Int, val v: T)
    private class PapP(val istd: Int, val inTable: Boolean, val ttp: Boolean, val jc: Int, val ilvl: Int, val ilfo: Int, val depth: Int)
    private class ChpP(val b: Boolean, val i: Boolean, val u: Boolean, val s: Boolean, val hps: Int, val color: Int?, val bg: Int?, val pic: Int, val special: Boolean, val vanish: Boolean, val sup: Boolean, val sub: Boolean)

    private val papx = ArrayList<FcRange<PapP>>()
    private val chpx = ArrayList<FcRange<ChpP>>()
    private val headingByIstd = HashMap<Int, Int>()
    private val titleIstd = HashSet<Int>()

    fun parse(): FlowDoc {
        val cfb = Cfb.open(file)
        wd = cfb.stream("WordDocument") ?: throw DocException("No WordDocument")
        val ident = le16(wd, 0)
        if (ident == 0xA5DC) return old(cfb)
        if (ident != 0xA5EC) throw DocException("Unsupported Word version")
        val flags = le16(wd, 0x0A)
        if (flags and 0x0100 != 0) throw DocException("Encrypted", needsPassword = true)
        val nFib = le16(wd, 2)
        if (nFib < 0x00C1) return old(cfb)
        tbl = cfb.stream(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: throw DocException("No table stream")
        data = cfb.stream("Data")

        // FIB: база (32 байти) → fibRgW → fibRgLw → fibRgFcLcb
        val csw = le16(wd, 32)
        val lwOff = 34 + csw * 2
        val cslw = le16(wd, lwOff)
        val lw = lwOff + 2
        val ccpText = le32(wd, lw + 3 * 4)
        val fcOff = lw + cslw * 4 + 2
        fun fc(i: Int) = le32(wd, fcOff + i * 8)
        fun lcb(i: Int) = le32(wd, fcOff + i * 8 + 4)

        readPieces(fc(33), lcb(33), ccpText)
        runCatching { readStyles(fc(1), lcb(1)) }
        runCatching { readPapx(fc(13), lcb(13)) }
        runCatching { readChpx(fc(12), lcb(12)) }
        val title = runCatching { Ole.summaryTitle(cfb) }.getOrNull()
        return build(ccpText, title)
    }

    // ───────── шматки тексту ─────────

    private fun readPieces(fcClx: Int, lcbClx: Int, ccpText: Int) {
        var p = fcClx
        val end = fcClx + lcbClx
        while (p < end && p < tbl.size) {
            when (tbl[p].toInt()) {
                0x01 -> p += 3 + le16(tbl, p + 1)
                0x02 -> {
                    val lcb = le32(tbl, p + 1)
                    val n = (lcb - 4) / 12
                    val cps = p + 5
                    val pcds = cps + (n + 1) * 4
                    for (i in 0 until n) {
                        val cp0 = le32(tbl, cps + i * 4)
                        val cp1 = le32(tbl, cps + (i + 1) * 4)
                        val raw = le32(tbl, pcds + i * 8 + 2)
                        val compressed = raw and 0x40000000 != 0
                        val fc = if (compressed) (raw and 0x3FFFFFFF) / 2 else raw
                        pieces.add(Piece(cp0, cp1, fc, compressed))
                    }
                    return
                }
                else -> return
            }
        }
        // без таблиці шматків — увесь текст одним шматком (дуже старі файли)
        if (pieces.isEmpty()) {
            val fcMin = le32(wd, 0x18)
            pieces.add(Piece(0, ccpText, fcMin, true))
        }
    }

    private val cp1252: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    // ───────── стилі: лише заголовки ─────────

    private fun readStyles(fcStsh: Int, lcb: Int) {
        if (lcb <= 0) return
        val cbStshi = le16(tbl, fcStsh)
        val stshi = fcStsh + 2
        val cstd = le16(tbl, stshi)
        val cbBase = le16(tbl, stshi + 2)
        var p = stshi + cbStshi
        for (istd in 0 until cstd) {
            if (p + 2 > tbl.size) break
            val cb = le16(tbl, p)
            if (cb > 0) {
                val sti = le16(tbl, p + 2) and 0x0FFF
                if (sti in 1..9) headingByIstd[istd] = sti
                if (sti == 62) titleIstd.add(istd)
                // ім'я стилю (для «Заголовок 1», якщо sti невідомий)
                if (sti == 0x0FFE || sti == 0x0FFF || sti > 9) {
                    val nameOff = p + 2 + cbBase
                    val cch = le16(tbl, nameOff)
                    if (cch in 1..80 && nameOff + 2 + cch * 2 <= tbl.size) {
                        val nm = String(tbl, nameOff + 2, cch * 2, Charsets.UTF_16LE).lowercase()
                        Regex("^(heading|заголовок)\\s*(\\d)").find(nm)?.let { headingByIstd[istd] = it.groupValues[2].toInt() }
                        if (nm == "title" || nm == "название") titleIstd.add(istd)
                    }
                }
            }
            p += 2 + cb
        }
    }

    // ───────── властивості абзаців і символів ─────────

    /** Довжина операнда sprm за полем spra. */
    private fun sprmLen(b: ByteArray, p: Int, sprm: Int): Int = when (sprm ushr 13) {
        0, 1 -> 1
        2, 4, 5 -> 2
        3 -> 4
        7 -> 3
        else -> if (sprm == 0xD608 || sprm == 0xD606) le16(b, p) + 1 else (b.getOrNull(p)?.toInt()?.and(0xFF) ?: 0) + 1
    }

    private fun readPapx(fcPlc: Int, lcb: Int) {
        if (lcb <= 4) return
        val n = (lcb - 4) / 8
        for (i in 0 until n) {
            val pn = le32(tbl, fcPlc + (n + 1) * 4 + i * 4) and 0x3FFFFF
            val page = pn * 512
            if (page + 512 > wd.size) continue
            val crun = wd[page + 511].toInt() and 0xFF
            for (k in 0 until crun) {
                val fs = le32(wd, page + k * 4)
                val fe = le32(wd, page + (k + 1) * 4)
                val bx = page + (crun + 1) * 4 + k * 13
                val off = (wd[bx].toInt() and 0xFF) * 2
                var istd = 0; var inTable = false; var ttp = false; var jc = 0; var ilvl = 0; var ilfo = 0; var depth = 0
                if (off != 0) {
                    var q = page + off
                    var cb = wd[q].toInt() and 0xFF
                    q++
                    val len = if (cb == 0) { cb = wd[q].toInt() and 0xFF; q++; cb * 2 } else cb * 2 - 1
                    istd = le16(wd, q)
                    var s = q + 2
                    val end = minOf(q + len, page + 511)
                    while (s + 2 <= end) {
                        val sprm = le16(wd, s)
                        val op = s + 2
                        val l = sprmLen(wd, op, sprm)
                        when (sprm) {
                            0x2416 -> inTable = wd[op].toInt() != 0
                            0x2417 -> ttp = wd[op].toInt() != 0
                            0x6649 -> { depth = le32(wd, op); if (depth > 0) inTable = true }
                            0x664A -> {}
                            0x244B -> { if (wd[op].toInt() != 0) { inTable = true; if (depth == 0) depth = 1 } }
                            0x244C -> if (wd[op].toInt() != 0) ttp = true
                            0x2403, 0x2461 -> jc = wd[op].toInt()
                            0x260A -> ilvl = wd[op].toInt()
                            0x460B -> ilfo = le16(wd, op)
                        }
                        s = op + l
                    }
                }
                papx.add(FcRange(fs, fe, PapP(istd, inTable, ttp, jc, ilvl, ilfo, depth)))
            }
        }
        papx.sortBy { it.start }
    }

    private val ico = intArrayOf(
        -1, 0x000000, 0x0000FF, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0xFFFF00, 0xFFFFFF,
        0x000080, 0x008080, 0x008000, 0x800080, 0x800000, 0x808000, 0x808080, 0xC0C0C0,
    )

    private fun readChpx(fcPlc: Int, lcb: Int) {
        if (lcb <= 4) return
        val n = (lcb - 4) / 8
        for (i in 0 until n) {
            val pn = le32(tbl, fcPlc + (n + 1) * 4 + i * 4) and 0x3FFFFF
            val page = pn * 512
            if (page + 512 > wd.size) continue
            val crun = wd[page + 511].toInt() and 0xFF
            for (k in 0 until crun) {
                val fs = le32(wd, page + k * 4)
                val fe = le32(wd, page + (k + 1) * 4)
                val off = (wd[page + (crun + 1) * 4 + k].toInt() and 0xFF) * 2
                var b = false; var it = false; var u = false; var st = false; var hps = 0; var color: Int? = null; var bg: Int? = null
                var pic = -1; var special = false; var vanish = false; var sup = false; var sub = false
                if (off != 0) {
                    val cb = wd[page + off].toInt() and 0xFF
                    var s = page + off + 1
                    val end = minOf(s + cb, page + 511)
                    while (s + 2 <= end) {
                        val sprm = le16(wd, s)
                        val op = s + 2
                        val l = sprmLen(wd, op, sprm)
                        fun on() = (wd[op].toInt() and 0xFF).let { v -> v == 1 || v == 0x81 }
                        when (sprm) {
                            0x0835 -> b = on()
                            0x0836 -> it = on()
                            0x0837 -> st = on()
                            0x2A3E -> u = wd[op].toInt() != 0
                            0x4A43 -> hps = le16(wd, op)
                            0x2A42 -> (wd[op].toInt() and 0xFF).let { c -> if (c in 1 until ico.size) color = ico[c] or 0xFF000000.toInt() }
                            0x6870 -> { val v = le32(wd, op); if (v ushr 24 == 0) color = (0xFF shl 24) or ((v and 0xFF) shl 16) or (v and 0xFF00) or ((v shr 16) and 0xFF) }
                            0x2A0C -> (wd[op].toInt() and 0xFF).let { c -> if (c in 1 until ico.size) bg = ico[c] or 0xFF000000.toInt() }
                            0x6A03 -> pic = le32(wd, op)
                            0x0855 -> special = on()
                            0x083C -> vanish = on()
                            0x2A48 -> when (wd[op].toInt()) { 1 -> sup = true; 2 -> sub = true }
                        }
                        s = op + l
                    }
                }
                chpx.add(FcRange(fs, fe, ChpP(b, it, u, st, hps, color, bg, pic, special, vanish, sup, sub)))
            }
        }
        chpx.sortBy { it.start }
    }

    private fun <T> find(list: List<FcRange<T>>, fc: Int): T? {
        var lo = 0
        var hi = list.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = list[mid]
            when {
                fc < r.start -> hi = mid - 1
                fc >= r.end -> lo = mid + 1
                else -> return r.v
            }
        }
        return null
    }

    // ───────── збирання документа ─────────

    private fun build(ccpText: Int, title: String?): FlowDoc {
        val blocks = ArrayList<Block>()
        val toc = ArrayList<TocItem>()
        val buf = RunBuf()
        // таблиця, що збирається
        var rows = ArrayList<Row>()
        var cells = ArrayList<Cell>()
        var cellBlocks = ArrayList<Block>()
        var field = 0          // глибина поля
        val fieldShow = ArrayList<Boolean>() // чи вже після роздільника (показуємо результат)

        fun paraProps(fc: Int) = find(papx, fc)

        fun endPara(fcMark: Int, cellEnd: Boolean) {
            val pp = paraProps(fcMark)
            val runs = buf.take()
            val heading = pp?.let { headingByIstd[it.istd] ?: if (it.istd in titleIstd) 1 else null } ?: 0
            val al = when (pp?.jc) { 1 -> Align.CENTER; 2 -> Align.END; 3, 4, 5 -> Align.JUSTIFY; else -> Align.START }
            val bullet = if (pp != null && pp.ilfo > 0) "•" else null
            val para = Para(runs, heading.coerceIn(0, 6), al, if (bullet != null) (pp?.ilvl ?: 0) + 1 else 0, bullet)
            val inTable = pp?.inTable == true || cellEnd
            if (inTable) {
                if (pp?.ttp == true && !cellEnd) return
                if (pp?.ttp == true) {
                    // кінець рядка
                    if (cells.isNotEmpty()) rows.add(Row(cells))
                    cells = ArrayList()
                    cellBlocks = ArrayList()
                    return
                }
                if (runs.isNotEmpty() || cellBlocks.isEmpty()) cellBlocks.add(para)
                if (cellEnd) {
                    cells.add(Cell(cellBlocks))
                    cellBlocks = ArrayList()
                }
            } else {
                if (rows.isNotEmpty() || cells.isNotEmpty()) {
                    if (cells.isNotEmpty()) rows.add(Row(cells))
                    blocks.add(Table(rows))
                    rows = ArrayList(); cells = ArrayList(); cellBlocks = ArrayList()
                }
                if (heading > 0 && para.text.isNotBlank()) toc.add(TocItem(para.text.trim(), blocks.size, heading - 1))
                blocks.add(para)
            }
        }

        val sb = StringBuilder()
        for (pc in pieces) {
            val start = maxOf(0, pc.cpStart)
            val end = minOf(pc.cpEnd, ccpText)
            if (start >= end) continue
            var cp = start
            while (cp < end) {
                val fc = pc.fc + (cp - pc.cpStart) * (if (pc.compressed) 1 else 2)
                if (fc < 0 || fc + (if (pc.compressed) 0 else 1) >= wd.size) break
                val ch = if (pc.compressed) String(byteArrayOf(wd[fc]), cp1252)[0] else ((wd[fc].toInt() and 0xFF) or ((wd[fc + 1].toInt() and 0xFF) shl 8)).toChar()
                val cprops = find(chpx, fc)
                val hidingField = field > 0 && fieldShow.lastOrNull() == false
                when (ch) {
                    '\u0013' -> { field++; fieldShow.add(false) }
                    '\u0014' -> if (fieldShow.isNotEmpty()) fieldShow[fieldShow.lastIndex] = true
                    '\u0015' -> if (field > 0) { field--; fieldShow.removeAt(fieldShow.lastIndex) }
                    '\r' -> if (!hidingField) endPara(fc, false)
                    '\u0007' -> endPara(fc, true)
                    '\u000B' -> if (!hidingField) buf.add(run("\n", cprops))
                    '\u000C' -> if (!hidingField) { endPara(fc, false); blocks.add(Rule(pageBreak = true)) }
                    '\u001E' -> if (!hidingField) buf.add(run("-", cprops))
                    '\u001F', '\u0000' -> {}
                    '\u0001', '\u0008' -> if (!hidingField && cprops != null && cprops.pic >= 0 && ch == '\u0001') {
                        val key = "pic:" + cprops.pic
                        if (picture(cprops.pic) != null) {
                            val text = buf.take()
                            if (text.isNotEmpty()) blocks.add(Para(text))
                            blocks.add(Image(key))
                        }
                    }
                    '\u0002', '\u0005' -> {}
                    '\t' -> if (!hidingField) buf.add(run("\t", cprops))
                    else -> if (!hidingField && (cprops == null || !cprops.vanish)) {
                        sb.setLength(0)
                        sb.append(ch)
                        // склеюємо послідовність звичайних символів з однаковими властивостями
                        buf.add(run(sb.toString(), cprops))
                    }
                }
                cp++
            }
        }
        if (!buf.isEmpty()) endPara(-1, false)
        if (cells.isNotEmpty()) rows.add(Row(cells))
        if (rows.isNotEmpty()) blocks.add(Table(rows))
        return FlowDoc(title, blocks, Resources { key -> key.removePrefix("pic:").toIntOrNull()?.let { picture(it) } }, FlowKind.DOCUMENT, toc, basePt = 12f)
    }

    private fun run(t: String, c: ChpP?): Run =
        if (c == null) Run(t)
        else Run(t, c.b, c.i, c.u, c.s, c.color?.takeIf { (it and 0xFFFFFF) != 0 }, c.bg, if (c.hps > 0) c.hps / 2f else null, sup = c.sup, sub = c.sub)

    private val picCache = HashMap<Int, ByteArray?>()

    /** Картинка з потоку Data: шукаємо всередині PICF сигнатуру PNG або JPEG. */
    private fun picture(off: Int): ByteArray? = picCache.getOrPut(off) {
        val d = data ?: return@getOrPut null
        if (off < 0 || off + 8 > d.size) return@getOrPut null
        val lcb = le32(d, off)
        val end = minOf(d.size, off + lcb.coerceAtLeast(0))
        Ole.findImage(d, off + 4, end)
    }

    /** Word 6/95: простий текст без форматування. */
    private fun old(cfb: Cfb): FlowDoc {
        val fcMin = le32(wd, 0x18)
        val fcMac = le32(wd, 0x1C)
        if (fcMin <= 0 || fcMac <= fcMin || fcMac > wd.size) throw DocException("Unsupported Word version")
        val cs = runCatching { Charset.forName("windows-1251") }.getOrDefault(cp1252)
        val txt = String(wd, fcMin, fcMac - fcMin, if (looksCyr(wd, fcMin, fcMac)) cs else cp1252)
        val blocks = txt.split('\r').map { line -> Para(listOf(Run(line.replace(Regex("[\\u0000-\\u0008\\u000E-\\u001F]"), "").replace('\u000B', '\n')))) }
        @Suppress("UNUSED_VARIABLE") val unused = cfb
        return FlowDoc(null, blocks, note = "old")
    }

    private fun looksCyr(b: ByteArray, s: Int, e: Int): Boolean {
        var hi = 0
        for (i in s until e) if ((b[i].toInt() and 0xFF) >= 0xC0) hi++
        return hi * 10 > (e - s)
    }
}

/** Спільне для старих форматів Office. */
object Ole {
    /** Перша картинка PNG/JPEG/GIF у байтах [from, to). */
    fun findImage(d: ByteArray, from: Int, to: Int): ByteArray? {
        var i = from
        val end = minOf(to, d.size)
        while (i + 8 < end) {
            val b0 = d[i].toInt() and 0xFF
            if (b0 == 0x89 && d[i + 1] == 'P'.code.toByte() && d[i + 2] == 'N'.code.toByte() && d[i + 3] == 'G'.code.toByte()) {
                // до кінця фрагмента IEND
                var j = i + 8
                while (j + 12 <= end) {
                    val len = ((d[j].toInt() and 0xFF) shl 24) or ((d[j + 1].toInt() and 0xFF) shl 16) or ((d[j + 2].toInt() and 0xFF) shl 8) or (d[j + 3].toInt() and 0xFF)
                    val type = String(d, j + 4, 4, Charsets.ISO_8859_1)
                    if (len < 0) break
                    j += 12 + len
                    if (type == "IEND") return d.copyOfRange(i, minOf(j, end))
                }
                return d.copyOfRange(i, end)
            }
            if (b0 == 0xFF && (d[i + 1].toInt() and 0xFF) == 0xD8 && (d[i + 2].toInt() and 0xFF) == 0xFF) {
                var j = end - 2
                while (j > i && !((d[j].toInt() and 0xFF) == 0xFF && (d[j + 1].toInt() and 0xFF) == 0xD9)) j--
                return d.copyOfRange(i, if (j > i) j + 2 else end)
            }
            if (b0 == 'G'.code && d[i + 1] == 'I'.code.toByte() && d[i + 2] == 'F'.code.toByte() && d[i + 3] == '8'.code.toByte()) return d.copyOfRange(i, end)
            i++
        }
        return null
    }

    /** Заголовок із потоку SummaryInformation. */
    fun summaryTitle(cfb: Cfb): String? {
        val s = cfb.stream("\u0005SummaryInformation") ?: return null
        if (s.size < 48) return null
        val secOff = le32(s, 44)
        if (secOff + 8 > s.size) return null
        val n = le32(s, secOff + 4)
        var codepage = 1252
        var titleOff = -1
        for (i in 0 until n) {
            val pid = le32(s, secOff + 8 + i * 8)
            val off = le32(s, secOff + 8 + i * 8 + 4)
            if (pid == 1) codepage = le16(s, secOff + off + 4)
            if (pid == 2) titleOff = secOff + off
        }
        if (titleOff < 0 || titleOff + 8 > s.size) return null
        val type = le16(s, titleOff)
        val len = le32(s, titleOff + 4)
        if (len <= 0 || titleOff + 8 + len > s.size) return null
        val cs = when {
            type == 0x1F -> Charsets.UTF_16LE
            codepage == 65001 -> Charsets.UTF_8
            else -> runCatching { Charset.forName("windows-$codepage") }.getOrDefault(Charsets.ISO_8859_1)
        }
        val bytes = if (type == 0x1F) len * 2 else len
        return String(s, titleOff + 8, minOf(bytes, s.size - titleOff - 8), cs).trimEnd('\u0000').trim().takeIf { it.isNotEmpty() }
    }
}
