package com.musicd.lite

import com.musicd.lite.api.StaticAssets
import com.musicd.lite.roon.Zone
import com.musicd.lite.store.MemoryStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * The whole request path, over a real socket.
 *
 * A scripted Core stands in for Roon, but everything above it is the shipping
 * code: the HTTP server, the router, the browse walkers and the JSON the
 * unmodified MusicD-Remote front-end reads. Getting a response SHAPE wrong is
 * the failure mode that matters most here — the front-end is not ours to
 * change, so a renamed field is a blank screen.
 */
class RemoteApiTest {

    private lateinit var core: FakeCore
    private lateinit var app: MusicdLite
    private lateinit var store: MemoryStore

    private val assets = object : StaticAssets {
        private val files = mapOf(
            "/index.html" to ("<!doctype html><title>MusicD</title>" to "text/html"),
            "/app.js" to ("console.log('ui');" to "application/javascript"),
            "/display.html" to ("<!doctype html><title>Wall</title>" to "text/html")
        )

        override fun read(path: String): Pair<ByteArray, String>? =
            files[path]?.let { it.first.toByteArray() to it.second }
    }

    @Before
    fun setUp() {
        core = FakeCore()
        listOf(
            "Blue Lines" to "Massive Attack",
            "Dummy" to "Portishead",
            "Mezzanine" to "Massive Attack",
            "Third" to "Portishead",
            "Kid A" to "Radiohead"
        ).forEach { (t, a) -> core.addAlbum(t, a, "img-${t.replace(' ', '-')}") }
        core.genres["Trip-Hop"] = mutableListOf(0, 1, 2)

        core.zonesList = listOf(
            Zone.parse(
                JSONObject(
                    """
                    {"zone_id":"z1","display_name":"Study","state":"playing",
                     "is_play_allowed":true,"is_pause_allowed":true,
                     "is_next_allowed":true,"is_previous_allowed":true,"is_seek_allowed":true,
                     "settings":{"shuffle":false,"loop":"disabled","auto_radio":true},
                     "outputs":[{"output_id":"o1","zone_id":"z1","display_name":"Amp",
                       "volume":{"type":"db","min":-80,"max":0,"value":-25,"step":0.5,"is_muted":false}}],
                     "now_playing":{"three_line":{"line1":"Teardrop","line2":"Massive Attack","line3":"Mezzanine"},
                       "length":330,"seek_position":40,"image_key":"img-Mezzanine"}}
                    """.trimIndent()
                )
            )
        )
        core.outputsList = core.zonesList.flatMap { it.outputs }

        store = MemoryStore()
        app = MusicdLite(
            store = store,
            assets = assets,
            artDir = null,
            version = "test",
            httpPort = 0
        ) { _, _, _ -> core }
        app.start()
        // Pairing is what normally triggers this; the scripted Core is simply
        // always up, so the walk is asked for directly.
        app.index.build(core.tree)
    }

    @After
    fun tearDown() {
        app.stop()
    }

    // ------------------------------------------------------------- plumbing

    private fun get(path: String): Pair<Int, String> = request("GET", path, null)

    private fun post(path: String, body: String): Pair<Int, String> = request("POST", path, body)

