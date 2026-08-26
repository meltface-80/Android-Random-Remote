package com.musicd.lite.android.dial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cover art for the widget's dial: fetched from the Core, scaled down, and kept
 * in memory and on disk so a widget whose process has died still has something
 * to draw.
 */
object WidgetArtwork {

    private const val TAG = "WidgetArtwork"
    const val SIZE = 384

    private const val CACHE_FILE = "widget-art.jpg"

    private var cachedKey: String? = null
    private var cachedCover: Bitmap? = null

    /**
     * Cover art for [imageKey], from memory, then disk, then the Core. Returns
     * null rather than blocking on a fetch; the caller re-renders when
     * [fetch] has something.
     */
    @Synchronized
    fun cached(context: Context, imageKey: String): Bitmap? {
        if (imageKey.isEmpty()) return null
        if (imageKey == cachedKey) return cachedCover
        val file = File(context.cacheDir, CACHE_FILE)
        if (file.exists() && context.readCachedKey() == imageKey) {
            val decoded = BitmapFactory.decodeFile(file.absolutePath)
            if (decoded != null) {
                cachedKey = imageKey
                cachedCover = decoded
                return decoded
            }
        }
        return null
    }

    /** Blocking fetch; call from a background thread. */
    fun fetch(context: Context, url: String, imageKey: String): Bitmap? {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
            }
            val decoded = connection.inputStream.use { BitmapFactory.decodeStream(it) } ?: return null
            val scaled = downscale(decoded)
            synchronized(this) {
                cachedKey = imageKey
                cachedCover = scaled
            }
            File(context.cacheDir, CACHE_FILE).outputStream().use {
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, it)
            }
            context.writeCachedKey(imageKey)
            scaled
        } catch (e: Exception) {
            Log.w(TAG, "artwork fetch failed: ${e.message}")
            null
        }
    }

    private fun downscale(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= SIZE) return source
        val scale = SIZE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        ).also { if (it !== source) source.recycle() }
    }

    private fun Context.readCachedKey(): String? =
        getSharedPreferences("roon_widget", Context.MODE_PRIVATE).getString("cached_art_key", null)

    private fun Context.writeCachedKey(key: String) {
        getSharedPreferences("roon_widget", Context.MODE_PRIVATE).edit()
            .putString("cached_art_key", key).apply()
    }

    @Synchronized
    fun clearMemoryCache() {
        cachedKey = null
        cachedCover = null
    }
}
