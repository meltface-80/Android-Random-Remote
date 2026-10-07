package com.musicd.lite

import com.musicd.lite.library.AlbumIndex
import com.musicd.lite.library.AlbumRecord
import com.musicd.lite.library.LibraryView
import com.musicd.lite.store.MemoryStore
import com.musicd.lite.store.YearSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library wall's Focus (Rouen's, back in Lite at the owner's word): several
 * values per facet, a value tapped again EXCLUDED ("!"), and the facet sheet's
 * vocabulary in the shape the page reads — {value, label, count}.
 */
class FocusTest {

    private val core = FakeCore()
    private val store = MemoryStore()
    private val index = AlbumIndex()
    private val view = LibraryView(index, store)
    private val day = 86_400_000L
    private val now = System.currentTimeMillis()

    private fun keyOf(title: String, artist: String) = AlbumRecord(0, title, artist, null).key

    private fun library() {
        listOf(
            "Aja" to "Steely Dan", "Blue" to "Joni Mitchell", "Dummy" to "Portishead",
            "Kid A" to "Radiohead", "The Wall" to "Pink Floyd", "1999" to "Prince"
        ).forEach { (t, a) -> core.addAlbum(t, a) }
        index.build(core.tree)
        store.putAlbumYear(keyOf("Aja", "Steely Dan"), 1977, YearSource.MUSICBRAINZ)
        store.putAlbumYear(keyOf("Blue", "Joni Mitchell"), 1971, YearSource.MUSICBRAINZ)
        store.putAlbumYear(keyOf("Dummy", "Portishead"), 1994, YearSource.MUSICBRAINZ)
        store.putAlbumYear(keyOf("Kid A", "Radiohead"), 2000, YearSource.MUSICBRAINZ)
        store.putAlbumGenres(keyOf("Aja", "Steely Dan"), listOf("Jazz", "Rock"))
        store.putAlbumGenres(keyOf("Blue", "Joni Mitchell"), listOf("Folk"))
        store.putAlbumGenres(keyOf("Dummy", "Portishead"), listOf("Electronic"))
        store.putAlbumGenres(keyOf("Kid A", "Radiohead"), listOf("Rock", "Electronic"))
        store.recordFirstSeen(
            mapOf(
                keyOf("Kid A", "Radiohead") to now - 3 * day,
                keyOf("Dummy", "Portishead") to now - 60 * day,
                keyOf("Aja", "Steely Dan") to now - 400 * day
            )
        )
    }

    private fun titles(facets: Map<String, List<String>>, played: String? = null): Set<String> =
        view.select(view.sanitize("album", "asc", null, played, null, null, null, facets))
            .map { it.title }.toSet()

    @Test
    fun severalValuesInAFacetAreAnyOfThem() {
        library()
        assertEquals(setOf("Blue", "Dummy", "Kid A"), titles(mapOf("genre" to listOf("Folk", "Electronic"))))
    }

    @Test
    fun aValueTappedAgainIsExcluded() {
        library()
        // Excludes alone: everything except Rock — albums with no genre at all included.
        assertEquals(setOf("Blue", "Dummy", "The Wall", "1999"), titles(mapOf("genre" to listOf("!Rock"))))
        // An include and an exclude: Electronic but not Rock.
        assertEquals(setOf("Dummy"), titles(mapOf("genre" to listOf("Electronic", "!Rock"))))
    }

    @Test
    fun facetsCombineWithEachOther() {
        library()
        assertEquals(setOf("Kid A"), titles(mapOf("genre" to listOf("Rock"), "decade" to listOf("2000"))))
        assertEquals(setOf("Aja", "Blue", "Dummy"), titles(mapOf("decade" to listOf("1970", "1990"))))
        // The form the old single-value page sent, "1990s", still means the 1990s.
        assertEquals(setOf("Dummy"), titles(mapOf("decade" to listOf("1990s"))))
    }

    @Test
    fun startsWithFilesTheWallUnderW() {
        library()
        assertEquals(setOf("The Wall"), titles(mapOf("letter" to listOf("W"))))
        assertEquals(setOf("Aja"), titles(mapOf("letter" to listOf("A"))))
        // Anything not a letter shares one bucket.
        assertEquals(setOf("1999"), titles(mapOf("letter" to listOf("#"))))
    }

