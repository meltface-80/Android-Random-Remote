package com.musicd.lite.android

import android.content.Context
import android.util.Log
import com.musicd.lite.api.StaticAssets
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The bundled MusicD front-end, read out of the APK.
 *
 * Files are cached in memory after the first read. The whole bundle is about a
 * megabyte and it cannot change while the process is alive, so re-opening an
 * asset per request would be work with no possible benefit.
 */
class AndroidAssets(context: Context, private val root: String = "web") : StaticAssets {

    private companion object {
        const val TAG = "Assets"
    }

    private val assets = context.applicationContext.assets
    private val cache = ConcurrentHashMap<String, Pair<ByteArray, String>>()

    override fun read(path: String): Pair<ByteArray, String>? {
        val clean = normalise(path) ?: return null
        cache[clean]?.let { return it }
        return try {
            val bytes = assets.open("$root/$clean").use { it.readBytes() }
            val hit = bytes to contentType(clean)
            cache[clean] = hit
            hit
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "reading $clean failed", e)
            null
        }
    }

    /**
     * Turn a request path into an asset path, or null if it tries to leave the
     * bundle. The server is loopback-only and the page is ours, but a path that
     * can climb out of the asset root is not something to leave lying around.
     */
    private fun normalise(path: String): String? {
        val trimmed = path.trimStart('/')
        if (trimmed.isEmpty() || trimmed.contains("..")) return null
        return trimmed
    }

    private fun contentType(path: String): String = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "webp" -> "image/webp"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        else -> "application/octet-stream"
    }
}
