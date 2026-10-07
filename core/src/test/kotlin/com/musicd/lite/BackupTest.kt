package com.musicd.lite

import com.musicd.lite.api.Settings
import com.musicd.lite.backup.BackupException
import com.musicd.lite.backup.BackupFormat
import com.musicd.lite.backup.BackupStore
import com.musicd.lite.library.LibraryView
import com.musicd.lite.library.ListenLater
import com.musicd.lite.library.UserPlaylists
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Backup & restore's pure half (what goes in which part, what a restore
 * writes) and its storage (the files, their names, how many are kept).
 *
 * The routes over a real socket are BackupRoutesTest.
 */
class BackupTest {

    private val utc = ZoneId.of("UTC")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, utc).toInstant().toEpochMilli()

    private val now = at(2026, 10, 7, 9, 30, 15)

    /** A phone's settings table as it might stand today. */
    private fun live(): Map<String, String> = mapOf(
        Settings.KEY_HOME_ROWS to """{"rows":[{"id":"history","on":false}]}""",
        Settings.KEY_SMART_PICKS to """{"enabled":false,"hour":9,"dest":"later","later_day":"2026-10-07"}""",
        Settings.KEY_SHARE_LINKS to """{"services":["qobuz"],"reviews":[]}""",
        Settings.KEY_DISCOVER to """{"enabled":true,"hour":6}""",
        Settings.KEY_DISCOGS_TOKEN to """{"value":"discogs-secret"}""",
        Settings.KEY_FANART_KEY to """{"value":"fanart-secret"}""",
        Settings.KEY_LAN to """{"enabled":true,"pin":"ABCD2345","secret":"signing-key"}""",
        Settings.KEY_LAST_ZONE to """{"zone":"1601bb42"}""",
        Settings.KEY_RADIO_ZONES to """{"zones":["1601bb42"]}""",
        ListenLater.KEY to """{"albums":[{"key":"k","title":"Dummy","artist":"Portishead","source":"album","ts":5}]}""",
        UserPlaylists.KEY to """{"playlists":[{"id":"up_1","name":"Mix","tracks":[],"created_at":1,"updated_at":2}]}""",
        "discover_list" to """{"days":{}}""",
        LibraryView.KEY_AOTD to """{"day":"2026-10-07"}"""
    )

    // ------------------------------------------------------------ classify

    @Test
    fun everyKeyThisBuildWritesHasTheRightPart() {
        assertEquals("keys", BackupFormat.classify(Settings.KEY_DISCOGS_TOKEN))
        assertEquals("keys", BackupFormat.classify(Settings.KEY_FANART_KEY))
        assertEquals("playlists", BackupFormat.classify(UserPlaylists.KEY))
        assertEquals("later", BackupFormat.classify(ListenLater.KEY))
        for (k in listOf(Settings.KEY_HOME_ROWS, Settings.KEY_SMART_PICKS, Settings.KEY_SHARE_LINKS,
                         Settings.KEY_DISCOVER)) {
            assertEquals(k, "settings", BackupFormat.classify(k))
        }
        // This phone's own: its network exposure, and zone ids, which do not
        // survive a Core restart, let alone another phone. Caches and stamps
        // are rebuilt.
        for (k in listOf(Settings.KEY_LAN, Settings.KEY_LAST_ZONE, Settings.KEY_RADIO_ZONES,
                         "discover_list", LibraryView.KEY_AOTD)) {
            assertEquals(k, "never", BackupFormat.classify(k))
        }
    }

    @Test
    fun anUnknownKeyIsASettingUnlessItIsNamedLikeASecret() {
        // Deny-by-default: a preference added later is backed up without
        // anyone remembering to list it here...
        assertEquals("settings", BackupFormat.classify("some_new_pref"))
        // ...and the risk that runs the other way — a new secret riding in the
        // settings part — goes to keys instead.
        for (k in listOf("spotify_token", "client_secret", "lastfm_api_key", "apikey", "account_password")) {
            assertEquals(k, "keys", BackupFormat.classify(k))
        }
    }

    // --------------------------------------------------------------- build

    @Test
    fun aBackupHoldsTheChosenPartsAndNothingThatIsThePhonesOwn() {
        val b = BackupFormat.build(live(), BackupFormat.PARTS, "manual", "0.6.0", now)
        assertEquals("Rouen Lite", b.getString("app"))
        assertEquals(1, b.getInt("format"))
        assertEquals("0.6.0", b.getString("version"))
        assertEquals("manual", b.getString("kind"))
        assertEquals("2026-10-07T09:30:15Z", b.getString("created"))
        assertEquals(listOf("settings", "playlists", "later", "keys"), list(b.getJSONArray("parts")))

        val settings = b.getJSONObject("settings")
        assertEquals(setOf("home_rows", "smart_picks", "share_links", "discover"), settings.keySet())
        // Bookkeeping, not a choice: which day's picks were already sent.
        assertFalse(settings.getJSONObject("smart_picks").has("later_day"))
        assertEquals(9, settings.getJSONObject("smart_picks").getInt("hour"))
        assertEquals("discogs-secret", b.getJSONObject("keys").getJSONObject("discogs_token").getString("value"))
        assertEquals(setOf(UserPlaylists.KEY), b.getJSONObject("playlists").keySet())
        assertEquals(setOf(ListenLater.KEY), b.getJSONObject("later").keySet())

        val text = b.toString()
        assertFalse("LAN PIN in a backup", text.contains("ABCD2345"))
        assertFalse("LAN signing key in a backup", text.contains("signing-key"))
        assertFalse(text.contains("1601bb42"))
    }

    @Test
    fun onlyTheChosenPartsAreWritten() {
        val b = BackupFormat.build(live(), listOf("later", "settings"), "manual", "0.6.0", now)
        // Canonical order, whatever order they were asked for in.
        assertEquals(listOf("settings", "later"), list(b.getJSONArray("parts")))
        assertFalse(b.has("keys"))
        assertFalse(b.has("playlists"))
        assertFalse(b.toString().contains("discogs-secret"))
    }

    // --------------------------------------------------------------- parse

    @Test
    fun aFileThatIsNotALiteBackupIsRefusedWithAReason() {
        assertParseFails("not json at all", "not a Rouen Lite backup")
        assertParseFails("[1,2]", "not a Rouen Lite backup")
        assertParseFails("""{"app":"Something","format":1,"settings":{}}""", "not a Rouen Lite backup")
        // Rouen's own backups are a different shape entirely: its settings file
        // has different names for everything.
        assertParseFails("""{"app":"Rouen","format":1,"settings":{}}""", "from Rouen")
        assertParseFails("""{"app":"Rouen Lite","format":2,"settings":{}}""", "newer Rouen Lite")
        assertParseFails("""{"app":"Rouen Lite","format":1}""", "nothing to restore")
    }

    @Test
    fun theParsedPartsAreTheOnesTheFileActuallyCarries() {
        val b = BackupFormat.parse(
            """{"app":"Rouen Lite","format":1,"parts":["settings","keys","later"],"later":{},"keys":"nope"}"""
        )
        assertEquals(listOf("later"), list(b.getJSONArray("parts")))
    }

    // ---------------------------------------------------------------- plan

    @Test
    fun restoringAPartReplacesItAndASettingTheBackupLacksGoesBackToItsDefault() {
        val backup = BackupFormat.parse(
            BackupFormat.build(
                mapOf(Settings.KEY_HOME_ROWS to """{"rows":[{"id":"genres","on":false}]}"""),
                BackupFormat.PARTS, "manual", "0.6.0", now
            ).toString()
        )
        val p = BackupFormat.plan(backup, listOf("settings"), live())
        assertEquals(listOf("settings"), p.parts)
        val row = JSONObject(p.puts.getValue(Settings.KEY_HOME_ROWS)).getJSONArray("rows").getJSONObject(0)
        assertEquals("genres", row.getString("id"))
        assertFalse(row.getBoolean("on"))
        // Absent from the backup: removed, so it reads as its default.
        assertTrue(Settings.KEY_DISCOVER in p.removes)
        assertTrue(Settings.KEY_SHARE_LINKS in p.removes)
        // Not the settings part: untouched.
        assertFalse(Settings.KEY_DISCOGS_TOKEN in p.removes)
        assertFalse(p.puts.containsKey(Settings.KEY_DISCOGS_TOKEN))
        assertFalse(ListenLater.KEY in p.removes)
        // Never part of anything.
        assertFalse(Settings.KEY_LAN in p.removes)
        assertFalse(Settings.KEY_RADIO_ZONES in p.removes)
    }

    @Test
    fun todaysPicksBookkeepingSurvivesARestoreOfTheSettings() {
        val backup = BackupFormat.parse(
            """{"app":"Rouen Lite","format":1,"settings":{"smart_picks":{"enabled":true,"hour":7}}}"""
        )
        val p = BackupFormat.plan(backup, listOf("settings"), live())
        val picks = JSONObject(p.puts.getValue(Settings.KEY_SMART_PICKS))
        assertTrue(picks.getBoolean("enabled"))
        assertEquals(7, picks.getInt("hour"))
        // Without this, an older backup would send today's picks again.
        assertEquals("2026-10-07", picks.getString("later_day"))
        assertFalse(Settings.KEY_SMART_PICKS in p.removes)
    }

    @Test
    fun aCraftedBackupCannotReachWhatIsNeverInOne() {
        // A hand-made file putting LAN access ON with a PIN its author knows,
        // in every part it could hide it in.
        val lan = """{"enabled":true,"pin":"KNOWN234","secret":"theirs"}"""
        val backup = BackupFormat.parse(
            """{"app":"Rouen Lite","format":1,
                "settings":{"lan_access":$lan,"last_zone":{"zone":"x"},"discogs_token":{"value":"smuggled"},
                            "home_rows":{"rows":[]},"Bad Key!":{"a":1},"listen_later":{"albums":[]}},
                "keys":{"lan_access":$lan,"fanart_key":{"value":"f2"},"home_rows":{"rows":[]}},
                "playlists":{"lan_access":$lan},
                "later":{"lan_access":$lan}}"""
        )
        val p = BackupFormat.plan(backup, BackupFormat.PARTS, live())
        assertFalse(p.puts.containsKey(Settings.KEY_LAN))
        assertFalse(p.puts.containsKey(Settings.KEY_LAST_ZONE))
        assertFalse(p.puts.containsKey("Bad Key!"))
        // Each part takes only its own keys: a token hidden in settings is not
        // restored as a setting, and a setting hidden in keys is not either.
        assertEquals("""{"rows":[]}""", p.puts[Settings.KEY_HOME_ROWS])
        assertFalse(p.puts.containsKey(Settings.KEY_DISCOGS_TOKEN))
        assertTrue(Settings.KEY_DISCOGS_TOKEN in p.removes)
        assertEquals("""{"value":"f2"}""", p.puts[Settings.KEY_FANART_KEY])
        assertFalse(Settings.KEY_LAN in p.removes)
    }

    @Test
    fun aRestoredListenLaterIsKeyedTheWayThisBuildLooksAlbumsUp() {
        // A row whose key was worked out some other way, a duplicate of it, a
        // row with no title, and a source this build does not know.
        val backup = BackupFormat.parse(
            """{"app":"Rouen Lite","format":1,"later":{"listen_later":{"albums":[
                {"key":"stale-key","title":"Dummy","artist":"Portishead","source":"album","ts":20},
                {"key":"other","title":" Dummy ","artist":"Portishead","source":"album","ts":10},
                {"title":"","artist":"Nobody","ts":5},
                {"title":"Third","artist":"Portishead","source":"carrier-pigeon","ts":3}]}}}"""
        )
        val p = BackupFormat.plan(backup, listOf("later"), live())
        val rows = JSONObject(p.puts.getValue(ListenLater.KEY)).getJSONArray("albums")
        assertEquals(2, rows.length())
        val first = rows.getJSONObject(0)
        assertEquals("Dummy", first.getString("title"))
        assertEquals(ListenLater.keyOf("Dummy", "Portishead"), first.getString("key"))
        assertEquals(20L, first.getLong("ts"))
        assertEquals("album", rows.getJSONObject(1).getString("source"))
    }

    @Test
    fun restoredPlaylistsAreReadTheWayTheAppReadsThem() {
        val backup = BackupFormat.parse(
            """{"app":"Rouen Lite","format":1,"playlists":{"user_playlists":{"playlists":[
                {"id":"up_a","name":"  Late   night ","created_at":1,"updated_at":2,"tracks":[
                  {"album_offset":3,"album_title":"Dummy","album_subtitle":"Portishead","track_index":1,
                   "title":"Roads","subtitle":"Portishead","image_key":"img","track_no":2},
                  {"album_offset":-1,"album_title":"Broken","title":"x"}]},
                {"id":"","name":"No id"}]}}}"""
        )
        val p = BackupFormat.plan(backup, listOf("playlists"), live())
        val lists = JSONObject(p.puts.getValue(UserPlaylists.KEY)).getJSONArray("playlists")
        assertEquals(1, lists.length())
        val pl = lists.getJSONObject(0)
        assertEquals("Late night", pl.getString("name"))
        assertEquals(1, pl.getJSONArray("tracks").length())
        assertEquals("Roads", pl.getJSONArray("tracks").getJSONObject(0).getString("title"))
    }

    @Test
    fun onlyPartsTheBackupHoldsAreRestored() {
        val backup = BackupFormat.parse("""{"app":"Rouen Lite","format":1,"later":{}}""")
        val p = BackupFormat.plan(backup, BackupFormat.PARTS, live())
        assertEquals(listOf("later"), p.parts)
        assertTrue(ListenLater.KEY in p.removes)
        assertFalse(Settings.KEY_HOME_ROWS in p.removes)
    }

    // ------------------------------------------------------------- storage

    private lateinit var dir: File

    @Before fun makeDir() { dir = Files.createTempDirectory("backups").toFile() }
    @After fun dropDir() { dir.deleteRecursively() }

    private fun backup(kind: String = "manual", created: Long = now): JSONObject =
        BackupFormat.build(live(), BackupFormat.PARTS, kind, "0.6.0", created)

    @Test
    fun aSavedBackupIsNamedForWhenItWasStoredAndListedWithWhatItHolds() {
        val files = BackupStore(dir) { utc }
        val id = files.save(backup(), now)
        assertEquals("rouen-lite-backup-20261007-093015", id)
        assertTrue(File(dir, "$id.json").isFile)
        val e = files.list().single()
        assertEquals(id, e.id)
        assertEquals("manual", e.kind)
        assertEquals("0.6.0", e.version)
        assertEquals(BackupFormat.PARTS, e.parts)
        assertEquals(File(dir, "$id.json").length(), e.size)
        assertNull(e.error)
        // No temp file left behind.
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun twoInTheSameSecondBothSurviveAndTheSecondListsFirst() {
        val files = BackupStore(dir) { utc }
        val a = files.save(backup(), now)
        val b = files.save(backup(), now)
        assertEquals("$a-2", b)
        assertEquals(listOf(b, a), files.list().map { it.id })
    }

    @Test
    fun tenOfYourOwnAndFiveBeforeRestoresAreKeptCountedApart() {
        val files = BackupStore(dir) { utc }
        repeat(12) { files.save(backup(), now + it * 1000L) }
        repeat(7) { files.save(backup("before-restore"), now + 60_000L + it * 1000L) }
        val all = files.list()
        assertEquals(10, all.count { it.kind == "manual" })
        assertEquals(5, all.count { it.kind == "before-restore" })
        // The newest of each survive.
        assertEquals("rouen-lite-backup-20261007-093026", all.first { it.kind == "manual" }.id)
        assertTrue(all.none { it.id == "rouen-lite-backup-20261007-093015" })
        assertTrue(all.first().id.startsWith("rouen-lite-before-restore-"))
    }

    @Test
    fun theBackupBeingRestoredIsNeverPrunedFromUnderTheRestore() {
        val files = BackupStore(dir) { utc }
        val ids = (0 until 5).map { files.save(backup("before-restore"), now + it * 1000L) }
        val oldest = ids.first()
        files.save(backup("before-restore"), now + 10_000L, keep = oldest)
        assertNotNull(files.list().firstOrNull { it.id == oldest })
    }

    @Test
    fun anOldBackupBroughtInFromAFileIsOrderedByWhenItArrived() {
        val files = BackupStore(dir) { utc }
        repeat(10) { files.save(backup(), now + it * 1000L) }
        val old = backup(created = at(2020, 1, 1, 0, 0, 0)).put("kind", "uploaded")
        val id = files.save(old, now + 100_000L)
        assertEquals(id, files.list().first().id)
        assertEquals("uploaded", files.list().first().kind)
    }

    @Test
    fun anIdIsANameNeverAPath() {
        val files = BackupStore(dir) { utc }
        File(dir.parentFile, "outside.json").writeText(backup().toString())
        for (bad in listOf("../outside", "rouen-lite-backup-20261007-093015/../x", "", "x")) {
            try {
                files.read(bad)
                fail("read $bad")
            } catch (e: BackupException) {
                assertEquals("No such backup.", e.message)
            }
            assertFalse(files.remove(bad))
        }
        assertTrue(File(dir.parentFile, "outside.json").delete())
    }

    @Test
    fun aFileThatCannotBeReadIsListedSoItCanStillBeDeleted() {
        val files = BackupStore(dir) { utc }
        File(dir, "rouen-lite-backup-20261007-093015.json").writeText("{broken")
        File(dir, "not-a-backup.json").writeText("{}")
        val e = files.list().single()
        assertNotNull(e.error)
        assertTrue(files.remove(e.id))
        assertTrue(files.list().isEmpty())
    }

    @Test
    fun whatIsReadBackIsTheFileExactly() {
        val files = BackupStore(dir) { utc }
        val id = files.save(backup(), now)
        val (text, parsed) = files.read(id)
        assertEquals(File(dir, "$id.json").readText(), text)
        assertEquals("Rouen Lite", parsed.getString("app"))
    }

    // ------------------------------------------------------------- helpers

    private fun list(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }

    private fun assertParseFails(text: String, says: String) {
        try {
            BackupFormat.parse(text)
            fail("parsed: $text")
        } catch (e: BackupException) {
            assertTrue("'${e.message}' should say '$says'", e.message!!.contains(says))
        }
    }
}
