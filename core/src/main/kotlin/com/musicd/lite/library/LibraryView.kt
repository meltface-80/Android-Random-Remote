package com.musicd.lite.library

import com.musicd.lite.store.Store
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom

/**
 * Sorting, filtering and picking over the album snapshot.
 *
 * Roon's own Sort and Focus run on a private API: the extension API exposes
 * four strings per album and no ordering control at all. Everything here is
 * built from the snapshot and its side tables (release years, play history,
 * first-seen dates), which means no Roon round-trips on a user action and
 * composable facets the browse tree cannot express.
 */
class LibraryView(private val index: AlbumIndex, private val store: Store) {

    companion object {
        val SORTS = listOf("album", "artist", "year", "added", "plays", "lastplayed", "random")
        val PLAYED_FILTERS = listOf("any", "never", "played", "6", "12")

        const val PREFIX_MAX = 40
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MONTH_MS = 30 * DAY_MS

        /** FNV-1a, for the deterministic album-of-the-day pick. */
        fun fnv1a(s: String): Int {
            var h = -2128831035          // 2166136261 as a signed Int
            for (c in s) {
                h = h xor c.code
                h *= 16777619
            }
            return h
        }

        /**
         * A stable shuffle: the same seed always yields the same order, and a
         * different seed yields a different one.
         *
         * The second half of that is why this is not the plain `h = h*31 + c`
         * that MusicD-Remote uses. With that hash the seed only ever contributes
         * `seed * 31^length`, so for two keys of the SAME length the difference
         * between their ranks does not depend on the seed at all — every album
         * whose key is the same length keeps its relative order no matter what
         * seed is passed. Folding the seed into an FNV-1a basis and finishing
         * with an avalanche makes the seed reach every bit.
         */
        fun seededRank(s: String, seed: Int): Int {
            var h = -2128831035 xor seed
            for (c in s) {
                h = h xor c.code
                h *= 16777619
            }
            h = h xor (h ushr 15)
            h *= -2048144789
            h = h xor (h ushr 13)
            return h
        }

        /**
         * The funnel's text, folded the one way this app folds anything, and
         * bounded because it arrives on a query string: a megabyte of "a" would
         * otherwise be compared against every album.
         */
        fun prefix(raw: String?): String = Normalize.text(raw).take(PREFIX_MAX)
    }

    /** Play history is keyed by album title alone, exactly as the table records it. */
    private fun playKey(al: AlbumRecord): String = al.title.lowercase(Locale.ROOT).trim()

    fun albumYearOf(al: AlbumRecord): Int? = store.albumYear(al.key)

    /** Epoch millis the album first appeared in the library, or null. */
    fun albumAddedOf(al: AlbumRecord): Long? = store.firstSeen(al.key)

    /**
     * Does this album start with the typed text, by title or by artist?
     *
     * Title uses [AlbumRecord.sortTitle] — the article-stripped key the A-Z
     * wall and every sort tiebreak use — so typing W finds "The Wall" where the
     * wall files it, not under T. Each credited artist is matched separately so
     * F finds "Fela Kuti" inside "Tony Allen / Fela Kuti".
     *
     * startsWith throughout, never contains: substring artist matching is what
     * puts "Prince" in front of Bonnie "Prince" Billy.
     */
    fun matchesPrefix(al: AlbumRecord, prefix: String): Boolean {
        if (prefix.isEmpty()) return true
        if (al.sortTitle.startsWith(prefix)) return true
        if (al.nTitle.startsWith(prefix)) return true
        if (al.artistNames.any { it.normalized.startsWith(prefix) }) return true
        return al.nArtist.startsWith(prefix)
    }

    /** Album titles played since [cutoff]. Title-keyed, as the plays table is. */
    fun playedTitlesSince(cutoff: Long): Set<String> =
        store.playsSince(cutoff).mapTo(HashSet()) { it.album.lowercase(Locale.ROOT).trim() }

    fun playedTitlesInLastMonths(months: Int): Set<String> =
        playedTitlesSince(System.currentTimeMillis() - months * MONTH_MS)

    // ------------------------------------------------------------------ query

    data class Query(
        val sort: String = "album",
        val desc: Boolean = false,
        val prefix: String = "",
        val played: String = "any",
        val genre: String? = null,
        val decade: Int? = null,
        val seed: Int = 0
    )

    fun sanitize(
        sort: String?,
        dir: String?,
        prefixRaw: String?,
        played: String?,
        genre: String?,
        decade: String?,
        seed: String?
    ) = Query(
        sort = if (sort in SORTS) sort!! else "album",
        desc = dir == "desc",
        prefix = prefix(prefixRaw),
        played = if (played in PLAYED_FILTERS) played!! else "any",
        genre = genre?.trim()?.takeIf { it.isNotEmpty() },
        decade = decade?.trim()?.removeSuffix("s")?.toIntOrNull(),
        seed = seed?.toIntOrNull() ?: 0
    )

