package com.musicd.lite

import com.musicd.lite.meta.Deezer as D
import com.musicd.lite.meta.ShareLinks
import java.time.LocalDate
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules "If you like this" and Discover read Deezer by. Rouen's own
 * test/unit/similar.test.js and newreleases.test.js, ported case for case —
 * nearly every one is about NOT showing something: a tribute band as the act,
 * a single as an album, a remaster as a new record.
 */
class DeezerTest {

    private fun data(vararg rows: String) = JSONObject("""{"data":[${rows.joinToString(",")}]}""")

    // ------------------------------------------------------------- similar.js

    @Test
    fun theNameGuardRunsOnEveryRowNotJustTheFirst() {
        val out = D.readArtists(
            data(
                """{"id":1,"name":"Sting Tribute Band","nb_fan":900000}""",
                """{"id":2,"name":"The Stings","nb_fan":500}""",
                """{"id":3,"name":"Sting","nb_fan":1200000}""",
                """{"id":4,"name":"Stinger","nb_fan":10}"""
            ),
            "Sting"
        )
        assertFalse(out.map { it.name }.toString(), out.any { it.name == "The Stings" || it.name == "Stinger" })
        assertEquals("Sting", out[0].name)
        assertTrue(out[0].exact)
    }

    @Test
    fun anExactNameOutranksABetterFollowedPartialOne() {
        val out = D.readArtists(
            data("""{"id":1,"name":"Sting Tribute Band","nb_fan":99999999}""", """{"id":2,"name":"Sting","nb_fan":1}"""),
            "Sting"
        )
        assertEquals("Sting", out[0].name)
    }

    @Test
    fun fansAreTheTieBreakNeverTheFilter() {
        val out = D.readArtists(
            data("""{"id":1,"name":"Nirvana","nb_fan":100}""", """{"id":2,"name":"Nirvana","nb_fan":9000000}"""),
            "Nirvana"
        )
        assertEquals(2, out.size)
        assertEquals("2", out[0].id)
        assertTrue(D.readArtists(data("""{"id":9,"name":"Completely Different","nb_fan":50000000}"""), "Nirvana").isEmpty())
    }

    @Test
    fun theIsDiscounted() {
        assertTrue(D.namesOverlap("The Beatles", "Beatles"))
        assertTrue(D.namesOverlap("Beatles", "The Beatles"))
        assertFalse(D.namesOverlap("The Beatles", "The Rutles"))
    }

    @Test
    fun aSuggestionIsAFullAlbumAndTheEarliest() {
        val best = D.readFirstAlbum(
            data(
                """{"record_type":"single","title":"A Single","release_date":"1990-01-01"}""",
                """{"record_type":"album","title":"Third","release_date":"2005-03-02"}""",
                """{"record_type":"ep","title":"An EP","release_date":"1991-01-01"}""",
                """{"record_type":"album","title":"The Debut","release_date":"1994-06-01","cover_medium":"c.jpg"}""",
                """{"record_type":"album","title":"Second","release_date":"1999-01-01"}"""
            )
        )!!
        assertEquals("The Debut", best.title)
        assertEquals(1994, best.year)
        assertEquals("c.jpg", best.cover)
    }

    @Test
    fun anAlbumWithNoUsableDateIsNotTheEarliestByDefault() {
        val best = D.readFirstAlbum(
            data(
                """{"record_type":"album","title":"Unknown date","release_date":"0000-00-00"}""",
                """{"record_type":"album","title":"Real","release_date":"2001-05-05"}"""
            )
        )
        assertEquals("Real", best!!.title)
        val future = LocalDate.now().year + 5
        assertNull(D.readFirstAlbum(data("""{"record_type":"album","title":"Typo","release_date":"$future-01-01"}""")))
    }

    @Test
    fun relatedActsAreCappedDeduplicatedAndKeepDeezersOrder() {
        val out = D.readRelated(
            data(
                """{"id":1,"name":"One"}""", """{"id":1,"name":"One again"}""",
                """{"id":2,"name":"Two"}""", """{"id":3,"name":"Three"}""", """{"id":4,"name":"Four"}"""
            )
        )
        assertEquals(listOf("One", "Two", "Three"), out.map { it.name })
    }

