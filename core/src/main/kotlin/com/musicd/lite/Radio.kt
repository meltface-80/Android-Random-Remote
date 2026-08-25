package com.musicd.lite

import com.musicd.lite.library.AlbumRecord
import com.musicd.lite.library.Albums
import com.musicd.lite.roon.Zone
import java.util.concurrent.ConcurrentHashMap

/**
 * Random Album Radio: when a zone's queue runs out, put another album on.
 *
 * The whole feature is one decision — "has this zone stopped, and did I put the
 * last thing on it?" — and the reason it is not simply "state == stopped" is
 * that Roon reports a stopped zone at several moments that are not the end of a
 * queue: while a track loads, between albums, and immediately after the user
 * pressed stop themselves. Queueing an album into any of those takes the
 * decision away from the person holding the phone.
 */
class Radio(private val app: MusicdLite) {

    private companion object {
        const val TAG = "Radio"

        /**
         * A zone must be seen stopped twice, this far apart, before radio acts.
         * One reading is a transition; two is a state.
         */
        const val SETTLE_MS = 4_000L

        /** Never start two albums in quick succession on the same zone. */
        const val COOLDOWN_MS = 30_000L
    }

    private class ZoneState {
        var stoppedSince: Long = 0
        var lastStart: Long = 0
        var busy: Boolean = false
    }

    private val states = ConcurrentHashMap<String, ZoneState>()

    fun enabledZones(): Set<String> = app.settings.radioZones()

    fun isEnabled(zoneId: String?): Boolean = zoneId != null && zoneId in enabledZones()

    fun setEnabled(zoneId: String, enabled: Boolean) {
        val zones = enabledZones().toMutableSet()
        if (enabled) zones += zoneId else zones -= zoneId
        app.settings.saveRadioZones(zones)
        if (!enabled) states.remove(zoneId)
    }

    /** Called for every zone update. Cheap and synchronous; the work is deferred. */
    fun onZones(zones: List<Zone>) {
        val on = enabledZones()
        if (on.isEmpty()) return
        val now = System.currentTimeMillis()
        for (zone in zones) {
            if (zone.zoneId !in on) continue
            val state = states.getOrPut(zone.zoneId) { ZoneState() }
            synchronized(state) {
                if (zone.state != "stopped") {
                    state.stoppedSince = 0
                    return@synchronized
                }
                if (state.busy) return@synchronized
                if (now - state.lastStart < COOLDOWN_MS) return@synchronized
                if (state.stoppedSince == 0L) {
                    state.stoppedSince = now
                    return@synchronized
                }
                if (now - state.stoppedSince < SETTLE_MS) return@synchronized
                state.busy = true
            }
            if (synchronized(state) { state.busy }) queueNext(zone.zoneId, state)
        }
    }

    private fun queueNext(zoneId: String, state: ZoneState) {
        app.background {
            try {
                val album = pick() ?: run {
                    Log.d(TAG, "no album to play for $zoneId")
                    return@background
                }
                Log.i(TAG, "radio starts ${album.title} on $zoneId")
                app.albums.open(
                    offset = album.offset,
                    zoneOrOutputId = zoneId,
                    invokeKind = "play_now",
                    filter = null,
                    expect = Albums.Expect(album.title, album.subtitle)
                )
                synchronized(state) { state.lastStart = System.currentTimeMillis() }
            } catch (e: Exception) {
                Log.w(TAG, "radio failed on $zoneId: ${e.message}")
            } finally {
                synchronized(state) {
                    state.busy = false
                    state.stoppedSince = 0
                }
            }
        }
    }

    /**
     * Prefer something the user has not heard lately, which is the whole point
     * of the feature — falling back to the whole library rather than refusing to
     * play when everything has been heard recently.
     */
    private fun pick(): AlbumRecord? {
        val fresh = app.view.unplayed(6)
        val pool = fresh.ifEmpty { app.index.albums }
        return app.view.sample(pool, 1).firstOrNull()
    }
}
