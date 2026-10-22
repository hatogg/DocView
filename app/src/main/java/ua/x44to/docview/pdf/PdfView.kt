@file:OptIn(FlowPreview::class)

package ua.x44to.docview.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.x44to.docview.data.Store
import ua.x44to.docview.ui.SearchState
import ua.x44to.docview.ui.Searcher
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

class PdfUi(val engine: PdfEngine, val file: File, start: Int, private val ctx: Context) : SearchState(), Searcher {
    val count = engine.count
    val list = LazyListState(start.coerceIn(0, maxOf(0, count - 1)))
    var zoom by mutableFloatStateOf(1f)
    var tx by mutableFloatStateOf(0f)
    var ty by mutableFloatStateOf(0f)
    var viewW = 1f
    var viewH = 1f
    var sizesVersion by mutableIntStateOf(0)
    val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    var renderW = 0

    class Tile(val left: Float, val top: Float, val w: Float, val h: Float, val bmp: Bitmap)
    var tiles by mutableStateOf<Map<Int, Tile>>(emptyMap())
    var hits by mutableStateOf<List<PdfText.Hit>>(emptyList())
    var toc by mutableStateOf<List<PdfText.TocEntry>?>(null)
    var lastActivity by mutableLongStateOf(0L)
    private var job: Job? = null
    private var scope: CoroutineScope? = null

    fun aspect(i: Int): Float {
        val s = engine.sizes.getOrNull(i) ?: engine.sizes.firstOrNull { it != null } ?: return 0.707f
        return (s[0].toFloat() / s[1].coerceAtLeast(1)).coerceIn(0.2f, 5f)
    }

    // ───────── масштаб і перетягування ─────────

    fun clampT() {
        tx = tx.coerceIn(viewW - viewW * zoom, 0f)
        ty = ty.coerceIn(viewH - viewH * zoom, 0f)
    }

    fun zoomAt(f: Float, cx: Float, cy: Float) {
        val nz = (zoom * f).coerceIn(1f, 6f)
        val lx = (cx - tx) / zoom
        val ly = (cy - ty) / zoom
        zoom = nz
        tx = cx - lx * nz
        ty = cy - ly * nz
        if (nz <= 1.001f) { zoom = 1f; tx = 0f; ty = 0f }
        clampT()
        lastActivity = System.currentTimeMillis()
    }

    /** Перетягування при збільшенні: горизонталь — зсув, вертикаль — спершу список, потім зсув. */
    fun pan(dx: Float, dy: Float) {
        tx = (tx + dx).coerceIn(viewW - viewW * zoom, 0f)
        var rem = dy
        if (rem < 0) {
            val consumed = list.dispatchRawDelta(-rem / zoom)
            rem += consumed * zoom
            ty = (ty + rem).coerceIn(viewH - viewH * zoom, 0f)
        } else if (rem > 0) {
            val nty = (ty + rem).coerceAtMost(0f)
            rem -= (nty - ty)
            ty = nty
            if (rem > 0) list.dispatchRawDelta(-rem / zoom)
        }
        lastActivity = System.currentTimeMillis()
    }

    // ───────── пошук ─────────

    override val total get() = totalS
    override val current get() = currentS
    override val busy get() = busyS
    override val progress get() = progressS

    override fun search(q: String, scope: CoroutineScope) {
        this.scope = scope
        job?.cancel()
        if (q.isBlank()) { clear(); return }
        busyS = true
        progressS = 0f
        hits = emptyList()
        totalS = 0
        val from = list.firstVisibleItemIndex
        job = scope.launch {
            // стан Compose змінюємо лише з головного потоку: фоновий пошук пише прогрес у звичайне поле,
            // а цей цикл переносить його в інтерфейс
            var raw = 0f
            val ticker = launch { while (true) { progressS = raw; delay(150) } }
            val res = withContext(Dispatchers.IO) {
                try {
                    PdfText.search(ctx, file, q, { i, n -> raw = if (n > 0) i.toFloat() / n else 0f }, { !isActive })
                } catch (_: Throwable) {
                    emptyList()
                }
            }
            ticker.cancel()
            progressS = 1f
            hits = res
            totalS = res.size
            busyS = false
            currentS = res.indexOfFirst { it.page >= from }.let { if (it < 0 && res.isNotEmpty()) 0 else it }
            go()
        }
    }

