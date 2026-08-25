package com.musicd.lite.library

import java.text.Normalizer

/**
 * The one text-folding rule the whole app matches on.
 *
 * Every comparison — search, artist identity, play history, decade counts —
 * goes through [text]. Two copies of this rule that drift apart is how a
 * library ends up reporting one number and returning a different set.
 */
object Normalize {

    private val COMBINING = Regex("[\\u0300-\\u036f]")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val LEADING_ARTICLE = Regex("^(the|a|an) ")

    /** Lowercase, strip accents, collapse everything else to single spaces. */
    fun text(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val folded = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
        return COMBINING.replace(folded, "")
            .replace(NON_ALNUM, " ")
            .trim()
    }

    /**
     * Sort key: Roon — and every record shop — files "The Wall" under W, not T.
     * Leading articles are dropped for ORDERING only; the displayed title is
     * untouched.
     */
    fun sortKey(normalized: String): String = LEADING_ARTICLE.replace(normalized, "")

    private val ARTIST_SEPARATORS =
        Regex(" / | feat\\.? | featuring | ft\\.? ", RegexOption.IGNORE_CASE)

    /**
     * Split a Roon subtitle into individual artist names on the common
     * multi-artist separators. Shared by the index build so the same separator
     * set is used everywhere.
     */
    fun splitArtists(subtitle: String?): List<ArtistName> {
        if (subtitle.isNullOrBlank()) return emptyList()
        return ARTIST_SEPARATORS.split(subtitle)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { ArtistName(it, text(it)) }
    }

    /** Roon prefixes track titles with "N. "; the UI renders its own counter. */
    private val TRACK_NUMBER = Regex("^(\\d+)\\.\\s+")

    fun stripTrackNumber(title: String?): String =
        TRACK_NUMBER.replace(title ?: "", "")

    /**
     * The number [stripTrackNumber] throws away. Roon's browse API exposes no
     * track-number field, so this prefix is the only place it exists. A "track
     * 0" or an absurd number means a title that merely begins with digits
     * ("1999. The Party" would otherwise parse as track 1999).
     */
    fun trackNumberOf(title: String?): Int? {
        val n = TRACK_NUMBER.find(title ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (n in 1..999) n else null
    }
}

data class ArtistName(val name: String, val normalized: String)
