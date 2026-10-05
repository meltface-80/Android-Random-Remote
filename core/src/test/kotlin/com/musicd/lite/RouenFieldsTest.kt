package com.musicd.lite

import com.musicd.lite.meta.Metadata
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The smaller answers Rouen's screens read, each in the shape the page reads
 * it — the class of bug this port has shipped most often is a right answer
 * under a name nothing reads, so every assertion here names the field the
 * page's code actually looks at.
 */
class RouenFieldsTest {

    private lateinit var f: ApiFixture

    @Before fun setUp() { f = ApiFixture().start() }
    @After fun tearDown() = f.stop()

    private fun respond(req: okhttp3.Request, body: String): okhttp3.Response =
        okhttp3.Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()

    // ------------------------------------------------------ features that are off

    @Test
    fun theWallDisplayAndWaveformsAnswerOffRatherThanMissing() {
        // The page asks both at boot and on every settings change; a 404 kept
        // them asking, and an answer in the "off" shape is what hides them.
        assertFalse(f.json("/api/settings/display").getBoolean("enabled"))
        assertFalse(f.json("/api/settings/waveform").getBoolean("enabled"))
        assertEquals(501, f.post("/api/settings/display", """{"enabled":true}""").first)
        assertEquals(501, f.post("/api/settings/waveform", """{"enabled":true}""").first)
    }

    @Test
    fun savingADynamicPlaylistIsNotReportedAsSaved() {
        // The page toasts 'Saved "<name>"' on any 2xx. This answered the save
        // with the GET's empty list and a 200.
        val (code, _) = f.post("/api/smart-playlists", """{"name":"Late night","view":{}}""")
        assertTrue("answered $code", code >= 400)
        assertEquals(0, f.json("/api/smart-playlists").getJSONArray("playlists").length())
    }

    @Test
    fun theQobuzLinkIsServedNotRefusedAsAStreamingRoute() {
        // Outbound is refused here, so there is no id to find: the answer is a
        // null url — the page keeps its search link — not a 501.
        val (code, text) = f.get("/api/qobuz-link?album=Dummy&artist=Portishead")
        assertEquals(text, 200, code)
        assertTrue(JSONObject(text).isNull("url"))
    }

    // ---------------------------------------------------------- Random Album

    @Test
    fun randomAlbumPlaysSomethingAndSaysWhat() {
        val j = f.postJson("/api/play-unheard", """{"zone":"z1"}""")
        assertTrue(j.getBoolean("ok"))
        assertTrue(j.getString("album").isNotEmpty())
        assertTrue(j.getString("artist").isNotEmpty())
        assertEquals(1, f.core.invoked.size)
        assertTrue(f.core.invoked[0], f.core.invoked[0].startsWith("play_now:") && f.core.invoked[0].endsWith("@z1"))
    }

    @Test
    fun randomAlbumNeedsAZone() {
        assertEquals(400, f.post("/api/play-unheard", "{}").first)
        assertTrue(f.core.invoked.isEmpty())
    }

    // ------------------------------------------------------------- the radios

    @Test
    fun turningOurRadioOnReportsBothSwitches() {
        val j = f.postJson("/api/radio", """{"zone":"z1","enabled":true}""")
        val radios = j.getJSONObject("radios")
        assertTrue(radios.getBoolean("own"))
        assertFalse(radios.getBoolean("roon"))
    }

    @Test
    fun turningRoonRadioOnStandsOursDownAndSaysSo() {
        f.postJson("/api/radio", """{"zone":"z1","enabled":true}""")
        val j = f.postJson("/api/zone-settings", """{"zone_or_output_id":"z1","auto_radio":true}""")
        val radios = j.getJSONObject("radios")
        // Roon's is what was just asked for, not the feed's stale value.
        assertTrue(radios.getBoolean("roon"))
        assertFalse(radios.getBoolean("own"))
    }

    // ------------------------------------------------------- zone and status

