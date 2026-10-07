package com.musicd.lite

import com.musicd.lite.library.AlbumRecord
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "If you like this" and Discover, driven through their routes with Deezer
 * answered by the fixture — what the page reads, which calls were made, and
 * the cases where the right answer is to show nothing or ask nobody.
 */
class DiscoverTest {

    private var fx: ApiFixture? = null
    private fun f(): ApiFixture = fx ?: ApiFixture().start().also { fx = it }
    @After fun tearDown() { fx?.stop() }

    private fun respond(req: okhttp3.Request, body: String, code: Int = 200): okhttp3.Response =
        okhttp3.Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()

    /**
     * Deezer as a table: artist searches by query, and per-artist related acts
     * and album listings by id. Anything not in it is refused, as the network
     * failing would be.
     */
    private fun deezer(
        searches: Map<String, String> = emptyMap(),
        related: Map<String, String> = emptyMap(),
        albums: Map<String, String> = emptyMap(),
        top: Map<String, String> = emptyMap()
    ) {
        f().outbound = { req ->
            val path = req.url.encodedPath
            val body = when {
                req.url.host != "api.deezer.com" -> null
                path == "/search/artist" -> searches[req.url.queryParameter("q")]
                path.endsWith("/related") -> related[req.url.pathSegments[1]]
                path.endsWith("/albums") -> albums[req.url.pathSegments[1]]
                path.endsWith("/top") -> top[req.url.pathSegments[1]]
                else -> null
            }
            body?.let { respond(req, it) }
        }
    }

    /** An act's top tracks, as Deezer lists them: one row per track, each naming its album. */
    private fun topTracks(vararg albums: String) =
        """{"data":[${albums.joinToString(",") { t ->
            """{"title":"a track","album":{"id":${t.hashCode() and 0xffff},"title":${JSONObject.quote(t)}}}"""
        }}]}"""

    private fun artists(vararg rows: Pair<Int, String>) =
        """{"data":[${rows.joinToString(",") { (id, name) -> """{"id":$id,"name":${JSONObject.quote(name)},"nb_fan":10}""" }}]}"""

    private fun albums(vararg rows: Pair<String, String>) =
        """{"data":[${rows.joinToString(",") { (title, date) ->
            """{"id":${title.hashCode() and 0xffff},"title":${JSONObject.quote(title)},"release_date":"$date","record_type":"album","cover_medium":"https://cdn/${title.length}.jpg"}"""
        }}]}"""

    private fun daysAgo(n: Long) = LocalDate.now().minusDays(n).toString()

    private fun offsetOf(title: String) = f().app.index.albums.first { it.title == title }.offset

    private fun awaitBuild() {
        val until = System.currentTimeMillis() + 15_000
        while (f().app.discover.building) {
            check(System.currentTimeMillis() < until) { "the build never finished" }
            Thread.sleep(10)
        }
    }

    private fun played(artist: String, vararg daysBack: Long) {
        val now = System.currentTimeMillis()
        for (d in daysBack) {
            val album = "Something by $artist"
            f().store.recordPlay(AlbumRecord(0, album, artist, null).key, album, artist, "A Track", now - d * 86_400_000L)
        }
    }

    // ---------------------------------------------------------- if you like this

    private fun similarDeezer(related: String = artists(20 to "Portishead", 21 to "Tricky", 22 to "Morcheeba")) = deezer(
        searches = mapOf(
            "Massive Attack" to artists(10 to "Massive Attack"),
            "Portishead" to artists(20 to "Portishead")
        ),
        related = mapOf(
            "10" to related,
            "20" to artists(21 to "Tricky", 23 to "Beth Gibbons")
        ),
        albums = mapOf(
            // Portishead: both studio records in the fixture's library, and one
            // more that is not.
            "20" to albums("Third" to "2008-04-28", "Portishead" to "1997-09-29", "Dummy" to "1994-08-22"),
            "21" to albums("Pre-Millennium Tension" to "1996-11-18", "Maxinquaye" to "1995-02-20"),
            "22" to albums("Big Calm" to "1998-03-16")
        ),
        top = mapOf(
            "21" to topTracks("Maxinquaye", "Maxinquaye", "Pre-Millennium Tension"),
            "22" to topTracks("Big Calm")
        )
    )

    private fun actsFor(artist: String): Map<String, JSONObject> {
        val acts = f().json("/api/similar?artist=" + java.net.URLEncoder.encode(artist, "UTF-8").replace("+", "%20"))
            .getJSONArray("acts")
        return (0 until acts.length()).associate { acts.getJSONObject(it).let { a -> a.getString("name") to a } }
    }

