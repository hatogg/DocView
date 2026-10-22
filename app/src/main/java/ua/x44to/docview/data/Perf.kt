package ua.x44to.docview.data

import android.os.SystemClock
import android.util.Log

/**
 * Журнал швидкодії. Мовчить, доки не ввімкнути: `adb shell setprop log.tag.DocView DEBUG`
 * (так робить тест зі скриншотами в CI).
 */
object Perf {
    val on: Boolean by lazy { try { Log.isLoggable("DocView", Log.DEBUG) } catch (_: Throwable) { false } }

    fun log(msg: String) {
        if (on) Log.d("DocView", msg)
    }

    inline fun <T> time(what: String, block: () -> T): T {
        if (!on) return block()
        val t0 = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            log("$what: ${SystemClock.elapsedRealtime() - t0} ms")
        }
    }
}
