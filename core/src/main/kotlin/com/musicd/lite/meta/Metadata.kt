package com.musicd.lite.meta

import com.musicd.lite.Log
import com.musicd.lite.library.Normalize
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The extra facts an album page shows that Roon's extension API does not carry.
 *
 * Roon gives four strings per album — title, subtitle, image_key, item_key —
 * and nothing else. No release year, no review, no biography. MusicD-Remote
 * fills that in from a chain of outside sources; this build keeps the two that
 * need no API key and no mounted music directory:
 *
 *   - release year, from MusicBrainz
 *   - album blurb and artist biography, from Wikipedia
 *
 * Deliberately absent: the label chain (iTunes, TheAudioDB, Discogs, FanArt.tv,
 * Bandcamp) and Pitchfork scraping. Those are the "labels" half of the original
 * and the reason this is a lite build.
 */
class Metadata(private val http: OkHttpClient, private val userAgent: String) {

    private companion object {
        const val TAG = "Meta"
        const val CACHE_MS = 12L * 60 * 60 * 1000

        /**
         * MusicBrainz asks for at most one request per second from a single
         * client, and enforces it. Wikipedia is more relaxed but gets the same
         * courtesy.
         */
        const val MB_INTERVAL_MS = 1100L
        const val WIKI_INTERVAL_MS = 200L
    }

    private val cache = TtlCache<String, AlbumExtras>(CACHE_MS, 512)
    private val mbGate = RateGate(MB_INTERVAL_MS)
    private val wikiGate = RateGate(WIKI_INTERVAL_MS)

    data class Bio(val description: String, val source: String, val url: String?)

    data class AlbumExtras(val year: Int?, val album: Bio?, val artist: Bio?)

    fun extras(title: String, artist: String): AlbumExtras {
        val key = Normalize.text(title) + "||" + Normalize.text(artist)
        if (key == "||") return AlbumExtras(null, null, null)
        return cache.get(key) {
            AlbumExtras(
                year = runCatching { musicBrainzYear(title, artist) }.getOrNull(),
                album = runCatching { wikipediaAlbum(title, artist) }.getOrNull(),
                artist = runCatching { wikipediaArtist(artist, title) }.getOrNull()
            )
        }
    }

    // ---------------------------------------------------------- MusicBrainz

    /** MusicBrainz's Lucene syntax needs quotes escaped, not stripped. */
    private fun mbQuote(s: String): String = s.replace("\"", "\\\"")

    fun musicBrainzYear(title: String, artist: String): Int? {
        if (title.isBlank()) return null
        val query = buildString {
            append("release:\"").append(mbQuote(title)).append('"')
            if (artist.isNotBlank()) append(" AND artist:\"").append(mbQuote(artist)).append('"')
        }
        val url = "https://musicbrainz.org/ws/2/release/?query=" +
            urlEncode(query) + "&fmt=json&limit=5"
        val json = mbGate.run { getJson(url) } ?: return null
        val releases = json.optJSONArray("releases") ?: return null

        // The earliest dated release is the release YEAR; a later reissue is a
        // different pressing of the same record, not a different album.
        var best = Int.MAX_VALUE
        for (i in 0 until releases.length()) {
            val r = releases.optJSONObject(i) ?: continue
            // Below ~70 the match is a different record that shares a word.
            if (r.optInt("score", 0) < 70) continue
            val year = yearOf(r.optString("date")) ?: continue
            if (year < best) best = year
        }
        return best.takeIf { it != Int.MAX_VALUE }
    }

    private fun yearOf(date: String?): Int? {
        if (date.isNullOrBlank()) return null
        val y = date.take(4).toIntOrNull() ?: return null
        return if (y in 1880..2100) y else null
    }

    // ------------------------------------------------------------ Wikipedia

    private fun wikiSearch(query: String, limit: Int = 5): List<String> {
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" +
            urlEncode(query) + "&srlimit=$limit&format=json"
        val json = wikiGate.run { getJson(url) } ?: return emptyList()
        val hits = json.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { hits.optJSONObject(it)?.optString("title") }
            .filter { it.isNotEmpty() }
    }

    private fun wikiExtract(pageTitle: String): String? {
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/" +
            urlEncode(pageTitle.replace(' ', '_'))
        val json = wikiGate.run { getJson(url) } ?: return null
        if (json.optString("type") == "disambiguation") return null
        return json.optString("extract").takeIf { it.length > 40 }
    }

