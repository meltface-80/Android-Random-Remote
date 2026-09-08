package com.musicd.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A launcher icon with no screen behind it has one failure mode that matters:
 * it does nothing, and says nothing about why.
 *
 * That happens if the command is a phrase the parser does not know, so the
 * phrase is checked here against the real parser rather than by reading it.
 */
class VoiceVerbsTest {

    @Test
    fun everyVerbSaysSomethingTheParserUnderstands() {
        for (verb in VoiceVerbs.ALL) {
            val parsed = Voice.parse(verb.command)
            assertFalse(
                "\"${verb.command}\" would give ${verb.label} an icon that does nothing",
                parsed is VoiceCommand.Unknown
            )
        }
    }

    /** Roulette is the one that exists, and it must mean a random album. */
    @Test
    fun rouletteAsksForARandomAlbum() {
        val roulette = VoiceVerbs.ALL.single { it.label == "Roulette" }
        assertEquals(VoiceCommand.Random, Voice.parse(roulette.command))
    }

    /**
     * The label is spoken out loud — "Ok Google, open Roulette" — so it is a
     * voice target under exactly the same rule as the app's own name.
     */
    @Test
    fun everyVerbCanBeSaidToAPhone() {
        for (verb in VoiceVerbs.ALL) {
            assertNull(
                "the verb \"${verb.label}\" ${VoiceName.problemWith(verb.label)}",
                VoiceName.problemWith(verb.label)
            )
        }
    }

    @Test
    fun aliasesAreDistinctAndLiveWhereTheManifestPutsThem() {
        val aliases = VoiceVerbs.ALL.map { it.alias }
        assertEquals("two verbs sharing a class name", aliases.size, aliases.toSet().size)
        for (alias in aliases) {
            assertTrue(
                "$alias is not under ${VoiceVerbs.PACKAGE}",
                alias.startsWith(VoiceVerbs.PACKAGE)
            )
        }
    }

    @Test
    fun aLauncherComponentIsMatchedBackToItsVerb() {
        val roulette = VoiceVerbs.ALL.first()
        assertEquals(roulette, VoiceVerbs.forComponent(roulette.alias))
        assertNull(VoiceVerbs.forComponent("com.musicd.lite.android.MainActivity"))
        assertNull(VoiceVerbs.forComponent(null))
    }
}
