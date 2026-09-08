package com.musicd.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The naming rule, against the names that actually failed on a phone.
 *
 * Checked here rather than only where it is applied, so the rule can be seen to
 * discriminate: every spelling below was tried out loud and lost, and each one
 * must be rejected for the specific reason it lost.
 */
class VoiceNameTest {

    @Test
    fun everySpellingThatFailedOnAPhoneIsRejectedForTheRightReason() {
        assertEquals("is more than one word", VoiceName.problemWith("MusicD Remote Lite"))
        assertEquals("ends in a stranded capital letter", VoiceName.problemWith("MusicD"))
        assertEquals("is a word Google has already claimed", VoiceName.problemWith("Remote"))
    }

    @Test
    fun theOtherWaysAGoodNameGoesWrong() {
        assertEquals("is too long to say", VoiceName.problemWith("MusicDRemoteLite"))
        assertEquals("has something other than letters in it", VoiceName.problemWith("Remote2"))
        assertEquals("has space around it", VoiceName.problemWith(" Jukebox"))
        assertEquals("is empty", VoiceName.problemWith(""))
    }

    /** Case is not what makes a word Google's, so neither is it here. */
    @Test
    fun aClaimedWordIsRejectedHoweverItIsCapitalised() {
        for (spelling in listOf("Remote", "remote", "REMOTE", "ReMoTe")) {
            assertEquals(
                "is a word Google has already claimed", VoiceName.problemWith(spelling)
            )
        }
    }

    @Test
    fun theNamesThatWereActuallyConsideredAllPass() {
        for (name in listOf("Jukebox", "Roulette", "Tonearm", "Turntable", "Gramophone")) {
            assertNull("$name should be a usable voice target", VoiceName.problemWith(name))
        }
    }

    /**
     * A one-letter name is not rejected by the stranded-capital rule, which
     * needs two characters to look at. Worth pinning: the rule reads back to
     * front and an off-by-one there would crash rather than fail.
     */
    @Test
    fun aSingleLetterIsHandledRatherThanCrashing() {
        assertNull(VoiceName.problemWith("A"))
    }
}