    private fun awaitTaste() {
        val until = System.currentTimeMillis() + 15_000
        while (f().app.similar.building) {
            check(System.currentTimeMillis() < until) { "the taste build never finished" }
            Thread.sleep(10)
        }
    }

    @Test
    fun threeActsEachWithARecordAReasonAndSomewhereToGo() {
        similarDeezer()
        val acts = actsFor("Massive Attack")
        assertEquals(setOf("Portishead", "Tricky", "Morcheeba"), acts.keys)

        // An act you don't know: its best-known record — the one most of its
        // top tracks are from, not its debut — with the year from its listing.
        val tricky = acts.getValue("Tricky")
        assertEquals("Maxinquaye", tricky.getString("album"))
        assertEquals(1995, tricky.getInt("year"))
        assertTrue(tricky.isNull("known"))
        // No listening yet, so nothing is near: Deezer's word for it.
        assertEquals("Near Massive Attack", tricky.getString("reason"))
        assertFalse(tricky.getBoolean("in_library"))
        assertTrue(tricky.isNull("offset"))
        val services = tricky.getJSONArray("services")
        assertEquals("qobuz", services.getJSONObject(0).getString("id"))
        assertTrue(services.getJSONObject(0).getString("url").contains("Tricky%20Maxinquaye"))

        // An act you know: the newest full album you DON'T own — both of the
        // library's Portishead records are passed over.
        val portishead = acts.getValue("Portishead")
        assertEquals("library", portishead.getString("known"))
        assertEquals("Portishead", portishead.getString("album"))
        assertEquals(1997, portishead.getInt("year"))
        assertEquals("In your library — a record you don't have", portishead.getString("reason"))
        assertFalse(portishead.getBoolean("in_library"))
    }

    @Test
    fun aRecordInTheLibraryIsAQueueWithTheLibrarysOwnIdentity() {
        deezer(
            searches = mapOf("Massive Attack" to artists(10 to "Massive Attack")),
            related = mapOf("10" to artists(30 to "Radiohead Tribute")),
            // An act not in the library by that name, whose best-known record is.
            top = mapOf("30" to topTracks("Kid A"))
        )
        val act = actsFor("Massive Attack").getValue("Radiohead Tribute")
        assertTrue(act.getBoolean("in_library"))
        assertEquals(offsetOf("Kid A"), act.getInt("offset"))
        assertEquals("Kid A", act.getString("library_title"))
        assertEquals(0, act.getJSONArray("services").length())
    }

    @Test
    fun anActIsKnownByItsExactNameNeverByALongerOne() {
        // Owning Portishead must not make "Portishead Sound System" an act you know.
        deezer(
            searches = mapOf("Massive Attack" to artists(10 to "Massive Attack")),
            related = mapOf("10" to artists(40 to "Portishead Sound System")),
            top = mapOf("40" to topTracks("Dub Plates"))
        )
        val act = actsFor("Massive Attack").getValue("Portishead Sound System")
        assertTrue(act.isNull("known"))
        assertEquals("Dub Plates", act.getString("album"))
    }

    @Test
    fun theActsYouPlayMakeTheTasteGraphAndSayWhy() {
        similarDeezer()
        played("Portishead", 1, 2, 3)
        actsFor("Massive Attack")     // the first share starts today's taste build
        awaitTaste()
        val tricky = actsFor("Massive Attack").getValue("Tricky")
        // Tricky is on Portishead's related list, and Portishead is played.
        assertEquals("Near Portishead, which you play", tricky.getString("reason"))
    }

    @Test
    fun anActYouPlayMostIsNeverSuggested() {
        similarDeezer()
        played("Tricky", 1, 2, 3, 4, 5, 6)
        actsFor("Massive Attack")
        awaitTaste()
        assertFalse(actsFor("Massive Attack").containsKey("Tricky"))
    }

    @Test
    fun anArtistsSuggestionsAreAskedForOnceADayAndEachActsRecordsOnceAWeek() {
        similarDeezer()
        actsFor("Massive Attack")
        awaitTaste()   // no plays: asks nobody
        val calls = f().outboundCalls.size
        assertEquals("search, related, then top tracks and albums for each of three", 8, calls)
        actsFor("Massive Attack")
        assertEquals("a second card for the same act asked Deezer again", calls, f().outboundCalls.size)
    }

    @Test
    fun aFailedDeezerCallIsNotRememberedAsNoRelatedActs() {
        // The related call fails (refused, as a timeout or Deezer's quota
        // error would): no row — and it must not be kept as "nobody".
        deezer(searches = mapOf("Massive Attack" to artists(10 to "Massive Attack")))
        assertTrue(actsFor("Massive Attack").isEmpty())
        similarDeezer()
        assertEquals(3, actsFor("Massive Attack").size)
    }

