package com.musicd.lite.roon

import com.musicd.lite.Log
import com.musicd.lite.library.Normalize
import org.json.JSONObject
import java.util.ArrayDeque

/** One row of a browse level. */
data class BrowseItem(
    val title: String,
    val subtitle: String,
    val imageKey: String?,
    val itemKey: String?,
    /** null | "action" | "action_list" | "list" | "header" — treat unknown as null. */
    val hint: String?
) {
    companion object {
        fun parse(o: JSONObject): BrowseItem = BrowseItem(
            title = o.optString("title"),
            subtitle = o.optString("subtitle"),
            imageKey = o.optString("image_key").takeIf { it.isNotEmpty() },
            itemKey = o.optString("item_key").takeIf { it.isNotEmpty() },
            hint = o.optString("hint").takeIf { it.isNotEmpty() }
        )
    }
}

/** The result of a `load`: one page of a level, plus what the level holds. */
data class BrowseLevel(
    val items: List<BrowseItem>,
    /** What Roon says the whole level holds, which is not always what arrived. */
    val total: Int
)

/**
 * Errors Roon itself explained. A browse response can come back with action
 * "message" carrying the Core's own words, and discarding that is how a user
 * asking "why can't I play this?" gets told about a protocol instead of the
 * reason.
 */
class BrowseException(message: String, val stale: Boolean = false) : RuntimeException(message)

/**
 * The Roon browse calls, behind an interface so every walker in this package
 * can be exercised against a scripted Core in the unit tests.
 */
interface BrowseApi {
    fun browse(opts: JSONObject): JSONObject
    fun load(opts: JSONObject): JSONObject

    /**
     * Run [fn] with a browse session key checked out of the pool.
     *
     * Roon keeps server-side state for every multi_session_key for as long as
     * the extension stays connected, so keys are pooled rather than minted per
     * operation: the number of sessions the Core ever holds equals the peak
     * number of simultaneous operations, not the number ever run. Reuse is safe
     * because every operation begins by re-navigating its hierarchy (pop_all),
     * which discards leftover state on that key.
     */
    fun <T> withSession(fn: (String) -> T): T
}

/** Checks browse session keys in and out. Not tied to a socket, so it survives reconnects. */
class BrowseSessionPool {
    private val free = ArrayDeque<String>()
    private var seq = 0

    @Synchronized
    fun acquire(): String = free.pollLast() ?: "mdl_s${++seq}"

    /**
     * Called exactly once per acquire. Releasing a key twice would let two
     * concurrent operations share a session and corrupt each other's state.
     */
    @Synchronized
    fun release(key: String) {
        free.addLast(key)
    }
}

/**
 * Walkers over the browse tree.
 *
 * The Browse API has no native Focus, so filtering works by navigating to a
 * list that already contains only the wanted albums:
 *   - genre: hierarchy "genres" -> [genre] -> its "Albums" child list
 *   - tag:   hierarchy "browse" -> Library -> Tags -> [tag] (-> "Albums" child
 *            when the tag mixes item types)
 *
 * Roon's exact tree labels are not formally documented, so children are
 * discovered by title at runtime and a miss fails with a descriptive error
 * rather than a silent empty list.
 */
class BrowseTree(private val api: BrowseApi) {

    companion object {
        private const val TAG = "Browse"
        const val PAGE = 100

        /**
         * An album's contents. 500 covers a box set; Roon pages above that and
         * the album view has never needed a second page.
         */
        const val ALBUM_CONTENTS_MAX = 500
    }

    /**
     * An item's OFFSET within a browse list, keyed by a navigation context
     * ("genres:root", "tags:root"). item_keys are session-scoped and must never
     * be cached across operations, but an item's position in its
     * alphabetically-stable list is reusable until the library changes. This is
     * what makes playing a genre fast: one round-trip at the remembered offset
     * with a title check, instead of paging through thousands of entries. A
     * stale entry can only cost a slower miss and a fallback scan, never the
     * wrong item.
     */
    private val offsetCache = HashMap<String, HashMap<String, Int>>()

