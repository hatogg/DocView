package ua.x44to.docview.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Звичайний текст, CSV і Markdown. */
object Text {
    private val cp1251: Charset = runCatching { Charset.forName("windows-1251") }.getOrDefault(Charsets.ISO_8859_1)

    /** Байти → рядок: BOM, потім UTF-8, якщо він коректний, інакше windows-1251 (кирилиця зі старих Windows). */
    fun decode(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return String(b, 3, b.size - 3, Charsets.UTF_8)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        return if (validUtf8(b)) String(b, Charsets.UTF_8) else String(b, cp1251)
    }

    private fun validUtf8(b: ByteArray): Boolean {
        // до 3 байтів з кінця можна відкинути: якщо читали лише початок файлу, останній символ міг обірватися
        for (cut in 0..3) {
            if (cut >= b.size) break
            val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            try { dec.decode(ByteBuffer.wrap(b, 0, b.size - cut)); return true } catch (_: Exception) {}
        }
        return false
    }

    fun read(f: File, limit: Long = 40L * 1024 * 1024): String {
        if (f.length() > limit) throw DocException("File is too large")
        return decode(f.readBytes())
    }

    private val codeExt = setOf("json", "xml", "yml", "yaml", "toml", "kt", "java", "py", "js", "ts", "c", "h", "cpp", "cs", "go", "rs", "sh", "bat", "sql", "css", "gradle", "kts", "swift", "php", "rb", "lua", "ini", "cfg", "conf", "properties", "log")

    fun txt(f: File, name: String): FlowDoc {
        val s = read(f).replace("\r\n", "\n").replace('\r', '\n')
        val mono = Detect.ext(name) in codeExt
        val blocks = ArrayList<Block>()
        // абзаци — за рядками; дуже довгі файли не ріжемо, LazyColumn упорається
        for (line in s.split('\n')) blocks.add(Para(listOf(Run(line.trimEnd())), mono = mono))
        return FlowDoc(name, blocks, kind = FlowKind.TEXT)
    }

    // ───────── CSV ─────────

    fun csv(f: File, name: String): SheetDoc {
        val s = read(f, 60L * 1024 * 1024)
        val first = s.lineSequence().take(20).joinToString("\n")
        val sep = when {
            Detect.ext(name) == "tsv" -> '\t'
            else -> listOf(';', ',', '\t', '|').maxByOrNull { c -> first.count { it == c } } ?: ','
        }
        val rows = ArrayList<Array<XCell?>>()
        var row = ArrayList<XCell?>()
        val cell = StringBuilder()
        var q = false
        var i = 0
        var maxCols = 0
        fun endCell() {
            val t = cell.toString()
            row.add(if (t.isEmpty()) null else XCell(t, num = t.isNotEmpty() && looksNumber(t)))
            cell.setLength(0)
        }
        fun endRow() {
            endCell()
            maxCols = maxOf(maxCols, row.size)
            rows.add(row.toTypedArray())
            row = ArrayList()
        }
        while (i < s.length && rows.size < 100_000) {
            val c = s[i]
            if (q) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { cell.append('"'); i++ } else q = false
                } else cell.append(c)
            } else when (c) {
                '"' -> if (cell.isEmpty()) q = true else cell.append(c)
                sep -> endCell()
                '\r' -> {}
                '\n' -> endRow()
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        while (rows.isNotEmpty() && rows.last().all { it == null }) rows.removeAt(rows.lastIndex)
        val norm = rows.map { r -> if (r.size == maxCols) r else Array(maxCols) { r.getOrNull(it) } }
        return SheetDoc(name, listOf(Sheet(name.substringBeforeLast('.'), norm, maxCols)))
    }

    fun looksNumber(t: String): Boolean {
        val x = t.trim().replace(" ", "").replace(" ", "")
        return x.isNotEmpty() && x.length < 25 && Regex("^[-+]?[0-9]+([.,][0-9]+)?%?$").matches(x)
    }

    // ───────── Markdown ─────────

