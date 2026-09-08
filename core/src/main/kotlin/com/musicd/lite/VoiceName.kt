package com.musicd.lite

/**
 * Whether a name can be said to a phone and land where it is meant to.
 *
 * Every name this app puts in the launcher is a voice target: it is what
 * Google matches for "open X". Three of them failed, each differently, and
 * this is the union of what they taught:
 *
 *   "MusicD Remote Lite" was heard as "Music d remote light" — too many words,
 *   and a trailing capital letter no speech model has anywhere to put.
 *
 *   "Roon" is heard as "rune". A name outside the lexicon is replaced by the
 *   nearest real word, and the request dies before any matching happens.
 *
 *   "Remote" was heard perfectly and still opened nothing, because "open
 *   remote" is how Google opens its own TV remote. It reached the matcher
 *   intact and lost to a feature that was already there.
 *
 * It lives in :core rather than beside the resource it checks because that is
 * the only place with tests that run without an Android device, and because it
 * now guards two things: the app's own label, and the name of every voice verb.
 */
object VoiceName {

    /**
     * Words Google resolves to something of its own before it looks at what is
     * installed. Learned one entry at a time — "remote" is here because a
     * release shipped with it and opened the TV remote instead.
     *
     * Not exhaustive and it cannot be: nobody publishes this list, and it grows
     * whenever Google ships a feature. A floor, not a guarantee.
     */
    val CLAIMED_BY_GOOGLE = setOf(
        "remote", "music", "radio", "play", "player", "home", "assistant",
        "settings", "camera", "clock", "phone", "cast", "search", "maps", "photos"
    )

    /**
     * Long enough for "Gramophone", short enough to rule out the other failure,
     * which is squashing several words together rather than choosing one.
     */
    const val MAX_LENGTH = 12

    /** Why [name] is no good to say, or null when it is fine. */
    fun problemWith(name: String): String? = when {
        name.isEmpty() -> "is empty"
        name.trim() != name -> "has space around it"
        name.any { it.isWhitespace() } -> "is more than one word"
        !name.all { it.isLetter() } -> "has something other than letters in it"
        name.length > MAX_LENGTH -> "is too long to say"
        name.length >= 2 && name.last().isUpperCase() && name[name.length - 2].isLowerCase() ->
            "ends in a stranded capital letter"
        name.lowercase() in CLAIMED_BY_GOOGLE -> "is a word Google has already claimed"
        else -> null
    }
}
