package com.musicd.lite

import com.musicd.lite.roon.BrowseApi
import com.musicd.lite.roon.BrowseSessionPool
import com.musicd.lite.roon.BrowseTree
import com.musicd.lite.roon.Output
import com.musicd.lite.roon.QueueItem
import com.musicd.lite.roon.RoonApi
import com.musicd.lite.roon.RoonStage
import com.musicd.lite.roon.RoonStatus
import com.musicd.lite.roon.Zone
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Roon Core made of data instead of a network.
 *
 * It answers `browse` and `load` the way a real Core does — levels with their
 * own item_keys, an action_list per album, paged loads with a declared count —
 * so the walkers, the stale-offset ladder and the HTTP API can all be driven
 * end to end without a Core on the network.
 */
class FakeCore(
    albums: List<Triple<String, String, String?>> = emptyList()
) : RoonApi, BrowseApi {

    /** One album in the fake library: title, artist, image key. */
    class FakeAlbum(val title: String, val artist: String, val imageKey: String?) {
        val tracks = ArrayList<String>()
    }

    val albums = ArrayList<FakeAlbum>()
    val genres = LinkedHashMap<String, MutableList<Int>>()   // genre -> album indexes
    val tags = LinkedHashMap<String, MutableList<Int>>()

    /** Every action the fake was asked to invoke, as "kind:target". */
    val invoked = ArrayList<String>()

    var zonesList: List<Zone> = emptyList()
    var outputsList: List<Output> = emptyList()
    var queueItems: List<QueueItem> = emptyList()
    val calls = ArrayList<String>()

    /** Flip to make every browse/load fail, as an unpaired Core does. */
    var paired = true

    init {
        for ((title, artist, image) in albums) {
            this.albums += FakeAlbum(title, artist, image).apply {
                tracks += listOf("1. Opening", "2. Middle Eight", "3. Closer")
            }
        }
    }

    fun addAlbum(title: String, artist: String, image: String? = null, tracks: List<String>? = null) {
        albums += FakeAlbum(title, artist, image).apply {
            this.tracks += tracks ?: listOf("1. Opening", "2. Middle Eight", "3. Closer")
        }
    }

    // ------------------------------------------------------------- RoonApi

    override val isPaired: Boolean get() = paired
    override val status: RoonStatus
        get() = if (paired) RoonStatus(RoonStage.PAIRED, "core-1", "Fake Core", "Paired")
        else RoonStatus(RoonStage.DISCOVERING, detail = "Looking")

    override val tree = BrowseTree(this)

    /**
     * The real waiting mechanism, not a stub — a fake that returned instantly
     * would let a long poll that never blocks pass its own test.
     */
    private val zoneLock = Object()
    private var zoneRev = 0L

    override val zoneRevision: Long get() = synchronized(zoneLock) { zoneRev }

    override fun awaitZoneChange(since: Long, timeoutMs: Long): Long = synchronized(zoneLock) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (zoneRev <= since) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            (zoneLock as Object).wait(left)
        }
        zoneRev
    }

    /** What a test calls to stand in for Roon pushing a change. */
    fun bumpZones() = synchronized(zoneLock) {
        zoneRev++
        (zoneLock as Object).notifyAll()
    }

    override fun zones(): List<Zone> = zonesList
    override fun zone(id: String?): Zone? = zonesList.firstOrNull { it.zoneId == id }
    override fun outputs(): List<Output> = outputsList
    override fun queue(zoneId: String, count: Int, timeoutMs: Long): List<QueueItem> = queueItems

    override fun control(zoneOrOutputId: String, command: String) {
        calls += "control:$zoneOrOutputId:$command"
    }

    override fun seek(zoneOrOutputId: String, how: String, seconds: Int) {
        calls += "seek:$zoneOrOutputId:$how:$seconds"
    }

    override fun changeVolume(outputId: String, how: String, value: Double) {
        calls += "volume:$outputId:$how:$value"
    }

    override fun mute(outputId: String, how: String) {
        calls += "mute:$outputId:$how"
    }

    override fun changeSettings(zoneOrOutputId: String, patch: JSONObject) {
        calls += "settings:$zoneOrOutputId:$patch"
    }

    override fun standby(outputId: String, controlKey: String) {
        calls += "standby:$outputId:$controlKey"
    }

    override fun convenienceSwitch(outputId: String, controlKey: String) {
        calls += "convenience:$outputId:$controlKey"
    }

    override fun groupOutputs(outputIds: List<String>) {
        calls += "group:${outputIds.joinToString(",")}"
    }

    override fun ungroupOutputs(outputIds: List<String>) {
        calls += "ungroup:${outputIds.joinToString(",")}"
    }

    override fun transferZone(fromZoneOrOutputId: String, toZoneOrOutputId: String) {
        calls += "transfer:$fromZoneOrOutputId:$toZoneOrOutputId"
    }

    override fun playFromHere(zoneOrOutputId: String, queueItemId: Long) {
        calls += "playfromhere:$zoneOrOutputId:$queueItemId"
    }

    override fun pauseAll() {
        calls += "pauseall"
    }

    override fun imageUrl(imageKey: String, width: Int, height: Int, scale: String): String? {
        // Recorded so a test can see the size actually asked of the Core. The
        // fetch that follows cannot succeed against a fake host, but the size
        // is decided before it and that is the part worth pinning.
        calls += "image:$imageKey:${width}x$height"
        return if (!paired) null
        else "http://fake-core:9330/api/image/$imageKey?w=$width&h=$height&s=$scale"
    }

    // ------------------------------------------------------------ BrowseApi

    private val pool = BrowseSessionPool()

    override fun <T> withSession(fn: (String) -> T): T {
        val key = pool.acquire()
        try {
            return fn(key)
        } finally {
            pool.release(key)
        }
    }

    /**
     * Where a session currently sits. Real browse state is per
     * multi_session_key and survives between calls, which is exactly what the
     * offset cache and the album drill rely on.
     */
    private class Cursor {
        var level: List<JSONObject> = emptyList()
        var label: String = "root"
    }

    private val cursors = HashMap<String, Cursor>()

    /** The last term handed to the search hierarchy, per session. */
    private val searchTerms = HashMap<String, String>()

    private fun cursor(key: String) = cursors.getOrPut(key) { Cursor() }

    private fun item(
        title: String,
        subtitle: String = "",
        itemKey: String? = null,
        hint: String? = null,
        imageKey: String? = null
    ): JSONObject = JSONObject()
        .put("title", title)
        .put("subtitle", subtitle)
        .put("item_key", itemKey ?: JSONObject.NULL)
        .put("hint", hint ?: JSONObject.NULL)
        .put("image_key", imageKey ?: JSONObject.NULL)

    private fun albumRows(indexes: List<Int>): List<JSONObject> = indexes.map { i ->
        val a = albums[i]
        item(a.title, a.artist, itemKey = "album:$i", hint = "list", imageKey = a.imageKey)
    }

    private fun listReply(cursor: Cursor): JSONObject = JSONObject()
        .put("action", "list")
        .put("list", JSONObject().put("title", cursor.label).put("count", cursor.level.size))

    override fun browse(opts: JSONObject): JSONObject {
        if (!paired) throw IllegalStateException("Not paired with a Roon Core")
        val key = opts.optString("multi_session_key")
        val hierarchy = opts.optString("hierarchy")
        val c = cursor(key)

        // Roon's "search" hierarchy takes a term through `input` and answers
        // with GROUPS ("Albums", "Artists", ...) that are drilled into.
        if (hierarchy == "search") {
            val input = opts.optString("input").takeIf { it.isNotEmpty() }
            if (input != null) {
                searchTerms[key] = input
                c.level = listOf(item("Albums", itemKey = "searchalbums", hint = "list"))
                c.label = "Search"
                return listReply(c)
            }
            if (opts.optString("item_key") == "searchalbums") {
                val want = searchTerms[key].orEmpty().lowercase()
                c.level = albumRows(
                    albums.indices.filter { albums[it].title.lowercase().contains(want) }
                )
                c.label = "Search / Albums"
                return listReply(c)
            }
            if (opts.optBoolean("pop_all", false)) {
                c.level = emptyList()
                c.label = "Search"
                return listReply(c)
            }
        }

        if (opts.optBoolean("pop_all", false) || !opts.has("item_key")) {
            when (hierarchy) {
                "albums" -> {
                    c.level = albumRows(albums.indices.toList())
                    c.label = "Albums"
                }
                "genres" -> {
                    c.level = genres.entries.map { (name, ids) ->
                        item(name, "${ids.size} Albums", itemKey = "genre:$name", hint = "list")
                    }
                    c.label = "Genres"
                }
                "browse" -> {
                    c.level = listOf(item("Library", itemKey = "library", hint = "list"))
                    c.label = "Browse"
                }
                else -> {
                    c.level = emptyList()
                    c.label = hierarchy
                }
            }
            if (!opts.has("item_key")) return listReply(c)
        }

        val itemKey = opts.optString("item_key").takeIf { it.isNotEmpty() } ?: return listReply(c)
        val zone = opts.optString("zone_or_output_id").takeIf { it.isNotEmpty() }

        when {
            itemKey.startsWith("album:") -> {
                val i = itemKey.removePrefix("album:").toInt()
                val a = albums[i]
                // Roon puts the album's Play menu and its tracks at the same
                // level, both hinted action_list; only the subtitle tells them
                // apart.
                c.level = listOf(item("Play Album", itemKey = "playmenu:$i", hint = "action_list")) +
                    a.tracks.mapIndexed { t, title ->
                        item(title, a.artist, itemKey = "track:$i:$t", hint = "action_list")
                    }
                c.label = a.title
            }
            itemKey.startsWith("playmenu:") || itemKey.startsWith("track:") -> {
                val target = itemKey
                c.level = listOf(
                    item("Play Now", itemKey = "do:play_now:$target", hint = "action"),
                    item("Add Next", itemKey = "do:play_next:$target", hint = "action"),
                    item("Queue", itemKey = "do:queue:$target", hint = "action"),
                    item("Start Radio", itemKey = "do:radio:$target", hint = "action")
                )
                c.label = "Play"
            }
            itemKey.startsWith("do:") -> {
                invoked += itemKey.removePrefix("do:") + (zone?.let { "@$it" } ?: "")
                return JSONObject().put("action", "none")
            }
            itemKey.startsWith("genre:") -> {
                val name = itemKey.removePrefix("genre:")
                c.level = listOf(item("Albums", itemKey = "genrealbums:$name", hint = "list"))
                c.label = name
            }
            itemKey.startsWith("genrealbums:") -> {
                val name = itemKey.removePrefix("genrealbums:")
                c.level = albumRows(genres[name].orEmpty())
                c.label = "$name / Albums"
            }
            itemKey == "library" -> {
                c.level = listOf(item("Tags", itemKey = "tags", hint = "list"))
                c.label = "Library"
            }
            itemKey == "tags" -> {
                c.level = tags.keys.map { item(it, itemKey = "tag:$it", hint = "list") }
                c.label = "Tags"
            }
            itemKey.startsWith("tag:") -> {
                val name = itemKey.removePrefix("tag:")
                c.level = albumRows(tags[name].orEmpty())
                c.label = name
            }
            else -> return JSONObject().put("action", "message")
                .put("message", "No such item: $itemKey").put("is_error", true)
        }
        return listReply(c)
    }

    override fun load(opts: JSONObject): JSONObject {
        if (!paired) throw IllegalStateException("Not paired with a Roon Core")
        val c = cursor(opts.optString("multi_session_key"))
        val offset = opts.optInt("offset", 0)
        val count = opts.optInt("count", 100)
        val slice = if (offset >= c.level.size) emptyList()
        else c.level.subList(offset, minOf(offset + count, c.level.size))
        return JSONObject()
            .put("offset", offset)
            .put("items", JSONArray().also { a -> slice.forEach(a::put) })
            .put("list", JSONObject().put("title", c.label).put("count", c.level.size))
    }
}