    fun go() {
        val h = hits.getOrNull(currentS) ?: return
        val s = scope ?: return
        s.launch {
            val size = engine.sizes.getOrNull(h.page)
            val r = h.rects.firstOrNull()
            // показуємо збіг на третині висоти екрана
            val off = if (size != null && r != null) ((r.top / size[1]) * (viewW / aspect(h.page)) - viewH / 3 / zoom).roundToInt() else 0
            if (zoom > 1f && r != null && size != null) {
                val px = r.left / size[0] * viewW
                tx = (viewW / 2 - px * zoom).coerceIn(viewW - viewW * zoom, 0f)
            }
            list.scrollToItem(h.page, off.coerceAtLeast(0))
            ty = 0f
        }
    }

    override fun next() { if (hits.isNotEmpty()) { currentS = (currentS + 1) % hits.size; go() } }
    override fun prev() { if (hits.isNotEmpty()) { currentS = (currentS - 1 + hits.size) % hits.size; go() } }
    override fun clear() { job?.cancel(); hits = emptyList(); totalS = 0; currentS = -1; busyS = false }

    /** Чіткі фрагменти видимих сторінок при збільшенні. */
    suspend fun updateTiles() {
        if (zoom <= 1.05f) { if (tiles.isNotEmpty()) tiles = emptyMap(); return }
        val info = list.layoutInfo.visibleItemsInfo
        val z = zoom
        val vx0 = -tx / z
        val vx1 = (viewW - tx) / z
        val vy0 = -ty / z
        val vy1 = (viewH - ty) / z
        val out = HashMap<Int, Tile>()
        for (it in info) {
            val i = it.index
            if (i !in 0 until count) continue
            val y0 = it.offset.toFloat()
            val y1 = y0 + it.size
            val a = maxOf(vy0, y0)
            val b = minOf(vy1, y1)
            if (b <= a) continue
            val itemW = viewW
            val rx0 = vx0.coerceIn(0f, itemW)
            val rx1 = vx1.coerceIn(0f, itemW)
            val ry0 = a - y0
            val ry1 = b - y0
            if (rx1 - rx0 < 2 || ry1 - ry0 < 2) continue
            val size = engine.sizes.getOrNull(i) ?: continue
            val k = size[0] / itemW // пунктів на піксель елемента
            val region = RectF(rx0 * k, ry0 * k, rx1 * k, ry1 * k)
            val scale = z / k
            val bmp = withContext(PdfEngine.dispatcher) { runCatching { engine.tile(i, region, scale) }.getOrNull() } ?: continue
            out[i] = Tile(rx0, ry0, rx1 - rx0, ry1 - ry0, bmp)
        }
        if (zoom == z) tiles = out
    }
}

/**
 * Нічний режим: інверсія яскравості з поворотом відтінку на 180°, тож білий папір стає темним,
 * чорний текст — світлим, а кольори картинок лишаються впізнаваними (синє небо не стає помаранчевим).
 */
private val INVERT = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.574f, -1.430f, -0.144f, 0f, 255f,
            -0.426f, -0.430f, -0.144f, 0f, 255f,
            -0.426f, -1.430f, 0.856f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

