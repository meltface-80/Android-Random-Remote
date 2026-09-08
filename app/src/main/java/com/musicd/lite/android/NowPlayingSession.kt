package com.musicd.lite.android

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.Rating
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.musicd.lite.MusicdLite
import com.musicd.lite.Voice
import com.musicd.lite.roon.Zone
import com.musicd.lite.roon.ZoneVolume
import java.util.concurrent.Executors

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
    private val app: MusicdLite
) {

    /**
     * NOT private: [ACTIONS] and [actionsFor] are asserted by SessionActionsTest
     * against Google's documented required set for a music app, which is the
     * only place that requirement is checked. Re-privatising this removes the
     * check along with it.
     */
    companion object {
        const val TAG = "NowPlaying"

        /** Big enough for a lock screen, small enough to decode without care. */
        const val ART_PX = 512

        /**
         * What the session says it is doing when volume goes back to the phone.
         *
         * setPlaybackToLocal takes AudioAttributes rather than a stream type —
         * the compat class takes an int and the framework one does not, which
         * is an easy way to write something that reads correctly and will not
         * compile.
         */
        val LOCAL_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        /**
         * "Play <something> on MusicD", which is a different kind of thing from
         * the transport actions beside it.
         *
         * Those say what can be done to what is ALREADY playing, so Roon gates
         * them per zone. This says the app can be asked for something new, and
         * that is true whatever the zone is doing — a stopped zone is exactly
         * where you would want to ask. So it is never gated.
         *
         * PLAY only, and NOT PREPARE_FROM_SEARCH. Offering prepare tells
         * Assistant it may send the search first and a bare PLAY after it, to
         * start what was prepared — and a bare PLAY here is a transport
         * command that resumes whatever the zone already had. Advertising a
         * prepare this does not honour would therefore not be a wasted call,
         * it would be the wrong record. Only claim what is implemented.
         */
        const val SEARCH = PlaybackState.ACTION_PLAY_FROM_SEARCH

        /**
         * What this session can do when nothing better is known.
         *
         * Deliberately not SEEK: Roon's seek is absolute against a track this
         * app does not own the clock for, and a scrubber that fights the zone's
         * own position is worse than none.
         */
        const val ACTIONS =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                SEARCH

        /**
         * What this zone will actually accept, right now.
         *
         * Roon says per zone whether play, pause, next and previous are
         * allowed, and it answers a disallowed command by doing nothing at all.
         * Advertising the full set regardless meant the lock screen offered
         * buttons the Core would silently refuse — a button that does nothing,
         * with nothing anywhere to say why.
         */
        fun actionsFor(zone: Zone?): Long {
            if (zone == null) return ACTIONS
            var a = 0L
            if (zone.isPlayAllowed) a = a or PlaybackState.ACTION_PLAY
            if (zone.isPauseAllowed) a = a or PlaybackState.ACTION_PAUSE
            if (zone.isPlayAllowed || zone.isPauseAllowed) {
                a = a or PlaybackState.ACTION_PLAY_PAUSE
            }
            // Roon has no stop, so onStop() pauses — which means STOP is
            // offered exactly when pause is. Google lists it as required for a
            // music app, and it was the one required action never advertised.
            if (zone.isPauseAllowed) a = a or PlaybackState.ACTION_STOP
            if (zone.isNextAllowed) a = a or PlaybackState.ACTION_SKIP_TO_NEXT
            if (zone.isPreviousAllowed) a = a or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            return a or SEARCH
        }
    }

    private var session: MediaSession? = null

    /**
     * The zone this session is currently reporting, and the one its buttons
     * address.
     *
     * Commands used to re-resolve "which zone is this about" at the moment the
     * button was pressed, which is a different question from "which zone is
     * this lock screen showing". When the two answers disagreed — and they
     * could, because the fallback moved with playback — the button affected a
     * room other than the one named on screen. The session now sends to the
     * zone it drew.
     */
    @Volatile
    private var zoneId: String? = null

    /**
     * Transport commands run here, never on the caller's thread.
     *
     * A media-button callback arrives on the main thread and
     * <code>roon.control</code> blocks until the Core answers, so calling it
     * inline froze the UI thread for a network round trip — the kind of thing
     * that shows up as a button that "does nothing" and, often enough, as an
     * ANR. Single-threaded so two fast taps reach Roon in the order they were
     * made rather than racing.
     */
    private val commands = Executors.newSingleThreadExecutor { r ->
        Thread(r, "transport").apply { isDaemon = true }
    }

    /** The zone this session currently speaks for, so art is not re-fetched. */
    private var lastArtKey: String? = null
    private var lastArt: Bitmap? = null

    /**
     * What the volume keys and "Hey Google, turn it up" reach.
     *
     * Null until a zone with a volume control is being shown. Held so the zone
     * feed can move the slider when the volume changes somewhere else — from
     * the app, from Roon's own remote, from the knob on the amp — rather than
     * only when this phone is the one turning it.
     */
    private var volume: ZoneVolumeProvider? = null

    /** The scale the current provider was built for; a new one needs a new provider. */
    private var volumeScale: ZoneVolume.Scale? = null

    val token: MediaSession.Token? get() = session?.sessionToken

    /** True once the session exists; false means the app runs as it always did. */
    fun start(): Boolean {
        if (session != null) return true
        return try {
            session = MediaSession(context, "MusicDRemoteLite").apply {
                // The handler is not optional, and leaving it out is what broke
                // this. setCallback(callback) with no handler does
                // `new Handler()` internally, which throws on any thread that
                // has not called Looper.prepare() — and this is built on the
                // startup thread, which has no looper. The session then failed
                // to construct on every launch: no lock screen controls, and
                // because the zone watcher was gated on the session existing,
                // no widget updates either. One missing argument, three
                // symptoms, and a catch block that turned it into a debug line
                // nobody read.
                setCallback(Callbacks(), Handler(Looper.getMainLooper()))
                // A session with no playback state is not shown by the system
                // media controls at all, and the first real state does not
                // arrive until the zone feed next moves — which, on a paused
                // Core, could be never.
                setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(ACTIONS)
                        .setState(PlaybackState.STATE_STOPPED, 0L, 0f)
                        .build()
                )
                // Required by Google's Assistant guide whether or not the app
                // has ratings: "If the app does not support rating, it should
                // set the rating type to RATING_NONE." Roon has no rating this
                // session could carry, so this says so rather than leaving the
                // field unset and the answer unknown.
                setRatingType(Rating.RATING_NONE)
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
        runCatching { commands.shutdownNow() }
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
        zoneId = zone?.zoneId
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
                    .setActions(actionsFor(zone))
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
            trackVolume(s, zone)
        } catch (e: Exception) {
            Log.w(TAG, "could not update the session", e)
        }
    }

    /**
     * Points the volume keys at the zone, or hands them back to the phone.
     *
     * WHY THIS EXISTS. "Hey Google, turn it up" and the hardware rocker both go
     * to the media session, and a session that has not declared a volume
     * provider is treated as playing locally — so they moved the PHONE's media
     * volume, which this app does not use for anything, and the room stayed
     * exactly as loud as it was. Confirmed on a phone: next and pause worked,
     * turn it up and turn it down did nothing.
     *
     * The provider is replaced rather than mutated when the SCALE changes,
     * because Android reads the maximum once: moving from a -80..0 dB zone to
     * one with a different range needs a new provider or the slider maps onto
     * the wrong numbers. A mere change of position — somebody turning the knob
     * on the amp, or the app's own dial — only needs the current value pushed,
     * which is what keeps the system slider honest without churn.
     *
     * A zone with no volume control at all goes back to local, so the rocker
     * does the ordinary thing rather than silently doing nothing.
     */
    private fun trackVolume(session: MediaSession, zone: Zone?) {
        val scale = ZoneVolume.scaleOf(zone)
        if (scale == null) {
            if (volume != null) {
                volume = null
                volumeScale = null
                runCatching { session.setPlaybackToLocal(LOCAL_AUDIO) }
            }
            return
        }
        // Only the shape needs a new provider; the position is pushed into it.
        val sameShape = volumeScale?.steps == scale.steps &&
            volumeScale?.relativeOnly == scale.relativeOnly
        if (volume == null || !sameShape) {
            val provider = ZoneVolumeProvider(scale)
            volume = provider
            volumeScale = scale
            runCatching { session.setPlaybackToRemote(provider) }
                .onFailure { Log.w(TAG, "could not hand volume to the zone", it) }
        } else if (!scale.relativeOnly) {
            runCatching { volume?.currentVolume = scale.position }
        }
    }

    /**
     * The volume keys, in Roon's terms.
     *
     * Every output in the zone moves together, which is what the app's own
     * volume already does and what somebody adjusting "the kitchen" means by
     * it. Off the caller's thread for the usual reason: changeVolume blocks
     * until the Core answers and these arrive on the main one.
     *
     * Nothing is written back optimistically. Roon pushes the new value through
     * the zone feed and [trackVolume] moves the slider from there, so the
     * system UI can never show a level the Core did not agree to.
     */
    private inner class ZoneVolumeProvider(scale: ZoneVolume.Scale) : VolumeProvider(
        if (scale.relativeOnly) VolumeProvider.VOLUME_CONTROL_RELATIVE
        else VolumeProvider.VOLUME_CONTROL_ABSOLUTE,
        scale.steps,
        scale.position
    ) {
        // `position`, not `volume`: the outer class has a field of that name
        // holding this very provider, and shadowing it here reads as a bug
        // even when it is not one.
        override fun onSetVolumeTo(position: Int) {
            val zone = app.roon.zone(zoneId) ?: return
            for (out in zone.volumeOutputs) {
                val v = out.volume ?: continue
                val target = ZoneVolume.valueAt(v, position)
                send { app.roon.changeVolume(out.outputId, "absolute", target) }
            }
        }

        override fun onAdjustVolume(direction: Int) {
            if (direction == 0) return
            val zone = app.roon.zone(zoneId) ?: return
            for (out in zone.volumeOutputs) {
                out.volume ?: continue
                send {
                    app.roon.changeVolume(out.outputId, "relative_step", direction.toDouble())
                }
            }
        }

        private fun send(body: () -> Unit) {
            runCatching {
                commands.execute { runCatching(body).onFailure { Log.w(TAG, "volume failed", it) } }
            }.onFailure { Log.w(TAG, "could not queue a volume change", it) }
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
                // Without this a secure lock screen hides the content as
                // "sensitive" — which for a notification whose entire purpose
                // is to be readable from the lock screen defeats the object.
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .apply { lastArt?.let { setLargeIcon(it) } }
        } catch (e: Exception) {
            Log.w(TAG, "could not decorate the notification", e)
            builder
        }
    }

    /**
     * The three transport actions, as notification buttons.
     *
     * The middle one shows what pressing it will do, so it is a pause icon
     * while the zone is playing. A control that always shows "play" is telling
     * you the wrong thing half the time.
     */
    fun actions(zone: Zone?): List<Notification.Action> {
        val playing = zone?.state == "playing" || zone?.state == "loading"
        return listOf(
            action("Previous", android.R.drawable.ic_media_previous, RemoteService.ACTION_PREVIOUS),
            action(
                if (playing) "Pause" else "Play",
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                RemoteService.ACTION_PLAY_PAUSE
            ),
            action("Next", android.R.drawable.ic_media_next, RemoteService.ACTION_NEXT)
        )
    }

    private fun action(title: String, icon: Int, intentAction: String): Notification.Action {
        // Foreground start for the same reason the widget uses one: if the
        // service has been killed, a plain start from the shade is refused.
        val pending = PendingIntent.getForegroundService(
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
        // Read on the caller's thread, so the target is the zone that was on
        // screen when the button was pressed rather than whatever it may have
        // become by the time the executor gets to it.
        val target = zoneId ?: app.activeZone()?.zoneId
        if (target == null) {
            Log.w(TAG, "no zone to send $command to")
            return
        }
        runCatching {
            commands.execute {
                runCatching { app.roon.control(target, command) }
                    .onFailure { Log.w(TAG, "transport command $command failed", it) }
            }
        }.onFailure { Log.w(TAG, "could not queue $command", it) }
    }

    /**
     * Note what is NOT here: an onMediaButtonEvent override.
     *
     * It used to exist and to rebuild the notification on every key event —
     * which meant twice per press, since a key press is a down and an up, and
     * each rebuild wrote the zone's pre-command state back over the session.
     * The button therefore snapped back to its old position immediately after
     * being pressed, which reads as "it did nothing" and invites a second
     * press. The default implementation treats a second press inside the
     * double-tap window as skip-to-next, so the reward for the retry was a
     * track change. Removing the override removes both halves: the default
     * mapping onto the callbacks below is exactly what is wanted, and the zone
     * feed is what updates the state.
     */
    private inner class Callbacks : MediaSession.Callback() {
        override fun onPlay() = command("play")
        override fun onPause() = command("pause")
        override fun onStop() = command("pause")
        override fun onSkipToNext() = command("next")
        override fun onSkipToPrevious() = command("previous")

        /**
         * "Hey Google, play Mezzanine on MusicD."
         *
         * Assistant hands over the spoken query and, separately, whatever it
         * managed to pick out of it as artist / album / title. Which of those
         * to believe is decided in :core by Voice.mediaCommand, where it is
         * tested — and the phrase it returns goes through the same obey() as
         * the dial's microphone, so "on the kitchen speakers" works here too.
         */
        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            search(query, extras)
        }
    }

    /**
     * Finds and plays what was asked for, off the caller's thread.
     *
     * The state goes to CONNECTING first because this is a library search
     * followed by a walk of Roon's browse tree — seconds, sometimes — and a
     * controller that is told nothing assumes the app ignored it. Android's own
     * guidance for this callback is to do exactly that rather than block.
     */
    private fun search(query: String?, extras: Bundle?) {
        val spoken = Voice.mediaCommand(
            query,
            extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
            extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
            extras?.getString(MediaStore.EXTRA_MEDIA_TITLE)
        )
        runCatching {
            session?.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(ACTIONS)
                    .setState(PlaybackState.STATE_CONNECTING, 0L, 0f)
                    .build()
            )
        }
        runCatching {
            commands.execute {
                val outcome = runCatching { app.obey(spoken) }.getOrNull()
                if (outcome?.ok != true) {
                    Log.w(TAG, "search \"$spoken\" -> ${outcome?.message ?: "failed"}")
                    // Put the session back where it was; the zone feed will
                    // correct it the moment Roon says anything, but a state
                    // left at CONNECTING after a miss is a spinner forever.
                    runCatching { update(app.activeZone()) }
                }
                // On success nothing is written here on purpose: Roon pushes
                // the new track through the zone feed and that updates this,
                // which is the same rule the transport buttons follow.
            }
        }.onFailure { Log.w(TAG, "could not queue the search", it) }
    }
}
