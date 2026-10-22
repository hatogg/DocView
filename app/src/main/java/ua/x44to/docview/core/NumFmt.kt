package ua.x44to.docview.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.floor

/**
 * Числові формати Excel: «#,##0.00», «0%», «dd.mm.yyyy», «[h]:mm» тощо.
 * Розділювачі — як у мові телефона (задаються з Android при запуску).
 */
object NumFmt {
    @Volatile var dec = '.'
    @Volatile var group = ','
    @Volatile var shortDate = "dd.mm.yyyy"
    @Volatile var monthsShort = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    @Volatile var monthsLong = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    @Volatile var daysShort = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    @Volatile var daysLong = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    fun builtin(id: Int): String = when (id) {
        0 -> "General"; 1 -> "0"; 2 -> "0.00"; 3 -> "#,##0"; 4 -> "#,##0.00"
        5 -> "#,##0 ;-#,##0"; 6 -> "#,##0 ;-#,##0"; 7 -> "#,##0.00;-#,##0.00"; 8 -> "#,##0.00;-#,##0.00"
        9 -> "0%"; 10 -> "0.00%"; 11 -> "0.00E+00"; 12 -> "# ?/?"; 13 -> "# ??/??"
        14 -> shortDate; 15 -> "d-mmm-yy"; 16 -> "d-mmm"; 17 -> "mmm-yy"; 18 -> "h:mm AM/PM"; 19 -> "h:mm:ss AM/PM"
        20 -> "h:mm"; 21 -> "h:mm:ss"; 22 -> "$shortDate h:mm"
        37 -> "#,##0 ;(#,##0)"; 38 -> "#,##0 ;(#,##0)"; 39 -> "#,##0.00;(#,##0.00)"; 40 -> "#,##0.00;(#,##0.00)"
        41, 42, 43, 44 -> "#,##0.00"
        45 -> "mm:ss"; 46 -> "[h]:mm:ss"; 47 -> "mm:ss.0"; 48 -> "##0.0E+0"; 49 -> "@"
        27, 28, 29, 30, 31, 34, 35, 36, 50, 51, 52, 53, 54, 57, 58 -> shortDate
        32, 33 -> "h:mm"; 55, 56 -> "h:mm:ss"
        else -> "General"
    }

    private val cache = HashMap<String, Boolean>()

    /** Чи це формат дати/часу. */
    fun isDate(code: String): Boolean = synchronized(cache) {
        cache.getOrPut(code) {
            val c = stripLiterals(code.substringBefore(';')).lowercase()
            c.any { it in "ymdhs" } && !c.contains("general")
        }
    }

