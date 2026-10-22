package ua.x44to.docview.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.x44to.docview.core.Align
import ua.x44to.docview.core.Block
import ua.x44to.docview.core.FlowDoc
import ua.x44to.docview.core.FlowKind
import ua.x44to.docview.core.Image
import ua.x44to.docview.core.Para
import ua.x44to.docview.core.Rule
import ua.x44to.docview.core.Table
import ua.x44to.docview.core.blockText
import ua.x44to.docview.data.Store
import ua.x44to.docview.render.Bitmaps
import kotlin.math.max

/** Стан перегляду тексту: прокрутка, пошук, масштаб. */
class FlowUi(val doc: FlowDoc, start: Int) : SearchState(), Searcher {
    val list = LazyListState(start.coerceIn(0, maxOf(0, doc.blocks.size - 1)))
    var query by mutableStateOf("")
    /** збіги: (блок, позиція в тексті блоку) */
    var hits by mutableStateOf<List<Pair<Int, Int>>>(emptyList())
    var zoom by mutableFloatStateOf(1f)
    private var scope: CoroutineScope? = null

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
            val found = withContext(Dispatchers.Default) {
                val out = ArrayList<Pair<Int, Int>>()
                for ((i, b) in doc.blocks.withIndex()) {
                    val text = blockText(b).lowercase()
                    var k = text.indexOf(needle)
                    while (k >= 0 && out.size < 5000) { out.add(i to k); k = text.indexOf(needle, k + needle.length) }
                }
                out
            }
            hits = found
            totalS = found.size
            busyS = false
            // перший збіг — найближчий після поточної позиції
            val from = list.firstVisibleItemIndex
            currentS = found.indexOfFirst { it.first >= from }.let { if (it < 0 && found.isNotEmpty()) 0 else it }
            go()
        }
    }

    private fun go() {
        val h = hits.getOrNull(currentS) ?: return
        scope?.launch { list.animateScrollToItem(h.first, -200) }
    }

    override fun next() { if (hits.isNotEmpty()) { currentS = (currentS + 1) % hits.size; go() } }
    override fun prev() { if (hits.isNotEmpty()) { currentS = (currentS - 1 + hits.size) % hits.size; go() } }
    override fun clear() { query = ""; hits = emptyList(); totalS = 0; currentS = -1 }
}

private fun readable(c: Int?, p: Paper): Color? {
    if (c == null) return null
    val col = Color(c)
    val l = col.luminance()
    return when {
        p.dark && l < 0.35f -> null
        !p.dark && l > 0.85f -> null
        else -> col
    }
}

/** Абзац → AnnotatedString з усім оформленням і підсвіткою пошуку. */
fun annotate(p: Para, doc: FlowDoc, paper: Paper, query: String, currentAt: Int?): AnnotatedString = buildAnnotatedString {
    val heading = p.heading
    val hScale = when (heading) { 1 -> 1.6f; 2 -> 1.4f; 3 -> 1.22f; 4 -> 1.1f; else -> 1f }
    withStyle(
        ParagraphStyle(
            textAlign = when (p.align) { Align.CENTER -> TextAlign.Center; Align.END -> TextAlign.End; Align.JUSTIFY -> TextAlign.Justify; else -> TextAlign.Start },
            textIndent = if (p.firstLine) TextIndent(firstLine = 1.5.em) else null,
        ),
    ) {
        for (r in p.runs) {
            val rel = r.pt?.let { (it / doc.basePt).coerceIn(0.6f, 3f) }
            val size: TextUnit = when {
                heading > 0 -> (max(rel ?: hScale, hScale)).em
                rel != null && doc.kind == FlowKind.DOCUMENT -> rel.em
                else -> TextUnit.Unspecified
            }
            val st = SpanStyle(
                color = readable(r.color, paper) ?: Color.Unspecified,
                fontSize = size,
                fontWeight = if (r.b || heading in 1..5) FontWeight.Bold else null,
                fontStyle = if (r.i) FontStyle.Italic else null,
                textDecoration = when {
                    r.u && r.s -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    r.u -> TextDecoration.Underline
                    r.s -> TextDecoration.LineThrough
                    else -> null
                },
                background = r.bg?.let { Color(it).copy(alpha = if (paper.dark) 0.45f else 0.85f) } ?: Color.Unspecified,
                baselineShift = if (r.sup) BaselineShift.Superscript else if (r.sub) BaselineShift.Subscript else null,
                fontFamily = if (r.mono || p.mono) FontFamily.Monospace else null,
            )
            val text = if (r.caps) r.text.uppercase() else r.text
            val link = r.link
            if (link != null && (link.startsWith("http://") || link.startsWith("https://") || link.startsWith("mailto:"))) {
                withLink(LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(color = paper.link, textDecoration = TextDecoration.Underline)))) {
                    withStyle(st) { append(text) }
                }
            } else withStyle(st) { append(text) }
        }
    }
    if (query.isNotEmpty()) {
        val plain = toString().lowercase()
        var k = plain.indexOf(query.lowercase())
        while (k >= 0) {
            val cur = currentAt != null && k == currentAt
            addStyle(SpanStyle(background = if (cur) Color(0xFFFF9800) else Color(0xFFFFEB3B).copy(alpha = 0.7f), color = if (cur || !paper.dark) Color.Unspecified else Color.Black), k, k + query.length)
            k = plain.indexOf(query.lowercase(), k + query.length)
        }
    }
}

