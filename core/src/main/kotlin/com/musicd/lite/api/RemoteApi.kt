package com.musicd.lite.api

import com.musicd.lite.str
import com.musicd.lite.strOrNull
import com.musicd.lite.Discover
import com.musicd.lite.LiveState
import com.musicd.lite.QueueHistory
import com.musicd.lite.Log
import com.musicd.lite.MusicdLite
import com.musicd.lite.http.HttpServer
import com.musicd.lite.http.LanAccess
import com.musicd.lite.http.Network
import com.musicd.lite.http.Request
import com.musicd.lite.http.Response
import com.musicd.lite.backup.BackupException
import com.musicd.lite.backup.BackupFormat
import com.musicd.lite.backup.BackupStore
import com.musicd.lite.library.AlbumRecord
import com.musicd.lite.library.Artists
import com.musicd.lite.library.Albums
import com.musicd.lite.library.UserPlaylists
import com.musicd.lite.library.LibraryView
import com.musicd.lite.library.ListenLater
import com.musicd.lite.library.Normalize
import com.musicd.lite.library.Search
import com.musicd.lite.meta.Metadata
import com.musicd.lite.meta.Pitchfork
import com.musicd.lite.meta.ShareLinks
import com.musicd.lite.roon.AlbumFilter
import com.musicd.lite.roon.BrowseException
import com.musicd.lite.roon.MooSocket
import com.musicd.lite.roon.ZoneSettings
import com.musicd.lite.store.YearSource
import org.json.JSONArray
import org.json.JSONObject

/** The bundled front-end: MusicD-Remote's own public/ directory, verbatim. */
interface StaticAssets {
    /** Bytes and content type for a web path such as "/app.js", or null. */
    fun read(path: String): Pair<ByteArray, String>?
}

/**
 * MusicD-Remote's HTTP API, reimplemented over the native Roon client.
 *
 * The front-end is unmodified, so these responses have to match the shapes it
 * already reads. Where a feature is not in this build the endpoint still
 * answers, in the shape the UI expects, saying the feature is off — the front
 * end has first-class "this feature is disabled" handling for exactly this, and
 * a 404 would instead surface as an error toast on a screen the user never
 * asked for.
 */
