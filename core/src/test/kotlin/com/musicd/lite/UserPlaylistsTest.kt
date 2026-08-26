package com.musicd.lite

import com.musicd.lite.library.UserPlaylists
import com.musicd.lite.store.MemoryStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Playlists you make yourself.
 *
 * The thing these guard is that a stored entry stays PLAYABLE. A playlist is
 * not a list of names — it is a set of directions back to a track on the Core,
 * and a row that has lost its way back is worse than a row that was never
 * added, because it counts towards the total and fails only when tapped.
 */
class UserPlaylistsTest {

    private lateinit var store: MemoryStore
    private lateinit var lists: UserPlaylists

    @Before
    fun setUp() {
        store = MemoryStore()
        lists = UserPlaylists(store)
    }

    private fun track(
        title: String,
        album: String = "Mezzanine",
        offset: Int = 12,
        index: Int = 0,
        image: String? = "img1"
    ) = UserPlaylists.Track.parse(
        JSONObject()
            .put("title", title)
            .put("album_title", album)
            .put("album_subtitle", "Massive Attack")
            .put("album_offset", offset)
            .put("track_index", index)
            .put("image_key", image ?: JSONObject.NULL)
    )!!

    @Test
    fun createsRenamesAndDeletes() {
        val made = lists.save(null, "Sunday morning").getOrThrow()
        assertEquals(1, made.size)
        val id = made.first().id

        assertEquals("Sunday evening", lists.save(id, "Sunday evening").getOrThrow().first().name)
        assertTrue(lists.delete(id).getOrThrow().isEmpty())
        assertTrue(lists.delete(id).isFailure)
    }

    @Test
    fun survivesARestart() {
        // The whole point of storing it. A second instance over the same store
        // is what the app does on every launch.
        val id = lists.save(null, "Keepers").getOrThrow().first().id
        lists.addTracks(id, listOf(track("Angel"), track("Teardrop", index = 1)))

        val reopened = UserPlaylists(store)
        val p = reopened.byId(id)
        assertNotNull(p)
        assertEquals("Keepers", p!!.name)
        assertEquals(listOf("Angel", "Teardrop"), p.tracks.map { it.title })
        assertEquals("the way back to the track has to survive too", 12, p.tracks[0].albumOffset)
        assertEquals("Mezzanine", p.tracks[0].albumTitle)
        assertEquals(1, p.tracks[1].trackIndex)
    }

    @Test
    fun aTrackWithNoAlbumIsRefused() {
        // Nothing to open on the Core, so it could never play. Storing it would
        // inflate the count with a row that fails only when tapped.
        assertNull(UserPlaylists.Track.parse(JSONObject().put("title", "Orphan")))
        assertNull(
            UserPlaylists.Track.parse(
                JSONObject().put("title", "Orphan").put("album_title", "X")
            )
        )
        assertNull(
            UserPlaylists.Track.parse(
                JSONObject().put("album_title", "X").put("album_offset", 1)
            )
        )
    }

    @Test
    fun theMosaicShowsFourDIFFERENTSleeves() {
        // A playlist of one record would otherwise show the same cover four
        // times, which reads as a rendering fault rather than as a playlist.
        val id = lists.save(null, "One record").getOrThrow().first().id
        lists.addTracks(id, (0..5).map { track("Track $it", index = it, image = "same") })
        assertEquals(listOf("same"), lists.byId(id)!!.artKeys())

        val mixed = lists.save(null, "Mixed").getOrThrow().last().id
        lists.addTracks(mixed, (0..5).map { track("T$it", index = it, image = "img$it") })
        assertEquals(4, lists.byId(mixed)!!.artKeys().size)
    }

    @Test
    fun addingStopsAtTheCeilingAndSaysSo() {
        val id = lists.save(null, "Big").getOrThrow().first().id
        val (_, first) = lists.addTracks(
            id, (0 until UserPlaylists.MAX_TRACKS).map { track("T$it", index = it % 999) }
        ).getOrThrow()
        assertEquals(UserPlaylists.MAX_TRACKS, first.added)
        assertTrue("a full playlist is not a clean success", !first.full)

        val (p, second) = lists.addTracks(id, listOf(track("One more"))).getOrThrow()
        assertEquals(0, second.added)
        assertTrue("the caller must be able to say it did not fit", second.full)
        assertEquals(UserPlaylists.MAX_TRACKS, p.tracks.size)
    }

    @Test
    fun theCeilingOnPlaylistsIsEnforced() {
        repeat(UserPlaylists.MAX_PLAYLISTS) { lists.save(null, "List $it").getOrThrow() }
        val over = lists.save(null, "One too many")
        assertTrue(over.isFailure)
        assertTrue(over.exceptionOrNull()!!.message!!.contains("delete one first"))
    }

    @Test
    fun addingByNameCreatesOnceAndThenAppends() {
        // "Add to playlist…" offers a name box as well as the existing list.
        // Two adds to the same name must not leave two playlists.
        val a = lists.resolveTarget(null, "From the album view").getOrThrow()
        lists.addTracks(a.id, listOf(track("Angel")))
        val b = lists.resolveTarget(a.id, null).getOrThrow()
        lists.addTracks(b.id, listOf(track("Teardrop", index = 1)))

        assertEquals(1, lists.all().size)
        assertEquals(2, lists.byId(a.id)!!.tracks.size)
    }

    @Test
    fun aNameOfNothingIsRefused() {
        assertTrue(lists.save(null, "   ").isFailure)
        assertTrue(lists.resolveTarget(null, "").isFailure)
    }

    @Test
    fun whitespaceIsCollapsedSoTwoSpellingsAreOneName() {
        val p = lists.save(null, "  Sunday   morning  ").getOrThrow().first()
        assertEquals("Sunday morning", p.name)
    }

    @Test
    fun rubbishInStorageIsSkippedRatherThanTakingTheListDown() {
        // A file half-written by an older version, or by a bug. Losing one row
        // is recoverable; refusing to load any playlist is not.
        store.putSetting(
            UserPlaylists.KEY,
            """{"playlists":[{"id":"","name":"no id"},
                             {"id":"ok","name":"Fine","tracks":[{"nonsense":true}]}]}"""
        )
        val all = lists.all()
        assertEquals(1, all.size)
        assertEquals("Fine", all[0].name)
        assertTrue(all[0].tracks.isEmpty())
    }
}
