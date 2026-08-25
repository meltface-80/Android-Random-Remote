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

    /**
     * Null means "has not happened", which is not the same fact as a timestamp
     * and must not be spelled as one. Writing these as 0 makes "never started"
     * read as "started at the epoch" — true enough in production, where that is
     * decades ago, and wrong the moment anything reasons about a clock that
     * does not start in 1970.
     */
    private class ZoneState {
        var stoppedSince: Long? = null
        var lastStart: Long? = null
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
    fun onZones(zones: List<Zone>, now: Long = System.currentTimeMillis()) {
        for (zoneId in zonesToStart(zones, now)) {
            queueNext(zoneId, states.getValue(zoneId))
        }
    }

    /**
     * Which zones should have an album put on right now, marking each one busy
     * so a second call cannot pick it again.
     *
     * The answer is RETURNED rather than re-read from the zone state, and that
     * is the whole point. Deciding inside the lock and then re-reading
     * `state.busy` outside it to decide whether to act reads `true` for a zone
     * that was ALREADY busy from an earlier update — so every zone update
     * arriving while one album was being queued would queue another. A zone
     * feed ticks several times a second.
     */
    internal fun zonesToStart(zones: List<Zone>, now: Long): List<String> {
        val on = enabledZones()
        if (on.isEmpty()) return emptyList()
        val starting = ArrayList<String>()
        for (zone in zones) {
            if (zone.zoneId !in on) continue
            val state = states.getOrPut(zone.zoneId) { ZoneState() }
            synchronized(state) {
                val stoppedSince = state.stoppedSince
                val lastStart = state.lastStart
                when {
                    // Playing, loading, paused: not our business, and the
                    // settle clock restarts from whenever it next stops.
                    zone.state != "stopped" -> state.stoppedSince = null
                    // An album is already on its way to this zone.
                    state.busy -> Unit
                    lastStart != null && now - lastStart < COOLDOWN_MS -> Unit
                    // First reading of a stop is a transition; two is a state.
                    stoppedSince == null -> state.stoppedSince = now
                    now - stoppedSince < SETTLE_MS -> Unit
                    else -> {
                        state.busy = true
                        starting += zone.zoneId
                    }
                }
            }
        }
        return starting
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
                    state.stoppedSince = null
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
