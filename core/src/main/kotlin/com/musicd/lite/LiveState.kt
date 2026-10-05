package com.musicd.lite

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONObject

/**
 * One revision per kind of data a screen can be showing, so a screen re-reads
 * itself when — and only when — something it shows has moved.
 *
 * Rouen's page asks for these with a timer every three seconds. This app does
 * not poll, so the same answer is served as a LONG POLL: the page sends back
 * the revisions it last saw as `wait_for`, and the request is held until one of
 * them differs or [MAX_WAIT_MS] passes. The page's own handling of an answer is
 * unchanged — it still diffs the revisions it is given — only the cadence is.
 *
 *   snapshot  the album list itself: the index was rebuilt.
 *   library   the snapshot and what is derived from it (years, genres).
 *   dates     release years. Moves the Release date order only.
 *   plays     a play recorded.
 *   settings  any settings write, from any device.
 *   labels    always "0": there are no labels in this build.
 *   picks     Smart Picks' daily set and its edits.
 *   discover  Discover's daily list.
 *   later     the Listen later list.
 *   day       the local date; the daily builds turn over at midnight.
 *   aotd      Album of the day's date, which turns at 00:01.
 *
 * Nothing here costs a Core call or a query: every value is a counter or a
 * clock reading.
 */
class LiveState(
    private val snapshot: () -> Long,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        /** How long a held request waits before answering unchanged. */
        const val MAX_WAIT_MS = 25_000L

        val KINDS = listOf("library", "dates", "plays", "settings", "picks", "discover", "later")
    }

    private val lock = Object()
    private val counters = HashMap<String, Long>()

    /** Something of [kind] changed. Wakes every held request. */
    fun bump(kind: String) = synchronized(lock) {
        require(kind in KINDS) { "unknown live kind $kind" }
        counters[kind] = (counters[kind] ?: 0) + 1
        lock.notifyAll()
    }

    /** The index was rebuilt: the album list moved, and everything derived from it. */
    fun snapshotChanged() = synchronized(lock) { lock.notifyAll() }

    private fun dayKey(at: Long): String =
        LocalDate.ofInstant(java.time.Instant.ofEpochMilli(at), zone()).toString()

    /** Album of the day turns at 00:01, a minute after [dayKey]. */
    private fun aotdKey(at: Long): String = dayKey(at - 60_000L)

    fun revisions(): Map<String, String> = synchronized(lock) {
        val built = snapshot()
        val t = now()
        linkedMapOf(
            "snapshot" to built.toString(),
            "library" to "$built.${counters["library"] ?: 0}",
            "dates" to (counters["dates"] ?: 0).toString(),
            "plays" to (counters["plays"] ?: 0).toString(),
            "settings" to (counters["settings"] ?: 0).toString(),
            "labels" to "0",
            "picks" to (counters["picks"] ?: 0).toString(),
            "discover" to (counters["discover"] ?: 0).toString(),
            "later" to (counters["later"] ?: 0).toString(),
            "day" to dayKey(t),
            "aotd" to aotdKey(t)
        )
    }

    /** The revisions as one comparable token, which is what the page sends back. */
    fun token(rev: Map<String, String> = revisions()): String =
        rev.entries.joinToString("&") { "${it.key}=${it.value}" }

    /** Milliseconds until the next clock-driven revision (midnight or 00:01) moves. */
    internal fun msUntilClockTurn(): Long {
        val z = zone()
        val t = now()
        val local = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), z)
        val midnight = local.toLocalDate().plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
        val nextMidnight = midnight - t
        val past = local.toLocalTime().toSecondOfDay() < 60
        val oneAfter = if (past) local.toLocalDate().atStartOfDay(z).toInstant().toEpochMilli() + 60_000 - t
        else nextMidnight + 60_000
        return minOf(nextMidnight, oneAfter).coerceAtLeast(1)
    }

    /**
     * Hold until the revisions differ from [since] (a [token]) or [timeoutMs]
     * passes, then answer the current ones. A [since] of null answers at once:
     * the page's first ask has nothing to compare against.
     */
    fun await(since: String?, timeoutMs: Long): Map<String, String> {
        if (since == null) return revisions()
        val deadline = now() + timeoutMs.coerceIn(0, MAX_WAIT_MS)
        synchronized(lock) {
            while (true) {
                val rev = revisions()
                if (token(rev) != since) return rev
                val left = minOf(deadline - now(), msUntilClockTurn())
                if (deadline - now() <= 0) return rev
                lock.wait(left.coerceAtLeast(1))
            }
        }
    }

    fun toJson(rev: Map<String, String> = revisions()): JSONObject =
        JSONObject().put("rev", JSONObject(rev)).put("token", token(rev))
}
