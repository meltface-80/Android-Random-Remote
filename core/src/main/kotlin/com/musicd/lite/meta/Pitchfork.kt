package com.musicd.lite.meta

import com.musicd.lite.Log
import com.musicd.lite.library.Normalize
import com.musicd.lite.str
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Pitchfork's album-review listings.
 *
 * This carries no review text and never will: the written review is Pitchfork's
 * and the client links out to pitchfork.com to read it. What is served is the
 * listing — title, artist, score, Best New Music, cover, date, and the URL —
 * which is what the cards need to render and what makes "read it on
 * pitchfork.com" a link the user can follow.
 *
 * Two sources. The listing page is primary, because it carries the score and
 * the Best New Music flag; the RSS feed is the fallback for the Latest tab when
 * the page is unavailable or its shape has moved, and gives covers and titles
 * with the artist derived from the review slug. Best New Music has no feed, so
 * it has only the one source.
 */
class Pitchfork(private val http: OkHttpClient, private val userAgent: String) {

    data class Item(
        val url: String,
        val album: String,
        val artist: String?,
        val cover: String?,
        val score: Double?,
        val isBestNewMusic: Boolean,
        val date: String?
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("url", url)
            .put("album", album)
            .put("artist", artist ?: JSONObject.NULL)
            .put("cover", cover ?: JSONObject.NULL)
            .put("score", score ?: JSONObject.NULL)
            .put("isBestNewMusic", isBestNewMusic)
            .put("date", date ?: JSONObject.NULL)
    }

    private val gate = RateGate(INTERVAL_MS)
    private val cache = TtlCache<String, List<Item>>(LIST_TTL_MS, 4)

    /**
     * One build per type at a time. Concurrent misses — a tab opening while a
     * search runs — share a single fetch instead of each hitting Pitchfork.
     */
    private val inFlight = ConcurrentHashMap<String, Any>()

