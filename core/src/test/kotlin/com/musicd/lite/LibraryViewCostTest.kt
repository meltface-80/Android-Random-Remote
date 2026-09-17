package com.musicd.lite

import com.musicd.lite.library.AlbumIndex
import com.musicd.lite.library.LibraryView
import com.musicd.lite.store.MemoryStore
import com.musicd.lite.store.PlayRow
import com.musicd.lite.store.Store
import com.musicd.lite.store.YearSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the view costs the database, which on a phone is the whole story.
 *
 * Every per-key read here is a real SQLite round trip in the shipping store,
 * and the view runs over the WHOLE snapshot: a decade filter that asks for one
 * album's year at a time is one query per album in the library, and a sort that
 * asks inside its comparator is one per COMPARISON — n log n queries to order n
 * albums. Neither shows up as a wrong answer, which is why it went unnoticed,
 * and neither shows up in a test that only checks the answer.
 *
 * So this asserts the calls that are NOT made. The numbers are deliberately
 * generous: the point is that the cost does not scale with the library, not
 * that it is any particular constant.
 */
class LibraryViewCostTest {

    /** Counts what the view asks for, and answers from a real store. */
    private class CountingStore(private val real: MemoryStore = MemoryStore()) : Store {
        var perKeyYear = 0
        var perKeyGenres = 0
        var perKeyFirstSeen = 0
        var bulkReads = 0

        fun reset() {
            perKeyYear = 0; perKeyGenres = 0; perKeyFirstSeen = 0; bulkReads = 0
        }

        override fun albumYear(albumKey: String): Int? {
            perKeyYear++
            return real.albumYear(albumKey)
        }

        override fun albumGenres(albumKey: String): List<String> {
            perKeyGenres++
            return real.albumGenres(albumKey)
        }

        override fun firstSeen(albumKey: String): Long? {
            perKeyFirstSeen++
            return real.firstSeen(albumKey)
        }

        override fun albumYears(): Map<String, Int> {
            bulkReads++
            return real.albumYears()
        }

        override fun albumGenresAll(): Map<String, List<String>> {
            bulkReads++
            return real.albumGenresAll()
        }

        override fun firstSeenAll(): Map<String, Long> {
            bulkReads++
            return real.firstSeenAll()
        }

        override fun tokenFor(coreId: String) = real.tokenFor(coreId)
        override fun saveToken(coreId: String, token: String) = real.saveToken(coreId, token)
        override fun lastCore() = real.lastCore()
        override fun saveLastCore(host: String, port: Int) = real.saveLastCore(host, port)
        override fun forgetLastCore() = real.forgetLastCore()
        override fun setting(key: String) = real.setting(key)
        override fun putSetting(key: String, value: String) = real.putSetting(key, value)
        override fun recordPlay(
            albumKey: String, album: String, artist: String, track: String, at: Long
        ) = real.recordPlay(albumKey, album, artist, track, at)
        override fun playsSince(since: Long): List<PlayRow> = real.playsSince(since)
        override fun lastPlayed(albumKey: String) = real.lastPlayed(albumKey)
        override fun lastPlayedAll() = real.lastPlayedAll()
        override fun playCounts() = real.playCounts()
        override fun prunePlays(before: Long) = real.prunePlays(before)
        override fun recordFirstSeen(entries: Map<String, Long>) = real.recordFirstSeen(entries)
        override fun putAlbumYear(albumKey: String, year: Int, sourceRank: Int) =
            real.putAlbumYear(albumKey, year, sourceRank)
        override fun putAlbumGenres(albumKey: String, genres: List<String>) =
            real.putAlbumGenres(albumKey, genres)
        override fun albumTracks(albumKey: String) = real.albumTracks(albumKey)
        override fun putAlbumTracks(albumKey: String, tracks: List<String>) =
            real.putAlbumTracks(albumKey, tracks)
        override fun blockedPicks() = real.blockedPicks()
        override fun blockPick(albumKey: String) = real.blockPick(albumKey)
        override fun seenPicks() = real.seenPicks()
        override fun markPickSeen(albumKey: String, at: Long) = real.markPickSeen(albumKey, at)
        override fun close() = real.close()
    }

    private val core = FakeCore()
    private val store = CountingStore()
    private val index = AlbumIndex()
    private val view = LibraryView(index, store)

    private val size = 300

    private fun library() {
        repeat(size) { core.addAlbum("Album $it", "Artist ${it % 20}") }
        index.build(core.tree)
        for ((i, al) in index.albums.withIndex()) {
            store.putAlbumYear(al.key, 1960 + (i % 60), YearSource.FILE_TAG)
            store.putAlbumGenres(al.key, listOf(if (i % 2 == 0) "Rock" else "Jazz"))
        }
        store.recordFirstSeen(index.albums.associate { it.key to 1_700_000_000_000L })
        store.reset()
    }

    /** A number that is fine for one album and ruinous for fifty thousand. */
    private fun assertDoesNotScaleWithTheLibrary(what: String, calls: Int) =
        assertTrue(
            "$what made $calls per-key reads for a $size-album library — " +
                "that is one database round trip per album",
            calls < size
        )

    @Test
    fun theDecadeFilterReadsTheYearsTableOnce() {
        library()
        val hits = view.select(view.sanitize("album", null, null, null, null, "1960s", null))
        assertTrue("the filter should still select albums", hits.isNotEmpty())
        assertDoesNotScaleWithTheLibrary("the decade filter", store.perKeyYear)
    }

    @Test
    fun theGenreFilterReadsTheGenresTableOnce() {
        library()
        val hits = view.select(view.sanitize("album", null, null, null, "Rock", null, null))
        assertEquals(size / 2, hits.size)
        assertDoesNotScaleWithTheLibrary("the genre filter", store.perKeyGenres)
    }

    /**
     * The sharp one. These lambdas were handed to a comparator, so the reads
     * were per comparison rather than per album — comfortably more reads than
     * there are albums.
     */
    @Test
    fun sortingByYearDoesNotQueryInsideTheComparator() {
        library()
        val sorted = view.select(view.sanitize("year", "desc", null, null, null, null, null))
        assertEquals(size, sorted.size)
        assertDoesNotScaleWithTheLibrary("the year sort", store.perKeyYear)
    }

    @Test
    fun sortingByAddedDoesNotQueryInsideTheComparator() {
        library()
        val sorted = view.select(view.sanitize("added", "desc", null, null, null, null, null))
        assertEquals(size, sorted.size)
        assertDoesNotScaleWithTheLibrary("the added sort", store.perKeyFirstSeen)
    }

    @Test
    fun theDecadeChipsReadTheYearsTableOnce() {
        library()
        assertTrue("the chips should find decades", view.decades().isNotEmpty())
        assertDoesNotScaleWithTheLibrary("the decade chips", store.perKeyYear)
    }
}