    fun select(q: Query): List<AlbumRecord> {
        var list: List<AlbumRecord> = index.albums

        if (q.prefix.isNotEmpty()) list = list.filter { matchesPrefix(it, q.prefix) }

        if (q.decade != null) {
            val from = q.decade
            list = list.filter { val y = albumYearOf(it); y != null && y >= from && y < from + 10 }
        }

        if (q.genre != null) {
            val want = Normalize.text(q.genre)
            list = list.filter { al ->
                store.albumGenres(al.key).any { Normalize.text(it) == want }
            }
        }

        if (q.played != "any") {
            // "never" uses the whole history; "played" is its complement;
            // "6"/"12" mean "not in the last N months".
            val months = q.played.toIntOrNull()
            val seen = if (q.played == "never" || q.played == "played") playedTitlesSince(0)
            else playedTitlesInLastMonths(if (months != null && months > 0) months else 6)
            val want = q.played == "played"
            list = list.filter { (playKey(it) in seen) == want }
        }

        return order(list, q)
    }

    private fun order(list: List<AlbumRecord>, q: Query): List<AlbumRecord> {
        // Albums with no date are UNKNOWN, not date zero: they are held out of
        // the ordering entirely and appended, so reversing to newest-first
        // cannot float them to the top. "Recently added" needs this most —
        // Roon publishes no import date at all, so on an established library
        // the undated set starts out large.
        if (q.sort == "year" || q.sort == "added") {
            val dateOf: (AlbumRecord) -> Long? =
                if (q.sort == "year") { a -> albumYearOf(a)?.toLong() } else { a -> albumAddedOf(a) }
            val known = ArrayList<AlbumRecord>(list.size)
            val unknown = ArrayList<AlbumRecord>()
            for (al in list) (if (dateOf(al) == null) unknown else known) += al
            known.sortWith(
                compareBy<AlbumRecord> { dateOf(it) ?: 0L }.thenBy { it.sortTitle }
            )
            if (q.desc) known.reverse()
            unknown.sortBy { it.sortTitle }
            return known + unknown
        }

        val counts by lazy { store.playCounts() }
        val lastPlayed by lazy { store.lastPlayedAll() }

        val cmp: Comparator<AlbumRecord> = when (q.sort) {
            "artist" -> compareBy<AlbumRecord> { it.nArtist }.thenBy { it.sortTitle }
            "plays" -> compareBy<AlbumRecord> { counts[it.key] ?: 0 }.thenBy { it.sortTitle }
            "lastplayed" -> compareBy<AlbumRecord> { lastPlayed[it.key] ?: 0L }.thenBy { it.sortTitle }
            "random" -> compareBy { seededRank(it.nTitle + it.nArtist, q.seed) }
            else -> compareBy<AlbumRecord> { it.sortTitle }.thenBy { it.nArtist }
        }
        val out = list.sortedWith(cmp)
        // `dir` means the same thing for every sort: asc is the comparator's
        // own order, desc is reversed. The client picks the sensible default
        // direction per sort, so nothing is special-cased here.
        return if (q.desc) out.reversed() else out
    }

    // ------------------------------------------------------------------ picks

    /** [n] distinct albums drawn at random from [pool]. */
    fun sample(pool: List<AlbumRecord>, n: Int): List<AlbumRecord> {
        if (pool.isEmpty()) return emptyList()
        val want = minOf(n, pool.size)
        if (want == pool.size) return pool.shuffled()
        val picked = LinkedHashSet<Int>(want * 2)
        val rnd = ThreadLocalRandom.current()
        while (picked.size < want) picked += rnd.nextInt(pool.size)
        return picked.map { pool[it] }
    }

    /** Albums whose title has not been played in the last [months] months. */
    fun unplayed(months: Int): List<AlbumRecord> {
        val heard = playedTitlesInLastMonths(months)
        return index.albums.filter { playKey(it) !in heard }
    }

    /**
     * The same album for everyone, all day, changing at local midnight — and
     * withdrawn once it has actually been played today, because a suggestion
     * you have already taken is not a suggestion.
     */
    fun albumOfTheDay(now: Long = System.currentTimeMillis()): AlbumRecord? {
        val albums = index.albums
        if (albums.isEmpty()) return null
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val stamp = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}"
        val idx = Math.floorMod(fnv1a(stamp), albums.size)
        return albums[idx]
    }

    fun playedToday(al: AlbumRecord, now: Long = System.currentTimeMillis()): Boolean {
        val midnight = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return playKey(al) in playedTitlesSince(midnight)
    }

    /**
     * Recently played albums, newest first, mapped back onto library records so
     * each tile can be opened and replayed. History records a title and artist;
     * an album that has since left the library simply drops out of the row.
     */
    fun history(days: Int, max: Int): List<AlbumRecord> {
        val since = System.currentTimeMillis() - days * DAY_MS
        val out = LinkedHashMap<String, AlbumRecord>()
        for (row in store.playsSince(since)) {
            if (out.size >= max) break
            if (row.album.isEmpty()) continue
            val hit = index.relocate(row.album, row.artist)
                ?: index.relocate(row.album, null)
                ?: continue
            out.putIfAbsent(hit.key, hit)
        }
        return out.values.toList()
    }

    /** Decades that actually hold albums, newest first. */
    fun decades(): List<Pair<Int, Int>> {
        val counts = HashMap<Int, Int>()
        for (al in index.albums) {
            val y = albumYearOf(al) ?: continue
            val d = (y / 10) * 10
            counts[d] = (counts[d] ?: 0) + 1
        }
        return counts.entries.sortedByDescending { it.key }.map { it.key to it.value }
    }
}
