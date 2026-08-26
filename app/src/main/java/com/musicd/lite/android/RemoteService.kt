package com.musicd.lite.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.util.Log as AndroidLog
import com.musicd.lite.Log
import com.musicd.lite.MusicdLite
import com.musicd.lite.roon.RoonApi
import com.musicd.lite.roon.RoonCore
import com.musicd.lite.roon.RoonStage
import com.musicd.lite.roon.RoonStatus
import com.musicd.lite.store.MemoryStore
import com.musicd.lite.store.Store
import java.io.File

/**
 * Keeps the extension alive.
 *
 * The app IS a Roon extension, not a client of one: the pairing, the library
 * snapshot and the play history all live in this process. If it were only alive
 * while its window was on screen, Roon would see the extension disconnect every
 * time the phone was pocketed, the library would be re-walked on every return,
 * and Random Album Radio could not put the next record on. A foreground service
 * is the only way Android lets an app hold a socket open like that, and the
 * notification is the honest price of it.
 */
class RemoteService : Service() {

    companion object {
        private const val TAG = "RemoteService"
        private const val CHANNEL_ID = "musicd-lite-status"
        private const val NOTIFICATION_ID = 1

        /**
         * Transport actions the notification's own buttons send back here.
         * A media button pressed on a headset reaches the session directly;
         * these are for the buttons drawn in the shade.
         */
        const val ACTION_PLAY_PAUSE = "com.musicd.lite.PLAY_PAUSE"
        const val ACTION_NEXT = "com.musicd.lite.NEXT"
        const val ACTION_PREVIOUS = "com.musicd.lite.PREVIOUS"

        /**
         * The running instance, so the Activity can find the local server's
         * port. A bound service would be the tidier shape, but the Activity
         * needs this before it can render anything at all, and binding is
         * asynchronous.
         */
        @Volatile
        var instance: RemoteService? = null
            private set
    }

    /** Published from the startup thread; read by the Activity's thread. */
    @Volatile
    var app: MusicdLite? = null
        private set

    private var multicastLock: WifiManager.MulticastLock? = null
    private var store: Store? = null
    private var nowPlaying: NowPlayingSession? = null

    /** Stops the zone watcher when the service goes away. */
    @Volatile
    private var watching = false

    override fun onCreate() {
        super.onCreate()
        Log.sink = LogcatSink()
        Log.debug = BuildConfig.DEBUG

        // Android gives a foreground service about five seconds to post its
        // notification, so that goes first and everything slower goes after.
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting…", "Looking for your Roon Core"))
        instance = this

        // Opening the database is disk I/O and binding the server is a syscall;
        // neither belongs on the main thread. The Activity already waits for
        // the server's address rather than assuming it exists (see
        // MainActivity.waitForServer), so starting up asynchronously costs
        // nothing on screen.
        Thread({ startUp() }, "musicd-startup").start()
    }

