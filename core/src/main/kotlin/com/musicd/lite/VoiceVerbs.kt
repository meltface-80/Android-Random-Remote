package com.musicd.lite

/**
 * Launcher entries that are commands rather than screens.
 *
 * WHY THIS EXISTS. Asked to play an album by name, Gemini answers "I cannot
 * directly control or trigger playback on your local devices or apps" — it
 * declines the whole category, so no amount of media-app conformance reaches
 * it. What DOES work, measured on a phone, is opening an app by its name.
 *
 * So a verb is a second launcher entry whose name is the command. "Roulette"
 * in the drawer means "Ok Google, open Roulette" plays a random album and
 * finishes without drawing anything. It rides the one channel that is proven,
 * and needs nothing from Google's media catalogue.
 *
 * THE TABLE IS HERE, IN :core, FOR TWO REASONS. The command must be a phrase
 * [Voice.parse] actually understands — a typo would give a launcher icon that
 * silently does nothing, which is the worst possible failure for something with
 * no UI — and the name must survive being said aloud, which is what
 * [VoiceName] checks. Both are asserted by tests that run without a device.
 *
 * Adding a verb means: an entry here, an activity-alias in the manifest whose
 * class name matches [alias], and a label string. A test checks all three agree.
 */
object VoiceVerbs {

    /**
     * One verb. [alias] is the activity-alias class name, which is what the
     * launcher hands back when the entry is tapped; [label] is what appears
     * under the icon and what is said out loud; [command] is put through
     * [MusicdLite.obey] exactly as if it had been spoken.
     */
    data class Verb(val alias: String, val label: String, val command: String)

    /** The package the aliases live in, so a class name can be built from a label. */
    const val PACKAGE = "com.musicd.lite.android"

    /**
     * Deliberately short. One verb that is obviously useful and impossible to
     * get wrong beats five that each add an icon somebody has to look at.
     *
     * "Roulette" is not a word Google answers to, every speech model has it,
     * and it says what it does. It plays into whichever zone is active, which
     * needs no configuration and so cannot be configured wrongly — naming a
     * room would mean baking somebody's room names into the manifest.
     */
    val ALL = listOf(
        Verb(
            alias = "$PACKAGE.verb.Roulette",
            label = "Roulette",
            command = "play a random album"
        )
    )

    /** The verb a launcher component belongs to, or null when it is not one. */
    fun forComponent(className: String?): Verb? =
        ALL.firstOrNull { it.alias == className }
}
