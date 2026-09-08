package com.musicd.lite.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The launcher label is a voice target, and this is what keeps it usable as one.
 *
 * The name Google matches for "open X" and "play something on X" is exactly
 * this string, and three attempts failed here in three different ways before
 * one worked. Each failure taught a rule, and each rule is easy to undo by
 * accident, because app_name is the obvious place to type a product name:
 *
 *   - "MusicD Remote Lite" was heard as "Music d remote light" — too many
 *     words, and a trailing capital with nowhere to go.
 *   - "Roon" is heard as "rune". A name outside the speech model's lexicon is
 *     replaced by the nearest real word and never reaches the matcher.
 *   - "Remote" transcribes perfectly and still opened nothing, because
 *     "open remote" is how Google opens its own TV remote.
 *
 * So the rule is asserted rather than left in a comment, and it is checked
 * against a table of real names first, so the test can be seen to discriminate:
 * every spelling that actually failed on a phone is rejected here by the same
 * code that then reads the resource this app ships.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppNameTest {

    /**
     * Words Google resolves to something of its own before it looks at what is
     * installed. Learned the hard way, one entry at a time — "remote" is here
     * because a release shipped with it and opened the TV remote instead.
     *
     * Not exhaustive and it cannot be: nobody publishes this list, and it grows
     * whenever Google adds a feature. It is a floor, not a guarantee.
     */
    private val claimedByGoogle = setOf(
        "remote", "music", "radio", "play", "player", "home", "assistant",
        "settings", "camera", "clock", "phone", "cast", "search", "maps", "photos"
    )

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
        label.lowercase() in claimedByGoogle -> "is a word Google has already claimed"
        else -> null
    }

    @Test
    fun theRuleRejectsEverySpellingThatFailedOnAPhone() {
        assertEquals("is more than one word", problemWith("MusicD Remote Lite"))
        assertEquals("ends in a stranded capital letter", problemWith("MusicD"))
        assertEquals("is a word Google has already claimed", problemWith("Remote"))
        assertEquals("is too long to say", problemWith("MusicDRemoteLite"))
        assertEquals("has something other than letters in it", problemWith("Remote2"))
        assertEquals("is empty", problemWith(""))
    }

    /** Case is not what makes a name Google's, so neither is it here. */
    @Test
    fun aClaimedWordIsRejectedHoweverItIsCapitalised() {
        for (spelling in listOf("Remote", "remote", "REMOTE", "ReMoTe")) {
            assertEquals(
                "is a word Google has already claimed", problemWith(spelling)
            )
        }
    }

    @Test
    fun theRuleAcceptsNamesThatWereActuallyConsidered() {
        for (name in listOf("Jukebox", "Tonearm", "Turntable", "Gramophone")) {
            assertNull("$name should be a usable voice target", problemWith(name))
        }
    }

    @Test
    fun theLabelThisAppShipsIsSayable() {
        val label = RuntimeEnvironment.getApplication().getString(R.string.app_name)
        assertNull("the launcher label \"$label\" ${problemWith(label)}", problemWith(label))
    }
}