    @Synchronized
    fun clearOffsetCache() = offsetCache.clear()

    @Synchronized
    private fun cacheFor(context: String): HashMap<String, Int> =
        offsetCache.getOrPut(context) { HashMap() }

    /** Run [fn] with a pooled browse session key. See [BrowseApi.withSession]. */
    fun <T> withSession(fn: (String) -> T): T = api.withSession(fn)

    // ------------------------------------------------------------ primitives

    fun browse(
        hierarchy: String,
        sessionKey: String,
        itemKey: String? = null,
        popAll: Boolean = false,
        zoneOrOutputId: String? = null,
        input: String? = null
    ): JSONObject {
        val opts = JSONObject()
            .put("hierarchy", hierarchy)
            .put("multi_session_key", sessionKey)
        if (popAll) opts.put("pop_all", true)
        if (itemKey != null) opts.put("item_key", itemKey)
        if (zoneOrOutputId != null) opts.put("zone_or_output_id", zoneOrOutputId)
        if (input != null) opts.put("input", input)
        return api.browse(opts)
    }

    fun load(hierarchy: String, sessionKey: String, offset: Int = 0, count: Int = PAGE): BrowseLevel {
        val body = api.load(
            JSONObject()
                .put("hierarchy", hierarchy)
                .put("multi_session_key", sessionKey)
                .put("offset", offset)
                .put("count", count)
        )
        val arr = body.optJSONArray("items")
        val items = if (arr == null) emptyList() else
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(BrowseItem::parse) }
        val total = body.optJSONObject("list")?.optInt("count", 0) ?: 0
        return BrowseLevel(items, total)
    }

    /**
     * Roon answered, but not with a list. When the action is "message" the Core
     * has said why in its own words, which beats anything we could infer.
     */
    fun requireList(body: JSONObject, what: String) {
        if (body.optString("action") == "list") return
        if (body.optString("action") == "message") {
            val msg = body.optString("message").ifEmpty { "Roon declined" }
            throw BrowseException("Roon says: $msg")
        }
        throw BrowseException("Roon gave no list for $what (action: ${body.optString("action")})")
    }

    // ----------------------------------------------------------- level walks

    /** Load every item at the current level. For small lists: genres, tags, children. */
    fun loadLevel(hierarchy: String, sessionKey: String, max: Int = 2000): BrowseLevel {
        val out = ArrayList<BrowseItem>()
        var total = 0
        var off = 0
        while (off < max) {
            val page = load(hierarchy, sessionKey, off, PAGE)
            total = page.total
            out += page.items
            if (page.items.isEmpty() || off + PAGE >= total) break
            off += PAGE
        }
        return BrowseLevel(out, total)
    }

    /**
     * Page through the current level looking for an item whose title matches,
     * case-insensitively. [context] enables the offset cache described above.
     */
    fun findItemByTitle(
        hierarchy: String,
        sessionKey: String,
        title: String,
        maxScan: Int = 3000,
        context: String? = null
    ): BrowseItem? {
        val want = title.trim().lowercase()
        if (want.isEmpty()) return null
        val cache = context?.let(::cacheFor)

        // Fast path: jump to the remembered position and confirm the title.
        val hint = cache?.get(want)
        if (hint != null) {
            try {
                val one = load(hierarchy, sessionKey, hint, 1).items.firstOrNull()
                if (one != null && one.title.trim().lowercase() == want) return one
            } catch (e: Exception) {
                Log.d(TAG, "offset hint $hint for '$want' did not load: ${e.message}")
            }
            synchronized(this) { cache.remove(want) }   // it moved — rescan
        }

        var off = 0
        while (off < maxScan) {
            val page = load(hierarchy, sessionKey, off, PAGE)
            page.items.forEachIndexed { i, item ->
                val t = item.title.trim().lowercase()
                if (cache != null && t.isNotEmpty()) synchronized(this) { cache[t] = off + i }
            }
            page.items.firstOrNull { it.title.trim().lowercase() == want }?.let { return it }
            if (page.items.isEmpty() || off + PAGE >= page.total) break
            off += PAGE
        }
        return null
    }

    // ------------------------------------------------------------ navigation

    /** Where an album list lives, and how many albums it holds. */
    data class AlbumList(val hierarchy: String, val total: Int)

    /**
     * Navigate the session to the level that lists albums for [filter], leaving
     * it positioned there.
     */
    fun navigateToAlbumList(sessionKey: String, filter: AlbumFilter?): AlbumList {
        if (filter == null || filter.type == AlbumFilter.DECADE) {
            // A decade has no Roon list of its own: those offsets are
            // full-library positions resolved against the whole album list.
            browse("albums", sessionKey, popAll = true)
            val head = load("albums", sessionKey, 0, 1)
            return AlbumList("albums", head.total)
        }

        return when (filter.type) {
            AlbumFilter.GENRE -> navigateGenre(sessionKey, filter)
            AlbumFilter.TAG -> navigateTag(sessionKey, filter)
            else -> throw BrowseException("Unknown filter type: ${filter.type}")
        }
    }

    private fun navigateGenre(sessionKey: String, filter: AlbumFilter): AlbumList {
        val hierarchy = "genres"
        browse(hierarchy, sessionKey, popAll = true)

        // An optional parent drills into the parent genre first, then finds the
        // sub-genre by title inside it (Pop/Rock -> Heavy Metal).
        if (!filter.parent.isNullOrBlank()) {
            val parent = findItemByTitle(hierarchy, sessionKey, filter.parent, 3000, "genres:root")
                ?: throw BrowseException("Parent genre \"${filter.parent}\" not found")
            browse(hierarchy, sessionKey, itemKey = parent.itemKey)
        }

        // Top-level genres share one list; a sub-genre lives in its parent's
        // child list, so its offset cache is namespaced by that parent.
        val ctx = if (filter.parent.isNullOrBlank()) "genres:root"
        else "genres:parent:${Normalize.text(filter.parent)}"
        val genre = findItemByTitle(hierarchy, sessionKey, filter.value, 3000, ctx)
            ?: throw BrowseException("Genre \"${filter.value}\" not found")
        browse(hierarchy, sessionKey, itemKey = genre.itemKey)
        return intoAlbumsChild(hierarchy, sessionKey, "genre \"${filter.value}\"", requireChild = true)
    }

    private fun navigateTag(sessionKey: String, filter: AlbumFilter): AlbumList {
        val hierarchy = "browse"
        browse(hierarchy, sessionKey, popAll = true)
        val lib = findItemByTitle(hierarchy, sessionKey, "Library", 50)
            ?: throw BrowseException("Couldn't find \"Library\" in the Roon browse tree")
        browse(hierarchy, sessionKey, itemKey = lib.itemKey)
        val tags = findItemByTitle(hierarchy, sessionKey, "Tags", 100)
            ?: throw BrowseException("Couldn't find \"Tags\" under Library")
        browse(hierarchy, sessionKey, itemKey = tags.itemKey)
        val tag = findItemByTitle(hierarchy, sessionKey, filter.value, 3000, "tags:root")
            ?: throw BrowseException("Tag \"${filter.value}\" not found")
        val into = browse(hierarchy, sessionKey, itemKey = tag.itemKey)
        val fallback = into.optJSONObject("list")?.optInt("count", 0) ?: 0
        return intoAlbumsChild(hierarchy, sessionKey, "tag \"${filter.value}\"", false, fallback)
    }

    /**
     * A genre or tag may list albums directly, or nest them under an "Albums"
     * child when it mixes item types. Handles both, leaving the session on the
     * album level either way.
     */
    private fun intoAlbumsChild(
        hierarchy: String,
        sessionKey: String,
        what: String,
        requireChild: Boolean,
        flatFallbackTotal: Int = 0
    ): AlbumList {
        val level = loadLevel(hierarchy, sessionKey, 300)
        val albumsChild = level.items.firstOrNull { it.title.trim().equals("albums", true) }
        if (albumsChild == null) {
            if (requireChild) {
                throw BrowseException(
                    "Couldn't find an \"Albums\" list inside $what. Level contains: " +
                        level.items.take(12).joinToString(", ") { it.title }
                )
            }
            // Flat list: the level itself holds the albums, and it is still the
            // session's current level, so loading by offset re-reads it fine.
            val total = if (level.total > 0) level.total
            else if (flatFallbackTotal > 0) flatFallbackTotal
            else level.items.size
            return AlbumList(hierarchy, total)
        }
        val into = browse(hierarchy, sessionKey, itemKey = albumsChild.itemKey)
        var total = into.optJSONObject("list")?.optInt("count", 0) ?: 0
        if (total == 0) total = load(hierarchy, sessionKey, 0, 1).total
        return AlbumList(hierarchy, total)
    }

    // --------------------------------------------------------------- actions

    /** One entry of an album's or track's Play menu. */
    data class Action(val itemKey: String?, val title: String, val hint: String?, val kind: String)

    /**
     * Drill into an action_list item and return its classified actions.
     *
     * The action check guards against a non-list response — without it the
     * follow-up load would re-read the CURRENT level, and a caller could
     * "invoke" a misclassified item and report false success.
     */
    fun drillActionMenu(hierarchy: String, sessionKey: String, itemKey: String?): List<Action> {
        if (itemKey == null) return emptyList()
        val d = browse(hierarchy, sessionKey, itemKey = itemKey)
        requireList(d, "this menu")
        return loadLevel(hierarchy, sessionKey, PAGE).items.map {
            Action(it.itemKey, it.title, it.hint, classifyAction(it.title))
        }
    }

    fun invoke(hierarchy: String, sessionKey: String, action: Action, zoneOrOutputId: String) {
        browse(hierarchy, sessionKey, itemKey = action.itemKey, zoneOrOutputId = zoneOrOutputId)
    }
}

