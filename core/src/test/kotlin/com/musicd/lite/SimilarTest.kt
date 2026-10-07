package com.musicd.lite

import com.musicd.lite.Similar.Near
import com.musicd.lite.Similar.PoolAct
import com.musicd.lite.Similar.Ranked
import com.musicd.lite.meta.Deezer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "If you like this", weighted by what you play (Rouen v1.8.82, from Mandarin
 * v0.7.6 and v0.7.9) — the pure rules. The route, with Deezer answered by the
 * fixture, is in DiscoverTest beside Discover's.
 */
class SimilarTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun act(name: String, rank: Int, fans: Int? = 50_000, id: String = name.lowercase()) =
        PoolAct(id, name, rank, fans, null)

    // ----------------------------------------------------------- taste graph

    @Test
    fun theTasteGraphWeighsAnActByWhatYouPlayNearIt() {
        val seeds = listOf(
            Deezer.Seed("Steely Dan", days = 10, last = now - day),
            Deezer.Seed("Boz Scaggs", days = 2, last = now - 200 * day)
        )
        val related = mapOf(
            "Steely Dan" to listOf(act("Donald Fagen", 0), act("Michael McDonald", 1), act("Steely Dan", 2)),
            "Boz Scaggs" to listOf(act("Michael McDonald", 0))
        )
        val g = Similar.tasteGraph(seeds, { related[it] }, now)
        // The nearest is 1: scores are relative.
        assertEquals(1.0, g.getValue("donald fagen").score, 1e-9)
        val mcd = g.getValue("michael mcdonald")
        assertTrue(mcd.score < 1.0)
        assertEquals(listOf("Steely Dan", "Boz Scaggs"), mcd.via)
        // A seed is not near itself.
        assertFalse(g.containsKey("steely dan"))
    }

    @Test
    fun oldPlaysCountForLessThanRecentOnes() {
        val related = mapOf(
            "Recent" to listOf(act("A", 0)),
            "Old" to listOf(act("B", 0))
        )
        val g = Similar.tasteGraph(
            listOf(Deezer.Seed("Recent", 3, now - day), Deezer.Seed("Old", 3, now - 120 * day)),
            { related[it] }, now
        )
        assertTrue(g.getValue("a").score > g.getValue("b").score)
    }

    // --------------------------------------------------------------- ranking

    @Test
    fun anActNearYourListeningOutranksDeezersOwnOrder() {
        val pool = listOf(act("Children's Choir", 0), act("Donald Fagen", 1))
        val taste = mapOf("donald fagen" to Near(0.5, listOf("Steely Dan")))
        val ranked = Similar.rankActs(pool, "Steely Dan", taste, { null }, emptySet(), emptySet())
        assertEquals("Donald Fagen", ranked.first().act.name)
        assertTrue(ranked.first().near)
        assertEquals(listOf("Steely Dan"), ranked.first().via)
    }

    @Test
    fun theActPlayingAndTheActsYouPlayMostAreNeverSuggested() {
        val pool = listOf(act("Steely Dan", 0), act("Boz Scaggs", 1), act("Donald Fagen", 2))
        val ranked = Similar.rankActs(pool, "Steely Dan", emptyMap(), { null }, setOf("boz scaggs"), emptySet())
        assertEquals(listOf("Donald Fagen"), ranked.map { it.act.name })
    }

    @Test
    fun anActShownLatelyAndAnObscureOneAreMovedBackNotOut() {
        val pool = listOf(act("A", 0), act("B", 1), act("C", 2, fans = 10))
        val ranked = Similar.rankActs(pool, "X", emptyMap(), { null }, emptySet(), setOf("a"))
        assertEquals(3, ranked.size)
        assertEquals("B", ranked.first().act.name)
        val a = ranked.first { it.act.name == "A" }
        assertEquals(0.3 * 0.5, a.score, 1e-9)
        val c = ranked.first { it.act.name == "C" }
        assertEquals(0.3 * (1 - 2.0 / 3) * 0.5, c.score, 1e-9)
    }

    // ---------------------------------------------------------------- choose

    private fun ranked(name: String, score: Double, known: String? = null, near: Boolean = false) =
        Ranked(act(name, 0), score, known, near, if (near) listOf("Seed") else emptyList())

    @Test
    fun twoActsYouHaventHeardAndOneYouKnow() {
        val list = listOf(
            ranked("K1", 0.9, known = "library"),
            ranked("U1", 0.8), ranked("U2", 0.7), ranked("U3", 0.6),
            ranked("K2", 0.5, known = "played")
        )
        val picks = Similar.choose(list, 3) { 0.0 }
        assertEquals(3, picks.size)
        assertEquals(2, picks.count { it.known == null })
        assertEquals(1, picks.count { it.known != null })
    }

    @Test
    fun withNothingYouKnowTheThirdSlotIsAnotherNewAct() {
        val picks = Similar.choose(listOf(ranked("U1", 0.9), ranked("U2", 0.8), ranked("U3", 0.7)), 3) { 0.5 }
        assertEquals(setOf("U1", "U2", "U3"), picks.map { it.act.name }.toSet())
    }

    @Test
    fun anActNearNothingYouPlayOnlyFillsASlotTheNearOnesCannot() {
        val list = listOf(
            ranked("Choir", 0.95), ranked("Near1", 0.2, near = true), ranked("Near2", 0.1, near = true)
        )
        // Whatever the draw, the two unknown slots go to the near acts first.
        for (r in listOf(0.0, 0.5, 0.999)) {
            val picks = Similar.choose(list, 3) { r }
            assertEquals(listOf("Near1", "Near2").toSet(), picks.take(2).map { it.act.name }.toSet())
            assertEquals("Choir", picks[2].act.name)
        }
    }

    @Test
    fun theDrawFavoursTheHigherScoreButCanPickAnyone() {
        val list = listOf(ranked("High", 1.0), ranked("Low", 0.1))
        assertEquals(0, Similar.draw(list) { 0.0 })
        assertEquals(1, Similar.draw(list) { 0.9999 })
        // High carries 1.0 of 1.01 of the weight.
        assertEquals(0, Similar.draw(list) { 0.98 })
    }

    // ---------------------------------------------------------------- reason

    @Test
    fun eachActSaysWhyItIsThere() {
        assertEquals("In your library — a record you don't have",
                     Similar.reasonFor(ranked("A", 1.0, known = "library"), "X"))
        assertEquals("Something you've played — a record you don't have",
                     Similar.reasonFor(ranked("A", 1.0, known = "played"), "X"))
        val two = Ranked(act("A", 0), 1.0, null, true, listOf("Steely Dan", "Boz Scaggs"))
        assertEquals("Near Steely Dan and Boz Scaggs, which you play", Similar.reasonFor(two, "X"))
        val viaPlaying = Ranked(act("A", 0), 1.0, null, true, listOf("X", "Boz Scaggs"))
        assertEquals("Near Boz Scaggs, which you play", Similar.reasonFor(viaPlaying, "X"))
        assertEquals("Near X", Similar.reasonFor(ranked("A", 1.0), "X"))
    }

    // --------------------------------------------------------------- records

    @Test
    fun anActsBestKnownRecordIsTheOneMostOfItsTopTracksAreFrom() {
        val top = Similar.readTop(JSONObject("""{"data":[
            {"album":{"id":1,"title":"Debut"}},
            {"album":{"id":2,"title":"The Hit Record","cover_medium":"c2"}},
            {"album":{"id":2,"title":"The Hit Record"}},
            {"album":{"id":3,"title":""}}]}"""))!!
        assertEquals("The Hit Record", top.title)
        assertEquals("c2", top.cover)
        assertNull(Similar.readTop(null))
    }

    @Test
    fun theAlbumListingIsFullAlbumsNewestFirst() {
        val list = Similar.readAlbumList(JSONObject("""{"data":[
            {"id":1,"title":"Old","release_date":"1990-01-01","record_type":"album"},
            {"id":2,"title":"Single","release_date":"2020-01-01","record_type":"single"},
            {"id":3,"title":"New","release_date":"2015-05-05","record_type":"album"}]}"""), 2026)
        assertEquals(listOf("New", "Old"), list.map { it.title })
        assertEquals(2015, list[0].year)
    }

    @Test
    fun anActYouKnowGetsItsNewestRecordYouDontOwnEditionsIncluded() {
        val albums = listOf(
            Similar.ListedAlbum("1", "Rumours (Super Deluxe)", 2013, null),
            Similar.ListedAlbum("2", "Tango in the Night", 1987, null),
            Similar.ListedAlbum("3", "Rumours", 1977, null)
        )
        val owned = listOf("Rumours")
        val rec = Similar.recordFor("library", null, albums, owned)!!
        assertEquals("Tango in the Night", rec.title)
        assertEquals(1987, rec.year)
        // Owning everything leaves nothing to name.
        assertNull(Similar.recordFor("library", null, albums, listOf("Rumours", "Tango in the Night")))
    }

    @Test
    fun anActYouDontKnowGetsItsBestKnownWithTheYearFromItsListing() {
        val top = Similar.TopAlbum("2", "Aja", "cover", 4)
        val albums = listOf(Similar.ListedAlbum("2", "Aja", 1977, "c"))
        val rec = Similar.recordFor(null, top, albums, emptyList())!!
        assertEquals("Aja", rec.title)
        assertEquals(1977, rec.year)
        assertEquals("cover", rec.cover)
        // No top tracks: the newest full album.
        assertEquals("Aja", Similar.recordFor(null, null, albums, emptyList())!!.title)
    }
}
