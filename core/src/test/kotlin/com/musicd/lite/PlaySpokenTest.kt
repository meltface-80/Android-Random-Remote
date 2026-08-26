package com.musicd.lite

import com.musicd.lite.roon.Zone
import com.musicd.lite.store.MemoryStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Say an album or an artist, and it plays" — the dial's microphone.
 *
 * The matching is the app's own library index rather than an exact lookup,
 * which is the whole point: a speech recogniser mis-hears constantly, and a
 * remote that only plays what was said perfectly is a remote nobody speaks to
 * twice.
 */
class PlaySpokenTest {

    private lateinit var core: FakeCore
    private lateinit var app: MusicdLite

    private val noAssets = object : com.musicd.lite.api.StaticAssets {
        override fun read(path: String): Pair<ByteArray, String>? = null
    }

    private fun zone(id: String) = Zone.parse(
        JSONObject("""{"zone_id":"$id","display_name":"Zone $id","state":"stopped"}""")
    )

    @Before
    fun setUp() {
        core = FakeCore()
        core.addAlbum("The Dark Side of the Moon", "Pink Floyd")
        core.addAlbum("Mezzanine", "Massive Attack")
        core.addAlbum("Blue Lines", "Massive Attack")
        core.addAlbum("Kind of Blue", "Miles Davis")
        app = MusicdLite(
            store = MemoryStore(),
            assets = noAssets,
            artDir = null,
            version = "test",
            httpPort = 0
        ) { _, _, _ -> core }
        app.index.build(core.tree)
        core.zonesList = listOf(zone("z1"))
    }

    @After
    fun tearDown() = app.stop()

    @Test
    fun anExactTitlePlays() {
        assertEquals("Mezzanine", app.playSpoken("Mezzanine").getOrNull()?.title)
    }

    @Test
    fun anArtistPlaysSomethingOfTheirs() {
        val played = app.playSpoken("Massive Attack").getOrNull()
        assertTrue(
            "expected a Massive Attack record, got $played",
            played?.subtitle?.contains("Massive Attack") == true
        )
    }

    @Test
    fun aMisheardTitleStillLands() {
        // What a recogniser actually produces. An exact match would refuse all
        // three of these, which is the difference between a feature and a
        // party trick.
        assertEquals(
            "The Dark Side of the Moon",
            app.playSpoken("dark side of the moon").getOrNull()?.title
        )
        assertEquals(
            "Kind of Blue",
            app.playSpoken("kind of blu").getOrNull()?.title
        )
    }

    @Test
    fun nothingHeardIsRefusedRatherThanPlayingAtRandom() {
        // The failure mode to avoid: a silent tap starting some arbitrary
        // record in the room.
        val result = app.playSpoken("   ")
        assertTrue(result.isFailure)
        assertEquals("Nothing heard", result.exceptionOrNull()?.message)
    }

    @Test
    fun somethingNotInTheLibrarySaysSoRatherThanPlayingSomethingElse() {
        val result = app.playSpoken("Taylor Swift")
        assertTrue("nothing in this library resembles it", result.isFailure)
    }

    @Test
    fun withNoZonesItRefuses() {
        core.zonesList = emptyList()
        val result = app.playSpoken("Mezzanine")
        assertTrue(result.isFailure)
        assertEquals("No zones available", result.exceptionOrNull()?.message)
    }
}
