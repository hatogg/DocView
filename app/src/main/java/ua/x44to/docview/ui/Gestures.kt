package ua.x44to.docview.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope

/** Пошук усередині документа: спільний інтерфейс для PDF, тексту, таблиць і слайдів. */
interface Searcher {
    val total: Int
    val current: Int
    val busy: Boolean
    val progress: Float
    fun search(q: String, scope: CoroutineScope)
    fun next()
    fun prev()
    fun clear()
}

/** Простий стан пошуку для реалізацій. */
open class SearchState {
    var totalS by mutableIntStateOf(0)
    var currentS by mutableIntStateOf(-1)
    var progressS by mutableFloatStateOf(0f)
    var busyS by androidx.compose.runtime.mutableStateOf(false)
}

/**
 * Масштаб двома пальцями, що не заважає прокрутці одним пальцем: перехоплюємо подію лише коли пальців два.
 * [onScale] отримує множник для кожного кроку жесту, [onEnd] — коли пальці відпущено.
 */
fun Modifier.pinch(onScale: (Float) -> Unit, onEnd: () -> Unit = {}): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zooming = false
        do {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = ev.changes.count { it.pressed }
            if (pressed >= 2) {
                zooming = true
                val z = ev.calculateZoom()
                if (z != 1f) onScale(z)
                ev.changes.forEach { if (it.pressed) it.consume() }
            }
        } while (ev.changes.any { it.pressed })
        if (zooming) onEnd()
    }
}
