package com.musicd.lite.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import com.musicd.lite.MusicdLite
import com.musicd.lite.android.dial.DialActivity
import com.musicd.lite.android.dial.WidgetArtwork
import com.musicd.lite.android.dial.WidgetDial
import com.musicd.lite.android.dial.WidgetGeometry
import com.musicd.lite.android.dial.WidgetSnapshot
import com.musicd.lite.roon.Zone
import java.util.concurrent.Executors

/**
 * The dial, on the home screen.
 *
 * The picture is the app's real [com.musicd.lite.android.dial.DialView] drawn
 * to a bitmap, not a lookalike, so the widget and the dial cannot drift apart.
 * Rendering, geometry and the snapshot are synced from Dial for Roon; this
 * file is the part that is ours, joining them to this app's service.
 *
 * A widget is RemoteViews, so there are no custom views and no gestures. The
 * ring reports the level and its two sides raise and lower it instead — a drag
 * on the home screen belongs to the launcher, and reaching for a rotary
 * gesture there gets you the notification shade.
 */
class DialWidget : AppWidgetProvider() {

    companion object {
        private const val TAG = "DialWidget"

        const val ACTION_VOLUME_UP = "com.musicd.lite.dial.VOLUME_UP"
        const val ACTION_VOLUME_DOWN = "com.musicd.lite.dial.VOLUME_DOWN"

        /**
         * Drawing means laying out a view and compressing a 512px image, so a
         * volume sweep in the dial — which changes the ring every frame — must
         * not become a render per frame out here.
         */
        private const val MIN_RENDER_INTERVAL_MS = 500L

        /** One press moves about a sixty-fourth of the output's range. */
        private const val VOLUME_PRESS_FRACTION = 1.0 / 64.0

        private val artLoader = Executors.newSingleThreadExecutor { r ->
            Thread(r, "dial-widget-art").apply { isDaemon = true }
        }

        @Volatile private var lastRenderAt = 0L
        @Volatile private var lastPublished: WidgetSnapshot? = null
        @Volatile private var lastZone: Zone? = null
        private var trailingRender: Runnable? = null

        private fun widgetIds(context: Context): IntArray = runCatching {
            AppWidgetManager.getInstance(context).getAppWidgetIds(
                ComponentName(context, DialWidget::class.java)
            )
        }.getOrDefault(IntArray(0))

        /**
         * Redraws every placed dial. Called from the service's zone feed, so it
         * drops out early when nothing a widget shows has changed — Roon sends
         * a seek update every second and not one of them matters here.
         */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val zone = RemoteService.instance?.app?.activeZone()
            val snapshot = WidgetSnapshot.of(zone)
            lastZone = zone
            if (snapshot == lastPublished) return
            lastPublished = snapshot
            runCatching { snapshot.save(app) }

            val main = Handler(Looper.getMainLooper())
            trailingRender?.let { main.removeCallbacks(it) }

            val now = SystemClock.uptimeMillis()
            val since = now - lastRenderAt
            if (since >= MIN_RENDER_INTERVAL_MS) {
                lastRenderAt = now
                main.post { render(app, zone, snapshot) }
                return
            }
            // Mid-sweep: let it settle, then draw where it landed.
            val trailing = Runnable {
                lastRenderAt = SystemClock.uptimeMillis()
                val latest = lastZone
                render(app, latest, WidgetSnapshot.of(latest))
            }
            trailingRender = trailing
            main.postDelayed(trailing, MIN_RENDER_INTERVAL_MS - since)
        }

        /** Must run on the main thread: WidgetDial lays out a real view. */
        private fun render(
            context: Context,
            zone: Zone?,
            snapshot: WidgetSnapshot = WidgetSnapshot.load(context)
        ) {
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            val cover = WidgetArtwork.cached(context, snapshot.imageKey)
            val dial = WidgetDial.render(context, zone, statusFor(snapshot), cover)
            if (dial != null) WidgetDial.cache(context, dial)
            val manager = AppWidgetManager.getInstance(context)
            // Per widget, because each may be a different size and the tap
            // targets are placed from that size.
            for (id in ids) {
                runCatching {
                    manager.updateAppWidget(id, buildViews(context, dial, geometryOf(context, id)))
                }.onFailure { Log.w(TAG, "could not update widget $id", it) }
            }
            ensureArtwork(context, snapshot)
        }

        private fun statusFor(snapshot: WidgetSnapshot): String =
            if (snapshot.hasZone) "" else "Open MusicD Remote Lite to connect"

        /**
         * Fetches cover art off the main thread, then redraws once it lands.
         *
         * The URL comes from this app's Roon client, and the bytes come from
         * its own image cache when it has them, so a cover the UI has already
         * looked at costs the Core nothing here.
         */
        private fun ensureArtwork(context: Context, snapshot: WidgetSnapshot) {
            val key = snapshot.imageKey
            if (key.isEmpty()) return
            if (WidgetArtwork.cached(context, key) != null) return
            val lite = RemoteService.instance?.app ?: return
            val url = lite.roon.imageUrl(key, WidgetArtwork.SIZE, WidgetArtwork.SIZE) ?: return
            artLoader.execute {
                val cover = WidgetArtwork.fetch(context, url, key) ?: return@execute
                Handler(Looper.getMainLooper()).post {
                    val ids = widgetIds(context)
                    if (ids.isEmpty()) return@post
                    val dial = WidgetDial.render(context, lastZone, statusFor(snapshot), cover)
                        ?: return@post
                    WidgetDial.cache(context, dial)
                    val manager = AppWidgetManager.getInstance(context)
                    for (id in ids) {
                        runCatching {
                            manager.updateAppWidget(
                                id, buildViews(context, dial, geometryOf(context, id))
                            )
                        }
                    }
                }
            }
        }