    @Test
    fun malformedAnswersAreEmptyNeverAThrow() {
        for (junk in listOf(null, JSONObject(), JSONObject("""{"data":null}"""), JSONObject("""{"data":"nope"}"""),
                            JSONObject("""{"data":[null,{}]}"""))) {
            assertTrue(D.readArtists(junk, "X").isEmpty())
            assertTrue(D.readRelated(junk).isEmpty())
            assertNull(D.readFirstAlbum(junk))
            assertTrue(D.readArtistAlbums(junk).isEmpty())
        }
    }

    @Test
    fun namesOverlapOverRouensBattery() {
        // Rouen checks its two copies agree over this battery; these are the
        // answers its rule gives.
        val expect = mapOf(
            ("Sting" to "Sting Tribute Band") to true, ("The Beatles" to "Beatles") to true,
            ("AC/DC" to "ACDC") to false, ("Mötley Crüe" to "Motley Crue") to true,
            ("Sting" to "Stinger") to false, ("" to "Sting") to false, ("Nirvana" to "Nirvana") to true,
            ("The The" to "The") to true, ("Hall & Oates" to "Hall and Oates") to false,
            ("Beyoncé" to "Beyonce") to true
        )
        for ((pair, want) in expect) assertEquals(pair.toString(), want, D.namesOverlap(pair.first, pair.second))
    }

    // --------------------------------------------------------- newreleases.js

    private val day = 86_400_000L
    private val now = LocalDate.of(2026, 9, 21).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val since = now - 60 * day

    private fun album(title: String, date: String, extra: String = "", type: String = "album"): String =
        """{"title":${JSONObject.quote(title)},"release_date":"$date","record_type":"$type","id":${title.length},"cover_medium":"c.jpg"$extra}"""

    private fun listing(vararg rows: String) = D.readArtistAlbums(data(*rows))

    @Test
    fun theCoverIsTakenFromWhicheverFieldDeezerFilledIn() {
        assertEquals("m", D.coverOf(JSONObject("""{"cover_medium":"m","cover":"c"}""")))
        assertEquals("b", D.coverOf(JSONObject("""{"cover_big":"b","cover":"c"}""")))
        assertEquals("c", D.coverOf(JSONObject("""{"cover":"c"}""")))
        assertTrue(D.coverOf(JSONObject("""{"md5_image":"abc123"}"""))!!
            .startsWith("https://cdn-images.dzcdn.net/images/cover/abc123/"))
        assertNull(D.coverOf(JSONObject()))
        assertEquals("m", D.coverOf(JSONObject("""{"cover_medium":"m","md5_image":"abc"}""")))
    }

    @Test
    fun singlesAndEpsAreNotNewRecords() {
        val out = listing(
            album("Real Album", "2026-09-01"),
            album("A Single", "2026-09-05", type = "single"),
            album("An EP", "2026-09-06", type = "ep")
        )
        assertEquals(listOf("Real Album"), out.map { it.title })
    }

    @Test
    fun anEpLengthReleaseIsNotAnAlbumWhenDeezerSaysHowLongItIs() {
        val out = listing(
            album("Proper Record", "2026-09-01", ""","nb_tracks":11"""),
            album("Four Tracker", "2026-09-02", ""","nb_tracks":4"""),
            album("Unstated", "2026-09-03")
        )
        assertEquals(listOf("Proper Record", "Unstated"), out.map { it.title }.sorted())
    }

    @Test
    fun aRowWithNoUsableDateIsDroppedNotDatedToday() {
        val out = listing(album("Known", "2026-09-01"), album("Unknown", "0000-00-00"), album("Blank", ""), album("Partial", "2026"))
        assertEquals(listOf("Known"), out.map { it.title })
    }

    @Test
    fun aDateOnlyStringIsReadAsTheDateItSaysWestOfGreenwichToo() {
        val ms = D.dateMs("2026-09-01")!!
        assertEquals(1, java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).dayOfMonth)
        assertTrue(ms - LocalDate.of(2026, 9, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() >= 11 * 3_600_000L)
    }

