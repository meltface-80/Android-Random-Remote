package com.musicd.lite.library

import com.musicd.lite.str
import com.musicd.lite.store.Store
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
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
class LibraryView(
    private val index: AlbumIndex,
    private val store: Store,
    /** Where Focus's genres come from — Roon's own lists in the app, see Genres.kt. */
    private val genres: GenreSource = StoredGenres(store)
) {

    companion object {
        /** Where the day's album is kept — see albumOfTheDay. */
        const val KEY_AOTD = "album_of_the_day"

        val SORTS = listOf("album", "artist", "year", "added", "plays", "lastplayed", "random")
        // "6" and "12" ("not in the last N months") are gone: the plays table
        // only holds what this app watched happen, and it starts empty, so a
        // recency window was answering a question the app cannot know.
        val PLAYED_FILTERS = listOf("any", "never", "played")

        /**
         * The Focus facets this build can serve, in Rouen's ids. Rouen's
         * Source, Record label, Format, Sample rate, Bit depth and Channels
         * come from music files and streaming accounts a phone does not have,
         * so they are not offered — and a saved selection naming one narrows
         * nothing rather than emptying the wall for a reason nobody can see.
         */
        val FACET_IDS = listOf("genre", "decade", "letter", "added")
        private val FACET_LABELS = mapOf(
            "genre" to "Genre", "decade" to "Decade", "letter" to "Starts with", "added" to "Added in the last"
        )

        /** "Added in the last" windows, shortest first: value, label, days. */
        val ADDED_WINDOWS = listOf(
            Triple("7", "7 days", 7), Triple("30", "30 days", 30),
            Triple("90", "3 months", 90), Triple("365", "A year", 365)
        )

        /** Chips offered per facet; the commonest, as Rouen's sheet shows them. */
        const val FACET_CHIP_MAX = 40
        private const val FACET_VALUES_MAX = 50

        /**
         * One selected value, kept only in a form this facet could match. The
         * "!" of an exclusion survives; "1990s", the old page's decade, becomes
         * "1990".
         */
        fun facetValue(id: String, raw: String): String? {
            val t = raw.trim()
            val not = t.startsWith("!")
            val v = (if (not) t.substring(1) else t).trim()
            if (v.isEmpty()) return null
            val ok = when (id) {
                "genre" -> v.take(100)
                "decade" -> v.removeSuffix("s").toIntOrNull()?.takeIf { it in 1000..2990 && it % 10 == 0 }?.toString()
                "letter" -> v.uppercase(Locale.ROOT).takeIf { it.length == 1 && (it[0] in 'A'..'Z' || it == "#") }
                "added" -> v.takeIf { w -> ADDED_WINDOWS.any { it.first == w } }
                else -> null
            } ?: return null
            return if (not) "!$ok" else ok
        }

        /**
         * Does an album with [values] pass one facet's [selected]? Rouen's rule:
         * any included value will do, an excluded one always wins, and
         * excludes alone ("everything except Pop") need no include.
         */
        fun facetMatch(selected: List<String>, values: Collection<String>): Boolean {
            if (selected.isEmpty()) return true
            var wanted = false
            var sawInclude = false
            for (sel in selected) {
                if (sel.startsWith("!")) {
                    if (sel.substring(1) in values) return false
                } else {
                    sawInclude = true
                    if (sel in values) wanted = true
                }
            }
            return if (sawInclude) wanted else true
        }

        /** "The Wall" under W, as the A-Z wall files it; anything not a letter under #. */
        fun letterOf(al: AlbumRecord): String? {
            val c = al.sortTitle.firstOrNull()?.uppercaseChar() ?: return null
            return if (c in 'A'..'Z') c.toString() else "#"
        }

        const val PREFIX_MAX = 40
        private const val DAY_MS = 24L * 60 * 60 * 1000

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

    /**
     * Play history is keyed by album title alone, exactly as the table records
     * it. Public because the facet counts have to fold the identical way the
     * filter does — a second copy of this rule is how a chip ends up promising
     * a number the list does not deliver.
     */
    fun playKey(al: AlbumRecord): String = al.title.lowercase(Locale.ROOT).trim()

    fun albumYearOf(al: AlbumRecord): Int? = store.albumYear(al.key)

    /** Epoch millis the album first appeared in the library, or null. */
    fun albumAddedOf(al: AlbumRecord): Long? = store.firstSeen(al.key)

    /**
     * Every first-seen date in one read, with the first-scan marker dropped.
     *
     * A stored zero means "was already there when this app first looked", which
     * is not a date — [Store.firstSeen] answers null for it, and reading the
     * whole table in bulk has to apply the same rule or a sort by "recently
     * added" would file the entire original library at the epoch.
     */
    private fun firstSeenDates(): Map<String, Long> =
        store.firstSeenAll().filterValues { it > 0L }

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

    // ------------------------------------------------------------------ query

    data class Query(
        val sort: String = "album",
        val desc: Boolean = false,
        val prefix: String = "",
        val played: String = "any",
        /**
         * The Focus selection, by facet id: each a list of values, a value
         * starting "!" EXCLUDED rather than included (Rouen's tap-again-to-
         * invert). Only facets this build serves ([FACET_IDS]), only values it
         * could ever match — anything else narrows nothing.
         */
        val facets: Map<String, List<String>> = emptyMap(),
        val seed: Int = 0
    )

    fun sanitize(
        sort: String?,
        dir: String?,
        prefixRaw: String?,
        played: String?,
        genre: String?,
        decade: String?,
        seed: String?,
        facets: Map<String, List<String>> = emptyMap()
    ): Query {
        val f = LinkedHashMap<String, List<String>>()
        for (id in FACET_IDS) {
            val raw = ArrayList<String>()
            facets[id]?.let { raw += it }
            // The single-value parameters the page sent before Focus came back.
            if (id == "genre" && genre != null) raw += genre
            if (id == "decade" && decade != null) raw += decade
            val clean = raw.mapNotNull { facetValue(id, it) }.distinct().take(FACET_VALUES_MAX)
            if (clean.isNotEmpty()) f[id] = clean
        }
        return Query(
            sort = if (sort in SORTS) sort!! else "album",
            desc = dir == "desc",
            prefix = prefix(prefixRaw),
            played = if (played in PLAYED_FILTERS) played!! else "any",
            facets = f,
            seed = seed?.toIntOrNull() ?: 0
        )
    }

    /**
     * How one facet reads an album, with its side table read once up front.
     * Genres are compared folded, as everywhere else in this app.
     */
    private fun facetValuesFn(id: String, now: Long = System.currentTimeMillis()): ((AlbumRecord) -> Collection<String>)? =
        when (id) {
            "decade" -> {
                val years = store.albumYears()
                val fn: (AlbumRecord) -> Collection<String> = { al ->
                    years[al.key]?.let { listOf(((it / 10) * 10).toString()) } ?: emptyList()
                }
                fn
            }
            "letter" -> { al -> letterOf(al)?.let { listOf(it) } ?: emptyList() }
            "added" -> {
                val dates = firstSeenDates()
                val fn: (AlbumRecord) -> Collection<String> = { al ->
                    val ts = dates[al.key]
                    if (ts == null) emptyList()
                    else ADDED_WINDOWS.filter { now - ts <= it.third * DAY_MS }.map { it.first }
                }
                fn
            }
            else -> null
        }

    /**
     * Genre, read through the selection: each genre named (included or
     * excluded) once, as the set of albums filed under it. An album's "values"
     * are the named genres it is in — exactly what facetMatch compares. A genre
     * whose albums can't be read right now holds nothing: an include of it
     * matches nothing, an exclude of it removes nothing.
     */
    private fun genreValuesFn(selected: List<String>): (AlbumRecord) -> Collection<String> {
        val names = selected.map { it.removePrefix("!") }.distinct()
        val sets = names.associateWith { genres.members(it) ?: emptySet() }
        return { al -> names.filter { sets.getValue(it).contains(al.key) } }
    }

    /**
     * The Focus sheet's vocabulary: which values each facet actually has, with
     * counts, so the sheet never offers one that would return nothing — in the
     * shape Rouen's page reads ({value, label, count}, total_values), and with
     * how many albums each facet KNOWS about, because none of it comes from
     * Roon and the sheet says so rather than leaving the numbers not to add up.
     */
    fun facets(now: Long = System.currentTimeMillis()): JSONObject {
        val albums = index.albums
        val out = JSONArray()
        val coverage = JSONObject()
        for (id in FACET_IDS) {
            if (id == "genre") {
                // Roon's own count for each, commonest first.
                val gs = (genres.genres() ?: emptyList()).filter { it.second > 0 }
                    .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                genres.known()?.let { coverage.put("genre", it) }
                if (gs.isEmpty()) continue
                val values = JSONArray()
                for ((name, n) in gs.take(FACET_CHIP_MAX)) {
                    values.put(JSONObject().put("value", name).put("label", name).put("count", n))
                }
                out.put(
                    JSONObject().put("id", id).put("label", FACET_LABELS.getValue(id))
                        .put("total_values", gs.size).put("values", values)
                )
                continue
            }
            val counts = LinkedHashMap<String, Int>()
            var known = 0
            val valuesOf = facetValuesFn(id, now) ?: continue
            for (al in albums) {
                val vs = valuesOf(al)
                if (vs.isNotEmpty()) known++
                for (v in vs) counts[v] = (counts[v] ?: 0) + 1
            }
            val ordered: List<String> = when (id) {
                "decade" -> counts.keys.sortedByDescending { it.toInt() }
                "added" -> ADDED_WINDOWS.map { it.first }.filter { it in counts }
                else -> counts.keys.sortedWith(compareByDescending<String> { counts[it] ?: 0 }.thenBy { it })
            }
            // Added: every album with a date this app worked out, whether or not
            // it falls in a window — the sheet's "N of M albums" is about that.
            if (id == "added") {
                val dates = firstSeenDates()
                known = albums.count { it.key in dates }
            }
            if (id != "letter") coverage.put(id, known)
            if (ordered.isEmpty()) continue
            val values = JSONArray()
            for (v in ordered.take(FACET_CHIP_MAX)) {
                val label = when (id) {
                    "decade" -> "${v}s"
                    "added" -> ADDED_WINDOWS.first { it.first == v }.second
                    else -> v
                }
                values.put(JSONObject().put("value", v).put("label", label).put("count", counts[v]))
            }
            out.put(
                JSONObject().put("id", id).put("label", FACET_LABELS.getValue(id))
                    .put("total_values", ordered.size).put("values", values)
            )
        }
        return JSONObject()
            .put("total", albums.size)
            .put("facets", out)
            .put("coverage", coverage)
            .put("hasPlays", playedTitlesSince(0).isNotEmpty())
            .put("played", JSONArray(PLAYED_FILTERS))
            .put("sorts", JSONArray(SORTS))
    }

    fun select(q: Query): List<AlbumRecord> {
        var list: List<AlbumRecord> = index.albums

        // Narrow on the free text FIRST. It is the only filter that needs no
        // side table, and everything below is proportional to what survives it.
        if (q.prefix.isNotEmpty()) list = list.filter { matchesPrefix(it, q.prefix) }

        // ONE read of each side table, not one per album. These used to be
        // store.albumYear(key) and store.albumGenres(key) inside the filter,
        // which is a SQLite round trip per album — fifty thousand of them to
        // draw one page of a large library.
        // Each facet reads its side table ONCE, then matches every album
        // against the selection with Rouen's rule (facetMatch).
        for ((id, selected) in q.facets) {
            val valuesOf = (if (id == "genre") genreValuesFn(selected) else facetValuesFn(id)) ?: continue
            list = list.filter { facetMatch(selected, valuesOf(it)) }
        }

        if (q.played != "any") {
            // The whole history, not a window: "played" means this app has seen
            // it play at least once, and "never" is its complement.
            val seen = playedTitlesSince(0)
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
            // Read the table ONCE and resolve every date up front. This was a
            // lambda that hit SQLite per call and was then handed to a
            // comparator, so a sort did a database round trip per COMPARISON —
            // n log n queries to order n albums.
            val dates: Map<String, Long> =
                if (q.sort == "year") store.albumYears().mapValues { it.value.toLong() }
                else firstSeenDates()
            val known = ArrayList<AlbumRecord>(list.size)
            val unknown = ArrayList<AlbumRecord>()
            for (al in list) (if (dates[al.key] == null) unknown else known) += al
            known.sortWith(
                compareBy<AlbumRecord> { dates[it.key] ?: 0L }.thenBy { it.sortTitle }
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
            // Precomputed, because a comparator selector runs per COMPARISON:
            // inline, this concatenated two strings and re-hashed every
            // character of the result some n log n times.
            "random" -> {
                val ranks = HashMap<String, Int>(list.size * 2)
                for (al in list) ranks[al.key] = seededRank(al.nTitle + al.nArtist, q.seed)
                compareBy { ranks[it.key] ?: 0 }
            }
            else -> compareBy<AlbumRecord> { it.sortTitle }.thenBy { it.nArtist }
        }
        val out = list.sortedWith(cmp)
        // `dir` means the same thing for every sort: asc is the comparator's
        // own order, desc is reversed. The client picks the sensible default
        // direction per sort, so nothing is special-cased here.
        return if (q.desc) out.reversed() else out
    }

    // ------------------------------------------------------------------ picks

    /**
     * [n] distinct albums drawn at random from [pool].
     *
     * With a [seed] the draw is DETERMINISTIC: the same seed gives the same
     * albums, in the same order, for as long as the library holds them. That
     * is what lets a Home row show the same ten all day without the page
     * having to remember which ten — it asks with today's date and gets
     * today's answer, on any device, after any restart, with a cleared cache.
     * Ordering by [seededRank] rather than seeding a Random because that is
     * already the app's one stable-shuffle rule, tested, and used by the
     * library wall's own random sort.
     *
     * Without a seed it is a fresh draw every call, which is what the reshuffle
     * button wants.
     */
    fun sample(pool: List<AlbumRecord>, n: Int, seed: Int? = null): List<AlbumRecord> {
        if (pool.isEmpty()) return emptyList()
        val want = minOf(n, pool.size)
        if (seed != null) {
            // Ranked once each, THEN sorted. sortedBy runs its selector on
            // every comparison, so hashing the key inline re-walked every
            // album's characters some n log n times to hand back ten tiles.
            return pool.map { seededRank(it.key, seed) to it }
                .sortedBy { it.first }
                .take(want)
                .map { it.second }
        }
        if (want == pool.size) return pool.shuffled()
        val picked = LinkedHashSet<Int>(want * 2)
        val rnd = ThreadLocalRandom.current()
        while (picked.size < want) picked += rnd.nextInt(pool.size)
        return picked.map { pool[it] }
    }

    /**
     * The day's album, the same for every device from 00:01 until it is
     * played. Chosen ONCE, at the first ask of the day, and kept as an album
     * IDENTITY (Rouen v1.8.74). It used to be worked out afresh on every ask as
     * hash(date) % library size, so a scan that added or removed one album put
     * a different album there mid-day — and one already played came back as a
     * "new" one. Now only a new day, or the album leaving the library, chooses
     * again. The choice is made over the library ordered by identity, so it
     * does not depend on the order Roon listed it in.
     *
     * Kept in the store directly rather than through Settings: it is
     * bookkeeping, and must not move the live `settings` revision.
     */
    fun albumOfTheDay(now: Long = System.currentTimeMillis()): AlbumRecord? {
        val albums = index.albums
        if (albums.isEmpty()) return null
        val day = aotdDay(now)
        synchronized(aotdLock) {
            aotdMemo?.let { (gen, d, al) -> if (gen == index.generation && d == day) return al }
            val kept = store.setting(KEY_AOTD)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            val keptAlbum = if (kept != null && kept.str("day") == day) {
                val key = kept.str("key")
                albums.firstOrNull { it.key == key }
            } else null
            val pick = keptAlbum ?: albums.sortedBy { it.key }.let { byKey ->
                byKey[Math.floorMod(fnv1a(day), byKey.size)]
            }.also { chosen ->
                runCatching {
                    store.putSetting(
                        KEY_AOTD,
                        org.json.JSONObject().put("day", day).put("key", chosen.key)
                            .put("title", chosen.title).put("subtitle", chosen.subtitle).toString()
                    )
                }
            }
            aotdMemo = Triple(index.generation, day, pick)
            return pick
        }
    }

    private val aotdLock = Any()

    /** The resolved pick for this snapshot and day, so a Home visit does not re-sort the library. */
    private var aotdMemo: Triple<Long, String, AlbumRecord>? = null

    /**
     * Album of the day's day: the local date a minute ago, so the pick turns
     * at 00:01 rather than midnight (Rouen's rule, and LiveState's `aotd`).
     */
    fun aotdDay(now: Long = System.currentTimeMillis()): String =
        java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now - 60_000L), java.time.ZoneId.systemDefault())
            .toString()

    /** When that day began: 00:01 of it. */
    fun aotdDayStart(now: Long = System.currentTimeMillis()): Long =
        java.time.LocalDate.parse(aotdDay(now)).atTime(0, 1)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Played since today's 00:01, on any zone — then it is gone until the next one. */
    fun playedToday(al: AlbumRecord, now: Long = System.currentTimeMillis()): Boolean =
        playKey(al) in playedTitlesSince(aotdDayStart(now))

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
        // One read of the years table. Per album it was a query each, and the
        // facet sheet asks for this every time it opens.
        val years = store.albumYears()
        val counts = HashMap<Int, Int>()
        for (al in index.albums) {
            val y = years[al.key] ?: continue
            val d = (y / 10) * 10
            counts[d] = (counts[d] ?: 0) + 1
        }
        return counts.entries.sortedByDescending { it.key }.map { it.key to it.value }
    }
}
