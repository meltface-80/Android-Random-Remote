package com.musicd.lite

import com.musicd.lite.api.Settings
import com.musicd.lite.library.UserPlaylists
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Backup & restore over a real socket: the routes the Settings → Backup page
 * calls, with the field names it reads (app.js, initBackups).
 */
class BackupRoutesTest {

    private lateinit var dir: File
    private lateinit var f: ApiFixture

    @Before fun setUp() {
        dir = Files.createTempDirectory("backups").toFile()
        f = ApiFixture(backupDir = dir).start()
    }

    @After fun tearDown() {
        f.stop()
        dir.deleteRecursively()
    }

    private val settings get() = f.app.settings

    private fun track(title: String) = UserPlaylists.Track(
        albumOffset = 1, albumTitle = "Dummy", albumSubtitle = "Portishead", trackIndex = 0,
        title = title, subtitle = "Portishead", imageKey = null, trackNo = 1
    )

    /** Some of everything a backup holds. */
    private fun makeThingsWorthKeeping() {
        settings.saveHomeRows(listOf("genres" to false, "history" to true))
        settings.saveSmartPicks(enabled = false, hour = 9, dest = "later")
        settings.saveSecret(Settings.KEY_DISCOGS_TOKEN, "discogs-1")
        f.app.listenLater.add("Dummy", "Portishead")
        val made = f.app.userPlaylists.resolveTarget(null, "Mix").getOrThrow()
        f.app.userPlaylists.addTracks(made.id, listOf(track("Roads"))).getOrThrow()
    }

    private fun makeBackup(parts: List<String> = listOf("settings", "playlists", "later", "keys")): JSONObject =
        f.postJson("/api/backups", JSONObject().put("parts", JSONArray(parts)).toString())

    private fun backups(): JSONArray = f.json("/api/backups").getJSONArray("backups")

    @Test
    fun theListSaysWhatCanBeBackedUpAndHowManyAreKept() {
        val j = f.json("/api/backups")
        assertEquals(JSONArray(listOf("settings", "playlists", "later", "keys")).toString(),
                     j.getJSONArray("parts").toString())
        assertEquals(10, j.getJSONObject("keep").getInt("manual"))
        assertEquals(5, j.getJSONObject("keep").getInt("before"))
        assertEquals(0, j.getJSONArray("backups").length())
        assertTrue(j.getString("boot").isNotEmpty())
    }

    @Test
    fun aBackupIsMadeListedAndDownloadedAsTheFileItIs() {
        makeThingsWorthKeeping()
        val made = makeBackup()
        assertTrue(made.getBoolean("ok"))
        val id = made.getString("id")
        assertTrue(id, id.startsWith("rouen-lite-backup-"))
        val row = made.getJSONArray("backups").getJSONObject(0)
        // Every field the list row draws.
        assertEquals(id, row.getString("id"))
        assertEquals("manual", row.getString("kind"))
        assertEquals("test", row.getString("version"))
        assertTrue(row.getLong("size") > 0)
        assertTrue(row.getString("created").endsWith("Z"))
        assertEquals(4, row.getJSONArray("parts").length())

        val headers = HashMap<String, String>()
        val (code, text) = f.request("GET", "/api/backups/$id/download", null, headers = headers)
        assertEquals(200, code)
        assertEquals(File(dir, "$id.json").readText(), text)
        assertTrue(headers["content-type"]!!.startsWith("application/json"))
        assertEquals("attachment; filename=\"$id.json\"", headers["content-disposition"])
        val file = JSONObject(text)
        assertEquals("discogs-1", file.getJSONObject("keys").getJSONObject("discogs_token").getString("value"))
    }

    @Test
    fun nothingChosenIsNothingMade() {
        val (code, text) = f.post("/api/backups", """{"parts":[]}""")
        assertEquals(400, code)
        assertTrue(text.contains("Choose at least one"))
        assertEquals(0, backups().length())
    }