    fun wikipediaAlbum(title: String, artist: String): Bio? {
        if (title.isBlank()) return null
        val candidates = wikiSearch("$title $artist album")
        for (page in candidates) {
            // The guard that stops a review being attached to the wrong record:
            // the page title must actually mention the album.
            if (!namesOverlap(page, title)) continue
            val extract = wikiExtract(page) ?: continue
            return Bio(extract, "Wikipedia", "https://en.wikipedia.org/wiki/" +
                urlEncode(page.replace(' ', '_')))
        }
        return null
    }

    fun wikipediaArtist(artist: String, albumTitle: String): Bio? {
        if (artist.isBlank()) return null
        val candidates = wikiSearch("$artist band musician")
        for (page in candidates) {
            // "The Who" vs "The Guess Who" is exactly the mismatch this rejects:
            // matching on a first token alone puts a stranger's biography on the
            // page. Fail safe — drop the bio rather than show the wrong one.
            if (!namesOverlap(page, artist)) continue
            val extract = wikiExtract(page) ?: continue
            return Bio(extract, "Wikipedia", "https://en.wikipedia.org/wiki/" +
                urlEncode(page.replace(' ', '_')))
        }
        return null
    }

    /**
     * Whole-phrase overlap in either direction, tolerant of a leading article.
     * "the who" vs "the guess who" -> false (correctly rejected);
     * "jay z" vs "jay z feat alicia keys" -> true (correctly kept).
     */
    fun namesOverlap(a: String, b: String): Boolean {
        val na = Normalize.sortKey(Normalize.text(a))
        val nb = Normalize.sortKey(Normalize.text(b))
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        // A wikipedia page is often "Title (album)" or "Artist (band)".
        val strippedA = na.replace(Regex("\\b(album|band|musician|singer|song)\\b"), "").trim()
        val strippedB = nb.replace(Regex("\\b(album|band|musician|singer|song)\\b"), "").trim()
        if (strippedA == strippedB) return true
        return containsWholeWords(strippedA, strippedB) || containsWholeWords(strippedB, strippedA)
    }

    /** [needle] appears in [hay] on word boundaries, never mid-word. */
    private fun containsWholeWords(hay: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        val h = hay.split(" ").filter(String::isNotEmpty)
        val n = needle.split(" ").filter(String::isNotEmpty)
        if (n.isEmpty() || n.size > h.size) return false
        for (i in 0..(h.size - n.size)) {
            if ((0 until n.size).all { h[i + it] == n[it] }) return true
        }
        return false
    }

    // ------------------------------------------------------------- plumbing

    private fun getJson(url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "$url -> ${response.code}")
                    return null
                }
                response.body?.string()?.takeIf { it.isNotEmpty() }?.let { JSONObject(it) }
            }
        } catch (e: Exception) {
            Log.d(TAG, "$url failed: ${e.message}")
            null
        }
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** Serialises calls to one host and spaces them out. */
class RateGate(private val intervalMs: Long) {
    private var last = 0L

    @Synchronized
    fun <T> run(body: () -> T): T {
        val wait = intervalMs - (System.currentTimeMillis() - last)
        if (wait > 0) {
            try {
                Thread.sleep(wait)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            return body()
        } finally {
            last = System.currentTimeMillis()
        }
    }
}

/** A bounded cache whose entries expire. Nothing here is worth a dependency. */
class TtlCache<K, V>(private val ttlMs: Long, private val max: Int) {
    private class Entry<V>(val value: V, val at: Long)

    private val map = object : LinkedHashMap<K, Entry<V>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean =
            size > max
    }

    @Synchronized
    fun peek(key: K): V? {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() - e.at > ttlMs) {
            map.remove(key)
            return null
        }
        return e.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = Entry(value, System.currentTimeMillis())
    }

    /**
     * Note the deliberate absence of a lock around [compute]: a metadata fetch
     * takes seconds over the network, and holding the cache lock across it would
     * stall every other request. A duplicate fetch is cheaper than that.
     */
    fun get(key: K, compute: () -> V): V {
        peek(key)?.let { return it }
        val value = compute()
        put(key, value)
        return value
    }

    @Synchronized
    fun clear() = map.clear()
}

/** okhttp tuned for the metadata hosts: short timeouts, nothing held open. */
fun metadataHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .build()
