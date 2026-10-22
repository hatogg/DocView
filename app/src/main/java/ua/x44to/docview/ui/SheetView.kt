@file:OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)

package ua.x44to.docview.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.x44to.docview.core.Align
import ua.x44to.docview.core.Sheet
import ua.x44to.docview.core.SheetDoc
import ua.x44to.docview.core.XCell
import ua.x44to.docview.core.colName
import ua.x44to.docview.data.Store
import ua.x44to.docview.data.t

class SheetUi(val doc: SheetDoc, startPos: Int) : SearchState(), Searcher {
    var sheet by mutableIntStateOf((startPos / 100_000).coerceIn(0, maxOf(0, doc.sheets.size - 1)))
    val lists = HashMap<Int, LazyListState>()
    val hscroll = HashMap<Int, ScrollState>()
    var zoom by mutableFloatStateOf(1f)
    var selected by mutableStateOf<Pair<Int, Int>?>(null)
    private val startRow = startPos % 100_000
    var query by mutableStateOf("")
    private var hits = emptyList<Triple<Int, Int, Int>>()
    private var scope: CoroutineScope? = null
    /** куди прокрутити по горизонталі (пікселі), коли сітка готова */
    var pendingCol by mutableStateOf<Int?>(null)

    fun list(i: Int) = lists.getOrPut(i) { LazyListState(if (i == sheet) startRow else 0) }
    fun h(i: Int) = hscroll.getOrPut(i) { ScrollState(0) }

    override val total get() = totalS
    override val current get() = currentS
    override val busy get() = busyS
    override val progress get() = progressS

    override fun search(q: String, scope: CoroutineScope) {
        this.scope = scope
        query = q.trim()
        if (query.isEmpty()) { clear(); return }
        busyS = true
        scope.launch {
            val needle = query.lowercase()
            hits = withContext(Dispatchers.Default) {
                val out = ArrayList<Triple<Int, Int, Int>>()
                for ((si, s) in doc.sheets.withIndex()) for ((ri, row) in s.rows.withIndex()) for ((ci, c) in row.withIndex()) {
                    if (c != null && c.text.lowercase().contains(needle)) { out.add(Triple(si, ri, ci)); if (out.size > 5000) return@withContext out }
                }
                out
            }
            totalS = hits.size
            busyS = false
            currentS = if (hits.isEmpty()) -1 else 0
            go()
        }
    }

    private fun go() {
        val (s, r, c) = hits.getOrNull(currentS) ?: return
        sheet = s
        selected = r to c
        pendingCol = c
        scope?.launch { list(s).animateScrollToItem(maxOf(0, r - 3)) }
    }

    override fun next() { if (hits.isNotEmpty()) { currentS = (currentS + 1) % hits.size; go() } }
    override fun prev() { if (hits.isNotEmpty()) { currentS = (currentS - 1 + hits.size) % hits.size; go() } }
    override fun clear() { query = ""; hits = emptyList(); totalS = 0; currentS = -1 }
}

