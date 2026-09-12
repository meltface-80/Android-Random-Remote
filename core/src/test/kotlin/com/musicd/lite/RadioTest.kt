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

    // ------------------------------------------------- a clock of its own
    //
    // THE BUG THE OWNER REPORTED: nothing is ever queued. Every test above
    // hands the radio its second reading of the stop, and that is exactly what
    // real life does not do. Roon's zone subscription is an event feed: it
    // reports the end of a queue in a short burst and then, with nothing
    // playing anywhere, says nothing at all. The settle needs a reading FOUR
    // SECONDS after the first one, and no such reading arrives — so the radio
    // sat at "one stop seen, waiting for the next" forever. It only ever fired
    // when some OTHER zone was playing, because that zone's seek ticks are
    // what kept calling the gate.

    /** Records what was asked for instead of waiting for it. */
    private class FakeDelay : Radio.Delay {
        val pending = ArrayList<Pair<Long, () -> Unit>>()
        override fun after(ms: Long, task: () -> Unit) { pending += ms to task }
        fun runAll() {
            val due = ArrayList(pending)
            pending.clear()
            due.forEach { it.second() }
        }
    }

    @Test
    fun theEndOfAQueueArmsARecheck() {
        val delay = FakeDelay()
        var nowMs = 1_000_000L
        val radio = Radio(app, clock = { nowMs }, delay = delay)
        radio.setEnabled("z1", true)

        // The end of a queue as Roon actually reports it: a burst, then silence.
        radio.onZones(listOf(zone("z1", "stopped")))
        nowMs += 200
        radio.onZones(listOf(zone("z1", "stopped")))
        nowMs += 700
        radio.onZones(listOf(zone("z1", "stopped")))

        // The burst is all inside the settle, so nothing has started — right so
        // far. What matters is that something is going to look again.
        assertEquals("the radio is waiting for a reading that will never come",
            1, delay.pending.size)
        assertTrue("it must wait out the settle, not less",
            delay.pending[0].first >= 4_000L)
    }

    @Test
    fun theRecheckIsWhatStartsTheAlbum() {
        val delay = FakeDelay()
        var nowMs = 1_000_000L
        val radio = Radio(app, clock = { nowMs }, delay = delay)
        core.zonesList = listOf(zone("z1", "stopped"))
        radio.setEnabled("z1", true)
        radio.onZones(core.zonesList)
        assertTrue("nothing may start on the first reading of a stop", core.invoked.isEmpty())

        // Roon has sent nothing since. The settle elapses anyway.
        nowMs += 5_000
        delay.runAll()

        val deadline = System.currentTimeMillis() + 5_000
        while (core.invoked.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("no album was ever put on the zone: " + core.invoked,
            core.invoked.any { it.startsWith("play_now") && it.endsWith("@z1") })
    }

    @Test
    fun aZoneThatStartsPlayingAgainStopsBeingWatched() {
        val delay = FakeDelay()
        var nowMs = 1_000_000L
        val radio = Radio(app, clock = { nowMs }, delay = delay)
        radio.setEnabled("z1", true)
        radio.onZones(listOf(zone("z1", "stopped")))
        assertEquals(1, delay.pending.size)

        // The user pressed play. The armed recheck still runs — it was already
        // scheduled — but it finds a playing zone, does nothing, and arms
        // nothing further.
        nowMs += 5_000
        core.zonesList = listOf(zone("z1", "playing"))
        delay.runAll()
        assertTrue("a playing zone must not be watched", delay.pending.isEmpty())
        assertTrue(core.invoked.isEmpty())
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
