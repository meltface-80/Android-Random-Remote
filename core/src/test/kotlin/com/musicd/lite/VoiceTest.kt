package com.musicd.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the dial hears, and what it decides that meant.
 *
 * The two cases that prompted all of this are [playMusicResumesRatherThanSearchingForAnAlbumCalledMusic]
 * and [volumeCommandsAreNeverSearches]. Both are the same mistake: stripping
 * the verb and searching for what is left, which turns every control phrase
 * into the title of a record nobody owns.
 */
class VoiceTest {

    private fun parse(s: String) = Voice.parse(s)

    // ------------------------------------------------------- the two it broke

    @Test
    fun playMusicResumesRatherThanSearchingForAnAlbumCalledMusic() {
        assertEquals(VoiceCommand.Resume, parse("play music"))
        assertEquals(VoiceCommand.Resume, parse("Play music"))
        assertEquals(VoiceCommand.Resume, parse("play the music"))
        assertEquals(VoiceCommand.Resume, parse("resume"))
        assertEquals(VoiceCommand.Resume, parse("continue playing"))
    }

    @Test
    fun volumeCommandsAreNeverSearches() {
        assertEquals(VoiceCommand.Volume(up = true), parse("turn up volume"))
        assertEquals(VoiceCommand.Volume(up = true), parse("turn up the volume"))
        assertEquals(VoiceCommand.Volume(up = true), parse("volume up"))
        assertEquals(VoiceCommand.Volume(up = true), parse("turn it up"))
        assertEquals(VoiceCommand.Volume(up = true), parse("louder"))

        assertEquals(VoiceCommand.Volume(up = false), parse("turn down volume"))
        assertEquals(VoiceCommand.Volume(up = false), parse("turn down the volume"))
        assertEquals(VoiceCommand.Volume(up = false), parse("volume down"))
        assertEquals(VoiceCommand.Volume(up = false), parse("turn it down"))
        assertEquals(VoiceCommand.Volume(up = false), parse("quieter"))
    }

    // -------------------------------------------------------------- the verbs

    @Test
    fun pauseIsPause() {
        assertEquals(VoiceCommand.Pause, parse("pause music"))
        assertEquals(VoiceCommand.Pause, parse("pause"))
        assertEquals(VoiceCommand.Pause, parse("stop the music"))
    }

    @Test
    fun playFollowedByANameIsASearch() {
        assertEquals(VoiceCommand.Play("Iron Maiden"), parse("play Iron Maiden"))
        assertEquals(VoiceCommand.Play("Mezzanine"), parse("play the album Mezzanine"))
        assertEquals(VoiceCommand.Play("Neil Young"), parse("play some Neil Young"))
        assertEquals(VoiceCommand.Play("Massive Attack"), parse("put on some Massive Attack"))
        assertEquals(VoiceCommand.Play("Radiohead"), parse("listen to Radiohead"))
    }

    @Test
    fun aPhraseStartingWithAControlPhraseIsStillASearch() {
        // "play music by Neil Young" begins with "play music", which is the
        // resume phrase. Matching control words as a PREFIX rather than as the
        // whole utterance would resume instead of playing Neil Young.
        assertEquals(VoiceCommand.Play("Neil Young"), parse("play music by Neil Young"))
        assertEquals(VoiceCommand.Play("Neil Young"), parse("play some music by Neil Young"))
        // And a record whose name happens to start with a control word.
        assertEquals(VoiceCommand.Play("Pause Ahead"), parse("play Pause Ahead"))
        assertEquals(VoiceCommand.Play("Volume One"), parse("play Volume One"))
    }

    @Test
    fun aBareNameIsASearch() {
        assertEquals(VoiceCommand.Play("Iron Maiden"), parse("Iron Maiden"))
        assertEquals(
            VoiceCommand.Play("The Number of the Beast"),
            parse("The Number of the Beast")
        )
    }

    @Test
    fun tracksAndMuting() {
        assertEquals(VoiceCommand.Next, parse("next track"))
        assertEquals(VoiceCommand.Next, parse("skip"))
        assertEquals(VoiceCommand.Previous, parse("previous track"))
        assertEquals(VoiceCommand.Previous, parse("go back"))
        assertEquals(VoiceCommand.Mute(on = true), parse("mute"))
        assertEquals(VoiceCommand.Mute(on = false), parse("unmute"))
    }

    @Test
    fun randomIsTheSignatureAction() {
        assertEquals(VoiceCommand.Random, parse("surprise me"))
        assertEquals(VoiceCommand.Random, parse("play something random"))
        assertEquals(VoiceCommand.Random, parse("random album"))
        assertEquals(VoiceCommand.Random, parse("play anything"))
    }

    // ------------------------------------------------ what recognisers do

    @Test
    fun punctuationAndCourtesyDoNotBreakACommand() {
        // A recogniser hands back both of these for the same request.
        assertEquals(VoiceCommand.Volume(up = true), parse("Turn up the volume."))
        assertEquals(VoiceCommand.Volume(up = true), parse("turn up the volume please"))
        assertEquals(VoiceCommand.Pause, parse("Pause, please."))
        assertEquals(VoiceCommand.Play("Iron Maiden"), parse("  play Iron Maiden?  "))
    }

