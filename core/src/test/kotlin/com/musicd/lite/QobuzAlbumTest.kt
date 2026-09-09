package com.musicd.lite

import com.musicd.lite.meta.QobuzAlbum
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Picking the Qobuz album id out of Qobuz's own search results.
 *
 * The markup below is the real shape: www.qobuz.com files every album under
 * /<store>/album/<album-slug>-<artist-slug>/<id>, and lists each hit twice —
 * once on the art, once on the title. The ids are the real ones, and the
 * "Mezzanine" page really does return the remixes album alongside the record.
 *
 * NO NETWORK HERE. [QobuzAlbum.pick] is the whole decision; the fetch around
 * it is four lines of OkHttp shared with Pitchfork.
 */
class QobuzAlbumTest {

    private val qobuz = QobuzAlbum(OkHttpClient(), "test")

    private fun results(vararg slugAndId: Pair<String, String>): String =
        slugAndId.joinToString("") { (slug, id) ->
            """<div class="ReleaseCard"><a href="/us-en/album/$slug/$id">""" +
                """<img src="x"></a><a href="/us-en/album/$slug/$id">t</a></div>"""
        }

    @Test
    fun theExactAlbumAndArtistSlugIsTheOneTaken() {
        val html = results(
            "mezzanine-massive-attack" to "0724384559953",
            "mezzanine-the-remixes-massive-attack" to "0094635428353"
        )
        assertEquals("0724384559953", qobuz.pick(html, "us-en", "Massive Attack", "Mezzanine"))
    }

    /**
     * The remixes album sorts first often enough to matter, and it starts with
     * the album slug and names the artist — so a first-hit rule would open the
     * wrong record. The exact slug wins wherever it appears in the results.
     */
    @Test
    fun anExactMatchBeatsANearOneThatCameFirst() {
        val html = results(
            "mezzanine-the-remixes-massive-attack" to "0094635428353",
            "mezzanine-massive-attack" to "0724384559953"
        )
        assertEquals("0724384559953", qobuz.pick(html, "us-en", "Massive Attack", "Mezzanine"))
    }

    /** The record from the report that started this. */
    @Test
    fun anApostropheAndAnAbbreviationBothMatch() {
        val html = results("blowin-up-kiwi-jr" to "xx4tt0fxk187e")
        assertEquals("xx4tt0fxk187e", qobuz.pick(html, "us-en", "Kiwi Jr.", "Blowin' Up"))
    }

    /**
     * Qobuz's own slugging cannot be reproduced by rule, and this is the proof:
     * it DROPS the full stops in "m.A.A.d" to make "maad", where it turns the
     * slash in "AC/DC" into a separator. Anything that treats punctuation
     * consistently gets one of the two wrong, so both sides are compared on
     * letters and digits alone. These slugs are Qobuz's real ones.
     */
    @Test
    fun qobuzsOwnPunctuationRulesDoNotAgreeWithEachOther() {
        assertEquals(
            "tmtynwrp5xr7b",
            qobuz.pick(
                results("good-kid-maad-city-kendrick-lamar" to "tmtynwrp5xr7b"),
                "us-en", "Kendrick Lamar", "good kid, m.A.A.d city"
            )
        )
        assertEquals(
            "0886444889841",
            qobuz.pick(
                results("back-in-black-ac-dc" to "0886444889841"),
                "us-en", "AC/DC", "Back in Black"
            )
        )
    }

    /** Accents fold through the app's one folding rule, as everywhere else. */
    @Test
    fun anAccentDoesNotStopAMatch() {
        val html = results("post-bjork" to "abc123")
        assertEquals("abc123", qobuz.pick(html, "us-en", "Björk", "Post"))
    }

