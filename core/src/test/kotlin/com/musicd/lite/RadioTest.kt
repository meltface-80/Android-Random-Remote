package com.musicd.lite

import com.musicd.lite.api.StaticAssets
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
 * Random Album Radio: when a zone's queue runs out, put another album on.
 *
 * Every test here is about NOT acting. Roon reports a stopped zone at several
 * moments that are not the end of a queue — while a track loads, between
 * albums, and immediately after the user pressed stop themselves — and putting
 * an album on during any of those takes the decision away from the person
 * holding the phone.
 */
class RadioTest {

    private lateinit var core: FakeCore
    private lateinit var app: MusicdLite

    private val noAssets = object : StaticAssets {
        override fun read(path: String): Pair<ByteArray, String>? = null
    }

    private fun zone(id: String, state: String): Zone = Zone.parse(
        JSONObject("""{"zone_id":"$id","display_name":"Zone $id","state":"$state"}""")
    )

    @Before
    fun setUp() {
        core = FakeCore()
        listOf("Blue Lines", "Dummy", "Mezzanine", "Third").forEach {
            core.addAlbum(it, "An Artist")
        }
        app = MusicdLite(
            store = MemoryStore(),
            assets = noAssets,
            artDir = null,
            version = "test",
            httpPort = 0
        ) { _, _, _ -> core }
        app.index.build(core.tree)
    }

    @After
    fun tearDown() {
        app.stop()
    }

    private fun start(state: String, now: Long, id: String = "z1") =
        app.radio.zonesToStart(listOf(zone(id, state)), now)

    // ------------------------------------------------------------- the switch

    @Test
    fun doesNothingUntilAZoneIsEnabled() {
        assertTrue(start("stopped", 0).isEmpty())
        assertTrue(start("stopped", 100_000).isEmpty())
        assertFalse(app.radio.isEnabled("z1"))
    }

    @Test
    fun theSwitchIsPerZoneAndPersists() {
        app.radio.setEnabled("z1", true)
        assertTrue(app.radio.isEnabled("z1"))
        assertFalse(app.radio.isEnabled("z2"))
        assertEquals(setOf("z1"), app.radio.enabledZones())

        app.radio.setEnabled("z1", false)
        assertFalse(app.radio.isEnabled("z1"))
        assertTrue(app.radio.enabledZones().isEmpty())
    }

    @Test
    fun aZoneThatIsNotEnabledIsNeverPicked() {
        app.radio.setEnabled("z1", true)
        assertTrue(start("stopped", 0, id = "z2").isEmpty())
        assertTrue(start("stopped", 100_000, id = "z2").isEmpty())
    }

    // ------------------------------------------------------------ the settle

    @Test
    fun oneReadingOfAStopIsATransitionNotAState() {
        app.radio.setEnabled("z1", true)
        // The first stopped reading only starts the clock.
        assertTrue(start("stopped", 1_000).isEmpty())
    }

    @Test
    fun aSustainedStopStartsAnAlbum() {
        app.radio.setEnabled("z1", true)
        assertTrue(start("stopped", 1_000).isEmpty())
        assertEquals(listOf("z1"), start("stopped", 1_000 + 5_000))
    }

    @Test
    fun aStopShorterThanTheSettleIsIgnored() {
        app.radio.setEnabled("z1", true)
        assertTrue(start("stopped", 1_000).isEmpty())
        // Still inside the settle window.
        assertTrue(start("stopped", 1_000 + 2_000).isEmpty())
    }

    @Test
    fun playingAgainRestartsTheSettleClock() {
        app.radio.setEnabled("z1", true)
        assertTrue(start("stopped", 1_000).isEmpty())
        // The user pressed play; the queue did not run out after all.
        assertTrue(start("playing", 2_000).isEmpty())
        // A later stop has to serve its own settle from scratch, so the time
        // banked before the track started must not count towards it.
        assertTrue(start("stopped", 20_000).isEmpty())
        assertEquals(listOf("z1"), start("stopped", 20_000 + 5_000))
    }

    @Test
    fun pausedIsNotStopped() {
        app.radio.setEnabled("z1", true)
        assertTrue(start("paused", 1_000).isEmpty())
        assertTrue(start("paused", 100_000).isEmpty())
    }

    @Test
    fun loadingIsNotStopped() {
        app.radio.setEnabled("z1", true)
        assertTrue(start("loading", 1_000).isEmpty())
        assertTrue(start("loading", 100_000).isEmpty())
    }

    // -------------------------------------------------------------- the guard

    @Test
    fun aZoneAlreadyBeingServedIsNotPickedAgain() {
        // The regression this test exists for. The decision used to be made
        // inside the lock and then re-checked by reading `state.busy` outside
        // it — which is true for a zone that is already busy, so every zone
        // update arriving while an album was being queued queued another one.
        app.radio.setEnabled("z1", true)
        assertTrue(start("stopped", 1_000).isEmpty())
        assertEquals(listOf("z1"), start("stopped", 6_000))

        // Nothing has released the zone, so the feed's next several ticks —
        // and they arrive several times a second — must all decline.
        for (tick in 1..10) {
            assertTrue(
                "tick $tick queued a second album while the first was in flight",
                start("stopped", 6_000 + tick * 500L).isEmpty()
            )
        }
    }

    @Test
    fun twoEnabledZonesAreServedIndependently() {
        app.radio.setEnabled("z1", true)
        app.radio.setEnabled("z2", true)
        val zones = listOf(zone("z1", "stopped"), zone("z2", "playing"))

        assertTrue(app.radio.zonesToStart(zones, 1_000).isEmpty())
        // Only the stopped one is picked; the playing one is left alone.
        assertEquals(listOf("z1"), app.radio.zonesToStart(zones, 6_000))
    }

    @Test
    fun switchingRadioOffForgetsTheZonesProgress() {
        app.radio.setEnabled("z1", true)
        assertTrue(start("stopped", 1_000).isEmpty())
        app.radio.setEnabled("z1", false)
        app.radio.setEnabled("z1", true)
        // The settle starts over rather than firing off the old clock.
        assertTrue(start("stopped", 6_000).isEmpty())
        assertEquals(listOf("z1"), start("stopped", 11_000))
    }
}
