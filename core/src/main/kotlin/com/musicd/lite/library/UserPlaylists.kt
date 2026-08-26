package com.musicd.lite.library

import com.musicd.lite.str
import com.musicd.lite.store.Store
import org.json.JSONArray
import org.json.JSONObject

/**
 * Playlists you make yourself, kept by this app.
 *
 * Nothing here touches Roon, and it cannot: the extension API has no way to
 * write a playlist. That is confirmed rather than assumed — the upstream
 * project drilled the browse tree's action menus against a live Core looking
 * for an "Add to Playlist" and found none, and the request has sat unanswered
 * on RoonLabs' trackers since 2017. So a playlist lives here, and playing it
 * means opening each album on the Core and invoking the track.
 *
 * A stored entry therefore names an album and a position inside it, not a file
 * or an id: [Track.albumOffset] with [Track.albumTitle] to check it against.
 * Offsets move when the library is re-indexed, which is exactly why the title
 * travels with it — the same stale-offset defence the album grid uses.
 */
class UserPlaylists(private val store: Store) {

    companion object {
        const val KEY = "user_playlists"

        /** Ceilings, matched to the upstream project so a shared file imports. */
        const val MAX_PLAYLISTS = 50
        const val MAX_TRACKS = 500
        const val MAX_ADD_AT_ONCE = 200
        const val MAX_NAME = 60
        const val MAX_TEXT = 500

        /**
         * Collapses whitespace and clamps, trimming AFTER the clamp as well as
         * before. Slicing mid-string can leave a trailing space, and a title
         * with one is a different key from the same title without.
         */
        fun text(v: String?, max: Int = MAX_TEXT): String =
            (v ?: "").replace(Regex("\\s+"), " ").trim().take(max).trim()
    }

    /** One track, stored as the way back to it rather than as a file. */
    data class Track(
        val albumOffset: Int,
        val albumTitle: String,
        val albumSubtitle: String,
        val trackIndex: Int,
        val title: String,
        val subtitle: String,
        val imageKey: String?,
        val trackNo: Int?
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("album_offset", albumOffset)
            .put("album_title", albumTitle)
            .put("album_subtitle", albumSubtitle)
            .put("track_index", trackIndex)
            .put("title", title)
            .put("subtitle", subtitle)
            .put("image_key", imageKey ?: JSONObject.NULL)
            .put("track_no", trackNo ?: JSONObject.NULL)

        companion object {
            /**
             * Built field by field from a known list, never passed through.
             * This data arrives from the page and, once sharing lands, from
             * other people's files.
             *
             * A track with no album is dropped rather than stored: there would
             * be nothing to open on the Core, so the entry could never play and
             * would only inflate the count with a dead row.
             */
            fun parse(o: JSONObject?): Track? {
                if (o == null) return null
                val title = text(o.str("title"))
                val albumTitle = text(o.str("album_title"))
                if (title.isEmpty() || albumTitle.isEmpty()) return null
                val offset = o.optInt("album_offset", -1)
                if (offset < 0 || offset > 5_000_000) return null
                return Track(
                    albumOffset = offset,
                    albumTitle = albumTitle,
                    albumSubtitle = text(o.str("album_subtitle")),
                    trackIndex = o.optInt("track_index", 0).coerceIn(0, 999),
                    title = title,
                    subtitle = text(o.str("subtitle")),
                    imageKey = text(o.str("image_key"), 200).takeIf { it.isNotEmpty() },
                    trackNo = o.optInt("track_no", 0).takeIf { it in 1..999 }
                )
            }
        }
    }

    data class Playlist(
        val id: String,
        val name: String,
        val tracks: List<Track>,
        val createdAt: Long,
        val updatedAt: Long
    ) {
        /**
         * Up to four DISTINCT covers for the tile mosaic. Distinct because a
         * playlist of one record would otherwise show the same sleeve four
         * times, which reads as a rendering fault rather than as a playlist.
         */
        fun artKeys(): List<String> {
            val keys = ArrayList<String>(4)
            for (t in tracks) {
                val k = t.imageKey ?: continue
                if (k !in keys) keys += k
                if (keys.size == 4) break
            }
            return keys
        }

        fun summaryJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("track_total", tracks.size)
            .put("art_keys", JSONArray(artKeys()))
            .put("updated_at", updatedAt)

        fun fullJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("tracks", JSONArray(tracks.map { it.toJson() }))
            .put("track_total", tracks.size)
    }

    /** What an append did, including what did not fit. */
    data class AddResult(val added: Int, val skipped: Int, val full: Boolean)

    // ------------------------------------------------------------- storage

