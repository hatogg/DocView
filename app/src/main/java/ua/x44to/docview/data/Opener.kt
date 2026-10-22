package ua.x44to.docview.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ua.x44to.docview.core.ArcEntry
import ua.x44to.docview.core.Archive
import ua.x44to.docview.core.Detect
import ua.x44to.docview.core.Format
import java.io.File
import java.security.MessageDigest

/** Відкритий файл: копія в пам'яті застосунку + запис в історії. */
class Opened(val recent: Recent, val file: File, val format: Format) {
    val id get() = recent.id
    val name get() = recent.name
}

class OpenException(message: String) : Exception(message)

/** Копіює файл із чужого застосунку (Telegram, «Файли», пошта) до себе й визначає формат. */
object Opener {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun nameOf(c: Context, uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = cur.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = cur.getString(ni)
                    if (si >= 0 && !cur.isNull(si)) size = cur.getLong(si)
                }
            }
        } catch (_: Exception) {
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "document"
        return name!! to size
    }

    fun import(c: Context, uri: Uri): Opened = Perf.time("import") { importNow(c, uri) }

    private fun importNow(c: Context, uri: Uri): Opened {
        val (name, _) = nameOf(c, uri)
        val tmp = File(c.cacheDir, "import.tmp")
        val md = MessageDigest.getInstance("SHA-1")
        var size = 0L
        val input = try {
            c.contentResolver.openInputStream(uri)
        } catch (e: SecurityException) {
            throw OpenException(t("Немає доступу до файлу", "No access to the file"))
        } catch (e: Exception) {
            null
        } ?: throw OpenException(t("Не вдалося відкрити файл", "Couldn't open the file"))
        input.use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                    size += n
                    if (size > 1_000L * 1024 * 1024) throw OpenException(t("Файл завеликий (більше 1 ГБ)", "The file is too large (over 1 GB)"))
                }
            }
        }
        if (size == 0L) { tmp.delete(); throw OpenException(t("Файл порожній", "The file is empty")) }
        val id = hex(md.digest()).take(16)
        return place(tmp, id, name, size)
    }

    private fun place(tmp: File, id: String, name: String, size: Long): Opened {
        val format = Detect.of(tmp, name)
        val ext = Detect.ext(name).ifEmpty { format.label.lowercase() }.take(8)
        val dest = File(Store.docsDir, "$id.$ext")
        if (!dest.exists() || dest.length() != size) {
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        } else tmp.delete()
        val r = Recent(id, name, format.name, size, System.currentTimeMillis(), dest.path)
        Store.touch(r)
        val stored = Store.data.value.recents.firstOrNull { it.id == id } ?: r
        return Opened(stored, dest, format)
    }

    fun fromRecent(r: Recent): Opened? {
        val f = File(r.path)
        if (!f.exists()) return null
        val format = runCatching { Format.valueOf(r.format) }.getOrDefault(Format.UNKNOWN)
        Store.touch(r.copy(opened = System.currentTimeMillis()))
        return Opened(Store.data.value.recents.firstOrNull { it.id == r.id } ?: r, f, format)
    }

    /** Файл з архіву: розпакувати й відкрити як звичайний. */
    fun fromArchive(c: Context, parent: Opened, e: ArcEntry): Opened {
        val tmp = File(c.cacheDir, "extract.tmp")
        if (!Archive.extract(parent.file, e.path, tmp)) throw OpenException(t("Не вдалося розпакувати", "Couldn't extract the file"))
        val md = MessageDigest.getInstance("SHA-1")
        tmp.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val id = hex(md.digest()).take(16)
        return place(tmp, id, e.path.substringAfterLast('/'), tmp.length())
    }
}
