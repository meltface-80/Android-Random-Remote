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
         * Redraws every placed widget. Called by the service when the zone
         * feed moves, which is the only time the contents can have changed.
         */
        fun refresh(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, NowPlayingWidget::class.java)
                )
                if (ids.isEmpty()) return          // none placed: nothing to draw
                val views = build(context)
                manager.updateAppWidget(ids, views)
            }.onFailure { Log.d(TAG, "widget refresh skipped: ${it.message}") }
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

        private fun currentZone(app: MusicdLite?) = runCatching {
            app ?: return@runCatching null
            app.roon.zone(app.settings.lastZone())
                ?: app.roon.zones().firstOrNull { it.isPlaying }
                ?: app.roon.zones().firstOrNull()
        }.getOrNull()

        private fun service(context: Context, action: String): PendingIntent =
            PendingIntent.getService(
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
        runCatching { manager.updateAppWidget(appWidgetIds, build(context)) }
            .onFailure { Log.w(TAG, "could not draw the widget", it) }
    }

    override fun onEnabled(context: Context) {
        // A widget on the home screen is a reason for the app to be running:
        // its buttons are useless without the Roon client behind them.
        runCatching {
            context.startForegroundService(Intent(context, RemoteService::class.java))
        }.onFailure { Log.d(TAG, "could not start the service for the widget: ${it.message}") }
    }
}
