package com.musicd.lite

import com.musicd.lite.library.AlbumIndex
import com.musicd.lite.library.LibraryView
import com.musicd.lite.store.MemoryStore
import com.musicd.lite.store.YearSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sorting, filtering and the Home rows, built entirely from the snapshot. */
class LibraryViewTest {

    private val core = FakeCore()
    private val store = MemoryStore()
    private val index = AlbumIndex()
    private val view = LibraryView(index, store)

    private fun build(vararg albums: Pair<String, String>) {
        albums.forEach { (t, a) -> core.addAlbum(t, a) }
        index.build(core.tree)
    }

    private fun keyOf(title: String, artist: String) =
        com.musicd.lite.library.AlbumRecord(0, title, artist, null).key

    @Test
    fun sortsByTitleIgnoringLeadingArticles() {
        build("The Wall" to "Pink Floyd", "Aja" to "Steely Dan", "Blue" to "Joni Mitchell")
        val titles = view.select(view.sanitize("album", "asc", null, null, null, null, null))
            .map { it.title }
        assertEquals(listOf("Aja", "Blue", "The Wall"), titles)
    }

    @Test
    fun unknownYearsSortLastInBothDirections() {
        build("Aja" to "Steely Dan", "Blue" to "Joni Mitchell", "Undated" to "Nobody")
        store.putAlbumYear(keyOf("Aja", "Steely Dan"), 1977, YearSource.MUSICBRAINZ)
        store.putAlbumYear(keyOf("Blue", "Joni Mitchell"), 1971, YearSource.MUSICBRAINZ)

        val asc = view.select(view.sanitize("year", "asc", null, null, null, null, null)).map { it.title }
        val desc = view.select(view.sanitize("year", "desc", null, null, null, null, null)).map { it.title }

        assertEquals(listOf("Blue", "Aja", "Undated"), asc)
        // An album with no year is UNKNOWN, not year zero — reversing must not
        // float it to the top.
        assertEquals(listOf("Aja", "Blue", "Undated"), desc)
    }

    @Test
    fun prefixMatchesTitleOrAnyCreditedArtist() {
        build("Talkie Walkie" to "Tony Allen / Fela Kuti", "The Wall" to "Pink Floyd")

        val byArticleStrippedTitle =
            view.select(view.sanitize(null, null, "wall", null, null, null, null)).map { it.title }
        assertEquals(listOf("The Wall"), byArticleStrippedTitle)

        // "F" must reach Fela Kuti even though he is billed second.
        val bySecondArtist =
            view.select(view.sanitize(null, null, "fela", null, null, null, null)).map { it.title }
        assertEquals(listOf("Talkie Walkie"), bySecondArtist)
    }

    @Test
    fun prefixNeverMatchesMidWord() {
        build("Prince" to "Prince", "Bonnie Prince Billy" to "Bonnie Prince Billy")
        val hits = view.select(view.sanitize(null, null, "prince", null, null, null, null))
        // startsWith throughout: substring matching would put Prince in front of
        // Bonnie "Prince" Billy on the artist axis.
        assertEquals(listOf("Prince"), hits.map { it.title })
    }

    @Test
    fun playedFiltersSplitTheLibrary() {
        build("Aja" to "Steely Dan", "Blue" to "Joni Mitchell")
        store.recordPlay(keyOf("Aja", "Steely Dan"), "Aja", "Steely Dan", "Peg", System.currentTimeMillis())

        val played = view.select(view.sanitize(null, null, null, "played", null, null, null)).map { it.title }
        val never = view.select(view.sanitize(null, null, null, "never", null, null, null)).map { it.title }
        assertEquals(listOf("Aja"), played)
        assertEquals(listOf("Blue"), never)
    }

    @Test
    fun unplayedIgnoresOldHistory() {
        build("Aja" to "Steely Dan", "Blue" to "Joni Mitchell")
        val longAgo = System.currentTimeMillis() - 400L * 24 * 60 * 60 * 1000
        store.recordPlay(keyOf("Aja", "Steely Dan"), "Aja", "Steely Dan", "Peg", longAgo)
        // Played, but not in the last six months, so it is still a rediscovery.
        assertEquals(2, view.unplayed(6).size)
        assertEquals(1, view.unplayed(240).size)
    }

