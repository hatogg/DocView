package ua.x44to.docview.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/** Мова: українська для uk/ru/be, інакше англійська. */
object L {
    @Volatile var override: String? = null
    val ukr: Boolean get() = (override ?: Locale.getDefault().language) in setOf("uk", "ru", "be")

    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10; val m100 = n % 100
        val w = if (m10 == 1 && m100 != 11) one else if (m10 in 2..4 && m100 !in 12..14) few else many
        return "$n $w"
    }

    fun pages(n: Int) = if (ukr) plural(n, "сторінка", "сторінки", "сторінок") else "$n " + if (n == 1) "page" else "pages"
    fun slides(n: Int) = if (ukr) plural(n, "слайд", "слайди", "слайдів") else "$n " + if (n == 1) "slide" else "slides"
    fun sheets(n: Int) = if (ukr) plural(n, "аркуш", "аркуші", "аркушів") else "$n " + if (n == 1) "sheet" else "sheets"
    fun files(n: Int) = if (ukr) plural(n, "файл", "файли", "файлів") else "$n " + if (n == 1) "file" else "files"
    fun results(n: Int) = if (ukr) plural(n, "збіг", "збіги", "збігів") else "$n " + if (n == 1) "match" else "matches"

    fun size(bytes: Long): String {
        val k = 1024.0
        return when {
            bytes < k -> "$bytes B"
            bytes < k * k -> "%.0f KB".format(Locale.ROOT, bytes / k)
            bytes < k * k * k -> "%.1f MB".format(Locale.ROOT, bytes / k / k)
            else -> "%.1f GB".format(Locale.ROOT, bytes / k / k / k)
        }.let { if (ukr) it.replace("KB", "КБ").replace("MB", "МБ").replace("GB", "ГБ").replace(" B", " Б").replace('.', ',') else it }
    }
}

fun t(uk: String, en: String) = if (L.ukr) uk else en

/** Файл в історії: звідки (uri) і де лежить копія. */
@Serializable
data class Recent(
    val id: String,
    val name: String,
    val format: String,
    val size: Long,
    /** коли відкривали востаннє */
    val opened: Long,
    /** копія в пам'яті застосунку */
    val path: String,
    /** позиція читання: номер сторінки / блоку / слайда */
    val pos: Int = 0,
    val total: Int = 0,
    val star: Boolean = false,
)

@Serializable
data class Settings(
    /** 0 — як у системі, 1 — світла, 2 — темна */
    val theme: Int = 0,
    val dynamic: Boolean = true,
    /** 0 — як тема, 1 — папір, 2 — сепія, 3 — ніч */
    val reading: Int = 0,
    val textScale: Float = 1f,
    val keepScreenOn: Boolean = true,
    /** PDF у нічному режимі — інвертовані кольори */
    val pdfNight: Boolean = false,
)

@Serializable
data class Data(val recents: List<Recent> = emptyList(), val settings: Settings = Settings())

/** Історія файлів і налаштування — один невеликий JSON. */
object Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _data = MutableStateFlow(Data())
    val data: StateFlow<Data> = _data
    private var file: File? = null
    lateinit var docsDir: File
        private set

    @Synchronized
    fun init(c: Context) {
        if (file != null) return
        val f = File(c.filesDir, "docview.json")
        file = f
        docsDir = File(c.filesDir, "docs").apply { mkdirs() }
        _data.value = try {
            if (f.exists()) json.decodeFromString(Data.serializer(), f.readText()) else Data()
        } catch (_: Exception) {
            Data()
        }
    }

    @Synchronized
    fun update(t: (Data) -> Data) {
        val n = t(_data.value)
        if (n == _data.value) return
        _data.value = n
        try {
            val f = file ?: return
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(Data.serializer(), n))
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {
        }
    }

    fun settings(t: (Settings) -> Settings) = update { it.copy(settings = t(it.settings)) }

    fun touch(r: Recent) = update { d ->
        val others = d.recents.filter { it.id != r.id }
        val old = d.recents.firstOrNull { it.id == r.id }
        val merged = r.copy(pos = old?.pos ?: r.pos, star = old?.star ?: r.star, total = if (r.total > 0) r.total else old?.total ?: 0)
        val list = listOf(merged) + others
        // старі копії без зірочки прибираємо: не більше 60 файлів і 1,5 ГБ
        var total = 0L
        val keep = ArrayList<Recent>()
        for ((i, x) in list.withIndex()) {
            total += x.size
            if (x.star || (i < 60 && total < 1_500L * 1024 * 1024)) keep.add(x) else File(x.path).delete()
        }
        d.copy(recents = keep)
    }

    fun position(id: String, pos: Int, total: Int) = update { d ->
        d.copy(recents = d.recents.map { if (it.id == id) it.copy(pos = pos, total = if (total > 0) total else it.total) else it })
    }

    fun star(id: String) = update { d -> d.copy(recents = d.recents.map { if (it.id == id) it.copy(star = !it.star) else it }) }

    fun remove(id: String) = update { d ->
        d.recents.firstOrNull { it.id == id }?.let { File(it.path).delete() }
        d.copy(recents = d.recents.filter { it.id != id })
    }

    fun clear() = update { d ->
        val keep = d.recents.filter { it.star }
        d.recents.filter { !it.star }.forEach { File(it.path).delete() }
        d.copy(recents = keep)
    }
}