    @Test
    fun theListingComesBackNewestFirst() {
        val out = listing(album("Old", "2020-01-01"), album("New", "2026-09-01"), album("Mid", "2023-05-05"))
        assertEquals(listOf("New", "Mid", "Old"), out.map { it.title })
    }

    @Test
    fun stripEditionFollowsRouensRules() {
        assertEquals("Rumours" to true, D.stripEdition("Rumours (2021 Remaster)"))
        assertEquals("Rumours" to true, D.stripEdition("Rumours (Deluxe Edition) (Remastered)"))
        // A dash tail only when it names an edition.
        assertEquals("Rumours" to true, D.stripEdition("Rumours - 2021 Remaster"))
        assertEquals("Sgt. Pepper - Reprise" to false, D.stripEdition("Sgt. Pepper - Reprise"))
        // A bracket that is not an edition reduces the title but is not flagged.
        assertEquals("Untitled" to false, D.stripEdition("Untitled (Black Is)"))
        // Sigur Rós's "( )" keeps its name.
        assertEquals("( )", D.stripEdition("( )").first)
        assertEquals("()", D.stripEdition("()").first)
    }

    @Test
    fun aReissueNeedsBothAnEditionNameAndAnOlderRecord() {
        val remaster = listing(album("Rumours (2021 Remaster)", "2026-09-01"), album("Rumours", "1977-02-04"))
        assertTrue(D.isReissue(remaster[0], remaster))
        val deluxe = listing(album("Brand New (Deluxe Edition)", "2026-09-01"))
        assertFalse("an edition name alone", D.isReissue(deluxe[0], deluxe))
        val sault = listing(album("Untitled (Black Is)", "2026-09-01"), album("Untitled (Rise)", "2026-06-01"))
        assertFalse("a shared base title alone", D.isReissue(sault[0], sault))
        val sameDay = listing(album("Record (Remastered)", "2026-09-01"), album("Record", "2026-09-01"))
        assertFalse("an identically dated pair", D.isReissue(sameDay[0], sameDay))
    }

    private fun pick(vararg rows: String, owned: Set<String> = emptySet(), wanted: Int = D.WANTED_PER_ARTIST) =
        D.pickNewReleases(listing(*rows), now, since, owned, wanted).map { it.title }

    @Test
    fun aRecordReleasedAfterTodayIsNotADiscovery() {
        assertEquals(listOf("Out Now"), pick(album("Announced", "2026-12-01"), album("Out Now", "2026-09-01")))
    }

    @Test
    fun aRecordOutsideTheWindowIsNotNew() {
        assertEquals(listOf("Recent"), pick(album("Recent", "2026-09-01"), album("Last Year", "2025-09-01")))
    }

    @Test
    fun aRecordAlreadyInTheLibraryIsNeverOffered() {
        assertEquals(
            listOf("Tusk"),
            pick(album("Rumours (2026 Remaster)", "2026-09-01"), album("Tusk", "2026-09-02"), owned = setOf(D.titleKey("Rumours")))
        )
        // Blind to the punctuation two catalogues disagree about.
        assertEquals(
            emptyList<String>(),
            pick(album("Sgt. Peppers Lonely Hearts Club Band", "2026-09-01"),
                 owned = setOf(D.titleKey("Sgt. Pepper's Lonely Hearts Club Band")))
        )
    }

    @Test
    fun aRemasterIsDroppedEvenWhenYouDoNotOwnTheOriginal() {
        assertEquals(
            listOf("Something Else"),
            pick(album("Rumours (2021 Remaster)", "2026-09-01"), album("Rumours", "1977-02-04"), album("Something Else", "2026-09-02"))
        )
    }

    @Test
    fun aStandardAndADeluxePressingAreOneRelease() {
        assertEquals(listOf("New Record"), pick(album("New Record (Deluxe Edition)", "2026-09-01"), album("New Record", "2026-09-01")))
        assertEquals(listOf("New Record"), pick(album("New Record (Deluxe Edition)", "2026-09-03"), album("New Record", "2026-09-01")))
    }