    private fun startUp() {
        val backing = try {
            AndroidStore(this)
        } catch (e: Exception) {
            // A remote that forgets its history is far better than one that
            // refuses to start.
            AndroidLog.e(TAG, "the database would not open; running without one", e)
            MemoryStore()
        }
        store = backing

        val lite = try {
            MusicdLite(
                store = backing,
                assets = AndroidAssets(this),
                artDir = File(cacheDir, "art"),
                version = BuildConfig.VERSION_NAME,
                httpPort = 0,
                multicastLock = WifiMulticastLock(),
                // The Android half of an update. :core notices the new version
                // and downloads it; only the system installer can apply it.
                updateInstaller = MusicdLite.UpdateInstaller(
                    downloadDir = ApkInstaller.downloadDir(this),
                    install = { apk -> ApkInstaller.install(this, apk) }
                )
            )
        } catch (e: Exception) {
            AndroidLog.e(TAG, "could not start the local server", e)
            notify("Could not start", "The remote could not open its local server. Reopen the app.")
            return
        }

        lite.roon.addListener(StatusWatcher())
        lite.start()
        app = lite
        AndroidLog.i(TAG, "serving the UI on ${lite.rootUrl}")

        // The media session is a bonus, never a dependency: if it cannot be
        // built the app still serves the UI and stays paired with Roon, which
        // is its actual job.
        runCatching {
            nowPlaying = NowPlayingSession(this, lite) { refreshNotification() }
                .takeIf { it.start() }
            if (nowPlaying != null) startZoneWatch(lite)
        }.onFailure { AndroidLog.w(TAG, "media session unavailable", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> nowPlaying?.command("playpause")
            ACTION_NEXT -> nowPlaying?.command("next")
            ACTION_PREVIOUS -> nowPlaying?.command("previous")
        }
        // Restarted by the system after being killed: come back up and re-pair.
        return START_STICKY
    }

    /**
     * Follows the zone the user is watching and keeps the session on it.
     *
     * This rides the same waiting primitive the front-end now uses: block until
     * Roon says something, act, block again. A timer would have to pick between
     * being slow and being wasteful; this is neither.
     */
    private fun startZoneWatch(lite: MusicdLite) {
        watching = true
        Thread({
            var seen = -1L
            while (watching) {
                try {
                    seen = lite.roon.awaitZoneChange(seen, 20_000)
                    if (!watching) return@Thread
                    refreshNotification()
                } catch (e: Exception) {
                    AndroidLog.d(TAG, "zone watch hiccup: ${e.message}")
                    Thread.sleep(2_000)
                }
            }
        }, "zone-watch").apply { isDaemon = true }.start()
    }

    /** The zone the session and the notification are about. */
    private fun currentZone() = app?.let { lite ->
        runCatching {
            lite.roon.zone(lite.settings.lastZone())
                ?: lite.roon.zones().firstOrNull { it.isPlaying }
                ?: lite.roon.zones().firstOrNull()
        }.getOrNull()
    }

    private fun refreshNotification() {
        runCatching {
            val zone = currentZone()
            nowPlaying?.update(zone)
            val status = app?.roon?.status
            notify(
                status?.let { statusTitle(it) } ?: "MusicD Remote Lite",
                status?.detail ?: ""
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        watching = false
        runCatching { nowPlaying?.stop() }
        nowPlaying = null
        app?.stop()
        app = null
        runCatching { store?.close() }
        releaseMulticastLock()
        super.onDestroy()
    }

    // --------------------------------------------------------- notification

    /**
     * The notification says what the extension is doing, because on a first run
     * it is genuinely waiting on the user: nothing works until they enable the
     * extension in Roon, and that instruction has to be somewhere they will see
     * it even if the app is in the background.
     */
    /** Shared by the status listener and the zone watcher. */
    private fun statusTitle(status: RoonStatus): String = when (status.stage) {
        RoonStage.PAIRED -> status.coreName ?: "Paired with Roon"
        RoonStage.AWAITING_APPROVAL -> "Waiting for approval in Roon"
        RoonStage.DISCOVERING -> "Looking for a Roon Core"
        RoonStage.CONNECTING -> "Connecting…"
        RoonStage.ERROR -> "Not connected"
        RoonStage.IDLE -> "Idle"
    }

    private inner class StatusWatcher : RoonApi.Listener {
        override fun onStatusChanged(status: RoonStatus) {
            // Pairing news replaces the whole notification; while paired the
            // zone watcher owns it, so the now-playing detail is not thrown
            // away every time the Core reports the same state again.
            if (status.stage == RoonStage.PAIRED && nowPlaying != null) {
                refreshNotification()
            } else {
                notify(statusTitle(status), status.detail ?: "")
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "MusicD Remote status", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows whether the app is paired with your Roon Core."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)

        // With a media session and a paired Core, this becomes the now-playing
        // notification: artwork, the track, and transport that works from the
        // lock screen. Without either it stays the plain status line it was.
        val session = nowPlaying
        val zone = if (session == null) null else currentZone()
        if (session != null && zone != null) {
            session.actions().forEach { builder.addAction(it) }
            return session.decorate(builder, zone).build()
        }
        return builder.build()
    }

    private fun notify(title: String, text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(title, text))
        }
    }

    // ------------------------------------------------------- multicast lock

    /**
     * Android filters multicast and broadcast traffic out of userspace unless a
     * lock is held, so without this the SOOD replies never arrive and discovery
     * silently finds nothing.
     */
    private inner class WifiMulticastLock : RoonCore.MulticastLock {
        override fun acquire() {
            if (multicastLock != null) return
            runCatching {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("musicd-lite-sood").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure { AndroidLog.w(TAG, "could not take the multicast lock", it) }
        }

        override fun release() = releaseMulticastLock()
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    /** Routes the core module's logging to Logcat. */
    private class LogcatSink : Log.Sink {
        override fun write(level: Char, tag: String, message: String, error: Throwable?) {
            val t = "MusicD/$tag"
            when (level) {
                'D' -> AndroidLog.d(t, message, error)
                'I' -> AndroidLog.i(t, message, error)
                'W' -> AndroidLog.w(t, message, error)
                else -> AndroidLog.e(t, message, error)
            }
        }
    }
}