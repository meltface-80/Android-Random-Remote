package com.musicd.lite

import com.musicd.lite.meta.Pitchfork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding a review from the search box.
 *
 * This is the whole decision — the endpoint around it only reads the cache and
 * hands the answer over — so it is tested here with no client and no network.
 */
class PitchforkSearchTest {

    private fun item(album: String, artist: String?, url: String = "u/" + album) =
        Pitchfork.Item(
            url = url, album = album, artist = artist, cover = null,
            score = 7.5, isBestNewMusic = false, date = null
        )

    private val shelf = listOf(
        item("Mezzanine", "Massive Attack"),
        item("Mezzanine: The Remixes", "Massive Attack"),
        item("Blue Lines", "Massive Attack"),
        item("Untrue", "Burial"),
        item("Post", "Björk"),
        item("Untitled (Black Is)", "SAULT")
    )

    private fun names(q: String) = Pitchfork.search(shelf, q).map { it.album }

    @Test
    fun anAlbumIsFoundByItsOwnName() {
        assertEquals(listOf("Mezzanine", "Mezzanine: The Remixes"), names("mezzanine"))
    }

    @Test
    fun anArtistFindsTheirRecords() {
        assertEquals(3, Pitchfork.search(shelf, "massive attack").size)
    }

    /**
     * THE ONE THAT MATTERS. Typing an album name must not bury it under
     * everything else that artist released, so a title that STARTS with what
     * was typed outranks one that merely contains it, which outranks a match
     * on the artist alone.
     */
    @Test
    fun theRecordYouNamedComesFirst() {
        val hits = Pitchfork.search(shelf + item("Blue", "Someone Else"), "blue")
        assertEquals("Blue", hits.first().album)
        assertTrue("Blue Lines" in hits.map { it.album })
    }

    /** Every term has to land, so a second word narrows rather than widens. */
    @Test
    fun aSecondWordNarrows() {
        assertEquals(listOf("Blue Lines"), names("blue massive"))
        assertEquals(emptyList<String>(), names("blue burial"))
    }

    /** The app's one folding rule, so case and accents are not a barrier. */
    @Test
    fun caseAndAccentsFold() {
        assertEquals(listOf("Post"), names("bjork"))
        assertEquals(listOf("Untitled (Black Is)"), names("sault"))
        assertEquals(listOf("Untrue"), names("UNTRUE"))
    }

    /** An artist Pitchfork did not name must not crash the match. */
    @Test
    fun aReviewWithNoArtistIsStillSearchable() {
        val odd = listOf(item("Compilation", null))
        assertEquals(listOf("Compilation"), Pitchfork.search(odd, "compilation").map { it.album })
        assertEquals(emptyList<Pitchfork.Item>(), Pitchfork.search(odd, "nobody"))
    }

    @Test
    fun nothingTypedFindsNothing() {
        assertEquals(emptyList<Pitchfork.Item>(), Pitchfork.search(shelf, ""))
        assertEquals(emptyList<Pitchfork.Item>(), Pitchfork.search(shelf, "   "))
    }

    /** A search box does not want forty rows. */
    @Test
    fun theResultsAreCapped() {
        val many = (1..40).map { item("Record $it", "One Band") }
        assertEquals(8, Pitchfork.search(many, "one band").size)
        assertEquals(3, Pitchfork.search(many, "one band", limit = 3).size)
    }
}
