package com.musicd.lite

import com.musicd.lite.meta.ShareLinks as L
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share card's links. The first half is Rouen's own test/unit/
 * share-links.test.js, ported case for case, so the Kotlin is held to the
 * behaviour the links were tuned to — every rule in it is a link that once
 * went somewhere useless. The second half drives the routes.
 */
class ShareLinksTest {

    @Test
    fun rule1_aSpaceIsPercent20NeverPlus() {
        val q = L.searchQuery("Talking Heads", "Remain in Light")!!
        assertEquals("Talking%20Heads%20Remain%20in%20Light", q)
        for (link in L.serviceLinks("Talking Heads", "Remain in Light", enabled = L.SERVICE_IDS)) {
            assertFalse("${link.id} encoded a space as +: ${link.url}", link.url.contains("+"))
        }
    }

    @Test
    fun rule1_aRealPlusAmpersandAndHashAreEncoded() {
        assertFalse(L.searchQuery("Sunn O)))", "White1")!!.contains("+"))
        assertTrue(L.searchQuery("Godspeed You! Black Emperor", "F# A# ∞")!!.contains("%23"))
        val amp = L.searchQuery("Hall & Oates", "Abandoned Luncheonette")!!
        assertTrue(amp.contains("%26"))
        assertFalse(amp.contains("&"))
    }

    @Test
    fun rule2_aSlashIsSpentAsASpace() {
        assertEquals("AC%20DC%20Back%20in%20Black", L.searchQuery("AC/DC", "Back in Black"))
        for (link in L.serviceLinks("AC/DC", "Back in Black", enabled = L.SERVICE_IDS)) {
            assertFalse(link.url, link.url.contains("%2F", ignoreCase = true))
        }
        assertEquals("AC%20DC%20Back%20in%20Black", L.searchQuery("AC\\DC", "Back in Black"))
    }

    @Test
    fun rule3_onlyTheFirstCreditedActIsSearched() {
        val credit = "Stan Getz / Cal Tjader / Alan Jay Lerner / Frederick Loewe"
        assertEquals("Stan Getz", L.primaryArtist(credit))
        assertEquals("Stan%20Getz%20Sextet", L.searchQuery(credit, "Sextet"))
        assertEquals("Drake", L.primaryArtist("Drake feat. Rihanna"))
        assertEquals("Drake", L.primaryArtist("Drake ft. Rihanna"))
        assertEquals("Drake", L.primaryArtist("Drake featuring Rihanna"))
        assertEquals("Bowie", L.primaryArtist("Bowie; Eno"))
        assertEquals("Getz", L.primaryArtist("Getz/ Tjader"))
        // One act each, and must stay whole.
        assertEquals("AC/DC", L.primaryArtist("AC/DC"))
        assertEquals("Hall & Oates", L.primaryArtist("Hall & Oates"))
        assertEquals("Emerson, Lake & Palmer", L.primaryArtist("Emerson, Lake & Palmer"))
        assertEquals("Crosby, Stills, Nash & Young", L.primaryArtist("Crosby, Stills, Nash & Young"))
        assertEquals("/ Leading", L.primaryArtist("/ Leading"))
        assertEquals(";", L.primaryArtist("; "))
    }

    @Test
    fun rule4_qobuzAlwaysHasAStorefrontAppleNever() {
        val by = L.serviceLinks("Bowie", "Low", locale = "en-GB", enabled = L.SERVICE_IDS).associate { it.id to it.url }
        assertTrue(by["qobuz"]!!, by["qobuz"]!!.startsWith("https://www.qobuz.com/gb-en/search/?q="))
        assertEquals("https://music.apple.com/search?term=Bowie%20Low", by["apple"])
    }

    @Test
    fun theQobuzStorefrontIsLookedUpNotConstructed() {
        assertEquals("gb-en", L.qobuzStorefront("en-GB"))
        assertEquals("fr-fr", L.qobuzStorefront("fr-FR"))
        assertEquals("jp-ja", L.qobuzStorefront("ja-JP"))
        assertTrue(L.qobuzStorefront("en-BE") in setOf("be-fr", "be-nl"))
        assertEquals("us-en", L.qobuzStorefront("en-IN"))
        assertEquals("us-en", L.qobuzStorefront(""))
        assertEquals("us-en", L.qobuzStorefront(null))
        assertEquals("fr-fr", L.qobuzStorefront("fr"))
    }

    @Test
    fun acceptLanguageHighestQWins() {
        assertEquals("en-GB", L.localeFromAcceptLanguage("en-GB,en;q=0.9,fr;q=0.8"))
        assertEquals("de-DE", L.localeFromAcceptLanguage("fr;q=0.5,de-DE;q=0.9"))
        assertEquals("", L.localeFromAcceptLanguage("*"))
        assertEquals("", L.localeFromAcceptLanguage(""))
        assertEquals("gb-en", L.qobuzStorefront(L.localeFromAcceptLanguage("en-GB,en;q=0.9")))
    }

    @Test
    fun nothingWorthSearchingForGivesNoLinks() {
        assertEquals(null, L.searchQuery("", ""))
        assertEquals(null, L.searchQuery(null, null))
        assertEquals(null, L.searchQuery("   ", "  "))
        assertTrue(L.serviceLinks("", "").isEmpty())
        assertTrue(L.reviewLinks("", "").isEmpty())
        assertEquals("Kind%20of%20Blue", L.searchQuery("", "Kind of Blue"))
    }