@Composable
fun FlowView(doc: FlowDoc, docId: String, ui: FlowUi, paper: Paper, textScale: Float, onTap: () -> Unit, onImage: (String) -> Unit) {
    val base = 16f * textScale * ui.zoom
    LaunchedEffect(ui) {
        snapshotFlow { ui.list.firstVisibleItemIndex }.debounce(800).collect { Store.position(docId, it, doc.blocks.size) }
    }
    val book = doc.kind == FlowKind.BOOK
    Box(
        Modifier.fillMaxSize().background(paper.bg).pinch(
            onScale = { f -> ui.zoom = (ui.zoom * f).coerceIn(0.6f / textScale, 2.6f / textScale) },
            onEnd = {
                val z = ui.zoom
                ui.zoom = 1f
                Store.settings { it.copy(textScale = (it.textScale * z).coerceIn(0.6f, 2.6f)) }
            },
        ),
    ) {
        SelectionContainer {
            LazyColumn(
                state = ui.list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = if (book) 22.dp else 18.dp, vertical = 16.dp),
            ) {
                doc.note?.let { note ->
                    item(key = "note") {
                        Text(
                            when (note) { "old" -> ua.x44to.docview.data.t("Старий формат: показано лише текст", "Old format: text only"); else -> note },
                            color = paper.dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
                itemsIndexed(doc.blocks, key = { i, _ -> i }, contentType = { _, b -> b.javaClass.simpleName }) { i, b ->
                    val cur = ui.hits.getOrNull(ui.current)?.takeIf { it.first == i }?.second
                    BlockView(b, doc, docId, paper, base, ui.query, cur, onImage)
                }
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") onTap
}

@Composable
private fun BlockView(b: Block, doc: FlowDoc, docId: String, paper: Paper, base: Float, query: String, cur: Int?, onImage: (String) -> Unit) {
    when (b) {
        is Para -> ParaView(b, doc, paper, base, query, cur)
        is Table -> TableView(b, doc, docId, paper, base, query, onImage)
        is Image -> ImageBlock(b, doc, docId, onImage)
        is Rule -> if (b.pageBreak) {
            Box(Modifier.fillMaxWidth().padding(vertical = 18.dp).height(1.dp).drawBehind {
                drawLine(paper.dim.copy(alpha = 0.5f), Offset(0f, 0f), Offset(size.width, 0f), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            })
        } else HorizontalDivider(Modifier.padding(vertical = 14.dp), color = paper.dim.copy(alpha = 0.35f))
    }
}

@Composable
private fun ParaView(p: Para, doc: FlowDoc, paper: Paper, base: Float, query: String, cur: Int?) {
    if (p.isEmpty && p.bullet == null) { Spacer(Modifier.height((base * 0.5f).dp)); return }
    val text = remember(p, paper, query, cur, doc) { annotate(p, doc, paper, query, cur?.let { it - (p.bullet?.length?.plus(1) ?: 0) }) }
    val book = doc.kind == FlowKind.BOOK
    val top = when {
        p.heading in 1..2 -> base * 1.1f
        p.heading > 0 -> base * 0.8f
        book -> base * 0.25f
        else -> base * 0.45f
    }
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontSize = base.sp, lineHeight = (base * if (book) 1.55f else 1.45f).sp, color = paper.fg,
        fontFamily = if (p.mono) FontFamily.Monospace else null,
    )
    val startPad = (p.indent * 18).dp
    var m = Modifier.fillMaxWidth().padding(top = top.dp, start = startPad)
    if (p.quote) m = m.drawBehind { drawLine(paper.dim.copy(alpha = 0.6f), Offset(-10f, 0f), Offset(-10f, size.height), 5f) }.padding(start = 10.dp)
    if (p.mono) m = m.background(paper.dim.copy(alpha = 0.08f)).padding(horizontal = 6.dp)
    if (p.bullet != null) {
        Row(m) {
            Text(p.bullet, style = style, modifier = Modifier.widthIn(min = 22.dp).padding(end = 6.dp), color = paper.dim)
            Text(text, style = style, modifier = Modifier.weight(1f))
        }
    } else Text(text, style = style, modifier = m)
}

@Composable
private fun TableView(t: Table, doc: FlowDoc, docId: String, paper: Paper, base: Float, query: String, onImage: (String) -> Unit) {
    val cols = t.cols.coerceAtLeast(1)
    // ширина стовпця — за найдовшим текстом у ньому
    val widths: List<Dp> = remember(t, base) {
        val w = FloatArray(cols) { 0f }
        for (r in t.rows) {
            var c = 0
            for (cell in r.cells) {
                if (c >= cols) break
                val len = cell.text.lines().maxOfOrNull { it.length } ?: 0
                if (cell.span == 1) w[c] = max(w[c], len.toFloat())
                c += cell.span
            }
        }
        w.map { (it * base * 0.55f + 20f).coerceIn(56f, 280f).dp }
    }
    val line = paper.dim.copy(alpha = 0.45f)
    Box(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState())) {
        Column(Modifier.border(1.dp, line)) {
            for (r in t.rows) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    var c = 0
                    for (cell in r.cells) {
                        if (c >= cols) break
                        val span = cell.span.coerceAtMost(cols - c)
                        var w = 0.dp
                        for (k in c until c + span) w += widths[k]
                        Column(
                            Modifier.width(w).fillMaxHeight().border(0.5.dp, line)
                                .background(cell.bg?.let { Color(it).copy(alpha = if (paper.dark) 0.35f else 1f) } ?: if (r.header) paper.dim.copy(alpha = 0.1f) else Color.Transparent)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            for (b in cell.blocks) {
                                when (b) {
                                    is Para -> {
                                        val text = remember(b, paper, query) { annotate(b, doc, paper, query, null) }
                                        val st = MaterialTheme.typography.bodyMedium.copy(fontSize = (base * 0.88f).sp, lineHeight = (base * 1.25f).sp, color = paper.fg, fontWeight = if (r.header) FontWeight.SemiBold else null)
                                        if (b.bullet != null) Text(b.bullet + " ", style = st)
                                        Text(text, style = st)
                                    }
                                    is Image -> ImageBlock(b, doc, docId, onImage)
                                    is Table -> TableView(b, doc, docId, paper, base * 0.9f, query, onImage)
                                    is Rule -> {}
                                }
                            }
                        }
                        c += span
                    }
                    // порожні комірки до кінця рядка
                    while (c < cols) {
                        Box(Modifier.width(widths[c]).fillMaxHeight().border(0.5.dp, line))
                        c++
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageBlock(b: Image, doc: FlowDoc, docId: String, onImage: (String) -> Unit) {
    val bmp by produceState<Bitmap?>(null, b.key, docId) {
        value = withContext(Dispatchers.IO) { Bitmaps.get(docId, doc.res, b.key, 1600) }
    }
    // без BoxWithConstraints: картинка може стояти в комірці таблиці, а там рядок міряється «інтринсиками»
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        val img = bmp
        if (img != null) {
            val ratio = img.width.toFloat() / img.height.coerceAtLeast(1)
            val w = b.w?.let { Modifier.widthIn(max = (it * 1.25f).dp) } ?: Modifier
            Image(
                img.asImageBitmap(), b.alt, contentScale = ContentScale.Fit,
                modifier = w.fillMaxWidth().aspectRatio(ratio.coerceIn(0.1f, 10f)).heightIn(max = 900.dp).clickable { onImage(b.key) },
            )
        } else {
            Spacer(Modifier.height(24.dp))
        }
    }
}
