package ua.x44to.docview.core

import java.nio.charset.Charset

/**
 * Невеликий потоковий розбирач XML (і поблажливий режим для HTML).
 * Без залежностей від Android, щоб ядро можна було перевіряти на звичайній JVM.
 * Імена елементів і атрибутів зберігаються як є («w:p»); [local] — без префікса.
 */
class XmlReader(private val s: String, private val html: Boolean = false) {
    enum class T { START, END, TEXT, EOF }

    var type = T.EOF; private set
    /** повне ім'я поточного елемента (у HTML — малими літерами) */
    var name = ""; private set
    val local: String get() = name.substringAfter(':')
    var text = ""; private set
    var depth = 0; private set
    private val an = ArrayList<String>(8)
    private val av = ArrayList<String>(8)
    private var pos = 0
    private var pendingEnd: String? = null

    val attrCount get() = an.size
    fun attrName(i: Int) = an[i]
    fun attrValue(i: Int) = av[i]

    /** Атрибут за локальним ім'ям: «val» знайде і «w:val», і «val». */
    fun attr(localName: String): String? {
        for (i in an.indices) {
            val n = an[i]
            if (n == localName || (n.length > localName.length && n.endsWith(localName) && n[n.length - localName.length - 1] == ':')) return av[i]
        }
        return null
    }

    /** Атрибут за точним ім'ям із префіксом: «r:id». */
    fun attrQ(q: String): String? {
        val i = an.indexOf(q)
        return if (i >= 0) av[i] else null
    }

    fun next(): T {
        pendingEnd?.let {
            pendingEnd = null
            name = it
            type = T.END
            depth--
            return type
        }
        while (true) {
            if (pos >= s.length) { type = T.EOF; return type }
            val c = s[pos]
            if (c == '<') {
                if (s.startsWith("<!--", pos)) { pos = idx("-->", pos + 4, 3); continue }
                if (s.startsWith("<![CDATA[", pos)) {
                    val e = s.indexOf("]]>", pos + 9).let { if (it < 0) s.length else it }
                    text = s.substring(pos + 9, e)
                    pos = minOf(s.length, e + 3)
                    type = T.TEXT
                    return type
                }
                if (s.startsWith("<?", pos)) { pos = idx("?>", pos + 2, 2); continue }
                if (s.startsWith("<!", pos)) { pos = idx(">", pos + 2, 1); continue }
                if (pos + 1 < s.length && s[pos + 1] == '/') {
                    val e = s.indexOf('>', pos + 2).let { if (it < 0) s.length else it }
                    name = s.substring(pos + 2, e).trim().let { if (html) it.lowercase() else it }
                    pos = minOf(s.length, e + 1)
                    type = T.END
                    depth--
                    return type
                }
                if (pos + 1 < s.length && (s[pos + 1].isLetter() || s[pos + 1] == '_' || s[pos + 1] == ':')) {
                    readStart()
                    return type
                }
                // одиночний «<» у HTML — просто текст
                if (html) {
                    text = "<"; pos++; type = T.TEXT; return type
                }
                pos++
                continue
            }
            val e = s.indexOf('<', pos).let { if (it < 0) s.length else it }
            text = decode(s, pos, e)
            pos = e
            type = T.TEXT
            return type
        }
    }

    private fun idx(end: String, from: Int, len: Int): Int {
        val e = s.indexOf(end, from)
        return if (e < 0) s.length else e + len
    }

    private fun readStart() {
        an.clear(); av.clear()
        var p = pos + 1
        val n0 = p
        while (p < s.length && !s[p].isWhitespace() && s[p] != '>' && s[p] != '/') p++
        name = s.substring(n0, p).let { if (html) it.lowercase() else it }
        var self = false
        while (p < s.length) {
            val ch = s[p]
            when {
                ch.isWhitespace() -> p++
                ch == '>' -> { p++; break }
                ch == '/' && p + 1 < s.length && s[p + 1] == '>' -> { self = true; p += 2; break }
                ch == '/' -> p++
                else -> {
                    val a0 = p
                    while (p < s.length && !s[p].isWhitespace() && s[p] != '=' && s[p] != '>' && !(s[p] == '/' && p + 1 < s.length && s[p + 1] == '>')) p++
                    val an0 = s.substring(a0, p).let { if (html) it.lowercase() else it }
                    while (p < s.length && s[p].isWhitespace()) p++
                    var value = ""
                    if (p < s.length && s[p] == '=') {
                        p++
                        while (p < s.length && s[p].isWhitespace()) p++
                        if (p < s.length && (s[p] == '"' || s[p] == '\'')) {
                            val q = s[p]
                            val e = s.indexOf(q, p + 1).let { if (it < 0) s.length else it }
                            value = decode(s, p + 1, e)
                            p = minOf(s.length, e + 1)
                        } else {
                            val v0 = p
                            while (p < s.length && !s[p].isWhitespace() && s[p] != '>') p++
                            value = decode(s, v0, p)
                        }
                    }
                    if (an0.isNotEmpty()) { an.add(an0); av.add(value) }
                }
            }
        }
        pos = p
        type = T.START
        depth++
        if (html && name in VOID) self = true
        if (self) pendingEnd = name
        // вміст <script> і <style> не потрібен
        if (html && (name == "script" || name == "style") && !self) {
            val e = s.indexOf("</$name", pos, ignoreCase = true)
            pos = if (e < 0) s.length else e
        }
    }