    private fun stripLiterals(c: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < c.length) {
            val ch = c[i]
            when (ch) {
                '"' -> { val e = c.indexOf('"', i + 1); i = if (e < 0) c.length else e + 1; continue }
                '\\', '_', '*' -> { i += 2; continue }
                '[' -> {
                    val e = c.indexOf(']', i + 1)
                    val inner = if (e < 0) "" else c.substring(i + 1, e).lowercase()
                    if (inner.isNotEmpty() && inner.all { it in "hms" }) sb.append(inner)
                    i = if (e < 0) c.length else e + 1
                    continue
                }
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    fun serialToDate(v: Double, date1904: Boolean): LocalDateTime {
        val days = floor(v).toLong()
        val frac = v - days
        val base = when {
            date1904 -> LocalDate.of(1904, 1, 1)
            days < 61 -> LocalDate.of(1899, 12, 31)
            else -> LocalDate.of(1899, 12, 30)
        }
        val secs = Math.round(frac * 86400.0)
        return base.plusDays(days).atStartOfDay().plusSeconds(secs)
    }

    fun format(v: Double, code0: String?, date1904: Boolean = false): String {
        if (v.isNaN()) return "#NUM!"
        if (v.isInfinite()) return "#DIV/0!"
        val code = code0?.takeIf { it.isNotBlank() } ?: "General"
        val sections = splitSections(code)
        var sec = sections[0]
        var value = v
        var negParens = false
        if (sections.size >= 2 && v < 0) { sec = sections[1]; value = abs(v); negParens = true }
        else if (sections.size >= 3 && v == 0.0) sec = sections[2]
        // умови на кшталт [>=100]
        val cond = Regex("^\\[([<>=]+)(-?[0-9.]+)]").find(sec)
        if (cond != null) {
            sec = sec.substring(cond.range.last + 1)
        }
        val clean = sec.replace(Regex("\\[(?:Red|Black|Blue|Green|White|Yellow|Cyan|Magenta|Color\\s*\\d+)]", RegexOption.IGNORE_CASE), "")
        if (clean.equals("General", true) || clean.isBlank()) {
            val g = general(value)
            return if (negParens && v < 0 && sections.size >= 2) "-$g" else g
        }
        if (clean == "@") return general(v)
        return try {
            if (isDate(clean)) date(value, clean, date1904)
            else {
                val s = number(value, clean)
                if (negParens || v >= 0) s else s
            }
        } catch (_: Exception) {
            general(v)
        }
    }

    private fun splitSections(c: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < c.length) {
            val ch = c[i]
            when {
                ch == '"' -> { q = !q; sb.append(ch) }
                ch == '\\' && i + 1 < c.length -> { sb.append(ch).append(c[i + 1]); i++ }
                ch == ';' && !q -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    /** «Загальний» формат: до 11 значущих цифр, без зайвих нулів. */
    fun general(v: Double): String {
        if (v == 0.0) return "0"
        val a = abs(v)
        val s = if (a >= 1e11 || a < 1e-9) {
            String.format(java.util.Locale.ROOT, "%.5E", v).replace(Regex("\\.?0+E"), "E").replace("E+0", "E+").replace("E-0", "E-")
        } else {
            BigDecimal(v).round(java.math.MathContext(11, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
        }
        return s.replace('.', dec)
    }

    private fun number(v: Double, code: String): String {
        // текстові вставки й символи навколо числа
        var c = code
        val percent = stripLiterals(c).contains('%')
        var value = if (percent) v * 100 else v
        val sci = Regex("[Ee][+-]").containsMatchIn(stripLiterals(c))
        // [$₴-422] → ₴
        c = c.replace(Regex("\\[\\$([^\\]-]*)(?:-[0-9A-Fa-f]+)?]")) { it.groupValues[1] }
        c = c.replace(Regex("\\[[^\\]]*]"), "")
        // дроби «# ?/?» показуємо десятковим числом
        if (Regex("\\?/\\?|#/#|0/0").containsMatchIn(c)) return general(v)
        val core = stripLiterals(c)
        val firstPh = c.indexOfFirst { it == '0' || it == '#' || it == '?' }
        if (firstPh < 0) return literal(c) // лише текст
        var lastPh = c.indexOfLast { it == '0' || it == '#' || it == '?' }
        if (sci) {
            val e = c.indexOfFirst { it == 'E' || it == 'e' }
            lastPh = c.length - 1
            val mant = c.substring(firstPh, e)
            val expDigits = c.substring(e + 2).count { it == '0' }.coerceAtLeast(1)
            val decs = mant.substringAfter('.', "").count { it == '0' || it == '#' }
            val s = String.format(java.util.Locale.ROOT, "%.${decs}E", value)
            val m = s.substringBefore('E').replace('.', dec)
            val ex = s.substringAfter('E').toInt()
            return literal(c.substring(0, firstPh)) + m + "E" + (if (ex < 0) "-" else "+") + abs(ex).toString().padStart(expDigits, '0')
        }
        val pattern = c.substring(firstPh, lastPh + 1)
        val prefix = literal(c.substring(0, firstPh))
        val suffix = literal(c.substring(lastPh + 1))
        val intPart = pattern.substringBefore('.')
        val fracPart = if (pattern.contains('.')) pattern.substringAfter('.') else ""
        val grouping = intPart.contains(',')
        // кома в кінці цілої частини — ділення на 1000
        var scale = 0
        var ip = intPart
        while (ip.endsWith(",")) { scale++; ip = ip.dropLast(1) }
        repeat(scale) { value /= 1000.0 }
        val decMin = fracPart.count { it == '0' }
        val decMax = fracPart.count { it == '0' || it == '#' || it == '?' }
        val bd = BigDecimal(abs(value)).setScale(decMax, RoundingMode.HALF_UP)
        var whole = bd.toBigInteger().toString()
        var frac = if (decMax > 0) bd.toPlainString().substringAfter('.', "").padEnd(decMax, '0') else ""
        while (frac.length > decMin && frac.endsWith("0")) frac = frac.dropLast(1)
        val minInt = ip.count { it == '0' }
        if (whole == "0" && minInt == 0) whole = ""
        whole = whole.padStart(minInt, '0')
        if (grouping && whole.length > 3) whole = whole.reversed().chunked(3).joinToString(group.toString()).reversed()
        val num = whole + if (frac.isNotEmpty()) dec + frac else ""
        val neg = value < 0 && bd.signum() != 0
        @Suppress("UNUSED_VARIABLE") val unused = core
        return (if (neg) "-" else "") + prefix + num + suffix
    }

    /** Текстова частина формату: лапки, екрановані символи, пропуски «_x». */
    private fun literal(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when (ch) {
                '"' -> { val e = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }; sb.append(s, i + 1, e); i = e + 1; continue }
                '\\' -> { if (i + 1 < s.length) sb.append(s[i + 1]); i += 2; continue }
                '_' -> { sb.append(' '); i += 2; continue }
                '*' -> { i += 2; continue }
                '0', '#', '?', ',', '.' -> {}
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    private fun date(v: Double, code: String, date1904: Boolean): String {
        val dt = serialToDate(v, date1904)
        val out = StringBuilder()
        val c = code
        val ampm = Regex("AM/PM|A/P", RegexOption.IGNORE_CASE).containsMatchIn(c)
        var i = 0
        var lastWasHour = false
        fun peekMinute(from: Int): Boolean {
            // «m» після годин або перед секундами — хвилини
            var j = from
            while (j < c.length && c[j].lowercaseChar() == 'm') j++
            while (j < c.length && (c[j] == ':' || c[j] == ' ' || c[j] == '.')) j++
            return j < c.length && c[j].lowercaseChar() == 's'
        }
        while (i < c.length) {
            val ch = c[i]
            val lc = ch.lowercaseChar()
            var run = 1
            while (i + run < c.length && c[i + run].lowercaseChar() == lc && lc in "ymdhs") run++
            when {
                ch == '"' -> { val e = c.indexOf('"', i + 1).let { if (it < 0) c.length else it }; out.append(c, i + 1, e); i = e + 1; continue }
                ch == '\\' -> { if (i + 1 < c.length) out.append(c[i + 1]); i += 2; continue }
                ch == '_' -> { out.append(' '); i += 2; continue }
                ch == '[' -> {
                    val e = c.indexOf(']', i).let { if (it < 0) c.length else it }
                    val inner = c.substring(i + 1, e).lowercase()
                    val totalSec = Math.round(v * 86400.0)
                    when {
                        inner.startsWith("h") -> { out.append((totalSec / 3600).toString().padStart(inner.length, '0')); lastWasHour = true }
                        inner.startsWith("m") -> out.append((totalSec / 60).toString().padStart(inner.length, '0'))
                        inner.startsWith("s") -> out.append(totalSec.toString().padStart(inner.length, '0'))
                    }
                    i = e + 1
                    continue
                }
                lc == 'y' -> out.append(if (run <= 2) (dt.year % 100).toString().padStart(2, '0') else dt.year.toString())
                lc == 'm' && (lastWasHour || peekMinute(i)) -> { out.append(if (run >= 2) dt.minute.toString().padStart(2, '0') else dt.minute.toString()); lastWasHour = false }
                lc == 'm' -> out.append(
                    when (run) {
                        1 -> dt.monthValue.toString(); 2 -> dt.monthValue.toString().padStart(2, '0')
                        3 -> monthsShort[dt.monthValue - 1]; 5 -> monthsLong[dt.monthValue - 1].take(1)
                        else -> monthsLong[dt.monthValue - 1]
                    },
                )
                lc == 'd' -> out.append(
                    when (run) {
                        1 -> dt.dayOfMonth.toString(); 2 -> dt.dayOfMonth.toString().padStart(2, '0')
                        3 -> daysShort[dt.dayOfWeek.ordinal]; else -> daysLong[dt.dayOfWeek.ordinal]
                    },
                )
                lc == 'h' -> {
                    var h = dt.hour
                    if (ampm) { h %= 12; if (h == 0) h = 12 }
                    out.append(if (run >= 2) h.toString().padStart(2, '0') else h.toString())
                    lastWasHour = true
                }
                lc == 's' -> {
                    out.append(if (run >= 2) dt.second.toString().padStart(2, '0') else dt.second.toString())
                    // десяті частки секунди «ss.0»
                    if (i + run < c.length && c[i + run] == '.' && i + run + 1 < c.length && c[i + run + 1] == '0') {
                        var z = 0
                        while (i + run + 1 + z < c.length && c[i + run + 1 + z] == '0') z++
                        val frac = ((v * 86400.0) % 1.0)
                        out.append(dec).append(String.format(java.util.Locale.ROOT, "%.${z}f", frac).substringAfter('.'))
                        run += 1 + z
                    }
                    lastWasHour = false
                }
                c.regionMatches(i, "AM/PM", 0, 5, ignoreCase = true) -> { out.append(if (dt.hour < 12) "AM" else "PM"); i += 5; continue }
                c.regionMatches(i, "A/P", 0, 3, ignoreCase = true) -> { out.append(if (dt.hour < 12) "A" else "P"); i += 3; continue }
                else -> out.append(ch)
            }
            i += run
        }
        return out.toString()
    }
}
