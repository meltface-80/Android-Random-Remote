package com.musicd.lite

import com.musicd.lite.api.Settings
import com.musicd.lite.library.AlbumIndex
import com.musicd.lite.library.AlbumRecord
import com.musicd.lite.meta.Deezer
import com.musicd.lite.meta.ShareLinks
import com.musicd.lite.meta.TtlCache
import com.musicd.lite.store.Store
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Discover (Rouen v1.8.37): new records by the acts you actually play.
 *
 * The one question nothing else in the app answers. Smart Picks is lateral
 * (your own library, reshuffled), Pitchfork is editorial; neither knows whether
 * anybody you listen to has put something out. That needs your listening
 * history, which no outside service has — so the seeds come from the plays
 * table, and Deezer is asked what each act has released lately.
 *
 * A port of Rouen's build in index.js; the rules themselves are [Deezer]'s
 * companion functions, tested without a network. Three differences, each
 * forced by this being a phone:
 *
 *  - NO TIMER OF ITS OWN. Upstream checks every ten minutes on an interval of
 *    its own. Here the existing library check (MusicdLite.libraryMaintenance)
 *    and opening the screen are what ask "is it due?", and [kick] is the one
 *    gate both go through.
 *  - The day's list is a settings document, not a table — the same storage
 *    every other feature store in this build uses, so the Android store needs
 *    no schema change.
 *  - Each act's Deezer listing is cached in memory for the week, not on disk.
 *    A process restart costs one rebuild's worth of calls (two per act), once.
 *
 * OFF BY DEFAULT, as upstream: with it off, nothing here makes a request.
 */
