package com.musicd.lite.library

/**
 * Instant local search over the album index.
 *
 * Roon's own browse search is server-driven, relevance-tuned and unhappy with
 * very short or common-word queries (typing "the t" for the band "The The").
 * Matching locally against the snapshot gives prefix-aware, typo-tolerant
 * search across the whole library on every keystroke, and each hit already
 * carries the offset that playback needs.
 */
object Search {

    /**
     * Earliest index i where qTokens[k] prefixes tokens[i+k] for every k — a
     * consecutive run. tokens=["the","the"], qTokens=["the","t"] -> 0, which is
     * the "The The" case.
     */
    fun consecutivePrefixStart(tokens: List<String>, qTokens: List<String>): Int {
        val last = tokens.size - qTokens.size
        for (i in 0..last) {
            var ok = true
            for (k in qTokens.indices) {
                if (!tokens[i + k].startsWith(qTokens[k])) { ok = false; break }
            }
            if (ok) return i
        }
        return -1
    }

    /**
     * Every query token prefixes some distinct title token, order-independent,
     * so "dark moon" still finds "Dark Side of the Moon".
     */
    fun allTokensPrefixSomewhere(tokens: List<String>, qTokens: List<String>): Boolean {
        val used = BooleanArray(tokens.size)
        for (qt in qTokens) {
            var found = false
            for (i in tokens.indices) {
                if (!used[i] && tokens[i].startsWith(qt)) { used[i] = true; found = true; break }
            }
            if (!found) return false
        }
        return true
    }

    /** Loose typo tolerance: all chars of q appear in order within s. */
    fun isSubsequence(q: String, s: String): Boolean {
        var i = 0
        var j = 0
        while (j < s.length && i < q.length) {
            if (s[j] == q[i]) i++
            j++
        }
        return i == q.length
    }

    /**
     * Higher score = better match. Title hits outrank artist hits of similar
     * quality; exact and prefix outrank substring; fuzzy is a last resort.
     */
    fun scoreAlbum(
        al: AlbumRecord,
        q: String,
        qTokens: List<String>,
        qJoined: String,
        singleChar: Boolean
    ): Int {
        var s = 0

        // ---- Title (primary) ----
        if (al.nTitle == q) return 1000
        if (al.nTitle.startsWith(q)) {
            s = maxOf(s, 920 - minOf(al.nTitle.length - q.length, 60))
        }
        run {
            val start = consecutivePrefixStart(al.tTitle, qTokens)
            if (start == 0) s = maxOf(s, 900 - minOf(al.tTitle.size, 40))
            else if (start > 0 && !singleChar) s = maxOf(s, 820 - start * 4)
        }
        if (qJoined.isNotEmpty() && al.jTitle.startsWith(qJoined)) {
            s = maxOf(s, 870 - minOf(al.jTitle.length - qJoined.length, 60))
        }
        if (!singleChar) {
            if (s < 760 && qTokens.size > 1 && allTokensPrefixSomewhere(al.tTitle, qTokens)) {
                s = maxOf(s, 760)
            }
            if (s < 650 && al.nTitle.contains(q)) {
                s = maxOf(s, 650 - minOf(al.nTitle.indexOf(q), 40))
            }
        }

        // ---- Artist (secondary) ----
        if (al.nArtist.isNotEmpty()) {
            if (al.nArtist == q) s = maxOf(s, 770)
            if (al.nArtist.startsWith(q)) {
                s = maxOf(s, 740 - minOf(al.nArtist.length - q.length, 60))
            }
            run {
                val start = consecutivePrefixStart(al.tArtist, qTokens)
                if (start == 0) s = maxOf(s, 720 - minOf(al.tArtist.size, 40))
                else if (start > 0 && !singleChar) s = maxOf(s, 660 - start * 4)
            }
            if (qJoined.isNotEmpty() && al.jArtist.startsWith(qJoined)) {
                s = maxOf(s, 700 - minOf(al.jArtist.length - qJoined.length, 60))
            }
            if (!singleChar) {
                if (s < 600 && qTokens.size > 1 && allTokensPrefixSomewhere(al.tArtist, qTokens)) {
                    s = maxOf(s, 600)
                }
                if (s < 520 && al.nArtist.contains(q)) {
                    s = maxOf(s, 520 - minOf(al.nArtist.indexOf(q), 40))
                }
            }
        }

        // ---- Fuzzy fallback (typos), only for longer queries with no real hit ----
        if (s == 0 && !singleChar && qJoined.length >= 4) {
            if (isSubsequence(qJoined, al.jTitle)) s = 300
            else if (isSubsequence(qJoined, al.jArtist)) s = 260
        }

        return s
    }

    data class Hit(val album: AlbumRecord, val score: Int)

    fun albums(index: List<AlbumRecord>, query: String, limit: Int): List<Hit> {
        val q = Normalize.text(query)
        if (q.isEmpty()) return emptyList()
        val qTokens = q.split(" ").filter(String::isNotEmpty)
        val qJoined = q.replace(" ", "")
        val singleChar = qJoined.length <= 1

        val out = ArrayList<Hit>()
        for (al in index) {
            val score = scoreAlbum(al, q, qTokens, qJoined, singleChar)
            if (score > 0) out += Hit(al, score)
        }
        out.sortWith(
            compareByDescending<Hit> { it.score }
                .thenBy { it.album.nTitle }
                .thenBy { it.album.nArtist }
        )
        return if (out.size > limit) out.subList(0, limit) else out
    }

    data class ArtistHit(val name: String, val normalized: String, val albumCount: Int)

    fun artists(index: List<AlbumRecord>, query: String, limit: Int = 8): List<ArtistHit> {
        val q = Normalize.text(query)
        if (q.isEmpty() || index.isEmpty()) return emptyList()
        val seen = LinkedHashMap<String, IntArray>()
        val display = HashMap<String, String>()
        for (al in index) {
            for (a in al.artistNames) {
                if (!a.normalized.contains(q)) continue
                val counter = seen.getOrPut(a.normalized) {
                    display[a.normalized] = a.name
                    intArrayOf(0)
                }
                counter[0]++
            }
        }
        return seen.entries
            .map { (n, c) -> ArtistHit(display[n] ?: n, n, c[0]) }
            .sortedWith(
                compareBy<ArtistHit> { if (it.normalized.startsWith(q)) 0 else 1 }
                    .thenByDescending { it.albumCount }
            )
            .take(limit)
    }
}