    /**
     * Обійти дочірні елементи поточного START. Обробник отримує локальне ім'я дитини;
     * якщо він її не «з'їв» (skip/readText/each), обхід спуститься в неї — зручно для обгорток.
     */
    inline fun each(f: (String) -> Unit) {
        if (type != T.START) return
        val d = depth
        while (true) {
            val t = next()
            if (t == T.EOF) return
            if (t == T.END && depth < d) return
            if (t == T.START) f(local)
        }
    }

    /** Пропустити піддерево поточного START. */
    fun skip() {
        if (type != T.START) return
        val d = depth
        while (true) {
            val t = next()
            if (t == T.EOF) return
            if (t == T.END && depth < d) return
        }
    }

    /** Увесь текст усередині поточного елемента (рекурсивно). */
    fun readText(): String {
        if (type != T.START) return ""
        val d = depth
        val sb = StringBuilder()
        while (true) {
            val t = next()
            if (t == T.EOF) break
            if (t == T.TEXT) sb.append(text)
            if (t == T.END && depth < d) break
        }
        return sb.toString()
    }

    companion object {
        private val VOID = setOf("br", "img", "hr", "meta", "link", "input", "col", "area", "base", "wbr", "source", "param", "embed", "track")

        private val ENT = mapOf(
            "lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
            "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»", "hellip" to "…", "copy" to "©",
            "reg" to "®", "trade" to "™", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
            "bdquo" to "„", "bull" to "•", "middot" to "·", "times" to "×", "divide" to "÷", "deg" to "°",
            "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "sect" to "§", "para" to "¶",
            "shy" to "\u00AD", "minus" to "−", "plusmn" to "±", "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
            "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "hearts" to "♥", "thinsp" to "\u2009",
            "ensp" to "\u2002", "emsp" to "\u2003", "zwj" to "\u200D", "zwnj" to "\u200C", "prime" to "′",
            "Prime" to "″", "dagger" to "†", "Dagger" to "‡", "permil" to "‰", "infin" to "∞", "ne" to "≠",
            "le" to "≤", "ge" to "≥", "asymp" to "≈", "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "szlig" to "ß",
            "eacute" to "é", "egrave" to "è", "agrave" to "à", "aacute" to "á", "ccedil" to "ç", "ntilde" to "ñ",
            "iexcl" to "¡", "iquest" to "¿", "sup2" to "²", "sup3" to "³", "micro" to "µ", "alpha" to "α",
            "beta" to "β", "gamma" to "γ", "delta" to "δ", "pi" to "π", "sigma" to "σ", "omega" to "ω",
        )

        fun decode(s: String, from: Int, to: Int): String {
            val amp = s.indexOf('&', from)
            if (amp < 0 || amp >= to) return s.substring(from, to)
            val sb = StringBuilder(to - from)
            var i = from
            while (i < to) {
                val c = s[i]
                if (c == '&') {
                    val semi = s.indexOf(';', i + 1)
                    if (semi in (i + 2)..minOf(to - 1, i + 12)) {
                        val ent = s.substring(i + 1, semi)
                        val rep: String? = if (ent.startsWith("#")) {
                            val code = if (ent.startsWith("#x") || ent.startsWith("#X")) ent.substring(2).toIntOrNull(16) else ent.substring(1).toIntOrNull()
                            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else null
                        } else ENT[ent]
                        if (rep != null) { sb.append(rep); i = semi + 1; continue }
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        /** Байти XML → рядок у кодуванні з пролога (UTF-8, UTF-16, windows-1251…). */
        fun decodeBytes(b: ByteArray): String {
            if (b.size >= 2 && (b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte())) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
            if (b.size >= 2 && (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte())) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
            if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return String(b, 3, b.size - 3, Charsets.UTF_8)
            val head = String(b, 0, minOf(b.size, 200), Charsets.ISO_8859_1)
            val m = Regex("encoding\\s*=\\s*[\"']([A-Za-z0-9_.:-]+)[\"']").find(head)
            val cs = m?.groupValues?.get(1)?.let { runCatching { Charset.forName(it) }.getOrNull() }
            return String(b, cs ?: Charsets.UTF_8)
        }
    }
}
