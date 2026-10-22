package ua.x44to.docview.core

import java.io.File
import java.util.zip.ZipFile

/** Zip-пакет документа (OOXML, OpenDocument, EPUB) з довідниками зв'язків. */
class Pkg(val file: File) : AutoCloseable {
    private val z: ZipFile? = try { ZipFile(file) } catch (_: Exception) { null }
    /** Пошкоджений zip (без центрального каталогу) читаємо послідовно в пам'ять. */
    private val mem: Map<String, ByteArray>? = if (z == null) readStream(file) else null
    private val names: Map<String, String> =
        z?.entries()?.asSequence()?.associate { it.name.lowercase() to it.name } ?: mem!!.keys.associateBy { it.lowercase() }

    class Rel(val id: String, val type: String, val target: String, val external: Boolean)

    fun exists(path: String) = names.containsKey(path.trimStart('/').lowercase())

    fun bytes(path: String, limit: Long = 200L * 1024 * 1024): ByteArray? {
        val real = names[path.trimStart('/').lowercase()] ?: return null
        mem?.let { return it[real] }
        val e = z!!.getEntry(real) ?: return null
        if (e.size > limit) throw DocException("Part is too large")
        return z.getInputStream(e).use { it.readBytes() }
    }

    fun text(path: String): String? = bytes(path)?.let { XmlReader.decodeBytes(it) }

    fun xml(path: String, html: Boolean = false): XmlReader? = text(path)?.let { XmlReader(it, html) }

    val entries: List<String> get() = names.values.toList()

    /** Зв'язки частини: id → ціль (вже абсолютний шлях у пакеті). */
    fun rels(part: String): Map<String, Rel> {
        val dir = part.substringBeforeLast('/', "")
        val relPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + part.substringAfterLast('/') + ".rels"
        val x = xml(relPath) ?: return emptyMap()
        val out = HashMap<String, Rel>()
        while (x.next() != XmlReader.T.EOF) {
            if (x.type == XmlReader.T.START && x.local == "Relationship") {
                val id = x.attr("Id") ?: continue
                val ext = x.attr("TargetMode") == "External"
                val t = x.attr("Target") ?: ""
                out[id] = Rel(id, x.attr("Type") ?: "", if (ext) t else resolve(dir, t), ext)
            }
        }
        return out
    }

    override fun close() { z?.close() }

    companion object {
        fun readStream(file: File): Map<String, ByteArray> {
            val out = LinkedHashMap<String, ByteArray>()
            var total = 0L
            try {
                java.util.zip.ZipInputStream(file.inputStream().buffered()).use { zs ->
                    while (true) {
                        val e = zs.nextEntry ?: break
                        if (e.isDirectory) continue
                        val bytes = zs.readBytes()
                        total += bytes.size
                        if (total > 400L * 1024 * 1024) break
                        out[e.name] = bytes
                    }
                }
            } catch (_: Exception) {
            }
            if (out.isEmpty()) throw DocException("Damaged archive")
            return out
        }

        /** «word» + «../media/a.png» → «media/a.png»; «/xl/x.xml» → «xl/x.xml». */
        fun resolve(dir: String, target: String): String {
            val t = target.substringBefore('#')
            if (t.startsWith("/")) return t.trimStart('/')
            val parts = ArrayList<String>()
            if (dir.isNotEmpty()) parts.addAll(dir.split('/'))
            for (p in t.split('/')) {
                when (p) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts.add(p)
                }
            }
            return parts.joinToString("/").let { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        }
    }
}

/** «FF0000» / «#ff0000» → ARGB. */
fun hexColor(v: String?): Int? {
    if (v == null) return null
    val h = v.trim().removePrefix("#")
    if (h.equals("auto", true) || h.isEmpty()) return null
    return when (h.length) {
        6 -> h.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
        8 -> h.toLongOrNull(16)?.let { (0xFF000000L or (it and 0xFFFFFF)).toInt() }
        3 -> h.toLongOrNull(16)?.let {
            val r = (it shr 8) and 0xF; val g = (it shr 4) and 0xF; val b = it and 0xF
            (0xFF000000L or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)).toInt()
        }
        else -> null
    }
}

/** Іменовані кольори (виділення у Word, CSS). */
fun namedColor(n: String?): Int? = when (n?.lowercase()) {
    null, "none", "auto", "transparent" -> null
    "yellow" -> 0xFFFFFF00.toInt(); "green" -> 0xFF00FF00.toInt(); "cyan" -> 0xFF00FFFF.toInt(); "magenta" -> 0xFFFF00FF.toInt()
    "blue" -> 0xFF0000FF.toInt(); "red" -> 0xFFFF0000.toInt(); "darkblue" -> 0xFF000080.toInt(); "darkcyan" -> 0xFF008080.toInt()
    "darkgreen" -> 0xFF008000.toInt(); "darkmagenta" -> 0xFF800080.toInt(); "darkred" -> 0xFF800000.toInt(); "darkyellow" -> 0xFF808000.toInt()
    "darkgray" -> 0xFF808080.toInt(); "lightgray" -> 0xFFC0C0C0.toInt(); "black" -> 0xFF000000.toInt(); "white" -> 0xFFFFFFFF.toInt()
    "gray", "grey" -> 0xFF808080.toInt(); "orange" -> 0xFFFFA500.toInt(); "purple" -> 0xFF800080.toInt(); "navy" -> 0xFF000080.toInt()
    "maroon" -> 0xFF800000.toInt(); "olive" -> 0xFF808000.toInt(); "teal" -> 0xFF008080.toInt(); "silver" -> 0xFFC0C0C0.toInt()
    "lime" -> 0xFF00FF00.toInt(); "aqua" -> 0xFF00FFFF.toInt(); "fuchsia" -> 0xFFFF00FF.toInt(); "brown" -> 0xFFA52A2A.toInt()
    "pink" -> 0xFFFFC0CB.toInt(); "gold" -> 0xFFFFD700.toInt()
    else -> null
}

/** CSS-колір: #rgb, #rrggbb, rgb(…), назва. */
fun cssColor(v: String?): Int? {
    val s = v?.trim()?.lowercase() ?: return null
    if (s.startsWith("#")) return hexColor(s)
    val m = Regex("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)").find(s)
    if (m != null) {
        val (r, g, b) = m.destructured
        return (0xFF000000L or (r.toLong().coerceIn(0, 255) shl 16) or (g.toLong().coerceIn(0, 255) shl 8) or b.toLong().coerceIn(0, 255)).toInt()
    }
    return namedColor(s)
}

const val EMU_PER_PT = 12700f

/** Ширина/висота з CSS чи ODF: «2.5cm», «12pt», «1in», «40mm», «100px» → пункти. */
fun lengthPt(v: String?): Float? {
    val s = v?.trim()?.lowercase() ?: return null
    val m = Regex("^(-?[0-9.]+)\\s*(cm|mm|in|pt|pc|px|em)?$").find(s) ?: return null
    val n = m.groupValues[1].toFloatOrNull() ?: return null
    return when (m.groupValues[2]) {
        "cm" -> n * 72f / 2.54f; "mm" -> n * 72f / 25.4f; "in" -> n * 72f; "pc" -> n * 12f; "px", "" -> n * 0.75f; "em" -> n * 12f
        else -> n
    }
}
