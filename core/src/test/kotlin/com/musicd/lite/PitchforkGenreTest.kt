package com.musicd.lite

import com.musicd.lite.meta.Pitchfork
import com.musicd.lite.meta.StreamingLinks
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Genres off a Pitchfork listing, and the links out from one.
 *
 * The state below is the real shape, taken from pitchfork.com's own
 * __PRELOADED_STATE__ and cut down to the fields that matter — including the
 * two awkward cases that actually occur in it: a review carrying three genres,
 * and one that lists the same genre twice.
 */
class PitchforkGenreTest {

    private val pitchfork = Pitchfork(OkHttpClient(), "test")

    private fun review(
        slug: String,
        hed: String,
        artist: String,
        rubric: String
    ) = """
        {"contentType":"review","url":"/reviews/albums/$slug/",
         "dangerousHed":"<em>$hed</em>",
         "subHed":{"name":"$artist"},
         "pubDate":"2026-09-01T05:00:00.000Z",
         "ratingValue":{"score":"7.5","isBestNewMusic":false,"isBestNewReissue":false},
         "rubric":$rubric}
    """.trimIndent()

    private fun state(vararg reviews: String) =
        JSONObject("""{"items":[${reviews.joinToString(",")}]}""")

    private fun itemsOf(vararg reviews: String) =
        pitchfork.collectReviewItems(state(*reviews))

    // --------------------------------------------------------------- genres

    @Test
    fun aReviewCarriesTheGenrePitchforkFiledItUnder() {
        val items = itemsOf(
            review("gi-gi-in-lieu", "In Lieu", "Gi Gi", """[{"name":"Electronic","url":"/genre/electronic"}]""")
        )
        assertEquals(1, items.size)
        assertEquals(listOf("Electronic"), items[0].genres)
    }

    /** Pitchfork files plenty of records under two or three at once. */
    @Test
    fun everyGenreIsKeptAndInPitchforksOwnOrder() {
        val items = itemsOf(
            review("x", "X", "A", """[{"name":"Pop/R&B"},{"name":"Rap"},{"name":"Electronic"}]""")
        )
        assertEquals(listOf("Pop/R&B", "Rap", "Electronic"), items[0].genres)
    }

    /**
     * Seen in the live listing: the same genre twice on one review. Left alone
     * it would put that record in the section twice.
     */
    @Test
    fun aGenreListedTwiceAppearsOnce() {
        val items = itemsOf(review("y", "Y", "B", """[{"name":"Rap"},{"name":"Rap"}]"""))
        assertEquals(listOf("Rap"), items[0].genres)
    }

    @Test
    fun aReviewWithNoGenreIsNotAFailure() {
        assertEquals(emptyList<String>(), itemsOf(review("z", "Z", "C", "[]"))[0].genres)
        assertEquals(emptyList<String>(), itemsOf(review("z", "Z", "C", "null"))[0].genres)
    }

    @Test
    fun blankNamesAreDropped() {
        val items = itemsOf(review("w", "W", "D", """[{"name":"  "},{"name":"Jazz"}]"""))
        assertEquals(listOf("Jazz"), items[0].genres)
    }

    @Test
    fun theGenresReachThePageInTheJson() {
        val json = itemsOf(review("v", "V", "E", """[{"name":"Metal"},{"name":"Rock"}]"""))[0].toJson()
        val arr = json.getJSONArray("genres")
        assertEquals(2, arr.length())
        assertEquals("Metal", arr.getString(0))
        assertEquals("Rock", arr.getString(1))
    }

    // ---------------------------------------------------------------- links

    @Test
    fun aSearchLinkCarriesTheArtistAndTheAlbum() {
        val q = StreamingLinks.qobuz("Massive Attack", "Mezzanine", Locale.UK)
        assertEquals("https://www.qobuz.com/gb-en/search/?q=Massive%20Attack%20Mezzanine", q)
        val t = StreamingLinks.tidal("Massive Attack", "Mezzanine")
        assertEquals("https://tidal.com/search?q=Massive%20Attack%20Mezzanine", t)
    }