    fun md(f: File, name: String): FlowDoc {
        val lines = read(f).replace("\r\n", "\n").split('\n')
        val blocks = ArrayList<Block>()
        val toc = ArrayList<TocItem>()
        var i = 0
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) blocks.add(Para(inline(para.toString().trim())))
            para.setLength(0)
        }
        while (i < lines.size) {
            val l = lines[i]
            val t = l.trim()
            when {
                t.startsWith("```") || t.startsWith("~~~") -> {
                    flush()
                    val fence = t.take(3)
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                        blocks.add(Para(listOf(Run(lines[i], mono = true)), mono = true))
                        i++
                    }
                }
                t.startsWith("#") && t.trimStart('#').startsWith(" ") -> {
                    flush()
                    val level = t.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    val text = t.drop(level).trim().trimEnd('#').trim()
                    toc.add(TocItem(text, blocks.size, level - 1))
                    blocks.add(Para(inline(text), heading = level))
                }
                t.isEmpty() -> flush()
                Regex("^([-*_])(\\s*\\1){2,}$").matches(t) -> { flush(); blocks.add(Rule()) }
                t.startsWith(">") -> { flush(); blocks.add(Para(inline(t.trimStart('>').trim()), quote = true, indent = 1)) }
                Regex("^[-*+]\\s+.*").matches(t) -> {
                    flush()
                    val lvl = (l.length - l.trimStart().length) / 2
                    val body = t.drop(1).trim()
                    val check = Regex("^\\[([ xX])]\\s+").find(body)
                    if (check != null) blocks.add(Para(inline(body.substring(check.range.last + 1)), bullet = if (check.groupValues[1].isBlank()) "☐" else "☑", indent = lvl + 1))
                    else blocks.add(Para(inline(body), bullet = "•", indent = lvl + 1))
                }
                Regex("^\\d+[.)]\\s+.*").matches(t) -> {
                    flush()
                    val lvl = (l.length - l.trimStart().length) / 2
                    val num = t.takeWhile { it.isDigit() }
                    blocks.add(Para(inline(t.dropWhile { it.isDigit() }.drop(1).trim()), bullet = "$num.", indent = lvl + 1))
                }
                t.startsWith("|") && i + 1 < lines.size && Regex("^\\|?\\s*:?-{2,}").containsMatchIn(lines[i + 1].trim()) -> {
                    flush()
                    val rows = ArrayList<Row>()
                    fun cells(x: String) = x.trim().trim('|').split('|').map { Cell(listOf(Para(inline(it.trim())))) }
                    rows.add(Row(cells(t), header = true))
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) { rows.add(Row(cells(lines[i]))); i++ }
                    blocks.add(Table(rows))
                    continue
                }
                else -> { if (para.isNotEmpty()) para.append(if (l.endsWith("  ")) "\n" else " "); para.append(t) }
            }
            i++
        }
        flush()
        return FlowDoc(name, blocks, kind = FlowKind.DOCUMENT, toc = toc)
    }

    /** **жирний**, *курсив*, `код`, [посилання](url), ~~закреслений~~. */
    fun inline(s: String): List<Run> {
        val out = ArrayList<Run>()
        val re = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|\\*(.+?)\\*|_(.+?)_|`(.+?)`|~~(.+?)~~|!?\\[(.*?)]\\((.*?)\\)")
        var last = 0
        for (m in re.findAll(s)) {
            if (m.range.first > last) out.add(Run(s.substring(last, m.range.first)))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> out.add(Run(g[1], b = true))
                g[2].isNotEmpty() -> out.add(Run(g[2], b = true))
                g[3].isNotEmpty() -> out.add(Run(g[3], i = true))
                g[4].isNotEmpty() -> out.add(Run(g[4], i = true))
                g[5].isNotEmpty() -> out.add(Run(g[5], mono = true))
                g[6].isNotEmpty() -> out.add(Run(g[6], s = true))
                else -> out.add(Run(g[7].ifEmpty { g[8] }, u = true, link = g[8]))
            }
            last = m.range.last + 1
        }
        if (last < s.length) out.add(Run(s.substring(last)))
        return out
    }
}
