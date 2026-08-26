package com.musicd.lite.android.dial

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.musicd.lite.MusicdLite
import com.musicd.lite.android.MainActivity
import com.musicd.lite.android.RemoteService
import com.musicd.lite.roon.Zone

/**
 * The dial, as its own launcher icon.
 *
 * One install, two icons. That matters more than it sounds: Dial for Roon and
 * this app were two separate installs, which meant two Roon extensions to
 * approve, two pairings to keep and two sockets on the Core for one person
 * controlling one room. Sharing this app's client makes the dial a second face
 * on an extension that is already there.
 *
 * Everything visual lives in [DialView], which is synced from the dial's own
 * repository and must not be edited here — see tools/dial-upstream.json. This
 * file is the part that is ours: it joins that view to this app's Roon client.
 */
class DialActivity : Activity(), DialView.Callbacks {

    companion object {
        private const val TAG = "Dial"

        /** Opens the dial with the microphone already listening. */
        const val EXTRA_START_VOICE = "com.musicd.lite.dial.START_VOICE"

        /** How big a cover to fetch for the middle of the ring. */
        private const val ART_PX = 640

        private const val SPEECH_REQUEST = 7001
    }

    private lateinit var dial: DialView
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var watching = false

    private var artKey: String? = null

    private val app: MusicdLite? get() = RemoteService.instance?.app

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dial = DialView(this).apply { callbacks = this@DialActivity }
        setContentView(dial)

        // A dial is something you glance at and reach for, so it should not go
        // dark while you are looking at it. The flag goes with the window, so
        // this costs nothing once the screen is not showing.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        runCatching { startForegroundService(Intent(this, RemoteService::class.java)) }
            .onFailure { Log.w(TAG, "could not start the service", it) }

