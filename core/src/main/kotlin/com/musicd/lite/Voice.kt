package com.musicd.lite

/**
 * What somebody actually meant when they spoke to the dial.
 *
 * The parsing lives here, in the one module with tests, because it is easy to
 * get quietly wrong in a way that is maddening in a room: the failure is not a
 * crash, it is the wrong record starting.
 */
sealed class VoiceCommand {

    /** Find this in the library and play it. */
    data class Play(val query: String) : VoiceCommand()

    /** Carry on from where it was paused. */
    object Resume : VoiceCommand()

    object Pause : VoiceCommand()

    /** A step of the output's range, up or down. */
    data class Volume(val up: Boolean) : VoiceCommand()

    object Next : VoiceCommand()
    object Previous : VoiceCommand()

    data class Mute(val on: Boolean) : VoiceCommand()

    /** A random album, the app's signature action. */
    object Random : VoiceCommand()

    /** Heard, but not understood. */
    data class Unknown(val heard: String) : VoiceCommand()
}

/**
 * Turns a spoken phrase into a command.
 *
 * The whole difficulty is that "play" starts both a search and a resume, and a
 * naive strip-the-verb-and-search treats every control phrase as the title of a
 * record. "Play music" would look for an album called Music, and "turn up
 * volume" would look for one called Volume — the two cases that prompted this.
 *
 * So control phrases are matched as WHOLE utterances first, and only what is
 * left over becomes a search. Matching a prefix would be worse than useless
 * here: "play music by Neil Young" starts with "play music", and must not be a
 * resume.
 */
object Voice {

    /**
     * Every way of saying each thing that a person plausibly says to a stereo.
     *
     * Written out rather than pattern-matched on purpose. A regular expression
     * covering these would also cover phrases nobody says, and every extra
     * phrase it swallows is an album that can no longer be asked for by name.
     */
    private val RESUME = setOf(
        "play", "play music", "play the music", "play it", "play again",
        "resume", "resume music", "resume the music", "resume playback",
        "continue", "continue playing", "carry on", "keep playing", "unpause"
    )

    private val PAUSE = setOf(
        "pause", "pause music", "pause the music", "pause playback", "pause it",
        "stop", "stop music", "stop the music", "stop playing", "stop it",
        "shut up", "be quiet"
    )

    private val VOLUME_UP = setOf(
        "turn up volume", "turn up the volume", "turn the volume up",
        "turn volume up", "volume up", "turn it up", "turn up",
        "turn music up", "turn the music up", "increase volume",
        "increase the volume", "louder", "make it louder"
    )

    private val VOLUME_DOWN = setOf(
        "turn down volume", "turn down the volume", "turn the volume down",
        "turn volume down", "volume down", "turn it down", "turn down",
        "turn music down", "turn the music down", "decrease volume",
        "decrease the volume", "lower the volume", "lower volume",
        "quieter", "softer", "make it quieter"
    )

    private val NEXT = setOf(
        "next", "next track", "next song", "next album", "skip", "skip track",
        "skip this", "skip song", "skip it", "forward"
    )

    private val PREVIOUS = setOf(
        "previous", "previous track", "previous song", "go back", "back",
        "last track", "last song", "back a track", "start again",
        "start over", "restart"
    )

    private val MUTE = setOf("mute", "mute music", "mute it", "silence", "silence it")

    private val UNMUTE = setOf("unmute", "unmute music", "unmute it", "sound on")

    private val RANDOM = setOf(
        "random", "random album", "play random", "play a random album",
        "play something random", "play something", "play anything",
        "surprise me", "something random", "anything"
    )

    /**
     * Ways of saying "play this thing", longest first.
     *
     * Longest first matters: "play some " has to be tried before "play ", or
     * the search keeps the word "some" and ranks everything worse for it.
     *
     * The flag says whether the lead-in NAMED A KIND of thing. It decides
     * whether the words after it can be trusted as a title: "play the album
     * Music" is unmistakably about a record called Music, while "listen to
     * music" is somebody trailing off. Without the distinction, one of those
     * two has to be broken — and Madonna's Music is a real album.
     */
    private data class Lead(val prefix: String, val namesAKind: Boolean)

    private val LEADING = listOf(
        Lead("play some music by ", true),
        Lead("play some music from ", true),
        Lead("play music by ", true),
        Lead("play music from ", true),
        Lead("play the album ", true),
        Lead("play album ", true),
        Lead("play the track ", true),
        Lead("play track ", true),
        Lead("play song ", true),
        Lead("search for ", true),
        Lead("find ", true),
        Lead("play me some ", false),
        Lead("play some ", false),
        Lead("play me ", false),
        Lead("play ", false),
        Lead("put on some ", false),
        Lead("put on ", false),
        Lead("listen to ", false)
    )

    /**
     * What is left when somebody says a lead-in and then stops.
     *
     * "Play the album" strips to "the album", which is not the name of
     * anything — but it is a perfectly good search term, so without this the
     * dial goes and starts whatever came closest. A remote that makes noise in
     * a room on a half-finished sentence is worse than one that says it did
     * not catch that.
     */
    private val FILLER = setOf(
        "the album", "album", "an album", "a album",
        "the song", "song", "a song",
        "the track", "track", "a track",
        "some music", "music", "the music", "a record", "record", "the record",
        "some", "something", "anything", "me", "it", "one", "that", "this"
    )

    /** A room named out loud, and the sentence with that part taken off. */
    data class Heard(val zone: String?, val rest: String)

    /**
     * Ways of naming the room a command is FOR, longest first.
     *
     * Only a trailing one counts. A leading "in the kitchen, play …" is not
     * how anybody talks to a stereo, and looking for it at the front would put
     * every album whose title starts with a preposition at risk.
     */
    private val ZONE_LEADS: List<List<String>> = listOf(
        listOf("through", "the"), listOf("in", "the"), listOf("on", "the"), listOf("to", "the"),
        listOf("through"), listOf("in"), listOf("on"), listOf("to")
    )

