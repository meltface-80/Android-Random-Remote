package com.musicd.lite.store

/**
 * Everything the app remembers between runs.
 *
 * The core module is plain Kotlin/JVM so it can be unit-tested without an
 * Android SDK, which rules out android.database.sqlite here. The Android module
 * supplies a SQLite-backed implementation; the tests supply [MemoryStore].
 *
 * Deliberately absent, and the single largest reason this build is "lite": the
 * label tables. MusicD-Remote keeps label_names, label_mbids, label_logos and
 * label_merges, fed by a scan that reads file tags off a mounted music
 * directory and then queries iTunes, MusicBrainz, TheAudioDB, Discogs and
 * FanArt.tv. A phone has no mounted /music, and the rest of that machinery is
 * most of the server's weight, so none of it is here.
 */
interface Store {

    // ----------------------------------------------------------- Roon pairing

    /** The extension token Roon issued for a Core, or null before approval. */
    fun tokenFor(coreId: String): String?
    fun saveToken(coreId: String, token: String)

    /** Last Core address, so a restart reconnects without waiting on discovery. */
    fun lastCore(): Pair<String, Int>?
    fun saveLastCore(host: String, port: Int)
    fun forgetLastCore()

    // -------------------------------------------------------------- settings

    /** Free-form settings, stored as one JSON document under a key. */
    fun setting(key: String): String?
    fun putSetting(key: String, value: String)

    // ---------------------------------------------------------- play history

    /**
     * A track that started playing. Recorded from the zone feed, which is the
     * only signal available — Roon has no "played" event for extensions.
     */
    fun recordPlay(albumKey: String, album: String, artist: String, track: String, at: Long)

    /** Album keys played since [since], most recent first. */
    fun playsSince(since: Long): List<PlayRow>

    /** Epoch millis an album was last played, or null if never. */
    fun lastPlayed(albumKey: String): Long?

    /** Album key -> last played, for every album with history. Used by the Home rows. */
    fun lastPlayedAll(): Map<String, Long>

    /** Total play count per album key. */
    fun playCounts(): Map<String, Int>

    /** Drop history older than [before]. */
    fun prunePlays(before: Long)

    // ------------------------------------------------------- album metadata

    /** When an album was first seen in the library — the "recently added" clock. */
    fun firstSeen(albumKey: String): Long?
    fun recordFirstSeen(entries: Map<String, Long>)
    fun firstSeenAll(): Map<String, Long>

    /** Release year, from whichever source found it. Higher rank wins. */
    fun albumYear(albumKey: String): Int?
    fun putAlbumYear(albumKey: String, year: Int, sourceRank: Int)
    fun albumYears(): Map<String, Int>

    /** Genres Roon files an album under. */
    fun albumGenres(albumKey: String): List<String>
    fun putAlbumGenres(albumKey: String, genres: List<String>)
    fun albumGenresAll(): Map<String, List<String>>

    /** Roon's own track list for an album, filled from ordinary browsing. */
    fun albumTracks(albumKey: String): List<String>
    fun putAlbumTracks(albumKey: String, tracks: List<String>)

    // ---------------------------------------------------------- smart picks

    /** Album keys the user has told the app not to suggest again. */
    fun blockedPicks(): Set<String>
    fun blockPick(albumKey: String)

    /** Album keys already offered as a pick, so the same record isn't re-served. */
    fun seenPicks(): Set<String>
    fun markPickSeen(albumKey: String, at: Long)

    fun close()
}

data class PlayRow(
    val albumKey: String,
    val album: String,
    val artist: String,
    val track: String,
    val at: Long
)

/**
 * Rank of the source a release year came from, so a good answer is never
 * overwritten by a worse one. Higher wins.
 */
object YearSource {
    const val GUESS = 0
    const val STREAMING = 1
    const val MUSICBRAINZ = 2
    const val FILE_TAG = 3
}
