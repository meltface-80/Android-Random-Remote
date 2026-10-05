package com.musicd.lite.meta

import org.json.JSONObject

/**
 * The links under the share card — where to hear a record, where to read about
 * it — and in Discover and "If you like this" rows. A port of Rouen's
 * lib/share-links.js, held to its unit suite (ShareLinksTest).
 *
 * PURE STRINGS. Nothing here makes a request: every link is a search URL built
 * from the title and the artist, which is what lets the card's chips appear
 * with the card rather than after a round of lookups. The one exception lives
 * elsewhere: a Qobuz search lands on their download store and never opens the
 * app, so the page asks /api/qobuz-link for an album id afterwards.
 *
 * The four rules a search URL has to keep, each learned from a broken link:
 *   1. A space is %20, never "+" — several services take the query as a PATH
 *      segment, where "+" is searched for literally.
 *   2. A slash is spent as a space, not encoded — "AC/DC" as AC%2FDC is a
 *      different path on half of these sites.
 *   3. Only the first credited act goes in — "Stan Getz / Cal Tjader / …" is
 *      no act anyone has heard of.
 *   4. Qobuz always has a storefront (their bare search 404s); Apple never
 *      does (theirs redirects to the right one by itself).
 */
object ShareLinks {

    data class Service(val id: String, val name: String)

    data class Review(
        val id: String,
        val name: String,
        val kind: String,
        val chip: String,
        val onByDefault: Boolean
    )

    val SERVICES = listOf(
        Service("qobuz", "Qobuz"),
        Service("tidal", "TIDAL"),
        Service("spotify", "Spotify"),
        Service("apple", "Apple Music"),
        Service("amazon", "Amazon Music"),
        Service("deezer", "Deezer"),
        Service("bandcamp", "Bandcamp")
    )

    val REVIEWS = listOf(
        Review("wikipedia", "Wikipedia", "album", "Wikipedia", true),
        Review("pitchfork", "Pitchfork", "album", "Pitchfork", true),
        Review("allmusic", "AllMusic", "album", "AllMusic", true),
        Review("wikipedia-artist", "Wikipedia", "artist", "Wikipedia artist", false),
        Review("allmusic-artist", "AllMusic", "artist", "AllMusic artist", false)
    )

    val SERVICE_IDS: List<String> = SERVICES.map { it.id }
    val REVIEW_IDS: List<String> = REVIEWS.map { it.id }

    /** Qobuz's storefronts, consulted rather than constructed — not every pair exists. */
    private val QOBUZ_STOREFRONTS = listOf(
        "ar-es", "at-de", "au-en", "be-fr", "be-nl", "br-pt", "ca-en", "ca-fr",
        "ch-de", "ch-fr", "cl-es", "co-es", "de-de", "dk-en", "es-es", "fi-en",
        "fr-fr", "gb-en", "ie-en", "it-it", "jp-ja", "lu-de", "lu-fr", "mx-es",
        "nl-nl", "no-en", "nz-en", "pt-pt", "se-en", "us-en"
    )
    private const val QOBUZ_DEFAULT_STORE = "us-en"

