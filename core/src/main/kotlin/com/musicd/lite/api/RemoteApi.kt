package com.musicd.lite.api

import com.musicd.lite.str
import com.musicd.lite.strOrNull
import com.musicd.lite.Log
import com.musicd.lite.MusicdLite
import com.musicd.lite.http.HttpServer
import com.musicd.lite.http.Request
import com.musicd.lite.http.Response
import com.musicd.lite.library.AlbumRecord
import com.musicd.lite.library.Albums
import com.musicd.lite.library.LibraryView
import com.musicd.lite.library.Normalize
import com.musicd.lite.library.Search
import com.musicd.lite.meta.Metadata
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
        const val RANDOM_DEFAULT = 30
        const val HISTORY_DAYS = 30
        const val HISTORY_MAX_TILES = 60

        /** Roon rejects a play of more albums than this in one go. */
        const val PLAY_MULTI_MAX = 400

        /** Distinguishes a blocked ARTIST from the album keys once stored. */
        const val BLOCKED_ARTIST_PREFIX = "artist:"

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
        val wanted = if (path == "/" || path.isEmpty()) "/index.html" else path
        val hit = assets.read(wanted) ?: assets.read("/index.html")
        ?: return Response.text(404, "The bundled front-end is missing from this build")
        return Response.bytes(
            200, hit.second, hit.first,
            // The assets ship inside the APK and only change when the app does,
            // so the WebView may hold them for the life of the process.
            mapOf("Cache-Control" to "public, max-age=3600")
        )
    }

    // ---------------------------------------------------------------- API

    private fun apiRoute(request: Request, path: String): Response {
        val post = request.method == "POST"

        // Image is the highest-volume route; keep it first.
        if (path.startsWith("/api/image/")) return image(request, path.removePrefix("/api/image/"))

        return when (path) {
            "/api/status" -> status()
            "/api/zones" -> zones()
            "/api/outputs" -> outputs()
            "/api/zone-state" -> zoneState(request)
            "/api/queue" -> queue(request)
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
            "/api/library/facets" -> libraryFacets(request)
            "/api/library/rescan", "/api/reindex" -> requirePost(post) { rescan() }
            "/api/library-stats" -> Json.obj(
                JSONObject().put("albums", index.count).put("building", index.isBuilding)
            )

            "/api/album" -> album(request)
            "/api/album/extras" -> albumExtras(request)
            "/api/album/now-playing" -> nowPlayingAlbum(request)
            "/api/play" -> playAlbum(request, "play_now")
            "/api/play-track" -> playTrack(request)
            "/api/play-multi" -> playMulti(request)
            "/api/play-unheard" -> playUnheard(request)

            "/api/search" -> search(request)
            "/api/search-status" -> Json.obj(
                JSONObject()
                    .put("ready", index.isBuilt)
                    .put("building", index.isBuilding)
                    .put("progress", index.progress)
                    .put("count", index.count)
            )

            "/api/artist-albums" -> artistAlbums(request)
            "/api/artist-bio" -> artistBio(request)

            // Updates. The banner polls status, taps check, then apply, and
            // keeps polling until `current` becomes the new version — which
            // happens when Android has replaced the app and this server has
            // restarted inside it.
            "/api/update/status" -> updateStatus()
            "/api/update/check" -> requirePost(post) { updateCheck() }
            "/api/update/apply" -> requirePost(post) { updateApply() }
            "/api/tile/status" ->
                Json.ok(JSONObject().put("supported", app.tileSupported()))
            "/api/tile/add" -> requirePost(post) { tileAdd() }

            "/api/filters/genres" -> genres()
            "/api/filters/decades" -> decades()
            "/api/filters/tags" -> tags()

            "/api/home/unplayed" -> homeUnplayed(request)
            "/api/home/history" -> homeHistory(request)
            "/api/home/album-of-the-day" -> albumOfTheDay()
            "/api/home/genre-groups" -> genreGroups()

            "/api/radio" -> radio(request)
            "/api/smart-picks" -> smartPicks(request)
            "/api/smart-picks/block" -> smartPickBlock(request)
            "/api/smart-picks/rebuild" -> requirePost(post) { Json.ok() }

            "/api/settings/home-rows" -> if (post) saveHomeRows(request) else homeRows()
            "/api/settings/smart-picks" -> if (post) saveSmartPicks(request) else smartPickSettings()
            "/api/settings/labels" -> labelsSetting(post)
            "/api/settings/discogs-token" ->
                secret(request, post, Settings.KEY_DISCOGS_TOKEN, "token")
            "/api/settings/fanart-key" ->
                secret(request, post, Settings.KEY_FANART_KEY, "key")

            "/api/pitchfork/reviews" -> pitchforkReviews(request)
            "/api/pitchfork/review" -> pitchforkReview(request)

            "/api/shortcut/zones" -> zones()
            "/api/shortcut/play-random" -> shortcutPlay(request, unheardOnly = false)
            "/api/shortcut/play-unheard" -> shortcutPlay(request, unheardOnly = true)

            else -> notInLite(path)
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
        val known = index.albums.mapTo(HashSet()) { it.nArtist }
        val perName = HashSet<String>()
        for (al in index.albums) for (a in al.artistNames) perName += a.normalized
        return JSONArray().also { arr ->
            for (n in names) {
                arr.put(
                    JSONObject()
                        .put("name", n.name)
                        .put("linkable", n.normalized in known || n.normalized in perName)
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
        return Json.obj(JSONObject().put("items", Json.arrayOf(items.map { it.toJson() })))
    }

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
        val how = body.str("how").ifEmpty { "absolute" }
        val value = body.optDouble("value", Double.NaN)
        if (value.isNaN()) return Json.error(400, "value is required")

        val outputId = body.str("output_id").takeIf { it.isNotEmpty() }
        val targets = if (outputId != null) {
            listOfNotNull(roon.outputs().firstOrNull { it.outputId == outputId })
        } else {
            val zone = roon.zone(body.str("zone_or_output_id").takeIf { it.isNotEmpty() })
                ?: return Json.error(400, "output_id or zone_or_output_id is required")
            zone.volumeOutputs
        }
        if (targets.isEmpty()) return Json.error(400, "that zone has no volume control")

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
        return Json.ok(JSONObject().put("random_album_radio_stands_down", stoodDown))
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

    private fun transferZone(request: Request): Response {
        val body = Json.body(request)
        val from = body.str("from").takeIf { it.isNotEmpty() }
            ?: return Json.error(400, "from is required")
        val to = body.str("to").takeIf { it.isNotEmpty() }
            ?: return Json.error(400, "to is required")
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

        // Unfiltered picks come straight from the snapshot: the same shape the
        // browse path returns, with full-library offsets, so open and play work
        // unchanged. That removes a browse walk plus one load per tile from
        // every Home visit.
        if (filter == null || filter.type == AlbumFilter.DECADE) {
            val pool = if (filter == null) index.albums else {
                val decade = filter.value.removeSuffix("s").toIntOrNull()
                    ?: return Json.error(400, "unrecognised decade ${filter.value}")
                index.albums.filter {
                    val y = view.albumYearOf(it)
                    y != null && y >= decade && y < decade + 10
                }
            }
            if (pool.isEmpty() && !index.isBuilt) {
                return Json.error(503, "The library is still being scanned")
            }
            val picked = view.sample(pool, count)
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
        val q = view.sanitize(
            request.str("sort"), request.str("dir"), request.str("prefix"),
            request.str("played"), request.str("genre"), request.str("decade"), request.str("seed")
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
    private fun libraryFacets(request: Request): Response {
        if (!index.isBuilt) return Json.error(503, "The library index is still building")

        val decadeChips = view.decades().map { (decade, n) ->
            JSONObject().put("id", "${decade}s").put("label", "${decade}s").put("count", n)
        }

        val genreCounts = HashMap<String, Int>()
        for ((_, genres) in store.albumGenresAll()) {
            for (g in genres) genreCounts[g] = (genreCounts[g] ?: 0) + 1
        }
        val genreChips = genreCounts.entries.sortedByDescending { it.value }.take(40)
            .map { JSONObject().put("id", it.key).put("label", it.key).put("count", it.value) }

        val everPlayed = view.playedTitlesSince(0)
        val played = index.albums.count { it.title.lowercase().trim() in everPlayed }

        val playedChips = listOf(
            JSONObject().put("id", "never").put("label", "Never played").put("count", index.count - played),
            JSONObject().put("id", "played").put("label", "Played").put("count", played),
            JSONObject().put("id", "6").put("label", "Not in 6 months")
                .put("count", view.unplayed(6).size),
            JSONObject().put("id", "12").put("label", "Not in a year")
                .put("count", view.unplayed(12).size)
        )

        val facets = JSONArray()
            .put(JSONObject().put("id", "decade").put("label", "Decade").put("values", Json.arrayOf(decadeChips)))
            .put(JSONObject().put("id", "played").put("label", "Listening").put("values", Json.arrayOf(playedChips)))
        if (genreChips.isNotEmpty()) {
            facets.put(JSONObject().put("id", "genre").put("label", "Genre").put("values", Json.arrayOf(genreChips)))
        }

        return Json.obj(
            JSONObject()
                .put("facets", facets)
                .put("total", index.count)
                .put("sorts", Json.strings(LibraryView.SORTS))
        )
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
        .put("library_moved", r.libraryMoved)
        .put("partial", r.partial)
        .put("declared_tracks", r.declaredTracks ?: JSONObject.NULL)

    private fun albumExtras(request: Request): Response {
        val title = request.str("title") ?: return Json.error(400, "title is required")
        val artist = request.str("artist") ?: ""
        val extras = app.metadata.extras(title, artist)

        // A year learned here is worth keeping: it feeds the Decade filter and
        // the year sort, which otherwise only fill in as albums are played.
        val record = index.relocate(title, artist)
        if (record != null && extras.year != null) {
            runCatching {
                store.putAlbumYear(record.key, extras.year, YearSource.MUSICBRAINZ)
            }
        }
        val year = extras.year ?: record?.let { view.albumYearOf(it) }

        // The Pitchfork score, which the album card draws as a chip beside the
        // year (plus a BNM badge). It reads extras.album.score and
        // extras.album.isBestNewMusic — fields this never sent, so the chip
        // never appeared even though the reviews screen had the data.
        //
        // A miss is the normal case: most records were never reviewed, and the
        // card simply shows no chip.
        val review = runCatching { app.pitchfork.reviewFor(title, artist) }.getOrNull()

        fun bio(b: Metadata.Bio?, withReview: Boolean = false): Any {
            if (b == null && !(withReview && review != null)) return JSONObject.NULL
            val o = JSONObject()
                .put("description", b?.description ?: "")
                .put("source", b?.source ?: JSONObject.NULL)
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

        return Json.obj(
            JSONObject()
                .put("year", year ?: JSONObject.NULL)
                .put("album", bio(extras.album, withReview = true))
                .put("artist", bio(extras.artist))
        )
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

    private fun playTrack(request: Request): Response {
        val body = Json.body(request)
        val offset = body.optInt("offset", -1)
        if (offset < 0) return Json.error(400, "offset is required")
        val zone = body.str("zone_or_output_id").takeIf { it.isNotEmpty() }
            ?: return Json.error(400, "zone_or_output_id is required")
        val trackIndex = body.optInt("track_index", -1)
        if (trackIndex < 0) return Json.error(400, "track_index is required")
        val kind = body.str("kind").takeIf { it.isNotEmpty() } ?: "play_now"
        val (invoked, track) = app.albums.invokeTrack(
            offset, trackIndex, body.str("track_title").takeIf { it.isNotEmpty() },
            zone, kind,
            AlbumFilter.parse(
                body.str("filter_type"), body.str("filter_value"),
                body.str("filter_parent")
            ),
            Albums.Expect(
                body.str("title").takeIf { it.isNotEmpty() },
                body.str("subtitle").takeIf { it.isNotEmpty() }
            )
        )
        return Json.ok(JSONObject().put("invoked", invoked).put("track", track))
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

    private fun playUnheard(request: Request): Response {
        val body = Json.body(request)
        val zone = body.str("zone_or_output_id").takeIf { it.isNotEmpty() }
            ?: return Json.error(400, "zone_or_output_id is required")
        val months = body.optInt("months", 6).coerceIn(1, 120)
        val pool = view.unplayed(months).ifEmpty { index.albums }
        val album = view.sample(pool, 1).firstOrNull()
            ?: return Json.error(503, "The library index is still building")
        val r = app.albums.open(
            album.offset, zone, "play_now", null, Albums.Expect(album.title, album.subtitle)
        )
        return Json.ok(JSONObject().put("album", Json.album(album)).put("invoked", r.invoked ?: JSONObject.NULL))
    }

    /**
     * One tap, one album. Shared with the widget and the Quick Settings tile,
     * which reach the same action without going through HTTP at all.
     */
    private fun shortcutPlay(request: Request, unheardOnly: Boolean): Response =
        app.playRandomAlbum(request.str("zone"), unheardOnly).fold(
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

    private fun homeUnplayed(request: Request): Response {
        val months = (request.int("months") ?: 6).coerceIn(1, 120)
        val count = (request.int("count") ?: RANDOM_DEFAULT).coerceIn(1, 96)
        val pool = view.unplayed(months)
        return Json.obj(
            JSONObject()
                .put("albums", Json.albums(view.sample(pool, count)))
                .put("total", pool.size)
                .put("months", months)
        )
    }

    private fun homeHistory(request: Request): Response {
        val count = (request.int("count") ?: HISTORY_MAX_TILES).coerceIn(1, HISTORY_MAX_TILES)
        return Json.obj(
            JSONObject()
                .put("albums", Json.albums(view.history(HISTORY_DAYS, count)))
                .put("days", HISTORY_DAYS)
        )
    }

    private fun albumOfTheDay(): Response {
        val album = view.albumOfTheDay()
            ?: return Json.obj(JSONObject().put("album", JSONObject.NULL))
        // A suggestion you have already taken is not a suggestion.
        if (view.playedToday(album)) {
            return Json.obj(JSONObject().put("album", JSONObject.NULL).put("played", true))
        }
        return Json.obj(JSONObject().put("album", Json.album(album)))
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
            it to (Regex("(\\d[\\d,]*)\\s*albums?", RegexOption.IGNORE_CASE)
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
            .put("auto_add", settings.smartPicksAutoAdd())
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
        val blocked = store.blockedPicks()
        // Both shapes are honoured: artist blocks (what "Not for me" writes) and
        // the album keys an earlier build stored, so nothing a user already
        // rejected comes back.
        val pool = view.unplayed(6).filter { album ->
            album.key !in blocked &&
                (BLOCKED_ARTIST_PREFIX + Normalize.text(album.subtitle)) !in blocked
        }
        // Seeded by the day so the row does not reshuffle on every Home visit.
        val seed = LibraryView.fnv1a(day)
        val picks = pool.sortedBy { LibraryView.seededRank(it.key, seed) }.take(count)
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
                                .put("kind", "unplayed")
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
                                .put("reason", "Not played in the last six months")
                                .put("genre", "")
                                .put("added", JSONObject.NULL)
                                .put("offset", album.offset)
                                .put("library_title", album.title)
                                .put("library_subtitle", album.subtitle)
                                .put("image_key", album.imageKey ?: JSONObject.NULL)
                        }
                    )
                )
        )
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
        return Json.ok(JSONObject().put("artist", artist))
    }

    // -------------------------------------------------------------- settings

    private fun homeRowsJson(): JSONArray = Json.arrayOf(
        settings.homeRows().map { (id, on) ->
            JSONObject().put("id", id).put("on", on)
                .put("unavailable", settings.homeRowUnavailable(id) ?: JSONObject.NULL)
        }
    )

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
            .put("auto_add", settings.smartPicksAutoAdd())
            // False, and not because the index is missing. `service_ready`
            // means "there is a streaming account to add a pick TO", and this
            // build has none — so the settings note and the picks banner both
            // say picks are shown rather than added. Reporting the index here
            // instead promised an Add button that had nothing behind it.
            .put("service_ready", false)
    )

    private fun saveSmartPicks(request: Request): Response {
        val body = Json.body(request)
        if (body.has("hour")) {
            val h = body.optInt("hour", -1)
            if (h !in 0..23) return Json.error(400, "hour must be 0-23")
        }
        settings.saveSmartPicks(
            if (body.has("enabled")) body.optBoolean("enabled") else null,
            if (body.has("hour")) body.optInt("hour") else null,
            if (body.has("auto_add")) body.optBoolean("auto_add") else null
        )
        return Json.ok(
            JSONObject()
                .put("enabled", settings.smartPicksEnabled())
                .put("hour", settings.smartPicksHour())
                .put("auto_add", settings.smartPicksAutoAdd())
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
        val scale = request.str("scale")?.takeIf { it in setOf("fit", "fill", "stretch") } ?: "fit"

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

    /**
     * Offers the Quick Settings tile.
     *
     * The tile is declared in the manifest and the system knows about it, but
     * finding it means opening the shade's tile editor and scrolling past
     * every tile the phone ships with — so the app asks on the user's behalf
     * instead of documenting where to look.
     */
    private fun tileAdd(): Response = app.requestTile().fold(
        onSuccess = { Json.ok(JSONObject().put("ok", true)) },
        onFailure = {
            Json.ok(
                JSONObject()
                    .put("ok", false)
                    .put("error", it.message ?: "Could not add the tile")
            )
        }
    )

    // ----------------------------------------------------- not in this build

    /**
     * Endpoints the original serves that this build does not.
     *
     * Answered in the UI's own "feature off" shape wherever one exists, so the
     * screen renders its empty state instead of an error. Anything with no such
     * shape gets a 501 that says what is missing and why, which is more use than
     * a bare 404.
     */
    private fun notInLite(path: String): Response = when {
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

        // Pitchfork is the only external source here, and it has its own route.
        path == "/api/search/external" ->
            Json.obj(JSONObject().put("albums", JSONArray()).put("artists", JSONArray()))

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
        path == "/api/user-playlists" -> Json.obj(JSONObject().put("playlists", JSONArray()))
        path == "/api/smart-playlists" -> Json.obj(JSONObject().put("playlists", JSONArray()))
        path.startsWith("/api/playlist") || path.startsWith("/api/user-playlist") ||
            path.startsWith("/api/smart-playlist") || path.startsWith("/api/share") ->
            Json.error(501, "Playlists and sharing aren't in the lite build yet.")

        path.startsWith("/api/debug") -> Json.error(501, "Debug endpoints aren't in the lite build.")

        else -> Json.error(404, "No such endpoint: $path")
    }
}