    /**
     * 0.4.19's bug, pinned. open.qobuz.com is claimed path-for-path by the
     * Qobuz app on both platforms — it published the assetlinks.json and the
     * apple-app-site-association saying so — and the app has no search screen
     * to answer with, so the link opened Discover and lost the record. Nothing
     * here may point at that host again.
     */
    @Test
    fun theQobuzLinkAvoidsTheHostTheAppSwallows() {
        val url = StreamingLinks.qobuz("Massive Attack", "Mezzanine", Locale.US)!!
        assertFalse("open.qobuz.com never reaches a browser: $url", url.contains("open.qobuz.com"))
        assertTrue(url.startsWith("https://www.qobuz.com/"))
    }

    /**
     * The storefront segment is not optional and cannot be invented: Qobuz
     * answers /search/?q= with no country, and any country it does not sell
     * in, with a 404.
     */
    @Test
    fun theQobuzStorefrontIsOneQobuzActuallyHas() {
        assertEquals("gb-en", StreamingLinks.storefront(Locale.UK))
        assertEquals("us-en", StreamingLinks.storefront(Locale.US))
        assertEquals("fr-fr", StreamingLinks.storefront(Locale.FRANCE))
        assertEquals("jp-ja", StreamingLinks.storefront(Locale.JAPAN))
        // No exact pair, but the country has a store: take that country's.
        assertEquals("be-fr", StreamingLinks.storefront(Locale("en", "BE")))
        // Countries Qobuz does not sell in, and a locale with no country at
        // all, both fall back rather than building a 404.
        assertEquals("us-en", StreamingLinks.storefront(Locale("en", "IN")))
        assertEquals("us-en", StreamingLinks.storefront(Locale("en")))
    }

    /**
     * Qobuz redirects "?q=…" into a path segment and decodes the %2F doing it,
     * so an encoded slash still splits the path and 404s. It has to be gone,
     * not merely escaped.
     */
    @Test
    fun aSlashInTheNameIsSpentAsASpace() {
        val url = StreamingLinks.qobuz("AC/DC", "Back in Black", Locale.US)!!
        assertEquals("https://www.qobuz.com/us-en/search/?q=AC%20DC%20Back%20in%20Black", url)
    }

    /**
     * A space MUST NOT come out as "+". It is only a space in a form body; in a
     * query string it is conventional at best, and a title with a real plus in
     * it then comes back wrong from whichever end decodes it the other way.
     */
    @Test
    fun aSpaceIsPercentEncodedAndAPlusSurvives() {
        val url = StreamingLinks.tidal("Godspeed You! Black Emperor", "F# A# ∞")!!
        assertTrue("a space must not become +", !url.contains("+") || url.contains("%2B"))
        assertTrue(url.startsWith("https://tidal.com/search?q="))
    }

    /** The characters that would otherwise end the query string early. */
    @Test
    fun theCharactersThatWouldBreakAUrlAreEncoded() {
        val url = StreamingLinks.qobuz("AC/DC", "Back in Black & Blue #1")!!
        for (raw in listOf(" ", "&", "#", "/")) {
            assertTrue(
                "\"$raw\" reached the URL unencoded: $url",
                !url.substringAfter("?q=").contains(raw)
            )
        }
    }

    @Test
    fun anAlbumWithNoArtistStillSearches() {
        assertEquals("https://tidal.com/search?q=Mezzanine", StreamingLinks.tidal(null, "Mezzanine"))
        assertEquals("https://tidal.com/search?q=Mezzanine", StreamingLinks.tidal("  ", "Mezzanine"))
    }

    /** Nothing to search for is a missing link, not a link to nothing. */
    @Test
    fun nothingToSearchForGivesNoLink() {
        assertNull(StreamingLinks.qobuz(null, ""))
        assertNull(StreamingLinks.tidal("   ", "   "))
    }

    @Test
    fun theLinksReachThePageInTheJson() {
        val json = itemsOf(review("u", "Mezzanine", "Massive Attack", "[]"))[0].toJson()
        assertTrue(json.getString("qobuz").matches(QOBUZ_SEARCH))
        assertTrue(json.getString("tidal").startsWith("https://tidal.com/search?q="))
    }

    /** Whichever storefront this machine's locale picks. */
    private val QOBUZ_SEARCH = Regex("https://www\\.qobuz\\.com/[a-z]{2}-[a-z]{2}/search/\\?q=.+")
}
