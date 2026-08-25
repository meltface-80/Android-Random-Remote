package com.musicd.lite.library

import com.musicd.lite.Log
import com.musicd.lite.roon.AlbumFilter
import com.musicd.lite.roon.BrowseException
import com.musicd.lite.roon.BrowseItem
import com.musicd.lite.roon.BrowseTree
import com.musicd.lite.roon.matchAction
import com.musicd.lite.store.Store

/**
 * Opening and playing an album.
 *
 * A tile carries the offset its album had when the index was built. A Roon
 * library edit shifts those positions, so the album now sitting at that offset
 * can be a different record entirely — and the album view still LOOKS right,
 * because its header renders from the tile. Every path through here therefore
 * verifies identity before it invokes anything, and climbs a ladder when the
 * check fails: relocate in the snapshot, then resolve live by name, and only
 * then refuse.
 */
class Albums(
    private val tree: BrowseTree,
    private val index: AlbumIndex,
    private val store: Store
) {

    private companion object {
        const val TAG = "Albums"
    }

    /** The album identity a caller believes it is opening. */
    data class Expect(val title: String?, val subtitle: String?) {
        val known: Boolean get() = !title.isNullOrBlank()
    }

    data class Track(val title: String, val subtitle: String)

    data class AlbumView(
        val title: String,
        val subtitle: String,
        val imageKey: String?,
        val tracks: List<Track>,
        val actions: List<BrowseTree.Action>,
        val invoked: String?,
        /** Corrected when the stale-offset defence relocated the album. */
        val offset: Int,
        val libraryMoved: Boolean,
        val partial: Boolean,
        val declaredTracks: Int?
    )

    private class Session(
        val hierarchy: String,
        val albumItem: BrowseItem,
        val items: List<BrowseItem>,
        val playMenu: BrowseItem?,
        val offset: Int,
        val libraryMoved: Boolean,
        val shortRead: Boolean,
        val declared: Int?
    )

    /**
     * A track is an item that is not the play menu, not a subtitle-less submenu
     * ("Add to Library"), and not a section header. No item_key means nothing
     * can be invoked on it, so it cannot be a track — rendering one produces a
     * row that silently does nothing when tapped.
     */
    private fun isTrack(item: BrowseItem, playMenu: BrowseItem?): Boolean {
        if (item === playMenu) return false
        if (item.hint == "action_list" && item.subtitle.isEmpty()) return false
        if (item.hint == "header") return false
        if (item.itemKey == null) return false
        return true
    }

    private fun identityMatches(item: BrowseItem?, expect: Expect): Boolean {
        if (!expect.known) return true          // the caller supplied no identity
        if (item == null) return false
        if (Normalize.text(item.title) != Normalize.text(expect.title)) return false
        // Subtitle is enforced only when supplied — some callers know only the title.
        if (!expect.subtitle.isNullOrBlank() &&
            Normalize.text(item.subtitle) != Normalize.text(expect.subtitle)
        ) return false
        return true
    }

    // ------------------------------------------------------------- resolution

    private fun openSession(
        sessionKey: String,
        requestedOffset: Int,
        filter: AlbumFilter?,
        expect: Expect
    ): Session {
        // Navigate to the album list this offset belongs to. Decade offsets are
        // full-library positions, so they resolve against the whole library.
        val navFilter = if (filter?.type == AlbumFilter.DECADE) null else filter
        val nav = tree.navigateToAlbumList(sessionKey, navFilter)
        var hierarchy = nav.hierarchy

        // Roon's LIVE album count, already fetched by the navigation above.
        // Against the snapshot's count it is free proof — available at the exact
        // moment a user hits a failure — that the library has CHANGED. Past
        // tense on purpose: it does not show an import is running now.
        val libraryMoved = navFilter == null && index.count > 0 &&
            nav.total != (if (index.declared > 0) index.declared else index.count)

        var offset = requestedOffset
        var albumItem = tree.load(hierarchy, sessionKey, offset, 1).items.firstOrNull()
            ?: throw BrowseException("No album at offset $offset", stale = true)

        if (!identityMatches(albumItem, expect)) {
            var relocated: BrowseItem? = null
            if (navFilter == null) {
                val hit = index.relocate(expect.title, expect.subtitle)
                if (hit != null && hit.offset != offset) {
                    val retry = tree.load(hierarchy, sessionKey, hit.offset, 1).items.firstOrNull()
                    if (identityMatches(retry, expect)) {
                        relocated = retry
                        offset = hit.offset
                    }
                }
            }
            if (relocated != null) {
                Log.d(TAG, "stale offset $requestedOffset relocated to $offset for ${expect.title}")
                albumItem = relocated
            } else {
                // The snapshot itself is stale, which is expected when it has
                // not refreshed since a Roon import. Resolve the album LIVE by
                // name through Roon's own search: offset-free, always current,
                // and a single-album lookup rather than a library scan, so it is
                // safe even mid-import.
                val live = if (expect.known) searchForAlbum(sessionKey, expect) else null
                if (live != null) {
                    Log.d(TAG, "stale offset $requestedOffset resolved live for ${expect.title}")
                    hierarchy = live.first
                    albumItem = live.second
                } else {
                    throw BrowseException(
                        "The library just changed and this album moved — close and reopen it.",
                        stale = true
                    )
                }
            }
        }

        val drill = tree.browse(hierarchy, sessionKey, itemKey = albumItem.itemKey)
        tree.requireList(drill, "this album")

        val inside = tree.load(hierarchy, sessionKey, 0, BrowseTree.ALBUM_CONTENTS_MAX)
        val items = inside.items
        // What Roon said the level HOLDS versus what it handed over. The two
        // differ while the Core is re-indexing. It is the only thing that tells
        // a three-track album apart from three tracks of a twelve-track one.
        val declared = inside.total.takeIf { it > 0 }
        val shortRead = declared != null && items.size < declared
        if (shortRead) {
            Log.w(TAG, "short read for ${albumItem.title}: declared $declared, got ${items.size}")
        }

        // In the "albums" hierarchy BOTH the Play action and each track come
        // back as "action_list" — tapping a track opens its own submenu. They
        // are told apart by the subtitle: tracks carry an artist credit,
        // submenu actions do not.
        val playMenu = items.firstOrNull {
            it.hint == "action_list" && it.subtitle.isEmpty() && it.title.startsWith("play", true)
        } ?: items.firstOrNull { it.hint == "action_list" && it.subtitle.isEmpty() }

        // Roon's own contents for this album, recorded under its identity from
        // ordinary use at no extra Roon cost. NEVER on a short read: this
        // replaces an album's rows wholesale, so recording three tracks of a
        // twelve-track album while the Core re-indexes would destroy the correct
        // record. A partial answer is not evidence about an album's contents.
        if (!shortRead) {
            runCatching {
                val key = AlbumRecord(0, albumItem.title, albumItem.subtitle, null).key
                store.putAlbumTracks(
                    key,
                    items.filter { isTrack(it, playMenu) }.map { Normalize.stripTrackNumber(it.title) }
                )
            }
        }

        return Session(hierarchy, albumItem, items, playMenu, offset, libraryMoved, shortRead, declared)
    }

    /**
     * Find an album through Roon's own search, for when every offset we hold is
     * stale. Returns the hierarchy the item lives in along with the item.
     */
    private fun searchForAlbum(sessionKey: String, expect: Expect): Pair<String, BrowseItem>? {
        val title = expect.title ?: return null
        return try {
            val hierarchy = "search"
            tree.browse(hierarchy, sessionKey, popAll = true)
            tree.browse(hierarchy, sessionKey, input = title)
            val level = tree.loadLevel(hierarchy, sessionKey, 200)
            // Search results are grouped under headings ("Albums", "Artists").
            val albumsGroup = level.items.firstOrNull { it.title.trim().equals("albums", true) }
            val candidates = if (albumsGroup?.itemKey != null) {
                tree.browse(hierarchy, sessionKey, itemKey = albumsGroup.itemKey)
                tree.loadLevel(hierarchy, sessionKey, 200).items
            } else {
                level.items
            }
            candidates.firstOrNull { identityMatches(it, expect) }?.let { hierarchy to it }
        } catch (e: Exception) {
            Log.d(TAG, "live search for ${expect.title} failed: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ verbs

    /**
     * Open an album, and optionally invoke one of its actions in one pass —
     * which is what "Play now" does: it must not re-walk the tree between
     * reading the menu and pressing the button, because the item_keys it read
     * are only valid inside that session.
     */
    fun open(
        offset: Int,
        zoneOrOutputId: String?,
        invokeKind: String?,
        filter: AlbumFilter?,
        expect: Expect
    ): AlbumView = tree.withSession { sessionKey ->
        val s = openSession(sessionKey, offset, filter, expect)

        val tracks = s.items.filter { isTrack(it, s.playMenu) }
            .map { Track(Normalize.stripTrackNumber(it.title), it.subtitle) }

        val actions =
            if (s.playMenu != null) tree.drillActionMenu(s.hierarchy, sessionKey, s.playMenu.itemKey)
            else emptyList()

        var invoked: String? = null
        if (invokeKind != null) {
            val action = matchAction(actions, invokeKind)
                ?: throw noAction(invokeKind, actions, "this album", s.libraryMoved)
            requireNotNull(zoneOrOutputId) { "zone_or_output_id is required to invoke an action" }
            tree.invoke(s.hierarchy, sessionKey, action, zoneOrOutputId)
            invoked = action.title
        }

        AlbumView(
            title = s.albumItem.title,
            subtitle = s.albumItem.subtitle,
            imageKey = s.albumItem.imageKey,
            tracks = tracks,
            actions = actions,
            invoked = invoked,
            offset = s.offset,
            libraryMoved = s.libraryMoved,
            partial = s.shortRead,
            declaredTracks = s.declared
        )
    }

    /**
     * Play or queue ONE track. [trackIndex] is a position in the same filtered
     * list [open] returns, and the tap's title is verified against the
     * re-resolved list: if the library changed since the modal opened, the
     * track is re-matched by title rather than firing whatever now sits at that
     * index.
     */
    fun invokeTrack(
        offset: Int,
        trackIndex: Int,
        trackTitle: String?,
        zoneOrOutputId: String,
        kind: String,
        filter: AlbumFilter?,
        expect: Expect
    ): Pair<String, String> = tree.withSession { sessionKey ->
        val s = openSession(sessionKey, offset, filter, expect)
        val trackItems = s.items.filter { isTrack(it, s.playMenu) }

        val wanted = Normalize.text(trackTitle)
        var item = trackItems.getOrNull(trackIndex)
        if (item == null ||
            (wanted.isNotEmpty() && Normalize.text(Normalize.stripTrackNumber(item.title)) != wanted)
        ) {
            item = if (wanted.isNotEmpty()) {
                trackItems.firstOrNull {
                    Normalize.text(Normalize.stripTrackNumber(it.title)) == wanted
                }
            } else null
        }
        if (item == null) {
            throw BrowseException("Track list changed — close and reopen the album", stale = true)
        }

        // Tapping a track opens its own submenu — the same drill as the album's.
        val actions = tree.drillActionMenu(s.hierarchy, sessionKey, item.itemKey)
        val action = matchAction(actions, kind)
            ?: throw noAction(kind, actions, "this track", false)
        tree.invoke(s.hierarchy, sessionKey, action, zoneOrOutputId)
        action.title to Normalize.stripTrackNumber(item.title)
    }

    /**
     * Two different facts, and they must not share one string: Roon offered
     * nothing, or Roon offered something else.
     */
    private fun noAction(
        kind: String,
        actions: List<BrowseTree.Action>,
        what: String,
        libraryMoved: Boolean
    ): BrowseException {
        if (actions.isEmpty()) {
            return if (libraryMoved) BrowseException(
                "Roon offered no playback options for $what. Its album count no longer matches " +
                    "what this app last scanned, so the library changed — a rescan is due.",
                stale = true
            ) else BrowseException("Roon offered no playback options for $what.")
        }
        return BrowseException(
            "Roon has no \"$kind\" option for $what. It offered: " +
                actions.joinToString(", ") { it.title }
        )
    }
}