    /** Dropped from the end before looking, because it sits after the room. */
    private val COURTESY = setOf("please", "thanks", "thankyou")

    /**
     * Which room was named, and what is left to obey.
     *
     * THE ZONE NAMES ARE PASSED IN, and that is the whole reason this is
     * trustworthy. "Play In The Kitchen" is an album; "play Kid A in the
     * kitchen" is a room — and no amount of grammar tells the two apart. What
     * does is knowing that a zone called Kitchen exists, so nothing is ever
     * stripped off a sentence unless it matches a room that is really there.
     *
     * A zone named with nothing in front of it is not a command ("in the
     * kitchen" on its own), so a match must leave at least one word behind.
     * Zone names are matched WHOLE and longest first: with both "Kitchen" and
     * "Kitchen Speakers", "in the kitchen speakers" has to take the longer one
     * or "speakers" is left behind to be searched for as part of a title.
     *
     * AND THE REMAINDER HAS TO BE A COMMAND. That is what keeps "play the album
     * In The Kitchen" pointing at the record: taking the room off leaves "play
     * the album", which is a lead-in with nothing after it and means nothing,
     * so the match is refused and the whole title survives. It also settles
     * the genuinely ambiguous "play in the kitchen" the way a person means it
     * — what is left is "play", which resumes, in that room.
     *
     * When nothing matches, the sentence comes back untouched — including its
     * courtesy, which [parse] has always stripped for itself.
     */
    fun splitZone(spoken: String, zones: List<String>): Heard {
        val text = spoken.trim()
        if (text.isEmpty() || zones.isEmpty()) return Heard(null, text)

        val spokenWords = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var tail = spokenWords
        while (tail.isNotEmpty() && bare(tail.last()) in COURTESY) tail = tail.dropLast(1)
        if (tail.isEmpty()) return Heard(null, text)

        var best: Heard? = null
        var bestLength = 0
        for (zone in zones) {
            val zoneWords = zone.split(Regex("\\s+")).map(::bare).filter { it.isNotEmpty() }
            if (zoneWords.isEmpty()) continue
            for (lead in ZONE_LEADS) {
                val phrase = lead + zoneWords
                if (tail.size <= phrase.size) continue
                if (phrase.size <= bestLength) continue
                if (tail.takeLast(phrase.size).map(::bare) != phrase) continue
                val rest = tail.dropLast(phrase.size).joinToString(" ")
                if (parse(rest) is VoiceCommand.Unknown) continue
                bestLength = phrase.size
                best = Heard(zone, rest)
            }
        }
        return best ?: Heard(null, text)
    }

    /** One word, stripped to what a recogniser cannot vary. */
    private fun bare(word: String): String =
        word.lowercase().filter { it.isLetterOrDigit() || it == '\'' }

    fun parse(spoken: String): VoiceCommand {
        val heard = spoken.trim()
        val key = normalise(heard)
        if (key.isEmpty()) return VoiceCommand.Unknown(heard)

        // Whole-utterance commands come first. Anything that is entirely a
        // control phrase is never a search, however much it looks like a title.
        when (key) {
            in RESUME -> return VoiceCommand.Resume
            in PAUSE -> return VoiceCommand.Pause
            in VOLUME_UP -> return VoiceCommand.Volume(up = true)
            in VOLUME_DOWN -> return VoiceCommand.Volume(up = false)
            in NEXT -> return VoiceCommand.Next
            in PREVIOUS -> return VoiceCommand.Previous
            in MUTE -> return VoiceCommand.Mute(on = true)
            in UNMUTE -> return VoiceCommand.Mute(on = false)
            in RANDOM -> return VoiceCommand.Random
        }

        // Everything else is something to find. The verb is stripped because
        // Roon would otherwise search for the word "play" along with the name.
        val (cleaned, namedAKind) = strip(heard)
        if (cleaned.isEmpty()) return VoiceCommand.Unknown(heard)
        // Only second-guess a generic lead-in. After "the album" or "search
        // for", whatever follows is a title even when the title is a filler
        // word.
        if (!namedAKind && normalise(cleaned) in FILLER) return VoiceCommand.Unknown(heard)
        return VoiceCommand.Play(cleaned)
    }

    /**
     * The form phrases are compared in: lower case, no punctuation, single
     * spaces, no trailing courtesy.
     *
     * A recogniser will hand back "Turn up the volume." and "turn up the
     * volume please" for the same request, and neither should miss.
     */
    private fun normalise(text: String): String {
        var s = text.lowercase()
        s = s.map { if (it.isLetterOrDigit() || it == ' ' || it == '\'') it else ' ' }
            .joinToString("")
        s = s.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        for (courtesy in listOf(" please", " thanks", " thank you")) {
            if (s.endsWith(courtesy)) s = s.removeSuffix(courtesy).trim()
        }
        return s
    }

    /**
     * Removes a leading "play …" style verb, preserving the original casing.
     *
     * @return what was left, and whether the lead-in named a kind of thing.
     */
    private fun strip(spoken: String): Pair<String, Boolean> {
        var text = spoken.trim()
        val lower = text.lowercase()
        var namedAKind = false
        for (lead in LEADING) {
            if (lower.startsWith(lead.prefix)) {
                text = text.substring(lead.prefix.length)
                namedAKind = lead.namesAKind
                break
            }
        }
        return text.trim().trim('.', '!', '?', ',').trim() to namedAKind
    }
}

/** What to say back after obeying, and whether it worked. */
data class VoiceOutcome(val ok: Boolean, val message: String)