    @Test
    fun aSeveralActCreditSearchesForTheFirstAct() {
        similarDeezer()
        f().json("/api/similar?artist=Massive%20Attack%20%2F%20Tricky")
        val search = f().outboundCalls.first()
        assertTrue(search, search.contains("q=Massive%20Attack"))
        assertFalse(search, search.contains("Tricky"))
    }

    @Test
    fun aTributeBandIsNotAskedAboutAheadOfTheAct() {
        deezer(
            searches = mapOf("Portishead" to """{"data":[
                {"id":66,"name":"Portishead Tribute Band","nb_fan":99999},
                {"id":20,"name":"Portishead","nb_fan":5}]}"""),
            related = mapOf("20" to artists(21 to "Tricky")),
            albums = mapOf("21" to albums("Maxinquaye" to "1995-02-20"))
        )
        val acts = f().json("/api/similar?artist=Portishead").getJSONArray("acts")
        assertEquals("Tricky", acts.getJSONObject(0).getString("name"))
        assertFalse(f().outboundCalls.any { it.contains("/artist/66/") })
    }

    @Test
    fun similarNeedsAnArtistAndNeverErrorsOtherwise() {
        assertEquals(400, f().get("/api/similar").first)
        // Deezer unreachable: an empty row, not a failure.
        val j = f().json("/api/similar?artist=Nobody")
        assertEquals(0, j.getJSONArray("acts").length())
    }

    // ------------------------------------------------------------------ discover

    @Test
    fun offByDefaultAndAsksNobody() {
        val s = f().json("/api/settings/discover")
        assertFalse(s.getBoolean("enabled"))
        assertEquals(5, s.getInt("hour"))
        assertEquals(60, s.getInt("window_days"))
        assertEquals(40, s.getInt("seed_count"))
        played("Portishead", 1, 2, 3)
        val d = f().json("/api/discover")
        assertFalse(d.getBoolean("enabled"))
        assertEquals(0, d.getJSONArray("releases").length())
        assertFalse(d.getBoolean("building"))
        assertTrue("Discover asked Deezer while switched off", f().outboundCalls.isEmpty())
        assertEquals(400, f().post("/api/discover/rebuild", "{}").first)
    }

    private fun discoverDeezer() = deezer(
        searches = mapOf(
            "Portishead" to artists(1 to "Portishead"),
            "Radiohead" to artists(2 to "Radiohead"),
            // Only a partial match: a tribute act is never a seed's records.
            "Sting" to artists(3 to "Sting Tribute Band")
        ),
        albums = mapOf(
            "1" to albums(
                "New Portishead" to daysAgo(10),
                "Third" to daysAgo(3),                          // owned
                "Dummy (Remastered)" to daysAgo(4),             // owned, and a reissue
                "Dummy" to "1994-08-22",
                "Announced" to LocalDate.now().plusDays(20).toString()
            ),
            "2" to albums("Kid B" to daysAgo(5), "Old One" to "2001-06-04")
        )
    )

    @Test
    fun theDaysListIsNewRecordsByTheActsYouPlay() {
        discoverDeezer()
        played("Portishead", 1, 2, 3)          // three days: the strongest seed
        played("Radiohead", 1)
        played("Various Artists", 1, 2, 3, 4)  // a filing, not an act
        played("Sting", 2)
        val before = f().json("/api/live").getJSONObject("rev").getString("discover")

        f().postJson("/api/settings/discover", """{"enabled":true,"hour":0}""")
        awaitBuild()

        val d = f().json("/api/discover")
        assertTrue(d.getBoolean("enabled"))
        assertEquals(LocalDate.now().toString(), d.getString("day"))
        assertFalse(d.getBoolean("building"))
        assertEquals(Discover.RULES, d.getString("rules"))
        assertTrue(d.getBoolean("rules_current"))
        assertEquals(60, d.getInt("window_days"))
        val rel = d.getJSONArray("releases")
        assertEquals(
            "newest first; nothing owned, reissued, announced or old",
            listOf("Kid B", "New Portishead"),
            (0 until rel.length()).map { rel.getJSONObject(it).getString("album") }
        )
        val kidB = rel.getJSONObject(0)
        assertEquals("Radiohead", kidB.getString("artist"))
        assertEquals(daysAgo(5), kidB.getString("release_date"))
        assertEquals(LocalDate.now().minusDays(5).year, kidB.getInt("year"))
        assertEquals("https://cdn/5.jpg", kidB.getString("cover"))
        assertFalse(kidB.getBoolean("in_library"))
        assertTrue(kidB.isNull("image_key"))
        assertTrue(kidB.getJSONArray("services").length() > 0)

        // Seeds asked about in order of days played; Various Artists never.
        val searches = f().outboundCalls.filter { it.contains("/search/artist") }
        assertTrue(searches[0], searches[0].contains("q=Portishead"))
        assertFalse(searches.any { it.contains("Various") })
        // The tribute act's catalogue was never read.
        assertFalse(f().outboundCalls.any { it.contains("/artist/3/") })
        // The screen re-reads itself when the build lands.
        assertNotEquals(before, f().json("/api/live").getJSONObject("rev").getString("discover"))
    }

