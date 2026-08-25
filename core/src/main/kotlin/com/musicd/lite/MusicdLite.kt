package com.musicd.lite

import com.musicd.lite.api.RemoteApi
import com.musicd.lite.api.Settings
import com.musicd.lite.api.StaticAssets
import com.musicd.lite.http.HttpServer
import com.musicd.lite.library.AlbumIndex
import com.musicd.lite.library.AlbumRecord
import com.musicd.lite.library.Albums
import com.musicd.lite.library.LibraryView
import com.musicd.lite.library.Normalize
import com.musicd.lite.meta.ImageCache
import com.musicd.lite.meta.Metadata
import com.musicd.lite.meta.metadataHttpClient
import com.musicd.lite.roon.RoonApi
import com.musicd.lite.roon.RoonCore
import com.musicd.lite.roon.Zone
import com.musicd.lite.store.Store
import com.musicd.lite.store.YearSource
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The whole app, minus the Android shell.
 *
 * Everything MusicD-Remote's Node process does — pair with the Core, hold the
 * library snapshot, keep the play history, serve the front-end and its API —
 * happens here, in one object with a start and a stop. The Android module adds
 * a WebView pointed at [rootUrl], a foreground service to keep this alive, and
 * a SQLite-backed [Store].
 */
class MusicdLite(
    private val store: Store,
    assets: StaticAssets,
    artDir: File?,
    val version: String,
    /** 0 lets the OS pick a free loopback port, which cannot clash. */
    httpPort: Int = 0,
    multicastLock: RoonCore.MulticastLock = RoonCore.MulticastLock.NONE,
    /**
     * How the Roon client is built. Production uses the real one; the tests
     * pass a scripted Core so the whole API can be driven over real HTTP with
     * no Roon on the network.
     */
    roonFactory: (Store, RoonCore.ExtensionInfo, RoonCore.MulticastLock) -> RoonApi =
        { store, extension, lock -> RoonCore(store, extension, lock) }
) {

    private companion object {
        const val TAG = "MusicdLite"

        /**
         * How often to ask Roon whether the library moved. The CHECK is frequent
         * because it is a two-call probe; the REBUILD is rare because it is a
         * full walk. This keeps the app off a busy Core entirely.
         */
        const val LIBRARY_CHECK_MS = 10L * 60 * 1000

        /** History older than this is dropped. */
        const val PLAYS_RETENTION_DAYS = 400L
    }

    val extension = RoonCore.ExtensionInfo(
        id = "com.musicd.lite.android",
        displayName = "MusicD Remote Lite (Android)",
        version = version,
        publisher = "Android-Random-Remote",
        email = "noreply@example.com",
        website = "https://github.com/meltface-80/Android-Random-Remote"
    )

    private val http = metadataHttpClient()

    val roon: RoonApi = roonFactory(store, extension, multicastLock)
    val index = AlbumIndex()
    val settings = Settings(store)
    val view = LibraryView(index, store)
    val albums = Albums(roon.tree, index, store)
    val metadata = Metadata(http, "MusicDRemoteLite/$version ( ${extension.website} )")
    val art = ImageCache(http, artDir)
    val radio = Radio(this)

    private val jobs: ScheduledExecutorService =
        Executors.newScheduledThreadPool(2) { r ->
            Thread(r, "musicd-jobs").apply { isDaemon = true }
        }

    private val api = RemoteApi(this, assets)
    private val server = HttpServer(api, httpPort)

    private val started = AtomicBoolean(false)

    /** Where the WebView should point. Valid after [start]. */
    val rootUrl: String get() = server.rootUrl

    val port: Int get() = server.port

    fun store(): Store = store

    fun start() {
        if (!started.compareAndSet(false, true)) return
        server.start()
        roon.addListener(Events())
        roon.start()
        jobs.scheduleWithFixedDelay(
            ::libraryMaintenance, LIBRARY_CHECK_MS, LIBRARY_CHECK_MS, TimeUnit.MILLISECONDS
        )
        jobs.schedule(
            { runCatching { store.prunePlays(System.currentTimeMillis() - PLAYS_RETENTION_DAYS * 86_400_000L) } },
            30, TimeUnit.SECONDS
        )
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        jobs.shutdownNow()
        server.stop()
        roon.stop()
    }

    // ------------------------------------------------------- library upkeep

    private val rebuilding = AtomicBoolean(false)

    /**
     * Walk the library on a background thread. Returns immediately; the UI
     * watches [AlbumIndex.progress] through /api/status.
     */
    fun rebuildIndex(reason: String) {
        if (!roon.isPaired) return
        if (!rebuilding.compareAndSet(false, true)) return
        jobs.execute {
            try {
                Log.i(TAG, "rebuilding the album index ($reason)")
                val before = index.albums.associate { it.key to it.offset }
                if (index.build(roon.tree)) {
                    roon.tree.clearOffsetCache()
                    recordFirstSeen(before.isEmpty())
                }
            } catch (e: Exception) {
                Log.w(TAG, "index rebuild failed: ${e.message}", e)
            } finally {
                rebuilding.set(false)
            }
        }
    }

    /**
     * The "recently added" clock. Roon publishes no import date for an album, so
     * the only honest answer is when this app first saw it. On the very first
     * scan every album is new at once, which would make "recently added" mean
     * "everything" — so that scan records nothing and the clock starts from the
     * next one.
     */
    private fun recordFirstSeen(firstEverScan: Boolean) {
        if (firstEverScan) {
            Log.i(TAG, "first scan — not dating ${index.count} albums as newly added")
            store.recordFirstSeen(index.albums.associate { it.key to 0L })
            return
        }
        val now = System.currentTimeMillis()
        val known = store.firstSeenAll()
        val fresh = index.albums.filter { it.key !in known }.associate { it.key to now }
        if (fresh.isNotEmpty()) {
            Log.i(TAG, "${fresh.size} albums are new since the last scan")
            store.recordFirstSeen(fresh)
        }
    }

    /**
     * A two-call probe: has Roon's album count moved, or has the first album
     * changed identity (a count-neutral reorder)? Only then is a full walk
     * worth it.
     */
    private fun libraryMaintenance() {
        if (!roon.isPaired) return
        try {
            if (!index.isBuilt) {
                rebuildIndex("index is empty")
                return
            }
            val moved = roon.tree.withSession { key ->
                roon.tree.browse("albums", key, popAll = true)
                val head = roon.tree.load("albums", key, 0, 1)
                val declared = if (index.declared > 0) index.declared else index.count
                val first = head.items.firstOrNull()
                val snapshotFirst = index.albums.firstOrNull()
                head.total != declared ||
                    (first != null && snapshotFirst != null &&
                        Normalize.text(first.title) != snapshotFirst.nTitle)
            }
            if (moved) rebuildIndex("Roon's album list changed")
        } catch (e: Exception) {
            Log.d(TAG, "library probe failed: ${e.message}")
        }
    }

    // ------------------------------------------------------- play history

    /**
     * What is playing, per zone, so a change can be recorded exactly once.
     *
     * Roon has no "played" event for extensions: the zone feed is the only
     * signal there is, and it repeats the same now_playing on every seek tick.
     */
    private val lastRecorded = HashMap<String, String>()

    private inner class Events : RoonApi.Listener {
        override fun onPaired() {
            if (!index.isBuilt) rebuildIndex("just paired")
        }

        override fun onZonesChanged(zones: List<Zone>) {
            for (zone in zones) recordIfChanged(zone)
            runCatching { radio.onZones(zones) }
        }

        override fun onDisconnected() {
            synchronized(lastRecorded) { lastRecorded.clear() }
        }
    }

    private fun recordIfChanged(zone: Zone) {
        val np = zone.nowPlaying ?: return
        if (!zone.isPlaying) return
        val track = np.line1
        val album = np.line3
        val artist = np.line2
        if (track.isEmpty() && album.isEmpty()) return
        val stamp = "$track|$album|$artist"
        synchronized(lastRecorded) {
            if (lastRecorded[zone.zoneId] == stamp) return
            lastRecorded[zone.zoneId] = stamp
        }
        val key = AlbumRecord(0, album, artist, null).key
        runCatching { store.recordPlay(key, album, artist, track, System.currentTimeMillis()) }
        // A year learned once is worth keeping, but never at the cost of a
        // better source: file tags and MusicBrainz both outrank a guess.
        runCatching {
            index.relocate(album, artist)?.let { hit ->
                if (store.albumYear(hit.key) == null) {
                    jobs.execute {
                        metadata.extras(hit.title, hit.subtitle).year?.let { y ->
                            store.putAlbumYear(hit.key, y, YearSource.MUSICBRAINZ)
                        }
                    }
                }
            }
        }
    }

    /** Run [body] off the request thread — used by the rescan endpoints. */
    fun background(body: () -> Unit) {
        runCatching { jobs.execute { runCatching(body) } }
    }
}
