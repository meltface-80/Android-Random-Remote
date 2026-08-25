package com.musicd.lite

import com.musicd.lite.library.AlbumIndex
import com.musicd.lite.library.Albums
import com.musicd.lite.roon.BrowseException
import com.musicd.lite.store.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening and playing an album, and the stale-offset defence.
 *
 * A tile carries the offset its album had when the index was built. A library
 * edit shifts those positions, and the album view still LOOKS right because its
 * header renders from the tile — so without these checks "Play now" quietly
 * plays a different record.
 */
class AlbumsTest {

    private class Fixture(albumTitles: List<Pair<String, String>>) {
        val core = FakeCore()
        val store = MemoryStore()
        val index = AlbumIndex()
        lateinit var albums: Albums

        init {
            albumTitles.forEach { (t, a) -> core.addAlbum(t, a, "img-$t") }
            albums = Albums(core.tree, index, store)
            index.build(core.tree)
        }
    }

    private val library = listOf(
        "Blue Lines" to "Massive Attack",
        "Dummy" to "Portishead",
        "Mezzanine" to "Massive Attack",
        "Third" to "Portishead"
    )

    @Test
    fun opensAnAlbumAndListsItsTracks() {
        val f = Fixture(library)
        val view = f.albums.open(1, null, null, null, Albums.Expect("Dummy", "Portishead"))
        assertEquals("Dummy", view.title)
        assertEquals("Portishead", view.subtitle)
        assertEquals(3, view.tracks.size)
        // Roon prefixes track titles with "N. "; the UI renders its own counter.
        assertEquals("Opening", view.tracks[0].title)
        assertEquals(listOf("play_now", "play_next", "queue", "radio"), view.actions.map { it.kind })
        assertNull(view.invoked)
        assertTrue(!view.partial)
    }

    @Test
    fun playsAnAlbumIntoAZone() {
        val f = Fixture(library)
        val view = f.albums.open(2, "zone-1", "play_now", null, Albums.Expect("Mezzanine", "Massive Attack"))
        assertEquals("Play Now", view.invoked)
        assertEquals(listOf("play_now:playmenu:2@zone-1"), f.core.invoked)
    }

    @Test
    fun queuesATrackByIndex() {
        val f = Fixture(library)
        val (invoked, track) = f.albums.invokeTrack(
            0, 1, "Middle Eight", "zone-1", "queue", null,
            Albums.Expect("Blue Lines", "Massive Attack")
        )
        assertEquals("Queue", invoked)
        assertEquals("Middle Eight", track)
        assertEquals(listOf("queue:track:0:1@zone-1"), f.core.invoked)
    }

    @Test
    fun aTrackWhoseIndexMovedIsRematchedByTitle() {
        val f = Fixture(library)
        // The caller believes "Closer" is at index 0; it is actually at 2.
        val (_, track) = f.albums.invokeTrack(
            0, 0, "Closer", "zone-1", "play_now", null,
            Albums.Expect("Blue Lines", "Massive Attack")
        )
        assertEquals("Closer", track)
        assertEquals(listOf("play_now:track:0:2@zone-1"), f.core.invoked)
    }

    @Test
    fun aTrackThatIsGoneIsAStaleError() {
        val f = Fixture(library)
        val e = runCatching {
            f.albums.invokeTrack(
                0, 0, "A Track That Left", "zone-1", "play_now", null,
                Albums.Expect("Blue Lines", "Massive Attack")
            )
        }.exceptionOrNull()
        assertTrue(e is BrowseException)
        // Stale, not broken: the client retries rather than showing a failure.
        assertTrue((e as BrowseException).stale)
        assertTrue(f.core.invoked.isEmpty())
    }

    @Test
    fun aStaleOffsetIsRelocatedFromTheSnapshot() {
        val f = Fixture(library)
        // The library gains an album at the front, shifting every offset by one,
        // WITHOUT the snapshot being rebuilt — exactly the situation a tile
        // opened before an import lands in.
        f.core.albums.add(0, FakeCore.FakeAlbum("A New Arrival", "Someone", null).apply {
            tracks += "1. Only Track"
        })

        // Offset 1 now holds "Blue Lines", but the caller asked for "Dummy".
        // The snapshot still knows Dummy's OLD offset (1), which is also wrong,
        // so the relocation has to fail through to the live search.
        val view = f.albums.open(1, null, null, null, Albums.Expect("Dummy", "Portishead"))
        assertEquals("Dummy", view.title)
        assertEquals(3, view.tracks.size)
    }

    @Test
    fun relocationUsesTheSnapshotWhenItIsStillRight() {
        val f = Fixture(library)
        // Ask for Third with a deliberately wrong offset. The snapshot has the
        // right one, so this must resolve without any live search.
        val view = f.albums.open(0, null, null, null, Albums.Expect("Third", "Portishead"))
        assertEquals("Third", view.title)
        assertEquals(3, view.offset)   // corrected, and handed back to the client
    }

    @Test
    fun anAlbumThatLeftTheLibraryRefusesRatherThanPlayingSomethingElse() {
        val f = Fixture(library)
        val e = runCatching {
            f.albums.open(0, "zone-1", "play_now", null, Albums.Expect("Deleted Record", "Nobody"))
        }.exceptionOrNull()
        assertTrue(e is BrowseException)
        assertTrue((e as BrowseException).stale)
        // The critical assertion: nothing was played.
        assertTrue(f.core.invoked.isEmpty())
    }

    @Test
    fun noIdentityMeansNoIdentityCheck() {
        // Some callers legitimately know only an offset. That must still work.
        val f = Fixture(library)
        val view = f.albums.open(2, null, null, null, Albums.Expect(null, null))
        assertEquals("Mezzanine", view.title)
    }

    @Test
    fun anAbsentActionNamesWhatRoonDidOffer() {
        val f = Fixture(library)
        val e = runCatching {
            f.albums.open(0, "zone-1", "shuffle", null, Albums.Expect("Blue Lines", "Massive Attack"))
        }.exceptionOrNull()
        assertTrue(e is BrowseException)
        assertTrue(e!!.message!!.contains("Play Now"))
        assertTrue(f.core.invoked.isEmpty())
    }

    @Test
    fun openingAnAlbumRecordsItsTrackList() {
        val f = Fixture(library)
        f.albums.open(1, null, null, null, Albums.Expect("Dummy", "Portishead"))
        val key = com.musicd.lite.library.AlbumRecord(0, "Dummy", "Portishead", null).key
        assertEquals(listOf("Opening", "Middle Eight", "Closer"), f.store.albumTracks(key))
    }

    @Test
    fun theIndexIsBuiltFromTheAlbumsHierarchy() {
        val f = Fixture(library)
        assertEquals(4, f.index.count)
        assertEquals(4, f.index.declared)
        assertTrue(f.index.isBuilt)
        assertEquals("Blue Lines", f.index.albums[0].title)
        assertEquals(3, f.index.relocate("Third", "Portishead")!!.offset)
        assertNull(f.index.relocate("Third", "Massive Attack"))
        // Title-only relocation is allowed: some callers know only the title.
        assertEquals(3, f.index.relocate("Third", null)!!.offset)
    }
}