    @Test
    fun twoDifferentAlbumsThatReduceToTheSameBaseBothSurvive() {
        assertEquals(
            listOf("Untitled (Black Is)", "Untitled (Rise)"),
            pick(album("Untitled (Black Is)", "2026-09-01"), album("Untitled (Rise)", "2026-08-01"))
        )
    }

    @Test
    fun theSameRecordListedTwiceIsOneRow() {
        assertEquals(1, pick(album("New Record", "2026-09-01"), album("New Record", "2026-09-01")).size)
    }

    @Test
    fun anEditionWithNoPlainPressingInTheWindowStillStandsIn() {
        assertEquals(listOf("Old One (Deluxe Edition)"), pick(album("Old One (Deluxe Edition)", "2026-09-01")))
    }

    @Test
    fun theOwnedSetIsTheActsTitles() {
        val mine = setOf(D.titleKey("Rumours"))
        fun rel(title: String) = D.Release(null, title, D.titleKey(D.stripEdition(title).first), false, "", 0, null)
        assertTrue(D.owns(mine, rel("Rumours (2026 Remaster)")))
        assertFalse(D.owns(mine, rel("Tusk")))
        assertFalse(D.owns(emptySet(), rel("x")))
    }

    @Test
    fun oneActCannotFillTheScreen() {
        assertEquals(
            listOf("One", "Two"),
            pick(album("One", "2026-09-01"), album("Two", "2026-08-01"), album("Three", "2026-07-25"), wanted = 2)
        )
    }

    // -------------------------------------------------------- the seed list

    private val splitFirst: (String) -> String = { it.split(Regex("\\s+feat\\.\\s+", RegexOption.IGNORE_CASE))[0] }

    @Test
    fun distinctDaysRankAnActNotTheNumberOfPlays() {
        val rows = ArrayList<Pair<String, Long>>()
        for (i in 0 until 40) rows += "One Night" to now + i * 60_000L
        for (d in 0 until 8) rows += "Every Week" to now - d * day
        val out = D.playedArtists(rows, 40, splitFirst)
        assertEquals("Every Week", out[0].name)
        assertEquals(8, out[0].days)
        assertEquals(1, out[1].days)
    }

    @Test
    fun recencyBreaksATieOnDays() {
        assertEquals(listOf("Newer", "Older"), D.playedArtists(listOf("Older" to now - 10 * day, "Newer" to now), 40, splitFirst).map { it.name })
    }

    @Test
    fun variousArtistsIsAFilingNeverAnAct() {
        val out = D.playedArtists(listOf("Various Artists" to now, "Various" to now, "VA" to now, "Real Act" to now), 40, splitFirst)
        assertEquals(listOf("Real Act"), out.map { it.name })
    }

    @Test
    fun theCreditRuleIsTheShareLinksOne() {
        val names = D.playedArtists(
            listOf("Hall & Oates" to now, "Artist A feat. Artist B" to now - day), 40, ShareLinks::primaryArtist
        ).map { it.name }
        assertTrue(names.toString(), "Hall & Oates" in names)
        assertTrue(names.toString(), "Artist A" in names && "Artist B" !in names)
    }

    @Test
    fun oneActUnderTwoSpellingsIsOneSeed() {
        val out = D.playedArtists(listOf("Sigur Rós" to now, "Sigur Ros" to now - day), 40, splitFirst)
        assertEquals(1, out.size)
        assertEquals(2, out[0].days)
    }

    @Test
    fun anEmptyArtistNeverBecomesASeed() {
        assertEquals(listOf("Good"), D.playedArtists(listOf("" to now, "   " to now, "Good" to now), 40, splitFirst).map { it.name })
    }

    @Test
    fun titleKeyIsBlindToApostrophesAndAmpersands() {
        assertEquals(D.titleKey("Sgt. Pepper's"), D.titleKey("Sgt. Peppers"))
        assertEquals(D.titleKey("Rock & Roll"), D.titleKey("Rock and Roll"))
    }
}
