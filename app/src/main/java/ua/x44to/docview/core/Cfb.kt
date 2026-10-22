package ua.x44to.docview.core

import java.io.File

/**
 * Читач «складеного файлу» OLE2 (Compound File Binary) — контейнера старих DOC, XLS і PPT.
 * Достатньо, щоб дістати потоки за іменем: WordDocument, 1Table, Workbook, PowerPoint Document.
 */
class Cfb(private val b: ByteArray) {
    class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    private val secSize: Int
    private val miniSize: Int
    private val cutoff: Int
    private val fat: IntArray
    private val miniFat: IntArray
    val entries: List<Entry>
    private val miniStream: ByteArray

    init {
        if (b.size < 512 || !isCfb(b)) throw DocException("Not an OLE2 file")
        secSize = 1 shl u16(0x1E)
        miniSize = 1 shl u16(0x20)
        cutoff = i32(0x38).takeIf { it > 0 } ?: 4096
        // DIFAT: 109 записів у заголовку + ланцюжок
        val difat = ArrayList<Int>()
        for (i in 0 until 109) {
            val v = i32(0x4C + i * 4)
            if (v >= 0) difat.add(v)
        }
        var ds = i32(0x44)
        var guard = 0
        val per = secSize / 4 - 1
        while (ds >= 0 && guard++ < 10_000) {
            val off = (ds + 1) * secSize
            if (off + secSize > b.size) break
            for (i in 0 until per) {
                val v = i32(off + i * 4)
                if (v >= 0) difat.add(v)
            }
            ds = i32(off + per * 4)
        }
        val ints = secSize / 4
        fat = IntArray(difat.size * ints)
        for ((k, s) in difat.withIndex()) {
            val off = (s + 1) * secSize
            for (i in 0 until ints) fat[k * ints + i] = if (off + i * 4 + 4 <= b.size) i32(off + i * 4) else -1
        }
        val dir = chain(i32(0x30), -1)
        val list = ArrayList<Entry>()
        var p = 0
        while (p + 128 <= dir.size) {
            val nl = (dir[p + 0x40].toInt() and 0xFF) or ((dir[p + 0x41].toInt() and 0xFF) shl 8)
            val type = dir[p + 0x42].toInt()
            val name = if (nl in 2..64) String(dir, p, nl - 2, Charsets.UTF_16LE) else ""
            val start = le32(dir, p + 0x74)
            val size = le32(dir, p + 0x78).toLong() and 0xFFFFFFFFL
            list.add(Entry(name, type, start, size))
            p += 128
        }
        entries = list
        val mf = chain(i32(0x3C), -1)
        miniFat = IntArray(mf.size / 4) { le32(mf, it * 4) }
        val root = list.firstOrNull { it.type == 5 }
        miniStream = if (root != null && root.start >= 0) chain(root.start, root.size) else ByteArray(0)
    }

    private fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun i32(o: Int) = le32(b, o)

    /** Ланцюжок секторів від [start]; [size] < 0 — до кінця ланцюжка. */
    private fun chain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        val limit = if (size >= 0) size else Long.MAX_VALUE
        while (s >= 0 && s < fat.size && out.size() < limit && guard++ < 2_000_000) {
            val off = (s + 1) * secSize
            if (off >= b.size) break
            out.write(b, off, minOf(secSize, b.size - off))
            s = fat[s]
        }
        val all = out.toByteArray()
        return if (size in 0 until all.size) all.copyOf(size.toInt()) else all
    }

    private fun miniChain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && s < miniFat.size && out.size() < size && guard++ < 2_000_000) {
            val off = s * miniSize
            if (off >= miniStream.size) break
            out.write(miniStream, off, minOf(miniSize, miniStream.size - off))
            s = miniFat[s]
        }
        val all = out.toByteArray()
        return if (size < all.size) all.copyOf(size.toInt()) else all
    }

    fun has(name: String) = entries.any { it.type == 2 && it.name.equals(name, ignoreCase = true) }

    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) } ?: return null
        return if (e.size < cutoff) miniChain(e.start, e.size) else chain(e.start, e.size)
    }

    companion object {
        fun isCfb(h: ByteArray) = h.size >= 8 && h[0] == 0xD0.toByte() && h[1] == 0xCF.toByte() && h[2] == 0x11.toByte() &&
            h[3] == 0xE0.toByte() && h[4] == 0xA1.toByte() && h[5] == 0xB1.toByte() && h[6] == 0x1A.toByte() && h[7] == 0xE1.toByte()

        fun open(f: File): Cfb {
            if (f.length() > 300L * 1024 * 1024) throw DocException("File is too large")
            return Cfb(f.readBytes())
        }
    }
}

fun le16(b: ByteArray, o: Int) = if (o + 1 < b.size) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) else 0
fun le32(b: ByteArray, o: Int) = if (o + 3 < b.size)
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
else 0
fun le64d(b: ByteArray, o: Int): Double {
    var v = 0L
    for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
    return java.lang.Double.longBitsToDouble(v)
}