/** A genre / tag / decade constraint on an album list. */
data class AlbumFilter(val type: String, val value: String, val parent: String? = null) {
    companion object {
        const val GENRE = "genre"
        const val TAG = "tag"
        const val DECADE = "decade"

        private val TYPES = setOf(GENRE, TAG, DECADE)

        /**
         * Read a filter from query params or a POST body. Returns null when
         * there isn't one, which every caller treats as "the whole library".
         *
         * Note the deliberate absence of "label": the lite build has no label
         * index, and accepting the type would navigate to a list it can never
         * populate.
         */
        fun parse(type: String?, value: String?, parent: String?): AlbumFilter? {
            val t = type?.trim().orEmpty()
            val v = value?.trim().orEmpty()
            if (t.isEmpty() || v.isEmpty()) return null
            if (t !in TYPES) return null
            return AlbumFilter(t, v, parent?.trim()?.takeIf { it.isNotEmpty() && t == GENRE })
        }
    }
}

/** Roon's own vocabulary for the entries of a Play menu. */
fun classifyAction(title: String?): String {
    val t = (title ?: "").lowercase()
    return when {
        Regex("play\\s*now").containsMatchIn(t) -> "play_now"
        Regex("add\\s*next|play\\s*next").containsMatchIn(t) -> "play_next"
        t.contains("queue") -> "queue"
        t.contains("shuffle") -> "shuffle"
        t.contains("radio") -> "radio"
        else -> "other"
    }
}

fun matchAction(actions: List<BrowseTree.Action>, kind: String): BrowseTree.Action? =
    actions.firstOrNull { it.kind == kind }
        ?: if (kind == "play_now") actions.firstOrNull { it.title.startsWith("play", true) } else null