class RemoteApi(
    private val app: MusicdLite,
    private val assets: StaticAssets
) : HttpServer.Handler {

    private companion object {
        const val TAG = "Api"

        /** What Settings → Backup's writes are sent as — see backupsRoute. */
        val JSON_TYPES = setOf("application/json")
        val UPLOAD_TYPES = setOf("application/octet-stream", "application/json")
        const val RANDOM_DEFAULT = 30
        const val HISTORY_DAYS = 30
        const val HISTORY_MAX_TILES = 60

        /** Roon rejects a play of more albums than this in one go. */
        const val PLAY_MULTI_MAX = 400

        /**
         * How many albums one "add to playlist" may open.
         *
         * Each costs a browse walk on the Core to read its tracklist — the
         * track route needs none, because the page already has the tracks on
         * screen. Twenty is a generous multi-select and a bounded number of
         * round trips.
         */
        const val ADD_ALBUMS_MAX = 20

        /** How many of the day's picks "Send to Listen later" puts on the list. Rouen's five. */
        const val PICKS_TO_LATER = 5

        /** Distinguishes a blocked ARTIST from the album keys once stored. */
        const val BLOCKED_ARTIST_PREFIX = "artist:"

        /** The renderings Roon's image service accepts. */
        val IMAGE_SCALES = setOf("fit", "fill", "stretch")

        /** Roon writes a genre's size into its subtitle, and nowhere else. */
        val GENRE_ALBUM_COUNT = Regex("(\\d[\\d,]*)\\s*albums?", RegexOption.IGNORE_CASE)

        /**
         * How long a zone-state request may wait for news. Comfortably inside
         * the HTTP read timeout, so a quiet system answers rather than hangs
         * up, and short enough that an interpolated progress bar resynchronises
         * before anyone could notice it drifting.
         */
        const val ZONE_WAIT_MS = 20_000
        const val ZONE_WAIT_MAX_MS = 25_000

        /** Said to anything still asking for a streaming route. */
        const val STREAMING_UNAVAILABLE =
            "%s isn't in this build. Roon streams it through its own account anyway."
    }

    /** Zones with a multi-album fill in flight. */
    private val fillingZones = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    private val roon get() = app.roon
    private val index get() = app.index
    private val store get() = app.store()
    private val view get() = app.view
    private val settings get() = app.settings

    override fun handle(request: Request): Response {
        val path = request.path
        return try {
            if (path.startsWith("/api/")) apiRoute(request, path) else staticRoute(request, path)
        } catch (e: BrowseException) {
            // A stale offset is a transient condition with a rebuild already
            // due, so it gets a 409 "try again" rather than a 500.
            Json.error(if (e.stale) 409 else 500, e.message ?: "Roon browse failed")
        } catch (e: MooSocket.MooException) {
            Json.error(503, e.message ?: "Not paired with a Roon Core")
        } catch (e: IllegalArgumentException) {
            Json.error(400, e.message ?: "Bad request")
        } catch (e: Exception) {
            Log.w(TAG, "$path failed: ${e.message}", e)
            Json.error(500, e.message ?: "Internal error")
        }
    }

    // ------------------------------------------------------------- static

    private fun staticRoute(request: Request, path: String): Response {
        if (request.method != "GET" && request.method != "HEAD") {
            return Json.error(405, "Method not allowed")
        }
        // Anything that is not a file is the single-page app, so a deep link
        // still opens it. /display used to be its own page; the wall display is
        // not in this build, so that path falls through to the app like any
        // other unknown one.
        //
        // /dial is the exception, and it has to be one: without this it would
        // fall through to the app like every other unknown path, which is a
        // page that works — so nothing would look broken and the dial would
        // simply never appear. It is the same gate as everything else; static
        // files are only reached once MusicdLite.guard() has allowed the
        // request, so this adds a page, not a way in.
        val wanted = when {
            path == "/" || path.isEmpty() -> "/index.html"
            path == "/dial" || path == "/dial/" -> "/dial.html"
            else -> path
        }
        val hit = assets.read(wanted) ?: assets.read("/index.html")
        ?: return Response.text(404, "The bundled front-end is missing from this build")

        // REVALIDATE, DO NOT EXPIRE. These used to go out with max-age=3600 and
        // no validator, on the reasoning that assets inside the APK only change
        // when the app does. They do — but the WebView's cache is on DISK and
        // outlives both the process and the update, so for an hour after
        // installing a new version it kept serving the OLD page from the old
        // one, with nothing to reveal it but the change not being there.
        //
        // It only started biting when the port was fixed at 3450 for LAN
        // access. Before that the OS handed out a different port every launch,
        // so the URL was new every time and nothing ever matched in the cache.
        //
        // no-cache does not mean "do not store": it means "ask before using
        // what you stored". The version is the validator, so an unchanged app
        // still gets a 304 and no bytes, and a new one cannot be missed.
        val etag = "\"v${app.version}\""
        if (request.headers["if-none-match"] == etag) {
            return Response.bytes(304, hit.second, ByteArray(0), validatorHeaders(etag))
        }
        return Response.bytes(200, hit.second, hit.first, validatorHeaders(etag))
    }

    private fun validatorHeaders(etag: String) = mapOf(
        "Cache-Control" to "no-cache",
        "ETag" to etag
    )

    // ---------------------------------------------------------------- API

    private fun apiRoute(request: Request, path: String): Response {
        val post = request.method == "POST"

        // Image is the highest-volume route; keep it first.
        if (path.startsWith("/api/image/")) return image(request, path.removePrefix("/api/image/"))

        // Settings → Backup: ids in the path, so not one of the fixed routes.
        if (path == "/api/backups" || path.startsWith("/api/backups/")) return backupsRoute(request, path)

        return when (path) {
            "/api/status" -> status()
            "/api/live" -> live(request)
            "/api/zones" -> zones()
            "/api/outputs" -> outputs()
            "/api/zone-state" -> zoneState(request)
            "/api/queue" -> queue(request)
            "/api/queue/play-history-next" -> requirePost(post) { playHistoryNext(request) }
            "/api/queue/history-multi" -> requirePost(post) { historyMulti(request) }
            "/api/control" -> control(request)
            "/api/seek" -> seek(request)
            "/api/volume" -> volume(request)
            "/api/zone-settings" -> zoneSettings(request)
            "/api/pause-all" -> requirePost(post) { roon.pauseAll(); Json.ok() }
            "/api/mute-all" -> muteAll(request)
            "/api/group-outputs" -> groupOutputs(request, group = true)
            "/api/ungroup-outputs" -> groupOutputs(request, group = false)
            "/api/transfer-zone" -> transferZone(request)
            "/api/play-from-here" -> playFromHere(request)
            "/api/output/standby" -> outputControl(request, standby = true)
            "/api/output/convenience-switch" -> outputControl(request, standby = false)

            "/api/random-albums" -> randomAlbums(request)
            "/api/library/albums" -> libraryAlbums(request)
            "/api/library/facets" -> libraryFacets()
            "/api/library/rescan", "/api/reindex" -> requirePost(post) { rescan() }
            "/api/library-stats" -> Json.obj(
                JSONObject().put("albums", index.count).put("building", index.isBuilding)
            )

            "/api/album" -> album(request)
            "/api/album/extras" -> albumExtras(request)
            "/api/album/release-date" -> releaseDate(request)
            "/api/album/now-playing" -> nowPlayingAlbum(request)
            "/api/play" -> playAlbum(request, "play_now")
            "/api/play-track" -> playTrack(request)
            "/api/play-multi" -> playMulti(request)

            "/api/search" -> search(request)
            "/api/search-status" -> Json.obj(
                JSONObject()
                    .put("ready", index.isBuilt)
                    .put("building", index.isBuilding)
                    .put("progress", index.progress)
                    .put("count", index.count)
            )

            "/api/artists" -> artists(request)
            "/api/artist-albums" -> artistAlbums(request)
            "/api/artist-bio" -> artistBio(request)

            // Updates. The banner polls status, taps check, then apply, and
            // keeps polling until `current` becomes the new version — which
            // happens when Android has replaced the app and this server has
            // restarted inside it.
            "/api/update/status" -> updateStatus()
            "/api/update/check" -> requirePost(post) { updateCheck() }
            "/api/update/apply" -> requirePost(post) { updateApply() }

            "/api/filters/genres" -> genres()
            "/api/filters/decades" -> decades()
            "/api/filters/tags" -> tags()

            "/api/home/history" -> homeHistory(request)
            "/api/home/album-of-the-day" -> albumOfTheDay()
            "/api/home/genre-groups" -> genreGroups()

            "/api/radio" -> radio(request)
            "/api/listen-later" -> if (post) setListenLater(request) else listenLaterList()
            "/api/smart-picks" -> smartPicks(request)
            "/api/smart-picks/block" -> smartPickBlock(request)
            "/api/smart-picks/rebuild" -> requirePost(post) { Json.ok() }

            // Deezer-backed: "If you like this" under the share card, and
            // Discover. Neither touches the Core.
            "/api/similar" -> similar(request)
            "/api/discover" -> discoverList(request)
            "/api/discover/rebuild" -> requirePost(post) { discoverRebuild() }

            "/api/settings/home-rows" -> if (post) saveHomeRows(request) else homeRows()
            "/api/settings/lan" -> if (post) saveLan(request) else lanStatus(request)

            "/api/user-playlists" ->
                if (post) saveUserPlaylist(request) else userPlaylistList()
            "/api/user-playlist" -> userPlaylistOne(request)
            "/api/user-playlists/delete" -> requirePost(post) { deleteUserPlaylist(request) }
            "/api/user-playlists/add" -> requirePost(post) { addTracksToPlaylist(request) }
            "/api/user-playlists/add-albums" ->
                requirePost(post) { addAlbumsToPlaylist(request) }
            "/api/settings/smart-picks" -> if (post) saveSmartPicks(request) else smartPickSettings()
            "/api/settings/share-links" -> if (post) saveShareLinks(request) else shareLinksSettings()
            "/api/settings/discover" -> if (post) saveDiscover(request) else discoverSettings()
            "/api/settings/labels" -> labelsSetting(post)
            "/api/settings/discogs-token" ->
                secret(request, post, Settings.KEY_DISCOGS_TOKEN, "token")
            "/api/settings/fanart-key" ->
                secret(request, post, Settings.KEY_FANART_KEY, "key")

            "/api/search/external" -> searchExternal(request)

            "/api/pitchfork/reviews" -> pitchforkReviews(request)
            "/api/pitchfork/review" -> pitchforkReview(request)
            "/api/pitchfork/qobuz" -> pitchforkQobuz(request)

            "/api/shortcut/zones" -> zones()
            "/api/shortcut/play-random" -> shortcutPlay(request)

            // Random Album — the disc under the greeting. Rouen's route plays
            // something unheard in a year; see playRandomAlbum for why this one
            // draws from the whole library.
            "/api/play-unheard" -> requirePost(post) { playRandomFromHome(request) }

            // The share card's Qobuz chip, upgraded to open the app on the
            // record. The same lookup the Pitchfork screen has always used;
            // answered here, ahead of the /api/qobuz prefix that is otherwise
            // not in this build (an account-free page read, not the account API).
            "/api/qobuz-link" -> pitchforkQobuz(request)

            // Features this build does not have, in the "off" shape their
            // screens already understand. A 404 left each one asking again.
            "/api/settings/display" ->
                if (post) Json.error(501, "The wall display isn't in this build.")
                else Json.obj(JSONObject().put("enabled", false).put("seconds", 10))
            "/api/settings/waveform" ->
                if (post) Json.error(501, "Waveforms aren't in this build — they need the audio files or a streaming account.")
                else Json.obj(JSONObject().put("enabled", false))

            else -> notInLite(path, post)
        }
    }

    /**
     * The front-end renders one of Roon's own outcomes as its toast and treats
     * anything it does not recognise as "Rescan failed", so this reports the
     * status rather than just acknowledging the request. It also blocks until
     * the answer is real: "your library is up to date" is only worth saying
     * once it has actually been checked.
     */
    private fun rescan(): Response {
        val r = app.rescan(force = true)
        val body = JSONObject().put("status", r.status)
        r.count?.let { body.put("count", it) }
        return Json.obj(body)
    }

    private inline fun requirePost(isPost: Boolean, body: () -> Response): Response =
        if (isPost) body() else Json.error(405, "POST required")

    // ------------------------------------------------------------- backups

    /**
     * Settings → Backup (Rouen v1.8.84): make, list, download, bring in from a
     * file, restore and delete. The work is backup/Backup.kt's.
     *
     * Every write demands the page's own content type. Any website the phone's
     * browser opens can POST to 127.0.0.1, but only as a "simple" request —
     * text/plain or a form — because anything else makes the browser ask this
     * server's leave first, and it never gives it. So requiring JSON (or the
     * upload's octet-stream) stops a stranger's page from replacing what is
     * here before a byte of it is read. DELETE is never a simple request.
     * This narrows what the gate lets through; it is not a way round it.
     */
    private fun backupsRoute(request: Request, path: String): Response {
        val backups = app.backups
            ?: return Json.error(501, "Backups aren't available on this host — it has nowhere to keep them.")
        val method = request.method
        val rest = path.removePrefix("/api/backups").trim('/')
        fun listed(): JSONObject = JSONObject()
            .put("boot", backups.boot)
            .put("parts", JSONArray(BackupFormat.PARTS))
            .put("keep", JSONObject().put("manual", BackupStore.KEEP_MANUAL).put("before", BackupStore.KEEP_BEFORE))
            .put("backups", JSONArray(backups.files.list().map { it.toJson() }))
        return when {
            rest.isEmpty() && method == "GET" -> Json.obj(listed())

            rest.isEmpty() && method == "POST" -> requireType(request, JSON_TYPES) {
                val parts = BackupFormat.partsFrom(Json.body(request).optJSONArray("parts"))
                if (parts.isEmpty()) return Json.error(400, "Choose at least one thing to back up.")
                val id = try {
                    backups.make(parts)
                } catch (e: Exception) {
                    Log.w(TAG, "could not make a backup: ${e.message}", e)
                    return Json.error(500, "Couldn't make the backup: ${e.message}")
                }
                Log.i(TAG, "backup made: $id (${parts.joinToString()})")
                Json.obj(listed().put("ok", true).put("id", id))
            }

            // A backup file from elsewhere, kept beside the others so it can be
            // restored (and downloaded again) like one made here.
            rest == "upload" && method == "POST" -> requireType(request, UPLOAD_TYPES) {
                val id = try {
                    backups.upload(request.bodyText)
                } catch (e: BackupException) {
                    return Json.error(400, e.message ?: "That file is not a Rouen Lite backup.")
                } catch (e: Exception) {
                    return Json.error(500, "Couldn't keep that backup: ${e.message}")
                }
                Log.i(TAG, "stored an uploaded backup as $id")
                Json.obj(listed().put("ok", true).put("id", id))
            }

            rest.endsWith("/download") && method == "GET" -> {
                val id = rest.removeSuffix("/download")
                val text = try {
                    backups.files.read(id).first
                } catch (e: BackupException) {
                    return Json.error(404, e.message ?: "No such backup.")
                }
                Response(
                    200, "application/json; charset=utf-8", text.toByteArray(Charsets.UTF_8),
                    mapOf(
                        "Content-Disposition" to "attachment; filename=\"$id.json\"",
                        "Cache-Control" to "no-store"
                    )
                )
            }

            rest.endsWith("/restore") && method == "POST" -> requireType(request, JSON_TYPES) {
                val id = rest.removeSuffix("/restore")
                val parts = BackupFormat.partsFrom(Json.body(request).optJSONArray("parts"))
                val done = try {
                    backups.restore(id, parts)
                } catch (e: BackupException) {
                    return Json.error(404, e.message ?: "No such backup.")
                } catch (e: IllegalArgumentException) {
                    return Json.error(400, e.message ?: "Nothing chosen that this backup holds.")
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "restore of $id failed: ${e.message}", e)
                    return Json.error(500, e.message ?: "Restore failed.")
                }
                Log.i(TAG, "restored ${done.parts.joinToString()} from $id (kept ${done.before})")
                // Nothing restarts: every reader goes to the store, so the page
                // reloads and finds what was restored.
                Json.obj(
                    JSONObject().put("ok", true).put("restored", JSONArray(done.parts))
                        .put("before", done.before).put("restarting", false).put("reload", true)
                )
            }

            rest.isNotEmpty() && !rest.contains('/') && method == "DELETE" ->
                if (backups.files.remove(rest)) Json.obj(listed().put("ok", true))
                else Json.error(404, "No such backup.")

            else -> Json.error(404, "No such endpoint: ${request.method} $path")
        }
    }

    /**
     * The request's content type, without parameters, must be one of [types] —
     * see backupsRoute for why. 415 otherwise, before the body is looked at.
     */
    private inline fun requireType(request: Request, types: Set<String>, body: () -> Response): Response {
        val type = request.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() ?: ""
        return if (type in types) body()
        else Json.error(415, "This needs the page's own request — sent as ${types.joinToString(" or ")}.")
    }

    // -------------------------------------------------------------- status

    private fun status(): Response {
        val s = roon.status
        return Json.obj(
            JSONObject()
                .put("paired", roon.isPaired)
                .put("core_id", s.coreId ?: JSONObject.NULL)
                .put("core_name", s.coreName ?: JSONObject.NULL)
                .put("zone_count", roon.zones().size)
                .put("library_importing", false)
                .put("library_recheck_pending", index.isBuilding)
                .put("index_built_at", index.builtAt)
                // The side menu's "checked just now"; 0 until the first probe,
                // and the page falls back to index_built_at then.
                .put("library_checked_at", app.libraryCheckedAt)
                .put("index_count", index.count)
                // Not in MusicD-Remote's shape, and additive on purpose: the
                // pairing screen needs to say WHY it is waiting, and on a phone
                // "enable the extension in Roon" is the whole first-run story.
                .put("stage", s.stage.name.lowercase())
                .put("stage_detail", s.detail ?: JSONObject.NULL)
                .put("index_progress", index.progress)
                .put("version", app.version)
                .put("lite", true)
        )
    }

    /**
     * The live-state revisions, as a long poll.
     *
     * Rouen's page asks for these every three seconds. This build's page sends
     * back the `token` it was last given as `wait_for`, and the request is held
     * until a revision moves or [LiveState.MAX_WAIT_MS] passes — so a screen
     * hears about a play, a setting changed on another device or a rebuilt
     * library within a moment, and an idle app makes one request every
     * twenty-five seconds rather than twenty a minute. No `wait_for` answers
     * at once: the page's first ask has nothing to compare against.
     *
     * One held request per open page, on the server's worker pool, alongside
     * the zone-state wait that already works this way.
     */
    private fun live(request: Request): Response {
        val timeout = (request.int("timeout")?.toLong() ?: LiveState.MAX_WAIT_MS)
        val rev = app.live.await(request.str("wait_for"), timeout)
        return Json.obj(app.live.toJson(rev))
    }

    // --------------------------------------------------------------- zones

    private fun zones(): Response {
        val list = roon.zones().map { z ->
            JSONObject()
                .put("zone_id", z.zoneId)
                .put("display_name", z.displayName)
                .put("state", z.state)
                .put("settings", z.settings.toJson())
                .put("outputs", JSONArray().also { a -> z.outputs.forEach { a.put(it.toJson()) } })
        }
        return Json.obj(JSONObject().put("zones", Json.arrayOf(list)))
    }

    private fun outputs(): Response {
        if (!roon.isPaired) return Json.error(503, "Not paired with a Roon Core")
        val zoneName = roon.zones().associate { it.zoneId to it.displayName }
        val list = roon.outputs().map { o ->
            o.toJson().put("zone_name", o.zoneId?.let { zoneName[it] } ?: "")
        }
        return Json.obj(JSONObject().put("outputs", Json.arrayOf(list)))
    }

    /**
     * Zone state, optionally waited for rather than asked for.
     *
     * With no `wait_for` this answers immediately, as it always has. With one,
     * it blocks until the zone feed moves past that revision — so the page can
     * hold a request open and be answered the instant Roon says something,
     * instead of asking forty times a minute to be told nothing changed.
     *
     * The wait is capped well under the socket's own read timeout, so a quiet
     * system returns a normal response rather than dropping the connection. The
     * cap doubles as a resynchronisation: seek positions deliberately do not
     * wake a waiter, and this is what stops the page's interpolated progress
     * bar drifting for longer than that.
     */
    private fun zoneState(request: Request): Response {
        if (!roon.isPaired) return Json.error(503, "Not paired with a Roon Core")

        val waitFor = request.str("wait_for")?.toLongOrNull()
        if (waitFor != null) {
            val timeout = (request.int("timeout") ?: ZONE_WAIT_MS).coerceIn(0, ZONE_WAIT_MAX_MS)
            runCatching { roon.awaitZoneChange(waitFor, timeout.toLong()) }
        }

        // Read BEFORE snapshotting the zone, and this order is the whole
        // correctness of the scheme. A change landing between the two reads
        // then leaves the client holding a revision OLDER than its data, so its
        // next wait returns at once and it catches up. Reading it afterwards
        // would hand back a revision NEWER than the data — and the client would
        // wait on a change it had already been given a number for, and sleep
        // through it.
        val revision = roon.zoneRevision
        val zone = roon.zone(request.str("zone"))
        // Remember what the page is watching. The notification and the media
        // session live outside the page and have no other way to know which
        // zone the user means. saveLastZone ignores an unchanged value, so this
        // does not write on every request.
        zone?.let { runCatching { settings.saveLastZone(it.zoneId) } }
            ?: return Json.obj(JSONObject().put("zone", JSONObject.NULL))
        val np = zone.nowPlaying

        val outputs = zone.outputs.map { o ->
            JSONObject()
                .put("output_id", o.outputId)
                .put("display_name", o.displayName)
                .put("is_muted", o.volume?.isMuted ?: false)
                .put("volume", o.volume?.toJson() ?: JSONObject.NULL)
        }

        val nowPlaying = if (np == null) JSONObject.NULL else JSONObject()
            .put("line1", np.line1)
            .put("line2", np.line2)
            .put("line3", np.line3)
            .put("artists", artistLinks(np.line2))
            .put("image_key", np.imageKey ?: JSONObject.NULL)
            .put("length", np.lengthSeconds ?: JSONObject.NULL)
            .put("seek_position", np.seekPosition ?: JSONObject.NULL)

        return Json.obj(
            JSONObject()
            // What to wait on next. Read AFTER the zone is snapshotted, so a
            // change landing mid-request is never lost: the client would wait
            // on a revision it has not actually seen and sleep through it.
            .put("revision", revision)
            .put(
                "zone",
                JSONObject()
                    .put("zone_id", zone.zoneId)
                    .put("display_name", zone.displayName)
                    .put("state", zone.state)
                    .put("is_play_allowed", zone.isPlayAllowed)
                    .put("is_pause_allowed", zone.isPauseAllowed)
                    .put("is_next_allowed", zone.isNextAllowed)
                    .put("is_previous_allowed", zone.isPreviousAllowed)
                    .put("is_seek_allowed", zone.isSeekAllowed)
                    .put("settings", zone.settings.toJson())
                    .put("queue_items_remaining", zone.queueItemsRemaining ?: JSONObject.NULL)
                    .put("outputs", Json.arrayOf(outputs))
                    .put("now_playing", nowPlaying)
            )
        )
    }

    /**
     * A credit split into individually linkable names, for now-playing.
     *
     * `linkable` says whether the library can actually open a screen for that
     * name. It matters on the now-playing line because that is the TRACK
     * artist: on a compilation most track artists have no album of their own,
     * and linking them all would be a row of dead ends.
     *
     * The album view wants [artistNames] instead — the two endpoints do NOT
     * carry the same shape. See there for why.
     */
    private fun artistLinks(credit: String): JSONArray {
        val names = Normalize.splitArtists(credit)
        if (names.isEmpty()) return JSONArray()
        val known = index.artistKeys
        return JSONArray().also { arr ->
            for (n in names) {
                arr.put(
                    JSONObject()
                        .put("name", n.name)
                        .put("linkable", n.normalized in known)
                )
            }
        }
    }

    /**
     * The same split as [artistLinks], as plain names.
     *
     * The album view marks every credit linkable itself — the credit came off
     * a library album, so that album is on the artist's screen at minimum —
     * and so it wraps each entry: `names.map(name => ({ name, linkable: true }))`.
     * Handing it objects makes `name` an object, and the button renders as
     * "[object Object]".
     */
    private fun artistNames(credit: String): JSONArray =
        JSONArray().also { arr -> Normalize.splitArtists(credit).forEach { arr.put(it.name) } }

    private fun queue(request: Request): Response {
        val zoneId = request.str("zone") ?: return Json.error(400, "zone is required")
        val items = roon.queue(zoneId)
        return Json.obj(
            JSONObject()
                .put("items", Json.arrayOf(items.map { it.toJson() }))
                // What already played, newest first — the "played earlier"
                // fold-out above the Now playing divider.
                .put("history", Json.arrayOf(app.queueHistory.recent(zoneId).map { it.toJson() }))
        )
    }

    /**
     * The library album a departed track came from, or null.
     *
     * By NAME, because that is all a departed track leaves behind: the album
     * title it played under (the zone's line3) resolved against the snapshot,
     * with the credit to tell two same-titled albums apart. A stream, a radio
     * track Roon added from outside the library, or an album since removed
     * resolves to nothing — an outcome the page explains, not a failure.
     *
     * Rouen also tries a track-title index when the album does not resolve;
     * that needs a reverse index of every album's tracks this build does not
     * keep, so a track whose album title Roon reported differently from the
     * library's is unresolved here.
     */
    private fun resolvePlayedTrack(album: String, artist: String): AlbumRecord? {
        if (album.isBlank()) return null
        return index.relocate(album, artist)
            ?: Normalize.splitArtists(artist).firstNotNullOfOrNull { index.relocate(album, it.name) }
            ?: index.relocate(album, null)?.takeIf { artist.isBlank() }
    }

    /**
     * Play a track from the zone's played-earlier list, NEXT. Not "play from
     * here": a played track is gone from Roon's queue, its queue_item_id is
     * spent, and rebuilding the queue around it would be hundreds of Core
     * calls behind a play_now that destroys the live queue first. Inserting
     * the one track after the current one is a single browse navigation, and
     * honest about being a different thing.
     */
    private fun playHistoryNext(request: Request): Response {
        val body = Json.body(request)
        val zone = body.strOrNull("zone_or_output_id") ?: return Json.error(400, "zone_or_output_id required")
        val title = body.str("track").trim()
        if (title.isEmpty()) return Json.error(400, "track required")
        val hit = resolvePlayedTrack(body.str("album").trim(), body.str("artist"))
            ?: return Json.obj(
                JSONObject().put("error", "Couldn't find that track in your library to play it again")
                    .put("unresolved", true),
                404
            )
        val (invoked, track) = app.albums.invokeTrack(
            hit.offset, 0, title, zone, "play_next", null, Albums.Expect(hit.title, hit.subtitle)
        )
        return Json.ok(JSONObject().put("action", invoked).put("track", track).put("album", hit.title))
    }

    /**
     * Several played tracks at once, in the order they were picked. Every track
     * is resolved BEFORE the Core is touched, so a selection with an
     * unplayable track in it says so instead of leaving the queue holding an
     * arbitrary prefix of what was asked for. One refusal does not abandon the
     * rest. One run per zone at a time: two interleaved runs would shuffle
     * each other's inserts.
     */
    private fun historyMulti(request: Request): Response {
        val body = Json.body(request)
        val zone = body.strOrNull("zone_or_output_id") ?: return Json.error(400, "zone_or_output_id required")
        val kind = body.str("kind")
        if (kind != "play_next" && kind != "queue") return Json.error(400, "kind must be play_next or queue")
        val tracks = body.optJSONArray("tracks")
        if (tracks == null || tracks.length() == 0) return Json.error(400, "tracks required")
        if (tracks.length() > QueueHistory.MULTI_MAX) {
            return Json.error(400, "at most ${QueueHistory.MULTI_MAX} tracks at a time")
        }
        val resolved = ArrayList<Pair<String, AlbumRecord>>()
        val unresolved = ArrayList<String>()
        for (i in 0 until tracks.length()) {
            val t = tracks.optJSONObject(i) ?: continue
            val title = t.str("track").trim()
            if (title.isEmpty()) continue
            val hit = resolvePlayedTrack(t.str("album").trim(), t.str("artist"))
            if (hit != null) resolved += title to hit else unresolved += title
        }
        if (resolved.isEmpty()) {
            return Json.obj(
                JSONObject().put("error", "None of those are in your library to play again")
                    .put("unresolved", Json.strings(unresolved)).put("queued", 0),
                404
            )
        }
        if (!historyZones.add(zone)) return Json.error(409, "Still adding the last selection to this zone")
        var queued = 0
        var firstError: String? = null
        val failed = ArrayList<String>()
        try {
            for ((title, hit) in QueueHistory.sendOrderFor(kind, resolved)) {
                try {
                    app.albums.invokeTrack(hit.offset, 0, title, zone, kind, null, Albums.Expect(hit.title, hit.subtitle))
                    queued++
                } catch (e: Exception) {
                    failed += title
                    if (firstError == null) firstError = e.message
                }
            }
        } finally {
            historyZones.remove(zone)
        }
        val out = JSONObject().put("kind", kind).put("queued", queued)
            .put("failed", Json.strings(failed)).put("unresolved", Json.strings(unresolved))
        if (queued == 0) return Json.obj(out.put("error", firstError ?: "Roon refused those tracks"), 500)
        firstError?.let { out.put("error", it) }
        return Json.ok(out)
    }

    /** Zones with a played-earlier run in flight — see historyMulti. */
    private val historyZones = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    // ------------------------------------------------------------ transport

    private fun control(request: Request): Response {
        val id = request.str("zone_or_output_id") ?: return Json.error(400, "zone_or_output_id is required")
        val command = request.str("command") ?: ""
        val allowed = listOf("play", "pause", "playpause", "stop", "previous", "next")
        if (command !in allowed) {
            return Json.error(400, "invalid command, allowed: ${allowed.joinToString(", ")}")
        }
        roon.control(id, command)
        return Json.ok()
    }

    private fun seek(request: Request): Response {
        val id = request.str("zone_or_output_id") ?: return Json.error(400, "zone_or_output_id is required")
        val seconds = request.int("seconds") ?: return Json.error(400, "seconds is required")
        val how = request.str("how")?.takeIf { it == "relative" || it == "absolute" } ?: "absolute"
        roon.seek(id, how, seconds)
        return Json.ok()
    }

    /**
     * Volume is per OUTPUT, not per zone. A grouped zone has one output per
     * device, each with its own type, range and step, so a zone-level change
     * drives all of them.
     */
    private fun volume(request: Request): Response {
        val body = Json.body(request)

        val outputId = body.str("output_id").takeIf { it.isNotEmpty() }
        val targets = if (outputId != null) {
            listOfNotNull(roon.outputs().firstOrNull { it.outputId == outputId })
        } else {
            val zone = roon.zone(body.str("zone_or_output_id").takeIf { it.isNotEmpty() })
                ?: return Json.error(400, "output_id or zone_or_output_id is required")
            zone.volumeOutputs
        }
        if (targets.isEmpty()) return Json.error(400, "that zone has no volume control")

        // Mute carries no value, and this route used to demand one before it
        // read anything else. The page's own mute button has been sending
        // {"mute": true} and getting back "value is required" for its trouble,
        // so muting from the front-end has never worked. Answered first, and
        // before the value check, for exactly that reason.
        if (body.has("mute")) {
            val how = if (body.optBoolean("mute", false)) "mute" else "unmute"
            for (out in targets) {
                if (out.volume == null) continue
                roon.mute(out.outputId, how)
            }
            return Json.ok()
        }

        // An output whose volume is incremental has no scale to step through,
        // so the page's − and + send {"relative": ±1} rather than a value.
        // This route demanded a value and answered 400, so on those
        // amplifiers the buttons did nothing.
        if (!body.has("value") && body.has("relative")) {
            val step = body.optDouble("relative", Double.NaN)
            if (step.isNaN() || step == 0.0) return Json.error(400, "relative must be a non-zero number")
            for (out in targets) {
                if (out.volume == null) continue
                roon.changeVolume(out.outputId, "relative", step)
            }
            return Json.ok()
        }

        val how = body.str("how").ifEmpty { "absolute" }
        val value = body.optDouble("value", Double.NaN)
        if (value.isNaN()) return Json.error(400, "value, relative or mute is required")

        for (out in targets) {
            val vol = out.volume ?: continue
            // An incremental control has no scale to step through: Roon's own
            // guidance is that only a relative +1/-1 is legal.
            if (vol.isIncremental) {
                roon.changeVolume(out.outputId, "relative", if (value >= 0) 1.0 else -1.0)
            } else {
                roon.changeVolume(out.outputId, how, value)
            }
        }
        return Json.ok()
    }

    private fun zoneSettings(request: Request): Response {
        val body = Json.body(request)
        val id = body.str("zone_or_output_id").takeIf { it.isNotEmpty() }
            ?: return Json.error(400, "zone_or_output_id is required")
        val patch = JSONObject()
        if (body.has("shuffle")) patch.put("shuffle", body.optBoolean("shuffle"))
        if (body.has("auto_radio")) patch.put("auto_radio", body.optBoolean("auto_radio"))
        if (body.has("loop")) {
            val loop = body.str("loop")
            if (loop !in ZoneSettings.LOOP_MODES) {
                return Json.error(400, "loop must be one of ${ZoneSettings.LOOP_MODES.joinToString(", ")}")
            }
            patch.put("loop", loop)
        }
        if (patch.length() == 0) return Json.error(400, "nothing to change")
        roon.changeSettings(id, patch)

        // Roon Radio and Random Album Radio both answer the question "what
        // plays when the queue runs out", so both on means two things racing to
        // fill the same queue. Roon Radio wins because the user just asked for
        // it; ours stands down and the client says so rather than leaving a
        // switch lit that is no longer doing anything.
        var stoodDown = false
        if (patch.optBoolean("auto_radio", false) && app.radio.isEnabled(id)) {
            app.radio.setEnabled(id, false)
            stoodDown = true
        }
        return Json.ok(
            JSONObject().put("random_album_radio_stands_down", stoodDown)
                .put(
                    "radios",
                    radiosJson(id, roonOverride = if (patch.has("auto_radio")) patch.optBoolean("auto_radio") else null)
                )
        )
    }

    /**
     * Both radios for a zone: ours and Roon's. Roon's is read off the zone feed,
     * which only catches up some time after change_settings calls back — so a
     * route that has just changed it passes what it asked for, rather than the
     * value from before the change.
     */
    private fun radiosJson(zoneId: String, roonOverride: Boolean? = null): JSONObject {
        val roonOn = roonOverride
            ?: (roon.zones().firstOrNull { it.zoneId == zoneId }?.settings?.autoRadio == true)
        return JSONObject().put("own", app.radio.isEnabled(zoneId)).put("roon", roonOn)
    }

    private fun muteAll(request: Request): Response {
        val how = request.str("how")?.takeIf { it == "mute" || it == "unmute" } ?: "mute"
        var touched = 0
        for (output in roon.outputs()) {
            if (output.volume == null) continue
            runCatching { roon.mute(output.outputId, how); touched++ }
        }
        return Json.ok(JSONObject().put("outputs", touched))
    }

    private fun groupOutputs(request: Request, group: Boolean): Response {
        val arr = Json.body(request).optJSONArray("output_ids")
            ?: return Json.error(400, "output_ids array is required")
        val ids = (0 until arr.length()).mapNotNull { arr.str(it).takeIf(String::isNotEmpty) }
        if (ids.size < (if (group) 2 else 1)) {
            return Json.error(400, if (group) "grouping needs at least two outputs" else "no outputs given")
        }
        if (group) roon.groupOutputs(ids) else roon.ungroupOutputs(ids)
        return Json.ok()
    }

    /**
     * Move playback to another zone. from_zone and to_zone are the page's
     * names — this read `from` and `to`, which nothing sends, so every transfer
     * was refused with 400 and the music stayed in the room it was in.
     */
    private fun transferZone(request: Request): Response {
        val body = Json.body(request)
        val from = body.strOrNull("from_zone")
            ?: return Json.error(400, "from_zone and to_zone are required")
        val to = body.strOrNull("to_zone")
            ?: return Json.error(400, "from_zone and to_zone are required")
        // Rouen's answer too: moving a zone onto itself is nothing to ask Roon.
        if (from == to) return Json.ok(JSONObject().put("noop", true))
        roon.transferZone(from, to)
        return Json.ok()
    }

    private fun playFromHere(request: Request): Response {
        val body = Json.body(request)
        val zone = body.str("zone_or_output_id").takeIf { it.isNotEmpty() }
            ?: return Json.error(400, "zone_or_output_id is required")
        val item = body.optLong("queue_item_id", -1)
        if (item < 0) return Json.error(400, "queue_item_id is required")
        roon.playFromHere(zone, item)
        return Json.ok()
    }

    private fun outputControl(request: Request, standby: Boolean): Response {
        val body = Json.body(request)
        val outputId = body.str("output_id").takeIf { it.isNotEmpty() }
            ?: return Json.error(400, "output_id is required")
        val controlKey = body.str("control_key").takeIf { it.isNotEmpty() }
            ?: return Json.error(400, "control_key is required")
        if (standby) roon.standby(outputId, controlKey) else roon.convenienceSwitch(outputId, controlKey)
        return Json.ok()
    }

    // ---------------------------------------------------------- discovery

    private fun filterOf(request: Request): AlbumFilter? = AlbumFilter.parse(
        request.str("filter_type"), request.str("filter_value"), request.str("filter_parent")
    )

    private fun randomAlbums(request: Request): Response {
        val count = (request.int("count") ?: RANDOM_DEFAULT).coerceIn(1, 96)
        val filter = filterOf(request)
        // Optional. Present, the same seed gives the same albums — which is how
        // Home's row shows one set for the whole day and still costs nothing to
        // ask for again. Absent, every call is a fresh draw.
        val seed = request.int("seed")

        // Unfiltered picks come straight from the snapshot: the same shape the
        // browse path returns, with full-library offsets, so open and play work
        // unchanged. That removes a browse walk plus one load per tile from
        // every Home visit.
        if (filter == null || filter.type == AlbumFilter.DECADE) {
            val pool = if (filter == null) index.albums else {
                val decade = filter.value.removeSuffix("s").toIntOrNull()
                    ?: return Json.error(400, "unrecognised decade ${filter.value}")
                // One read of the years table, not a query per album.
                val years = store.albumYears()
                index.albums.filter {
                    val y = years[it.key]
                    y != null && y >= decade && y < decade + 10
                }
            }
            if (pool.isEmpty() && !index.isBuilt) {
                return Json.error(503, "The library is still being scanned")
            }
            val picked = view.sample(pool, count, seed)
            return Json.obj(
                JSONObject()
                    .put("albums", Json.albums(picked))
                    .put("total", pool.size)
                    .put("filtered", filter != null)
            )
        }

        // A genre or tag has its own Roon list with its own offsets, so it has
        // to be walked live.
        val picked = roon.tree.withSession { key ->
            val nav = roon.tree.navigateToAlbumList(key, filter)
            if (nav.total == 0) return@withSession emptyList<JSONObject>() to 0
            val want = minOf(count, nav.total)
            val offsets = LinkedHashSet<Int>()
            while (offsets.size < want) offsets += (0 until nav.total).random()
            val out = ArrayList<JSONObject>(want)
            for (off in offsets) {
                val item = runCatching { roon.tree.load(nav.hierarchy, key, off, 1).items.firstOrNull() }
                    .getOrNull() ?: continue
                if (item.hint == "header") continue
                out += Json.album(AlbumRecord(off, item.title, item.subtitle, item.imageKey))
            }
            out to nav.total
        }
        return Json.obj(
            JSONObject()
                .put("albums", Json.arrayOf(picked.first))
                .put("total", picked.second)
                .put("filtered", true)
        )
    }

    private fun libraryAlbums(request: Request): Response {
        if (!index.isBuilt) return Json.error(503, "The library index is still building")
        // Focus sends a facet once per value; every value counts.
        val q = view.sanitize(
            request.str("sort"), request.str("dir"), request.str("prefix"),
            request.str("played"), null, null, request.str("seed"),
            LibraryView.FACET_IDS.associateWith { request.queryAll[it] ?: emptyList() }
        )
        val all = view.select(q)
        val offset = (request.int("offset") ?: 0).coerceIn(0, all.size)
        val count = (request.int("count") ?: 60).coerceIn(1, 200)
        val page = all.subList(offset, minOf(offset + count, all.size))
        return Json.obj(
            JSONObject()
                .put("albums", Json.albums(page))
                .put("offset", offset)
                .put("total", all.size)
        )
    }

    /**
     * Which focus values actually exist, with counts, so the sheet never offers
     * a facet that would return nothing. Counted through the same tables the
     * filter selects through: a facet that counts one way and selects another is
     * worse than either being wrong alone, because the number promises something
     * the list then fails to deliver.
     */
    private fun libraryFacets(): Response {
        if (!index.isBuilt) return Json.error(503, "The library index is still building")
        return Json.obj(view.facets())
    }

    // ---------------------------------------------------------------- album

    private fun expectOf(request: Request) =
        Albums.Expect(request.str("title"), request.str("subtitle") ?: request.str("artist"))

    private fun album(request: Request): Response {
        val offset = request.int("offset") ?: return Json.error(400, "offset is required")
        val r = app.albums.open(offset, null, null, filterOf(request), expectOf(request))
        return Json.obj(albumViewJson(r))
    }

    private fun albumViewJson(r: Albums.AlbumView): JSONObject = JSONObject()
        .put(
            "album",
            JSONObject()
                .put("title", r.title)
                .put("subtitle", r.subtitle)
                .put("image_key", r.imageKey ?: JSONObject.NULL)
                .put("source", JSONObject.NULL)
        )
        .put(
            "tracks",
            Json.arrayOf(r.tracks.map { JSONObject().put("title", it.title).put("subtitle", it.subtitle) })
        )
        .put(
            "actions",
            Json.arrayOf(r.actions.map { JSONObject().put("kind", it.kind).put("title", it.title) })
        )
        .put("offset", r.offset)
        .put("artists", artistNames(r.subtitle))
        // The ⋯ menu's "Listen later / Remove from Listen later".
        .put("listen_later", app.listenLater.has(r.title, r.subtitle))
        .put("library_moved", r.libraryMoved)
        .put("partial", r.partial)
        .put("declared_tracks", r.declaredTracks ?: JSONObject.NULL)

    /**
     * The facts about an album that Roon does not carry — release year, blurb,
     * Pitchfork score.
     *
     * `fast=1` answers the same shape out of what the app already holds and
     * never opens a socket. The share card is the caller, and it wants exactly
     * one field of this — the year — with a spinner in front of the user while
     * it waits. The full answer is a chain of five requests to MusicBrainz and
     * Wikipedia behind a one-per-second rate gate, plus a Pitchfork review
     * page: seconds, on a phone, for a four-digit number the app has usually
     * learned already. The album card still asks the slow way, because a blurb
     * that has never been fetched is the thing it is there to show.
     */
    private fun albumExtras(request: Request): Response {
        val title = request.str("title") ?: return Json.error(400, "title is required")
        val artist = request.str("artist") ?: ""
        val fast = request.bool("fast") == true
        val extras =
            if (fast) app.metadata.cachedExtras(title, artist)
            else app.metadata.extras(title, artist)

        // A year learned here is worth keeping: it feeds the Decade filter and
        // the year sort, which otherwise only fill in as albums are played.
        val record = index.relocate(title, artist)
        if (record != null && extras?.year != null && store.albumYear(record.key) != extras.year) {
            runCatching {
                store.putAlbumYear(record.key, extras.year, YearSource.MUSICBRAINZ)
                app.yearLearned()
            }
        }
        // The store is the fast path's real source: every album whose card has
        // been opened has left its year here, which is why `fast=1` usually
        // has an answer at all.
        val year = extras?.year ?: record?.let { view.albumYearOf(it) }

        // The Pitchfork score, which the album card draws as a chip beside the
        // year (plus a BNM badge). It reads extras.album.score and
        // extras.album.isBestNewMusic — fields this never sent, so the chip
        // never appeared even though the reviews screen had the data.
        //
        // A miss is the normal case: most records were never reviewed, and the
        // card simply shows no chip.
        val review =
            if (fast) app.pitchfork.cachedReviewFor(title, artist)
            else runCatching { app.pitchfork.reviewFor(title, artist) }.getOrNull()

        fun bio(b: Metadata.Bio?, withReview: Boolean = false): Any {
            if (b == null && !(withReview && review != null)) return JSONObject.NULL
            val o = JSONObject()
                .put("description", b?.description ?: "")
                .put("source", b?.source ?: JSONObject.NULL)
                // WHOSE words the description is, which is a different fact
                // from where `source`/`url` link to: Pitchfork takes the link
                // below when it reviewed the record, and without this the
                // share card credited Wikipedia's prose to Pitchfork.
                .put("description_source", b?.takeIf { it.description.isNotBlank() }?.source ?: JSONObject.NULL)
                .put("url", b?.url ?: JSONObject.NULL)
                // The lite build has no label chain, and the album card reads
                // this field. Null keeps the row hidden rather than blank.
                .put("label", JSONObject.NULL)
            if (!withReview || review == null) return o
            // Pitchfork wins the source link when it has the record: the card
            // then offers "Read the full review on Pitchfork", which is the
            // whole point of carrying a score with no review text.
            return o
                .put("score", review.score ?: JSONObject.NULL)
                .put("isBestNewMusic", review.isBestNewMusic)
                .put("source", "Pitchfork")
                .put("url", review.url)
        }

        // Where to hear it and where to read about it, under the share card.
        // Pure string building, so it rides along on the answer the card
        // already waits for. The article and review found above are handed
        // over, so those chips land on the actual page rather than a search.
        val links = JSONObject()
            .put(
                "services",
                Json.arrayOf(
                    ShareLinks.serviceLinks(
                        artist, title,
                        locale = ShareLinks.localeFromAcceptLanguage(request.headers["accept-language"]),
                        enabled = settings.shareServices()
                    ).map { it.toJson() }
                )
            )
            .put(
                "reviews",
                Json.arrayOf(
                    ShareLinks.reviewLinks(
                        artist, title,
                        enabled = settings.shareReviews(),
                        wikipediaUrl = extras?.album?.takeIf { it.source == "Wikipedia" }?.url,
                        pitchforkUrl = review?.url,
                        wikipediaArtistUrl = extras?.artist?.takeIf { it.source == "Wikipedia" }?.url
                    ).map { it.toJson() }
                )
            )

        return Json.obj(
            JSONObject()
                .put("links", links)
                .put("year", year ?: JSONObject.NULL)
                // To the day where MusicBrainz knows it (Rouen v1.8.62); the
                // album view prints it in the device's own date format.
                .put("release_date", extras?.releaseDate ?: year?.toString() ?: JSONObject.NULL)
                .put("album", bio(extras?.album, withReview = true))
                .put("artist", bio(extras?.artist))
        )
    }

    /**
     * The release date the album view shows, re-read when the live `dates`
     * revision moves — from what is already known, never a lookup: the full
     * lookup is /api/album/extras, which the view has already started.
     */
    private fun releaseDate(request: Request): Response {
        val title = request.str("title") ?: return Json.error(400, "title is required")
        val artist = request.str("artist") ?: ""
        val known = app.metadata.cachedExtras(title, artist)
        val stored = index.relocate(title, artist)?.let { view.albumYearOf(it) }
        val date = known?.releaseDate ?: known?.year?.toString() ?: stored?.toString()
        return Json.obj(JSONObject().put("release_date", date ?: JSONObject.NULL))
    }

    /** Match what a zone is playing back to a library tile, so it can be opened. */
    private fun nowPlayingAlbum(request: Request): Response {
        val zone = roon.zone(request.str("zone"))
            ?: return Json.obj(JSONObject().put("album", JSONObject.NULL))
        val np = zone.nowPlaying
            ?: return Json.obj(JSONObject().put("album", JSONObject.NULL))
        val hit = index.relocate(np.line3, np.line2) ?: index.relocate(np.line3, null)
        return Json.obj(
            JSONObject().put("album", hit?.let { Json.album(it) } ?: JSONObject.NULL)
        )
    }

    // -------------------------------------------------------------- playing

    private fun playAlbum(request: Request, defaultKind: String): Response {
        val body = Json.body(request)
        val offset = body.optInt("offset", -1)
        if (offset < 0) return Json.error(400, "offset is required")
        val zone = body.str("zone_or_output_id").takeIf { it.isNotEmpty() }
            ?: return Json.error(400, "zone_or_output_id is required")
        val kind = body.str("kind").takeIf { it.isNotEmpty() } ?: defaultKind
        val r = app.albums.open(
            offset, zone, kind,
            AlbumFilter.parse(
                body.str("filter_type"), body.str("filter_value"),
                body.str("filter_parent")
            ),
            Albums.Expect(
                body.str("title").takeIf { it.isNotEmpty() },
                body.str("subtitle").takeIf { it.isNotEmpty() }
            )
        )
        return Json.ok(
            JSONObject().put("invoked", r.invoked ?: JSONObject.NULL).put("offset", r.offset)
        )
    }

    /**
     * Play, queue or play-next one track of an album.
     *
     * The field names are the page's, as every caller in app.js sends them:
     * `track` is the track's INDEX, `title` is the TRACK's title, and the album
     * it is expected to be in is album_title / album_subtitle. This read
     * track_index and track_title, with the album as title/subtitle — the
     * playlist route's vocabulary — so every tap on a track was answered 400
     * "track_index is required", and the test written from the same reading
     * passed throughout.
     */
    private fun playTrack(request: Request): Response {
        val body = Json.body(request)
        val offset = body.optInt("offset", -1)
        if (offset < 0) return Json.error(400, "offset is required")
        val zone = body.str("zone_or_output_id").takeIf { it.isNotEmpty() }
            ?: return Json.error(400, "zone_or_output_id is required")
        val trackIndex = body.optInt("track", -1)
        if (trackIndex < 0) return Json.error(400, "track index is required")
        val kind = body.str("kind").takeIf { it.isNotEmpty() } ?: "play_now"
        val (invoked, track) = app.albums.invokeTrack(
            offset, trackIndex, body.str("title").takeIf { it.isNotEmpty() },
            zone, kind,
            AlbumFilter.parse(
                body.str("filter_type"), body.str("filter_value"),
                body.str("filter_parent")
            ),
            Albums.Expect(
                body.str("album_title").takeIf { it.isNotEmpty() },
                body.str("album_subtitle").takeIf { it.isNotEmpty() }
            )
        )
        // `action` is what the page toasts; `invoked` is kept for anything
        // that already reads it.
        return Json.ok(
            JSONObject().put("action", invoked).put("invoked", invoked).put("track", track)
        )
    }

    /**
     * Queue several albums back to back.
     *
     * The field is `items` — {offset, title, subtitle} each — so the
     * stale-offset defence covers a multi-selection too; bare `offsets` is
     * accepted for older callers. Reading `albums` here, which nothing sends,
     * is what made every multi-select queue fail with "albums array is
     * required".
     *
     * The first album takes the caller's kind (usually play_now) and the rest
     * are queued, because "play now" for each would leave only the last one
     * playing. A partial result is a SUCCESS: the first album is already
     * playing and everything that queued is in the queue, so the counts travel
     * back rather than an error that throws all of it away.
     */
    private fun playMulti(request: Request): Response {
        val body = Json.body(request)
        val zone = body.strOrNull("zone_or_output_id")
            ?: return Json.error(400, "zone_or_output_id required")
        val kind = body.strOrNull("kind") ?: return Json.error(400, "kind required")

        val items = body.optJSONArray("items")
        val list = ArrayList<Pair<Int, Albums.Expect>>()
        if (items != null) {
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val offset = it.optInt("offset", -1)
                if (offset < 0) continue
                list += offset to Albums.Expect(
                    it.strOrNull("title"),
                    it.strOrNull("subtitle")
                )
            }
        } else {
            body.optJSONArray("offsets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val offset = arr.optInt(i, -1)
                    if (offset >= 0) list += offset to Albums.Expect(null, null)
                }
            }
        }
        if (list.isEmpty()) return Json.error(400, "offsets required")
        if (list.size > PLAY_MULTI_MAX) {
            return Json.error(400, "at most $PLAY_MULTI_MAX albums at a time")
        }

        // One fill per zone. Two overlapping runs interleave their albums, and
        // the second would restart a queue the first is still building.
        if (!fillingZones.add(zone)) {
            return Json.error(
                409, "Still filling this zone's queue — let that finish before starting another"
            )
        }

        val filter = AlbumFilter.parse(
            body.strOrNull("filter_type"), body.strOrNull("filter_value"),
            body.strOrNull("filter_parent")
        )

        try {
            // Play next (Rouen v1.8.80): EVERY album is Add Next. Queueing the
            // rest behind the first put them at the far END of the queue, after
            // everything already waiting. Each Add Next lands in front of the
            // one before it, so they go last album first — sendOrderFor, the one
            // place that order is decided — and one at a time, for the reasons
            // below. One refusal does not abandon the rest.
            if (kind == "play_next") {
                var failed = 0
                var firstError: Throwable? = null
                for ((offset, expect) in QueueHistory.sendOrderFor(kind, list)) {
                    runCatching { app.albums.open(offset, zone, "play_next", filter, expect) }
                        .exceptionOrNull()?.let { e ->
                            failed++
                            if (firstError == null) firstError = e
                        }
                }
                // Nothing went in: that is an error, the first one's.
                firstError?.let { if (failed == list.size) throw it }
                return Json.ok(
                    JSONObject()
                        .put("queued", list.size - failed)
                        .put("failed", failed)
                        .put("total", list.size)
                        .put("first_error", firstError?.let { it.message ?: it.toString() } ?: JSONObject.NULL)
                )
            }

            app.albums.open(list[0].first, zone, kind, filter, list[0].second)

            // Strictly one at a time, and NOT because it is simpler.
            //
            // The queue is ordered, and the order is the user's: they picked
            // these albums in a sequence and expect to hear them in it.
            // Queueing them concurrently makes the order whichever thread wins,
            // which is the same "takes the decision away from the person
            // holding the phone" failure the radio gate exists to prevent.
            //
            // It is also wrong about the transport. Every open is several
            // browse round-trips over the ONE Roon socket, on browse sessions
            // that carry position state between calls — so parallel walks
            // interleave on a stateful protocol rather than going faster.
            //
            // A slow, correct queue beats a fast, shuffled one. The bound on
            // how long this takes is PLAY_MULTI_MAX, not a thread count.
            var failed = 0
            var firstError: String? = null
            for ((offset, expect) in list.drop(1)) {
                runCatching { app.albums.open(offset, zone, "queue", filter, expect) }
                    .exceptionOrNull()?.let { e ->
                        failed++
                        if (firstError == null) firstError = e.message ?: e.toString()
                    }
            }

            return Json.ok(
                JSONObject()
                    .put("queued", list.size - failed)
                    .put("failed", failed)
                    .put("total", list.size)
                    .put("first_error", firstError ?: JSONObject.NULL)
            )
        } finally {
            fillingZones.remove(zone)
        }
    }

    /**
     * One tap, one album. Shared with the widget and the Quick Settings tile,
     * which reach the same action without going through HTTP at all.
     */
    /**
     * Random Album, from the Home strip: `{zone}` in, `{ok, album, artist}` out,
     * which is what the page toasts ("Playing: <album>").
     */
    private fun playRandomFromHome(request: Request): Response {
        val zone = Json.body(request).strOrNull("zone") ?: return Json.error(400, "zone required")
        return app.playRandomAlbum(zone).fold(
            onSuccess = { Json.ok(JSONObject().put("album", it.title).put("artist", it.subtitle)) },
            onFailure = { Json.error(503, it.message ?: "Could not start an album") }
        )
    }

    private fun shortcutPlay(request: Request): Response =
        app.playRandomAlbum(request.str("zone")).fold(
            onSuccess = { Json.ok(JSONObject().put("album", Json.album(it))) },
            onFailure = { Json.error(503, it.message ?: "Could not start an album") }
        )

    // --------------------------------------------------------------- search

    /**
     * Library search, in the shape the search sheet reads.
     *
     * The album hits are `results`. This returned them as `albums`, which
     * nothing reads — so the sheet's album section was permanently empty (the
     * artist chips still appeared, because `artists` happened to match, which
     * is what made it look like search half-worked), and tapping the album name
     * on the now-playing screen — which searches for the album to open it —
     * always ended at "Album not yet indexed".
     *
     * `building` and `progress` matter just as much: while the first index is
     * being walked the sheet shows "Building index… n%" and retries. Without
     * them it silently reported no matches for a library it had not read yet.
     */
    private fun search(request: Request): Response {
        val q = request.str("q") ?: ""
        val limit = (request.int("limit") ?: 40).coerceIn(1, 200)
        val base = JSONObject().put("query", q).put("indexed", index.albums.size)

        if (index.isBuilding && !index.isBuilt) {
            return Json.obj(
                base.put("building", true).put("progress", index.progress)
                    .put("results", JSONArray()).put("artists", JSONArray())
                    .put("labels", JSONArray())
            )
        }
        if (q.isBlank()) {
            return Json.obj(
                base.put("count", 0).put("results", JSONArray())
                    .put("artists", JSONArray()).put("labels", JSONArray())
            )
        }
        val hits = Search.albums(index.albums, q, limit)
        val artists = Search.artists(index.albums, q)
        return Json.obj(
            base
                .put("count", hits.size)
                .put("results", Json.arrayOf(hits.map { Json.album(it.album, JSONObject().put("score", it.score)) }))
                .put(
                    "artists",
                    Json.arrayOf(artists.map {
                        JSONObject().put("name", it.name).put("albumCount", it.albumCount)
                    })
                )
                // Labels are not in this build; an empty array keeps the search
                // sheet's label section collapsed rather than erroring.
                .put("labels", JSONArray())
        )
    }

    /**
     * The library's artists, sorted and paged.
     *
     * Derived on every request rather than cached, because it is a walk over
     * an index already in memory and the alternative is a second thing that
     * can go stale when the library rebuilds. A library of ten thousand albums
     * is a few milliseconds of work.
     *
     * `seed` only means anything to the random sort, and the page owns it: the
     * reshuffle button changes the number, and the same number has to give the
     * same wall or paging would show one artist twice and miss another.
     */
    private fun artists(request: Request): Response {
        val sort = request.str("sort")?.takeIf { it.isNotEmpty() } ?: Artists.AZ
        val seed = request.int("seed") ?: 1
        val offset = (request.int("offset") ?: 0).coerceAtLeast(0)
        val limit = (request.int("limit") ?: 120).coerceIn(1, 500)

        val all = Artists.sorted(Artists.of(index.albums), sort, seed)
        // The same 503 the random row already answers with while the snapshot
        // is still being scanned. Without it the page was told, truthfully and
        // uselessly, that a library it had not finished reading has no artists
        // in it — and painted "No artists." over the row for the rest of the
        // session. Reported from a phone: the Home row said that while the
        // artists screen, opened later, was full.
        if (all.isEmpty() && !index.isBuilt) {
            return Json.error(503, "The library is still being scanned")
        }
        val page = all.drop(offset).take(limit)
        return Json.obj(
            JSONObject()
                .put("total", all.size)
                .put("offset", offset)
                .put("sort", sort)
                .put("seed", seed)
                .put("artists", Json.arrayOf(page.map { a ->
                    JSONObject()
                        .put("name", a.name)
                        .put("albums", a.albums)
                        .put("image_key", a.imageKey ?: JSONObject.NULL)
                }))
        )
    }

    private fun artistAlbums(request: Request): Response {
        val artist = request.str("artist") ?: return Json.error(400, "artist is required")
        val want = Normalize.text(artist)
        if (want.isEmpty() || index.albums.isEmpty()) {
            return Json.obj(
                JSONObject().put("artist", artist).put("primary", JSONArray()).put("featured", JSONArray())
            )
        }
        val primary = ArrayList<AlbumRecord>()
        val featured = ArrayList<AlbumRecord>()
        for (al in index.albums) {
            when {
                al.nArtist == want -> primary += al
                al.artistNames.any { it.normalized == want } -> featured += al
            }
        }
        primary.sortBy { it.sortTitle }
        featured.sortBy { it.sortTitle }
        return Json.obj(
            JSONObject()
                .put("artist", artist)
                .put("primary", Json.albums(primary))
                .put("featured", Json.albums(featured))
        )
    }

    /**
     * The artist bio, in the shape the artist view reads.
     *
     * That shape is `text`, NOT `description` — and this sent `description`,
     * so the view's `if (!b || !b.text) return` dropped every bio silently and
     * no artist page ever showed one. The album bio on the same screen really
     * does use `description` (see albumExtras): two names for one idea, which
     * is the page's inconsistency and not something to tidy away here. The
     * server's job is to answer what each caller asks for.
     *
     * `album` pins the identity. The client sends one of the artist's own album
     * titles for exactly that reason, and ignoring it was how a search for a
     * common name could return a stranger's article.
     */
    private fun artistBio(request: Request): Response {
        val artist = request.str("artist") ?: return Json.error(400, "artist is required")
        val album = request.str("album") ?: ""
        val bio = app.metadata.wikipediaArtist(artist, album)
            ?: return Json.obj(JSONObject().put("bio", JSONObject.NULL))
        return Json.obj(
            JSONObject().put(
                "bio",
                JSONObject()
                    .put("text", bio.description)
                    .put("source", bio.source)
                    .put("url", bio.url ?: JSONObject.NULL)
                    .put("image", bio.image ?: JSONObject.NULL)
            )
        )
    }

    // -------------------------------------------------------------- filters

    private fun genres(): Response {
        if (!roon.isPaired) return Json.error(503, "Not paired with a Roon Core")
        val items = roon.tree.withSession { key ->
            roon.tree.browse("genres", key, popAll = true)
            roon.tree.loadLevel("genres", key, 1000).items
        }
        val genres = items.filter { it.hint != "header" }.map {
            JSONObject().put("title", it.title).put("subtitle", it.subtitle)
        }
        return Json.obj(JSONObject().put("genres", Json.arrayOf(genres)))
    }

    private fun decades(): Response {
        if (!index.isBuilt) return Json.error(503, "The library index is still building")
        val decades = view.decades().map { (decade, n) ->
            JSONObject()
                .put("title", "${decade}s")
                .put("subtitle", "$n album" + if (n == 1) "" else "s")
        }
        return Json.obj(JSONObject().put("decades", Json.arrayOf(decades)))
    }

    private fun tags(): Response {
        if (!roon.isPaired) return Json.obj(JSONObject().put("tags", JSONArray()))
        val items = try {
            roon.tree.withSession { key ->
                roon.tree.browse("browse", key, popAll = true)
                val lib = roon.tree.findItemByTitle("browse", key, "Library", 50)
                    ?: return@withSession emptyList()
                roon.tree.browse("browse", key, itemKey = lib.itemKey)
                val tagsNode = roon.tree.findItemByTitle("browse", key, "Tags", 100)
                    ?: return@withSession emptyList()
                roon.tree.browse("browse", key, itemKey = tagsNode.itemKey)
                roon.tree.loadLevel("browse", key, 1000).items
            }
        } catch (e: Exception) {
            // A library with no tags has no Tags node at all — an empty list,
            // not an error the user has to dismiss.
            Log.d(TAG, "no tags: ${e.message}")
            emptyList()
        }
        return Json.obj(
            JSONObject().put(
                "tags",
                Json.arrayOf(items.filter { it.hint != "header" }
                    .map { JSONObject().put("title", it.title).put("subtitle", it.subtitle) })
            )
        )
    }

    // ----------------------------------------------------------------- home

    private fun homeHistory(request: Request): Response {
        val count = (request.int("count") ?: HISTORY_MAX_TILES).coerceIn(1, HISTORY_MAX_TILES)
        return Json.obj(
            JSONObject()
                .put("albums", Json.albums(view.history(HISTORY_DAYS, count)))
                .put("days", HISTORY_DAYS)
        )
    }

    /**
     * `day` is Album of the day's day (00:01 to 00:01). The page keeps a copy
     * of the strip for an instant cold open and paints it only on the day it
     * was chosen for — without this it could never tell, and never painted it.
     */
    private fun albumOfTheDay(): Response {
        val day = view.aotdDay()
        val album = view.albumOfTheDay()
            ?: return Json.obj(JSONObject().put("album", JSONObject.NULL).put("day", day))
        // A suggestion you have already taken is not a suggestion.
        if (view.playedToday(album)) {
            return Json.obj(JSONObject().put("album", JSONObject.NULL).put("played", true).put("day", day))
        }
        return Json.obj(JSONObject().put("album", Json.album(album)).put("played", false).put("day", day))
    }

    /**
     * The Home genre row. Roon's genre tree is a flat list plus one deep
     * "Pop/Rock" node that holds most of a typical library, so the row shows the
     * top-level genres by album count and lets the sheet drill in.
     */
    private fun genreGroups(): Response {
        if (!roon.isPaired) return Json.obj(JSONObject().put("groups", JSONArray()))
        val items = try {
            roon.tree.withSession { key ->
                roon.tree.browse("genres", key, popAll = true)
                roon.tree.loadLevel("genres", key, 1000).items
            }
        } catch (e: Exception) {
            Log.d(TAG, "genre groups unavailable: ${e.message}")
            emptyList()
        }
        val counted = items.filter { it.hint != "header" }.map {
            it to (GENRE_ALBUM_COUNT
                .find(it.subtitle)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0)
        }.sortedByDescending { it.second }
        val groups = counted.take(24).map { (item, n) ->
            JSONObject()
                .put("title", item.title)
                .put("subtitle", item.subtitle)
                .put("count", n)
                .put("image_key", item.imageKey ?: JSONObject.NULL)
        }
        return Json.obj(JSONObject().put("groups", Json.arrayOf(groups)))
    }

    // ---------------------------------------------------------------- radio

    private fun radio(request: Request): Response {
        if (request.method == "POST") {
            val body = Json.body(request)
            val zone = body.str("zone").takeIf { it.isNotEmpty() }
                ?: return Json.error(400, "zone is required")
            val enabled = body.optBoolean("enabled", false)
            app.radio.setEnabled(zone, enabled)

            // The other half of the same exclusion (see zoneSettings). Enabling
            // ours turns Roon's own radio off for the zone, so whichever the
            // user reaches for last is the one that runs.
            var roonRadioOff = false
            if (enabled && roon.zones().firstOrNull { it.zoneId == zone }?.settings?.autoRadio == true) {
                runCatching {
                    roon.changeSettings(zone, JSONObject().put("auto_radio", false))
                    roonRadioOff = true
                }
            }
            return Json.ok(
                JSONObject().put("enabled", enabled).put("roon_radio_off", roonRadioOff)
                    // Both switches, as they now stand — the page paints them
                    // from this and treats an answer without it as a failure.
                    .put("radios", radiosJson(zone, roonOverride = if (roonRadioOff) false else null))
            )
        }
        val zone = request.str("zone")
        return Json.obj(
            JSONObject()
                .put("enabled", app.radio.isEnabled(zone))
                .put("zones", Json.strings(app.radio.enabledZones()))
        )
    }

    // ----------------------------------------------------------- smart picks

    /**
     * A short list of albums the user probably has not heard lately, refreshed
     * daily and stable within the day, minus anything they have blocked.
     *
     * The list is `picks`, and each entry is a PICK — not an album row. This
     * sent `albums` full of album objects, so the Home row and the Smart Picks
     * screen both read `j.picks` as empty and showed their "nothing yet" state
     * on a library that had plenty to offer.
     *
     * Upstream picks come from outside the library and may or may not be in
     * Roon; here they are drawn FROM the library, so every one has an offset
     * and is playable. That is why `service` and `album_id` are empty and
     * `added` is null: there is no streaming account to add anything to, and
     * null (not false) is what tells the card to leave the button alone rather
     * than claim the album is not added.
     */
    private fun smartPicks(request: Request): Response {
        val day = java.time.LocalDate.now().toString()
        val base = JSONObject()
            .put("day", day)
            .put("auto_add", false)
            .put("dest", settings.smartPicksDest())
            .put("hour", settings.smartPicksHour())
            // No streaming account to favourite a pick into — see the note on
            // the settings endpoint.
            .put("service_ready", false)

        if (!settings.smartPicksEnabled()) {
            return Json.obj(
                base.put("enabled", false).put("building", false).put("picks", JSONArray())
            )
        }
        // Nothing to choose from yet: say "building" so the screen waits and
        // retries instead of reporting that there is nothing to suggest.
        if (!index.isBuilt) {
            return Json.obj(
                base.put("enabled", true).put("building", true).put("picks", JSONArray())
            )
        }

        val count = (request.int("count") ?: 12).coerceIn(1, 48)
        val pool = smartPickPool()
        val picks = smartPicksFor(day, pool, count)
        sendPicksToLaterIfDue(day, pool)
        return Json.obj(
            base
                .put("enabled", true)
                .put("building", false)
                .put("total", pool.size)
                .put(
                    "picks",
                    Json.arrayOf(
                        picks.map { album ->
                            JSONObject()
                                .put("kind", "library")
                                .put("artist", album.subtitle)
                                .put("album", album.title)
                                .put("album_id", "")
                                .put("service", "")
                                // The card renders `image` as a URL — upstream
                                // picks come from a streaming catalogue, so it
                                // was a remote link. Here the art is Roon's, so
                                // it points at this server's own image route.
                                // Sending only image_key left every pick blank.
                                .put(
                                    "image",
                                    album.imageKey?.let { "/api/image/$it?width=400" } ?: ""
                                )
                                .put("reason", "From your library")
                                .put("genre", "")
                                .put("added", JSONObject.NULL)
                                .put("offset", album.offset)
                                .put("library_title", album.title)
                                .put("library_subtitle", album.subtitle)
                                .put("image_key", album.imageKey ?: JSONObject.NULL)
                                // The pick's "＋ Listen later" button reads it.
                                .put("later", app.listenLater.has(album.title, album.subtitle))
                        }
                    )
                )
        )
    }

    /**
     * Everything a pick may be drawn from: the whole library, minus anything
     * rejected. Both shapes are honoured — artist blocks (what "Not for me"
     * writes) and the album keys an earlier build stored — so nothing a user
     * already turned down comes back. It used to be albums not played in six
     * months, which the plays table cannot actually answer.
     */
    private fun smartPickPool(): List<AlbumRecord> {
        val blocked = store.blockedPicks()
        return index.albums.filter { album ->
            album.key !in blocked &&
                (BLOCKED_ARTIST_PREFIX + Normalize.text(album.subtitle)) !in blocked
        }
    }

    /**
     * The day's picks. Seeded by the day so the row does not reshuffle on every
     * Home visit. Through view.sample because that IS this draw — the same
     * seededRank order over the same pool — and having it in one place means
     * it is ranked once per album rather than once per comparison. A smaller
     * count is the head of a larger one, so the five sent to Listen later are
     * the first five on the screen.
     */
    private fun smartPicksFor(day: String, pool: List<AlbumRecord>, count: Int): List<AlbumRecord> =
        view.sample(pool, count, LibraryView.fnv1a(day))

    /**
     * Settings → Smart Picks → "Send each day's picks to: Listen later" — the
     * day's first few go on the list, once a day. Done when the picks are
     * next asked for (the Home row, the screen, or the Listen later list
     * itself), which on any day the app is opened is the first screenful.
     */
    private fun sendPicksToLaterIfDue(day: String, pool: List<AlbumRecord>? = null) {
        if (!settings.smartPicksEnabled() || settings.smartPicksDest() != "later") return
        if (!index.isBuilt || settings.smartPicksSentDay() == day) return
        val picks = smartPicksFor(day, pool ?: smartPickPool(), PICKS_TO_LATER)
        for (album in picks) app.listenLater.add(album.title, album.subtitle, source = "picks")
        settings.markSmartPicksSent(day)
    }

    // ------------------------------------------------------ share-card links

    /** Settings → Share Card: what CAN be linked to, and what is switched on. */
    private fun shareLinksSettings(): Response = Json.obj(
        JSONObject()
            .put(
                "services",
                JSONObject()
                    .put("all", Json.arrayOf(ShareLinks.SERVICES.map { JSONObject().put("id", it.id).put("name", it.name) }))
                    .put("enabled", Json.strings(settings.shareServices()))
            )
            .put(
                "reviews",
                JSONObject()
                    .put(
                        "all",
                        Json.arrayOf(
                            ShareLinks.REVIEWS.map {
                                JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind)
                                    .put("chip", it.chip).put("onByDefault", it.onByDefault)
                            }
                        )
                    )
                    .put("enabled", Json.strings(settings.shareReviews()))
            )
    )

    private fun saveShareLinks(request: Request): Response {
        val body = Json.body(request)
        fun ids(field: String): List<String>? =
            body.optJSONArray(field)?.let { arr -> (0 until arr.length()).map { arr.str(it) } }
        val services = ids("services")
        val reviews = ids("reviews")
        // An absent field is "not being changed"; an empty array is "all off".
        if (services == null && reviews == null) return Json.error(400, "services and/or reviews array required")
        settings.saveShareLinks(services, reviews)
        return Json.ok(
            JSONObject()
                .put("services", Json.strings(settings.shareServices()))
                .put("reviews", Json.strings(settings.shareReviews()))
        )
    }

    // --------------------------------------------------------- listen later

    /**
     * The Listen later list, newest first, each entry resolved against the
     * library as it stands NOW — so it is playable wherever Roon has it, under
     * Roon's own strings. The streaming fields Rouen fills (a service album an
     * entry could be added from) are always empty here: every entry this
     * build can hold was put aside from the library.
     */
    private fun listenLaterList(): Response {
        sendPicksToLaterIfDue(java.time.LocalDate.now().toString())
        val list = app.listenLater.entries().map { e ->
            val rec = app.listenLater.record(e)
            JSONObject()
                .put("key", e.key)
                .put("title", e.title)
                .put("artist", e.artist)
                .put("service", "")
                .put("album_id", "")
                .put("image", "")
                .put("source", e.source)
                .put("added_at", e.addedAt)
                .put("added", JSONObject.NULL)
                .put("service_url", JSONObject.NULL)
                .put("offset", rec?.offset ?: JSONObject.NULL)
                .put("library_title", rec?.title ?: "")
                .put("library_subtitle", rec?.subtitle ?: "")
                .put("image_key", rec?.imageKey ?: JSONObject.NULL)
                .put("album", rec?.let { Json.album(it) } ?: JSONObject.NULL)
        }
        return Json.obj(JSONObject().put("albums", Json.arrayOf(list)))
    }

    /**
     * Put an album aside, or take it off. `on` is the state ASKED FOR, not a
     * toggle: two devices tapping at once must both end where they meant to,
     * which a toggle cannot promise.
     */
    private fun setListenLater(request: Request): Response {
        val body = Json.body(request)
        val title = body.str("title").trim()
        val artist = body.str("artist").trim()
        if (title.isEmpty()) return Json.error(400, "title required")
        val on = body.opt("on") as? Boolean ?: return Json.error(400, "on must be true or false")
        if (ListenLater.keyOf(title, artist).isBlank()) return Json.error(400, "unrecognisable album title")
        if (on) {
            val source = body.str("source").takeIf { it in ListenLater.SOURCES } ?: "album"
            if (!app.listenLater.add(title, artist, source)) {
                return Json.error(500, "Couldn't save that — the list holds ${ListenLater.MAX_ENTRIES} albums")
            }
            return Json.ok(JSONObject().put("on", true))
        }
        app.listenLater.remove(title, artist)
        return Json.ok(JSONObject().put("on", false))
    }

    /**
     * "Not for me" — permanently, and per ARTIST rather than per album.
     *
     * The client posts `{artist}` and its toast says "Won't suggest <artist>
     * again", so blocking one record and leaving the rest of the discography in
     * the pool would not be what the button promises. This asked for `title`,
     * which is never sent, so every tap answered "title is required".
     *
     * Stored under a prefix because the same set once held album keys; the
     * prefix keeps the two apart rather than having an artist name silently
     * match an album's key.
     */
    private fun smartPickBlock(request: Request): Response {
        val artist = Json.body(request).str("artist").takeIf { it.isNotBlank() }
            ?: return Json.error(400, "artist is required")
        val canon = Normalize.text(artist).takeIf { it.isNotEmpty() }
            ?: return Json.error(400, "unrecognisable artist name")
        store.blockPick(BLOCKED_ARTIST_PREFIX + canon)
        // The artist, everywhere: their picks leave Listen later too (an album
        // put aside from the album view stays — that was the user's own find).
        app.listenLater.forgetPickArtist(artist)
        app.live.bump("picks")
        return Json.ok(JSONObject().put("artist", artist))
    }

    // -------------------------------------------------------------- settings

    private fun homeRowsJson(): JSONArray = Json.arrayOf(
        settings.homeRows().map { (id, on) ->
            JSONObject().put("id", id).put("on", on)
                .put("unavailable", settings.homeRowUnavailable(id) ?: JSONObject.NULL)
        }
    )

    // ------------------------------------------------------------ lan access

    /**
     * What Settings shows: the switch, the code, and the address to type.
     *
     * LOOPBACK ONLY, both of these. The PIN is the credential — serving it to a
     * device that authenticated WITH it would be harmless, but serving it to
     * anything else would make the whole thing decorative, and the simplest
     * rule that cannot be got wrong later is that the network never sees this
     * route at all.
     */
    private fun lanStatus(request: Request): Response {
        if (!LanAccess.isLoopback(request.remoteAddress)) return Json.error(404, "Not found")
        val lan = settings.lan()
        return Json.obj(
            JSONObject()
                .put("enabled", lan.enabled)
                .put("pin", lan.pin)
                .put("port", app.port)
                .put("addresses", JSONArray().also { a -> Network.hostAddresses().forEach { a.put(it) } })
        )
    }

    private fun saveLan(request: Request): Response {
        if (!LanAccess.isLoopback(request.remoteAddress)) return Json.error(404, "Not found")
        val body = Json.body(request)
        if (!body.has("enabled")) return Json.error(400, "enabled is required")
        val lan = app.setLanAccess(body.optBoolean("enabled", false))
        return Json.ok(
            JSONObject()
                .put("enabled", lan.enabled)
                .put("pin", lan.pin)
                .put("port", app.port)
                .put("addresses", JSONArray().also { a -> Network.hostAddresses().forEach { a.put(it) } })
        )
    }

    private fun homeRows(): Response = Json.obj(JSONObject().put("rows", homeRowsJson()))

    private fun saveHomeRows(request: Request): Response {
        val arr = Json.body(request).optJSONArray("rows")
            ?: return Json.error(400, "rows array is required")
        val clean = ArrayList<Pair<String, Boolean>>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val id = r.str("id").takeIf { it in Settings.HOME_ROW_IDS } ?: continue
            if (!seen.add(id)) continue
            clean += id to r.optBoolean("on", true)
        }
        if (clean.isEmpty()) return Json.error(400, "no recognisable rows")
        settings.saveHomeRows(clean)
        // Answered through the same repair path the GET uses, so the client is
        // told what was actually stored rather than what it sent.
        return Json.ok(JSONObject().put("rows", homeRowsJson()))
    }

    private fun smartPickSettings(): Response = Json.obj(
        JSONObject()
            .put("enabled", settings.smartPicksEnabled())
            .put("hour", settings.smartPicksHour())
            // No streaming library to add picks to, so never "on".
            .put("auto_add", false)
            .put("dest", settings.smartPicksDest())
            .put("dests", Json.strings(Settings.SMART_PICK_DESTS))
            // False, and not because the index is missing. `service_ready`
            // means "there is a streaming account to add a pick TO", and this
            // build has none — so the settings note and the picks banner both
            // say picks are shown rather than added. Reporting the index here
            // instead promised an Add button that had nothing behind it.
            .put("service_ready", false)
    )

    // --------------------------------------------- similar acts and Discover

    /**
     * Where a record the library may or may not hold should GO: in the library
     * -> its offset plus the library's own title and artist (what /api/play
     * checks identity against, since Deezer's punctuation differs from Roon's);
     * otherwise a link per enabled service, the page choosing which.
     *
     * Resolved per request, never cached with the suggestion: whether Roon has
     * a record changes with the library.
     */
    private fun placeOf(o: JSONObject, title: String?, artist: String, request: Request): AlbumRecord? {
        val inLib = if (title.isNullOrBlank()) null else app.discover.resolve(title, artist)
        o.put("in_library", inLib != null)
            .put("offset", inLib?.offset ?: JSONObject.NULL)
            .put("library_title", inLib?.title ?: JSONObject.NULL)
            .put("library_subtitle", inLib?.subtitle ?: JSONObject.NULL)
        val services = if (inLib != null) emptyList() else ShareLinks.serviceLinks(
            artist, title ?: "",
            locale = ShareLinks.localeFromAcceptLanguage(request.headers["accept-language"]),
            enabled = settings.shareServices()
        )
        o.put("services", Json.arrayOf(services.map { it.toJson() }))
        return inLib
    }

    /**
     * "If you like this" — three acts worth hearing next, weighted by what you
     * play (Rouen v1.8.82): see Similar. Each row is a place to go — a queue
     * for a record in the library, a link per enabled service otherwise.
     * Never an error: a suggestion row is not worth one.
     */
    private fun similar(request: Request): Response {
        val artist = request.str("artist")?.trim().orEmpty()
        if (artist.isEmpty()) return Json.error(400, "artist query parameter required")
        val acts = try {
            // The FIRST credited act: a four-act credit searched whole finds nobody.
            app.similar.suggest(ShareLinks.primaryArtist(artist))
        } catch (e: Exception) {
            Log.w(TAG, "similar: ${e.message}", e)
            emptyList()
        }
        return Json.obj(
            JSONObject().put(
                "acts",
                Json.arrayOf(
                    acts.map { a ->
                        JSONObject()
                            .put("name", a.name).put("id", a.id)
                            .put("album", a.album ?: JSONObject.NULL)
                            .put("year", a.year ?: JSONObject.NULL)
                            .put("cover", a.cover ?: JSONObject.NULL)
                            .put("reason", a.reason)
                            .put("known", a.known ?: JSONObject.NULL)
                            .also { placeOf(it, a.album, a.name, request) }
                    }
                )
            )
        )
    }

    /**
     * Discover's list: today's, or the most recent day that found something.
     * Opening the screen is also one of the two things that ask whether the
     * day's build is due — this build has no timer of its own (see Discover).
     */
    private fun discoverList(request: Request): Response {
        if (!roon.isPaired) return Json.error(503, "Not paired with a Roon Core")
        val discover = app.discover
        discover.kick("screen opened")
        val (day, rows) = discover.latest()
        val releases = rows.map { r ->
            val o = JSONObject()
                .put("artist", r.artist).put("album", r.album)
                .put("cover", r.cover ?: JSONObject.NULL)
                .put("release_date", r.releaseDate ?: JSONObject.NULL)
                .put("year", r.releaseDate?.take(4)?.toIntOrNull() ?: JSONObject.NULL)
            val inLib = placeOf(o, r.album, r.artist, request)
            // Roon's own art for a record the library holds.
            o.put("image_key", inLib?.imageKey ?: JSONObject.NULL)
        }
        return Json.obj(
            JSONObject()
                .put("enabled", settings.discoverEnabled())
                .put("day", day)
                .put("releases", Json.arrayOf(releases))
                .put("window_days", Discover.WINDOW_DAYS)
                .put("building", discover.building)
                .put("rules", Discover.RULES)
                .put("rules_current", discover.stampCurrent(day))
        )
    }

    private fun discoverRebuild(): Response {
        if (!settings.discoverEnabled()) return Json.error(400, "Discover is switched off")
        if (app.discover.building) return Json.ok(JSONObject().put("building", true))
        // A refusal is reported, not swallowed: "Refreshing…" for a build that
        // never started is the worst of both answers.
        if (!app.discover.kick("manual rebuild", force = true)) {
            return Json.error(
                503,
                if (roon.isPaired) "Still reading your library — try again in a minute"
                else "Not paired with a Roon Core"
            )
        }
        return Json.ok(JSONObject().put("building", true))
    }

    private fun discoverSettings(): Response = Json.obj(
        JSONObject()
            .put("enabled", settings.discoverEnabled())
            .put("hour", settings.discoverHour())
            .put("window_days", Discover.WINDOW_DAYS)
            .put("seed_count", Discover.SEED_ARTISTS)
    )

    private fun saveDiscover(request: Request): Response {
        val body = Json.body(request)
        if (body.has("hour")) {
            val h = body.optInt("hour", -1)
            if (h !in 0..23) return Json.error(400, "hour must be 0-23")
        }
        settings.saveDiscover(
            if (body.has("enabled")) body.optBoolean("enabled") else null,
            if (body.has("hour")) body.optInt("hour") else null
        )
        // Switched on after the hour: today's list starts now rather than at
        // the next library check.
        if (settings.discoverEnabled()) app.discover.kick("switched on")
        return Json.ok(
            JSONObject().put("enabled", settings.discoverEnabled()).put("hour", settings.discoverHour())
        )
    }

    private fun saveSmartPicks(request: Request): Response {
        val body = Json.body(request)
        if (body.has("hour")) {
            val h = body.optInt("hour", -1)
            if (h !in 0..23) return Json.error(400, "hour must be 0-23")
        }
        // Every field is checked before any is applied, so a refused request
        // changes nothing. "library" is Rouen's third destination — a Qobuz or
        // TIDAL favourite — and is refused rather than stored as a choice
        // nothing here could act on.
        val dest = body.strOrNull("dest")
        if (dest != null && dest !in Settings.SMART_PICK_DESTS) {
            return Json.error(400, "dest must be one of ${Settings.SMART_PICK_DESTS.joinToString(", ")}")
        }
        settings.saveSmartPicks(
            if (body.has("enabled")) body.optBoolean("enabled") else null,
            if (body.has("hour")) body.optInt("hour") else null,
            dest
        )
        return Json.ok(
            JSONObject()
                .put("enabled", settings.smartPicksEnabled())
                .put("hour", settings.smartPicksHour())
                .put("dest", settings.smartPicksDest())
                .put("auto_add", false)
        )
    }

    /**
     * A saved credential, in the shape the settings screen reads: `{set,
     * masked}` on the way out and `{ok, saved}` on the way back.
     *
     * Answering with the wrong shape is what made Save report "Failed to save
     * token" — the page checks `j.ok`, and a response without it is a failure
     * however successful the save was.
     *
     * These are stored but not yet used: the label chain they feed is not in
     * this build (see /api/settings/labels). Storing them anyway is deliberate
     * — a token is the user's to keep, and losing it because the feature is not
     * written yet would mean typing it again later.
     */
    private fun secret(request: Request, isPost: Boolean, key: String, field: String): Response {
        if (!isPost) {
            return Json.obj(
                JSONObject()
                    .put("set", settings.secret(key) != null)
                    .put("masked", settings.maskSecret(key))
                    .put("unused", Settings.LABELS_UNAVAILABLE)
            )
        }
        val value = Json.body(request).str(field).trim()
        // The page reads `ok` and shows `error` beside it, so a bare 400 would
        // surface as its generic "Failed to save" rather than the reason.
        if (value.isEmpty()) {
            return Json.obj(JSONObject().put("ok", false).put("error", "$field is empty"))
        }
        settings.saveSecret(key, value)
        return Json.obj(JSONObject().put("ok", true).put("saved", true))
    }

    /**
     * The labels switch, answered honestly.
     *
     * The front-end already treats `enabled: false` as "hide the Labels screen
     * and its Home row", which is exactly the shape this build needs — so the
     * feature disappears from the UI through its own supported path rather than
     * leaving a menu entry that leads to an error.
     */
    private fun labelsSetting(isPost: Boolean): Response {
        if (isPost) {
            return Json.error(400, Settings.LABELS_UNAVAILABLE)
        }
        return Json.obj(
            JSONObject()
                .put("enabled", false)
                .put("count", 0)
                .put("scanning", false)
                .put("unavailable", Settings.LABELS_UNAVAILABLE)
        )
    }

    // --------------------------------------------------------- wall display

    private fun pitchforkReviews(request: Request): Response {
        val type = if (request.str("type") == "best") "best" else "latest"
        val items = app.pitchfork.reviews(type)
        if (items.isEmpty()) {
            return Json.error(502, "Couldn't reach Pitchfork just now — try again shortly.")
        }
        return Json.obj(
            JSONObject().put("type", type).put("items", Json.arrayOf(items.map { it.toJson() }))
        )
    }

    /**
     * What the library knows about one listing, so the card can offer to play
     * it. `review` is always null and the field is kept only so an older client
     * reading the old shape sees no text rather than undefined.
     */
    private fun pitchforkReview(request: Request): Response {
        val raw = request.str("url") ?: return Json.error(400, "Invalid url")
        val url = runCatching { java.net.URI(raw) }.getOrNull()
            ?: return Json.error(400, "Invalid url")
        if (url.host != "pitchfork.com" || url.path?.startsWith("/reviews/albums/") != true) {
            return Json.error(400, "Not a Pitchfork album-review URL")
        }
        val hit = matchLibraryAlbum(request.str("album"), request.str("artist"))
        return Json.obj(
            JSONObject()
                .put("review", JSONObject.NULL)
                .put("match", hit?.let { Json.album(it) } ?: JSONObject.NULL)
        )
    }

    /**
     * The Qobuz deep link for one review's record, if Qobuz has it.
     *
     * Its own route rather than a field on /api/pitchfork/review, and that is
     * deliberate. The review call answers from the local index and returns at
     * once; this one reads a page off Qobuz and can take a second. Bundling
     * them would hold the "Open in your library" button behind somebody else's
     * server, so the page asks for both at the same time and each upgrades the
     * actions when it lands.
     */
    /**
     * What the search box can find beyond your own library.
     *
     * Pitchfork only — the Qobuz and TIDAL sections went with their browsers.
     * It answers from the listings already cached and NEVER fetches on the way
     * through: this runs on every keystroke, and Pitchfork is rate-gated to one
     * request every 1.5s, so a fetch here would either stall the search box or
     * hammer somebody else's server.
     *
     * The cache is warmed in the background instead when it is found empty, so
     * the first search after a restart comes back empty and the next one works.
     * That is the honest limit of this: what is searchable is the Latest and
     * Best New Music listings, not everything Pitchfork has ever reviewed.
     */
    private fun searchExternal(request: Request): Response {
        val q = request.str("q").orEmpty()
        val cached = app.pitchfork.cachedReviews()
        if (cached.isEmpty()) {
            app.background {
                for (type in Pitchfork.LIST_TYPES) runCatching { app.pitchfork.reviews(type) }
            }
        }
        val hits = Pitchfork.search(cached, q)
        return Json.obj(
            JSONObject()
                .put("pitchfork", Json.arrayOf(hits.map { it.toJson() }))
                // The page reads only "pitchfork" now, but an older cached copy
                // of it read these two — and an empty array is the answer that
                // keeps such a page quiet rather than throwing.
                .put("albums", JSONArray())
                .put("artists", JSONArray())
        )
    }

    private fun pitchforkQobuz(request: Request): Response {
        val album = request.str("album")
        if (album.isNullOrBlank()) return Json.error(400, "album is required")
        val url = app.qobuz.deepLink(request.str("artist"), album)
        return Json.obj(JSONObject().put("url", url ?: JSONObject.NULL))
    }

    /**
     * Pitchfork's album/artist against the library. The artist is only used to
     * disambiguate when it is known — a wrong match here offers to play the
     * wrong record.
     */
    private fun matchLibraryAlbum(album: String?, artist: String?): AlbumRecord? {
        if (album.isNullOrBlank()) return null
        return index.relocate(album, artist) ?: index.relocate(album, null)
    }

    // ---------------------------------------------------------------- image

    private fun image(request: Request, rawKey: String): Response {
        val key = rawKey.substringBefore('?')
        if (key.isEmpty()) return Json.error(400, "image key is required")
        // `size` is what the page actually sends — every art URL it builds uses
        // it, for square art, from an 80px queue thumbnail to a 1000px share
        // card. Reading only width/w meant all of them were served at the 512
        // default: a queue row was fetching forty times the pixels it drew,
        // then holding them in the cache and decoding them in the WebView.
        val size = request.int("size")
        val width = (size ?: request.int("width") ?: request.int("w") ?: 512).coerceIn(32, 2048)
        val height = (size ?: request.int("height") ?: request.int("h") ?: width).coerceIn(32, 2048)
        val scale = request.str("scale")?.takeIf { it in IMAGE_SCALES } ?: "fit"

        val url = roon.imageUrl(key, width, height, scale)
            ?: return Json.error(503, "Not paired with a Roon Core")
        val art = app.art.get(url, "$key|$width|$height|$scale")
            ?: return Json.error(404, "Roon has no art for that key")
        return Response.bytes(
            200, art.contentType, art.bytes,
            // Roon's image keys are content-addressed: the same key is always
            // the same picture, so this can be cached hard.
            mapOf("Cache-Control" to "public, max-age=604800, immutable")
        )
    }

    // -------------------------------------------------------------- updates

    /**
     * An APK cannot replace itself: only Android's installer may, and it always
     * asks. So "apply" downloads the new APK and presents the install, and the
     * banner's poll finishes the story — when the user confirms, this process
     * is replaced and the next status call comes from the new version.
     */
    private fun updateStatus(): Response =
        app.updater?.let { Json.obj(it.status()) } ?: notInLite("/api/update/status")

    private fun updateCheck(): Response =
        app.updater?.let { Json.obj(it.check()) } ?: notInLite("/api/update/check")

    private fun updateApply(): Response {
        val updater = app.updater ?: return notInLite("/api/update/apply")
        // The download must not run on the request thread: the banner starts
        // polling as soon as this returns, and a reply held open for the length
        // of a download reads as a hung update.
        return Json.ok(JSONObject().put("status", updater.apply { r -> app.background { r.run() } }))
    }

    // ------------------------------------------------------ your own playlists

    private fun userPlaylistList(): Response = Json.obj(
        JSONObject().put(
            "playlists",
            Json.arrayOf(app.userPlaylists.all().map { it.summaryJson() })
        )
    )

    private fun userPlaylistOne(request: Request): Response {
        val id = request.str("id") ?: return Json.error(400, "id is required")
        val p = app.userPlaylists.byId(id) ?: return Json.error(404, "No such playlist")
        return Json.obj(p.fullJson())
    }

    /** Creates one, or renames an existing one when the body names an id. */
    private fun saveUserPlaylist(request: Request): Response {
        val body = Json.body(request)
        return app.userPlaylists.save(
            body.str("id").takeIf { it.isNotEmpty() },
            body.str("name")
        ).fold(
            onSuccess = { list ->
                Json.ok(
                    JSONObject()
                        .put("playlists", Json.arrayOf(list.map { it.summaryJson() }))
                )
            },
            onFailure = { Json.error(errorStatus(it), it.message ?: "Couldn't save") }
        )
    }

    private fun deleteUserPlaylist(request: Request): Response {
        val id = Json.body(request).str("id")
        if (id.isEmpty()) return Json.error(400, "id is required")
        return app.userPlaylists.delete(id).fold(
            onSuccess = { list ->
                Json.ok(
                    JSONObject()
                        .put("playlists", Json.arrayOf(list.map { it.summaryJson() }))
                )
            },
            onFailure = { Json.error(errorStatus(it), it.message ?: "Couldn't delete") }
        )
    }

    /**
     * Appends tracks the page already has in hand — from a tracklist on screen,
     * so no Roon call is needed to find out what they are.
     */
    private fun addTracksToPlaylist(request: Request): Response {
        val body = Json.body(request)
        val arr = body.optJSONArray("tracks") ?: return Json.error(400, "tracks required")
        if (arr.length() == 0) return Json.error(400, "tracks required")
        if (arr.length() > UserPlaylists.MAX_ADD_AT_ONCE) {
            return Json.error(400, "Too many at once — ${UserPlaylists.MAX_ADD_AT_ONCE} maximum")
        }
        val incoming = (0 until arr.length()).mapNotNull {
            UserPlaylists.Track.parse(arr.optJSONObject(it))
        }
        val target = app.userPlaylists.resolveTarget(
            body.str("id").takeIf { it.isNotEmpty() },
            body.str("name").takeIf { it.isNotEmpty() }
        ).getOrElse { return Json.error(errorStatus(it), it.message ?: "Couldn't add") }

        return app.userPlaylists.addTracks(target.id, incoming).fold(
            onSuccess = { (p, r) ->
                Json.ok(
                    JSONObject()
                        .put("id", p.id).put("name", p.name)
                        .put("track_total", p.tracks.size)
                        .put("added", r.added)
                        // Rows the page sent that could not be stored, PLUS any
                        // that did not fit — the page reports one number.
                        .put("skipped", r.skipped + (arr.length() - incoming.size))
                        .put("full", r.full)
                )
            },
            onFailure = { Json.error(errorStatus(it), it.message ?: "Couldn't add") }
        )
    }

    /**
     * Appends whole albums, which costs Roon calls that the track route does
     * not: a stored entry names specific tracks, and only the Core knows what
     * is on a record. Each album is opened to read its tracklist.
     *
     * Bounded and reported per album, because a partial success here is normal
     * — one album may have moved since the grid was drawn — and reporting the
     * whole call as failed would be a worse lie than naming the one that missed.
     */
    private fun addAlbumsToPlaylist(request: Request): Response {
        val body = Json.body(request)
        val arr = body.optJSONArray("albums") ?: return Json.error(400, "albums required")
        if (arr.length() == 0) return Json.error(400, "albums required")
        if (arr.length() > ADD_ALBUMS_MAX) {
            return Json.error(400, "Too many albums at once — $ADD_ALBUMS_MAX maximum")
        }
        val target = app.userPlaylists.resolveTarget(
            body.str("id").takeIf { it.isNotEmpty() },
            body.str("name").takeIf { it.isNotEmpty() }
        ).getOrElse { return Json.error(errorStatus(it), it.message ?: "Couldn't add") }

        // The page reads a COUNT of albums read and a LIST OF NAMES that could
        // not be — checked against app.js rather than invented here, which is
        // where eleven wire bugs in this project came from. A count of failures
        // would be useless: knowing WHICH record Roon would not open is the
        // only way to do anything about it.
        val failed = JSONArray()
        var albumsRead = 0
        var addedTotal = 0
        var skippedTotal = 0
        var full = false
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val offset = a.optInt("offset", -1)
            val title = a.str("title")
            if (offset < 0) {
                failed.put(title.ifEmpty { "an album" })
                continue
            }
            // Once it is full there is nothing to be gained by opening the
            // rest, and each one is a browse walk.
            if (full) {
                failed.put(title.ifEmpty { "an album" })
                continue
            }
            val opened = runCatching {
                app.albums.open(
                    offset, null, null, null,
                    Albums.Expect(title.takeIf { it.isNotEmpty() }, a.str("subtitle").takeIf { it.isNotEmpty() })
                )
            }.getOrElse {
                failed.put(title.ifEmpty { "an album" })
                continue
            }
            val tracks = opened.tracks.mapIndexedNotNull { idx, t ->
                UserPlaylists.Track.parse(
                    JSONObject()
                        .put("title", t.title)
                        .put("subtitle", t.subtitle)
                        .put("album_title", opened.title)
                        .put("album_subtitle", opened.subtitle)
                        .put("album_offset", offset)
                        .put("track_index", idx)
                        .put("image_key", opened.imageKey ?: JSONObject.NULL)
                )
            }
            val r = app.userPlaylists.addTracks(target.id, tracks)
                .getOrElse {
                    return Json.error(errorStatus(it), it.message ?: "Couldn't add")
                }
            addedTotal += r.second.added
            skippedTotal += r.second.skipped
            if (r.second.full) full = true
            // Read means it contributed. An album opened successfully that
            // turned out to have nothing storable on it has not been read in
            // any sense the message means.
            if (r.second.added > 0) albumsRead++
            else failed.put(opened.title.ifEmpty { title.ifEmpty { "an album" } })
        }

        val p = app.userPlaylists.byId(target.id)
        return Json.ok(
            JSONObject()
                .put("id", target.id).put("name", target.name)
                .put("track_total", p?.tracks?.size ?: 0)
                .put("added", addedTotal)
                .put("skipped", skippedTotal)
                .put("full", full)
                .put("albums_read", albumsRead)
                .put("albums_failed", failed)
        )
    }

    /** A refusal's shape decides its status; the message is the model's. */
    private fun errorStatus(e: Throwable): Int = when (e) {
        is NoSuchElementException -> 404
        else -> 400
    }

    // ----------------------------------------------------- not in this build

    /**
     * Endpoints the original serves that this build does not.
     *
     * Answered in the UI's own "feature off" shape wherever one exists, so the
     * screen renders its empty state instead of an error. Anything with no such
     * shape gets a 501 that says what is missing and why, which is more use than
     * a bare 404.
     */
    private fun notInLite(path: String, post: Boolean = false): Response = when {
        // Labels, and everything the label index feeds.
        path.startsWith("/api/labels") || path == "/api/label-albums" ->
            Json.error(501, Settings.LABELS_UNAVAILABLE)

        path == "/api/filters/labels" -> Json.obj(JSONObject().put("labels", JSONArray()))
        path == "/api/home/label-of-the-week" -> Json.obj(JSONObject().put("label", JSONObject.NULL))
        path == "/api/settings/label-folder-depth" -> Json.obj(JSONObject().put("depth", 0))

        // Qobuz and TIDAL are gone from this build entirely — the browsers,
        // the logins and the routes. Both went through unofficial APIs the two
        // services' own terms forbid, and both bought catalogue browsing only:
        // Roon streams from either service through its own account regardless,
        // so their absence changes nothing about playback.
        //
        // These paths answer 501 rather than 404 because a stale cached page
        // asking for them deserves the reason, not "no such endpoint".
        path.startsWith("/api/qobuz") || path.startsWith("/api/settings/qobuz") ->
            Json.error(501, STREAMING_UNAVAILABLE.format("Qobuz"))
        path.startsWith("/api/tidal") || path.startsWith("/api/settings/tidal") ->
            Json.error(501, STREAMING_UNAVAILABLE.format("TIDAL"))

        // Self-update on a host that cannot install an APK — the JVM tests.
        // "available: false" is the shape the update banner reads as "nothing
        // to do", so it stays hidden rather than erroring.
        path.startsWith("/api/update") ->
            Json.obj(
                JSONObject().put("available", false).put("current", app.version)
                    .put("note", "Updates aren't available on this host.")
            )

        // Reading file tags needs a mounted music directory, which a phone
        // does not have.
        path == "/api/music-mount" -> Json.obj(JSONObject().put("mounted", false).put("path", ""))

        // Playlists, sharing and saved lists are not in this build yet. Empty
        // collections keep their screens at "nothing here" rather than an error.
        path == "/api/playlists" -> Json.obj(JSONObject().put("playlists", JSONArray()))
        // GET only. Answering a SAVE with the same empty list told the page it
        // had worked — it checks for a 2xx and toasts 'Saved "<name>"' — while
        // nothing was kept anywhere.
        path == "/api/smart-playlists" && !post -> Json.obj(JSONObject().put("playlists", JSONArray()))
        path.startsWith("/api/playlist") ||
            path.startsWith("/api/smart-playlist") || path.startsWith("/api/share") ->
            Json.error(501, "Playlists and sharing aren't in the lite build yet.")

        path.startsWith("/api/debug") -> Json.error(501, "Debug endpoints aren't in the lite build.")

        else -> Json.error(404, "No such endpoint: $path")
    }
}