class Discover(
    private val store: Store,
    private val index: AlbumIndex,
    private val settings: Settings,
    private val deezer: Deezer,
    /** A build finished, whatever it found: the `discover` live revision. */
    private val onBuilt: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault
) {

    companion object {
        private const val TAG = "Discover"

        /** The persisted lists, by day, and which rules built the latest one. */
        const val KEY_LIST = "discover_list"

        /** How far back a release counts as new. */
        const val WINDOW_DAYS = 60

        /** How far back the plays table is read for seeds. */
        const val SEED_DAYS = 180

        /** Acts asked about per build — two Deezer calls each. */
        const val SEED_ARTISTS = 40

        /** The screen's ceiling. */
        const val MAX_ROWS = 12

        /** A day's list is kept this long, so a build that finds nothing can show the last one. */
        const val KEEP_DAYS = 7L

        /** One act's listing is good for a week: a back catalogue does not move. */
        const val ARTIST_TTL_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * How soon an UNFORCED attempt may run again after one that did not
         * produce a list (no plays yet, or Deezer unreachable) — upstream's
         * interval, so a failure is retried at the cadence it would be there.
         * Without it, the screen opening a build that ends by moving the
         * `discover` revision would re-read, open another, and loop.
         */
        const val RETRY_MS = 10L * 60 * 1000

        private const val DAY_MS = 86_400_000L

        /**
         * The rules that produced a day's list, stamped beside it. A day built
         * under different ones counts as not built, so changing what counts as
         * a release takes effect the same day rather than the next. Rouen's
         * format, so the stamp reads the same in both.
         */
        val RULES: String = listOf(
            "gen2", WINDOW_DAYS, SEED_ARTISTS, MAX_ROWS, Deezer.WANTED_PER_ARTIST, Deezer.MIN_ALBUM_TRACKS
        ).joinToString(":")

        /** One library album, reduced to what the owned check needs. */
        class Owned(val key: String, val names: List<String>)

        fun ownedIndex(albums: List<AlbumRecord>): List<Owned> = albums.mapNotNull { al ->
            val key = Deezer.titleKey(al.title)
            if (key.isEmpty()) return@mapNotNull null
            val names = (listOf(al.subtitle) + al.artistNames.map { it.name })
                .map { Deezer.normalize(it) }.filter { it.isNotEmpty() }
            Owned(key, names)
        }

        /**
         * The titles THIS act already has in the library. The artist is part
         * of the question: a title-only check means owning any "Greatest Hits"
         * hides every other act's for ever. Matched loosely ("Eno" finds
         * "Brian Eno"), because hiding a row is the better failure than
         * showing one you already own.
         */
        fun ownedFor(seedName: String, owned: List<Owned>): Set<String> {
            val want = Deezer.normalize(seedName)
            if (want.isEmpty()) return emptySet()
            val wantPad = " $want "
            val out = HashSet<String>()
            for (row in owned) {
                if (row.names.any { n -> " $n ".contains(wantPad) || wantPad.contains(" $n ") }) out += row.key
            }
            return out
        }
    }

    /** One record on the day's list, as persisted. */
    data class Row(
        val artist: String,
        val album: String,
        val albumId: String?,
        val cover: String?,
        val releaseDate: String?,
        val ts: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("artist", artist).put("album", album)
            .put("album_id", albumId ?: JSONObject.NULL)
            .put("cover", cover ?: JSONObject.NULL)
            .put("release_date", releaseDate ?: JSONObject.NULL)
            .put("ts", ts)

        companion object {
            fun from(o: JSONObject?): Row? {
                if (o == null) return null
                val album = o.str("album")
                if (album.isEmpty()) return null
                return Row(o.str("artist"), album, o.strOrNull("album_id"), o.strOrNull("cover"),
                    o.strOrNull("release_date"), o.optLong("ts", 0))
            }
        }
    }

    // ----------------------------------------------------------------- the gate

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "musicd-discover").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    @Volatile
    private var lastAttempt = 0L

    private val artistCache = TtlCache<String, List<Deezer.Release>>(ARTIST_TTL_MS, 500)

    val building: Boolean get() = inFlight.get()

    fun today(): String = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate().toString()

    /** At or after the hour, not equal to it — a phone asleep at 05:00 still gets its list later. */
    fun due(): Boolean = Instant.ofEpochMilli(clock()).atZone(zone()).hour >= settings.discoverHour()

    /**
     * Start today's build if it should run; true when one is running.
     *
     * THE PRECONDITIONS ARE NOT SCHEDULE RULES AND [force] DOES NOT SKIP THEM.
     * A build with no library has an empty owned set, so every record already
     * owned would read as new and the day would be stamped built with them.
     * What [force] overrides is "already built today", "not yet the hour" and
     * the retry spacing.
     */
    fun kick(why: String, force: Boolean = false): Boolean {
        if (!settings.discoverEnabled()) return false
        if (!index.isBuilt) return false
        if (inFlight.get()) return true
        val day = today()
        if (!force) {
            if (stampCurrent(day)) return false
            if (!due()) return false
            if (clock() - lastAttempt < RETRY_MS) return false
        }
        if (!inFlight.compareAndSet(false, true)) return true
        lastAttempt = clock()
        try {
            worker.execute {
                try {
                    build(day)
                } catch (e: Exception) {
                    Log.w(TAG, "build ($why) failed: ${e.message}", e)
                } finally {
                    // Cleared BEFORE the revision moves: a page that re-reads on
                    // it must not be told the build is still running.
                    inFlight.set(false)
                    runCatching(onBuilt)
                }
            }
        } catch (e: Exception) {
            inFlight.set(false)
            Log.w(TAG, "could not start a build: ${e.message}")
            return false
        }
        return true
    }

    fun close() {
        worker.shutdownNow()
    }

    // ---------------------------------------------------------------- the build

    /** The acts to ask about: the TRACK artist from six months of plays. */
    internal fun seeds(): List<Deezer.Seed> {
        val rows = store.playsSince(clock() - SEED_DAYS * DAY_MS).map { it.artist to it.at }
        return Deezer.playedArtists(rows, SEED_ARTISTS, ShareLinks::primaryArtist)
    }

    /**
     * One act's recent records, or null when Deezer could not be asked (which
     * is not cached, unlike an act Deezer does not know).
     *
     * THE NAME MATCH IS EXACT, stricter than "If you like this": the seed is
     * the act's name as Roon files it, so a partial match here is a tribute
     * band, and its record would be shown as new from someone you love.
     */
    internal fun releasesFor(seedName: String, ownedKeys: Set<String>, now: Long): List<Deezer.Release>? {
        val key = Deezer.normalize(seedName)
        var albums = artistCache.peek(key)
        if (albums == null) {
            val search = deezer.searchArtist(seedName) ?: return null
            val exact = Deezer.readArtists(search, seedName).filter { it.exact }
            if (exact.isEmpty()) {
                artistCache.put(key, emptyList())
                return emptyList()
            }
            val listing = deezer.albums(exact[0].id) ?: return null
            albums = Deezer.readArtistAlbums(listing)
            artistCache.put(key, albums)
        }
        // The window and the owned check are applied on the way OUT, never
        // before the cache: both move while a cached listing stays valid.
        return Deezer.pickNewReleases(albums, now, now - WINDOW_DAYS * DAY_MS, ownedKeys)
    }

    internal fun build(day: String) {
        val t0 = clock()
        val seeds = seeds()
        if (seeds.isEmpty()) {
            // Not stamped: a phone with no listening history yet should start
            // producing the day it has one, not tomorrow.
            Log.i(TAG, "no play history yet — nothing to build from")
            return
        }
        val owned = ownedIndex(index.albums)
        val now = clock()
        val found = ArrayList<Row>()
        val seen = HashSet<String>()
        var asked = 0
        var failed = 0
        for (seed in seeds) {
            if (Thread.currentThread().isInterrupted) return   // the app is stopping
            val rows = releasesFor(seed.name, ownedFor(seed.name, owned), now)
            if (rows == null) {
                failed++
                continue
            }
            asked++
            for (r in rows) {
                // Two seeded acts on one record (a split, a collaboration) is
                // ONE row — keyed on Deezer's id, the only thing that means
                // "the same release" here.
                val dedupe = r.id?.let { "id:$it" } ?: (Deezer.titleKey(r.title) + "|" + Deezer.normalize(seed.name))
                if (!seen.add(dedupe)) continue
                found += Row(seed.name, r.title, r.id, r.cover, r.date, r.ts)
            }
        }
        if (asked == 0) {
            // Every act failed: Deezer is unreachable, which says nothing about
            // what is new. Yesterday's list stands; the next check retries.
            Log.w(TAG, "${seeds.size} seeds, every lookup failed — keeping the last list")
            return
        }
        val rows = found.sortedByDescending { it.ts }.take(MAX_ROWS)
        persist(day, rows)
        Log.i(
            TAG,
            "${seeds.size} seeds ($asked asked, $failed failed) -> ${found.size} releases, " +
                "kept ${rows.size} in ${(clock() - t0) / 1000}s"
        )
    }

    // ---------------------------------------------------------------- storage

    private fun doc(): JSONObject =
        store.setting(KEY_LIST)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    @Synchronized
    private fun persist(day: String, rows: List<Row>) {
        val d = doc()
        val days = d.optJSONObject("days") ?: JSONObject()
        days.put(day, JSONArray().apply { rows.forEach { put(it.toJson()) } })
        // Older days are never read again except as a fallback; a week is kept.
        val floor = LocalDate.parse(day).minusDays(KEEP_DAYS).toString()
        for (k in days.keys().asSequence().toList()) if (k < floor) days.remove(k)
        d.put("days", days)
        // Written with the rows, never ahead of them: a stamp for a list that
        // is not there would freeze the old one in place until tomorrow.
        d.put("built", JSONObject().put("day", day).put("rules", RULES))
        store.putSetting(KEY_LIST, d.toString())
    }

    /** Was [day] built under the rules this build runs? */
    fun stampCurrent(day: String): Boolean {
        val b = doc().optJSONObject("built") ?: return false
        return b.str("day") == day && b.str("rules") == RULES
    }

    private fun rowsOf(days: JSONObject, day: String): List<Row> {
        val a = days.optJSONArray(day) ?: return emptyList()
        return (0 until a.length()).mapNotNull { Row.from(a.optJSONObject(it)) }
    }

    /**
     * Today's list, or the most recent day that has one: a build that found
     * nothing today shows last week's records rather than an empty screen —
     * they are still new, and still unheard.
     */
    fun latest(): Pair<String, List<Row>> {
        val today = today()
        val days = doc().optJSONObject("days") ?: return today to emptyList()
        val now = rowsOf(days, today)
        if (now.isNotEmpty()) return today to now
        for (k in days.keys().asSequence().toList().sortedDescending()) {
            if (k > today) continue
            val rows = rowsOf(days, k)
            if (rows.isNotEmpty()) return k to rows
        }
        return today to emptyList()
    }

    // ------------------------------------------------- is it in the library?

    @Volatile
    private var titleMap: Pair<Long, Map<String, List<AlbumRecord>>>? = null

    private fun byTitle(): Map<String, List<AlbumRecord>> {
        val gen = index.generation
        titleMap?.let { (g, m) -> if (g == gen) return m }
        val m = index.albums.groupBy { Deezer.titleKey(it.title) }.filterKeys { it.isNotEmpty() }
        titleMap = gen to m
        return m
    }

    /**
     * Is this suggested record in the library, and if so which album?
     *
     * STRICT ON THE TITLE, FORGIVING ON THE ARTIST. The answer becomes a
     * queue, so a wrong title plays the wrong record; the artist is matched
     * loosely so "Eno" finds "Brian Eno". A title shared by different acts
     * with no artist to separate them is not an answer. Used by Discover and
     * by "If you like this" alike.
     */
    fun resolve(title: String?, artist: String?): AlbumRecord? {
        val t = Deezer.titleKey(title)
        if (t.isEmpty() || !index.isBuilt) return null
        val hits = byTitle()[t] ?: return null
        val want = (artist ?: "").trim()
        if (want.isEmpty()) return hits.singleOrNull()
        return hits.firstOrNull { al ->
            Deezer.namesOverlap(al.subtitle, want) || al.artistNames.any { Deezer.namesOverlap(it.name, want) }
        }
    }
}