    @Test
    fun aBuiltDayIsNotBuiltAgainUnlessAskedTo() {
        discoverDeezer()
        played("Radiohead", 1)
        f().postJson("/api/settings/discover", """{"enabled":true,"hour":0}""")
        awaitBuild()
        val calls = f().outboundCalls.size
        f().json("/api/discover")
        assertFalse(f().app.discover.building)
        assertEquals("opening the screen rebuilt a day already built", calls, f().outboundCalls.size)

        // Refresh does — and answers from the week's cache, not Deezer.
        val r = f().postJson("/api/discover/rebuild", "{}")
        assertTrue(r.getBoolean("building"))
        awaitBuild()
        assertEquals(calls, f().outboundCalls.size)
    }

    @Test
    fun noListeningHistoryIsNotABuiltDayAndDoesNotLoop() {
        discoverDeezer()
        f().postJson("/api/settings/discover", """{"enabled":true,"hour":0}""")
        awaitBuild()
        val d = f().json("/api/discover")
        assertEquals(0, d.getJSONArray("releases").length())
        assertFalse("a day with no seeds was stamped built", d.getBoolean("rules_current"))
        // The screen re-reading after that build does not start another one —
        // the loop a build that moves the revision would otherwise make.
        assertFalse(f().app.discover.building)
        assertTrue(f().outboundCalls.isEmpty())
    }

    @Test
    fun deezerUnreachableKeepsTheLastList() {
        played("Radiohead", 1)
        val yesterday = daysAgo(1)
        f().store.putSetting(
            Discover.KEY_LIST,
            JSONObject().put(
                "days",
                JSONObject().put(
                    yesterday,
                    org.json.JSONArray().put(Discover.Row("Radiohead", "Kid B", "9", null, daysAgo(6), 0).toJson())
                )
            ).toString()
        )
        f().outbound = null
        f().postJson("/api/settings/discover", """{"enabled":true,"hour":0}""")
        awaitBuild()
        val d = f().json("/api/discover")
        assertEquals(yesterday, d.getString("day"))
        assertEquals("Kid B", d.getJSONArray("releases").getJSONObject(0).getString("album"))
        assertFalse(d.getBoolean("rules_current"))
    }

    @Test
    fun aRecordTheLibraryNowHoldsIsQueuedWithRoonsOwnArt() {
        // Built before the record was imported; resolved per request, so the
        // row changes with the library rather than with tomorrow's build.
        f().store.putSetting(
            Discover.KEY_LIST,
            JSONObject().put(
                "days",
                JSONObject().put(
                    LocalDate.now().toString(),
                    org.json.JSONArray().put(Discover.Row("Massive Attack", "Mezzanine", "9", "https://cdn/x.jpg", daysAgo(2), 0).toJson())
                )
            ).toString()
        )
        val row = f().json("/api/discover").getJSONArray("releases").getJSONObject(0)
        assertTrue(row.getBoolean("in_library"))
        assertEquals(offsetOf("Mezzanine"), row.getInt("offset"))
        assertEquals("Mezzanine", row.getString("library_title"))
        assertEquals("Massive Attack", row.getString("library_subtitle"))
        assertEquals("img-Mezzanine", row.getString("image_key"))
        assertEquals(0, row.getJSONArray("services").length())
    }

    @Test
    fun settingsValidateTheHour() {
        assertEquals(400, f().post("/api/settings/discover", """{"hour":24}""").first)
        assertEquals(5, f().json("/api/settings/discover").getInt("hour"))
        val j = f().postJson("/api/settings/discover", """{"hour":7}""")
        assertEquals(7, j.getInt("hour"))
        assertFalse(j.getBoolean("enabled"))
        assertEquals(7, f().json("/api/settings/discover").getInt("hour"))
    }