    @Test
    fun addedWindowsNest() {
        library()
        assertEquals(setOf("Kid A"), titles(mapOf("added" to listOf("7"))))
        // Picking three months must not drop this week's.
        assertEquals(setOf("Kid A", "Dummy"), titles(mapOf("added" to listOf("90"))))
    }

    @Test
    fun unknownFacetsAndValuesNarrowNothing() {
        library()
        val all = titles(emptyMap())
        // A facet this build cannot know (Rouen's Format, say, in a saved view).
        assertEquals(all, titles(mapOf("format" to listOf("FLAC"))))
        assertEquals(all, titles(mapOf("decade" to listOf("not-a-decade"))))
    }

    @Test
    fun theFacetSheetIsServedInTheShapeThePageReads() {
        library()
        val j = view.facets()
        assertEquals(6, j.getInt("total"))
        val byId = (0 until j.getJSONArray("facets").length())
            .map { j.getJSONArray("facets").getJSONObject(it) }.associateBy { it.getString("id") }
        assertEquals(setOf("genre", "decade", "letter", "added"), byId.keys)

        val genre = byId.getValue("genre")
        assertEquals("Genre", genre.getString("label"))
        val rock = values(genre).first { it.getString("value") == "Rock" }
        assertEquals("Rock", rock.getString("label"))
        assertEquals(2, rock.getInt("count"))
        assertEquals(4, genre.getInt("total_values"))

        // Decades newest first, labelled as decades.
        val decades = values(byId.getValue("decade"))
        assertEquals(listOf("2000", "1990", "1970"), decades.map { it.getString("value") })
        assertEquals("1970s", decades.last().getString("label"))
        assertEquals(2, decades.last().getInt("count"))

        // Shortest window first, nested counts.
        val added = values(byId.getValue("added"))
        assertEquals(listOf("7", "30", "90", "365"), added.map { it.getString("value") })
        assertEquals(listOf(1, 1, 2, 2), added.map { it.getInt("count") })
        assertEquals("3 months", added[2].getString("label"))

        val coverage = j.getJSONObject("coverage")
        assertEquals(4, coverage.getInt("decade"))
        assertEquals(4, coverage.getInt("genre"))
        assertEquals(3, coverage.getInt("added"))
        assertFalse(j.getBoolean("hasPlays"))
    }

    private fun values(facet: JSONObject): List<JSONObject> =
        facet.getJSONArray("values").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    // ------------------------------------------------------------- the route

    @Test
    fun theWallReadsEveryValueOfARepeatedParameterAndRoonsOwnGenres() {
        val f = ApiFixture().start()
        try {
            // Roon's genre lists, as the app reads them — this build records no
            // genres of its own. The fixture files Blue Lines, Dummy and
            // Mezzanine under Trip-Hop; Third and Kid A go under Rock here.
            f.core.genres["Rock"] = mutableListOf(3, 4)
            fun names(q: String): Set<String> {
                val a: JSONArray = f.json("/api/library/albums?sort=album&$q").getJSONArray("albums")
                return (0 until a.length()).map { a.getJSONObject(it).getString("title") }.toSet()
            }
            assertEquals(5, names("").size)
            assertEquals(setOf("Blue Lines", "Dummy", "Mezzanine"), names("genre=Trip-Hop"))
            assertEquals(5, names("genre=Trip-Hop&genre=Rock").size)
            assertEquals(setOf("Third", "Kid A"), names("genre=%21Trip-Hop"))
            assertEquals(setOf("Kid A"), names("genre=Rock&letter=K"))

            val facets = f.json("/api/library/facets").getJSONArray("facets")
            val genre = (0 until facets.length()).map { facets.getJSONObject(it) }.first { it.getString("id") == "genre" }
            val vals = genre.getJSONArray("values")
            assertEquals("Trip-Hop", vals.getJSONObject(0).getString("value"))
            assertEquals(3, vals.getJSONObject(0).getInt("count"))
            assertEquals("Rock", vals.getJSONObject(1).getString("value"))
        } finally {
            f.stop()
        }
    }
}