    @Test
    fun nothingUsableIsNotGuessedAt() {
        // The phrase is reported trimmed, so an all-whitespace utterance and
        // an empty one are the same nothing.
        assertEquals(VoiceCommand.Unknown(""), parse(""))
        assertEquals(VoiceCommand.Unknown(""), parse("   "))
        // Trailing off after the lead-in. "play the album" strips to "the
        // album", which is a perfectly good search term and the name of
        // nothing — so without a guard the dial starts whatever came closest.
        assertEquals(VoiceCommand.Unknown("play the album"), parse("play the album"))
        assertEquals(VoiceCommand.Unknown("play me some"), parse("play me some"))
        assertEquals(VoiceCommand.Unknown("put on a record"), parse("put on a record"))

        // But the escape hatch still works: an album really called Music, or
        // Record, is reachable by naming the kind first.
        assertEquals(VoiceCommand.Play("Music"), parse("play the album Music"))
    }

    // ------------------------------------------------------ naming the room

    private val rooms = listOf("Study", "Kitchen", "Kitchen Speakers", "Living Room")

    private fun split(s: String) = Voice.splitZone(s, rooms)

    @Test
    fun aRoomAtTheEndIsTakenOffTheSentence() {
        val heard = split("play Mezzanine in the kitchen")
        assertEquals("Kitchen", heard.zone)
        // The room must not reach the search, or the album looked for is
        // "Mezzanine In The Kitchen" and nothing matches it.
        assertEquals("play Mezzanine", heard.rest)
    }

    @Test
    fun everyWayOfNamingTheRoomIsUnderstood() {
        for (phrase in listOf(
            "pause in the kitchen", "pause in kitchen", "pause on the kitchen",
            "pause to the kitchen", "pause through the kitchen", "pause on kitchen"
        )) {
            assertEquals(phrase, "Kitchen", split(phrase).zone)
            assertEquals(phrase, "pause", split(phrase).rest)
        }
    }

    @Test
    fun aRoomOfSeveralWordsIsMatchedWhole() {
        val heard = split("play Kind of Blue in the living room")
        assertEquals("Living Room", heard.zone)
        assertEquals("play Kind of Blue", heard.rest)
    }

    /**
     * With both "Kitchen" and "Kitchen Speakers" in the house, the room asked
     * for is the one whose whole name is at the end — matching "Kitchen" here
     * would leave "speakers" behind to be searched for as part of the title.
     *
     * (splitZone also prefers the longest match outright. That is insurance
     * for a longer list of lead-ins rather than something this can provoke:
     * with the eight it has, a name only ever matches the tail one way, so no
     * test here fails without it. Said plainly instead of dressed up in a
     * test that would pass either way.)
     */
    @Test
    fun aRoomWhoseNameContainsAnotherRoomsIsMatchedWhole() {
        val heard = split("play Mezzanine in the kitchen speakers")
        assertEquals("Kitchen Speakers", heard.zone)
        assertEquals("play Mezzanine", heard.rest)
    }

    @Test
    fun courtesyAfterTheRoomIsNotPartOfIt() {
        val heard = split("play Mezzanine in the kitchen please")
        assertEquals("Kitchen", heard.zone)
        assertEquals("play Mezzanine", heard.rest)
    }

    /**
     * The guard that makes this safe at all: nothing is stripped unless it
     * names a room that really exists, so an album can be called anything.
     */
    @Test
    fun aRoomNobodyHasIsJustWordsInATitle() {
        val heard = split("play Live at the Garage")
        assertNull(heard.zone)
        assertEquals("play Live at the Garage", heard.rest)
    }

    /**
     * "Play the album In The Kitchen" is about a record. Taking the room off
     * would leave "play the album", which means nothing — so the match is
     * refused and the title survives whole.
     */
    @Test
    fun aTitleThatIsAlsoARoomSurvivesWhenTheKindIsNamed() {
        val heard = split("play the album In The Kitchen")
        assertNull(heard.zone)
        assertEquals(VoiceCommand.Play("In The Kitchen"), Voice.parse(heard.rest))
    }

    /**
     * "Play in the kitchen" really is a room, and what is left — "play" —
     * resumes. That is what somebody standing in a kitchen means by it.
     */
    @Test
    fun aBareVerbBeforeARoomIsAControlNotASearch() {
        val heard = split("play in the kitchen")
        assertEquals("Kitchen", heard.zone)
        assertEquals(VoiceCommand.Resume, Voice.parse(heard.rest))
    }

    @Test
    fun aRoomWithNoCommandInFrontOfItIsNotObeyed() {
        val heard = split("in the kitchen")
        assertNull(heard.zone)
        assertEquals("in the kitchen", heard.rest)
    }

    @Test
    fun withNoZonesKnownNothingIsStripped() {
        val heard = Voice.splitZone("play Mezzanine in the kitchen", emptyList())
        assertNull(heard.zone)
        assertEquals("play Mezzanine in the kitchen", heard.rest)
    }
}
