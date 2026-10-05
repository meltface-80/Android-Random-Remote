package com.musicd.lite

import com.musicd.lite.roon.Zone
import org.json.JSONObject

/**
 * What has already played, per zone — the Queue's "N played earlier" fold-out
 * (Rouen v1.7.77). A port of Rouen's lib/queue-history.js and the zone hook
 * that feeds it.
 *
 * Roon's queue reports the current track and what is coming; a track that has
 * finished — or that was skipped past — is simply gone from it, and the
 * extension API cannot look backwards. The one place a departure can be seen
 * is the zone feed the app already handles: at a track change, the OUTGOING
 * track and how much of it played are both known, and are known nowhere else
 * afterwards. This is the record built from those moments.
 *
 * It is not a queue. It cannot be replayed as one (that would mean rebuilding
 * the queue through the browse hierarchy, hundreds of Core round trips), and it
 * is in memory: it does not survive the app being stopped. The page says so.
 */
class QueueHistory(private val max: Int = HISTORY_MAX) {

    companion object {
        /** Per zone: longer than any session anyone scrolls back through, and bounded. */
        const val HISTORY_MAX = 200

        /** How many the page is sent — the most recent. */
        const val SEND_MAX = 50

        /**
         * How many tracks one selection may act on. Every track is a full browse
         * navigation, so this is what stops a tap becoming minutes of Core
         * traffic, not a limit anything breaks above.
         */
        const val MULTI_MAX = 20

        /**
         * Did enough of a track play to count as listened to? The scrobbler's
         * rule: thirty seconds, and half the track or four minutes.
         */
        fun playCounted(elapsed: Int, duration: Int): Boolean =
            elapsed >= 30 && (elapsed >= duration * 0.5 || elapsed >= 240)

        /**
         * The order to send a selection in so it lands in the order it was
         * picked. "Add Next" puts an item straight after the current track, so
         * repeated sends STACK — A, B, C would play C, B, A — and are sent
         * reversed. Appending keeps its order by definition.
         *
         * Rouen's note stands here too: last-in-first-out is how every player
         * this behaves like treats "play next", and it could not be verified
         * against a live Core without a real insert the API cannot undo. If
         * Roon proves otherwise, this is the one line to change.
         */
        fun <T> sendOrderFor(kind: String, list: List<T>): List<T> =
            if (kind == "play_next") list.reversed() else list.toList()
    }

    data class Entry(
        val track: String,
        val artist: String,
        val album: String,
        val imageKey: String?,
        val duration: Int,
        val elapsed: Int,
        val played: Boolean,
        val ts: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("track", track)
            .put("artist", artist)
            .put("album", album)
            .put("image_key", imageKey ?: JSONObject.NULL)
            .put("duration", duration)
            .put("elapsed", elapsed)
            .put("played", played)
            .put("ts", ts)
    }

    /** What a zone is playing now, and how much of it has played. */
    private class Current(
        val track: String,
        val artist: String,
        val album: String,
        val imageKey: String?,
        val duration: Int,
        var elapsed: Int,
        var lastSeek: Int
    )

    private val lock = Any()
    private val current = HashMap<String, Current>()
    private val history = HashMap<String, ArrayDeque<Entry>>()

    /** Every zone push. Cheap: a few comparisons per zone, no I/O. */
    fun observe(zones: List<Zone>, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        for (z in zones) observeZone(z, now)
    }

    private fun observeZone(z: Zone, now: Long) {
        val np = z.nowPlaying
        val prev = current[z.zoneId]
        val track = np?.line1.orEmpty()
        if (z.isPlaying && np != null && track.isNotEmpty()) {
            val album = np.line3
            if (prev == null || prev.track != track || prev.album != album) {
                // The outgoing track has just left the queue, and Roon will
                // never report it again: this is the only moment it and its
                // elapsed time are both known.
                if (prev != null) push(z.zoneId, prev, now)
                current[z.zoneId] = Current(
                    track, np.line2, album, np.imageKey, np.lengthSeconds ?: 0,
                    elapsed = 0, lastSeek = np.seekPosition ?: 0
                )
            } else {
                // Same track: count the clock forward by what the seek
                // position moved — and only a plausible step of it, so a seek
                // ahead is not "listened to".
                val pos = np.seekPosition ?: prev.lastSeek
                val delta = pos - prev.lastSeek
                if (delta in 1..29) prev.elapsed += delta
                prev.lastSeek = pos
            }
        } else if (prev != null) {
            // Paused or stopped: NOT a departure. Pausing would otherwise file
            // the track still being listened to as "played earlier" every time.
            current.remove(z.zoneId)
        }
    }

    private fun push(zoneId: String, prev: Current, now: Long) {
        val list = history.getOrPut(zoneId) { ArrayDeque() }
        // The same track announced twice in a moment (metadata settling) is
        // not two plays.
        val last = list.lastOrNull()
        if (last != null && last.track == prev.track && last.album == prev.album && now - last.ts < 2_000) return
        list.addLast(
            Entry(
                prev.track, prev.artist, prev.album, prev.imageKey, prev.duration,
                prev.elapsed.coerceAtLeast(0), playCounted(prev.elapsed, prev.duration), now
            )
        )
        while (list.size > max) list.removeFirst()
    }

    /** The newest [count] departures for a zone, newest FIRST. */
    fun recent(zoneId: String?, count: Int = SEND_MAX): List<Entry> = synchronized(lock) {
        val list = zoneId?.let { history[it] } ?: return emptyList()
        list.toList().takeLast(count).reversed()
    }

    /** The Core went away: zone ids may come back meaning something else. */
    fun clear() = synchronized(lock) {
        current.clear()
        history.clear()
    }
}