@Composable
fun SheetView(doc: SheetDoc, docId: String, ui: SheetUi, paper: Paper) {
    Column(Modifier.fillMaxSize().background(paper.bg).navigationBarsPadding()) {
        if (doc.sheets.isEmpty()) {
            Empty(Icons.Rounded.Info, t("Порожня книга", "Empty workbook"), t("У файлі немає аркушів з даними.", "The file has no sheets with data."))
            return@Column
        }
        if (doc.note == "large") Text(
            t("Таблиця дуже велика — показано перші 50 000 рядків і 400 стовпців.", "Very large sheet — showing the first 50,000 rows and 400 columns."),
            style = MaterialTheme.typography.bodySmall, color = paper.dim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Box(Modifier.weight(1f)) {
            val s = doc.sheets[ui.sheet.coerceIn(0, doc.sheets.size - 1)]
            Grid(s, ui, paper, ui.list(ui.sheet), ui.h(ui.sheet))
        }
        ui.selected?.let { (r, c) ->
            val cell = doc.sheets.getOrNull(ui.sheet)?.cell(r, c)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${colName(c)}${r + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(64.dp))
                    SelectionContainer(Modifier.weight(1f)) { Text(cell?.text ?: "", style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        if (doc.sheets.size > 1) {
            PrimaryScrollableTabRow(selectedTabIndex = ui.sheet, edgePadding = 8.dp) {
                doc.sheets.forEachIndexed { i, sh ->
                    Tab(selected = i == ui.sheet, onClick = { ui.sheet = i; ui.selected = null }, text = { Text(sh.name, maxLines = 1) })
                }
            }
        }
    }
    LaunchedEffect(ui) {
        snapshotFlow { ui.sheet * 100_000 + ui.list(ui.sheet).firstVisibleItemIndex }.debounce(800).collect { Store.position(docId, it, 0) }
    }
}

@Composable
private fun Grid(s: Sheet, ui: SheetUi, paper: Paper, list: LazyListState, hs: ScrollState) {
    val z = ui.zoom
    val dens = LocalDensity.current
    // ширини стовпців у dp: з файлу або за довжиною вмісту
    val widths: FloatArray = remember(s) {
        FloatArray(s.cols) { c ->
            val w = s.widths?.getOrNull(c)
            when {
                w != null && w == 0f -> 0f
                w != null && w > 0f -> (w * 7.5f).coerceIn(24f, 600f)
                else -> {
                    var m = 0
                    for (r in 0 until minOf(s.rows.size, 300)) m = maxOf(m, s.rows[r].getOrNull(c)?.text?.lines()?.maxOfOrNull { it.length } ?: 0)
                    (m * 7.2f + 16f).coerceIn(56f, 260f)
                }
            }
        }
    }
    val starts = remember(widths) { FloatArray(widths.size + 1).also { for (i in widths.indices) it[i + 1] = it[i] + widths[i] } }
    val totalW = starts.last()
    // злиття: початок → (кінцевий стовпець), прикриті клітинки
    val mergeStart = remember(s) { s.merges.associate { (it.r1.toLong() shl 20 or it.c1.toLong()) to it } }
    val covered = remember(s) {
        val set = HashSet<Long>()
        for (m in s.merges) for (r in m.r1..minOf(m.r2, m.r1 + 2000)) for (c in m.c1..minOf(m.c2, m.c1 + 200)) if (r != m.r1 || c != m.c1) set.add(r.toLong() shl 20 or c.toLong())
        set
    }
    val rowH = 30f * z
    val numW = (34f + s.rows.size.toString().length * 7f) * z
    val line = paper.dim.copy(alpha = 0.3f)
    val head = if (paper.dark) Color(0xFF2A2B30) else Color(0xFFF1F3F4)
    BoxWithConstraints(Modifier.fillMaxSize().pinch({ f -> ui.zoom = (ui.zoom * f).coerceIn(0.5f, 2.5f) })) {
        val viewW = with(dens) { maxWidth.toPx() }
        // видимі стовпці (з запасом), щоб не малювати сотні клітинок у кожному рядку
        val visible by remember(widths, z) {
            derivedStateOf {
                val x0 = with(dens) { hs.value.toDp().value } / z
                val x1 = x0 + with(dens) { (viewW).toDp().value } / z
                var a = starts.indexOfFirst { it > x0 } - 1
                if (a < 0) a = 0
                var b = starts.indexOfFirst { it > x1 }
                if (b < 0) b = widths.size
                (a - 1).coerceAtLeast(0) until (b + 1).coerceAtMost(widths.size)
            }
        }
        LaunchedEffect(ui.pendingCol) {
            ui.pendingCol?.let { c ->
                val x = with(dens) { (starts.getOrElse(c) { 0f } * z).dp.toPx() }.toInt()
                hs.animateScrollTo((x - viewW / 3).toInt().coerceAtLeast(0))
                ui.pendingCol = null
            }
        }
        Column(Modifier.fillMaxSize()) {
            // заголовок стовпців
            Row(Modifier.background(head)) {
                Box(Modifier.width(numW.dp).height(rowH.dp).border(0.5.dp, line))
                Row(Modifier.horizontalScroll(hs)) {
                    val range = visible
                    Spacer(Modifier.width((starts[range.first] * z).dp))
                    for (c in range) {
                        Box(Modifier.width((widths[c] * z).dp).height(rowH.dp).border(0.5.dp, line), contentAlignment = Alignment.Center) {
                            Text(colName(c), fontSize = (12 * z).sp, color = paper.dim, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.width(((totalW - starts[range.last + 1]) * z).dp))
                }
            }
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                items(s.rows.size, key = { it }) { r ->
                    val row = s.rows[r]
                    Row {
                        Box(Modifier.width(numW.dp).height(rowH.dp).background(head).border(0.5.dp, line), contentAlignment = Alignment.Center) {
                            Text("${r + 1}", fontSize = (12 * z).sp, color = paper.dim, maxLines = 1)
                        }
                        Row(Modifier.horizontalScroll(hs)) {
                            val range = visible
                            Spacer(Modifier.width((starts[range.first] * z).dp))
                            var c = range.first
                            while (c <= range.last) {
                                val key = r.toLong() shl 20 or c.toLong()
                                if (key in covered) {
                                    Box(Modifier.width((widths[c] * z).dp).height(rowH.dp))
                                    c++
                                    continue
                                }
                                val m = mergeStart[key]
                                val span = if (m != null) (m.c2 - c + 1).coerceAtMost(widths.size - c) else 1
                                var w = 0f
                                for (k in c until c + span) w += widths[k]
                                CellBox(row.getOrNull(c), (w * z), rowH, z, paper, line, ui.selected == (r to c), ui.query) { ui.selected = r to c }
                                c += span
                            }
                            val end = minOf(c, widths.size)
                            Spacer(Modifier.width(((totalW - starts[end]) * z).dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CellBox(x: XCell?, w: Float, h: Float, z: Float, paper: Paper, line: Color, selected: Boolean, query: String, onClick: () -> Unit) {
    val st = x?.style
    val bg = st?.bg?.let { Color(it).copy(alpha = if (paper.dark) 0.4f else 1f) }
    val hit = query.isNotEmpty() && x != null && x.text.contains(query, ignoreCase = true)
    var m = Modifier.width(w.dp).height(h.dp)
    if (bg != null) m = m.background(bg)
    if (hit) m = m.background(Color(0xFFFFEB3B).copy(alpha = 0.6f))
    m = m.border(if (selected) 2.dp else 0.5.dp, if (selected) MaterialTheme.colorScheme.primary else line).clickable(onClick = onClick).padding(horizontal = (4 * z).dp)
    Box(m, contentAlignment = when (st?.align) {
        Align.CENTER -> Alignment.Center
        Align.END -> Alignment.CenterEnd
        Align.START -> Alignment.CenterStart
        else -> if (x?.num == true) Alignment.CenterEnd else Alignment.CenterStart
    }) {
        if (x != null && x.text.isNotEmpty()) {
            val color = st?.color?.let { c -> Color(c).takeIf { col -> !(paper.dark && (col.red + col.green + col.blue) / 3 < 0.35f) } } ?: paper.fg
            Text(
                x.text.replace('\n', ' '), fontSize = (13 * z).sp, color = color, maxLines = 1, overflow = TextOverflow.Clip,
                fontWeight = if (st?.b == true) FontWeight.Bold else null, fontStyle = if (st?.i == true) FontStyle.Italic else null,
                textDecoration = if (st?.u == true) TextDecoration.Underline else null,
                textAlign = when (st?.align) { Align.CENTER -> TextAlign.Center; Align.END -> TextAlign.End; else -> null },
                softWrap = false,
            )
        }
    }
}
