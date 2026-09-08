package com.musicd.lite.android

import com.musicd.lite.VoiceName
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The launcher label is a voice target, and this is what keeps it usable as one.
 *
 * The rule itself, and the table of names that failed on a phone, live in
 * VoiceName in :core — where the tests run without a device, and where the same
 * rule guards the voice verbs. All that is left here is the part only Android
 * can answer: what this app's label actually resolves to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppNameTest {

    @Test
    fun theLabelThisAppShipsCanBeSaidToAPhone() {
        val label = RuntimeEnvironment.getApplication().getString(R.string.app_name)
        assertNull("the launcher label \"$label\" ${VoiceName.problemWith(label)}",
            VoiceName.problemWith(label))
    }

    @Test
    fun theVerbLabelsAreTheOnesTheRuleWasCheckedAgainst() {
        val app = RuntimeEnvironment.getApplication()
        val roulette = app.getString(R.string.verb_roulette)
        assertNull("the verb label \"$roulette\" ${VoiceName.problemWith(roulette)}",
            VoiceName.problemWith(roulette))
    }
}