    private fun load(): MutableList<Playlist> {
        val raw = store.setting(KEY) ?: return ArrayList()
        val out = ArrayList<Playlist>()
        runCatching {
            val arr = JSONObject(raw).optJSONArray("playlists") ?: return@runCatching
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = text(o.str("id"), 64)
                val name = text(o.str("name"), MAX_NAME)
                if (id.isEmpty() || name.isEmpty()) continue
                val tracks = ArrayList<Track>()
                val ta = o.optJSONArray("tracks")
                if (ta != null) {
                    for (j in 0 until ta.length()) {
                        Track.parse(ta.optJSONObject(j))?.let { tracks += it }
                        if (tracks.size >= MAX_TRACKS) break
                    }
                }
                out += Playlist(
                    id, name, tracks,
                    o.optLong("created_at", 0L), o.optLong("updated_at", 0L)
                )
            }
        }
        return out
    }

    private fun persist(list: List<Playlist>) {
        val arr = JSONArray()
        for (p in list.take(MAX_PLAYLISTS)) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("tracks", JSONArray(p.tracks.map { it.toJson() }))
                    .put("created_at", p.createdAt)
                    .put("updated_at", p.updatedAt)
            )
        }
        store.putSetting(KEY, JSONObject().put("playlists", arr).toString())
    }

    private fun newId(now: Long): String =
        "up_" + now.toString(36) + "_" + (0..0xFFFF).random().toString(36)

    // --------------------------------------------------------------- reads

    fun all(): List<Playlist> = load()

    fun byId(id: String): Playlist? = load().firstOrNull { it.id == id }

    // -------------------------------------------------------------- writes

    /** Creates one, or renames an existing one when [id] names it. */
    fun save(id: String?, nameRaw: String, now: Long = System.currentTimeMillis()):
        Result<List<Playlist>> {
        val name = text(nameRaw, MAX_NAME)
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("name required"))
        val list = load()
        if (!id.isNullOrEmpty()) {
            val at = list.indexOfFirst { it.id == id }
            if (at < 0) return Result.failure(NoSuchElementException("No such playlist"))
            list[at] = list[at].copy(name = name, updatedAt = now)
        } else {
            if (list.size >= MAX_PLAYLISTS) {
                return Result.failure(
                    IllegalStateException("That's $MAX_PLAYLISTS playlists — delete one first")
                )
            }
            list += Playlist(newId(now), name, emptyList(), now, now)
        }
        persist(list)
        return Result.success(list)
    }

    fun delete(id: String): Result<List<Playlist>> {
        val list = load()
        if (!list.removeAll { it.id == id }) {
            return Result.failure(NoSuchElementException("No such playlist"))
        }
        persist(list)
        return Result.success(list)
    }

    /**
     * Finds the playlist an add is aimed at, creating it when only a name was
     * given. One resolver, so the track route and the album route cannot come
     * to different conclusions about what a name with no id means.
     */
    fun resolveTarget(id: String?, nameRaw: String?, now: Long = System.currentTimeMillis()):
        Result<Playlist> {
        val list = load()
        if (!id.isNullOrEmpty()) {
            return list.firstOrNull { it.id == id }
                ?.let { Result.success(it) }
                ?: Result.failure(NoSuchElementException("No such playlist"))
        }
        val name = text(nameRaw, MAX_NAME)
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("id or name required"))
        if (list.size >= MAX_PLAYLISTS) {
            return Result.failure(
                IllegalStateException("That's $MAX_PLAYLISTS playlists — delete one first")
            )
        }
        val made = Playlist(newId(now), name, emptyList(), now, now)
        persist(list + made)
        return Result.success(made)
    }

    /**
     * Appends, clamped, and says what did not fit.
     *
     * `full` travels back so a caller can report a playlist that filled up
     * rather than a clean success for a partial add.
     */
    fun addTracks(
        playlistId: String,
        incoming: List<Track>,
        now: Long = System.currentTimeMillis()
    ): Result<Pair<Playlist, AddResult>> {
        val list = load()
        val at = list.indexOfFirst { it.id == playlistId }
        if (at < 0) return Result.failure(NoSuchElementException("No such playlist"))
        val tracks = ArrayList(list[at].tracks)
        var added = 0
        var full = false
        for (t in incoming) {
            if (tracks.size >= MAX_TRACKS) { full = true; break }
            tracks += t
            added++
        }
        val updated = list[at].copy(tracks = tracks, updatedAt = now)
        list[at] = updated
        persist(list)
        return Result.success(updated to AddResult(added, incoming.size - added, full))
    }
}
