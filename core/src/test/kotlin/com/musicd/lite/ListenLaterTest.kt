package com.musicd.lite

import com.musicd.lite.library.ListenLater
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Listen later, over the real server and the shipping store.
 *
 * The rules worth pinning are the ones a user would notice going wrong: an
 * entry follows its album when the library changes under it, a second tap does
 * not move it to the front, `on` is the state asked for rather than a toggle,
 * and an album leaves by itself only when every track has really started —
 * never on a guess about an album whose tracks this app has not seen.
 */
class ListenLaterTest {

    private lateinit var f: ApiFixture

    @Before fun setUp() { f = ApiFixture().start() }
    @After fun tearDown() = f.stop()

    private fun later(): List<JSONObject> = f.json("/api/listen-later").getJSONArray("albums").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun put(title: String, artist: String, on: Boolean = true, extra: String = "") =
        f.postJson("/api/listen-later", """{"title":"$title","artist":"$artist","on":$on$extra}""")

    @Test
    fun anAlbumPutAsideIsListedResolvedAgainstTheLibrary() {
        assertTrue(put("Dummy", "Portishead").getBoolean("on"))
        val list = later()
        assertEquals(1, list.size)
        val e = list[0]
        assertEquals("Dummy", e.getString("title"))
        assertEquals("album", e.getString("source"))
        // Playable: the offset and Roon's own strings, and the album as every
        // other Home row sends it.
        assertEquals(f.app.index.relocate("Dummy", "Portishead")!!.offset, e.getInt("offset"))
        assertEquals("Portishead", e.getString("library_subtitle"))
        assertEquals("Dummy", e.getJSONObject("album").getString("title"))
        assertEquals("img-Dummy", e.getString("image_key"))
    }

    @Test
    fun onIsTheStateAskedForNotAToggle() {
        put("Third", "Portishead", on = true)
        put("Third", "Portishead", on = true)   // a second device, at the same moment
        assertEquals(1, later().size)
        put("Third", "Portishead", on = false)
        put("Third", "Portishead", on = false)
        assertEquals(0, later().size)
    }

    @Test
    fun aSecondTapKeepsTheOriginalDate() {
        put("Third", "Portishead")
        Thread.sleep(5)
        put("Kid A", "Radiohead")
        Thread.sleep(5)
        put("Third", "Portishead")              // already there: must not jump to the front
        assertEquals(listOf("Kid A", "Third"), later().map { it.getString("title") })
    }

    @Test
    fun theAlbumViewSaysWhetherItIsOnTheList() {
        val offset = f.app.index.relocate("Mezzanine", "Massive Attack")!!.offset
        fun flag() = f.json("/api/album?offset=$offset&title=Mezzanine&subtitle=Massive%20Attack")
            .getBoolean("listen_later")
        assertFalse(flag())
        put("Mezzanine", "Massive Attack")
        assertTrue(flag())
    }

    @Test
    fun anEntryFollowsItsAlbumWhenTheLibraryReshuffles() {
        put("Kid A", "Radiohead")
        val before = later()[0].getInt("offset")
        // An album sorted in front of it moves every offset after it.
        f.core.albums.add(0, com.musicd.lite.FakeCore.FakeAlbum("Aaa First", "Somebody", null)
            .apply { tracks += listOf("1. One") })
        f.app.index.build(f.core.tree)
        val after = later()[0]
        assertNotEquals(before, after.getInt("offset"))
        assertEquals(f.app.index.relocate("Kid A", "Radiohead")!!.offset, after.getInt("offset"))
    }

    @Test
    fun requestsThatCannotBeKeptAreRefusedWithAReason() {
        assertEquals(400, f.post("/api/listen-later", """{"artist":"x","on":true}""").first)
        assertEquals(400, f.post("/api/listen-later", """{"title":"Dummy","artist":"Portishead"}""").first)
        assertEquals(400, f.post("/api/listen-later", """{"title":"Dummy","on":"yes"}""").first)
        assertEquals(0, later().size)
    }

    @Test
    fun changingTheListMovesTheLiveRevision() {
        val before = f.json("/api/live").getJSONObject("rev").getString("later")
        put("Dummy", "Portishead")
        assertNotEquals(before, f.json("/api/live").getJSONObject("rev").getString("later"))
    }

