package com.musicd.lite

import com.musicd.lite.roon.Zone
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Queue's "played earlier": what each zone played, recorded from the zone
 * feed at the moment a track leaves, and the two routes that put one back.
 */
class QueueHistoryTest {

    private fun zone(track: String?, album: String = "Dummy", state: String = "playing",
                     seek: Int = 0, length: Int = 200, id: String = "z1"): Zone {
        val np = if (track == null) "" else """,
            "now_playing":{"three_line":{"line1":"$track","line2":"Portishead","line3":"$album"},
              "length":$length,"seek_position":$seek,"image_key":"img-$album"}"""
        return Zone.parse(JSONObject("""{"zone_id":"$id","display_name":"Study","state":"$state","outputs":[]$np}"""))
    }

    @Test
    fun theOutgoingTrackIsRecordedWithHowMuchOfItPlayed() {
        val h = QueueHistory()
        var t = 1_000_000L
        h.observe(listOf(zone("Mysterons", seek = 0)), t)
        // A second at a time, as the seek feed reports it.
        for (s in 1..120) h.observe(listOf(zone("Mysterons", seek = s)), t + s * 1000L)
        t += 121_000
        h.observe(listOf(zone("Sour Times", seek = 0)), t)
        val got = h.recent("z1")
        assertEquals(1, got.size)
        val e = got[0]
        assertEquals("Mysterons", e.track)
        assertEquals("Dummy", e.album)
        assertEquals(120, e.elapsed)
        assertTrue("two minutes of a 200s track counts as played", e.played)
    }

    @Test
    fun aSkipIsRecordedAsASkip() {
        val h = QueueHistory()
        h.observe(listOf(zone("Mysterons", seek = 0)), 0)
        for (s in 1..12) h.observe(listOf(zone("Mysterons", seek = s)), s * 1000L)
        h.observe(listOf(zone("Sour Times")), 13_000)
        val e = h.recent("z1")[0]
        assertEquals(12, e.elapsed)
        assertFalse(e.played)
    }

    @Test
    fun seekingAheadIsNotListening() {
        val h = QueueHistory()
        h.observe(listOf(zone("Mysterons", seek = 0)), 0)
        h.observe(listOf(zone("Mysterons", seek = 180)), 1000)   // dragged to the end
        h.observe(listOf(zone("Sour Times")), 2000)
        assertEquals(0, h.recent("z1")[0].elapsed)
    }

    @Test
    fun pausingIsNotADeparture() {
        val h = QueueHistory()
        h.observe(listOf(zone("Mysterons")), 0)
        h.observe(listOf(zone("Mysterons", state = "paused")), 1000)
        h.observe(listOf(zone("Mysterons")), 60_000)
        assertTrue("a paused track was filed as played earlier", h.recent("z1").isEmpty())
    }

    @Test
    fun goingBackToATrackIsAnotherPlay() {
        // Only an IDENTICAL entry repeated within two seconds is collapsed
        // (Rouen's rule); going back to a track is listening to it again.
        val h = QueueHistory()
        h.observe(listOf(zone("Mysterons")), 0)
        h.observe(listOf(zone("Sour Times")), 10_000)
        h.observe(listOf(zone("Mysterons")), 20_000)
        h.observe(listOf(zone("Strangers")), 30_000)
        assertEquals(listOf("Mysterons", "Sour Times", "Mysterons"), h.recent("z1").map { it.track }.reversed())
    }

    @Test
    fun theHistoryIsBoundedAndNewestFirst() {
        val h = QueueHistory(max = 5)
        for (i in 0..9) h.observe(listOf(zone("T$i")), i * 10_000L)
        val got = h.recent("z1")
        assertEquals(5, got.size)
        assertEquals("T8", got.first().track)
        assertEquals("T4", got.last().track)
        assertEquals(listOf("T8", "T7"), h.recent("z1", 2).map { it.track })
    }

    @Test
    fun playNextIsSentBackwardsSoItLandsInPickedOrder() {
        assertEquals(listOf("C", "B", "A"), QueueHistory.sendOrderFor("play_next", listOf("A", "B", "C")))
        assertEquals(listOf("A", "B", "C"), QueueHistory.sendOrderFor("queue", listOf("A", "B", "C")))
    }

    @Test
    fun theScrobblersRuleDecidesWhatCountsAsPlayed() {
        assertFalse(QueueHistory.playCounted(29, 40))
        assertTrue(QueueHistory.playCounted(30, 60))
        assertFalse(QueueHistory.playCounted(100, 600))
        assertTrue(QueueHistory.playCounted(240, 1200))   // four minutes of a long side
    }