    @Test
    fun zoneStateCarriesWhatIsLeftInTheQueue() {
        f.core.zonesList = listOf(f.zone("z1", "Study", playing = "Mezzanine" to "Massive Attack", remaining = 3))
        val zone = f.json("/api/zone-state?zone=z1").getJSONObject("zone")
        assertEquals(3, zone.getInt("queue_items_remaining"))
    }

    @Test
    fun statusSaysWhenTheLibraryWasLastChecked() {
        assertTrue(f.json("/api/status").has("library_checked_at"))
        f.post("/api/library/rescan", "{}")
        assertTrue(f.json("/api/status").getLong("library_checked_at") > 0)
    }

    @Test
    fun albumOfTheDaySaysWhichDayItIsFor() {
        val j = f.json("/api/home/album-of-the-day")
        assertEquals(f.app.view.aotdDay(), j.getString("day"))
        assertFalse(j.getBoolean("played"))
    }

    // ---------------------------------------------------------- album facts

    private val mbReleases = """
        {"releases":[
          {"score":100,"title":"Dummy","date":"1995"},
          {"score":100,"title":"Dummy","date":"1994-08-22"},
          {"score":95,"title":"Dummy","date":"1994"},
          {"score":40,"title":"Dummy Run","date":"1979-01-01"}
        ]}
    """.trimIndent()

    @Test
    fun theAlbumViewGetsTheReleaseDateToTheDay() {
        f.outbound = { req -> if (req.url.host == "musicbrainz.org") respond(req, mbReleases) else null }
        val j = f.json("/api/album/extras?title=Dummy&artist=Portishead&day=1")
        assertEquals("1994-08-22", j.getString("release_date"))
        assertEquals(1994, j.getInt("year"))
        // And the re-read the view makes when release days move answers from
        // what is known — no second lookup.
        val calls = f.outboundCalls.size
        assertEquals("1994-08-22", f.json("/api/album/release-date?title=Dummy&artist=Portishead")
            .getString("release_date"))
        assertEquals(calls, f.outboundCalls.size)
    }

    @Test
    fun anUnknownReleaseDateIsNullNotAGuess() {
        val j = f.json("/api/album/release-date?title=Nothing%20Known&artist=Nobody")
        assertTrue(j.isNull("release_date"))
        assertTrue(f.outboundCalls.isEmpty())
    }

    @Test
    fun theDescriptionIsCreditedToWhoeverWroteItNotToTheLink() {
        val summary = JSONObject()
            .put("type", "standard")
            .put("extract", "Dummy is the debut studio album by the English band Portishead, released in 1994.")
            .toString()
        f.outbound = { req ->
            when {
                req.url.host == "en.wikipedia.org" && req.url.encodedPath == "/w/api.php" ->
                    respond(req, """{"query":{"search":[{"title":"Dummy (album)"}]}}""")
                req.url.host == "en.wikipedia.org" -> respond(req, summary)
                else -> null
            }
        }
        val album = f.json("/api/album/extras?title=Dummy&artist=Portishead").getJSONObject("album")
        assertTrue(album.getString("description").startsWith("Dummy is the debut"))
        assertEquals("Wikipedia", album.getString("description_source"))
    }

    @Test
    fun theFirstReleaseIsTheEarliestYearToTheMostPreciseDay() {
        val m = Metadata(okhttp3.OkHttpClient(), "test")
        assertEquals("1994-08-22", m.earliestRelease(listOf("1995", "1994", "1994-08-22", "1994-08")))
        assertEquals("1994-08", m.earliestRelease(listOf("1994", "1994-08")))
        assertEquals("1971", m.earliestRelease(listOf("1971", "2011-05-02")))
        assertEquals(null, m.earliestRelease(emptyList()))
    }

    @Test
    fun theLiveDatesRevisionMovesWhenAYearIsLearned() {
        f.outbound = { req -> if (req.url.host == "musicbrainz.org") respond(req, mbReleases) else null }
        val before = f.json("/api/live").getJSONObject("rev").getString("dates")
        f.json("/api/album/extras?title=Dummy&artist=Portishead")
        assertNotEquals(before, f.json("/api/live").getJSONObject("rev").getString("dates"))
    }
}
