package com.musicd.lite.meta

import com.musicd.lite.Log
import com.musicd.lite.str
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneOffset
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Deezer's public catalogue API — no key, no account — for the two features
 * that need to know about records OUTSIDE the library: "If you like this" under
 * the share card, and Discover. Rouen uses the same three calls.
 *
 * The reading rules are ports of Rouen's lib/similar.js and lib/newreleases.js,
 * kept as pure functions over the JSON so they are tested without a network.
 */
class Deezer(
    private val http: OkHttpClient,
    private val userAgent: String,
    /** Tests shorten it; nothing else should. */
    private val gapMs: Long = GAP_MS
) {

    companion object {
        private const val TAG = "Deezer"
        private const val API = "https://api.deezer.com"

        /** Acts "If you like this" suggests. */
        const val WANTED = 3

        /** Artist-search rows considered, and how many are worth a second call. */
        const val SEARCH_ROWS = 10
        const val CANDIDATES = 3

        /** Deezer allows 50 requests in 5 seconds; this stays well inside it. */
        const val GAP_MS = 250L

        private val COMBINING = Regex("\\p{Mn}+")
        private val NON_ALNUM = Regex("[^a-z0-9]+")

        /** Rouen's normalize: lower case, accents off, punctuation to one space. */
        fun normalize(s: String?): String {
            if (s.isNullOrEmpty()) return ""
            val folded = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
            return COMBINING.replace(folded, "").replace(NON_ALNUM, " ").trim().replace(Regex("\\s+"), " ")
        }

        /**
         * Whether two artist names name the same act: one contains the other on
         * word boundaries ("Eno" in "Brian Eno"), or they differ by a leading
         * "The". Permissive on purpose — this checks a SEARCH result against the
         * name searched for.
         */
        fun namesOverlap(a: String?, b: String?): Boolean {
            val x = normalize(a)
            val y = normalize(b)
            if (x.isEmpty() || y.isEmpty()) return false
            if (" $x ".contains(" $y ") || " $y ".contains(" $x ")) return true
            return x.removePrefix("the ") == y.removePrefix("the ")
        }

        /**
         * A title reduced to what two catalogues agree on: every character in
         * order, "&" spelled out, no spaces or punctuation — Roon's "Sgt.
         * Pepper's" and Deezer's "Sgt. Peppers" are the same record.
         */
        fun titleKey(s: String?): String = normalize((s ?: "").replace("&", " and ")).replace(" ", "")

        private fun yearOf(date: String?, maxAhead: Int): Int? {
            val y = Regex("^(\\d{4})").find((date ?: "").trim())?.groupValues?.get(1)?.toIntOrNull() ?: return null
            return if (y in 1900..(LocalDate.now().year + maxAhead)) y else null
        }

        // ------------------------------------------------------------ similar

        /** Search rows that are really the act searched for, exact names first, then the most followed. */
        fun readArtists(json: JSONObject?, artist: String): List<Artist> {
            val data = json?.optJSONArray("data") ?: return emptyList()
            val out = ArrayList<Artist>()
            for (i in 0 until data.length()) {
                val a = data.optJSONObject(i) ?: continue
                val id = if (a.isNull("id")) "" else a.opt("id")?.toString().orEmpty()
                val name = a.str("name").trim()
                if (id.isEmpty() || name.isEmpty() || !namesOverlap(name, artist)) continue
                out += Artist(id, name, a.optInt("nb_fan", 0), normalize(name) == normalize(artist))
            }
            return out.sortedWith(compareByDescending<Artist> { it.exact }.thenByDescending { it.fans })
        }

        fun readRelated(json: JSONObject?, wanted: Int = WANTED): List<Related> {
            val data = json?.optJSONArray("data") ?: return emptyList()
            val seen = HashSet<String>()
            val out = ArrayList<Related>()
            for (i in 0 until data.length()) {
                val a = data.optJSONObject(i) ?: continue
                val id = if (a.isNull("id")) "" else a.opt("id")?.toString().orEmpty()
                val name = a.str("name").trim()
                if (id.isEmpty() || name.isEmpty() || !seen.add(id)) continue
                out += Related(id, name, a.str("picture_medium").ifEmpty { a.str("picture") }.ifEmpty { null })
                if (out.size >= wanted) break
            }
            return out
        }

        /** An act's EARLIEST full album — the place to start with someone new. */
        fun readFirstAlbum(json: JSONObject?): FirstAlbum? {
            val data = json?.optJSONArray("data") ?: return null
            var best: FirstAlbum? = null
            for (i in 0 until data.length()) {
                val a = data.optJSONObject(i) ?: continue
                if (a.str("record_type").lowercase() != "album") continue
                val title = a.str("title").trim()
                if (title.isEmpty()) continue
                val year = yearOf(a.str("release_date"), 1) ?: continue
                if (best == null || year < best.year) {
                    best = FirstAlbum(title, year, a.str("cover_medium").ifEmpty { a.str("cover") }.ifEmpty { null })
                }
            }
            return best
        }

        // ------------------------------------------------------- new releases

        /** At most this many records from any one act. */
        const val WANTED_PER_ARTIST = 2

        /** Below this many tracks a "single" is a single, whatever it calls itself. */
        const val MIN_ALBUM_TRACKS = 5

        private val EDITION_WORDS = Regex(
            "\\b(remaster|remastered|remasters|reissue|reissued|deluxe|expanded|extended|" +
                "anniversary|edition|version|mono|stereo|bonus|collector|collectors|special|" +
                "super|legacy|definitive)\\b",
            RegexOption.IGNORE_CASE
        )

        /** "Album (Deluxe Edition)" -> base "Album", and whether an edition word was stripped. */
        fun stripEdition(title: String): Pair<String, Boolean> {
            var s = title.trim()
            var edition = false
            while (true) {
                val m = Regex("\\s*[(\\[]([^()\\[\\]]*)[)\\]]\\s*$").find(s) ?: break
                val rest = s.substring(0, m.range.first).trim()
                if (rest.isEmpty()) break
                if (EDITION_WORDS.containsMatchIn(m.groupValues[1])) edition = true
                s = rest
            }
            Regex("\\s+[-–—]\\s+([^-–—]*)$").find(s)?.let { m ->
                if (EDITION_WORDS.containsMatchIn(m.groupValues[1])) {
                    val rest = s.substring(0, m.range.first).trim()
                    if (rest.isNotEmpty()) { s = rest; edition = true }
                }
            }
            return s to edition
        }

        /** Midday UTC of a YYYY-MM-DD date, or null — a bare year is not a release DAY. */
        fun dateMs(date: String?): Long? {
            val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find((date ?: "").trim()) ?: return null
            if (yearOf(date, 2) == null) return null
            val (y, mo, d) = m.destructured
            return runCatching {
                LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()
        }

        /** One listing row, if it is a full-length album with a usable day; else null. */
        fun classify(a: JSONObject?): Release? {
            if (a == null) return null
            if (a.str("record_type").lowercase() != "album") return null
            val tracks = if (a.has("nb_tracks") && !a.isNull("nb_tracks")) a.optInt("nb_tracks", 0) else 0
            if (tracks in 1 until MIN_ALBUM_TRACKS) return null
            val title = a.str("title").trim()
            if (title.isEmpty()) return null
            val date = a.str("release_date")
            val ts = dateMs(date) ?: return null
            val (base, edition) = stripEdition(title)
            return Release(
                id = if (a.isNull("id")) null else a.opt("id")?.toString(),
                title = title, baseKey = titleKey(base), edition = edition,
                date = date.take(10), ts = ts, cover = coverOf(a)
            )
        }

        /** Deezer names its covers several ways; the hash builds one when none is named. */
        fun coverOf(a: JSONObject): String? {
            for (k in listOf("cover_medium", "cover_big", "cover_small", "cover_xl", "cover")) {
                a.str(k).takeIf { it.isNotEmpty() }?.let { return it }
            }
            val md5 = a.str("md5_image")
            if (md5.isNotEmpty()) {
                return "https://cdn-images.dzcdn.net/images/cover/" + ShareLinks.encodeUriComponent(md5) +
                    "/250x250-000000-80-0-0.jpg"
            }
            return null
        }

        fun readArtistAlbums(json: JSONObject?): List<Release> {
            val data = json?.optJSONArray("data") ?: return emptyList()
            return (0 until data.length()).mapNotNull { classify(data.optJSONObject(it)) }.sortedByDescending { it.ts }
        }

        /** An edition of a record that already came out earlier is a reissue, not a new record. */
        fun isReissue(row: Release, all: List<Release>): Boolean {
            if (!row.edition || row.baseKey.isEmpty()) return false
            return all.any { it !== row && it.baseKey == row.baseKey && it.ts < row.ts }
        }

        fun owns(owned: Set<String>, r: Release): Boolean =
            owned.isNotEmpty() && (r.baseKey in owned || titleKey(r.title) in owned)

        /**
         * The new records in an act's listing: released in the window, not
         * owned, not a reissue — one per record (the plain release, or the
         * earliest edition when there is no plain one), newest first.
         */
        fun pickNewReleases(albums: List<Release>, now: Long, sinceMs: Long, owned: Set<String>,
                            wanted: Int = WANTED_PER_ARTIST): List<Release> {
            val inWindow = albums.filter { it.ts in sinceMs..now && !owns(owned, it) && !isReissue(it, albums) }
            val buckets = LinkedHashMap<String, Pair<LinkedHashMap<String, Release>, MutableList<Release>>>()
            for (a in inWindow) {
                val b = buckets.getOrPut(a.baseKey) { LinkedHashMap<String, Release>() to ArrayList() }
                if (a.edition) b.second += a else b.first.putIfAbsent(titleKey(a.title), a)
            }
            val out = ArrayList<Release>()
            for ((plain, editions) in buckets.values) {
                if (plain.isNotEmpty()) out += plain.values
                else if (editions.isNotEmpty()) out += editions.minByOrNull { it.ts }!!
            }
            return out.sortedByDescending { it.ts }.take(wanted.coerceAtLeast(0))
        }

        /**
         * The acts a person actually plays, ranked by how many DIFFERENT days
         * they were played on — one long session of an album is not the same
         * signal as coming back to an act for weeks. "Various Artists" is not
         * an act.
         */
        fun playedArtists(rows: List<Pair<String, Long>>, limit: Int, split: (String) -> String): List<Seed> {
            data class Acc(val name: String, val days: HashSet<Long> = HashSet(), var last: Long = 0)
            val seen = LinkedHashMap<String, Acc>()
            for ((artist, ts) in rows) {
                val name = split(artist).trim()
                if (name.isEmpty()) continue
                val key = normalize(name)
                if (key.isEmpty() || key == "various artists" || key == "various" || key == "va") continue
                val e = seen.getOrPut(key) { Acc(name) }
                e.days += Math.floorDiv(ts, 86_400_000L)
                if (ts > e.last) e.last = ts
            }
            return seen.values.map { Seed(it.name, it.days.size, it.last) }
                .sortedWith(compareByDescending<Seed> { it.days }.thenByDescending { it.last })
                .take(limit.coerceAtLeast(0))
        }
    }

    data class Artist(val id: String, val name: String, val fans: Int, val exact: Boolean)

    data class Related(val id: String, val name: String, val picture: String?)

    data class FirstAlbum(val title: String, val year: Int, val cover: String?)

    data class Seed(val name: String, val days: Int, val last: Long)

    data class Release(
        val id: String?,
        val title: String,
        val baseKey: String,
        val edition: Boolean,
        val date: String,
        val ts: Long,
        val cover: String?
    )

    private var last = 0L

    /** One GET against Deezer, spaced out, or null on any failure — these features fail quietly. */
    @Synchronized
    fun get(path: String): JSONObject? {
        val wait = gapMs - (System.currentTimeMillis() - last)
        if (wait > 0) runCatching { Thread.sleep(wait) }
        last = System.currentTimeMillis()
        val request = Request.Builder().url(API + path)
            .header("User-Agent", userAgent).header("Accept", "application/json").build()
        return try {
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return null
                val body = r.body?.string()?.takeIf { it.isNotEmpty() } ?: return null
                val json = JSONObject(body)
                // Deezer answers errors with a 200 and an "error" object.
                if (json.has("error")) {
                    Log.d(TAG, "$path -> ${json.optJSONObject("error")?.str("message")}")
                    null
                } else json
            }
        } catch (e: Exception) {
            Log.d(TAG, "$path failed: ${e.message}")
            null
        }
    }

    fun searchArtist(name: String): JSONObject? =
        get("/search/artist?limit=$SEARCH_ROWS&q=" + ShareLinks.encodeUriComponent(name))

    fun related(artistId: String, limit: Int = WANTED): JSONObject? =
        get("/artist/" + ShareLinks.encodeUriComponent(artistId) + "/related?limit=$limit")

    fun albums(artistId: String): JSONObject? =
        get("/artist/" + ShareLinks.encodeUriComponent(artistId) + "/albums?limit=50")

    data class Act(val name: String, val id: String, val album: String?, val year: Int?, val cover: String?)

    private val similarCache = TtlCache<String, List<Act>>(24L * 60 * 60 * 1000, 300)

    /**
     * Three acts like [artist], each with their earliest full album — "If you
     * like this". Cached a day per artist: it is a suggestion, and the share
     * card should not cost five calls every time it opens.
     */
    fun similarActs(artist: String): List<Act> {
        val key = normalize(artist)
        if (key.isEmpty()) return emptyList()
        similarCache.peek(key)?.let { return it }
        var acts: List<Act> = emptyList()
        val candidates = readArtists(searchArtist(artist), artist)
        for (cand in candidates.take(CANDIDATES)) {
            val rel = readRelated(related(cand.id))
            if (rel.isEmpty()) continue
            acts = rel.map { r ->
                val first = readFirstAlbum(albums(r.id))
                Act(r.name, r.id, first?.title, first?.year, first?.cover ?: r.picture)
            }
            break
        }
        similarCache.put(key, acts)
        return acts
    }
}