        /**
         * The widget's real size, so the targets can be laid over the controls
         * that were actually drawn. Options are reported in dp and are
         * approximate, which is fine: the targets are far larger than the error.
         */
        private fun geometryOf(context: Context, appWidgetId: Int): WidgetGeometry? = runCatching {
            val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
            val density = context.resources.displayMetrics.density
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            if (widthDp <= 0 || heightDp <= 0) null
            else WidgetGeometry(
                widthPx = (widthDp * density).toInt(),
                heightPx = (heightDp * density).toInt(),
                density = density
            )
        }.getOrNull()

        private fun buildViews(
            context: Context,
            dial: ByteArray?,
            geometry: WidgetGeometry?
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_dial)

            val image = dial ?: WidgetDial.cached(context)
            if (image != null) views.setImageViewIcon(R.id.widget_dial, WidgetDial.icon(image))

            // Inset the controls row onto the drawn controls. Without a known
            // size the row stays full width, which is wrong but reachable.
            if (geometry != null && geometry.isUsable()) {
                val padding = geometry.controlsPadding()
                views.setViewPadding(
                    R.id.widget_controls,
                    padding.left, padding.top, padding.right, padding.bottom
                )
            }

            // Transport reuses the service actions the bar widget and the
            // notification already send, so there is one path into Roon.
            views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                NowPlayingWidget.serviceIntent(context, RemoteService.ACTION_PLAY_PAUSE)
            )
            views.setOnClickPendingIntent(
                R.id.widget_next,
                NowPlayingWidget.serviceIntent(context, RemoteService.ACTION_NEXT)
            )
            views.setOnClickPendingIntent(
                R.id.widget_previous,
                NowPlayingWidget.serviceIntent(context, RemoteService.ACTION_PREVIOUS)
            )
            views.setOnClickPendingIntent(R.id.widget_volume_up, broadcast(context, ACTION_VOLUME_UP))
            views.setOnClickPendingIntent(R.id.widget_volume_down, broadcast(context, ACTION_VOLUME_DOWN))
            // A widget cannot record audio, so its microphone opens the dial
            // already listening rather than pretending to hear from here.
            views.setOnClickPendingIntent(R.id.widget_voice, openDial(context, listening = true))
            views.setOnClickPendingIntent(R.id.widget_open, openDial(context))
            return views
        }

        private fun broadcast(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, DialWidget::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun openDial(context: Context, listening: Boolean = false): PendingIntent =
            PendingIntent.getActivity(
                context,
                if (listening) 1 else 0,
                Intent(context, DialActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(DialActivity.EXTRA_START_VOICE, listening),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        /**
         * One press moves a sixty-fourth of the output's range, which lands
         * near 1 dB on a typical DAC. A single step would be right for a volume
         * rocker but means 160 presses end to end on a 0.5 dB output.
         */
        private fun stepVolume(lite: MusicdLite, up: Boolean) {
            val zone = lite.activeZone() ?: return
            for (out in zone.volumeOutputs) {
                val vol = out.volume ?: continue
                val direction = if (up) 1 else -1
                if (vol.isIncremental) {
                    runCatching { lite.roon.changeVolume(out.outputId, "relative", direction.toDouble()) }
                    continue
                }
                val span = vol.effectiveMax - vol.min
                if (span <= 0.0) continue
                val steps = ((span * VOLUME_PRESS_FRACTION) / vol.step)
                    .toInt().coerceAtLeast(1) * direction
                runCatching {
                    lite.roon.changeVolume(out.outputId, "relative_step", steps.toDouble())
                }.onFailure { Log.w(TAG, "volume press failed", it) }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val up = when (intent.action) {
            ACTION_VOLUME_UP -> true
            ACTION_VOLUME_DOWN -> false
            else -> return
        }
        // The press is usually made with the app asleep, so wake it first; the
        // service is what owns the Roon connection.
        val pending = goAsync()
        val app = context.applicationContext
        runCatching { app.startForegroundService(Intent(app, RemoteService::class.java)) }
        Thread({
            try {
                // Give the extension a moment to register if it was not up.
                val deadline = SystemClock.uptimeMillis() + 6_000
                var lite = RemoteService.instance?.app
                while (lite?.activeZone() == null && SystemClock.uptimeMillis() < deadline) {
                    Thread.sleep(150)
                    lite = RemoteService.instance?.app
                }
                lite?.let { stepVolume(it, up) }
            } catch (e: Exception) {
                Log.w(TAG, "volume press failed", e)
            } finally {
                pending.finish()
            }
        }, "dial-widget-volume").apply { isDaemon = true }.start()
    }

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            runCatching { render(app, lastZone) }
                .onFailure { Log.w(TAG, "could not draw the dial widget", it) }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        // Resized: the targets are placed from the size, so they must move too.
        Handler(Looper.getMainLooper()).post {
            runCatching {
                manager.updateAppWidget(
                    appWidgetId,
                    buildViews(context, WidgetDial.cached(context), geometryOf(context, appWidgetId))
                )
            }
        }
    }

    override fun onEnabled(context: Context) {
        runCatching {
            context.startForegroundService(Intent(context, RemoteService::class.java))
        }.onFailure { Log.d(TAG, "could not start the service: ${it.message}") }
    }
}
