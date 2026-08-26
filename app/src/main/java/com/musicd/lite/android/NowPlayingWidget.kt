package com.musicd.lite.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.RemoteViews
import com.musicd.lite.MusicdLite
import java.util.concurrent.Executors

/**
 * Now playing on the home screen, with transport and one tap for a random
 * album.
 *
 * The widget is deliberately not a live feed. It is redrawn when the zone
 * changes — the service already watches for that — and otherwise sits there
 * costing nothing. A widget that polled would be the poll this release just
 * removed, moved somewhere less visible.
 *
 * All four buttons go through the service, which owns the Roon client. The
 * widget itself holds no state and survives the app being killed: on the next
 * redraw it shows whatever is true then.
 */
class NowPlayingWidget : AppWidgetProvider() {

    companion object {
        private const val TAG = "Widget"

        const val ACTION_RANDOM = "com.musicd.lite.widget.RANDOM"

        /** How big a cover to fetch. Widgets are small and RemoteViews are heavy. */
        private const val ART_PX = 256

        /**
         * Drawing happens here, never on the caller's thread.
         *
         * Filling in the cover can miss both cache tiers and go to the Core,
         * and both callers of this arrive on the main thread — the service's
         * notification refresh and the launcher's onUpdate broadcast. On the
         * main thread that network read throws NetworkOnMainThreadException,
         * which the guard below swallowed, so the widget silently fell back to
         * the app icon every single time. That is why it showed a duck instead
         * of a sleeve.
         */
        private val painter = Executors.newSingleThreadExecutor { r ->
            Thread(r, "widget-draw").apply { isDaemon = true }
        }

        /**
         * Redraws every placed widget. Called by the service when the zone
         * feed moves, which is the only time the contents can have changed.
         */
        fun refresh(context: Context) {
            val app = context.applicationContext
            runCatching {
                painter.execute {
                    runCatching {
                        val manager = AppWidgetManager.getInstance(app)
                        val ids = manager.getAppWidgetIds(
                            ComponentName(app, NowPlayingWidget::class.java)
                        )
                        if (ids.isEmpty()) return@runCatching   // none placed
                        manager.updateAppWidget(ids, build(app))
                    }.onFailure { Log.w(TAG, "widget refresh failed", it) }
                }
            }.onFailure { Log.d(TAG, "widget refresh not queued: ${it.message}") }
        }

        private fun build(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_now_playing)
            val app = RemoteService.instance?.app
            val zone = currentZone(app)
            val np = zone?.nowPlaying

            views.setTextViewText(R.id.widget_title, np?.line1 ?: "Nothing playing")
            views.setTextViewText(
                R.id.widget_subtitle,
                listOfNotNull(
                    np?.line2?.takeIf { it.isNotBlank() },
                    zone?.displayName?.takeIf { it.isNotBlank() }
                ).joinToString(" · ").ifEmpty { "Tap the duck for a random album" }
            )

            // Art, from the app's own cache so this costs the Core nothing the
            // UI has not already fetched.
            val art = runCatching {
                if (app == null) return@runCatching null
                val key = np?.imageKey ?: return@runCatching null
                val url = app.roon.imageUrl(key, ART_PX, ART_PX) ?: return@runCatching null
                app.art.get(url, "$key|$ART_PX|$ART_PX|fit")?.bytes
                    ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }.getOrNull()
            if (art != null) {
                views.setImageViewBitmap(R.id.widget_art, art)
            } else {
                views.setImageViewResource(R.id.widget_art, R.mipmap.ic_launcher_foreground)
            }

            // What pressing it will do, not what it did last. This was simply
            // never written: the layout ships a play triangle and nothing ever
            // changed it, so the widget claimed to be paused the whole time it
            // was playing.
            val playing = zone?.state == "playing" || zone?.state == "loading"
            views.setImageViewResource(
                R.id.widget_playpause,
                if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            views.setContentDescription(
                R.id.widget_playpause, if (playing) "Pause" else "Play"
            )

            views.setOnClickPendingIntent(R.id.widget_art, service(context, ACTION_RANDOM))

            views.setOnClickPendingIntent(
                R.id.widget_previous, service(context, RemoteService.ACTION_PREVIOUS)
            )
            views.setOnClickPendingIntent(
                R.id.widget_playpause, service(context, RemoteService.ACTION_PLAY_PAUSE)
            )
            views.setOnClickPendingIntent(
                R.id.widget_next, service(context, RemoteService.ACTION_NEXT)
            )
            // The text opens the app, so the widget is also a launcher.
            views.setOnClickPendingIntent(
                R.id.widget_text,
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            return views
        }

        private fun currentZone(app: MusicdLite?) = app?.activeZone()

        /**
         * A foreground-service start, not a plain one.
         *
         * If the service has been killed, plain startService from a widget tap
         * is refused outright on Android 12 and up, because the app counts as
         * background. Tapping a widget is one of the actions that earns a
         * short exemption for a foreground start, so this way the buttons still
         * work on a cold app — the service comes back, re-pairs, and the tap
         * after that lands.
         */
        private fun service(context: Context, action: String): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                action.hashCode(),
                Intent(context, RemoteService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Also the first draw after the widget is placed, when the service may
        // not be running yet — build() handles a null app by saying so.
        // Off this thread too: onUpdate is a broadcast on the main thread, and
        // build() may fetch a cover.
        val app = context.applicationContext
        runCatching {
            painter.execute {
                runCatching { manager.updateAppWidget(appWidgetIds, build(app)) }
                    .onFailure { Log.w(TAG, "could not draw the widget", it) }
            }
        }
    }

    override fun onEnabled(context: Context) {
        // A widget on the home screen is a reason for the app to be running:
        // its buttons are useless without the Roon client behind them.
        runCatching {
            context.startForegroundService(Intent(context, RemoteService::class.java))
        }.onFailure { Log.d(TAG, "could not start the service for the widget: ${it.message}") }
    }
}
