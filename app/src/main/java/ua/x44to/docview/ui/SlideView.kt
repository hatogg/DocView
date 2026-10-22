@file:OptIn(FlowPreview::class)

package ua.x44to.docview.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.x44to.docview.core.SlideDoc
import ua.x44to.docview.data.Store
import ua.x44to.docview.data.t
import ua.x44to.docview.render.SlideRender

class SlideUi(val doc: SlideDoc, val docId: String, start: Int) : SearchState(), Searcher {
    val list = LazyListState(start.coerceIn(0, maxOf(0, doc.slides.size - 1)))
    var show by mutableStateOf<Int?>(null)
    private var hits = emptyList<Int>()
    private var scope: CoroutineScope? = null
    val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun render(i: Int, w: Int): Bitmap {
        val key = "$i/$w"
        cache.get(key)?.let { return it }
        return SlideRender.bitmap(doc, doc.slides[i], docId, w).also { cache.put(key, it) }
    }

    override val total get() = totalS
    override val current get() = currentS
    override val busy get() = busyS
    override val progress get() = progressS

    override fun search(q: String, scope: CoroutineScope) {
        this.scope = scope
        val n = q.trim().lowercase()
        if (n.isEmpty()) { clear(); return }
        hits = doc.slides.withIndex().filter { (_, s) -> s.text.lowercase().contains(n) || s.notes?.lowercase()?.contains(n) == true }.map { it.index }
        totalS = hits.size
        currentS = if (hits.isEmpty()) -1 else 0
        go()
    }

    private fun go() {
        val i = hits.getOrNull(currentS) ?: return
        scope?.launch { list.animateScrollToItem(i) }
    }

    override fun next() { if (hits.isNotEmpty()) { currentS = (currentS + 1) % hits.size; go() } }
    override fun prev() { if (hits.isNotEmpty()) { currentS = (currentS - 1 + hits.size) % hits.size; go() } }
    override fun clear() { hits = emptyList(); totalS = 0; currentS = -1 }
}

@Composable
fun SlideView(ui: SlideUi) {
    val doc = ui.doc
    LaunchedEffect(ui) {
        snapshotFlow { ui.list.firstVisibleItemIndex }.debounce(800).collect { Store.position(ui.docId, it, doc.slides.size) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        val wPx = with(LocalDensity.current) { (maxWidth - 32.dp).toPx() }.toInt().coerceIn(200, 1600)
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            state = ui.list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + navBottom),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(doc.slides.size) { i ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SlideImage(ui, i, wPx, Modifier.fillMaxWidth().shadow(3.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)).clickable { ui.show = i })
                    doc.slides[i].notes?.let { n ->
                        Card(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) { Text(n, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp), maxLines = 8) }
                    }
                }
            }
        }
    }
    ui.show?.let { start -> Slideshow(ui, start) { ui.show = null } }
}

@Composable
private fun SlideImage(ui: SlideUi, i: Int, w: Int, modifier: Modifier) {
    val ratio = ui.doc.w / ui.doc.h
    val bmp by produceState<Bitmap?>(ui.cache.get("$i/$w"), i, w) {
        if (value == null) value = withContext(Dispatchers.Default) { runCatching { ui.render(i, w) }.getOrNull() }
    }
    Box(modifier.aspectRatio(ratio.coerceIn(0.3f, 4f)).background(Color.White)) {
        bmp?.let { Image(it.asImageBitmap(), t("Слайд ${i + 1}", "Slide ${i + 1}"), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

/** Показ на весь екран: гортання пальцем, масштаб. */
@Composable
private fun Slideshow(ui: SlideUi, start: Int, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val pager = rememberPagerState(start) { ui.doc.slides.size }
        var bars by androidx.compose.runtime.remember { mutableStateOf(true) }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val wPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceIn(200, 2400)
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { i ->
                val bmp by produceState<Bitmap?>(ui.cache.get("$i/$wPx"), i, wPx) {
                    if (value == null) value = withContext(Dispatchers.Default) { runCatching { ui.render(i, wPx) }.getOrNull() }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    bmp?.let { ZoomImage(it, onTap = { bars = !bars }) }
                }
            }
            if (bars) {
                Row(Modifier.fillMaxWidth().systemBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, t("Закрити", "Close"), tint = Color.White) }
                    Text("${pager.currentPage + 1} / ${ui.doc.slides.size}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                ui.doc.slides.getOrNull(pager.currentPage)?.notes?.let { n ->
                    Text(
                        n, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, maxLines = 4,
                        modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(16.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp),
                    )
                }
            }
        }
    }
}
