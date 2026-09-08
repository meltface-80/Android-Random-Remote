package com.musicd.lite.android

import android.media.session.PlaybackState
import com.musicd.lite.roon.Zone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the session tells Google it can do.
 *
 * This is not a style question. Google's guide for Assistant and media apps
 * names the actions a MUSIC app must support — play, pause, stop, skip to next,
 * skip to previous — and this session shipped for four releases advertising
 * every one of them except STOP, while implementing onStop() the whole time.
 * The gap was invisible: nothing on the phone shows which bits a session set,
 * and transport kept working because the actions that ARE advertised are the
 * ones the lock screen uses.
 *
 * So the requirement is pinned here, against the documented list rather than
 * against whatever the code happens to do. Dropping an action from ACTIONS
 * fails the first test by name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionActionsTest {

    /**
     * The actions Google's guide lists as required for a music app, by the
     * names it uses for them.
     */
    private val requiredForMusic = mapOf(
        "play" to PlaybackState.ACTION_PLAY,
        "pause" to PlaybackState.ACTION_PAUSE,
        "stop" to PlaybackState.ACTION_STOP,
        "skip to next" to PlaybackState.ACTION_SKIP_TO_NEXT,
        "skip to previous" to PlaybackState.ACTION_SKIP_TO_PREVIOUS
    )

    private fun zone(
        play: Boolean = true,
        pause: Boolean = true,
        next: Boolean = true,
        previous: Boolean = true
    ): Zone = Zone.parse(
        JSONObject(
            """
            {"zone_id":"z1","display_name":"Study","state":"playing",
             "is_play_allowed":$play,"is_pause_allowed":$pause,
             "is_next_allowed":$next,"is_previous_allowed":$previous,
             "outputs":[{"output_id":"o1","display_name":"Study"}]}
            """.trimIndent()
        )
    )

    @Test
    fun theDefaultSetOffersEveryActionGoogleRequiresOfAMusicApp() {
        for ((name, bit) in requiredForMusic) {
            assertTrue(
                "a music app must advertise $name, and ACTIONS does not",
                NowPlayingSession.ACTIONS and bit != 0L
            )
        }
    }

    /** The search action is what makes "play something on this app" possible. */
    @Test
    fun theDefaultSetAlsoOffersPlayFromSearch() {
        assertTrue(NowPlayingSession.ACTIONS and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L)
    }

    /**
     * A zone Roon has not described yet must not look LESS capable than one it
     * has, or the first thing an assistant sees is a session that can do
     * nothing.
     */
    @Test
    fun anUnknownZoneAdvertisesTheFullSet() {
        assertEquals(NowPlayingSession.ACTIONS, NowPlayingSession.actionsFor(null))
    }

    @Test
    fun aZoneThatAllowsEverythingMatchesTheDefaultSet() {
        assertEquals(NowPlayingSession.ACTIONS, NowPlayingSession.actionsFor(zone()))
    }

    /**
     * Roon answers a disallowed command by doing nothing at all, so a button
     * offered for one is a button that silently fails.
     */
    @Test
    fun anActionRoonWouldRefuseIsNotOffered() {
        val noSkipping = NowPlayingSession.actionsFor(zone(next = false, previous = false))
        assertEquals(0L, noSkipping and PlaybackState.ACTION_SKIP_TO_NEXT)
        assertEquals(0L, noSkipping and PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        assertTrue("play is still allowed here", noSkipping and PlaybackState.ACTION_PLAY != 0L)
    }

    /**
     * Roon has no stop, so onStop() pauses — and an action offered on a zone
     * that refuses pause would be one more silent button.
     */
    @Test
    fun stopFollowsPauseBecauseThatIsWhatItDoes() {
        val noPause = NowPlayingSession.actionsFor(zone(pause = false))
        assertEquals(0L, noPause and PlaybackState.ACTION_STOP)
        assertTrue(NowPlayingSession.actionsFor(zone()) and PlaybackState.ACTION_STOP != 0L)
    }

    /** Search is never gated: a stopped zone is exactly where you would ask. */
    @Test
    fun searchSurvivesAZoneThatAllowsNothing() {
        val dead = NowPlayingSession.actionsFor(
            zone(play = false, pause = false, next = false, previous = false)
        )
        assertEquals(PlaybackState.ACTION_PLAY_FROM_SEARCH, dead)
    }
}
