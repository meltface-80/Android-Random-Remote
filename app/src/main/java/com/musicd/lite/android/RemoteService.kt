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
                multicastLock = WifiMulticastLock()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restarted by the system after being killed: come back up and re-pair.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
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
    private inner class StatusWatcher : RoonApi.Listener {
        override fun onStatusChanged(status: RoonStatus) {
            val title = when (status.stage) {
                RoonStage.PAIRED -> status.coreName ?: "Paired with Roon"
                RoonStage.AWAITING_APPROVAL -> "Waiting for approval in Roon"
                RoonStage.DISCOVERING -> "Looking for a Roon Core"
                RoonStage.CONNECTING -> "Connecting…"
                RoonStage.ERROR -> "Not connected"
                RoonStage.IDLE -> "Idle"
            }
            notify(title, status.detail ?: "")
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
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
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