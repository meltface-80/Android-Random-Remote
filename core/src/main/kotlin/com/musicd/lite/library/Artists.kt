package com.musicd.lite.library

/**
 * The library's artists, derived from its albums.
 *
 * There is no artist index and there does not need to be one: every album
 * record already carries [AlbumRecord.artistNames], split by the app's one
 * rule — "A / B", "feat.", "ft." — so the artists ARE the albums, read a
 * different way. Building the list is a walk over what is already in memory.
 *
 * Grouped by the normalised name, not the printed one, so "The xx" and "THE
 * XX" are one artist rather than two. The name shown is the one that appears
 * most often, because a single oddly-cased tag should not decide how a name
 * reads on screen; ties go to whichever came first, so the answer is stable
 * between builds of the same library.
 */
object Artists {

    data class Entry(
        val name: String,
        val normalized: String,
        /** How many albums credit this artist. */
        val albums: Int,
        /** A cover to put on the tile — see [of] for which one. */
        val imageKey: String?
    )

    /** Sort orders the artists screen offers. */
    const val AZ = "az"
    const val ZA = "za"
    const val RANDOM = "random"

    /**
     * Every artist credited on an album, once each.
     *
     * The cover is the one from their alphabetically first album, chosen that
     * way for one reason: it has to be the SAME cover every time the screen is
     * drawn. Picking "their newest" or "whichever came first in the index"
     * would change as the library grows, and a wall of faces that reshuffles
     * itself between visits is a wall you cannot learn.
     */
    fun of(albums: List<AlbumRecord>): List<Entry> {
        class Acc(val first: String) {
            var albums = 0
            val names = LinkedHashMap<String, Int>()
            var coverTitle: String? = null
            var coverKey: String? = null
        }

        val byNorm = LinkedHashMap<String, Acc>()
        for (al in albums) {
            for (artist in al.artistNames) {
                if (artist.normalized.isEmpty()) continue
                val acc = byNorm.getOrPut(artist.normalized) { Acc(artist.name) }
                acc.albums++
                acc.names[artist.name] = (acc.names[artist.name] ?: 0) + 1
                // Alphabetically first album with art. An album with no cover
                // never displaces one that has a picture to offer.
                if (al.imageKey != null &&
                    (acc.coverTitle == null || al.sortTitle < acc.coverTitle!!)
                ) {
                    acc.coverTitle = al.sortTitle
                    acc.coverKey = al.imageKey
                }
            }
        }

        return byNorm.map { (norm, acc) ->
            val display = acc.names.entries.maxByOrNull { it.value }?.key ?: acc.first
            Entry(display, norm, acc.albums, acc.coverKey)
        }
    }

    /**
     * [list] in the order the screen asked for.
     *
     * Random is SEEDED rather than shuffled in place: the same seed gives the
     * same order, so paging through a random wall does not hand you the same
     * artist twice and miss another. The seed changes only when the reshuffle
     * is tapped.
     */
    fun sorted(list: List<Entry>, sort: String, seed: Int = 1): List<Entry> = when (sort) {
        ZA -> list.sortedByDescending { it.normalized }
        RANDOM -> list.sortedBy { mix(it.normalized, seed) }
        else -> list.sortedBy { it.normalized }
    }

    /**
     * A stable pseudo-random key for one artist under one seed: FNV-1a over the
     * name, folded with the seed. Deterministic, so the same seed always gives
     * the same wall, and cheap enough to run over every artist on every page.
     */
    private fun mix(normalized: String, seed: Int): Int {
        var h = -2128831035 xor seed
        for (ch in normalized) {
            h = h xor ch.code
            h *= 16777619
        }
        return h
    }
}
