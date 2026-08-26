package com.musicd.lite.android

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast

/**
 * A random album from the notification shade.
 *
 * Pull down, tap, and a record starts in the zone you were last looking at.
 * The app's most-used action, without the app: no launcher, no cold start, no
 * WebView. That is the point of it — the fastest path to the thing people
 * actually open this for.
 *
 * A tile cannot exist for a web page, which is why this was not possible before
 * the port.
 */
class RandomAlbumTile : TileService() {

    private companion object {
        const val TAG = "RandomTile"
    }

    override fun onStartListening() {
        super.onStartListening()
        runCatching {
            val running = RemoteService.instance?.app
            qsTile?.apply {
                // Unavailable is honest and useful: the tile greys out rather
                // than accepting a tap it cannot act on.
                state = if (running?.roon?.isPaired == true) Tile.STATE_INACTIVE
                else Tile.STATE_UNAVAILABLE
                subtitleIfSupported(
                    when {
                        running == null -> "Not running"
                        running.roon.isPaired ->
                            running.activeZone()?.displayName ?: "Random album"
                        else -> "Not paired"
                    }
                )
                updateTile()
            }
        }.onFailure { Log.w(TAG, "could not refresh the tile", it) }
    }

    override fun onClick() {
        super.onClick()
        val app = RemoteService.instance?.app
        if (app == null) {
            // Nothing is running, so there is nothing to play into. Opening the
            // app starts the service, which is the only useful thing to do.
            openApp()
            return
        }

        // Off the main thread: this walks Roon's browse tree, which is several
        // round trips over the network.
        Thread({
            val result = runCatching { app.playRandomAlbum() }.getOrElse { Result.failure(it) }
            val message = result.fold(
                onSuccess = { "${it.title} — ${it.subtitle}" },
                onFailure = { it.message ?: "Could not start an album" }
            )
            runCatching {
                android.os.Handler(mainLooper).post {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
        }, "tile-play").apply { isDaemon = true }.start()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure { Log.w(TAG, "could not open the app from the tile", it) }
    }

    /** Subtitles are API 29+; below that the tile is just its label. */
    private fun Tile.subtitleIfSupported(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = text
    }
}
