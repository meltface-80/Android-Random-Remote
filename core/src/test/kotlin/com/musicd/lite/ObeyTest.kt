package com.musicd.lite

import com.musicd.lite.roon.Zone
import com.musicd.lite.store.MemoryStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a spoken phrase actually sends to Roon.
 *
 * The parser has its own tests; these check the other half — that the command
 * reaches the Core as the right call, on the right zone, by the right amount.
 * A phrase can parse perfectly and still turn the wrong knob.
 */
class ObeyTest {

    private lateinit var core: FakeCore
    private lateinit var app: MusicdLite

    private val noAssets = object : com.musicd.lite.api.StaticAssets {
        override fun read(path: String): Pair<ByteArray, String>? = null
    }

    /** A zone with a -80..0 dB output stepping in 0.5 dB, like a real DAC. */
    private fun zone(id: String = "z1", volume: Boolean = true): Zone = Zone.parse(
        JSONObject(
            if (volume) """
            {"zone_id":"$id","display_name":"Study","state":"playing",
             "outputs":[{"output_id":"o1","display_name":"Study",
               "volume":{"type":"db","min":-80,"max":0,"value":-30,"step":0.5,
                         "is_muted":false}}]}
            """.trimIndent()
            else """
            {"zone_id":"$id","display_name":"Fixed","state":"playing",
             "outputs":[{"output_id":"o1","display_name":"DAC"}]}
            """.trimIndent()
        )
    )

    @Before
    fun setUp() {
        core = FakeCore()
        core.addAlbum("Mezzanine", "Massive Attack")
        core.addAlbum("Kind of Blue", "Miles Davis")
        app = MusicdLite(
            store = MemoryStore(),
            assets = noAssets,
            artDir = null,
            version = "test",
            httpPort = 0
        ) { _, _, _ -> core }
        app.index.build(core.tree)
        core.zonesList = listOf(zone())
    }

    @After
    fun tearDown() = app.stop()

    @Test
    fun pauseAndResumeAreTransportNotSearches() {
        assertTrue(app.obey("pause music").ok)
        assertEquals(listOf("control:z1:pause"), core.calls)

        core.calls.clear()
        assertTrue(app.obey("play music").ok)
        assertEquals(
            "\"play music\" must resume, not go looking for an album",
            listOf("control:z1:play"), core.calls
        )
    }

    @Test
    fun volumeUpMovesFivePercentOfTheRange() {
        assertTrue(app.obey("turn up volume").ok)
        // 5% of an 80 dB range is 4 dB, which is 8 steps of 0.5 dB.
        assertEquals(listOf("volume:o1:relative_step:8.0"), core.calls)
    }

    @Test
    fun volumeDownIsTheSameStepTheOtherWay() {
        assertTrue(app.obey("turn down the volume").ok)
        assertEquals(listOf("volume:o1:relative_step:-8.0"), core.calls)
    }

    @Test
    fun volumeNeverArrivesAsASearch() {
        // The failure this guards is silent: the phrase would be looked up in
        // the library, nothing would match, and the volume would not move.
        for (phrase in listOf("turn it up", "louder", "volume up")) {
            core.calls.clear()
            app.obey(phrase)
            assertTrue(
                "\"$phrase\" did not change the volume",
                core.calls.any { it.startsWith("volume:") }
            )
        }
    }

    @Test
    fun aZoneWithNoVolumeControlSaysSoRatherThanFailingSilently() {
        core.zonesList = listOf(zone(volume = false))
        app.settings.saveLastZone("z1")
        val outcome = app.obey("turn it up")
        assertFalse(outcome.ok)
        assertTrue(outcome.message.contains("no volume control"))
        assertTrue(core.calls.isEmpty())
    }

    @Test
    fun playFollowedByANameReachesTheLibrary() {
        val outcome = app.obey("play Mezzanine")
        assertTrue(outcome.message, outcome.ok)
        assertTrue("expected the album in the reply", outcome.message.contains("Mezzanine"))
    }

    @Test
    fun tracksAndMutingReachRoon() {
        app.obey("next track")
        assertEquals(listOf("control:z1:next"), core.calls)

        core.calls.clear()
        app.obey("go back")
        assertEquals(listOf("control:z1:previous"), core.calls)

        core.calls.clear()
        app.obey("mute")
        assertEquals(listOf("mute:o1:mute"), core.calls)

        core.calls.clear()
        app.obey("unmute")
        assertEquals(listOf("mute:o1:unmute"), core.calls)
    }

    @Test
    fun somethingUnintelligibleChangesNothing() {
        val outcome = app.obey("play the album")
        assertFalse(outcome.ok)
        assertEquals("Didn't understand that", outcome.message)
        assertTrue("nothing should have reached Roon", core.calls.isEmpty())
    }

    @Test
    fun aCommandGoesToTheZoneOnScreenNotWhicheverIsFirst() {
        // Two zones, and the one being watched is the second. Addressing the
        // wrong room is the worst outcome here: it is not an error, it is
        // music starting somewhere else in the house.
        core.zonesList = listOf(zone("a"), zone("b"))
        app.settings.saveLastZone("b")
        app.obey("pause")
        assertEquals(listOf("control:b:pause"), core.calls)
    }
}
