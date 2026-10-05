package com.musicd.lite.library

import com.musicd.lite.str
import com.musicd.lite.store.Store
import org.json.JSONArray
import org.json.JSONObject

/**
 * Listen later — albums put aside to play another time (Rouen v1.8.67).
 *
 * Roon's own Listen later cannot be reached from an extension, so this is the
 * app's own list, kept beside the user playlists in the settings store.
 *
 * WHAT AN ENTRY IS. An album identity — the same title-and-artist key the play
 * history uses — plus the strings it was put aside under. Never an offset,
 * which is a position in a list that reshuffles on every library change. An
 * entry is resolved against the snapshot when it is READ, so it follows its
 * album through a rescan.
 *
 * HOW AN ENTRY LEAVES. By hand, or once every track of the album has been
 * played since it was put aside — on any zone, from any app, as long as this
 * app was running to see it. "Every track" is the album's track list as Roon
 * gave it the last time the album was opened here; for an album this app has
 * never opened the list cannot tell a played-through album from a sampled one,
 * so the entry stays until it is taken off by hand rather than leaving on a
 * guess.
 *
 * One honest difference from Rouen: its plays table marks a track COMPLETED,
 * and this one records a track STARTING (Roon has no "finished" event for an
 * extension; Rouen infers one from the seek position). So here an album counts
 * as played through when every one of its tracks has started since it was put
 * aside. Skipping through a whole album would take it off the list too.
 */
