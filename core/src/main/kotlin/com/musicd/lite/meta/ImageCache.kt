package com.musicd.lite.meta

import com.musicd.lite.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Album art, cached so browsing puts no repeated load on the Roon Core.
 *
 * Roon's image service is reachable over plain HTTP on the same host and port
 * as the extension API, so art is fetched directly rather than pulled through
 * the MOO socket — which matters, because that socket also carries every browse
 * and transport call, and a wall of sixty tiles would otherwise queue behind
 * itself.
 *
 * Two tiers: a small in-memory tier for the wall being looked at right now, and
 * a disk tier under the app's own cache directory that survives a restart.
 * Android may delete the disk tier under storage pressure, which costs a
 * refetch and nothing else.
 *
 * Both tiers are bounded. The disk tier used to be the exception, and that was
 * a real leak rather than a theoretical one: art is cached per rendering, so a
 * large library browsed at a thumbnail size and a full size is two files per
 * album, forever. Nothing ever deleted them. Android only reclaims an app's
 * cache directory when the device is close to full, and it does it by wiping
 * the lot — so between those two extremes the app simply grew, and showed up
 * in Settings as hundreds of megabytes of "cache" that nothing would give
 * back. It is now swept back to [diskBudgetBytes], eldest first.
 */
class ImageCache(
    private val fetcher: Fetcher,
    private val diskDir: File?,
    private val memoryBudgetBytes: Long = 24L * 1024 * 1024,
    private val diskBudgetBytes: Long = 256L * 1024 * 1024
) {

    /** Where art comes from when neither tier has it. */
    fun interface Fetcher {
        fun fetch(url: String): Art?
    }

    /** The real one: Roon's image service over plain HTTP. */
    constructor(
        http: OkHttpClient,
        diskDir: File?,
        memoryBudgetBytes: Long = 24L * 1024 * 1024,
        diskBudgetBytes: Long = 256L * 1024 * 1024
    ) : this(httpFetcher(http), diskDir, memoryBudgetBytes, diskBudgetBytes)

    private companion object {
        const val TAG = "Art"

        /**
         * How much new art is written before the disk tier is swept.
         *
         * The sweep lists and stats the whole directory, which on a large
         * library is thousands of files. Doing that once per fetched thumbnail
         * would cost more than the growth it exists to prevent, so it is
         * amortised: a sweep every few megabytes written keeps the tier within
         * a few megabytes of its budget, which is close enough for a cache.
         */
        const val PRUNE_EVERY_BYTES = 8L * 1024 * 1024

        private fun httpFetcher(http: OkHttpClient) = Fetcher { url ->
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.d(TAG, "$url -> ${response.code}")
                        null
                    } else {
                        val body = response.body?.bytes()
                        if (body == null || body.isEmpty()) null
                        else Art(response.header("Content-Type") ?: "image/jpeg", body)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "$url failed: ${e.message}")
                null
            }
        }
    }

    class Art(val contentType: String, val bytes: ByteArray)

    /**
     * Access-ordered, so the eldest entry is the least recently *used* rather
     * than the least recently added — a wall of tiles being scrolled back
     * through should not evict the art it is about to show again.
     *
     * Eviction is the loop in [toMemory] alone. An earlier version also
     * overrode removeEldestEntry, which never actually ran: that hook fires
     * during put(), by which point the loop had already brought the total under
     * budget, so the code that appeared to do the evicting did nothing.
     */
    private var memoryBytes = 0L

    private val memory = LinkedHashMap<String, Art>(64, 0.75f, true)

    /**
     * A cache filename that cannot collide and cannot escape the directory.
     * Roon's image keys are opaque and have contained characters that are not
     * safe in a path, so they are hashed rather than sanitised.
     */
    private fun fileName(cacheKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".jpg"
    }

    @Synchronized
    private fun fromMemory(key: String): Art? = memory[key]

    @Synchronized
    private fun toMemory(key: String, art: Art) {
        val previous = memory.put(key, art)
        if (previous != null) memoryBytes -= previous.bytes.size
        memoryBytes += art.bytes.size
        val entries = memory.entries.iterator()
        while (memoryBytes > memoryBudgetBytes && entries.hasNext()) {
            val eldest = entries.next()
            entries.remove()
            memoryBytes -= eldest.value.bytes.size
        }
    }

    /** Bytes currently held in the memory tier. For the tests. */
    @Synchronized
    internal fun memoryFootprint(): Long = memoryBytes

    @Synchronized
    internal fun memoryKeys(): Set<String> = LinkedHashSet(memory.keys)

    /**
     * @param url the Core's image URL, already carrying the size parameters.
     * @param cacheKey identity of this exact rendering (key plus size), so a
     *                 thumbnail request never serves the full-size art.
     */
    fun get(url: String, cacheKey: String): Art? {
        fromMemory(cacheKey)?.let { return it }

        val file = diskDir?.let { File(it, fileName(cacheKey)) }
        if (file != null && file.isFile && file.length() > 0) {
            return try {
                // Marks it as recently used, which is what the sweep orders by.
                runCatching { file.setLastModified(System.currentTimeMillis()) }
                Art("image/jpeg", file.readBytes()).also { toMemory(cacheKey, it) }
            } catch (e: Exception) {
                Log.d(TAG, "disk read failed for $cacheKey: ${e.message}")
                null
            }
        }

        val art = fetcher.fetch(url) ?: return null
        toMemory(cacheKey, art)
        if (file != null) {
            try {
                diskDir.mkdirs()
                // Write to a sibling and rename, so an interrupted write cannot
                // leave a truncated file that then caches as the real art.
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeBytes(art.bytes)
                if (tmp.renameTo(file)) {
                    noteWrite(art.bytes.size.toLong())
                } else {
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.d(TAG, "disk write failed for $cacheKey: ${e.message}")
            }
        }
        return art
    }

    @Synchronized
    fun clearMemory() {
        memory.clear()
        memoryBytes = 0
    }

    /**
     * Bytes written since the last sweep, and whether one is already running.
     *
     * Deliberately not the object monitor the memory tier uses: a sweep does
     * file I/O over the whole directory, and holding the lock that every
     * memory hit needs for the length of that would stall the wall being
     * scrolled.
     */
    private val pruneLock = Object()
    private var writtenSincePrune = 0L
    private var pruning = false

    /** Records a disk write and sweeps when enough has accumulated. */
    private fun noteWrite(bytes: Long) {
        val mine = synchronized(pruneLock) {
            writtenSincePrune += bytes
            if (pruning || writtenSincePrune < PRUNE_EVERY_BYTES) {
                false
            } else {
                writtenSincePrune = 0
                pruning = true
                true
            }
        }
        if (!mine) return
        try {
            pruneDisk()
        } finally {
            synchronized(pruneLock) { pruning = false }
        }
    }

    /**
     * Deletes the least recently used art until the disk tier is within budget.
     *
     * Ordered by last-modified, which [get] touches on every hit, so this is
     * true LRU wherever the filesystem honours the touch and falls back to
     * eldest-written where it does not. Either way the tier stays bounded,
     * which is the property that matters; the worst a failed touch costs is
     * refetching a picture.
     */
    internal fun pruneDisk() {
        val dir = diskDir ?: return
        val files = try {
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }
        } catch (e: Exception) {
            Log.d(TAG, "could not list the art cache: ${e.message}")
            null
        } ?: return

        var total = files.sumOf { it.length() }
        if (total <= diskBudgetBytes) return

        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= diskBudgetBytes) break
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) total -= size
        }
        Log.d(TAG, "art cache swept to ${total / (1024 * 1024)}MB")
    }

    /** Bytes currently held in the disk tier. For the tests. */
    internal fun diskFootprint(): Long =
        diskDir?.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }
            ?.sumOf { it.length() } ?: 0L
}
