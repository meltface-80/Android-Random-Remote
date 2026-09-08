package com.musicd.lite.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The launcher label is a voice target, and this is what keeps it one.
 *
 * "MusicD Remote Lite" came back from Google as "Music d remote light" and
 * resolved to nothing, because the name Google matches for "open X" and "play
 * something on X" is exactly this string. Two separate things were wrong with
 * it — three words to say, and a trailing capital letter that no speech model
 * has anywhere to put — and both are easy to reintroduce by accident, since
 * app_name is the obvious place to write a product name.
 *
 * So the rule is asserted rather than left in a comment. It is checked against
 * a table of names first, so the test can be seen to discriminate: the two
 * spellings that actually failed on a phone are rejected here by the same code
 * that then reads the real resource.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppNameTest {

    /**
     * Why a name is no good for speech, or null when it is fine.
     *
     * The length ceiling is generous on purpose — "Turntable" and "Gramophone"
     * are both perfectly sayable. It is there to catch the other way this goes
     * wrong, which is squashing the words together rather than choosing one.
     */
    private fun problemWith(label: String): String? = when {
        label.isEmpty() -> "is empty"
        label.trim() != label -> "has space around it"
        label.any { it.isWhitespace() } -> "is more than one word"
        !label.all { it.isLetter() } -> "has something other than letters in it"
        label.length > 12 -> "is too long to say"
        label.length >= 2 && label.last().isUpperCase() && label[label.length - 2].isLowerCase() ->
            "ends in a stranded capital letter"
        else -> null
    }

    @Test
    fun theRuleRejectsTheSpellingsThatFailedOnAPhone() {
        assertEquals("is more than one word", problemWith("MusicD Remote Lite"))
        assertEquals("ends in a stranded capital letter", problemWith("MusicD"))
        assertEquals("is too long to say", problemWith("MusicDRemoteLite"))
        assertEquals("has something other than letters in it", problemWith("Remote2"))
        assertEquals("is empty", problemWith(""))
    }

    @Test
    fun theRuleAcceptsNamesThatWereActuallyConsidered() {
        for (name in listOf("Remote", "Jukebox", "Tonearm", "Turntable", "Gramophone")) {
            assertNull("$name should be a usable voice target", problemWith(name))
        }
    }

    @Test
    fun theLabelThisAppShipsIsSayable() {
        val label = RuntimeEnvironment.getApplication().getString(R.string.app_name)
        assertNull("the launcher label \"$label\" ${problemWith(label)}", problemWith(label))
    }
}
