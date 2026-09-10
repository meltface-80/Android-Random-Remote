package com.musicd.lite

import com.musicd.lite.api.StaticAssets
import com.musicd.lite.roon.Zone
import com.musicd.lite.store.MemoryStore
import com.musicd.lite.store.YearSource
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import okhttp3.OkHttpClient

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
    private lateinit var outbound: CountingHttp

    /**
     * Everything the app sends anywhere that is not Roon — MusicBrainz,
     * Wikipedia, Pitchfork — counted, and refused.
     *
     * "This path does not go to the network" is a claim about calls that are
     * NOT made, and the only way to assert it is to be able to see the ones
     * that are. Refusing them keeps the suite off the internet as well: every
     * lookup then fails the way it does on a phone with no signal, which is a
     * shape the endpoints have to survive anyway.
     */
    private class CountingHttp {
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                calls.add(chain.request().url.host)
                throw IOException("the test refuses outbound requests")
            }
            .build()

        fun to(host: String): Int = synchronized(calls) { calls.count { it.contains(host) } }
    }

    private val assets = object : StaticAssets {
        private val files = mapOf(
            "/index.html" to ("<!doctype html><title>MusicD</title>" to "text/html"),
            "/app.js" to ("console.log('ui');" to "application/javascript"),
            "/display.html" to ("<!doctype html><title>Wall</title>" to "text/html"),
            "/dial.html" to ("<!doctype html><title>Dial</title>" to "text/html"),
            "/dial.js" to ("console.log('dial');" to "application/javascript")
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
        outbound = CountingHttp()
        app = MusicdLite(
            store = store,
            assets = assets,
            artDir = null,
            version = "test",
            httpPort = 0,
            importSettleMs = 0,         // no need to wait out an import in a test
            httpClient = outbound.client
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
        // A deep link is still the single-page app, not a 404 — including
        // /display, which used to be the wall display's own page and is now
        // just another unknown path.
        assertTrue(get("/library/albums").second.contains("MusicD"))
        assertTrue(get("/display").second.contains("MusicD"))
    }

    /**
     * The search box's one outside source. It answered {albums:[],artists:[]}
     * before, while the page read `pitchfork` off it — so the "Pitchfork
     * reviews" section of search could never appear, and nothing looked wrong
     * because an empty section simply does not draw.
     *
     * The key name is the contract; the content depends on what has been
     * cached, and this test's HTTP client refuses outbound requests, so cold
     * is the case it pins. Cold must be an empty list and not an absent field.
     */
    @Test
    fun searchExternalAnswersWithAPitchforkList() {
        val j = json("/api/search/external?q=mezzanine")
        assertTrue("no `pitchfork` key: $j", j.has("pitchfork"))
        assertEquals(0, j.getJSONArray("pitchfork").length())
    }

    /**
     * THE ONE THAT MATTERS, and the dial learned it the hard way. This endpoint
     * resolves the id it is GIVEN and nothing else — RoonCore.zone(id) is
     * zoneStore.byId(id), and byId(null) is null — so a request that names no
     * zone answers "no zone" even while music is playing. There is no "the
     * current zone" here to fall back on.
     *
     * The dial's first version asked without one and looked broken in a way
     * that was hard to read: it opened on "No zone", a zone picked by hand
     * worked, and then the next thing that moved — a pause, a volume nudge —
     * bumped the revision, returned the waiting poll, and put it back to "No
     * zone". Every client must name its zone on every request.
     */
    @Test
    fun zoneStateWithoutAZoneAnswersNoZone() {
        assertTrue(json("/api/zone-state").isNull("zone"))
        // ...while the same request naming the zone answers with it.
        assertEquals("z1", json("/api/zone-state?zone=z1").getJSONObject("zone").getString("zone_id"))
    }

    /**
     * THE ONE THAT MATTERS. Every unknown path falls through to the
     * single-page app, and that is a page which WORKS — so a /dial that was
     * not routed would show the remote instead, look like a working app, and
     * simply never be the dial. Nothing would appear broken.
     */
    @Test
    fun theDialIsItsOwnPageAndNotTheAppAgain() {
        for (path in listOf("/dial", "/dial/")) {
            val (code, text) = get(path)
            assertEquals(200, code)
            assertTrue("$path served the app, not the dial: $text", text.contains("Dial"))
        }
        assertEquals(200, get("/dial.js").first)
        // Still true of everything else, which is what makes the case above
        // invisible without a test.
        assertTrue(get("/dialogue").second.contains("MusicD"))
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
        assertTrue("the library has albums to offer", picks.length() > 0)

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

    /**
     * THE ONE THAT MATTERS. The page has always sent {"mute": true} with no
     * value, and this route checked for a value before it looked at anything
     * else — so the mute button answered 400 and did nothing, in the app and
     * on every LAN device. The dial has a mute tap too, which is how it
     * surfaced.
     */
    @Test
    fun muteCarriesNoValueAndIsStillAllowedThrough() {
        assertEquals(200, post("/api/volume", """{"zone_or_output_id":"z1","mute":true}""").first)
        assertEquals(200, post("/api/volume", """{"zone_or_output_id":"z1","mute":false}""").first)
        assertEquals(listOf("mute:o1:mute", "mute:o1:unmute"), core.calls)
    }

    /** A request that is neither a mute nor a value is still a bad request. */
    @Test
    fun aVolumeRequestWithNothingToDoIsRefused() {
        val (code, text) = post("/api/volume", """{"zone_or_output_id":"z1"}""")
        assertEquals(400, code)
        assertTrue(JSONObject(text).getString("error").contains("value is required"))
        assertTrue(core.calls.isEmpty())
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

    /**
     * The long poll: hold the request open until Roon says something.
     *
     * Roon pushes zone changes to this app instantly and the page used to ask
     * anyway, 2,400 times an hour. These three tests pin the three things that
     * have to hold for waiting to replace asking — that it blocks, that a real
     * change wakes it at once, and that the revision it hands back is safe to
     * wait on.
     */
    @Test
    fun aWaitingRequestBlocksUntilItsTimeout() {
        val revision = json("/api/zone-state?zone=z1").getLong("revision")
        val started = System.currentTimeMillis()
        json("/api/zone-state?zone=z1&wait_for=$revision&timeout=400")
        val waited = System.currentTimeMillis() - started
        assertTrue("returned after ${waited}ms — it did not wait at all", waited >= 350)
    }

    @Test
    fun aChangeWakesAWaitingRequestImmediately() {
        val revision = json("/api/zone-state?zone=z1").getLong("revision")
        // Stand in for Roon pushing a zone change while the request is open.
        val pusher = Thread {
            Thread.sleep(120)
            core.bumpZones()
        }.apply { start() }

        val started = System.currentTimeMillis()
        val body = json("/api/zone-state?zone=z1&wait_for=$revision&timeout=5000")
        val waited = System.currentTimeMillis() - started
        pusher.join()

        assertTrue("waited ${waited}ms — it slept through the change", waited < 2000)
        assertTrue("the revision must move, or the client re-waits on a stale one",
            body.getLong("revision") > revision)
    }

    @Test
    fun theRevisionIsNeverNewerThanTheDataItArrivedWith() {
        // Read before the snapshot, deliberately: a revision NEWER than the
        // zone it came with would have the client wait on a change it was
        // already handed a number for, and sleep through it. Older is safe —
        // the next wait returns at once.
        core.bumpZones()
        val body = json("/api/zone-state?zone=z1")
        assertTrue(body.getLong("revision") <= core.zoneRevision)
    }

    @Test
    fun zoneStateStillAnswersImmediatelyWithoutAWait() {
        val started = System.currentTimeMillis()
        val body = json("/api/zone-state?zone=z1")
        assertTrue(System.currentTimeMillis() - started < 1000)
        assertEquals("Study", body.getJSONObject("zone").getString("display_name"))
    }

    /**
     * `size` is the parameter the page sends, and it was being ignored.
     *
     * Every art URL the front-end builds uses it — 80 for a queue thumbnail,
     * 800 for the album view, 1000 for a share card. This read only width/w, so
     * all of them were served at the 512 default: a queue row fetched forty
     * times the pixels it drew, cached them, and decoded them.
     */
    @Test
    fun artIsFetchedAtTheSizeThePageAsksFor() {
        get("/api/image/img-Mezzanine?size=80")
        assertTrue(
            core.calls.toString(),
            core.calls.any { it == "image:img-Mezzanine:80x80" }
        )
    }

    @Test
    fun artFallsBackToTheOldParametersAndThenToADefault() {
        get("/api/image/img-Mezzanine?width=300")
        assertTrue(core.calls.any { it == "image:img-Mezzanine:300x300" })

        get("/api/image/img-Mezzanine")
        assertTrue(core.calls.any { it == "image:img-Mezzanine:512x512" })
    }

    @Test
    fun zoneSettingsRejectAnUnknownLoopMode() {
        assertEquals(400, post("/api/zone-settings", """{"zone_or_output_id":"z1","loop":"next"}""").first)
        assertEquals(200, post("/api/zone-settings", """{"zone_or_output_id":"z1","loop":"loop_one"}""").first)
        assertTrue(core.calls.any { it.startsWith("settings:z1:") && it.contains("loop_one") })
    }

    // --------------------------------------------------------- asset caching

    /**
     * THE BUG THIS EXISTS FOR. The page used to go out with max-age=3600 and no
     * validator. The WebView's cache is on disk and survives an app update, so
     * for an hour after installing a new version it kept serving the OLD page
     * — a feature would ship, be verified in the APK, and simply not be there
     * on the phone, with nothing to say why.
     *
     * It only became visible when the port was fixed at 3450 for LAN access.
     * Before that every launch got a different port, so nothing ever matched.
     */
    @Test
    fun theBundledPageIsNeverServedFromCacheWithoutAsking() {
        val headers = headersOf("/app.js")
        assertEquals(
            "a stored copy must be revalidated, not used on trust",
            "no-cache", headers["cache-control"]?.firstOrNull()
        )
        assertTrue("revalidation needs a validator to compare", headers.containsKey("etag"))
    }

    @Test
    fun theValidatorIsTheAppVersionSoANewBuildCannotBeMissed() {
        assertEquals(""""v${app.version}"""", headersOf("/app.js")["etag"]?.firstOrNull())
    }

    /** Unchanged app, no bytes: revalidating must stay cheap. */
    @Test
    fun anUnchangedPageComesBackAsNotModified() {
        val etag = headersOf("/app.js")["etag"]!!.first()
        val conn = URL(app.rootUrl + "/app.js").openConnection() as HttpURLConnection
        conn.setRequestProperty("If-None-Match", etag)
        assertEquals(304, conn.responseCode)
        conn.disconnect()
    }

    @Test
    fun aStaleValidatorGetsTheWholePageBack() {
        val conn = URL(app.rootUrl + "/app.js").openConnection() as HttpURLConnection
        conn.setRequestProperty("If-None-Match", "\"v0.0.1-from-an-older-install\"")
        assertEquals(200, conn.responseCode)
        assertTrue(conn.inputStream.readBytes().isNotEmpty())
        conn.disconnect()
    }

    private fun headersOf(path: String): Map<String, List<String>> {
        val conn = URL(app.rootUrl + path).openConnection() as HttpURLConnection
        conn.responseCode
        // Header names are case-insensitive on the wire; normalise so the
        // assertions do not depend on how they were spelled.
        val out = conn.headerFields.entries
            .filter { it.key != null }
            .associate { it.key.lowercase() to it.value }
        conn.disconnect()
        return out
    }

    // ------------------------------------------------------------ lan access

    /**
     * THE ONE THAT MATTERS MOST.
     *
     * Every request in this file goes over loopback, which is exactly what the
     * app's own WebView does. If the gate ever stops letting loopback through,
     * turning on LAN access locks the owner out of their own phone — and the
     * whole suite going red here is the loudest way to find that out.
     */
    @Test
    fun turningOnLanAccessDoesNotLockThisPhoneOut() {
        // Switching rebuilds the socket, and the WebView has already loaded a
        // page from the old one — so the port must survive, or that page is
        // left talking to nothing with nothing to say why.
        val before = app.port
        assertEquals(200, post("/api/settings/lan", """{"enabled":true}""").first)
        assertEquals("the port must not move under the open page", before, app.port)

        // The ordinary API still answers, with no cookie and no PIN anywhere.
        assertNotNull(json("/api/zones"))
        assertEquals(200, get("/api/settings/home-rows").first)

        // And back again.
        assertEquals(200, post("/api/settings/lan", """{"enabled":false}""").first)
        assertEquals(before, app.port)
        assertNotNull(json("/api/zones"))
    }

    @Test
    fun lanAccessIsOffAndHasNoCredentialsUntilItIsAskedFor() {
        val before = json("/api/settings/lan")
        assertFalse(before.getBoolean("enabled"))
        assertEquals("", before.getString("pin"))
    }

    @Test
    fun turningItOnMintsACodeAndTurningItOffThrowsTheCodeAway() {
        val on = JSONObject(post("/api/settings/lan", """{"enabled":true}""").second)
        assertTrue(on.getBoolean("enabled"))
        assertEquals("a code somebody has to type once", 8, on.getString("pin").length)
        assertTrue("the port is what the owner types", on.getInt("port") > 0)

        val off = JSONObject(post("/api/settings/lan", """{"enabled":false}""").second)
        assertFalse(off.getBoolean("enabled"))
        assertEquals("a code that is no longer usable must not be shown", "", off.getString("pin"))
    }

    /**
     * Enabling twice mints a different code, which is how somebody revokes one
     * they have shared — and it invalidates the cookies the old one bought.
     */
    @Test
    fun switchingItOnAgainIssuesADifferentCode() {
        fun enable() =
            JSONObject(post("/api/settings/lan", """{"enabled":true}""").second).getString("pin")
        val first = enable()
        post("/api/settings/lan", """{"enabled":false}""")
        assertNotEquals(first, enable())
    }

    @Test
    fun aRequestWithoutTheFlagIsRefusedRatherThanGuessedAt() {
        assertEquals(400, post("/api/settings/lan", """{}""").first)
    }

    @Test
    fun homeRowsRepairThemselvesAndOfferOnlyRowsThisBuildServes() {
        val rows = json("/api/settings/home-rows").getJSONArray("rows")
        val ids = (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
        assertEquals(
            listOf("aotd", "history", "picks", "random", "library", "genres"),
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
        assertNotNull(json("/api/home/album-of-the-day").getJSONObject("album").getString("title"))
        assertEquals(0, json("/api/home/history").getJSONArray("albums").length())
    }

    /**
     * The wall display is gone, routes included.
     *
     * It served a page to OTHER devices on the network, and this server binds
     * to loopback — so nothing off the phone could ever reach it. Opening that
     * up would put an API that controls playback on the LAN with no
     * authentication in front of it.
     */
    @Test
    fun theWallDisplayRoutesAreGone() {
        assertEquals(404, get("/api/display/content").first)
        assertEquals(404, get("/api/settings/display").first)
        assertEquals(404, post("/api/settings/display", """{"enabled":true}""").first)
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

    // ------------------------------------------------- the share card's extras

    /**
     * The share card asks with `fast=1` and gets an answer without a single
     * packet leaving the phone.
     *
     * It needs one field of this endpoint — the release year — and it holds a
     * spinner in front of the user until it has it. Asking the slow way meant
     * MusicBrainz, two Wikipedia searches, two summaries and a Pitchfork review
     * page, in sequence, behind a one-per-second rate gate: seconds of spinner
     * for a number the app had already learned and written down.
     */
    @Test
    fun theShareCardsExtrasNeverLeaveTheDevice() {
        val album = app.index.albums.first { it.title == "Mezzanine" }
        store.putAlbumYear(album.key, 1998, YearSource.MUSICBRAINZ)

        val before = outbound.calls.size
        val body = json("/api/album/extras?fast=1&title=Mezzanine&artist=Massive%20Attack")

        assertEquals(1998, body.getInt("year"))
        assertEquals(
            "the fast path went to the network: ${outbound.calls.drop(before)}",
            before, outbound.calls.size
        )
    }

    /**
     * The control for the test above: the album card still looks things up, so
     * a zero there means the fast path stayed home rather than the counter
     * being blind.
     */
    @Test
    fun theAlbumCardsExtrasStillLookThingsUp() {
        val before = outbound.calls.size
        json("/api/album/extras?title=Mezzanine&artist=Massive%20Attack")
        assertTrue("nothing was looked up at all", outbound.calls.size > before)
    }

    /**
     * A Pitchfork score is fetched once per album, not once per request.
     *
     * The lookup downloads a whole review page and sat outside the cache that
     * covers everything beside it, so opening an album and then sharing it
     * fetched the same markup twice — and every later visit paid again. A miss
     * is the usual answer and costs exactly the same request, so it is cached
     * too.
     */
    @Test
    fun aPitchforkReviewIsFetchedOncePerAlbum() {
        json("/api/album/extras?title=Mezzanine&artist=Massive%20Attack")
        assertEquals("the review page was never asked for", 1, outbound.to("pitchfork.com"))

        json("/api/album/extras?title=Mezzanine&artist=Massive%20Attack")
        assertEquals("the review page was fetched twice", 1, outbound.to("pitchfork.com"))
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