    @Test
    fun aRestorePutsBackWhatTheBackupHeldAndKeepsHowThingsWere() {
        makeThingsWorthKeeping()
        val id = makeBackup().getString("id")

        // Then everything changes.
        settings.saveHomeRows(listOf("history" to false))
        settings.saveSmartPicks(enabled = true, hour = 3, dest = "ask")
        settings.saveSecret(Settings.KEY_DISCOGS_TOKEN, "discogs-2")
        f.app.listenLater.remove("Dummy", "Portishead")
        f.app.listenLater.add("Third", "Portishead")
        f.app.userPlaylists.all().forEach { f.app.userPlaylists.delete(it.id) }
        val revBefore = f.app.live.revisions()

        val j = f.postJson("/api/backups/$id/restore",
                           """{"parts":["settings","playlists","later","keys"]}""")
        assertTrue(j.getBoolean("ok"))
        assertEquals(4, j.getJSONArray("restored").length())
        // Nothing to restart here: every reader goes to the store, so the page
        // reloads and finds it.
        assertFalse(j.getBoolean("restarting"))
        assertTrue(j.getBoolean("reload"))

        assertEquals(listOf("genres" to false, "history" to true), settings.homeRows().take(2))
        assertFalse(settings.smartPicksEnabled())
        assertEquals(9, settings.smartPicksHour())
        assertEquals("later", settings.smartPicksDest())
        assertEquals("discogs-1", settings.secret(Settings.KEY_DISCOGS_TOKEN))
        assertEquals(listOf("Dummy"), f.app.listenLater.entries().map { it.title })
        assertEquals(listOf("Mix"), f.app.userPlaylists.all().map { it.name })
        assertEquals(listOf("Roads"), f.app.userPlaylists.all().single().tracks.map { it.title })

        // Every other screen hears about it.
        val revAfter = f.app.live.revisions()
        assertNotEquals(revBefore["settings"], revAfter["settings"])
        assertNotEquals(revBefore["later"], revAfter["later"])

        // How things were just before is itself a backup now.
        val before = j.getString("before")
        assertTrue(before, before.startsWith("rouen-lite-before-restore-"))
        val beforeRow = (0 until backups().length()).map { backups().getJSONObject(it) }
            .single { it.getString("id") == before }
        assertEquals("before-restore", beforeRow.getString("kind"))
        val kept = JSONObject(File(dir, "$before.json").readText())
        assertEquals("discogs-2", kept.getJSONObject("keys").getJSONObject("discogs_token").getString("value"))
    }

    @Test
    fun onlyTheChosenPartsAreRestored() {
        makeThingsWorthKeeping()
        val id = makeBackup().getString("id")
        settings.saveSecret(Settings.KEY_DISCOGS_TOKEN, "discogs-2")
        f.app.listenLater.remove("Dummy", "Portishead")

        f.postJson("/api/backups/$id/restore", """{"parts":["later"]}""")
        assertEquals(listOf("Dummy"), f.app.listenLater.entries().map { it.title })
        assertEquals("discogs-2", settings.secret(Settings.KEY_DISCOGS_TOKEN))
    }

    @Test
    fun aRestoreOfAPartTheBackupLacksIsRefusedAndChangesNothing() {
        makeThingsWorthKeeping()
        val id = makeBackup(listOf("later")).getString("id")
        val (code, text) = f.post("/api/backups/$id/restore", """{"parts":["keys"]}""")
        assertEquals(400, code)
        assertTrue(text.contains("Nothing chosen that this backup holds"))
        assertEquals(1, backups().length())   // no before-restore copy either
    }

