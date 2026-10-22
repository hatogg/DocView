package ua.x44to.docview.core

import java.io.File
import java.util.zip.ZipFile

enum class Group { PDF, WORD, EXCEL, SLIDES, BOOK, TEXT, IMAGE, ARCHIVE, OTHER }

enum class Format(val group: Group, val label: String) {
    PDF(Group.PDF, "PDF"),
    DOCX(Group.WORD, "DOCX"), DOC(Group.WORD, "DOC"), ODT(Group.WORD, "ODT"), RTF(Group.WORD, "RTF"),
    XLSX(Group.EXCEL, "XLSX"), XLS(Group.EXCEL, "XLS"), ODS(Group.EXCEL, "ODS"), CSV(Group.EXCEL, "CSV"),
    PPTX(Group.SLIDES, "PPTX"), PPT(Group.SLIDES, "PPT"), ODP(Group.SLIDES, "ODP"),
    EPUB(Group.BOOK, "EPUB"), FB2(Group.BOOK, "FB2"),
    HTML(Group.TEXT, "HTML"), MD(Group.TEXT, "MD"), TXT(Group.TEXT, "TXT"),
    IMAGE(Group.IMAGE, "IMG"),
    ZIP(Group.ARCHIVE, "ZIP"),
    UNKNOWN(Group.OTHER, "?"),
}

/** Визначає формат за вмістом (а не лише за розширенням — з Telegram файли часто приходять без нього). */
object Detect {
    private val textExt = setOf(
        "txt", "log", "ini", "cfg", "conf", "json", "xml", "yml", "yaml", "toml", "kt", "java", "py", "js", "ts", "c", "h",
        "cpp", "cs", "go", "rs", "sh", "bat", "sql", "css", "srt", "vtt", "properties", "gradle", "kts", "swift", "php", "rb", "lua", "nfo",
    )

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun of(file: File, name: String): Format {
        val head = ByteArray(4096)
        val n = file.inputStream().use { it.read(head) }.coerceAtLeast(0)
        val h = head.copyOf(n)
        val ext = ext(name)
        if (n == 0) return if (ext in textExt || ext == "txt") Format.TXT else Format.UNKNOWN
        val start = String(h, 0, minOf(n, 1024), Charsets.ISO_8859_1)
        // PDF може мати сміття перед %PDF
        if (start.contains("%PDF-")) return Format.PDF
        if (h.size >= 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() && h[2].toInt() in 3..7) return zip(file, ext)
        if (Cfb.isCfb(h)) return cfb(file, ext)
        if (start.startsWith("{\\rtf")) return Format.RTF
        if (isImage(h)) return Format.IMAGE
        // далі — текстові формати
        val text = looksText(h)
        if (!text) return if (ext == "csv") Format.CSV else Format.UNKNOWN
        val lower = Text.decode(h).take(2000).lowercase()
        if (lower.contains("<fictionbook")) return Format.FB2
        return when (ext) {
            "csv", "tsv" -> Format.CSV
            "md", "markdown" -> Format.MD
            "htm", "html", "xhtml", "mht" -> Format.HTML
            "fb2" -> Format.FB2
            "rtf" -> Format.RTF
            else -> when {
                lower.trimStart().startsWith("<!doctype html") || lower.trimStart().startsWith("<html") -> Format.HTML
                else -> Format.TXT
            }
        }
    }

    private fun zip(file: File, ext: String): Format {
        return try {
            ZipFile(file).use { z ->
                if (z.getEntry("word/document.xml") != null) return Format.DOCX
                if (z.getEntry("xl/workbook.xml") != null) return Format.XLSX
                if (z.getEntry("ppt/presentation.xml") != null) return Format.PPTX
                z.getEntry("mimetype")?.let { e ->
                    val mt = z.getInputStream(e).use { String(it.readBytes().take(100).toByteArray()) }.trim()
                    when {
                        mt.startsWith("application/epub") -> return Format.EPUB
                        mt.contains("opendocument.text") -> return Format.ODT
                        mt.contains("opendocument.spreadsheet") -> return Format.ODS
                        mt.contains("opendocument.presentation") -> return Format.ODP
                    }
                }
                if (z.getEntry("META-INF/container.xml") != null) return Format.EPUB
                if (z.getEntry("content.xml") != null) {
                    return when (ext) { "ods" -> Format.ODS; "odp" -> Format.ODP; else -> Format.ODT }
                }
                val names = z.entries().asSequence().filter { !it.isDirectory }.map { it.name }.take(3).toList()
                if (names.size == 1 && names[0].lowercase().endsWith(".fb2")) return Format.FB2
                Format.ZIP
            }
        } catch (_: Exception) {
            // пошкоджений центральний каталог — дивимося на локальні заголовки
            try {
                val names = HashSet<String>()
                java.util.zip.ZipInputStream(file.inputStream().buffered()).use { zs ->
                    var n = 0
                    while (n++ < 5000) { val e = zs.nextEntry ?: break; names.add(e.name) }
                }
                when {
                    "word/document.xml" in names -> Format.DOCX
                    "xl/workbook.xml" in names -> Format.XLSX
                    "ppt/presentation.xml" in names -> Format.PPTX
                    "META-INF/container.xml" in names -> Format.EPUB
                    else -> Format.ZIP
                }
            } catch (_: Exception) {
                Format.ZIP
            }
        }
    }

    private fun cfb(file: File, ext: String): Format = try {
        val c = Cfb.open(file)
        when {
            c.has("WordDocument") -> Format.DOC
            c.has("Workbook") || c.has("Book") -> Format.XLS
            c.has("PowerPoint Document") -> Format.PPT
            c.has("EncryptedPackage") -> when (ext) {
                "xlsx", "xlsm" -> Format.XLSX; "pptx" -> Format.PPTX; else -> Format.DOCX
            }
            else -> Format.UNKNOWN
        }
    } catch (_: Exception) {
        Format.UNKNOWN
    }

    fun isImage(h: ByteArray): Boolean {
        if (h.size < 12) return false
        fun at(i: Int, vararg v: Int) = v.withIndex().all { (k, x) -> (h[i + k].toInt() and 0xFF) == x }
        return at(0, 0x89, 0x50, 0x4E, 0x47) || at(0, 0xFF, 0xD8, 0xFF) || at(0, 0x47, 0x49, 0x46, 0x38) ||
            (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50)) || at(0, 0x42, 0x4D) ||
            (at(4, 0x66, 0x74, 0x79, 0x70) && String(h, 8, 4, Charsets.ISO_8859_1).let { it.startsWith("hei") || it.startsWith("mif") || it.startsWith("avi") })
    }

    /** Текст — якщо немає нульових байтів (або це UTF-16 з BOM) і мало керівних символів. */
    fun looksText(h: ByteArray): Boolean {
        if (h.size >= 2 && ((h[0] == 0xFF.toByte() && h[1] == 0xFE.toByte()) || (h[0] == 0xFE.toByte() && h[1] == 0xFF.toByte()))) return true
        var bad = 0
        for (x in h) {
            val v = x.toInt() and 0xFF
            if (v == 0) return false
            if (v < 9 || (v in 14..31 && v != 27)) bad++
        }
        return bad * 50 < h.size + 1
    }
}
