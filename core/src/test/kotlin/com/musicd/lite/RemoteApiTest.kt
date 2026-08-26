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
            httpPort = 0,
            importSettleMs = 0          // no need to wait out an import in a test
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

    /**
     * The album view and the now-playing screen read `artists` DIFFERENTLY,
     * and the shipped page is the authority on which is which.
     *
     * The album view wraps each entry itself —
     * `names.map(name => ({ name, linkable: true }))` — because a credit on a
     * library album always has an artist screen to open. Handing it objects
     * makes `name` an object and the credit line renders "[object Object]".
     * The now-playing line is the TRACK artist, which on a compilation often
     * has no screen, so there the server decides and sends `linkable`.
     */
    @Test
    fun theAlbumViewGetsPlainNamesAndNowPlayingGetsLinkability() {
        val album = json("/api/album?offset=1&title=Dummy&subtitle=Portishead")
            .getJSONArray("artists")
        assertEquals("Portishead", album.getString(0))
        assertTrue(
            "the album view wraps these itself, so an object renders as [object Object]",
            album.get(0) is String
        )

        val playing = json("/api/zone-state?zone=z1")
            .getJSONObject("zone").getJSONObject("now_playing").getJSONArray("artists")
        assertTrue("now-playing decides linkability server-side", playing.get(0) is JSONObject)
        assertTrue(playing.getJSONObject(0).has("linkable"))
    }

    @Test
    fun aSplitCreditReachesTheAlbumViewAsSeparateNames() {
        core.addAlbum("A Collaboration", "Portishead / Massive Attack")
        app.index.build(core.tree)
        val names = json("/api/album?offset=5&title=A%20Collaboration&subtitle=Portishead%20%2F%20Massive%20Attack")
            .getJSONArray("artists")
        assertEquals(
            listOf("Portishead", "Massive Attack"),
            (0 until names.length()).map { names.getString(it) }
        )
    }

    /**
     * The artist view reads `bio.text`. This sent `description`, so its
     * `if (!b || !b.text) return` dropped every biography without a trace —
     * no error, no empty state, just no bio on any artist page ever.
     *
     * The album bio next door really does use `description`. Two names for the
     * same idea in one UI is the page's inconsistency, not something to
     * reconcile here: each caller gets the shape it reads.
     */
    @Test
    fun theArtistBioIsSentUnderTheKeyTheViewReads() {
        val body = json("/api/artist-bio?artist=Portishead&album=Dummy")
        if (body.isNull("bio")) return          // no network in this environment
        val bio = body.getJSONObject("bio")
        assertTrue("the view reads bio.text", bio.has("text"))
        assertTrue("the view draws bio.image when present", bio.has("image"))
        assertTrue(bio.has("source"))
    }

    /**
     * The Home row and the Smart Picks screen both read `j.picks`, and each
     * entry has to be a PICK. This sent `albums` full of album rows, so both
     * screens read an empty list and showed their "nothing to suggest yet"
     * state on a library with plenty in it.
     */
    @Test
    fun smartPicksAreSentAsPicksTheCardCanRender() {
        val body = json("/api/smart-picks")
        assertTrue("the screen reads j.picks", body.has("picks"))
        val picks = body.getJSONArray("picks")
        assertTrue("the library has unplayed albums to offer", picks.length() > 0)

        val pick = picks.getJSONObject(0)
        // Every field the card touches, because a missing one renders as
        // "undefined" rather than failing.
        for (field in listOf(
            "artist", "album", "reason", "offset",
            "library_title", "library_subtitle", "image_key", "added"
        )) {
            assertTrue("pick is missing $field", pick.has(field))
        }
        // Picks here come FROM the library, so each one is playable.
        assertTrue(pick.get("offset") is Int)
        // null, not false: there is no service to ask whether it was added, and
        // false would have the card claim it is not in a library that has it.
        assertTrue(pick.isNull("added"))
        assertFalse("no streaming account to add a pick to", body.getBoolean("service_ready"))
    }

    /**
     * "Not for me" posts {artist} and promises "Won't suggest <artist> again".
     * This handler asked for `title`, which is never sent, so every tap came
     * back "title is required".
     */
    @Test
    fun notForMeBlocksTheArtistItWasToldAbout() {
        val before = json("/api/smart-picks").getJSONArray("picks")
        val target = before.getJSONObject(0).getString("artist")

        val (code, text) = post("/api/smart-picks/block", """{"artist":"$target"}""")
        assertEquals(text, 200, code)
        assertTrue(JSONObject(text).getBoolean("ok"))

        // The button blocks the ARTIST, so nothing by them comes back — not
        // just the one record that happened to be offered.
        val after = json("/api/smart-picks").getJSONArray("picks")
        val artists = (0 until after.length()).map { after.getJSONObject(it).getString("artist") }
        assertFalse("$target is still being suggested", target in artists)
    }

    @Test
    fun blockingNeedsAnArtistAndSaysSo() {
        val (code, text) = post("/api/smart-picks/block", "{}")
        assertEquals(400, code)
        assertTrue(text, JSONObject(text).getString("error").contains("artist"))
    }

    /** The card renders `image` as a URL; only sending a key left it blank. */
    @Test
    fun aPicksArtIsAUrlTheCardCanPutInAnImgTag() {
        val picks = json("/api/smart-picks").getJSONArray("picks")
        val withArt = (0 until picks.length()).map { picks.getJSONObject(it) }
            .first { !it.isNull("image_key") }
        assertTrue(
            withArt.getString("image"),
            withArt.getString("image").startsWith("/api/image/")
        )
    }

    @Test
    fun smartPicksSwitchedOffAnswersInTheSameShape() {
        assertEquals(200, post("/api/settings/smart-picks", """{"enabled":false}""").first)
        val body = json("/api/smart-picks")
        assertFalse(body.getBoolean("enabled"))
        assertEquals(0, body.getJSONArray("picks").length())
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
    fun multiSelectQueuesTheFieldTheClientActuallySends() {
        // The client posts `items`, not `albums`. Reading the wrong field made
        // every multi-select fail with "albums array is required".
        val (code, text) = post(
            "/api/play-multi",
            """{"zone_or_output_id":"z1","kind":"play_now","items":[
                 {"offset":0,"title":"Blue Lines","subtitle":"Massive Attack"},
                 {"offset":1,"title":"Dummy","subtitle":"Portishead"},
                 {"offset":3,"title":"Third","subtitle":"Portishead"}]}"""
        )
        assertEquals(text, 200, code)
        val body = JSONObject(text)
        assertEquals(3, body.getInt("queued"))
        assertEquals(0, body.getInt("failed"))
        assertEquals(3, body.getInt("total"))
        assertTrue(body.isNull("first_error"))
        // The order is the point, not an incidental. The user picked these
        // albums in a sequence and the queue is ordered, so the fills run one
        // at a time; queueing them concurrently made this list arbitrary (and
        // occasionally short, which is how CI found it).
        assertEquals(
            listOf("play_now:playmenu:0@z1", "queue:playmenu:1@z1", "queue:playmenu:3@z1"),
            core.invoked
        )
    }

    @Test
    fun multiSelectStillAcceptsBareOffsets() {
        val (code, text) = post(
            "/api/play-multi",
            """{"zone_or_output_id":"z1","kind":"queue","offsets":[0,1]}"""
        )
        assertEquals(text, 200, code)
        assertEquals(2, JSONObject(text).getInt("queued"))
    }

    @Test
    fun multiSelectReportsPartialSuccessRatherThanFailing() {
        // The first album is already playing and everything that queued is in
        // the queue; answering with an error would throw all of that away.
        val (code, text) = post(
            "/api/play-multi",
            """{"zone_or_output_id":"z1","kind":"play_now","items":[
                 {"offset":0,"title":"Blue Lines","subtitle":"Massive Attack"},
                 {"offset":1,"title":"An Album That Left","subtitle":"Nobody"}]}"""
        )
        assertEquals(text, 200, code)
        val body = JSONObject(text)
        assertEquals(1, body.getInt("queued"))
        assertEquals(1, body.getInt("failed"))
        assertEquals(2, body.getInt("total"))
        assertTrue(body.getString("first_error").isNotEmpty())
    }

    @Test
    fun multiSelectNeedsAZoneAndAKind() {
        assertEquals(400, post("/api/play-multi", """{"kind":"queue","offsets":[0]}""").first)
        assertEquals(400, post("/api/play-multi", """{"zone_or_output_id":"z1","offsets":[0]}""").first)
        assertEquals(
            400,
            post("/api/play-multi", """{"zone_or_output_id":"z1","kind":"queue","items":[]}""").first
        )
    }

    @Test
    fun aCredentialSavesAndComesBackMasked() {
        // The page checks j.ok; a response without it reads as "Failed to save
        // token" however well the save went.
        val saved = JSONObject(
            post("/api/settings/discogs-token", """{"token":"abcdef123456"}""").second
        )
        assertTrue(saved.getBoolean("ok"))
        assertTrue(saved.getBoolean("saved"))

        val read = json("/api/settings/discogs-token")
        assertTrue(read.getBoolean("set"))
        // Masked, never echoed back in full.
        assertEquals("••••••••3456", read.getString("masked"))
        assertFalse(read.getString("masked").contains("abcdef"))
    }

    @Test
    fun anEmptyCredentialIsRefusedWithAReason() {
        val body = JSONObject(post("/api/settings/discogs-token", """{"token":"  "}""").second)
        assertFalse(body.getBoolean("ok"))
        assertTrue(body.getString("error").isNotEmpty())
        assertFalse(json("/api/settings/discogs-token").getBoolean("set"))
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

    /**
     * The album hits are `results`, and that name is the whole feature.
     *
     * They were sent as `albums`, which nothing reads, so the search sheet's
     * album section was always empty and tapping the album name on the
     * now-playing screen — which searches to find the album to open — always
     * ended at "Album not yet indexed". The artist chips came through the whole
     * time, which is what made it look like search merely found nothing.
     */
    @Test
    fun searchAnswersFromTheSnapshot() {
        val body = json("/api/search?q=portishead")
        val results = body.getJSONArray("results")
        assertEquals(2, results.length())
        assertTrue(results.getJSONObject(0).has("score"))
        assertTrue("the sheet reads `results`", body.has("results"))
        assertEquals(2, body.getInt("count"))
        assertEquals("portishead", body.getString("query"))
        assertEquals("Portishead", body.getJSONArray("artists").getJSONObject(0).getString("name"))
        // Labels are not in this build; an empty array keeps the sheet's label
        // section collapsed rather than erroring.
        assertEquals(0, body.getJSONArray("labels").length())
    }

    /**
     * The now-playing screen's album link searches for the album by title and
     * opens the match. This is the exact call it makes.
     */
    @Test
    fun theNowPlayingAlbumLinkCanFindItsAlbum() {
        val body = json("/api/search?q=Mezzanine&limit=20")
        val results = body.getJSONArray("results")
        val match = (0 until results.length()).map { results.getJSONObject(it) }
            .firstOrNull { it.getString("title").equals("Mezzanine", ignoreCase = true) }
        assertNotNull("the link has nothing to open without this", match)
        // It opens on the offset, so a hit with no offset is no use.
        assertTrue(match!!.has("offset"))
        assertEquals("Massive Attack", match.getString("subtitle"))
    }

    @Test
    fun anEmptyQueryStillAnswersInTheSheetsShape() {
        val body = json("/api/search?q=")
        assertEquals(0, body.getJSONArray("results").length())
        assertEquals(0, body.getJSONArray("artists").length())
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

    /**
     * The two radios are mutually exclusive, and the server is where that has
     * to live — both directions have to hold however the switch was reached.
     *
     * Roon Radio and Random Album Radio both answer "what plays when the queue
     * runs out", so both on means two things racing to fill one queue.
     */
    @Test
    fun turningOnRoonRadioStandsDownTheRandomAlbumRadio() {
        assertEquals(200, post("/api/radio", """{"zone":"z1","enabled":true}""").first)
        assertTrue(json("/api/radio?zone=z1").getBoolean("enabled"))

        val (code, text) = post("/api/zone-settings", """{"zone_or_output_id":"z1","auto_radio":true}""")
        assertEquals(text, 200, code)
        assertTrue(JSONObject(text).getBoolean("random_album_radio_stands_down"))
        assertFalse("ours must be off once Roon's is on", json("/api/radio?zone=z1").getBoolean("enabled"))
    }

    @Test
    fun turningOnTheRandomAlbumRadioTurnsRoonRadioOff() {
        // The zone reports Roon Radio on, so enabling ours has to switch it off.
        core.zonesList = core.zonesList.map { zone ->
            zone.copy(settings = zone.settings.copy(autoRadio = true))
        }
        val (code, text) = post("/api/radio", """{"zone":"z1","enabled":true}""")
        assertEquals(text, 200, code)
        assertTrue(JSONObject(text).getBoolean("roon_radio_off"))
        assertTrue(
            core.calls.toString(),
            core.calls.any { it.startsWith("settings:z1:") && it.contains("\"auto_radio\":false") }
        )
    }

    @Test
    fun switchingOffRoonRadioLeavesTheOtherAlone() {
        assertEquals(200, post("/api/radio", """{"zone":"z1","enabled":true}""").first)
        val (_, text) = post("/api/zone-settings", """{"zone_or_output_id":"z1","auto_radio":false}""")
        assertFalse(JSONObject(text).getBoolean("random_album_radio_stands_down"))
        assertTrue(json("/api/radio?zone=z1").getBoolean("enabled"))
    }

    @Test
    fun zoneSettingsRejectAnUnknownLoopMode() {
        assertEquals(400, post("/api/zone-settings", """{"zone_or_output_id":"z1","loop":"next"}""").first)
        assertEquals(200, post("/api/zone-settings", """{"zone_or_output_id":"z1","loop":"loop_one"}""").first)
        assertTrue(core.calls.any { it.startsWith("settings:z1:") && it.contains("loop_one") })
    }

    @Test
    fun homeRowsRepairThemselvesAndOfferOnlyRowsThisBuildServes() {
        val rows = json("/api/settings/home-rows").getJSONArray("rows")
        val ids = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
        assertEquals(
            listOf("unplayed", "history", "picks", "random", "library", "genres"),
            ids
        )
        // The settings screen renders its list from this response, so a row
        // this build can never serve must be absent rather than present and
        // greyed out. Label of the week is the one.
        assertFalse("lotw" in ids)

        // A stored layout that predates a row must still get that row back.
        val (code, _) = post("/api/settings/home-rows", """{"rows":[{"id":"random","on":false}]}""")
        assertEquals(200, code)
        val repaired = json("/api/settings/home-rows").getJSONArray("rows")
        assertEquals(6, repaired.length())
        assertEquals("random", repaired.getJSONObject(0).getString("id"))
        assertFalse(repaired.getJSONObject(0).getBoolean("on"))
    }

    /**
     * A stored layout from an older build still names "lotw". It must be
     * dropped on the way back out, not carried through into the settings list.
     */
    @Test
    fun aStoredLayoutNamingARetiredRowLosesIt() {
        post(
            "/api/settings/home-rows",
            """{"rows":[{"id":"lotw","on":true},{"id":"random","on":true}]}"""
        )
        val ids = json("/api/settings/home-rows").getJSONArray("rows").let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
        }
        assertFalse("lotw" in ids)
        assertEquals("random", ids.first())
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
        assertFalse(json("/api/update/status").getBoolean("available"))
        assertFalse(json("/api/music-mount").getBoolean("mounted"))
        assertEquals(0, json("/api/playlists").getJSONArray("playlists").length())
        // An endpoint with no "off" shape says what is missing rather than 404.
        val (code, text) = get("/api/labels/logo-candidates")
        assertEquals(501, code)
        assertTrue(JSONObject(text).getString("error").isNotEmpty())
    }

    /**
     * Qobuz and TIDAL are gone from this build, routes included.
     *
     * 501 with a reason rather than 404, so a stale cached page asking for one
     * of these is told why it is missing.
     */
    @Test
    fun theStreamingRoutesAreGoneAndSayWhy() {
        val paths = listOf(
            "/api/settings/qobuz", "/api/settings/tidal",
            "/api/qobuz/new-releases", "/api/tidal/favorite"
        )
        for (path in paths) {
            for ((code, text) in listOf(get(path), post(path, "{}"))) {
                assertEquals("$path -> $text", 501, code)
                assertTrue(text, JSONObject(text).getString("error").contains("this build"))
            }
        }
    }

    @Test
    fun rescanReportsAnOutcomeTheFrontEndUnderstands() {
        // The UI maps j.status to its toast and shows "Rescan failed" for
        // anything else, so acknowledging the request without saying what
        // happened reads to the user as a broken button.
        val (code, text) = post("/api/library/rescan", "{}")
        assertEquals(text, 200, code)
        val body = JSONObject(text)
        assertEquals("rebuilt", body.getString("status"))
        assertEquals(5, body.getInt("count"))
    }

    @Test
    fun rescanSaysSoWhenThereIsNoCore() {
        core.paired = false
        val body = JSONObject(post("/api/library/rescan", "{}").second)
        assertEquals("unpaired", body.getString("status"))
    }

    @Test
    fun rescanIsAPostOnly() {
        assertEquals(405, get("/api/library/rescan").first)
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