    @Test
    fun rebuildRefusesWhenItCannotStart() {
        f().postJson("/api/settings/discover", """{"enabled":true,"hour":23}""")
        awaitBuild()
        f().app.index.clear()
        val (code, text) = f().post("/api/discover/rebuild", "{}")
        assertEquals(text, 503, code)
        assertTrue(text, text.contains("library"))
    }

    // ------------------------------------------------------- the gate, by clock

    @Test
    fun theDayIsDueAtOrAfterTheHourAndForceSkipsOnlyTheSchedule() {
        val utc = ZoneId.of("UTC")
        var now = LocalDate.of(2026, 10, 5).atTime(4, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        val app = f().app
        app.settings.saveDiscover(enabled = true, hour = 5)
        val d = Discover(f().store, app.index, app.settings, app.deezer, {}, clock = { now }, zone = { utc })
        try {
            assertFalse("built before the hour", d.kick("test"))
            now += 3_600_000L
            assertTrue(d.due())
            assertTrue(d.kick("test"))
            while (d.building) Thread.sleep(10)
            // Nothing played, so nothing stamped — and not retried at once.
            assertFalse(d.kick("test"))
            assertTrue("force skips the retry spacing", d.kick("test", force = true))
            while (d.building) Thread.sleep(10)
            app.index.clear()
            assertFalse("force built with no library to check against", d.kick("test", force = true))
        } finally {
            d.close()
        }
    }

    @Test
    fun aBuiltDayWaitsForTomorrow() {
        discoverDeezer()
        val utc = ZoneId.of("UTC")
        var now = LocalDate.of(2026, 10, 5).atTime(6, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        f().store.recordPlay(AlbumRecord(0, "Kid A", "Radiohead", null).key, "Kid A", "Radiohead", "Idioteque", now - 86_400_000L)
        val app = f().app
        app.settings.saveDiscover(enabled = true, hour = 5)
        val d = Discover(f().store, app.index, app.settings, app.deezer, {}, clock = { now }, zone = { utc })
        try {
            assertTrue(d.kick("test"))
            while (d.building) Thread.sleep(10)
            assertTrue(d.stampCurrent("2026-10-05"))
            now += 60L * 60_000   // an hour on: well past the retry spacing
            assertFalse("a built day was built again", d.kick("test"))
            now += 23L * 3_600_000   // the next morning
            assertTrue(d.kick("test"))
            while (d.building) Thread.sleep(10)
        } finally {
            d.close()
        }
    }

    @Test
    fun aRecordTwoSeedsShareIsOneRow() {
        // A collaboration filed under both acts: the same Deezer id twice.
        val shared = """{"data":[{"id":777,"title":"Together","release_date":"${daysAgo(3)}","record_type":"album"}]}"""
        deezer(
            searches = mapOf("Portishead" to artists(1 to "Portishead"), "Beth Gibbons" to artists(5 to "Beth Gibbons")),
            albums = mapOf("1" to shared, "5" to shared)
        )
        played("Portishead", 1, 2)
        played("Beth Gibbons", 1)
        f().postJson("/api/settings/discover", """{"enabled":true,"hour":0}""")
        awaitBuild()
        val rel = f().json("/api/discover").getJSONArray("releases")
        assertEquals(1, rel.length())
        assertEquals("credited to the act played most", "Portishead", rel.getJSONObject(0).getString("artist"))
    }

    @Test
    fun aDeezerErrorIsAFailureNotAnAnswer() {
        // Deezer reports a quota or a fault as a 200 carrying an "error"
        // object. Read as an answer, it would be "no such act" — cached for
        // the week, and the day stamped built with nothing on it.
        f().outbound = { req ->
            if (req.url.host == "api.deezer.com")
                respond(req, """{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}""")
            else null
        }
        played("Radiohead", 1)
        f().postJson("/api/settings/discover", """{"enabled":true,"hour":0}""")
        awaitBuild()
        assertFalse(f().json("/api/discover").getBoolean("rules_current"))
    }

    @Test
    fun resolvingIsStrictOnTheTitleAndForgivingOnTheArtist() {
        val d = f().app.discover
        assertEquals("Mezzanine", d.resolve("Mezzanine", "Massive Attack")?.title)
        assertEquals("Mezzanine", d.resolve("mezzanine!", "massive attack")?.title)
        assertNull("another act's record of the same name", d.resolve("Mezzanine", "Someone Else"))
        assertEquals("one title, no artist to check", "Dummy", d.resolve("Dummy", "")?.title)
        assertNull(d.resolve("Mezz", "Massive Attack"))
    }
}