    // ------------------------------------------------------------- the routes

    private var fx: ApiFixture? = null
    private fun f(): ApiFixture = fx ?: ApiFixture().start().also { fx = it }
    @After fun tearDown() { fx?.stop() }

    private fun played(vararg tracks: Pair<String, String>) {
        var t = 0L
        for ((track, album) in tracks) {
            f().app.queueHistory.observe(listOf(zone(track, album = album)), t)
            t += 10_000
        }
        f().app.queueHistory.observe(listOf(zone("Now", album = "Kid A")), t)
    }

    @Test
    fun theQueueCarriesWhatAlreadyPlayed() {
        played("Opening" to "Dummy", "Closer" to "Third")
        val history = f().json("/api/queue?zone=z1").getJSONArray("history")
        assertEquals(2, history.length())
        val newest = history.getJSONObject(0)
        assertEquals("Closer", newest.getString("track"))
        for (k in listOf("track", "artist", "album", "image_key", "duration", "elapsed", "played", "ts")) {
            assertTrue("history entry has no $k", newest.has(k))
        }
    }

    @Test
    fun aPlayedTrackGoesBackInNext() {
        val j = f().postJson(
            "/api/queue/play-history-next",
            """{"zone_or_output_id":"z1","track":"Middle Eight","artist":"Portishead","album":"Dummy"}"""
        )
        assertEquals("Middle Eight", j.getString("track"))
        assertEquals("Dummy", j.getString("album"))
        assertEquals(listOf("play_next:track:1:1@z1"), f().core.invoked)
    }

    @Test
    fun aTrackFromOutsideTheLibraryIsUnresolvedNotAnError() {
        val (code, text) = f().post(
            "/api/queue/play-history-next",
            """{"zone_or_output_id":"z1","track":"Glory Box","artist":"Someone","album":"A Radio Track"}"""
        )
        assertEquals(404, code)
        assertTrue(JSONObject(text).getBoolean("unresolved"))
        assertTrue(f().core.invoked.isEmpty())
    }

    @Test
    fun aSelectionPlaysNextInThePickedOrder() {
        val j = f().postJson(
            "/api/queue/history-multi",
            """{"zone_or_output_id":"z1","kind":"play_next","tracks":[
                {"track":"Opening","artist":"Portishead","album":"Dummy"},
                {"track":"Closer","artist":"Portishead","album":"Third"}]}"""
        )
        assertEquals(2, j.getInt("queued"))
        // Sent backwards, so Add Next stacks them back into the picked order.
        assertEquals(listOf("play_next:track:3:2@z1", "play_next:track:1:0@z1"), f().core.invoked)
    }

    @Test
    fun aSelectionQueuedGoesInAsPicked() {
        f().postJson(
            "/api/queue/history-multi",
            """{"zone_or_output_id":"z1","kind":"queue","tracks":[
                {"track":"Opening","artist":"Portishead","album":"Dummy"},
                {"track":"Closer","artist":"Portishead","album":"Third"}]}"""
        )
        assertEquals(listOf("queue:track:1:0@z1", "queue:track:3:2@z1"), f().core.invoked)
    }

    @Test
    fun aSelectionNamesWhatItCouldNotFindAndStillPlaysTheRest() {
        val j = f().postJson(
            "/api/queue/history-multi",
            """{"zone_or_output_id":"z1","kind":"queue","tracks":[
                {"track":"Opening","artist":"Portishead","album":"Dummy"},
                {"track":"Glory Box","artist":"Someone","album":"A Radio Track"}]}"""
        )
        assertEquals(1, j.getInt("queued"))
        assertEquals("Glory Box", j.getJSONArray("unresolved").getString(0))
    }

    @Test
    fun aSelectionIsBoundedAndNeedsAKind() {
        val many = (1..21).joinToString(",") { """{"track":"Opening","artist":"Portishead","album":"Dummy"}""" }
        assertEquals(400, f().post("/api/queue/history-multi", """{"zone_or_output_id":"z1","kind":"queue","tracks":[$many]}""").first)
        assertEquals(400, f().post("/api/queue/history-multi", """{"zone_or_output_id":"z1","kind":"play_now","tracks":[]}""").first)
        assertTrue(f().core.invoked.isEmpty())
    }
}
