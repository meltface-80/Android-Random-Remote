package com.musicd.lite

import com.musicd.lite.library.AlbumIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot's derived sets.
 *
 * [AlbumIndex.artistKeys] answers "can the library open a screen for this
 * name?", which the now-playing line asks of every request it serves — and
 * /api/zone-state is held open by the page continuously. It used to be built
 * per call, out of two full walks of the album list; it is built once per
 * snapshot now, so what it CONTAINS is worth pinning rather than leaving to
 * the one end-to-end assertion that happened to cover the easy case.
 */
class AlbumIndexTest {

    private val core = FakeCore()
    private val index = AlbumIndex()

    private fun build(vararg albums: Pair<String, String>) {
        albums.forEach { (t, a) -> core.addAlbum(t, a) }
        index.build(core.tree)
    }

    @Test
    fun artistKeysHoldTheWholeCreditAndEachNameInside() {
        build("Black Voices" to "Tony Allen / Fela Kuti", "Dummy" to "Portishead")

        // The credit as one string: what a single-artist album matches on.
        assertTrue("portishead" in index.artistKeys)
        assertTrue("tony allen fela kuti" in index.artistKeys)
        // And each name credited inside it, so a track artist on a
        // collaboration still finds its screen.
        assertTrue("tony allen" in index.artistKeys)
        assertTrue("fela kuti" in index.artistKeys)

        assertFalse("nobody at all" in index.artistKeys)
        assertFalse("" in index.artistKeys)
    }

    @Test
    fun artistKeysAreRebuiltWithTheSnapshotAndDroppedWithIt() {
        build("Dummy" to "Portishead")
        assertTrue("portishead" in index.artistKeys)

        core.addAlbum("Third", "Portishead")
        core.addAlbum("Aja", "Steely Dan")
        index.build(core.tree)
        assertTrue("a rebuild should pick up the new credit", "steely dan" in index.artistKeys)
        assertTrue("portishead" in index.artistKeys)

        // The Core going away wipes the snapshot, and nothing derived from it
        // may outlive it — a linkable name with no album behind it is a tap
        // that opens an empty screen.
        index.clear()
        assertEquals(emptySet<String>(), index.artistKeys)
    }
}