    @Test
    fun chipLabelsAreConstantsAndTheTwoAllMusicChipsDiffer() {
        val credit = "Stan Getz / Cal Tjader / Alan Jay Lerner / Frederick Loewe"
        for (l in L.reviewLinks(credit, "Sextet", enabled = L.REVIEW_IDS)) {
            assertTrue(l.chip!!.length <= 16)
            assertFalse(l.chip!!.contains("Getz"))
        }
        val both = L.reviewLinks("Bowie", "Low", enabled = listOf("allmusic", "allmusic-artist"))
        assertEquals(2, both.size)
        assertNotEquals(both[0].chip, both[1].chip)
    }

    @Test
    fun reviewLinksPreferAResolvedPageAndFallBackToASearch() {
        val by = L.reviewLinks(
            "Bowie", "Low", enabled = listOf("wikipedia", "pitchfork", "allmusic"),
            wikipediaUrl = "https://en.wikipedia.org/wiki/Low_(David_Bowie_album)",
            pitchforkUrl = "https://pitchfork.com/reviews/albums/bowie-low/"
        ).associate { it.id to it.url }
        assertEquals("https://en.wikipedia.org/wiki/Low_(David_Bowie_album)", by["wikipedia"])
        assertEquals("https://pitchfork.com/reviews/albums/bowie-low/", by["pitchfork"])
        assertEquals("https://www.allmusic.com/search/albums/Bowie%20Low", by["allmusic"])
        val bare = L.reviewLinks("Bowie", "Low", enabled = listOf("wikipedia", "pitchfork")).associate { it.id to it.url }
        assertTrue(bare["wikipedia"]!!.contains("search="))
        assertTrue(bare["pitchfork"]!!.contains("/search/"))
    }

    @Test
    fun theArtistLinksSearchForTheArtistAndAreAbsentWithoutOne() {
        val by = L.reviewLinks("Stan Getz / Cal Tjader", "Sextet", enabled = listOf("allmusic-artist", "wikipedia-artist"))
            .associate { it.id to it.url }
        assertEquals("https://www.allmusic.com/search/artists/Stan%20Getz", by["allmusic-artist"])
        assertFalse(by["wikipedia-artist"]!!.contains("Sextet"))
        val ids = L.reviewLinks("", "Kind of Blue", enabled = L.REVIEW_IDS).map { it.id }
        assertFalse("allmusic-artist" in ids)
        assertFalse("wikipedia-artist" in ids)
        assertTrue("allmusic" in ids)
    }

    @Test
    fun defaultsAndStaleIds() {
        assertEquals(L.SERVICE_IDS, L.defaultServiceIds())
        assertEquals(listOf("wikipedia", "pitchfork", "allmusic"), L.defaultReviewIds())
        assertEquals(L.defaultReviewIds(), L.reviewLinks("Bowie", "Low").map { it.id })
        assertEquals(listOf("qobuz", "tidal"), L.sanitiseIds(listOf("qobuz", "napster", "tidal"), L.SERVICE_IDS))
    }

    // ------------------------------------------------------------- the routes

    private var fx: ApiFixture? = null
    private fun f(): ApiFixture = fx ?: ApiFixture().start().also { fx = it }
    @After fun tearDown() { fx?.stop() }

    @Test
    fun settingsListWhatCanBeLinkedAndWhatIsOn() {
        val j = f().json("/api/settings/share-links")
        val services = j.getJSONObject("services")
        assertEquals(7, services.getJSONArray("all").length())
        assertEquals("qobuz", services.getJSONArray("all").getJSONObject(0).getString("id"))
        assertEquals(7, services.getJSONArray("enabled").length())
        val reviews = j.getJSONObject("reviews")
        assertTrue(reviews.getJSONArray("all").getJSONObject(0).has("onByDefault"))
        assertEquals(3, reviews.getJSONArray("enabled").length())
    }

    @Test
    fun anEmptyListIsAllOffNotTheDefaults() {
        f().postJson("/api/settings/share-links", """{"services":[]}""")
        val j = f().json("/api/settings/share-links")
        assertEquals(0, j.getJSONObject("services").getJSONArray("enabled").length())
        // Reviews were not sent, so they are untouched.
        assertEquals(3, j.getJSONObject("reviews").getJSONArray("enabled").length())
        assertEquals(400, f().post("/api/settings/share-links", "{}").first)
    }

    @Test
    fun theCardsAnswerCarriesTheLinksTheSettingsAllow() {
        f().postJson("/api/settings/share-links", """{"services":["tidal","spotify","napster"],"reviews":["allmusic"]}""")
        val links = f().json("/api/album/extras?title=Dummy&artist=Portishead&fast=1").getJSONObject("links")
        val services = links.getJSONArray("services")
        assertEquals(listOf("tidal", "spotify"), (0 until services.length()).map { services.getJSONObject(it).getString("id") })
        assertEquals("https://open.spotify.com/search/Portishead%20Dummy", services.getJSONObject(1).getString("url"))
        val reviews = links.getJSONArray("reviews")
        assertEquals(1, reviews.length())
        val r = reviews.getJSONObject(0)
        // The chip label and kind the page draws.
        assertEquals("AllMusic", r.getString("chip"))
        assertEquals("album", r.getString("kind"))
        // Building links costs no network at all.
        assertTrue(f().outboundCalls.isEmpty())
    }

    @Test
    fun theQobuzChipUsesTheVisitorsStorefront() {
        val conn = java.net.URL(f().app.rootUrl + "/api/album/extras?title=Dummy&artist=Portishead&fast=1")
            .openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9")
        val j = JSONObject(conn.inputStream.bufferedReader().readText())
        val qobuz = j.getJSONObject("links").getJSONArray("services").getJSONObject(0)
        assertEquals("qobuz", qobuz.getString("id"))
        assertTrue(qobuz.getString("url"), qobuz.getString("url").startsWith("https://www.qobuz.com/fr-fr/search/"))
    }
}
