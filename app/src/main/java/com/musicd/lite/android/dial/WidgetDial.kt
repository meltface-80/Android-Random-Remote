package com.musicd.lite.android.dial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.view.View
import com.musicd.lite.roon.Zone
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Renders the app's dial for the widget.
 *
 * This draws the real [DialView] rather than a lookalike, so the widget and the
 * app cannot drift apart: one change to the dial changes both.
 *
 * The result travels to the launcher as compressed image data rather than as a
 * bitmap. Every bitmap in a RemoteViews is parcelled raw across the 1 MB Binder
 * buffer shared by the whole process — a 512px square would be a megabyte on
 * its own — while the same image compressed is a few tens of kilobytes.
 */
object WidgetDial {

    private const val TAG = "WidgetDial"

    /** Rendered square. Scaled up by the launcher on larger widgets. */
    const val SIZE = 512

    private const val QUALITY = 84
    private const val CACHE_FILE = "widget-dial.webp"

    /**
     * Draws [zone] as the dial. Must run on the main thread: it lays out a
     * real view.
     */
    fun render(context: Context, zone: Zone?, status: String, cover: Bitmap?): ByteArray? {
        return try {
            val view = DialView(context).apply {
                widgetMode = true
                setStatus(status)
                setZone(zone)
                setArtwork(cover)
            }
            val spec = View.MeasureSpec.makeMeasureSpec(SIZE, View.MeasureSpec.EXACTLY)
            view.measure(spec, spec)
            view.layout(0, 0, SIZE, SIZE)

            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))

            val out = ByteArrayOutputStream()
            bitmap.compress(compressFormat(), QUALITY, out)
            bitmap.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "dial render failed: ${e.message}")
            null
        }
    }

    fun icon(data: ByteArray): Icon = Icon.createWithData(data, 0, data.size)

    /** Keeps the last render so a widget whose process has died still shows it. */
    fun cache(context: Context, data: ByteArray) {
        try {
            File(context.cacheDir, CACHE_FILE).writeBytes(data)
        } catch (e: Exception) {
            Log.w(TAG, "could not cache dial: ${e.message}")
        }
    }

    fun cached(context: Context): ByteArray? = try {
        File(context.cacheDir, CACHE_FILE).takeIf { it.exists() }?.readBytes()
    } catch (e: Exception) {
        null
    }

    private fun compressFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
}
