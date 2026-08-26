package com.musicd.lite

import com.musicd.lite.roon.Zone
import com.musicd.lite.store.MemoryStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Which zone the notification, the media session, the widget and the tile are
 * about.
 *
 * This exists because three of those four resolved it themselves and only one
 * of them checked that the remembered zone still existed. Zone ids do not
 * survive a Core restart, a regrouping or a rename, and Roon answers a
 * transport command for an unknown zone by quietly doing nothing — so a stale
 * id turned every button into a no-op with no error anywhere to find.
 */
class ActiveZoneTest {

    private lateinit var core: FakeCore
    private lateinit var app: MusicdLite

    private val noAssets = object : com.musicd.lite.api.StaticAssets {
        override fun read(path: String): Pair<ByteArray, String>? = null
    }

    private fun zone(id: String, state: String = "stopped"): Zone = Zone.parse(
        JSONObject("""{"zone_id":"$id","display_name":"Zone $id","state":"$state"}""")
    )

    @Before
    fun setUp() {
        core = FakeCore()
        app = MusicdLite(
            store = MemoryStore(),
            assets = noAssets,
            artDir = null,
            version = "test",
            httpPort = 0
        ) { _, _, _ -> core }
    }

    @After
    fun tearDown() = app.stop()

    @Test
    fun theRememberedZoneWins() {
        core.zonesList = listOf(zone("a"), zone("b"), zone("c"))
        app.settings.saveLastZone("b")
        assertEquals("b", app.activeZone()?.zoneId)
    }

    @Test
    fun aRememberedZoneThatNoLongerExistsIsIgnored() {
        // The bug: "gone" was passed straight to roon.control, which accepted
        // it and did nothing. Every transport button was dead until the user
        // opened the app and picked a zone again.
        core.zonesList = listOf(zone("a"), zone("b", state = "playing"))
        app.settings.saveLastZone("gone")
        assertEquals("the playing zone is the honest fallback", "b", app.activeZone()?.zoneId)
    }

    @Test
    fun withNothingRememberedThePlayingZoneWins() {
        core.zonesList = listOf(zone("a"), zone("b", state = "playing"), zone("c"))
        assertEquals("b", app.activeZone()?.zoneId)
    }

    @Test
    fun withNothingPlayingTheFirstZoneWins() {
        core.zonesList = listOf(zone("a"), zone("b"))
        assertEquals("a", app.activeZone()?.zoneId)
    }

    @Test
    fun noZonesIsNullRatherThanAThrow() {
        core.zonesList = emptyList()
        app.settings.saveLastZone("a")
        assertNull(app.activeZone())
    }

    @Test
    fun playRandomAlbumRefusesRatherThanPlayingIntoAStaleZone() {
        core.zonesList = emptyList()
        app.settings.saveLastZone("gone")
        val result = app.playRandomAlbum()
        assertEquals(
            "No zones available",
            result.exceptionOrNull()?.message
        )
    }
}
