package com.musicd.lite.android

import android.content.Intent
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.service.media.MediaBrowserService
import android.util.Log

/**
 * The door Assistant knocks on before it can say anything to this app.
 *
 * WHY PAUSE ALREADY WORKED AND NOTHING ELSE DID. A media session that is
 * ACTIVE is reachable by the system for transport — that is how "Hey Google,
 * pause" reached Roon with none of this in place. But "play <something> on
 * MusicD" is not a transport command: Assistant has to find the app by name,
 * start it, and get hold of its session, and the only route to that is a
 * MediaBrowserService. Android's own guidance is blunt about it — "You must
 * return a BrowserRoot in order to allow the Assistant to send commands to
 * your media session."
 *
 * That is what this is for, and it is worth being clear that it is NOT App
 * Actions. App Actions needs the app published on Play and a shortcuts.xml
 * reviewed by Google, which a privately signed APK will never have. This route
 * asks for neither; it is the ordinary media-app integration that every player
 * on Android implements, and it is the one that was missing.
 *
 * BROWSING IS NOT IMPLEMENTED, DELIBERATELY, AND IT IS NOT NEEDED FOR VOICE.
 * A root is returned so a controller can connect and reach the session, and
 * the root is empty. Serving the library as a browsable tree is what Android
 * Auto wants and it is a bigger, separate job — one this app is unusually well
 * placed to do later, since :core already holds the index. Returning an empty
 * list is honest in the meantime; returning nothing at all would refuse the
 * connection and take voice with it.
 *
 * NOTHING HERE PLAYS AUDIO, and that stays true. This app is a remote: the
 * session reports the state of a Roon zone and its buttons send commands to
 * the Core. A browser service says "I can be asked about media and told what
 * to do with it", which is exactly what a remote does — it is not a claim to
 * be an audio player, and no foreground-service media type is declared for it.
 */
class BrowserService : MediaBrowserService() {

    private companion object {
        const val TAG = "BrowserService"

        /**
         * The root's id. Nothing hangs off it yet, but a controller expects an
         * id it can pass back to onLoadChildren.
         */
        const val ROOT = "musicd:root"

        /** How long to wait for the session, and how often to look. */
        const val TOKEN_WAIT_MS = 5_000L
        const val TOKEN_POLL_MS = 100L
    }

    override fun onCreate() {
        super.onCreate()
        // Assistant binds this directly, which can happen with the app closed
        // and nothing connected to Roon — and the session it wants is created
        // by RemoteService. A FOREGROUND start, like every other surface here:
        // a plain one is refused outright from the background on Android 12
        // and up. Being bound by a visible assistant should earn the exemption
        // that makes this land, but it is not guaranteed, so a refusal is
        // logged and survived rather than thrown. The service is normally
        // already up — it is what holds the Roon connection open — and this
        // only matters on a genuinely cold app.
        runCatching { startForegroundService(Intent(this, RemoteService::class.java)) }
            .onFailure { Log.w(TAG, "could not start the remote service", it) }
        publishToken()
    }

    /**
     * Hands the controller the session, once there is one.
     *
     * The wait is the awkward part and it is not avoidable: setSessionToken can
     * be called exactly once and a controller gets nothing until it has been,
     * so a token that is not ready yet has to be waited for rather than skipped.
     * It is bounded, it is off the main thread, and a miss leaves the service
     * connectable-but-mute rather than crashing — the next bind tries again.
     */
    private fun publishToken() {
        Thread({
            val deadline = System.currentTimeMillis() + TOKEN_WAIT_MS
            var token: android.media.session.MediaSession.Token? = null
            while (token == null && System.currentTimeMillis() < deadline) {
                token = RemoteService.instance?.mediaToken
                if (token != null) break
                try {
                    Thread.sleep(TOKEN_POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            val found = token
            if (found == null) {
                Log.w(TAG, "no media session after ${TOKEN_WAIT_MS}ms; voice control will not work")
            } else {
                runCatching { setSessionToken(found) }
                    .onFailure { Log.w(TAG, "could not publish the session token", it) }
            }
        }, "browser-token").apply { isDaemon = true }.start()
    }

    /**
     * Everyone gets a root.
     *
     * The usual advice is to check the caller's package and signature, which
     * exists to stop a stranger reading a library that is behind somebody's
     * subscription. There is nothing to protect here: the root is empty, so a
     * caller learns nothing from it, and what it unlocks — sending transport
     * and search to the session — is the same thing the exported VOICE_COMMAND
     * intent already offers any app on this phone. Refusing unknown callers
     * would mainly mean refusing whichever assistant the phone actually has.
     */
    override fun onGetRoot(
        clientPackage: String,
        clientUid: Int,
        hints: Bundle?
    ): MediaBrowserService.BrowserRoot {
        Log.d(TAG, "connect from $clientPackage")
        return MediaBrowserService.BrowserRoot(ROOT, null)
    }

    /**
     * Nothing to browse yet — see the note above about Android Auto.
     *
     * The Result is spelled out in full because Kotlin auto-imports its own
     * kotlin.Result, and a bare one here is a coin toss over which the reader
     * (and the compiler's overload resolution) means.
     */
    override fun onLoadChildren(
        parentId: String,
        result: MediaBrowserService.Result<MutableList<MediaBrowser.MediaItem>>
    ) {
        result.sendResult(mutableListOf())
    }
}
