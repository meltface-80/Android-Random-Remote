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
 *
 * IT NEEDS A CLOCK OF ITS OWN, and not having one is why it never queued
 * anything. Roon's zone subscription is an event feed: the end of a queue
 * arrives as a short burst of updates and then, with nothing playing anywhere,
 * the Core says nothing more. The settle below wants a reading four seconds
 * after the first — and no such reading comes, because the thing that produces
 * readings has just gone quiet. The feature only ever fired when a DIFFERENT
 * zone was playing, whose once-a-second seek ticks kept calling the gate for
 * everybody.
 *
 * So a stopped zone arms a single one-shot recheck through [Delay]. It is not
 * a poll and must not become one: it asks the Core for nothing, it reads zone
 * state the app already holds, it is armed by an event rather than running all
 * the time, and it disarms itself. A timer that ticks while there is nothing
 * to decide is the thing this project forbids.
 */
class Radio(
    private val app: MusicdLite,
    private val clock: () -> Long = System::currentTimeMillis,
    private val delay: Delay = Delay { ms, task -> app.schedule(ms, task) }
) {

    /** Run [task] once, [ms] from now. The seam that makes the wait testable. */
    fun interface Delay {
        fun after(ms: Long, task: () -> Unit)
    }

    private companion object {
        const val TAG = "Radio"

        /**
         * A zone must be seen stopped twice, this far apart, before radio acts.
         * One reading is a transition; two is a state.
         */
        const val SETTLE_MS = 4_000L

        /** Never start two albums in quick succession on the same zone. */
        const val COOLDOWN_MS = 30_000L

        /**
         * When to look at a stopped zone again. Comfortably past the settle, so
         * the recheck that follows the first reading of a stop is the one that
         * acts rather than another one that waits.
         */
        const val RECHECK_MS = SETTLE_MS + 1_000L

        /**
         * How many times in a row a stopped zone is looked at before the radio
         * leaves it alone until Roon speaks again.
         *
         * Normally one recheck starts an album and the zone plays. A zone that
         * accepts "play now" and stays stopped — a dead output, a grouped zone
         * that has gone away — would otherwise be retried by this clock for as
         * long as the app runs. Twelve is a minute of trying.
         */
        const val MAX_RECHECKS = 12
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
        /** A recheck is already scheduled for this zone; do not stack another. */
        var armed: Boolean = false
        /** Rechecks since this zone was last seen doing anything but stopped. */
        var rechecks: Int = 0
    }

    private val states = ConcurrentHashMap<String, ZoneState>()

    fun enabledZones(): Set<String> = app.settings.radioZones()

    fun isEnabled(zoneId: String?): Boolean = zoneId != null && zoneId in enabledZones()

    fun setEnabled(zoneId: String, enabled: Boolean) {
        val zones = enabledZones().toMutableSet()
        if (enabled) zones += zoneId else zones -= zoneId
        app.settings.saveRadioZones(zones)
        if (!enabled) {
            states.remove(zoneId)
            return
        }
        // Switching it on is itself an event, and usually the only one coming:
        // the zone this is turned on for is typically sitting stopped, which is
        // why its owner wants a radio on it. Without this the feature waits for
        // a zone update that a quiet Core will not send.
        runCatching { onZones(app.roon.zones()) }
    }

    /** Called for every zone update. Cheap and synchronous; the work is deferred. */
    fun onZones(zones: List<Zone>, now: Long = clock()) {
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
                    zone.state != "stopped" -> {
                        state.stoppedSince = null
                        state.rechecks = 0
                    }
                    // An album is already on its way to this zone.
                    state.busy -> Unit
                    // Each of the three waiting cases below arms the look that
                    // ends the wait. The Core is under no obligation to send
                    // another update about a zone that has stopped, and when
                    // nothing else is playing it sends none.
                    lastStart != null && now - lastStart < COOLDOWN_MS ->
                        armRecheck(state)
                    // First reading of a stop is a transition; two is a state.
                    stoppedSince == null -> {
                        state.stoppedSince = now
                        armRecheck(state)
                    }
                    now - stoppedSince < SETTLE_MS -> armRecheck(state)
                    else -> {
                        state.busy = true
                        state.rechecks = 0
                        starting += zone.zoneId
                    }
                }
            }
        }
        return starting
    }

    /**
     * Look at this zone again once the settle can have elapsed.
     *
     * Called while holding the zone's lock, and arms at most one outstanding
     * look per zone. The task re-reads the zones the app already has and runs
     * the same gate, so it is a no-op unless the zone is still stopped.
     */
    private fun armRecheck(state: ZoneState) {
        if (state.armed || state.rechecks >= MAX_RECHECKS) return
        state.armed = true
        state.rechecks++
        delay.after(RECHECK_MS) {
            synchronized(state) { state.armed = false }
            runCatching { onZones(app.roon.zones()) }
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
                synchronized(state) { state.lastStart = clock() }
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
     * Anything in the library, at random.
     *
     * This used to prefer albums not played in six months. It no longer can:
     * the plays table holds only what this app watched happen and starts empty,
     * so "not heard lately" was really "not heard by this app", which on a young
     * install is the whole library and after that is still missing everything
     * played from another remote.
     */
    private fun pick(): AlbumRecord? = app.view.sample(app.index.albums, 1).firstOrNull()
}
