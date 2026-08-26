package com.musicd.lite.android

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.musicd.lite.MusicdLite

/**
 * Asks the system to offer the Quick Settings tile.
 *
 * A declared tile does appear in the shade's tile editor, but only after
 * opening it and scrolling past every tile the phone ships with, which is a
 * poor answer to "where is it?". Android 13 added a way to ask directly: the
 * system puts up its own prompt, the user says yes, and the tile is placed.
 * The app cannot add the tile itself and this does not let it — the prompt is
 * the system's and the answer is the user's.
 *
 * Below Android 13 there is no such call, so [installer] reports itself
 * unsupported and the button says so rather than failing silently.
 */
object TileRequest {

    private const val TAG = "TileRequest"

    fun installer(context: Context): MusicdLite.TileInstaller {
        val app = context.applicationContext
        return MusicdLite.TileInstaller(
            supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            request = { request(app) }
        )
    }

    private fun request(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val bars = context.getSystemService(StatusBarManager::class.java)
            ?: throw IllegalStateException("No status bar service on this device")
        bars.requestAddTileService(
            ComponentName(context, RandomAlbumTile::class.java),
            context.getString(R.string.tile_random_album),
            Icon.createWithResource(context, R.drawable.ic_tile_random),
            { r -> r.run() }
        ) { result ->
            // The system's own dialog has already told the user what happened;
            // this only covers the outcomes it does not show, and says nothing
            // when the tile is added or the user declines.
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                    "Random album is already in Quick Settings"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> null
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> null
                else -> "Could not add the tile"
            }
            if (message != null) {
                runCatching {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }.onFailure { Log.d(TAG, "no toast: ${it.message}") }
            }
        }
    }
}
