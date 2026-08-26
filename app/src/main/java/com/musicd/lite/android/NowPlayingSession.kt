package com.musicd.lite.android

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import com.musicd.lite.MusicdLite
import com.musicd.lite.roon.Zone

/**
 * What makes this behave like a music app rather than a web page in an app.
 *
 * The notification used to be a line of text saying the extension was running.
 * A MediaSession turns it into artwork and transport controls on the lock
 * screen — and, the part that is not cosmetic, it is the thing Android routes
 * hardware media buttons to. Headset play/pause, a Bluetooth speaker's buttons,
 * steering-wheel controls and Assistant all speak MediaSession, so one
 * integration reaches all of them. None of it was available to the Docker
 * build, because none of it is available to a browser tab.
 *
 * Nothing here plays audio: Roon does. This session is a REMOTE control, and it
 * reports its playback state from whichever zone the user is watching. That is
 * why the foreground service is not declared as a media-playback service — the
 * app produces no sound, and claiming otherwise to win scheduling latitude
 * would be a lie to the platform.
 *
 * Every entry point is guarded. A media session that fails to build, or a
 * device that behaves oddly, must cost the transport controls and nothing else
 * — the app's actual job is serving the UI and staying paired with Roon.
 */
class NowPlayingSession(
    private val context: Context,
    private val app: MusicdLite,
    /** Rebuilds the foreground notification when the session state changes. */
    private val onChanged: () -> Unit
) {

    private companion object {
        const val TAG = "NowPlaying"

        /** Big enough for a lock screen, small enough to decode without care. */
        const val ART_PX = 512

        /**
         * What this session can do. Deliberately not SEEK: Roon's seek is
         * absolute against a track this app does not own the clock for, and a
         * scrubber that fights the zone's own position is worse than none.
         */
        const val ACTIONS =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_STOP
    }

    private var session: MediaSession? = null

    /** The zone this session currently speaks for, so art is not re-fetched. */
    private var lastArtKey: String? = null
    private var lastArt: Bitmap? = null

    val token: MediaSession.Token? get() = session?.sessionToken

    /** True once the session exists; false means the app runs as it always did. */
    fun start(): Boolean {
        if (session != null) return true
        return try {
            session = MediaSession(context, "MusicDRemoteLite").apply {
                setCallback(Callbacks())
                isActive = true
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "no media session on this device; controls will be text only", e)
            session = null
            false
        }
    }

    fun stop() {
        runCatching {
            session?.isActive = false
            session?.release()
        }
        session = null
        lastArt = null
        lastArtKey = null
    }

    /**
     * Points the session at [zone]. Safe to call often — it is driven by the
     * zone feed, which changes whenever Roon says something.
     */
    fun update(zone: Zone?) {
        val s = session ?: return
        try {
            val np = zone?.nowPlaying
            val art = np?.imageKey?.let { artFor(it) }

            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, np?.line1 ?: "Nothing playing")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, np?.line2 ?: "")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, np?.line3 ?: "")
                    // The zone is the "device" this is playing on, and naming it
                    // is what tells you which room the buttons will affect.
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, zone?.displayName ?: "")
                    .putLong(
                        MediaMetadata.METADATA_KEY_DURATION,
                        (np?.lengthSeconds?.toLong() ?: 0L) * 1000L
                    )
                    .apply { if (art != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art) }
                    .build()
            )

            val state = when (zone?.state) {
                "playing" -> PlaybackState.STATE_PLAYING
                "paused" -> PlaybackState.STATE_PAUSED
                "loading" -> PlaybackState.STATE_BUFFERING
                else -> PlaybackState.STATE_STOPPED
            }
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(ACTIONS)
                    .setState(
                        state,
                        (np?.seekPosition?.toLong() ?: 0L) * 1000L,
                        // Roon owns the clock. Reporting a rate of 1 would have
                        // Android extrapolate a position of its own, which then
                        // disagrees with the zone every time the two drift.
                        0f
                    )
                    .build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not update the session", e)
        }
    }

    /**
     * Album art for the lock screen, reusing the app's own cache so this costs
     * the Core nothing the UI has not already asked for.
     */
    private fun artFor(imageKey: String): Bitmap? {
        if (imageKey == lastArtKey) return lastArt
        return try {
            val url = app.roon.imageUrl(imageKey, ART_PX, ART_PX) ?: return null
            val bytes = app.art.get(url, "$imageKey|$ART_PX|$ART_PX|fit")?.bytes ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size).also {
                lastArtKey = imageKey
                lastArt = it
            }
        } catch (e: Exception) {
            Log.d(TAG, "no art for $imageKey: ${e.message}")
            null
        }
    }

    /**
     * A MediaStyle notification, which is what puts the controls on the lock
     * screen and in the shade. Falls back to the caller's plain notification if
     * there is no session.
     */
    fun decorate(builder: Notification.Builder, zone: Zone?): Notification.Builder {
        val t = token ?: return builder
        return try {
            val np = zone?.nowPlaying
            builder
                .setContentTitle(np?.line1 ?: "Nothing playing")
                .setContentText(
                    listOfNotNull(
                        np?.line2?.takeIf { it.isNotBlank() },
                        zone?.displayName?.takeIf { it.isNotBlank() }
                    ).joinToString(" · ").ifEmpty { "Connected to Roon" }
                )
                .setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(t)
                        // Which controls survive the collapsed notification.
                        .setShowActionsInCompactView(0, 1, 2)
                )
                .apply { lastArt?.let { setLargeIcon(it) } }
        } catch (e: Exception) {
            Log.w(TAG, "could not decorate the notification", e)
            builder
        }
    }

    /** The three transport actions, as notification buttons. */
    fun actions(): List<Notification.Action> = listOf(
        action("Previous", android.R.drawable.ic_media_previous, RemoteService.ACTION_PREVIOUS),
        action("Play/pause", android.R.drawable.ic_media_play, RemoteService.ACTION_PLAY_PAUSE),
        action("Next", android.R.drawable.ic_media_next, RemoteService.ACTION_NEXT)
    )

    private fun action(title: String, icon: Int, intentAction: String): Notification.Action {
        val pending = PendingIntent.getService(
            context,
            intentAction.hashCode(),
            Intent(context, RemoteService::class.java).setAction(intentAction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(context, icon), title, pending
        ).build()
    }

    /**
     * Sends a transport command to whichever zone the session speaks for.
     *
     * Deliberately does NOT update the session optimistically: the command goes
     * to Roon, Roon pushes the result back through the zone feed, and the feed
     * updates this. A button that lights up before the Core agrees is a button
     * that lies whenever the Core refuses.
     */
    fun command(command: String) {
        runCatching {
            val zoneId = app.settings.lastZone()
                ?: app.roon.zones().firstOrNull { it.isPlaying }?.zoneId
                ?: app.roon.zones().firstOrNull()?.zoneId
                ?: return
            app.roon.control(zoneId, command)
        }.onFailure { Log.w(TAG, "transport command $command failed", it) }
    }

    private inner class Callbacks : MediaSession.Callback() {
        override fun onPlay() = command("play")
        override fun onPause() = command("pause")
        override fun onStop() = command("stop")
        override fun onSkipToNext() = command("next")
        override fun onSkipToPrevious() = command("previous")
        override fun onMediaButtonEvent(intent: Intent): Boolean {
            // Let the default handling map key events onto the callbacks above;
            // this override exists only so a failure here cannot escape.
            return runCatching { super.onMediaButtonEvent(intent) }.getOrDefault(false)
                .also { onChanged() }
        }
    }
}
