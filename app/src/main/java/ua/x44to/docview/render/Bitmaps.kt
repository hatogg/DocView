package ua.x44to.docview.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import ua.x44to.docview.core.Resources

/** Декодування картинок документа зі зменшенням і кеш у пам'яті. */
object Bitmaps {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(16 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<String>()

    /** [docId] відокремлює картинки різних документів з однаковими іменами («media/image1.png»). */
    fun get(docId: String, res: Resources, key: String, maxSide: Int = 1600): Bitmap? {
        val ck = "$docId|$key|$maxSide"
        cache.get(ck)?.let { return it }
        synchronized(missing) { if (ck in missing) return null }
        val bytes = try { res.bytes(key) } catch (_: Throwable) { null }
        val bmp = bytes?.let { decode(it, maxSide) }
        if (bmp == null) synchronized(missing) { missing.add(ck) } else cache.put(ck, bmp)
        return bmp
    }

    fun decode(bytes: ByteArray, maxSide: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null
        else {
            var s = 1
            while (o.outWidth / (s * 2) >= maxSide || o.outHeight / (s * 2) >= maxSide) s *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        }
    } catch (_: Throwable) {
        null
    }

    /** Розміри без декодування (для резервування місця). */
    fun size(docId: String, res: Resources, key: String): Pair<Int, Int>? {
        get(docId, res, key, 400)?.let { return it.width to it.height }
        return null
    }

    fun clear() {
        cache.evictAll()
        synchronized(missing) { missing.clear() }
    }
}
