package com.musicd.lite.meta

import java.net.URLEncoder

/**
 * Links out to Qobuz and TIDAL for a record you have just read about.
 *
 * A SEARCH, NOT THE ALBUM, AND THAT IS NOT LAZINESS. Linking straight to an
 * album page needs that service's own id for it, and the only ways to get one
 * are the unofficial APIs whose terms forbid it — the same reason Qobuz and
 * TIDAL browsing are not in this build at all — or partner credentials this
 * project does not have. All that is actually known here is two strings off a
 * Pitchfork review, so what is offered is honest: the service's own search,
 * pre-filled, which lands one tap from the record.
 *
 * Ordinary https links, deliberately, rather than qobuz:// or tidal:// custom
 * schemes. Android hands an https link to whichever app claims that domain, so
 * on a phone with the app installed it opens there, and on one without it opens
 * the web player instead of failing. A custom scheme does the first and not the
 * second. The WebView already routes every off-site link through ACTION_VIEW,
 * so nothing else is needed to make that work.
 */
object StreamingLinks {

    /** Qobuz's web player. Verified to answer this shape. */
    private const val QOBUZ = "https://open.qobuz.com/search?q="

    /**
     * TIDAL's own domain, which its Android app claims, so the app takes it
     * when installed.
     */
    private const val TIDAL = "https://tidal.com/search?q="

    fun qobuz(artist: String?, album: String): String? = query(artist, album)?.let { QOBUZ + it }

    fun tidal(artist: String?, album: String): String? = query(artist, album)?.let { TIDAL + it }

    /**
     * "artist album", encoded, or null when there is nothing worth searching.
     *
     * Percent-encoded rather than form-encoded: URLEncoder writes a space as
     * "+", which is correct in a form body and merely conventional in a query
     * string — and a title with a real plus in it comes back wrong from
     * whichever end decodes it the other way. Titles carrying "&", "#" or a
     * quote are the reason any of this is encoded at all.
     */
    private fun query(artist: String?, album: String): String? {
        val words = "${artist.orEmpty()} $album".trim().replace(WHITESPACE, " ")
        if (words.isEmpty()) return null
        return URLEncoder.encode(words, "UTF-8").replace("+", "%20")
    }

    private val WHITESPACE = Regex("\\s+")
}
