package com.musicd.lite.meta

import com.musicd.lite.Log
import com.musicd.lite.library.Normalize
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * The Qobuz album id for a record off a Pitchfork review, so the link can open
 * the Qobuz APP on the record instead of a browser on a shop page.
 *
 * WHY AN ID IS THE ONLY THING THAT WORKS. open.qobuz.com is Qobuz's own
 * "open this in the app" host — its page is titled "Open Qobuz" — and both
 * platforms hand it every path, because Qobuz publishes an assetlinks.json and
 * an apple-app-site-association claiming all of them. Its router understands
 * exactly five shapes, and they are all ids:
 *
 *     /album/:id   /artist/:id   /track/:id   /playlist/:id   /:type/:id
 *
 * There is no search route, on that host or in the app behind it, which is why
 * 0.4.19's open.qobuz.com/search?q= opened the app on Discover with the query
 * thrown away, and why no search URL anywhere will ever do better. Sending the
 * link to the web player instead does not help: play.qobuz.com is claimed by
 * the same app, so the app takes that link too and lands in the same place.
 *
 * WHERE THE ID COMES FROM. Qobuz's own public search page, read the way
 * [Pitchfork] reads pitchfork.com: no API, no key, no account, nothing but the
 * markup a browser is served. Their API is a different matter — it wants
 * credentials this project does not have and may not borrow — and this deals
 * only with a page anyone can open.
 *
 * A WRONG ALBUM IS WORSE THAN A SEARCH PAGE. Qobuz answers a query it cannot
 * place with its nearest guess rather than nothing, so the first hit is never
 * taken on trust: the slug it is filed under has to match the record that was
 * asked for, or this returns null and the caller keeps the search link.
 */
class QobuzAlbum(private val http: OkHttpClient, private val userAgent: String) {

    /**
     * `https://open.qobuz.com/album/<id>` for [album] by [artist], or null when
     * Qobuz has nothing that is recognisably it.
     *
     * Cached including the misses — a record Qobuz does not carry is a fact
     * about the record, and re-asking on every visit to the same review would
     * spend a request to be told so again.
     */
    fun deepLink(artist: String?, album: String?, locale: Locale = Locale.getDefault()): String? {
        if (album.isNullOrBlank()) return null
        val store = StreamingLinks.storefront(locale)
        val key = "$store|${artist.orEmpty()}|$album"
        val id = cache.get(key) { resolve(store, artist, album) ?: "" }
        return if (id.isEmpty()) null else OPEN + id
    }

    private fun resolve(store: String, artist: String?, album: String): String? {
        val query = StreamingLinks.searchQuery(artist, album) ?: return null
        val html = gate.run { text("https://www.qobuz.com/$store/search/albums/$query") }
            ?: return null
        return pick(html, store, artist, album)
    }

    /**
     * The id of the result that is recognisably the record asked for.
     *
     * Qobuz files an album under `/<store>/album/<album-slug>-<artist-slug>/<id>`,
     * so "album then artist" is the signal. It is compared on letters and
     * digits alone, because Qobuz's slugging is not a rule that can be
     * reproduced: an apostrophe and a full stop vanish ("Ol' Dirty Bastard" is
     * ol-dirty-bastard, "good kid, m.A.A.d city" is good-kid-maad-city) while a
     * slash becomes a separator ("AC/DC" is ac-dc). Dropping every separator
     * from both sides makes all three agree, and [Normalize.text] is the app's
     * one folding rule, so accents fold the same way here as everywhere else.
     *
     * An exact "album+artist" is taken wherever it appears in the results, not
     * merely first: searching "Mezzanine" returns the remixes album too, and it
     * often sorts above the record. Failing that, a result that STARTS with the
     * album and mentions the artist will do — that catches a remaster or a
     * deluxe edition, whose slug carries a suffix Pitchfork's title does not.
     *
     * Anything else is a guess, and a guess here opens the wrong record.
     */
    internal fun pick(html: String, store: String, artist: String?, album: String): String? {
        val wantAlbum = canon(album)
        if (wantAlbum.isEmpty()) return null
        val wantArtist = canon(artist.orEmpty())
        val exact = wantAlbum + wantArtist

        var loose: String? = null
        for (m in HREF.findAll(html)) {
            if (m.groupValues[1] != store) continue
            val slug = canon(m.groupValues[2])
            val id = m.groupValues[3]
            if (slug == exact) return id
            if (loose == null && slug.startsWith(wantAlbum) && slug.contains(wantArtist)) {
                loose = id
            }
        }
        return loose
    }

    /** Letters and digits only, accents folded — see [pick]. */
    private fun canon(s: String): String = Normalize.text(s).replace(" ", "")

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

    private val gate = RateGate(INTERVAL_MS)
    private val cache = TtlCache<String, String>(TTL_MS, 200)

    companion object {
        private const val TAG = "QobuzAlbum"

        /** The host that opens the app — see the class KDoc for why only this one. */
        const val OPEN = "https://open.qobuz.com/album/"

        /**
         * The same album as a `qobuzapp://` link, which is the door the Qobuz
         * app's own redirector uses — or null if [url] is not one of ours.
         *
         * Handing Android the https link lets it decide, and it gives it to the
         * Qobuz app because Qobuz claims every path on that host. That works,
         * but not from cold: the app comes up on its Home screen with the album
         * dropped, and only a second tap — with the app now running — lands on
         * the record. open.qobuz.com's own page does not use the https route at
         * all when it detects a phone. It builds
         *
         *     intent://album/<id>#Intent;scheme=qobuzapp;package=com.qobuz.music;…
         *
         * on Android and navigates to "qobuzapp://album/<id>" on iOS, so that
         * scheme is the app's real front door and the https link is the web
         * fallback around it. The caller tries this first and keeps the https
         * link for when it fails, which is what happens on a phone with no
         * Qobuz app installed.
         *
         * Deliberately strict. This string is handed to startActivity, so it is
         * built from an id this app resolved itself and nothing else: the host,
         * the path and the id shape all have to match, and a query string or a
         * fragment — anything that could carry a second instruction — means no.
         */
        fun appUri(url: String?): String? {
            val id = url?.removePrefix(OPEN)?.takeIf { it != url } ?: return null
            return if (id.isNotEmpty() && id.all { it.isLetterOrDigit() && it.code < 128 }) {
                "qobuzapp://album/$id"
            } else {
                null
            }
        }

        /** `href="/us-en/album/mezzanine-massive-attack/0724384559953"` */
        private val HREF =
            Regex("href=\"/([a-z]{2}-[a-z]{2})/album/([a-z0-9-]+)/([A-Za-z0-9]+)\"")

        /** A catalogue does move, but not in an afternoon. */
        private const val TTL_MS = 24L * 60 * 60 * 1000

        /** One request at a time, spaced out — somebody else's server. */
        private const val INTERVAL_MS = 1500L
    }
}
