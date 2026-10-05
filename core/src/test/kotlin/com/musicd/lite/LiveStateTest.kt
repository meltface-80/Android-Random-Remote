package com.musicd.lite

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The revisions a screen follows, and the long poll that serves them.
 *
 * The page diffs these to decide what to re-read, so the two promises worth
 * pinning are: a change moves the token, and a waiting request is answered
 * when — and only when — something moved (or the clock says a day turned).
 * A wait that returned at once every time would pass every "changed" test and
 * still turn the long poll back into a busy loop, so the waits are timed.
 */
class LiveStateTest {

    private val utc = ZoneId.of("UTC")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(utc).toInstant().toEpochMilli()

    @Test
    fun bumpingAKindMovesTheTokenAndOnlyThatRevision() {
        val live = LiveState({ 0L }, { utc })
        val before = live.revisions()
        live.bump("plays")
        val after = live.revisions()
        assertNotEquals(live.token(before), live.token(after))
        assertNotEquals(before["plays"], after["plays"])
        for (k in before.keys - "plays") assertEquals(k, before[k], after[k])
    }

    @Test(expected = IllegalArgumentException::class)
    fun anUnknownKindIsAMistakeNotANewRevision() {
        LiveState({ 0L }, { utc }).bump("playz")
    }

    @Test
    fun theSnapshotFollowsTheIndexWithoutBeingBumped() {
        val built = AtomicLong(1L)
        val live = LiveState({ built.get() }, { utc })
        val t0 = live.token()
        built.set(2L)
        val r = live.revisions()
        assertNotEquals(t0, live.token(r))
        assertEquals("2", r["snapshot"])
        // The library revision carries the snapshot too, so a rebuilt index
        // re-reads everything derived from it.
        assertTrue(r["library"]!!.startsWith("2."))
    }

    @Test
    fun aWaitWithNothingToCompareAnswersAtOnce() {
        val live = LiveState({ 0L }, { utc })
        val t = System.nanoTime()
        live.await(null, 5_000)
        assertTrue("took ${(System.nanoTime() - t) / 1_000_000}ms", (System.nanoTime() - t) < 1_000_000_000L)
    }

    @Test
    fun aStaleTokenAnswersAtOnce() {
        val live = LiveState({ 0L }, { utc })
        val old = live.token()
        live.bump("later")
        val t = System.nanoTime()
        val rev = live.await(old, 5_000)
        assertTrue((System.nanoTime() - t) < 1_000_000_000L)
        assertNotEquals(old, live.token(rev))
    }

    @Test
    fun aCurrentTokenIsHeldUntilSomethingMoves() {
        val live = LiveState({ 0L }, { utc })
        val current = live.token()
        Thread {
            Thread.sleep(300)
            live.bump("settings")
        }.start()
        val t = System.nanoTime()
        val rev = live.await(current, 5_000)
        val ms = (System.nanoTime() - t) / 1_000_000
        assertTrue("answered after ${ms}ms — before anything moved", ms >= 250)
        assertTrue("answered after ${ms}ms — long after the change", ms < 3_000)
        assertNotEquals(current, live.token(rev))
    }

    @Test
    fun aRebuiltIndexWakesAWaitingRequest() {
        val built = AtomicLong(1L)
        val live = LiveState({ built.get() }, { utc })
        val current = live.token()
        Thread {
            Thread.sleep(200)
            built.set(2L)
            live.snapshotChanged()
        }.start()
        val t = System.nanoTime()
        val rev = live.await(current, 5_000)
        assertTrue((System.nanoTime() - t) / 1_000_000 < 3_000)
        assertEquals("2", rev["snapshot"])
    }

    @Test
    fun aQuietWaitEndsAtItsTimeoutUnchanged() {
        val live = LiveState({ 0L }, { utc })
        val current = live.token()
        val t = System.nanoTime()
        val rev = live.await(current, 200)
        val ms = (System.nanoTime() - t) / 1_000_000
        assertTrue("answered after ${ms}ms", ms in 150..2_000)
        assertEquals(current, live.token(rev))
    }

    @Test
    fun albumOfTheDayTurnsAMinuteAfterTheDay() {
        val clock = AtomicLong(at(2026, 10, 5, 0, 0, 30))
        val live = LiveState({ 0L }, { utc }, { clock.get() })
        var r = live.revisions()
        assertEquals("2026-10-05", r["day"])
        assertEquals("2026-10-04", r["aotd"])
        clock.set(at(2026, 10, 5, 0, 1, 30))
        r = live.revisions()
        assertEquals("2026-10-05", r["day"])
        assertEquals("2026-10-05", r["aotd"])
    }

    @Test
    fun theWaitNeverOutlastsTheNextClockTurn() {
        // Five seconds before midnight, a 25-second wait must give up at the
        // turn, or "day" would move while every page slept through it.
        val clock = AtomicLong(at(2026, 10, 5, 23, 59, 55))
        val live = LiveState({ 0L }, { utc }, { clock.get() })
        val turn = live.msUntilClockTurn()
        assertTrue("$turn ms", turn in 1..5_000)
        // And between the two turns, the 00:01 one is next.
        clock.set(at(2026, 10, 6, 0, 0, 30))
        assertEquals(30_000L, live.msUntilClockTurn())
    }

    @Test
    fun theAnswerCarriesTheRevisionsAndTheTokenToSendBack() {
        val live = LiveState({ 7L }, { utc })
        val j = live.toJson()
        val rev = j.getJSONObject("rev")
        for (k in listOf("snapshot", "library", "dates", "plays", "settings", "labels",
                         "picks", "discover", "later", "day", "aotd")) {
            assertTrue("missing $k", rev.has(k))
            // The page string-compares them.
            assertTrue(rev.get(k) is String)
        }
        assertEquals(live.token(), j.getString("token"))
    }
}
