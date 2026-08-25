package com.musicd.lite

import com.musicd.lite.library.AlbumRecord
import com.musicd.lite.library.Normalize
import com.musicd.lite.library.Search
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local search.
 *
 * Roon's own browse search is server-driven and unhappy with short or
 * common-word queries — typing "the t" for the band "The The" is the case that
 * forces a local index.
 */
class SearchTest {

    private val library = listOf(
        AlbumRecord(0, "The Dark Side of the Moon", "Pink Floyd", null),
        AlbumRecord(1, "The Wall", "Pink Floyd", null),
        AlbumRecord(2, "Soul Mining", "The The", null),
        AlbumRecord(3, "Kid A", "Radiohead", null),
        AlbumRecord(4, "OK Computer", "Radiohead", null),
        AlbumRecord(5, "Moon Safari", "Air", null),
        AlbumRecord(6, "Talkie Walkie", "Tony Allen / Fela Kuti", null)
    )

    private fun titles(q: String, limit: Int = 10) =
        Search.albums(library, q, limit).map { it.album.title }

    @Test
    fun exactTitleWinsOutright() {
        assertEquals("The Wall", titles("The Wall").first())
    }

    @Test
    fun outOfOrderTokensStillMatch() {
        // "dark moon" must find "Dark Side of the Moon".
        assertTrue(titles("dark moon").contains("The Dark Side of the Moon"))
    }

    @Test
    fun consecutivePrefixesHandleTheThe() {
        // The case the whole local index exists for.
        val start = Search.consecutivePrefixStart(listOf("the", "the"), listOf("the", "t"))
        assertEquals(0, start)
        assertTrue(titles("the t").contains("Soul Mining"))
    }

    @Test
    fun artistMatchesRankBelowTitleMatches() {
        val hits = Search.albums(library, "radiohead", 10)
        assertEquals(2, hits.size)
        // Both are artist hits, so neither should score like an exact title.
        assertTrue(hits.all { it.score < 1000 })
    }

    @Test
    fun typosFallBackToASubsequence() {
        // "rdiohead" is a dropped letter, not a prefix of anything.
        val hits = titles("rdiohead")
        assertTrue("a dropped letter should still find the band", hits.isNotEmpty())
        assertTrue(hits.contains("Kid A") || hits.contains("OK Computer"))
    }

    @Test
    fun aSingleCharacterDoesNotFuzzyMatchEverything() {
        // One character is a prefix query, never a fuzzy one, or every album in
        // the library comes back on the first keystroke.
        val hits = Search.albums(library, "k", 50)
        assertTrue(hits.size < library.size)
        assertTrue(hits.any { it.album.title == "Kid A" })
    }

    @Test
    fun emptyQueryReturnsNothing() {
        assertTrue(Search.albums(library, "", 10).isEmpty())
        assertTrue(Search.albums(library, "   ", 10).isEmpty())
    }

    @Test
    fun limitIsHonoured() {
        assertEquals(2, Search.albums(library, "the", 2).size)
    }

    @Test
    fun artistSearchSplitsCollaborations() {
        // "F" must find Fela Kuti inside "Tony Allen / Fela Kuti"; a
        // whole-credit test would miss every collaboration where the artist is
        // not billed first.
        val names = Search.artists(library, "fela").map { it.name }
        assertTrue(names.contains("Fela Kuti"))
    }

    @Test
    fun artistSearchCountsAlbumsAndPrefersPrefixes() {
        val hits = Search.artists(library, "pink")
        assertEquals(1, hits.size)
        assertEquals("Pink Floyd", hits[0].name)
        assertEquals(2, hits[0].albumCount)
    }

    @Test
    fun normalisationFoldsAccentsAndPunctuation() {
        assertEquals("bjork homogenic", Normalize.text("Björk – Homogénic"))
        assertEquals("motorhead", Normalize.text("Motörhead"))
        assertEquals("", Normalize.text(null))
    }

    @Test
    fun sortKeyDropsLeadingArticles() {
        // Roon — and every record shop — files "The Wall" under W.
        assertEquals("wall", Normalize.sortKey(Normalize.text("The Wall")))
        assertEquals("a", Normalize.sortKey(Normalize.text("A")))
    }

    @Test
    fun trackNumberPrefixesAreStrippedButRead() {
        assertEquals("Money", Normalize.stripTrackNumber("6. Money"))
        assertEquals(6, Normalize.trackNumberOf("6. Money"))
        // A title that merely begins with digits is not a track NUMBER, even
        // though the prefix still looks like one and is stripped: an absurd
        // value is rejected by trackNumberOf rather than by the stripper, so a
        // shared playlist never carries "track 1999".
        assertEquals(null, Normalize.trackNumberOf("1999. The Party"))
        assertEquals(null, Normalize.trackNumberOf("0. Intro"))
        assertEquals("The Party", Normalize.stripTrackNumber("1999. The Party"))
    }

    @Test
    fun subsequenceIsOrderSensitive() {
        assertTrue(Search.isSubsequence("abc", "axbxc"))
        assertFalse(Search.isSubsequence("cba", "axbxc"))
    }
}
