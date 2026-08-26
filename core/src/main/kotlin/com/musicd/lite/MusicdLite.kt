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
import com.musicd.lite.meta.Pitchfork
import com.musicd.lite.meta.Updater
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
    /**
     * How long the album count must hold still before a rescan believes Roon
     * has finished importing. Zero skips the wait, which is what the tests want
     * and what a caller who has already established the library is quiet can
     * ask for.
     */
    private val importSettleMs: Long = IMPORT_SETTLE_MS,
    /**
     * Where a downloaded update goes, and how it is installed. Null on any host
     * that cannot install an APK — the JVM tests, for one — and the update
     * routes then report that updates are not available here rather than
     * offering a button that leads nowhere.
     */
    private val updateInstaller: UpdateInstaller? = null,
    /**
     * Asks the system to offer adding the Quick Settings tile, and reports what
     * it said. Null on any host without one — the JVM tests, and any Android
     * below 13, where the tile can only be added by hand from the shade's
     * editor.
     *
     * This exists because "the tile is declared correctly" and "the user can
     * find the tile" turned out to be different things, and only the first one
     * is something the manifest can settle.
     */
    private val tileInstaller: TileInstaller? = null,
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

        /** How long the album count must hold still to count as "not importing". */
        const val IMPORT_SETTLE_MS = 5_000L

        /** Published by CI next to the APK it describes. */
        const val UPDATE_MANIFEST_URL =
            "https://raw.githubusercontent.com/meltface-80/Android-Random-Remote/main/dist/latest.json"
    }

    /** What an Android host supplies so [updater] can finish the job. */
    class UpdateInstaller(val downloadDir: File, val install: (File) -> Unit)

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
    val pitchfork = Pitchfork(http, "MusicDRemoteLite/$version ( ${extension.website} )")

    /**
     * The published manifest CI writes beside the APK. Read from the default
     * branch, which is where the download link in the README points too.
     */
    val updater: Updater? = updateInstaller?.let {
        Updater(
            http = http,
            currentVersion = version,
            manifestUrl = UPDATE_MANIFEST_URL,
            downloadDir = it.downloadDir,
            install = it.install
        )
    }
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
    private fun libraryChangedSince(): Boolean = roon.tree.withSession { key ->
        roon.tree.browse("albums", key, popAll = true)
        val head = roon.tree.load("albums", key, 0, 1)
        val declared = if (index.declared > 0) index.declared else index.count
        val first = head.items.firstOrNull()
        val snapshotFirst = index.albums.firstOrNull()
        head.total != declared ||
            (first != null && snapshotFirst != null &&
                Normalize.text(first.title) != snapshotFirst.nTitle)
    }

    /**
     * Is Roon still adding albums?
     *
     * There is no "importing" flag in the extension API, so the only signal is
     * the album count refusing to hold still. Rebuilding mid-import walks a
     * list that is moving underneath the walk, which is how a snapshot ends up
     * with holes — so a count that is still changing means wait.
     */
    private fun libraryIsImporting(): Boolean {
        fun albumCount(): Int = roon.tree.withSession { key ->
            roon.tree.browse("albums", key, popAll = true)
            roon.tree.load("albums", key, 0, 1).total
        }

        val first = albumCount()
        if (importSettleMs <= 0) return false
        try {
            Thread.sleep(importSettleMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return albumCount() != first
    }

    /**
     * The Rescan button, answered synchronously.
     *
     * The caller is waiting to be told whether their library changed, so the
     * check and the rebuild both happen before the response — the front-end
     * renders one of these statuses as its toast and treats anything it does
     * not recognise as "Rescan failed".
     *
     * @param force a human pressed the button, so skip the "did anything
     *              change?" probe and rebuild regardless.
     */
    fun rescan(force: Boolean): RescanResult {
        if (!roon.isPaired) return RescanResult("unpaired")
        if (!rebuilding.compareAndSet(false, true)) return RescanResult("busy")
        try {
            val changed = try {
                force || libraryChangedSince()
            } catch (e: Exception) {
                Log.w(TAG, "rescan probe failed: ${e.message}")
                return RescanResult("error")
            }
            if (!changed) return RescanResult("fresh")

            if (runCatching { libraryIsImporting() }.getOrDefault(false)) {
                Log.i(TAG, "library changed but Roon is still importing — refresh paused")
                return RescanResult("importing")
            }

            val before = index.isBuilt
            val built = try {
                index.build(roon.tree)
            } catch (e: Exception) {
                Log.w(TAG, "rescan rebuild failed: ${e.message}", e)
                false
            }
            // A rebuild that threw leaves the OLD snapshot in place, so saying
            // "rebuilt" would report a refresh that did not happen.
            if (!built) return RescanResult("error")
            roon.tree.clearOffsetCache()
            recordFirstSeen(firstEverScan = !before)
            return RescanResult("rebuilt", index.count)
        } finally {
            rebuilding.set(false)
        }
    }

    data class RescanResult(val status: String, val count: Int? = null)

    private fun libraryMaintenance() {
        if (!roon.isPaired) return
        try {
            if (!index.isBuilt) {
                rebuildIndex("index is empty")
                return
            }
            if (libraryChangedSince()) rebuildIndex("Roon's album list changed")
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

    /**
     * How the Android shell offers the Quick Settings tile.
     *
     * [supported] is false where the system has no way to ask — below Android
     * 13 the only route is the shade's own tile editor.
     */
    class TileInstaller(
        val supported: Boolean,
        /** Puts up the system's "add tile?" prompt. */
        val request: () -> Unit
    )

    /** Whether this host can put up the system's "add tile?" prompt at all. */
    fun tileSupported(): Boolean = tileInstaller?.supported ?: false

    fun requestTile(): Result<Unit> {
        val installer = tileInstaller
            ?: return Result.failure(IllegalStateException("Not available on this device"))
        if (!installer.supported) {
            return Result.failure(
                IllegalStateException("Android 13 or newer is needed to add it this way")
            )
        }
        return runCatching { installer.request() }
    }

    /**
     * The zone everything outside the web UI is about: the notification, the
     * media session, the widget and the tile.
     *
     * The saved zone is only used if it still exists. That check is the whole
     * point of this method: zone ids are not stable across a Core restart, a
     * regrouping or a rename, so a remembered id goes stale silently, and Roon
     * accepts a transport command for an unknown zone by quietly doing nothing.
     * Three callers resolved this themselves and only one of them validated —
     * so on a stale id the buttons stopped working with no error anywhere.
     */
    fun activeZone(): Zone? = runCatching {
        val zones = roon.zones()
        if (zones.isEmpty()) return@runCatching null
        settings.lastZone()?.let { id -> zones.firstOrNull { it.zoneId == id } }?.let {
            return@runCatching it
        }
        // Nothing remembered, so pick one — and then remember it. Without that
        // last step the answer moved with playback: "the zone that is playing"
        // resolves to a real zone while something is playing and to "whichever
        // zone happens to be first" the moment everything pauses. So pausing
        // and then pressing play could address two different rooms, which is
        // exactly what a remote must never do.
        val chosen = zones.firstOrNull { it.isPlaying } ?: zones.first()
        runCatching { settings.saveLastZone(chosen.zoneId) }
        chosen
    }.getOrNull()

    /**
     * Put a random album on, in one call.
     *
     * The home screen widget, the Quick Settings tile and the shortcut
     * endpoints all want exactly this and none of them should have to know how
     * it is done. It lives here rather than in the HTTP layer because two of
     * those three callers are Android surfaces that never go near HTTP — a
     * widget tapped on the launcher should not have to talk to a socket inside
     * its own process.
     *
     * @param zoneId the zone to play in; the last zone the app watched, then
     *               any zone, when null.
     * @return what started playing, or null with a reason.
     */
    fun playRandomAlbum(
        zoneId: String? = null,
        unheardOnly: Boolean = false
    ): Result<AlbumRecord> {
        val zone = zoneId
            ?: activeZone()?.zoneId
            ?: return Result.failure(IllegalStateException("No zones available"))
        val pool = if (unheardOnly) view.unplayed(6).ifEmpty { index.albums } else index.albums
        val album = view.sample(pool, 1).firstOrNull()
            ?: return Result.failure(IllegalStateException("The library index is still building"))
        return runCatching {
            albums.open(album.offset, zone, "play_now", null, Albums.Expect(album.title, album.subtitle))
            album
        }
    }

    /** Run [body] off the request thread — used by the rescan endpoints. */
    fun background(body: () -> Unit) {
        runCatching { jobs.execute { runCatching(body) } }
    }
}
