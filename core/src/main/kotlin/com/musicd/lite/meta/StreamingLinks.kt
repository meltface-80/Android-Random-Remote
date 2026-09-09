package com.musicd.lite.meta

import java.net.URLEncoder
import java.util.Locale

/**
 * Links out to Qobuz and TIDAL for a record you have just read about.
 *
 * A SEARCH, NOT THE ALBUM. Linking straight to an album page needs that
 * service's own id for it, and all that is actually known here is two strings
 * off a Pitchfork review — so what these build is honest: the service's own
 * search, pre-filled, which lands one tap from the record. Their APIs are not
 * the way to do better; they want credentials this project does not have, which
 * is the same reason Qobuz and TIDAL browsing are not in this build at all.
 *
 * For Qobuz there IS better, and [QobuzAlbum] does it from a public page with
 * no API and no key. When it finds the record, the page shows its link instead
 * of this one; when it does not, this is the fallback, and it says "Find on",
 * not "Open in", because a search is all it is.
 *
 * Ordinary https links, deliberately, rather than qobuz:// or tidal:// custom
 * schemes. Android hands an https link to whichever app claims that domain, so
 * on a phone with the app installed it opens there, and on one without it opens
 * the web player instead of failing. A custom scheme does the first and not the
 * second. The WebView already routes every off-site link through ACTION_VIEW,
 * so nothing else is needed to make that work.
 *
 * WHICH HOST IS NOT COSMETIC, and 0.4.19 got Qobuz wrong. It used
 * open.qobuz.com, and the Qobuz app swallowed the link and opened on its
 * Discover screen — the search never happened, on Android and on iOS alike.
 * The reason is published by Qobuz itself:
 *
 *   https://open.qobuz.com/.well-known/assetlinks.json   → com.qobuz.music
 *   https://open.qobuz.com/.well-known/apple-app-site-association
 *                                                        → paths ["*"]
 *
 * The app claims EVERY path on that host on both platforms, so the link never
 * reaches a browser, and the app has no screen for /search?q=. www.qobuz.com
 * publishes neither file (both 403), so it is not an app link on either
 * platform and falls through to the browser — which is exactly what TIDAL's
 * search already does here, and what lands on the record.
 */
object StreamingLinks {

    /**
     * Qobuz's storefront search. The trailing slash on "/search/" is theirs:
     * without it they 301 to it, and the hop is free to skip.
     *
     * The path form is "/<storefront>/search/?q=", and the storefront segment
     * is not optional — https://www.qobuz.com/search/?q=… is a 404, as is any
     * country code Qobuz does not sell in. [storefront] picks a real one.
     */
    private const val QOBUZ = "https://www.qobuz.com/"

    /**
     * TIDAL's own domain. Its apps claim tidal.com, but only a listed set of
     * paths, and /search is not among them — so this reaches the browser, which
     * is where it was seen to work.
     */
    private const val TIDAL = "https://tidal.com/search?q="

    fun qobuz(artist: String?, album: String, locale: Locale = Locale.getDefault()): String? =
        searchQuery(artist, album)?.let { QOBUZ + storefront(locale) + "/search/?q=" + it }

    fun tidal(artist: String?, album: String): String? = searchQuery(artist, album)?.let { TIDAL + it }

    /**
     * The Qobuz storefront to search, for a device set to [locale].
     *
     * Qobuz has exactly these thirty, and answers anything else with a 404, so
     * this cannot be built by string-joining language and country and hoping.
     * An exact "country-language" match wins; failing that any storefront in
     * the same country does, which is what settles a device set to English in
     * Belgium; failing that, us-en, which is where qobuz.com itself sends a
     * visitor it cannot place.
     */
    internal fun storefront(locale: Locale): String {
        val country = locale.country.lowercase(Locale.ROOT)
        if (country.isEmpty()) return DEFAULT_STORE
        val exact = "$country-${locale.language.lowercase(Locale.ROOT)}"
        if (exact in STOREFRONTS) return exact
        return STOREFRONTS.firstOrNull { it.startsWith("$country-") } ?: DEFAULT_STORE
    }

    /**
     * "artist album", encoded, or null when there is nothing worth searching.
     *
     * Percent-encoded rather than form-encoded: URLEncoder writes a space as
     * "+", which is correct in a form body and merely conventional in a query
     * string — and a title with a real plus in it comes back wrong from
     * whichever end decodes it the other way. Titles carrying "&", "#" or a
     * quote are the reason any of this is encoded at all.
     *
     * A slash is the exception that must not merely be encoded. Qobuz's search
     * redirects "?q=…" into a path segment and decodes the %2F on the way, so
     * "AC/DC" arrives as two segments and 404s. A slash carries no meaning to a
     * search box anyway, so it is spent as a space before anything else
     * happens, and "AC DC Back in Black" finds the record.
     */
    internal fun searchQuery(artist: String?, album: String): String? {
        val words = "${artist.orEmpty()} $album".replace(SLASH, " ").trim().replace(WHITESPACE, " ")
        if (words.isEmpty()) return null
        return URLEncoder.encode(words, "UTF-8").replace("+", "%20")
    }

    private const val DEFAULT_STORE = "us-en"

    /** Qobuz's own country switcher, in its order — see the KDoc above. */
    private val STOREFRONTS = listOf(
        "ar-es", "at-de", "au-en", "be-fr", "be-nl", "br-pt", "ca-en", "ca-fr",
        "ch-de", "ch-fr", "cl-es", "co-es", "de-de", "dk-en", "es-es", "fi-en",
        "fr-fr", "gb-en", "ie-en", "it-it", "jp-ja", "lu-de", "lu-fr", "mx-es",
        "nl-nl", "no-en", "nz-en", "pt-pt", "se-en", "us-en"
    )

    private val SLASH = Regex("[/\\\\]")
    private val WHITESPACE = Regex("\\s+")
}