    /**
     * What separates two acts in a credit. Not "&" and not "," — "Hall & Oates"
     * and "Emerson, Lake & Palmer" are one act each — and not a slash with no
     * space either side, which is "AC/DC".
     */
    private val CREDIT_SEPARATOR =
        Regex("\\s+/\\s*|\\s*/\\s+|\\s*;\\s*|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE)

    fun primaryArtist(artist: String?): String {
        val whole = (artist ?: "").trim()
        if (whole.isEmpty()) return ""
        val first = whole.split(CREDIT_SEPARATOR).firstOrNull()?.trim().orEmpty()
        return first.ifEmpty { whole }
    }

    /**
     * JavaScript's encodeURIComponent, which is what the links were tuned
     * against: UTF-8, %20 for a space, and A–Z a–z 0–9 - _ . ! ~ * ' ( ) left
     * alone. java.net.URLEncoder is form encoding — "+" for a space — which is
     * rule 1 broken.
     */
    fun encodeUriComponent(s: String): String {
        val out = StringBuilder(s.length * 3)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.!~*'()") out.append(ch)
            else out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return out.toString()
    }

    /** The encoded search text, or null when there is nothing worth searching for. */
    fun searchQuery(artist: String?, album: String?): String? {
        val words = "${primaryArtist(artist)} ${album ?: ""}"
            .replace(Regex("[/\\\\]"), " ")   // rule 2 — spent, not encoded
            .trim()
            .replace(Regex("\\s+"), " ")
        if (words.isEmpty()) return null
        return encodeUriComponent(words)
    }

    fun qobuzStorefront(locale: String?): String {
        val tag = (locale ?: "").trim().lowercase()
        if (tag.isEmpty()) return QOBUZ_DEFAULT_STORE
        val parts = tag.split(Regex("[-_]"))
        val language = parts.firstOrNull().orEmpty()
        val country = (if (parts.size > 1) parts.last() else language).replace(Regex("[^a-z]"), "")
        if (country.isEmpty()) return QOBUZ_DEFAULT_STORE
        val exact = "$country-$language"
        if (exact in QOBUZ_STOREFRONTS) return exact
        return QOBUZ_STOREFRONTS.firstOrNull { it.startsWith("$country-") } ?: QOBUZ_DEFAULT_STORE
    }

    /** The highest-q tag in an Accept-Language header, or "". */
    fun localeFromAcceptLanguage(header: String?): String {
        val s = (header ?: "").trim()
        if (s.isEmpty()) return ""
        var best: Pair<String, Double>? = null
        for (part in s.split(",")) {
            val bits = part.trim().split(";")
            val tag = bits[0].trim()
            if (tag.isEmpty() || tag == "*") continue
            var q = 1.0
            for (p in bits.drop(1)) {
                Regex("^\\s*q\\s*=\\s*([0-9.]+)\\s*$", RegexOption.IGNORE_CASE).find(p)?.let {
                    q = it.groupValues[1].toDoubleOrNull() ?: 0.0
                }
            }
            if (best == null || q > best.second) best = tag to q
        }
        return best?.first ?: ""
    }

    private fun serviceUrl(id: String, query: String, storefront: String): String? = when (id) {
        "qobuz" -> "https://www.qobuz.com/$storefront/search/?q=$query"
        "tidal" -> "https://tidal.com/search?q=$query"
        "spotify" -> "https://open.spotify.com/search/$query"
        "apple" -> "https://music.apple.com/search?term=$query"   // no storefront — rule 4
        "amazon" -> "https://music.amazon.com/search/$query"
        "deezer" -> "https://www.deezer.com/search/$query"
        "bandcamp" -> "https://bandcamp.com/search?q=$query&item_type=a"
        else -> null
    }

    data class Link(val id: String, val name: String, val url: String, val chip: String? = null, val kind: String? = null) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url).also { o ->
            chip?.let { o.put("chip", it) }
            kind?.let { o.put("kind", it) }
        }
    }

    /** One search link per enabled service, in the table's order. */
    fun serviceLinks(artist: String?, album: String?, locale: String? = null, enabled: Collection<String>? = null): List<Link> {
        val query = searchQuery(artist, album) ?: return emptyList()
        val store = qobuzStorefront(locale)
        val allow = enabled?.toSet()
        return SERVICES.filter { allow == null || it.id in allow }
            .mapNotNull { s -> serviceUrl(s.id, query, store)?.let { Link(s.id, s.name, it) } }
    }

    /**
     * One link per enabled review site. Wikipedia and Pitchfork link to the
     * actual page when it has already been found ([wikipediaUrl] /
     * [pitchforkUrl]) and to a search when it has not; AllMusic is always its
     * search. The artist pair searches for the ARTIST, and is absent rather
     * than empty when there is no artist.
     */
    fun reviewLinks(
        artist: String?,
        album: String?,
        enabled: Collection<String>? = null,
        wikipediaUrl: String? = null,
        pitchforkUrl: String? = null,
        wikipediaArtistUrl: String? = null
    ): List<Link> {
        val query = searchQuery(artist, album) ?: return emptyList()
        val artistQuery = searchQuery(artist, "")
        val allow = (enabled ?: defaultReviewIds()).toSet()
        fun urlFor(id: String): String? = when (id) {
            "wikipedia" -> wikipediaUrl ?: "https://en.wikipedia.org/w/index.php?search=$query"
            "pitchfork" -> pitchforkUrl ?: "https://pitchfork.com/search/?q=$query"
            "allmusic" -> "https://www.allmusic.com/search/albums/$query"
            "wikipedia-artist" -> artistQuery?.let { wikipediaArtistUrl ?: "https://en.wikipedia.org/w/index.php?search=$it" }
            "allmusic-artist" -> artistQuery?.let { "https://www.allmusic.com/search/artists/$it" }
            else -> null
        }
        return REVIEWS.filter { it.id in allow }
            .mapNotNull { r -> urlFor(r.id)?.let { Link(r.id, r.name, it, r.chip, r.kind) } }
    }

    /** A stored list outliving an id it names is ordinary: the unknown ones are dropped. */
    fun sanitiseIds(ids: Collection<String>?, known: List<String>): List<String> {
        val want = ids?.toSet() ?: emptySet()
        return known.filter { it in want }
    }

    fun defaultServiceIds(): List<String> = SERVICE_IDS
    fun defaultReviewIds(): List<String> = REVIEWS.filter { it.onByDefault }.map { it.id }
}
