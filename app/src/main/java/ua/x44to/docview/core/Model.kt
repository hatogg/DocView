package ua.x44to.docview.core

import java.io.File
import java.util.zip.ZipFile

/** Спільна модель документа, у яку перетворюються всі формати (крім PDF і картинок — їх малює система). */
sealed class Doc {
    abstract val title: String?
}

/** Звідки брати картинки документа: за ключем (шлях у zip, ім'я в FB2…). */
fun interface Resources {
    fun bytes(key: String): ByteArray?

    companion object {
        val NONE = Resources { null }

        /** Картинки всередині zip-контейнера (DOCX, EPUB, ODT…). */
        fun zip(file: File) = Resources { key ->
            try {
                ZipFile(file).use { z -> z.getEntry(key)?.let { e -> z.getInputStream(e).use { it.readBytes() } } }
            } catch (_: Exception) {
                // пошкоджений zip — шукаємо послідовно
                try {
                    java.util.zip.ZipInputStream(file.inputStream().buffered()).use { zs ->
                        var found: ByteArray? = null
                        while (true) {
                            val e = zs.nextEntry ?: break
                            if (e.name == key) { found = zs.readBytes(); break }
                        }
                        found
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }

        fun map(m: Map<String, ByteArray>) = Resources { m[it] }
    }
}

enum class Align { START, CENTER, END, JUSTIFY }

/** Шматок тексту з однаковим оформленням. [pt] — розмір шрифту в пунктах, якщо відомий. */
class Run(
    val text: String,
    val b: Boolean = false,
    val i: Boolean = false,
    val u: Boolean = false,
    val s: Boolean = false,
    val color: Int? = null,
    val bg: Int? = null,
    val pt: Float? = null,
    val link: String? = null,
    val sup: Boolean = false,
    val sub: Boolean = false,
    val mono: Boolean = false,
    val caps: Boolean = false,
) {
    fun copy(text: String) = Run(text, b, i, u, s, color, bg, pt, link, sup, sub, mono, caps)
    fun same(o: Run) = b == o.b && i == o.i && u == o.u && s == o.s && color == o.color && bg == o.bg && pt == o.pt &&
        link == o.link && sup == o.sup && sub == o.sub && mono == o.mono && caps == o.caps
}

sealed class Block

class Para(
    val runs: List<Run>,
    /** 0 — звичайний абзац, 1..6 — заголовок */
    val heading: Int = 0,
    val align: Align = Align.START,
    /** рівень відступу (списки, цитати) */
    val indent: Int = 0,
    /** «•», «1.», «а)» */
    val bullet: String? = null,
    val quote: Boolean = false,
    val mono: Boolean = false,
    /** відступ першого рядка (як у книжках) */
    val firstLine: Boolean = false,
) : Block() {
    val text: String by lazy { runs.joinToString("") { it.text } }
    val isEmpty get() = runs.all { it.text.isBlank() }
}

class Cell(val blocks: List<Block>, val span: Int = 1, val rowSpan: Int = 1, val bg: Int? = null) {
    val text: String get() = blocks.joinToString("\n") { blockText(it) }
}

class Row(val cells: List<Cell>, val header: Boolean = false)

class Table(val rows: List<Row>) : Block() {
    val cols: Int get() = rows.maxOfOrNull { r -> r.cells.sumOf { it.span } } ?: 0
}

/** Картинка; розміри в пунктах, якщо відомі. */
class Image(val key: String, val w: Float? = null, val h: Float? = null, val alt: String? = null) : Block()

/** Розрив сторінки / горизонтальна лінія / роздільник розділів. */
class Rule(val pageBreak: Boolean = false) : Block()

fun blockText(b: Block): String = when (b) {
    is Para -> (b.bullet?.let { "$it " } ?: "") + b.text
    is Table -> b.rows.joinToString("\n") { r -> r.cells.joinToString("\t") { it.text } }
    is Image -> b.alt ?: ""
    is Rule -> ""
}

class TocItem(val title: String, val block: Int, val level: Int = 0)

enum class FlowKind { DOCUMENT, BOOK, TEXT }

/** Текстовий документ: Word, ODT, RTF, книги, TXT, HTML. */
class FlowDoc(
    override val title: String?,
    val blocks: List<Block>,
    val res: Resources = Resources.NONE,
    val kind: FlowKind = FlowKind.DOCUMENT,
    val toc: List<TocItem> = emptyList(),
    val author: String? = null,
    val cover: String? = null,
    /** базовий розмір шрифту документа в пт — від нього рахуються відносні розміри */
    val basePt: Float = 11f,
    /** попередження: «показано лише текст» тощо */
    val note: String? = null,
) : Doc()

// ───────── таблиці ─────────

class CellStyle(
    val b: Boolean = false,
    val i: Boolean = false,
    val u: Boolean = false,
    val color: Int? = null,
    val bg: Int? = null,
    val align: Align? = null,
    val wrap: Boolean = false,
)

class XCell(val text: String, val num: Boolean = false, val style: CellStyle? = null)

class Merge(val r1: Int, val c1: Int, val r2: Int, val c2: Int)

class Sheet(
    val name: String,
    /** рядки → комірки; null — порожньо */
    val rows: List<Array<XCell?>>,
    val cols: Int,
    /** ширини стовпців у «символах» Excel (≈ 7 px) */
    val widths: FloatArray? = null,
    val merges: List<Merge> = emptyList(),
) {
    fun cell(r: Int, c: Int): XCell? = rows.getOrNull(r)?.getOrNull(c)
}

class SheetDoc(override val title: String?, val sheets: List<Sheet>, val note: String? = null) : Doc()

// ───────── презентації ─────────

enum class Geom { RECT, ROUND, ELLIPSE, LINE }
enum class VAlign { TOP, MIDDLE, BOTTOM }

class SPara(val runs: List<Run>, val align: Align = Align.START, val bullet: String? = null, val level: Int = 0) {
    val text: String get() = runs.joinToString("") { it.text }
}

/** Фігура на слайді; координати й розміри — у пунктах від лівого верхнього кута. */
class Shape(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val paras: List<SPara> = emptyList(),
    val fill: Int? = null,
    val line: Int? = null,
    val geom: Geom = Geom.RECT,
    val image: String? = null,
    val anchor: VAlign = VAlign.TOP,
    val rot: Float = 0f,
    val table: List<List<String>>? = null,
    /** розмір тексту за замовчуванням для цієї фігури */
    val defPt: Float = 18f,
    val inset: Float = 7.2f,
    val autofit: Float = 1f,
)

class Slide(val shapes: List<Shape>, val bg: Int? = null, val bgImage: String? = null, val notes: String? = null) {
    val text: String get() = shapes.joinToString("\n") { s -> s.paras.joinToString("\n") { it.text } + (s.table?.joinToString("\n") { it.joinToString("\t") } ?: "") }
}

class SlideDoc(override val title: String?, val w: Float, val h: Float, val slides: List<Slide>, val res: Resources = Resources.NONE, val note: String? = null) : Doc()

// ───────── інше ─────────

class ArcEntry(val path: String, val size: Long, val packed: Long, val dir: Boolean, val time: Long)

class ArchiveDoc(override val title: String?, val entries: List<ArcEntry>) : Doc()

/** Файл, який показує сама система (PDF, картинка). */
class NativeDoc(override val title: String?, val format: Format) : Doc()

class DocException(message: String, val needsPassword: Boolean = false) : Exception(message)
