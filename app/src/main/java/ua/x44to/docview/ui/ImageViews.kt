package ua.x44to.docview.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.x44to.docview.render.Bitmaps
import java.io.File
import kotlin.math.abs

/**
 * Картинка з масштабом: два пальці, подвійний дотик, перетягування. Коли масштаб 1 — свайп одним пальцем
 * не перехоплюється (щоб гортати сторінки в пейджері).
 */
@Composable
fun ZoomImage(bmp: Bitmap, modifier: Modifier = Modifier, onTap: () -> Unit = {}) {
    var scale by remember(bmp) { mutableFloatStateOf(1f) }
    var ox by remember(bmp) { mutableFloatStateOf(0f) }
    var oy by remember(bmp) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        fun clamp() {
            val mx = (w * scale - w) / 2
            val my = (h * scale - h) / 2
            ox = ox.coerceIn(-mx, mx)
            oy = oy.coerceIn(-my, my)
        }
        fun zoomAt(f: Float, c: Offset) {
            val ns = (scale * f).coerceIn(1f, 6f)
            val k = ns / scale
            // точка під пальцями лишається на місці
            ox = (ox - (c.x - w / 2)) * k + (c.x - w / 2)
            oy = (oy - (c.y - h / 2)) * k + (c.y - h / 2)
            scale = ns
            if (scale <= 1.001f) { ox = 0f; oy = 0f }
            clamp()
        }
        var lastTap = 0L
        Box(
            Modifier.fillMaxSize().pointerInput(bmp) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var moved = 0f
                    var multi = false
                    val t0 = down.uptimeMillis
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            multi = true
                            zoomAt(ev.calculateZoom(), ev.calculateCentroid())
                            val p = ev.calculatePan()
                            ox += p.x; oy += p.y; clamp()
                            ev.changes.forEach { it.consume() }
                        } else if (pressed.size == 1) {
                            val d = pressed[0].positionChange()
                            moved += abs(d.x) + abs(d.y)
                            if (scale > 1.01f && !multi) {
                                ox += d.x; oy += d.y; clamp()
                                pressed[0].consume()
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                    val dt = (currentEvent.changes.firstOrNull()?.uptimeMillis ?: t0) - t0
                    if (!multi && moved < 24f && dt < 300) {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 320) {
                            lastTap = 0
                            val target = if (scale > 1.2f) 1f else 2.5f
                            val c = down.position
                            scope.launch {
                                val a = Animatable(scale)
                                a.animateTo(target) { zoomAt(value / scale, c) }
                            }
                        } else {
                            lastTap = now
                            scope.launch {
                                kotlinx.coroutines.delay(330)
                                if (lastTap == now) onTap()
                            }
                        }
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bmp.asImageBitmap(), null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = ox; translationY = oy
                    transformOrigin = TransformOrigin.Center
                },
            )
        }
    }
}

/** Окремий файл-картинка. */
@Composable
fun ImageFileView(file: File, onTap: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { Bitmaps.decode(file.readBytes(), 4096) }.getOrNull() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b == null) CircularProgressIndicator() else ZoomImage(b, onTap = onTap)
    }
}