@Composable
fun PdfView(ui: PdfUi, docId: String, night: Boolean, onTap: () -> Unit, onGoto: () -> Unit) {
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate } // інтерфейс — лише з головного потоку
    val tap by rememberUpdatedState(onTap)
    LaunchedEffect(ui) {
        // розміри всіх сторінок — у фоні, щоб прокрутка не стрибала
        withContext(Dispatchers.Main.immediate) { withContext(PdfEngine.dispatcher) {
            for (i in 0 until ui.count) {
                if (ui.engine.closed) break
                runCatching { ui.engine.size(i) }
                if (i % 50 == 49) withContext(Dispatchers.Main) { ui.sizesVersion++ }
            }
        }
        ui.sizesVersion++ }
    }
    LaunchedEffect(ui) {
        snapshotFlow { ui.list.firstVisibleItemIndex }.debounce(700).collect { Store.position(docId, it, ui.count) }
    }
    LaunchedEffect(ui) {
        snapshotFlow { listOf(ui.zoom, ui.tx, ui.ty, ui.list.firstVisibleItemIndex.toFloat(), ui.list.firstVisibleItemScrollOffset.toFloat()) }
            .debounce(220).collect { withContext(Dispatchers.Main.immediate) { ui.updateTiles() } }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest).clipToBounds()
            .pointerInput(ui) {
                var lastTap = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val vt = VelocityTracker()
                    vt.addPosition(down.uptimeMillis, down.position)
                    var moved = 0f
                    var pinch = false
                    var panning = false
                    var upTime = down.uptimeMillis
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            pinch = true
                            val c = ev.calculateCentroid()
                            ui.zoomAt(ev.calculateZoom(), c.x, c.y)
                            val p = ev.calculatePan()
                            if (ui.zoom > 1f) ui.pan(p.x, p.y)
                            ev.changes.forEach { it.consume() }
                        } else if (pressed.size == 1) {
                            val ch = pressed[0]
                            val d = ch.positionChange()
                            moved += abs(d.x) + abs(d.y)
                            vt.addPosition(ch.uptimeMillis, ch.position)
                            if (ui.zoom > 1.01f && !pinch && moved > viewConfiguration.touchSlop / 2) {
                                panning = true
                                ui.pan(d.x, d.y)
                                ch.consume()
                            }
                            upTime = ch.uptimeMillis
                        } else {
                            ev.changes.firstOrNull()?.let { upTime = it.uptimeMillis }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (panning) {
                        val v = vt.calculateVelocity()
                        scope.launch {
                            var lx = 0f
                            AnimationState(0f, v.x).animateDecay(exponentialDecay()) { ui.pan(value - lx, 0f); lx = value }
                        }
                        scope.launch {
                            var ly = 0f
                            AnimationState(0f, v.y).animateDecay(exponentialDecay()) { ui.pan(0f, value - ly); ly = value }
                        }
                    } else if (!pinch && moved < viewConfiguration.touchSlop && upTime - down.uptimeMillis < 300) {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 300) {
                            lastTap = 0
                            val target = if (ui.zoom > 1.2f) 1f else 2.5f
                            val c = down.position
                            scope.launch {
                                val a = Animatable(ui.zoom)
                                a.animateTo(target) { ui.zoomAt(value / ui.zoom, c.x, c.y) }
                            }
                        } else {
                            lastTap = now
                            scope.launch { delay(310); if (lastTap == now) tap() }
                        }
                    }
                }
            },
    ) {
        val dens = LocalDensity.current
        ui.viewW = with(dens) { maxWidth.toPx() }
        ui.viewH = with(dens) { maxHeight.toPx() }
        val renderW = ui.viewW.toInt().coerceIn(200, 1600)
        if (ui.renderW != renderW) { ui.renderW = renderW; ui.cache.evictAll() }
        @Suppress("UNUSED_VARIABLE") val v = ui.sizesVersion
        LazyColumn(
            state = ui.list,
            userScrollEnabled = ui.zoom <= 1.01f,
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = ui.zoom; scaleY = ui.zoom; translationX = ui.tx; translationY = ui.ty
                transformOrigin = TransformOrigin(0f, 0f)
            },
        ) {
            items(ui.count, key = { it }) { i -> Page(ui, i, renderW, night) }
        }
        PageBadge(ui, onGoto, Modifier.align(Alignment.BottomCenter))
        if (ui.count > 8) Scrubber(ui, scope, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun Page(ui: PdfUi, i: Int, w: Int, night: Boolean) {
    val bmp by produceState<Bitmap?>(ui.cache.get(i), i, w) {
        if (value == null) {
            delay(30) // при швидкій прокрутці не малюємо сторінки, що проскочили
            value = withContext(PdfEngine.dispatcher) { runCatching { ui.engine.render(i, w) }.getOrNull() }?.also { ui.cache.put(i, it) }
        }
    }
    val filter = if (night) INVERT else null
    Box(Modifier.fillMaxWidth().aspectRatio(ui.aspect(i)).background(if (night) Color.Black else Color.White)) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = filter)
        else CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), strokeWidth = 3.dp, color = Color.Gray.copy(alpha = 0.6f))
        val tile = ui.tiles[i]
        val hits = ui.hits.filter { it.page == i }
        if (tile != null || hits.isNotEmpty()) {
            val cur = ui.hits.getOrNull(ui.current)
            Canvas(Modifier.fillMaxSize()) {
                tile?.let { t ->
                    drawImage(
                        t.bmp.asImageBitmap(), dstOffset = IntOffset(t.left.roundToInt(), t.top.roundToInt()),
                        dstSize = IntSize(t.w.roundToInt(), t.h.roundToInt()), colorFilter = filter,
                    )
                }
                val s = ui.engine.sizes.getOrNull(i)
                if (s != null) {
                    val k = size.width / s[0]
                    for (h in hits) for (r in h.rects) {
                        drawRect(
                            if (h === cur) Color(0x99FF9800) else Color(0x66FFEB3B),
                            Offset(r.left * k, r.top * k), Size(r.width() * k, r.height() * k),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageBadge(ui: PdfUi, onGoto: () -> Unit, modifier: Modifier) {
    val page by remember { derivedStateOf { ui.list.firstVisibleItemIndex + if (ui.list.firstVisibleItemScrollOffset > 0 && ui.list.layoutInfo.visibleItemsInfo.size > 1 && ui.list.firstVisibleItemScrollOffset > (ui.list.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0) / 2) 1 else 0 } }
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(ui.list.isScrollInProgress, ui.lastActivity, page) {
        visible = true
        delay(1800)
        visible = false
    }
    AnimatedVisibility(visible, modifier.navigationBarsPadding().padding(bottom = 16.dp), enter = fadeIn(), exit = fadeOut()) {
        Surface(
            onClick = onGoto, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
        ) {
            Text(
                "${(page + 1).coerceAtMost(ui.count)} / ${ui.count}", color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** Повзунок справа для швидкого переходу в довгих PDF. */
@Composable
private fun Scrubber(ui: PdfUi, scope: CoroutineScope, modifier: Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(ui.list.isScrollInProgress, dragging) {
        if (ui.list.isScrollInProgress || dragging) visible = true
        else { delay(1500); visible = false }
    }
    BoxWithConstraints(modifier.fillMaxHeight().width(36.dp).padding(vertical = 24.dp)) {
        val h = constraints.maxHeight.toFloat()
        val thumb = with(LocalDensity.current) { 44.dp.toPx() }
        val frac = ui.list.firstVisibleItemIndex.toFloat() / (ui.count - 1).coerceAtLeast(1)
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().pointerInput(ui) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { ch, _ ->
                        val f = ((ch.position.y - thumb / 2) / (h - thumb)).coerceIn(0f, 1f)
                        scope.launch { ui.list.scrollToItem((f * (ui.count - 1)).roundToInt()) }
                    }
                },
            ) {
                Box(
                    Modifier.offset { IntOffset(0, ((h - thumb) * frac).roundToInt()) }.align(Alignment.TopEnd).padding(end = 4.dp)
                        .width(8.dp).height(44.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}