class ListenLater(
    private val store: Store,
    private val index: AlbumIndex,
    /** Told after every change, so the live `later` revision moves. */
    private val onChange: () -> Unit = {}
) {

    companion object {
        const val KEY = "listen_later"

        /** Enough for a long backlog; a bound so a runaway client cannot grow it forever. */
        const val MAX_ENTRIES = 500

        /** Where an entry came from. Rouen's vocabulary, so the page's wording applies. */
        val SOURCES = setOf("album", "picks")

        /** The identity every other part of the app keys an album on. */
        fun keyOf(title: String, artist: String): String = AlbumRecord(0, title, artist, null).key
    }

    data class Entry(
        val key: String,
        val title: String,
        val artist: String,
        val source: String,
        val addedAt: Long
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("key", key)
            .put("title", title)
            .put("artist", artist)
            .put("source", source)
            .put("ts", addedAt)
    }

    private val lock = Any()

    /** Newest first: "when it was first put aside". */
    fun entries(): List<Entry> = synchronized(lock) { read() }

    private fun read(): List<Entry> {
        val raw = store.setting(KEY) ?: return emptyList()
        val arr = runCatching { JSONObject(raw).optJSONArray("albums") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.str("title").trim()
            if (title.isEmpty()) continue
            val artist = o.str("artist").trim()
            out += Entry(
                key = o.str("key").ifEmpty { keyOf(title, artist) },
                title = title,
                artist = artist,
                source = o.str("source").takeIf { it in SOURCES } ?: "album",
                addedAt = o.optLong("ts", 0L)
            )
        }
        return out.sortedByDescending { it.addedAt }
    }

    private fun write(entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries.take(MAX_ENTRIES)) arr.put(e.toJson())
        store.putSetting(KEY, JSONObject().put("albums", arr).toString())
        runCatching { onChange() }
    }

    /** The library album an entry stands for right now, or null. */
    fun record(e: Entry): AlbumRecord? =
        index.relocate(e.title, e.artist) ?: index.relocate(e.title, null)?.takeIf { e.artist.isEmpty() }

    /**
     * Every key that names the same album as [title]/[artist]: its own, and —
     * when it resolves to a library record — that record's, so an entry stored
     * under one spelling is found from Roon's and the other way round.
     */
    private fun keysFor(title: String, artist: String): Set<String> {
        val keys = HashSet<String>(2)
        val own = keyOf(title, artist)
        if (own.isNotBlank()) keys += own
        index.relocate(title, artist)?.let { keys += it.key }
        return keys
    }

    private fun matches(e: Entry, want: Set<String>): Boolean =
        e.key in want || record(e)?.key?.let { it in want } == true

    fun has(title: String, artist: String): Boolean {
        val want = keysFor(title, artist)
        if (want.isEmpty()) return false
        return synchronized(lock) { read().any { matches(it, want) } }
    }

    /**
     * Put an album aside. True when it is on the list afterwards — whether or
     * not it already was. An album already there keeps its original date: a
     * second tap must not move it to the front.
     */
    fun add(title: String, artist: String, source: String = "album", now: Long = System.currentTimeMillis()): Boolean {
        val t = title.trim().take(300)
        val a = artist.trim().take(300)
        if (t.isEmpty()) return false
        val key = keyOf(t, a)
        if (key.isBlank()) return false
        val want = keysFor(t, a)
        synchronized(lock) {
            val list = read()
            if (list.any { matches(it, want) }) return true
            if (list.size >= MAX_ENTRIES) return false
            write(listOf(Entry(key, t, a, source.takeIf { it in SOURCES } ?: "album", now)) + list)
        }
        return true
    }

    /** Take an album off — every entry that names it. Returns how many went. */
    fun remove(title: String, artist: String): Int {
        val want = keysFor(title.trim(), artist.trim())
        if (want.isEmpty()) return 0
        synchronized(lock) {
            val list = read()
            val keep = list.filterNot { matches(it, want) }
            val gone = list.size - keep.size
            if (gone > 0) write(keep)
            return gone
        }
    }

    /**
     * "Not for me" on a Smart Pick means the artist, everywhere: every entry
     * the picks put here for them goes too. An album put aside from the album
     * view stays — that was the user's own find, not a suggestion.
     */
    fun forgetPickArtist(artist: String): Int {
        val canon = Normalize.text(artist)
        if (canon.isEmpty()) return 0
        synchronized(lock) {
            val list = read()
            val keep = list.filterNot { e ->
                e.source == "picks" && Normalize.splitArtists(e.artist).any { it.normalized == canon } ||
                    e.source == "picks" && Normalize.text(e.artist) == canon
            }
            val gone = list.size - keep.size
            if (gone > 0) write(keep)
            return gone
        }
    }

    /**
     * A track of [albumTitle] started playing. Only an entry whose album has
     * that title is examined, so the play history is read only when it could
     * end in a removal — not on every track of every evening.
     */
    fun noticePlay(albumTitle: String, artist: String, now: Long = System.currentTimeMillis()): Int {
        val want = Normalize.text(albumTitle)
        if (want.isEmpty()) return 0
        synchronized(lock) {
            val list = read()
            val candidates = list.filter { e ->
                Normalize.text(record(e)?.title ?: e.title) == want
            }
            if (candidates.isEmpty()) return 0
            val done = candidates.filter { playedThrough(it) }.map { it.key }.toSet()
            if (done.isEmpty()) return 0
            write(list.filterNot { it.key in done })
            return done.size
        }
    }

    /**
     * Has every track of [e]'s album started since it was put aside? False
     * whenever it cannot be shown — including an album whose track list was
     * never recorded.
     */
    private fun playedThrough(e: Entry): Boolean {
        val rec = record(e)
        val title = rec?.title ?: e.title
        val artist = rec?.subtitle ?: e.artist
        val tracks = store.albumTracks(keyOf(title, artist))
            .map { Normalize.text(Normalize.stripTrackNumber(it)) }
            .filter { it.isNotEmpty() }
        if (tracks.isEmpty()) return false
        val need = tracks.toMutableSet()
        val wantAlbum = Normalize.text(title)
        for (p in store.playsSince(e.addedAt)) {
            if (Normalize.text(p.album) != wantAlbum) continue
            need.remove(Normalize.text(Normalize.stripTrackNumber(p.track)))
            if (need.isEmpty()) return true
        }
        return false
    }
}