    // ------------------------------------------------------------ playing through

    private fun play(album: String, artist: String, track: String) {
        val key = ListenLater.keyOf(album, artist)
        f.store.recordPlay(key, album, artist, track, System.currentTimeMillis())
        f.app.listenLater.noticePlay(album, artist)
    }

    @Test
    fun anAlbumLeavesOnceEveryTrackHasPlayed() {
        // Opening the album records Roon's own track list for it.
        val offset = f.app.index.relocate("Blue Lines", "Massive Attack")!!.offset
        f.json("/api/album?offset=$offset&title=Blue%20Lines&subtitle=Massive%20Attack")
        put("Blue Lines", "Massive Attack")
        Thread.sleep(5)
        play("Blue Lines", "Massive Attack", "Opening")
        play("Blue Lines", "Massive Attack", "Middle Eight")
        assertEquals("two of three tracks is not played through", 1, later().size)
        play("Blue Lines", "Massive Attack", "Closer")
        assertEquals(0, later().size)
    }

    @Test
    fun playsFromBeforeItWasPutAsideDoNotCount() {
        val offset = f.app.index.relocate("Blue Lines", "Massive Attack")!!.offset
        f.json("/api/album?offset=$offset&title=Blue%20Lines&subtitle=Massive%20Attack")
        play("Blue Lines", "Massive Attack", "Opening")
        play("Blue Lines", "Massive Attack", "Middle Eight")
        Thread.sleep(5)
        put("Blue Lines", "Massive Attack")
        Thread.sleep(5)
        play("Blue Lines", "Massive Attack", "Closer")
        assertEquals(1, later().size)
    }

    @Test
    fun anAlbumWhoseTracksWereNeverSeenStaysUntilTakenOff() {
        // Never opened here, so there is no track list to check against: the
        // list cannot tell played-through from sampled, and keeps it.
        put("Third", "Portishead")
        Thread.sleep(5)
        for (t in listOf("Opening", "Middle Eight", "Closer")) play("Third", "Portishead", t)
        assertEquals(1, later().size)
    }

    // ---------------------------------------------------------------- picks

    @Test
    fun aPickSaysWhetherItIsOnTheList() {
        val pick = f.json("/api/smart-picks?count=3").getJSONArray("picks").getJSONObject(0)
        assertFalse(pick.getBoolean("later"))
        put(pick.getString("album"), pick.getString("artist"), extra = ""","source":"picks"""")
        val again = f.json("/api/smart-picks?count=3").getJSONArray("picks").getJSONObject(0)
        assertTrue(again.getBoolean("later"))
    }

    @Test
    fun notForMeTakesThatArtistsPicksOffTheListButNotTheUsersOwnFinds() {
        put("Dummy", "Portishead", extra = ""","source":"picks"""")
        put("Third", "Portishead")   // put aside by hand, from the album view
        f.postJson("/api/smart-picks/block", """{"artist":"Portishead"}""")
        val left = later()
        assertEquals(listOf("Third"), left.map { it.getString("title") })
    }

    @Test
    fun picksSentToListenLaterArriveOnceADay() {
        assertEquals(200, f.post("/api/settings/smart-picks", """{"dest":"later"}""").first)
        assertEquals("later", f.json("/api/settings/smart-picks").getString("dest"))
        val list = later()
        assertTrue("nothing was sent", list.isNotEmpty())
        assertTrue(list.all { it.getString("source") == "picks" })
        val n = list.size
        // Asking again the same day sends nothing more, and taking one off by
        // hand does not bring it back.
        put(list[0].getString("title"), list[0].getString("artist"), on = false)
        assertEquals(n - 1, later().size)
        f.json("/api/smart-picks")
        assertEquals(n - 1, later().size)
    }

    @Test
    fun theStreamingDestinationIsNotAcceptedHere() {
        val (code, text) = f.post("/api/settings/smart-picks", """{"dest":"library"}""")
        assertEquals(text, 400, code)
        assertEquals("ask", f.json("/api/settings/smart-picks").getString("dest"))
        val dests = f.json("/api/settings/smart-picks").getJSONArray("dests")
        assertEquals(listOf("later", "ask"), (0 until dests.length()).map { dests.getString(it) })
    }
}