    fun reviews(type: String): List<Item> {
        val key = if (type == "best") "best" else "latest"
        cache.peek(key)?.let { return it }
        val lock = inFlight.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            cache.peek(key)?.let { return it }
            val built = build(key)
            cache.put(key, built)
            return built
        }
    }

    private fun build(type: String): List<Item> {
        if (type == "best") {
            return newestFirst(listing("/reviews/best/albums/").filter { it.album.isNotEmpty() })
        }
        val fromListing = runCatching { listing("/reviews/albums/") }
            .onFailure { Log.d(TAG, "listing failed: ${it.message}") }
            .getOrDefault(emptyList())
            .filter { it.album.isNotEmpty() }
        if (fromListing.isNotEmpty()) return newestFirst(fromListing)
        // RSS document order is already newest-first.
        return rss().filter { it.album.isNotEmpty() }
    }

    /** Lexicographic compare is correct for ISO dates; undated items go last. */
    private fun newestFirst(items: List<Item>): List<Item> =
        items.sortedWith(compareByDescending { it.date ?: "" })

    // ---------------------------------------------------------- listing page

    private fun listing(path: String): List<Item> {
        val html = gate.run { text(HOST + path) } ?: return emptyList()
        val raw = extractPreloadedState(html) ?: run {
            Log.d(TAG, "no preloaded state in $path")
            return emptyList()
        }
        val state = runCatching { JSONObject(raw) }.getOrElse {
            Log.d(TAG, "state parse failed: ${it.message}")
            return emptyList()
        }
        return collectReviewItems(state)
    }

    /**
     * `window.__PRELOADED_STATE__ = {...}`, found by matching braces. A greedy
     * regex cannot balance braces reliably across a couple of megabytes.
     */
    internal fun extractPreloadedState(html: String): String? {
        val marker = html.indexOf("__PRELOADED_STATE__")
        if (marker == -1) return null
        val start = html.indexOf('{', marker)
        if (start == -1) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> if (--depth == 0) return html.substring(start, i + 1)
            }
        }
        return null
    }

    /**
     * Walk the state and collect review listings.
     *
     * Matched on contentType + ratingValue + url rather than a fixed path, so a
     * container reshuffle on Pitchfork's side does not silently empty the list.
     */
    internal fun collectReviewItems(state: JSONObject): List<Item> {
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<Any>()
        stack.push(state)
        var guard = 0

        while (stack.isNotEmpty() && guard++ < WALK_GUARD) {
            when (val node = stack.pop()) {
                is JSONArray -> for (i in 0 until node.length()) {
                    node.opt(i)?.takeIf { it is JSONObject || it is JSONArray }?.let(stack::push)
                }

                is JSONObject -> {
                    val rating = node.optJSONObject("ratingValue")
                    val url = node.str("url")
                    if (node.str("contentType") == "review" && rating != null && url.isNotEmpty()) {
                        val full = (if (url.startsWith("http")) url else HOST + url)
                            .substringBefore('?').substringBefore('#')
                        if (seen.add(full)) {
                            // dangerousHed is HTML; source.hed is a
                            // markdown-ish fallback consulted only when
                            // stripping leaves nothing.
                            var album = stripHtml(node.str("dangerousHed")).trim()
                            if (album.isEmpty()) {
                                album = node.optJSONObject("source")?.str("hed")
                                    ?.replace("*", "")?.trim().orEmpty()
                            }
                            val score = rating.str("score").toDoubleOrNull()
                            out += Item(
                                url = full,
                                album = album,
                                artist = node.optJSONObject("subHed")?.str("name")?.trim()
                                    ?.takeIf { it.isNotEmpty() },
                                cover = listingCover(node),
                                score = score,
                                isBestNewMusic = rating.optBoolean("isBestNewMusic") ||
                                    rating.optBoolean("isBestNewReissue"),
                                date = node.str("pubDate").takeIf { it.isNotEmpty() }
                            )
                        }
                    }
                    for (key in node.keys()) {
                        node.opt(key)?.takeIf { it is JSONObject || it is JSONArray }?.let(stack::push)
                    }
                }
            }
        }
        return out
    }

    /**
     * Square cover from a listing item. lg (~1280px) is plenty for the largest
     * tile without pulling xxl; the rest are availability fallbacks.
     */
    private fun listingCover(node: JSONObject): String? {
        val sources = node.optJSONObject("image")?.optJSONObject("sources") ?: return null
        for (size in listOf("lg", "xxl", "md", "sm")) {
            sources.optJSONObject(size)?.str("url")?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------- RSS

    internal fun rss(): List<Item> {
        val xml = gate.run { text("$HOST/feed/feed-album-reviews/rss") } ?: return emptyList()
        val out = ArrayList<Item>()
        for (block in Regex("<item\\b[\\s\\S]*?</item>", RegexOption.IGNORE_CASE).findAll(xml)) {
            val item = block.value
            fun pick(pattern: String): String? =
                Regex(pattern, RegexOption.IGNORE_CASE).find(item)?.groupValues?.get(1)?.let(::unCdata)

            val link = pick("<link>([\\s\\S]*?)</link>") ?: continue
            if (!link.contains("/reviews/albums/")) continue
            // stripHtml decodes entities itself; decoding first would let an
            // escaped "&lt;em&gt;" become a strippable tag and lose real text.
            val album = stripHtml(pick("<title>([\\s\\S]*?)</title>").orEmpty()).trim()
            if (album.isEmpty()) continue
            val url = link.substringBefore('?').substringBefore('#')
            out += Item(
                url = url,
                album = album,
                artist = artistFromReviewUrl(url, album),
                cover = Regex("<media:thumbnail[^>]*\\burl=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(item)?.groupValues?.get(1),
                score = null,
                isBestNewMusic = false,
                date = pick("<pubDate>([\\s\\S]*?)</pubDate>")
            )
        }
        return out
    }

    /**
     * Best-effort artist from a review URL, for the RSS path which has no
     * artist field. The slug is "<artist>-<album>", so the known album slug is
     * stripped and what remains is title-cased. Casing is approximate.
     */
    internal fun artistFromReviewUrl(url: String, albumTitle: String): String? {
        val match = Regex("/reviews/albums/([^/?#]+)").find(url) ?: return null
        var slug = match.groupValues[1]
        val albumSlug = slugify(albumTitle)
        if (albumSlug.isNotEmpty() && slug.endsWith("-$albumSlug")) {
            slug = slug.dropLast(albumSlug.length + 1)
        }
        val words = slug.split("-").filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        return words.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    /**
     * The score for one album, from its own review page.
     *
     * The listing endpoints only carry the most recent reviews, so an album
     * from 1994 is never in them — the album view needs a direct lookup, and
     * Pitchfork's review URLs are built from slugs
     * (`/reviews/albums/<artist>-<album>/`), so one can be constructed rather
     * than searched for.
     *
     * NO review text is returned, here or anywhere else in this class: the
     * score, the Best New Music flag and the URL to go and read it. That is
     * the same line the original draws, and it is deliberate.
     *
     * A constructed URL can land on a real page for the WRONG record when two
     * artists share an album title, so the artist named on the page has to
     * agree with the one asked about before anything is returned.
     */
    fun reviewFor(title: String, artist: String): Item? {
        if (title.isBlank() || artist.isBlank()) return null
        val artistSlug = slugify(artist)
        val albumSlug = slugify(title)
        if (artistSlug.isEmpty() || albumSlug.isEmpty()) return null
        val url = "https://pitchfork.com/reviews/albums/$artistSlug-$albumSlug/"
        val html = text(url) ?: return null
        return reviewFromPage(html, url, title, artist)
    }

    /**
     * Pulls the rating out of a review page's JSON-LD.
     *
     * Split out from the fetch so the parsing is testable without a network:
     * the shape of Pitchfork's markup is the part that can silently change.
     */
    internal fun reviewFromPage(html: String, url: String, title: String, artist: String): Item? {
        val score = jsonLdRating(html) ?: return null
        // A URL built from slugs can resolve to a different act's record of the
        // same name. The page has to name the artist we asked about.
        val onPage = artistFromReviewUrl(url, title) ?: return null
        if (!Normalize.text(onPage).equals(Normalize.text(artist), ignoreCase = true) &&
            !Normalize.sortKey(Normalize.text(onPage))
                .contains(Normalize.sortKey(Normalize.text(artist)))
        ) {
            return null
        }
        return Item(
            url = url,
            album = title,
            artist = artist,
            cover = null,
            score = score,
            isBestNewMusic = BNM.containsMatchIn(html),
            date = null
        )
    }

    /** `"ratingValue": 8.7` inside any of the page's JSON-LD blocks. */
    private fun jsonLdRating(html: String): Double? =
        RATING.find(html)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }

    internal fun slugify(s: String): String = s.lowercase()
        .replace(Regex("['‘’]"), "")
        .replace(Regex("[^a-z0-9\\s-]"), " ")
        .replace(Regex("\\s+"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')

    // -------------------------------------------------------------- plumbing

    private fun text(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "$url -> ${response.code}")
                    null
                } else {
                    response.body?.string()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "$url failed: ${e.message}")
            null
        }
    }

    companion object Text {
        /** The rating in a review page's JSON-LD. */
        private val RATING = Regex("\"ratingValue\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9])?)\"?")

        /** Best New Music is a page-level flag, not part of the rating object. */
        private val BNM = Regex("best[ -]?new[ -]?music", RegexOption.IGNORE_CASE)

        private const val TAG = "Pitchfork"
        private const val LIST_TTL_MS = 6L * 60 * 60 * 1000
        const val HOST = "https://pitchfork.com"

        /** Pitchfork throttles; one request at a time, spaced out. */
        private const val INTERVAL_MS = 1500L

        /** A brace-walk over a multi-megabyte page needs a stop. */
        private const val WALK_GUARD = 500_000

        fun unCdata(s: String): String =
            s.replace(Regex("^\\s*<!\\[CDATA\\["), "").replace(Regex("]]>\\s*$"), "").trim()

        private val NAMED = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "copy" to "©", "reg" to "®", "trade" to "™",
            "nbsp" to " ", "hellip" to "...", "mdash" to "—", "ndash" to "–",
            "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
            "deg" to "°"
        )

        /** Named and numeric entities, with or without the trailing semicolon. */
        fun decodeEntities(input: String): String {
            if (input.isEmpty()) return input
            var s = Regex("&#x([0-9a-f]+);?", RegexOption.IGNORE_CASE).replace(input) { m ->
                m.groupValues[1].toIntOrNull(16)?.let(::codePoint) ?: m.value
            }
            s = Regex("&#(\\d+);?").replace(s) { m ->
                m.groupValues[1].toIntOrNull()?.let(::codePoint) ?: m.value
            }
            return Regex("&([a-z][a-z0-9]*);?", RegexOption.IGNORE_CASE).replace(s) { m ->
                NAMED[m.groupValues[1].lowercase()] ?: m.value
            }
        }

        private fun codePoint(n: Int): String =
            try {
                String(Character.toChars(n))
            } catch (e: Exception) {
                ""
            }

        fun stripHtml(html: String): String {
            val s = html
                .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<br\\s*/?\\s*>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("</?p[^>]*>", RegexOption.IGNORE_CASE), "\n\n")
                .replace(Regex("<[^>]+>"), "")
            return decodeEntities(s)
                .replace(Regex("\n[ \t]+"), "\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .replace(Regex("[ \t]+"), " ")
                .trim()
        }
    }
}
