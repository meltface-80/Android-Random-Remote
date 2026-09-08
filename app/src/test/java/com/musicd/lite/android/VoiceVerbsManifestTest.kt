package com.musicd.lite.android

import android.content.Intent
import android.content.pm.PackageManager
import com.musicd.lite.VoiceVerbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The manifest half of a voice verb.
 *
 * A verb is three things that must agree: a row in VoiceVerbs, an
 * activity-alias declared here, and a label string. :core tests the row — that
 * the phrase parses and the name can be said — but it cannot see the manifest,
 * and the failure when they disagree is silent in the worst way: no icon in the
 * drawer, so "Ok Google, open Roulette" finds nothing and there is no error
 * anywhere to read.
 *
 * Robolectric parses the real merged manifest, so this checks the actual
 * declaration rather than a copy of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceVerbsManifestTest {

    private val context = RuntimeEnvironment.getApplication()
    private val packageManager: PackageManager get() = context.packageManager

    /** Every entry the launcher would draw for this app, by class name. */
    private fun launcherEntries(): Map<String, CharSequence> =
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(context.packageName),
            0
        ).associate { it.activityInfo.name to it.loadLabel(packageManager) }

    /**
     * The whole point: a verb that is not in the launcher cannot be opened by
     * name, and opening by name is the only voice channel that works.
     */
    @Test
    fun everyVerbIsDeclaredAsALauncherEntry() {
        val entries = launcherEntries()
        for (verb in VoiceVerbs.ALL) {
            assertTrue(
                "${verb.alias} is in VoiceVerbs but not a launcher entry in the manifest; " +
                    "the drawer would have no ${verb.label} icon and nothing would say why. " +
                    "Declared entries: ${entries.keys}",
                entries.containsKey(verb.alias)
            )
        }
    }

    /** The label is what gets said out loud, so it is the label that must match. */
    @Test
    fun theLabelInTheManifestIsTheOneTheTableSays() {
        val entries = launcherEntries()
        for (verb in VoiceVerbs.ALL) {
            assertEquals(
                "the manifest and VoiceVerbs disagree about what ${verb.alias} is called",
                verb.label,
                entries[verb.alias]?.toString()
            )
        }
    }

    /**
     * The alias must point at the activity that knows what to do with it.
     * Pointing it anywhere else gives an icon that opens the wrong thing.
     */
    @Test
    fun eachVerbIsAnAliasOfTheActivityThatHandlesIt() {
        for (verb in VoiceVerbs.ALL) {
            val info = packageManager.getActivityInfo(
                android.content.ComponentName(context.packageName, verb.alias), 0
            )
            assertEquals(VoiceIntentActivity::class.java.name, info.targetActivity)
            assertTrue("a launcher entry must be exported", info.exported)
        }
    }

    /**
     * The app's own two entries are not verbs and must not be mistaken for
     * them, or tapping the app would run a command instead of opening it.
     */
    @Test
    fun theAppsOwnLauncherEntriesAreNotVerbs() {
        for (name in listOf(MainActivity::class.java.name, "$PACKAGE.dial.DialActivity")) {
            assertEquals(null, VoiceVerbs.forComponent(name))
        }
    }

    private companion object {
        const val PACKAGE = "com.musicd.lite.android"
    }
}