        if (intent?.getBooleanExtra(EXTRA_START_VOICE, false) == true) onVoiceTapped()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_START_VOICE, false) == true) onVoiceTapped()
    }

    override fun onResume() {
        super.onResume()
        startWatch()
    }

    override fun onPause() {
        super.onPause()
        watching = false
    }

    /**
     * Rides the same waiting primitive as everything else: block until Roon
     * says something, redraw, block again. The one timed wait is for the
     * service to finish starting, and it ends as soon as it has.
     */
    private fun startWatch() {
        if (watching) return
        watching = true
        Thread({
            var seen = -1L
            while (watching) {
                val lite = app
                if (lite == null) {
                    Thread.sleep(150)
                    continue
                }
                try {
                    seen = lite.roon.awaitZoneChange(seen, 20_000)
                    if (!watching) return@Thread
                    render(lite)
                } catch (e: Exception) {
                    Log.d(TAG, "zone watch hiccup: ${e.message}")
                    Thread.sleep(2_000)
                }
            }
        }, "dial-watch").apply { isDaemon = true }.start()
    }

    /** Off the main thread: fetching a cover can reach the Core. */
    private fun render(lite: MusicdLite) {
        val zone = lite.activeZone()
        val status = statusLine(lite, zone)
        val art = artFor(lite, zone)
        main.post {
            dial.setZone(zone)
            dial.setStatus(status)
            if (art != null) dial.setArtwork(art)
        }
    }

    private fun statusLine(lite: MusicdLite, zone: Zone?): String = when {
        !lite.roon.isPaired ->
            lite.roon.status.detail ?: "Enable this extension in Roon"
        zone == null -> "No zones yet"
        else -> ""
    }

    private fun artFor(lite: MusicdLite, zone: Zone?) = runCatching {
        val key = zone?.nowPlaying?.imageKey
        if (key == null) {
            artKey = null
            return@runCatching null
        }
        if (key == artKey) return@runCatching null   // already on screen
        val url = lite.roon.imageUrl(key, ART_PX, ART_PX) ?: return@runCatching null
        val bytes = lite.art.get(url, "$key|$ART_PX|$ART_PX|fit")?.bytes ?: return@runCatching null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { artKey = key }
    }.getOrNull()

    // ------------------------------------------------------------- callbacks

    /**
     * Roon has nothing a ring should use to set an absolute level, and each
     * output in a grouped zone keeps its own, so every one is stepped by the
     * same amount instead.
     */
    override fun onVolumeSteps(steps: Int) {
        if (steps == 0) return
        val lite = app ?: return
        val zone = lite.activeZone() ?: return
        background {
            for (out in zone.volumeOutputs) {
                val vol = out.volume ?: continue
                // An incremental control has no scale to step through, so
                // Roon's guidance is relative +/-1 only.
                val how = if (vol.isIncremental) "relative" else "relative_step"
                runCatching { lite.roon.changeVolume(out.outputId, how, steps.toDouble()) }
                    .onFailure { Log.w(TAG, "volume change failed", it) }
            }
        }
    }

    override fun onPlayPause() = transport("playpause")
    override fun onNext() = transport("next")
    override fun onPrevious() = transport("previous")

    private fun transport(command: String) {
        val lite = app ?: return
        val zoneId = lite.activeZone()?.zoneId ?: return
        background {
            runCatching { lite.roon.control(zoneId, command) }
                .onFailure { Log.w(TAG, "transport $command failed", it) }
        }
    }

    override fun onMuteTapped() {
        val lite = app ?: return
        val zone = lite.activeZone() ?: return
        background {
            for (out in zone.volumeOutputs) {
                val muted = out.volume?.isMuted ?: continue
                runCatching { lite.roon.mute(out.outputId, if (muted) "unmute" else "mute") }
                    .onFailure { Log.w(TAG, "mute failed", it) }
            }
        }
    }

    override fun onZoneTapped() {
        val lite = app ?: return
        val zones = runCatching { lite.roon.zones() }.getOrDefault(emptyList())
        if (zones.isEmpty()) {
            Toast.makeText(this, "No zones yet", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Play in")
            .setItems(zones.map { it.displayName }.toTypedArray()) { _, which ->
                // Saving it is what makes the choice stick everywhere: the
                // dial, both widgets, the notification and the app all read
                // the same remembered zone.
                runCatching { lite.settings.saveLastZone(zones[which].zoneId) }
                background { render(lite) }
            }
            .show()
    }

    /**
     * Say an album or an artist, and it plays.
     *
     * The recognising is Android's and the matching is this app's own library
     * index — nothing about what was said leaves the phone beyond whatever the
     * system recogniser already does, and no music service is consulted,
     * because the answer can only be a record you own.
     */
    override fun onVoiceTapped() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Album or artist")
        try {
            dial.voice = DialView.Voice.Listening("")
            @Suppress("DEPRECATION")
            startActivityForResult(intent, SPEECH_REQUEST)
        } catch (e: ActivityNotFoundException) {
            dial.voice = DialView.Voice.Idle
            Toast.makeText(this, "No speech recogniser on this device", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("startActivityForResult is what the speech recogniser answers on")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SPEECH_REQUEST) return

        val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.takeIf { resultCode == RESULT_OK && it.isNotBlank() }
        if (heard == null) {
            dial.voice = DialView.Voice.Idle
            return
        }

        dial.voice = DialView.Voice.Working(heard)
        val lite = app
        if (lite == null) {
            said("Still starting up")
            return
        }
        background {
            val result = lite.playSpoken(heard)
            main.post {
                result.fold(
                    onSuccess = { said("${it.title} — ${it.subtitle}") },
                    onFailure = { said(it.message ?: "Nothing matched") }
                )
            }
        }
    }

    /** Shows a line on the dial, then puts it back to now playing. */
    private fun said(message: String) {
        dial.voice = DialView.Voice.Said(message)
        main.postDelayed({ dial.voice = DialView.Voice.Idle }, 3_500)
    }

    override fun onLongPress() {
        AlertDialog.Builder(this)
            .setTitle("Dial")
            .setItems(arrayOf("Open the full app", "Pick a zone")) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, MainActivity::class.java))
                    1 -> onZoneTapped()
                }
            }
            .show()
    }

    private fun background(body: () -> Unit) {
        Thread({ runCatching(body) }, "dial-action").apply { isDaemon = true }.start()
    }
}