    @Test
    fun decadesAreCountedFromTheLibraryNewestFirst() {
        build("Aja" to "Steely Dan", "Blue" to "Joni Mitchell", "Kid A" to "Radiohead")
        store.putAlbumYear(keyOf("Aja", "Steely Dan"), 1977, YearSource.MUSICBRAINZ)
        store.putAlbumYear(keyOf("Blue", "Joni Mitchell"), 1971, YearSource.MUSICBRAINZ)
        store.putAlbumYear(keyOf("Kid A", "Radiohead"), 2000, YearSource.MUSICBRAINZ)

        assertEquals(listOf(2000 to 1, 1970 to 2), view.decades())
    }

    @Test
    fun aBetterYearSourceIsNotOverwrittenByAWorseOne() {
        build("Aja" to "Steely Dan")
        val key = keyOf("Aja", "Steely Dan")
        store.putAlbumYear(key, 1977, YearSource.FILE_TAG)
        store.putAlbumYear(key, 1999, YearSource.GUESS)
        assertEquals(1977, store.albumYear(key))
    }

    @Test
    fun albumOfTheDayIsStableWithinADay() {
        build("Aja" to "Steely Dan", "Blue" to "Joni Mitchell", "Kid A" to "Radiohead")
        val first = view.albumOfTheDay()
        assertNotNull(first)
        assertEquals(first!!.title, view.albumOfTheDay()!!.title)
    }

    @Test
    fun albumOfTheDayNoticesItWasPlayed() {
        build("Aja" to "Steely Dan")
        val album = view.albumOfTheDay()!!
        assertFalse(view.playedToday(album))
        store.recordPlay(album.key, album.title, album.subtitle, "Peg", System.currentTimeMillis())
        assertTrue(view.playedToday(album))
    }

    @Test
    fun historyMapsPlaysBackOntoLibraryTiles() {
        build("Aja" to "Steely Dan", "Blue" to "Joni Mitchell")
        val now = System.currentTimeMillis()
        store.recordPlay(keyOf("Blue", "Joni Mitchell"), "Blue", "Joni Mitchell", "River", now - 1000)
        store.recordPlay(keyOf("Aja", "Steely Dan"), "Aja", "Steely Dan", "Peg", now)
        // An album played twice appears once; newest first.
        store.recordPlay(keyOf("Aja", "Steely Dan"), "Aja", "Steely Dan", "Josie", now - 5)

        assertEquals(listOf("Aja", "Blue"), view.history(30, 10).map { it.title })
    }

    @Test
    fun historyDropsAlbumsThatLeftTheLibrary() {
        build("Aja" to "Steely Dan")
        store.recordPlay("gone something", "Gone", "Something", "A Track", System.currentTimeMillis())
        assertTrue(view.history(30, 10).isEmpty())
    }

    @Test
    fun sampleNeverRepeatsAnAlbum() {
        build(*(0 until 20).map { "Album $it" to "Artist" }.toTypedArray())
        val picked = view.sample(index.albums, 12)
        assertEquals(12, picked.size)
        assertEquals(12, picked.map { it.key }.toSet().size)
    }

    @Test
    fun sampleAsksForMoreThanExistsWithoutLooping() {
        build("Only One" to "Artist")
        assertEquals(1, view.sample(index.albums, 30).size)
        assertTrue(view.sample(emptyList(), 5).isEmpty())
    }

    @Test
    fun randomSortIsStableForASeed() {
        build(*(0 until 10).map { "Album $it" to "Artist" }.toTypedArray())
        val a = view.select(view.sanitize("random", null, null, null, null, null, "42")).map { it.title }
        val b = view.select(view.sanitize("random", null, null, null, null, null, "42")).map { it.title }
        val c = view.select(view.sanitize("random", null, null, null, null, null, "43")).map { it.title }
        assertEquals(a, b)
        assertTrue("a different seed should reorder", a != c)
    }

    @Test
    fun unknownSortAndFilterValuesFallBackSafely() {
        build("Aja" to "Steely Dan")
        val q = view.sanitize("nonsense", "sideways", null, "sometimes", null, null, null)
        assertEquals("album", q.sort)
        assertFalse(q.desc)
        assertEquals("any", q.played)
    }
}
