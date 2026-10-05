package com.musicd.lite

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLEncoder

/**
 * /api/live over the real server: the page's long poll.
 *
 * Rouen's page polls this every three seconds; this build's page sends back
 * the token it was given as `wait_for` and the server holds the request. These
 * pin the half the page cannot see — that a request with nothing new really
 * is held, and that a change from ANOTHER request (another device, in life)
 * releases it.
 */
class LiveApiTest {

    private lateinit var f: ApiFixture

    @Before fun setUp() { f = ApiFixture().start() }
    @After fun tearDown() = f.stop()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    @Test
    fun theFirstAskAnswersAtOnceWithEveryRevisionTheScreensRead() {
        val j = f.json("/api/live")
        val rev = j.getJSONObject("rev")
        for (k in listOf("snapshot", "library", "dates", "plays", "settings", "labels",
                         "picks", "discover", "later", "day", "aotd")) {
            assertTrue("rev.$k missing", rev.has(k))
        }
        assertTrue(j.getString("token").isNotEmpty())
        // The snapshot is the index this server built in setUp.
        assertEquals(f.app.index.builtAt.toString(), rev.getString("snapshot"))
    }

    @Test
    fun aRequestWithNothingNewIsHeldThenAnswersUnchanged() {
        val token = f.json("/api/live").getString("token")
        val t = System.nanoTime()
        val again = f.json("/api/live?wait_for=${enc(token)}&timeout=400")
        val ms = (System.nanoTime() - t) / 1_000_000
        assertTrue("answered in ${ms}ms — the long poll is not holding", ms >= 350)
        assertEquals(token, again.getString("token"))
    }

    @Test
    fun aSettingChangedElsewhereReleasesAHeldRequest() {
        val first = f.json("/api/live")
        val token = first.getString("token")
        Thread {
            Thread.sleep(300)
            // Another device reorders Home.
            f.post("/api/settings/home-rows", """{"rows":[{"id":"genres","on":true}]}""")
        }.start()
        val t = System.nanoTime()
        val next = f.json("/api/live?wait_for=${enc(token)}&timeout=10000")
        val ms = (System.nanoTime() - t) / 1_000_000
        assertTrue("answered in ${ms}ms", ms in 250..8_000)
        assertNotEquals(
            first.getJSONObject("rev").getString("settings"),
            next.getJSONObject("rev").getString("settings")
        )
    }

    @Test
    fun aRebuiltLibraryMovesTheSnapshot() {
        val before = f.json("/api/live").getJSONObject("rev")
        Thread.sleep(5)   // builtAt is a clock reading
        f.core.addAlbum("Heligoland", "Massive Attack", "img-Heligoland")
        f.app.index.build(f.core.tree)
        val after = f.json("/api/live").getJSONObject("rev")
        assertNotEquals(before.getString("snapshot"), after.getString("snapshot"))
        assertNotEquals(before.getString("library"), after.getString("library"))
    }

    @Test
    fun whereTheZoneIsKeptIsNotASettingsChange() {
        // The page reports the zone it is watching on every zone-state read;
        // counting that as a settings write would wake every page each time
        // anyone looked at another room.
        val before = f.json("/api/live").getJSONObject("rev").getString("settings")
        f.json("/api/zone-state?zone=z1")
        assertEquals(before, f.json("/api/live").getJSONObject("rev").getString("settings"))
    }
}