    @Test
    fun anotherSiteCannotRestoreOrUploadBehindThePagesBack() {
        // A page on any website the phone's browser opens can POST to
        // 127.0.0.1 — but only a "simple" request, with no preflight, which
        // means text/plain or a form. Requiring the page's own content types
        // makes those fail before anything is read.
        makeThingsWorthKeeping()
        val id = makeBackup().getString("id")
        settings.saveSecret(Settings.KEY_DISCOGS_TOKEN, "discogs-2")

        val (code, _) = f.request("POST", "/api/backups/$id/restore",
                                  """{"parts":["keys"]}""", contentType = "text/plain")
        assertEquals(415, code)
        assertEquals("discogs-2", settings.secret(Settings.KEY_DISCOGS_TOKEN))

        val crafted = JSONObject(File(dir, "$id.json").readText()).toString()
        assertEquals(415, f.request("POST", "/api/backups/upload", crafted, contentType = "text/plain").first)
        assertEquals(415, f.request("POST", "/api/backups/upload", crafted,
                                    contentType = "application/x-www-form-urlencoded").first)
        assertEquals(415, f.request("POST", "/api/backups", """{"parts":["keys"]}""",
                                    contentType = "text/plain").first)
        assertEquals(1, backups().length())
    }

    @Test
    fun anUploadedFileIsKeptBesideTheOthersAndRestoresLikeThem() {
        makeThingsWorthKeeping()
        val id = makeBackup().getString("id")
        val text = File(dir, "$id.json").readText()
        val (code, body) = f.request("POST", "/api/backups/upload", text, contentType = "application/octet-stream")
        assertEquals(body, 200, code)
        val j = JSONObject(body)
        val up = j.getString("id")
        assertNotEquals(id, up)
        val row = (0 until j.getJSONArray("backups").length()).map { j.getJSONArray("backups").getJSONObject(it) }
            .single { it.getString("id") == up }
        assertEquals("uploaded", row.getString("kind"))

        settings.saveSecret(Settings.KEY_DISCOGS_TOKEN, "discogs-2")
        f.postJson("/api/backups/$up/restore", """{"parts":["keys"]}""")
        assertEquals("discogs-1", settings.secret(Settings.KEY_DISCOGS_TOKEN))
    }

    @Test
    fun aFileThatIsNotABackupIsTurnedAwayWithTheReason() {
        val (code, text) = f.request("POST", "/api/backups/upload", "{\"hello\":1}",
                                     contentType = "application/octet-stream")
        assertEquals(400, code)
        assertTrue(text, JSONObject(text).getString("error").contains("not a Rouen Lite backup"))
        assertEquals(0, backups().length())
    }

    @Test
    fun aCraftedUploadCannotTurnOnNetworkAccess() {
        val crafted = """{"app":"Rouen Lite","format":1,"parts":["settings","keys"],
            "settings":{"lan_access":{"enabled":true,"pin":"KNOWN234","secret":"theirs"}},
            "keys":{"lan_access":{"enabled":true,"pin":"KNOWN234","secret":"theirs"}}}"""
        val (code, body) = f.request("POST", "/api/backups/upload", crafted, contentType = "application/octet-stream")
        assertEquals(body, 200, code)
        val up = JSONObject(body).getString("id")
        f.postJson("/api/backups/$up/restore", """{"parts":["settings","keys"]}""")
        assertFalse(settings.lan().enabled)
        assertEquals("", settings.lan().pin)
        assertTrue(f.app.rootUrl.contains("127.0.0.1"))
    }

    @Test
    fun aBackupIsDeletedAndAnUnknownOneIsNotFound() {
        val id = makeBackup().getString("id")
        val (code, body) = f.request("DELETE", "/api/backups/$id", null)
        assertEquals(200, code)
        assertEquals(0, JSONObject(body).getJSONArray("backups").length())
        assertFalse(File(dir, "$id.json").exists())
        assertEquals(404, f.request("DELETE", "/api/backups/$id", null).first)
        assertEquals(404, f.get("/api/backups/$id/download").first)
        assertEquals(404, f.post("/api/backups/$id/restore", """{"parts":["later"]}""").first)
        assertEquals(404, f.get("/api/backups/..%2F..%2Fetc/download").first)
    }

    @Test
    fun aHostWithNowhereToKeepBackupsSaysSo() {
        val bare = ApiFixture().start()
        try {
            val (code, text) = bare.get("/api/backups")
            assertEquals(501, code)
            assertTrue(text.contains("error"))
        } finally {
            bare.stop()
        }
    }
}
