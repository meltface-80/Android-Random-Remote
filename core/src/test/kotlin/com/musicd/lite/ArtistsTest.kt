package com.musicd.lite

import com.musicd.lite.library.AlbumRecord
import com.musicd.lite.library.Artists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a shelf of albums into a list of artists.
 *
 * The whole decision lives in [Artists]; the endpoint around it only pages the
 * answer. So it is tested here, with no server and no index build.
 */
class ArtistsTest {

    private var n = 0
    private fun album(title: String, subtitle: String, cover: String? = "img-$title") =
        AlbumRecord(offset = n++, title = title, subtitle = subtitle, imageKey = cover)

    private val shelf = listOf(
        album("Mezzanine", "Massive Attack"),
        album("Blue Lines", "Massive Attack"),
        album("Untrue", "Burial"),
        album("Ego Death", "The Internet")
    )

    private fun names(list: List<Artists.Entry>) = list.map { it.name }

    @Test
    fun everyAlbumArtistAppearsOnce() {
        val a = Artists.of(shelf)
        assertEquals(3, a.size)
        assertEquals(setOf("Massive Attack", "Burial", "The Internet"), names(a).toSet())
        assertEquals(2, a.first { it.name == "Massive Attack" }.albums)
    }

    /** The app's own splitting rule, so a collaboration credits both. */
    @Test
    fun aSplitCreditReachesBothArtists() {
        val a = Artists.of(listOf(album("Rockit", "Herbie Hancock / Grand Mixer DXT")))
        assertEquals(listOf("Herbie Hancock", "Grand Mixer DXT"), names(a))
        val b = Artists.of(listOf(album("Guest Spot", "Someone feat. A Guest")))
        assertEquals(listOf("Someone", "A Guest"), names(b))
    }

    /**
     * THE ONE THAT MATTERS for a wall of names: two spellings of one artist
     * must not become two tiles. Grouped by the folded name, and the one shown
     * is the spelling that appears most often — a single odd tag should not
     * decide how a name reads.
     */
    @Test
    fun oneArtistSpeltTwoWaysIsOneArtist() {
        val a = Artists.of(listOf(
            album("Coexist", "The xx"),
            album("I See You", "The xx"),
            album("xx", "THE XX")
        ))
        assertEquals(1, a.size)
        assertEquals("The xx", a[0].name)      // two tags to one
        assertEquals(3, a[0].albums)
    }

    /**
     * The cover has to be the SAME one on every visit, so it is the
     * alphabetically first album's — not the newest, not whichever the index
     * happened to hold first, either of which moves as the library grows.
     */
    @Test
    fun theCoverIsTheAlphabeticallyFirstAlbums() {
        val a = Artists.of(listOf(
            album("Zoo", "One Band", "img-zoo"),
            album("Apple", "One Band", "img-apple"),
            album("Middle", "One Band", "img-middle")
        ))
        assertEquals("img-apple", a[0].imageKey)
    }

    /** An album with no art must not rob the artist of a cover that exists. */
    @Test
    fun anAlbumWithNoArtIsSkippedOverForTheCover() {
        val a = Artists.of(listOf(
            album("Aaa", "One Band", null),
            album("Bbb", "One Band", "img-bbb")
        ))
        assertEquals("img-bbb", a[0].imageKey)
        assertNull(Artists.of(listOf(album("Only", "No Art", null)))[0].imageKey)
    }

    @Test
    fun aToZAndBackAgain() {
        val a = Artists.of(shelf)
        assertEquals(listOf("Burial", "Massive Attack", "The Internet"),
                     names(Artists.sorted(a, Artists.AZ)))
        assertEquals(listOf("The Internet", "Massive Attack", "Burial"),
                     names(Artists.sorted(a, Artists.ZA)))
    }

    /** An unknown sort is A-Z rather than an error: the screen must still draw. */
    @Test
    fun anUnknownSortFallsBackToAToZ() {
        val a = Artists.of(shelf)
        assertEquals(names(Artists.sorted(a, Artists.AZ)), names(Artists.sorted(a, "sideways")))
    }

    /**
     * Random is SEEDED. The same seed has to give the same wall — paging a
     * shuffle that re-rolls per request shows one artist twice and skips
     * another — and a new seed has to actually change it.
     */
    @Test
    fun randomIsStableUnderOneSeedAndMovesUnderAnother() {
        val a = Artists.of((1..40).map { album("Album $it", "Artist $it") })
        val once = names(Artists.sorted(a, Artists.RANDOM, seed = 7))
        val again = names(Artists.sorted(a, Artists.RANDOM, seed = 7))
        assertEquals(once, again)
        assertEquals(once.toSet(), names(a).toSet())          // nothing lost or invented
        assertNotEquals(once, names(Artists.sorted(a, Artists.RANDOM, seed = 8)))
        assertNotEquals(once, names(Artists.sorted(a, Artists.AZ)))
    }

    @Test
    fun anEmptyShelfHasNoArtists() {
        assertTrue(Artists.of(emptyList()).isEmpty())
        assertTrue(Artists.of(listOf(album("Untitled", ""))).isEmpty())
    }
}
