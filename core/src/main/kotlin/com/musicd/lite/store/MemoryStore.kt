package com.musicd.lite.store

import java.util.concurrent.ConcurrentHashMap

/**
 * A [Store] that keeps everything in memory.
 *
 * Used by the unit tests, and by the app as a fallback if the database cannot
 * be opened — the remote still works in that case, it just forgets what it
 * learned when it stops, which is a far better failure than refusing to start.
 */
class MemoryStore : Store {

    private val tokens = ConcurrentHashMap<String, String>()
    private val settings = ConcurrentHashMap<String, String>()
    private val plays = ArrayList<PlayRow>()
    private val firstSeen = ConcurrentHashMap<String, Long>()
    private val years = ConcurrentHashMap<String, Pair<Int, Int>>()   // key -> (year, rank)
    private val genres = ConcurrentHashMap<String, List<String>>()
    private val tracks = ConcurrentHashMap<String, List<String>>()
    private val blocked = ConcurrentHashMap.newKeySet<String>()
    private val picksSeen = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var core: Pair<String, Int>? = null

    override fun tokenFor(coreId: String): String? = tokens[coreId]

    override fun saveToken(coreId: String, token: String) {
        tokens[coreId] = token
    }

    override fun lastCore(): Pair<String, Int>? = core

    override fun saveLastCore(host: String, port: Int) {
        core = host to port
    }

    override fun forgetLastCore() {
        core = null
    }

    override fun setting(key: String): String? = settings[key]

    override fun putSetting(key: String, value: String) {
        settings[key] = value
    }

    override fun recordPlay(
        albumKey: String,
        album: String,
        artist: String,
        track: String,
        at: Long
    ) {
        synchronized(plays) { plays += PlayRow(albumKey, album, artist, track, at) }
    }

    override fun playsSince(since: Long): List<PlayRow> =
        synchronized(plays) { plays.filter { it.at >= since }.sortedByDescending { it.at } }

    override fun lastPlayed(albumKey: String): Long? =
        synchronized(plays) { plays.filter { it.albumKey == albumKey }.maxOfOrNull { it.at } }

    override fun lastPlayedAll(): Map<String, Long> = synchronized(plays) {
        val out = HashMap<String, Long>()
        for (p in plays) {
            val prev = out[p.albumKey]
            if (prev == null || p.at > prev) out[p.albumKey] = p.at
        }
        out
    }

    override fun playCounts(): Map<String, Int> = synchronized(plays) {
        val out = HashMap<String, Int>()
        for (p in plays) out[p.albumKey] = (out[p.albumKey] ?: 0) + 1
        out
    }

    override fun prunePlays(before: Long) {
        synchronized(plays) { plays.removeAll { it.at < before } }
    }

    override fun firstSeen(albumKey: String): Long? = firstSeen[albumKey]

    override fun recordFirstSeen(entries: Map<String, Long>) {
        for ((k, v) in entries) firstSeen.putIfAbsent(k, v)
    }

    override fun firstSeenAll(): Map<String, Long> = HashMap(firstSeen)

    override fun albumYear(albumKey: String): Int? = years[albumKey]?.first

    override fun putAlbumYear(albumKey: String, year: Int, sourceRank: Int) {
        years.compute(albumKey) { _, existing ->
            if (existing != null && existing.second > sourceRank) existing else year to sourceRank
        }
    }

    override fun albumYears(): Map<String, Int> = years.mapValues { it.value.first }

    override fun albumGenres(albumKey: String): List<String> = genres[albumKey] ?: emptyList()

    override fun putAlbumGenres(albumKey: String, genres: List<String>) {
        this.genres[albumKey] = genres
    }

    override fun albumGenresAll(): Map<String, List<String>> = HashMap(genres)

    override fun albumTracks(albumKey: String): List<String> = tracks[albumKey] ?: emptyList()

    override fun putAlbumTracks(albumKey: String, tracks: List<String>) {
        this.tracks[albumKey] = tracks
    }

    override fun blockedPicks(): Set<String> = HashSet(blocked)

    override fun blockPick(albumKey: String) {
        blocked += albumKey
    }

    override fun seenPicks(): Set<String> = HashSet(picksSeen)

    override fun markPickSeen(albumKey: String, at: Long) {
        picksSeen += albumKey
    }

    override fun close() { /* nothing to release */ }
}