    /**
     * No exact slug, but a remaster of the right record by the right artist —
     * take it, because that is the album, catalogued under a longer name.
     */
    @Test
    fun aRemasterOfTheRightRecordIsStillTheRightRecord() {
        val html = results("kid-a-remastered-radiohead" to "0634904078263")
        assertEquals("0634904078263", qobuz.pick(html, "us-en", "Radiohead", "Kid A"))
    }

    /**
     * THE ONE THAT MATTERS. Qobuz answers a query it cannot place with its
     * nearest guess rather than with nothing, so taking the first result on
     * trust opens a record nobody asked for. No match must mean no link — the
     * caller then keeps the search, which is honest about not knowing.
     */
    @Test
    fun aNearestGuessIsNotAMatch() {
        val html = results("the-non-existent-first-album-the-urinals" to "jhfliwagsry1p")
        assertNull(qobuz.pick(html, "us-en", "Zzzz", "Nonexistent Album Xyzzy"))
    }

    /** Right album, wrong artist: a covers record, a re-recording, a namesake. */
    @Test
    fun theRightTitleByTheWrongArtistIsNotAMatch() {
        val html = results("mezzanine-someone-else" to "999")
        assertNull(qobuz.pick(html, "us-en", "Massive Attack", "Mezzanine"))
    }

    /** A results page from another storefront is not this storefront's. */
    @Test
    fun anotherStorefrontsLinkIsIgnored() {
        val html = results("mezzanine-massive-attack" to "0724384559953")
        assertNull(qobuz.pick(html, "gb-en", "Massive Attack", "Mezzanine"))
    }

    @Test
    fun nothingToMatchGivesNoId() {
        assertNull(qobuz.pick("", "us-en", "Massive Attack", "Mezzanine"))
        assertNull(qobuz.pick(results("x-y" to "1"), "us-en", "Massive Attack", "  "))
    }

    /**
     * The host is not interchangeable: open.qobuz.com is the one both platforms
     * hand to the Qobuz app, and /album/<id> is the route it answers.
     */
    @Test
    fun theDeepLinkPointsAtTheHostTheAppClaims() {
        assertEquals("https://open.qobuz.com/album/", QobuzAlbum.OPEN)
    }

    // ------------------------------------------------- the app's own scheme

    /**
     * open.qobuz.com's own page does not use its https link when it detects a
     * phone — it goes to qobuzapp://album/<id>. That is the app's front door,
     * and the https link is the web fallback around it.
     */
    @Test
    fun anAlbumLinkAlsoHasTheAppsOwnScheme() {
        assertEquals(
            "qobuzapp://album/0724384559953",
            QobuzAlbum.appUri("https://open.qobuz.com/album/0724384559953")
        )
        assertEquals(
            "qobuzapp://album/xx4tt0fxk187e",
            QobuzAlbum.appUri("https://open.qobuz.com/album/xx4tt0fxk187e")
        )
    }

    /**
     * THE ONE THAT MATTERS. What comes back from here is handed to
     * startActivity, so it may only ever be built out of an id this app
     * resolved for itself. Another host, another path, a second path segment,
     * or anything that could carry an instruction of its own — a query, a
     * fragment, an @ — is not a Qobuz album link and gets no scheme.
     */
    @Test
    fun nothingButOurOwnAlbumLinkGetsTurnedIntoAnIntent() {
        for (bad in listOf(
            null,
            "",
            "https://open.qobuz.com/album/",
            "https://open.qobuz.com/artist/36819",
            "https://open.qobuz.com/album/123/extra",
            "https://open.qobuz.com/album/123?x=1",
            "https://open.qobuz.com/album/123#Intent;package=com.example;end",
            "https://evil.example/album/123",
            "https://open.qobuz.com.evil.example/album/123",
            "http://open.qobuz.com/album/123",
            "https://www.qobuz.com/us-en/album/mezzanine-massive-attack/0724384559953",
            "https://tidal.com/search?q=x"
        )) {
            assertNull("\"$bad\" must not become an app link", QobuzAlbum.appUri(bad))
        }
    }
}
