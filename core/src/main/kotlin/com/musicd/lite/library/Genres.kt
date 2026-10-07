package com.musicd.lite.library

import com.musicd.lite.Log
import com.musicd.lite.roon.AlbumFilter
import com.musicd.lite.roon.RoonApi
import com.musicd.lite.store.Store

/**
 * Where the Library's Focus gets genres from.
 *
 * Rouen harvests every album's genres during its library sync. This build does
 * not, so the genres come from where Roon keeps them: its own genre list, and
 * each genre's album list when one is chosen.
 */
interface GenreSource {
    /** The genres to offer, with Roon's album count for each; null when they can't be read now. */
    fun genres(): List<Pair<String, Int>>?

    /** The keys of the albums filed under [genre]; null when that can't be read now. */
    fun members(genre: String): Set<String>?

    /** How many albums have any genre at all, when that is known without a walk. */
    fun known(): Int? = null
}

/** Genres recorded in the store — none, today, but the shape a harvest would fill. */
class StoredGenres(private val store: Store) : GenreSource {
    override fun genres(): List<Pair<String, Int>> {
        val counts = HashMap<String, Int>()
        val spelling = HashMap<String, String>()
        for ((_, gs) in store.albumGenresAll()) {
            for (g in gs.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { Normalize.text(it) }) {
                val k = Normalize.text(g)
                spelling.putIfAbsent(k, g)
                counts[k] = (counts[k] ?: 0) + 1
            }
        }
        return counts.map { (k, n) -> spelling.getValue(k) to n }
    }

    override fun known(): Int = store.albumGenresAll().count { (_, gs) -> gs.isNotEmpty() }

    override fun members(genre: String): Set<String> {
        val want = Normalize.text(genre)
        return store.albumGenresAll().filterValues { gs -> gs.any { Normalize.text(it) == want } }.keys
    }
}

/**
 * Roon's own genres. The list is one browse; a genre's albums are a walk of its
 * album list, matched to the library by title and artist — the same identity
 * the album index keys everything on. Both are kept until the library changes
 * (the index's generation moves), so the wall's later pages, and the next
 * Focus on the same genre, cost nothing.
 *
 * Always called from an HTTP thread, never the main one: these block on the Core.
 */
class RoonGenres(private val roon: () -> RoonApi, private val index: AlbumIndex) : GenreSource {

    private companion object {
        const val TAG = "Genres"
        /** A genre with more albums than this is read this far and no further. */
        const val MAX_ALBUMS = 20_000
        val COUNT = Regex("\\d[\\d,. ]*")
    }

    private val lock = Any()
    private var generation = -1L
    private var list: List<Pair<String, Int>>? = null
    private val members = HashMap<String, Set<String>>()

    private fun current() {
        if (index.generation != generation) {
            generation = index.generation
            list = null
            members.clear()
        }
    }

    override fun genres(): List<Pair<String, Int>>? {
        synchronized(lock) { current(); list?.let { return it } }
        val api = roon()
        if (!api.isPaired) return null
        val read = try {
            api.tree.withSession { key ->
                api.tree.browse("genres", key, popAll = true)
                api.tree.loadLevel("genres", key, 1000).items
            }.filter { it.hint != "header" && it.title.isNotBlank() }.map { item ->
                val n = COUNT.find(item.subtitle)?.value?.replace(Regex("[,. ]"), "")?.toIntOrNull() ?: 0
                item.title.trim() to n
            }
        } catch (e: Exception) {
            Log.w(TAG, "genre list: ${e.message}", e)
            return null
        }
        synchronized(lock) { current(); list = read }
        return read
    }

    override fun members(genre: String): Set<String>? {
        val k = Normalize.text(genre)
        synchronized(lock) { current(); members[k]?.let { return it } }
        val api = roon()
        if (!api.isPaired) return null
        val keys = try {
            api.tree.withSession { key ->
                val at = api.tree.navigateToAlbumList(key, AlbumFilter(AlbumFilter.GENRE, genre, null))
                api.tree.loadLevel(at.hierarchy, key, minOf(at.total, MAX_ALBUMS)).items
            }.mapTo(HashSet()) { AlbumRecord(0, it.title, it.subtitle, null).key }
        } catch (e: Exception) {
            Log.w(TAG, "albums in $genre: ${e.message}", e)
            return null
        }
        synchronized(lock) { current(); members[k] = keys }
        return keys
    }
}
