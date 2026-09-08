package com.musicd.lite.android

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import com.musicd.lite.Voice
import com.musicd.lite.VoiceVerbs

/**
 * Playing something without opening anything: the app answered from outside.
 *
 * Two doors, and they are not the same kind of thing.
 *
 * **`android.media.action.MEDIA_PLAY_FROM_SEARCH`** is Android's own
 * media-search intent, the one a car head unit, a launcher or an assistant
 * fires for "play <something>". Declaring it costs a filter and it is the only
 * way this app can be offered as an answer to that question at all.
 *
 * BE CLEAR ABOUT WHAT IT IS NOT. It does not get you "Hey Google, play X on
 * MusicD". That is App Actions, which Android's own documentation gates on
 * publication: "Your app must be published to the Google Play Store, because
 * App Actions are only available for apps published there" — and then on a
 * separate Google review of a shortcuts.xml. An APK signed with a private key
 * and installed by hand satisfies neither, and Gemini's music routing is an
 * account-linked list of streaming services rather than an open intent. So
 * this filter is a standing offer that Google may never take up. It is here
 * because it is the standard door, ten lines wide, and everything that is not
 * Google — adb, a car, an automation app — can already knock on it.
 *
 * **`com.musicd.lite.android.action.VOICE_COMMAND`** is the app's own, and it
 * is the one that will actually get used. It takes a phrase and runs it
 * through the same [MusicdLite.obey] the dial's microphone uses, so everything
 * that can be said can be automated: play, pause, next, volume, mute, a random
 * album, and the room to do it in.
 *
 *     adb shell am start -a com.musicd.lite.android.action.VOICE_COMMAND \
 *       -e command "play Mezzanine in the kitchen"
 *
 * EXPORTED, DELIBERATELY. An intent nothing can send is not an integration, so
 * any app on this phone can drive the music with these. That is a real if
 * small thing to accept: the blast radius is somebody else's app changing what
 * is playing, and there is no path from here to the library, the Core's
 * settings or anything on disk. The HTTP server stays shut to the world for
 * the reasons in CLAUDE.md; this is a narrower door on purpose.
 *
 * **A voice verb** is the third, and it is the one that actually answers
 * "Hey Google, <do the thing>". Gemini refuses to play an album by name in a
 * third-party app — "I cannot directly control or trigger playback on your
 * local devices or apps" — but it opens an app by name without complaint. So a
 * verb is a launcher entry whose NAME is the command: tapping, or saying, "open
 * Roulette" arrives here as a plain ACTION_MAIN and the component names the
 * verb. See VoiceVerbs in :core for the table.
 *
 * No UI, and that is what makes a verb possible. The activity exists because
 * MEDIA_PLAY_FROM_SEARCH is an activity intent, and it finishes before it can
 * be drawn — Theme.NoDisplay, and the answer arrives as a toast, which is what
 * the Quick Settings tile does for the same reason.
 */
class VoiceIntentActivity : Activity() {

    private companion object {
        const val TAG = "VoiceIntent"

        /** The app's own door. Play, pause, volume, a room — anything sayable. */
        const val ACTION_VOICE_COMMAND = "com.musicd.lite.android.action.VOICE_COMMAND"

        /** The phrase to obey, for [ACTION_VOICE_COMMAND]. */
        const val EXTRA_COMMAND = "command"

        /** Zone display name, used only when the phrase does not name a room. */
        const val EXTRA_ZONE = "zone"
    }

    /** What one intent asked for. */
    private data class Request(val spoken: String, val zone: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = read(intent)
        // Nothing is drawn and nothing is waited for: the work happens on a
        // background thread and this is gone before it finishes.
        finish()
        if (request == null) {
            Log.d(TAG, "nothing usable in ${intent?.action}")
            return
        }
        run(request)
    }

    /** What to do, or null when the intent carried nothing to act on. */
    private fun read(intent: Intent?): Request? {
        if (intent == null) return null
        val zone = intent.getStringExtra(EXTRA_ZONE)?.trim()?.takeIf { it.isNotEmpty() }
        val spoken = when (intent.action) {
            ACTION_VOICE_COMMAND ->
                intent.getStringExtra(EXTRA_COMMAND)?.trim()?.takeIf { it.isNotEmpty() }

            // A voice verb: a launcher entry whose NAME is the command, so
            // "Ok Google, open Roulette" arrives here as an ordinary launch and
            // the component says which verb was tapped. The table and the
            // phrase it runs live in :core, where both are tested — a phrase
            // the parser did not understand would be an icon that silently
            // does nothing, which is the worst failure for something with no UI.
            Intent.ACTION_MAIN ->
                VoiceVerbs.forComponent(intent.component?.className)?.command

            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH ->
                // Every decision about what a media search MEANS is in :core,
                // where it is tested — the whole spoken sentence over the
                // sender's split into artist / album / title, an empty search
                // as "play something", and the verb put back on the rest.
                Voice.mediaCommand(
                    intent.getStringExtra(SearchManager.QUERY),
                    intent.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST),
                    intent.getStringExtra(MediaStore.EXTRA_MEDIA_ALBUM),
                    intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE)
                )

            else -> null
        }
        return spoken?.let { Request(it, zone) }
    }

    private fun run(request: Request) {
        val app = RemoteService.instance?.app
        if (app == null) {
            // Nothing is running, so there is no Core connection to act on.
            // Opening the app starts the service; saying so beats silence.
            toast("${getString(R.string.app_name)} isn't running yet")
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { Log.w(TAG, "could not open the app", it) }
            return
        }
        // Off the main thread without exception: obey() walks Roon's browse
        // tree, and roon.control blocks until the Core answers.
        Thread({
            val message = runCatching { app.obey(request.spoken, request.zone).message }
                .getOrElse { it.message ?: "Could not do that" }
            toast(message)
        }, "voice-intent").apply { isDaemon = true }.start()
    }

    private fun toast(message: String) {
        runCatching {
            Handler(mainLooper).post {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }.onFailure { Log.w(TAG, "could not show \"$message\"", it) }
    }
}