    private fun request(method: String, path: String, body: String?): Pair<Int, String> {
        val conn = URL(app.rootUrl + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 20000
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    private fun json(path: String): JSONObject {
        val (code, text) = get(path)
        assertEquals("GET $path -> $text", 200, code)
        return JSONObject(text)
    }

    // ------------------------------------------------------------- the app

    @Test
    fun servesTheBundledFrontEnd() {
        val (code, text) = get("/")
        assertEquals(200, code)
        assertTrue(text.contains("MusicD"))

        assertEquals(200, get("/app.js").first)
        // The wall display is its own page.
        assertTrue(get("/display").second.contains("Wall"))
        // A deep link is still the single-page app, not a 404.
        assertTrue(get("/library/albums").second.contains("MusicD"))
    }

    @Test
    fun statusReportsPairingAndTheIndex() {
        val s = json("/api/status")
        assertTrue(s.getBoolean("paired"))
        assertEquals("Fake Core", s.getString("core_name"))
        assertEquals(1, s.getInt("zone_count"))
        assertEquals(5, s.getInt("index_count"))
        assertTrue(s.getBoolean("lite"))
        assertEquals("paired", s.getString("stage"))
    }

    @Test
    fun zonesCarryOutputsAndPlaybackModes() {
        val zones = json("/api/zones").getJSONArray("zones")
        assertEquals(1, zones.length())
        val z = zones.getJSONObject(0)
        assertEquals("Study", z.getString("display_name"))
        assertEquals("playing", z.getString("state"))
        assertTrue(z.getJSONObject("settings").getBoolean("auto_radio"))
        assertEquals("o1", z.getJSONArray("outputs").getJSONObject(0).getString("output_id"))
    }

    @Test
    fun zoneStateCarriesNowPlaying() {
        val z = json("/api/zone-state?zone=z1").getJSONObject("zone")
        val np = z.getJSONObject("now_playing")
        assertEquals("Teardrop", np.getString("line1"))
        assertEquals("Mezzanine", np.getString("line3"))
        assertEquals(330, np.getInt("length"))
        assertEquals(40, np.getInt("seek_position"))
        // The track artist is offered as a link only when the library can open
        // a screen for it.
        val artists = np.getJSONArray("artists")
        assertEquals("Massive Attack", artists.getJSONObject(0).getString("name"))
        assertTrue(artists.getJSONObject(0).getBoolean("linkable"))
        assertEquals(-25.0, z.getJSONArray("outputs").getJSONObject(0)
            .getJSONObject("volume").getDouble("value"), 0.001)
    }

    @Test
    fun anUnknownZoneIsNullNotAnError() {
        val body = json("/api/zone-state?zone=nope")
        assertTrue(body.isNull("zone"))
    }

    @Test
    fun randomAlbumsComeFromTheSnapshot() {
        val body = json("/api/random-albums?count=3")
        assertEquals(5, body.getInt("total"))
        assertFalse(body.getBoolean("filtered"))
        val albums = body.getJSONArray("albums")
        assertEquals(3, albums.length())
        val first = albums.getJSONObject(0)
        // The tile shape the front-end reads.
        assertTrue(first.has("offset"))
        assertTrue(first.has("title"))
        assertTrue(first.has("subtitle"))
        assertTrue(first.has("image_key"))
        assertTrue("no streaming service is connected, so no source badge", first.isNull("source"))
    }

    @Test
    fun aGenreFilterWalksRoonsOwnList() {
        val body = json("/api/random-albums?count=10&filter_type=genre&filter_value=Trip-Hop")
        assertTrue(body.getBoolean("filtered"))
        assertEquals(3, body.getInt("total"))
        val titles = (0 until body.getJSONArray("albums").length())
            .map { body.getJSONArray("albums").getJSONObject(it).getString("title") }
        assertTrue(titles.all { it in listOf("Blue Lines", "Dummy", "Mezzanine") })
    }

    @Test
    fun theLibraryWallPagesAndSorts() {
        val page = json("/api/library/albums?sort=album&offset=1&count=2")
        assertEquals(5, page.getInt("total"))
        assertEquals(1, page.getInt("offset"))
        val titles = (0 until 2).map { page.getJSONArray("albums").getJSONObject(it).getString("title") }
        assertEquals(listOf("Dummy", "Kid A"), titles)
    }

    @Test
    fun openingAnAlbumReturnsTracksAndActions() {
        val body = json("/api/album?offset=1&title=Dummy&subtitle=Portishead")
        assertEquals("Dummy", body.getJSONObject("album").getString("title"))
        assertEquals(3, body.getJSONArray("tracks").length())
        assertEquals("Opening", body.getJSONArray("tracks").getJSONObject(0).getString("title"))
        val kinds = (0 until body.getJSONArray("actions").length())
            .map { body.getJSONArray("actions").getJSONObject(it).getString("kind") }
        assertEquals(listOf("play_now", "play_next", "queue", "radio"), kinds)
    }

    @Test
    fun playingAnAlbumReachesRoon() {
        val (code, text) = post(
            "/api/play",
            """{"offset":2,"zone_or_output_id":"z1","title":"Mezzanine","subtitle":"Massive Attack"}"""
        )
        assertEquals(text, 200, code)
        assertTrue(JSONObject(text).getBoolean("ok"))
        assertEquals(listOf("play_now:playmenu:2@z1"), core.invoked)
    }

    @Test
    fun playingAMovedAlbumIsA409NotASilentWrongAlbum() {
        val (code, text) = post(
            "/api/play",
            """{"offset":0,"zone_or_output_id":"z1","title":"An Album That Left","subtitle":"Nobody"}"""
        )
        assertEquals(409, code)
        assertTrue(JSONObject(text).has("error"))
        assertTrue("nothing must have been played", core.invoked.isEmpty())
    }

    @Test
    fun queueingSeveralAlbumsPlaysTheFirstAndQueuesTheRest() {
        val (code, text) = post(
            "/api/play-multi",
            """{"zone_or_output_id":"z1","albums":[
                 {"offset":0,"title":"Blue Lines","subtitle":"Massive Attack"},
                 {"offset":1,"title":"Dummy","subtitle":"Portishead"},
                 {"offset":3,"title":"Third","subtitle":"Portishead"}]}"""
        )
        assertEquals(text, 200, code)
        assertEquals(3, JSONObject(text).getInt("queued"))
        assertEquals(
            listOf("play_now:playmenu:0@z1", "queue:playmenu:1@z1", "queue:playmenu:3@z1"),
            core.invoked
        )
    }

    @Test
    fun aTrackIsPlayedByIndexAndVerifiedByTitle() {
        val (code, text) = post(
            "/api/play-track",
            """{"offset":1,"zone_or_output_id":"z1","track_index":2,"track_title":"Closer",
                "kind":"queue","title":"Dummy","subtitle":"Portishead"}"""
        )
        assertEquals(text, 200, code)
        assertEquals("Closer", JSONObject(text).getString("track"))
        assertEquals(listOf("queue:track:1:2@z1"), core.invoked)
    }

    @Test
    fun searchAnswersFromTheSnapshot() {
        val body = json("/api/search?q=portishead")
        val albums = body.getJSONArray("albums")
        assertEquals(2, albums.length())
        assertTrue(albums.getJSONObject(0).has("score"))
        assertEquals("Portishead", body.getJSONArray("artists").getJSONObject(0).getString("name"))
        // Labels are not in this build; an empty array keeps the sheet's label
        // section collapsed rather than erroring.
        assertEquals(0, body.getJSONArray("labels").length())
    }

    @Test
    fun artistPagesSeparatePrimaryFromFeatured() {
        core.addAlbum("A Collaboration", "Portishead / Somebody Else")
        app.index.build(core.tree)
        val body = json("/api/artist-albums?artist=Portishead")
        assertEquals(2, body.getJSONArray("primary").length())
        assertEquals(1, body.getJSONArray("featured").length())
        assertEquals(
            "A Collaboration",
            body.getJSONArray("featured").getJSONObject(0).getString("title")
        )
    }

    @Test
    fun transportCommandsReachRoon() {
        assertEquals(200, post("/api/control", """{"zone_or_output_id":"z1","command":"playpause"}""").first)
        assertEquals(200, post("/api/seek", """{"zone_or_output_id":"z1","how":"absolute","seconds":90}""").first)
        assertEquals(200, post("/api/volume", """{"zone_or_output_id":"z1","how":"absolute","value":-20}""").first)
        assertEquals(200, post("/api/pause-all", "{}").first)
        assertEquals(
            listOf("control:z1:playpause", "seek:z1:absolute:90", "volume:o1:absolute:-20.0", "pauseall"),
            core.calls
        )
    }

    @Test
    fun anInvalidCommandIsRefusedBeforeItReachesRoon() {
        val (code, text) = post("/api/control", """{"zone_or_output_id":"z1","command":"selfdestruct"}""")
        assertEquals(400, code)
        assertTrue(JSONObject(text).getString("error").contains("invalid command"))
        assertTrue(core.calls.isEmpty())
    }

    @Test
    fun zoneSettingsRejectAnUnknownLoopMode() {
        assertEquals(400, post("/api/zone-settings", """{"zone_or_output_id":"z1","loop":"next"}""").first)
        assertEquals(200, post("/api/zone-settings", """{"zone_or_output_id":"z1","loop":"loop_one"}""").first)
        assertTrue(core.calls.any { it.startsWith("settings:z1:") && it.contains("loop_one") })
    }

    @Test
    fun homeRowsRepairThemselvesAndReportWhatIsUnavailable() {
        val rows = json("/api/settings/home-rows").getJSONArray("rows")
        assertEquals(7, rows.length())
        val lotw = (0 until rows.length()).map { rows.getJSONObject(it) }
            .first { it.getString("id") == "lotw" }
        // The Label of the Week row cannot be served by this build, and says so
        // rather than rendering empty.
        assertNotNull(lotw.getString("unavailable"))

        // A stored layout that predates a row must still get that row back.
        val (code, _) = post("/api/settings/home-rows", """{"rows":[{"id":"random","on":false}]}""")
        assertEquals(200, code)
        val repaired = json("/api/settings/home-rows").getJSONArray("rows")
        assertEquals(7, repaired.length())
        assertEquals("random", repaired.getJSONObject(0).getString("id"))
        assertFalse(repaired.getJSONObject(0).getBoolean("on"))
    }

    @Test
    fun labelsAnswerAsOffRatherThanMissing() {
        // The front-end already treats enabled:false as "hide the Labels screen",
        // so the feature disappears through the UI's own supported path.
        val body = json("/api/settings/labels")
        assertFalse(body.getBoolean("enabled"))
        assertEquals(0, body.getInt("count"))
        assertTrue(body.getString("unavailable").isNotEmpty())
        // Switching it on is refused with a reason, not silently ignored.
        assertEquals(400, post("/api/settings/labels", """{"enabled":true}""").first)
    }

    @Test
    fun featuresNotInThisBuildDegradeInsteadOfErroring() {
        assertEquals(0, json("/api/filters/labels").getJSONArray("labels").length())
        assertTrue(json("/api/home/label-of-the-week").isNull("label"))
        assertFalse(json("/api/settings/qobuz").getBoolean("connected"))
        assertFalse(json("/api/settings/tidal/status").getBoolean("connected"))
        assertFalse(json("/api/update/status").getBoolean("available"))
        assertFalse(json("/api/music-mount").getBoolean("mounted"))
        assertEquals(0, json("/api/playlists").getJSONArray("playlists").length())
        // An endpoint with no "off" shape says what is missing rather than 404.
        val (code, text) = get("/api/labels/logo-candidates")
        assertEquals(501, code)
        assertTrue(JSONObject(text).getString("error").isNotEmpty())
    }

    @Test
    fun unknownEndpointsAre404() {
        assertEquals(404, get("/api/does-not-exist").first)
    }

    @Test
    fun radioIsPerZoneAndPersists() {
        assertFalse(json("/api/radio?zone=z1").getBoolean("enabled"))
        assertEquals(200, post("/api/radio", """{"zone":"z1","enabled":true}""").first)
        assertTrue(json("/api/radio?zone=z1").getBoolean("enabled"))
        assertFalse(json("/api/radio?zone=other").getBoolean("enabled"))
        assertEquals(200, post("/api/radio", """{"zone":"z1","enabled":false}""").first)
        assertFalse(json("/api/radio?zone=z1").getBoolean("enabled"))
    }

    @Test
    fun homeRowsServeAlbumsAndTheDailyPick() {
        val unplayed = json("/api/home/unplayed?count=3")
        assertEquals(5, unplayed.getInt("total"))
        assertEquals(3, unplayed.getJSONArray("albums").length())

        assertNotNull(json("/api/home/album-of-the-day").getJSONObject("album").getString("title"))
        assertEquals(0, json("/api/home/history").getJSONArray("albums").length())
    }

    @Test
    fun theWallDisplayRefusesWhileItIsSwitchedOff() {
        assertEquals(403, get("/api/display/content").first)
        assertEquals(200, post("/api/settings/display", """{"enabled":true,"seconds":30}""").first)
        val body = json("/api/display/content?zone=z1")
        assertEquals(30, body.getInt("seconds"))
        assertEquals("Study", body.getString("zone"))
        assertEquals("Mezzanine", body.getJSONObject("album").getString("title"))
    }

    @Test
    fun nowPlayingMapsBackOntoALibraryTile() {
        val body = json("/api/album/now-playing?zone=z1")
        val album = body.getJSONObject("album")
        assertEquals("Mezzanine", album.getString("title"))
        // The offset is what makes the tile openable and playable.
        assertEquals(2, album.getInt("offset"))
    }

    @Test
    fun decadesAreEmptyUntilYearsAreKnown() {
        assertEquals(0, json("/api/filters/decades").getJSONArray("decades").length())
        store.putAlbumYear(
            app.index.albums.first { it.title == "Kid A" }.key, 2000,
            com.musicd.lite.store.YearSource.MUSICBRAINZ
        )
        val decades = json("/api/filters/decades").getJSONArray("decades")
        assertEquals(1, decades.length())
        assertEquals("2000s", decades.getJSONObject(0).getString("title"))
        assertEquals("1 album", decades.getJSONObject(0).getString("subtitle"))
    }

    @Test
    fun genresComeFromRoonsOwnTree() {
        val genres = json("/api/filters/genres").getJSONArray("genres")
        assertEquals(1, genres.length())
        assertEquals("Trip-Hop", genres.getJSONObject(0).getString("title"))
    }

    @Test
    fun anUnpairedCoreAnswers503RatherThanHanging() {
        core.paired = false
        val (code, text) = get("/api/outputs")
        assertEquals(503, code)
        assertTrue(JSONObject(text).getString("error").isNotEmpty())
        // Status still answers, because the pairing screen is what reads it.
        assertFalse(json("/api/status").getBoolean("paired"))
    }
}
