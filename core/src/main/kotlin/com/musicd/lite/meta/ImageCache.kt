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
 */
class ImageCache(
    private val fetcher: Fetcher,
    private val diskDir: File?,
    private val memoryBudgetBytes: Long = 24L * 1024 * 1024
) {

    /** Where art comes from when neither tier has it. */
    fun interface Fetcher {
        fun fetch(url: String): Art?
    }

    /** The real one: Roon's image service over plain HTTP. */
    constructor(
        http: OkHttpClient,
        diskDir: File?,
        memoryBudgetBytes: Long = 24L * 1024 * 1024
    ) : this(httpFetcher(http), diskDir, memoryBudgetBytes)

    private companion object {
        const val TAG = "Art"

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
                if (!tmp.renameTo(file)) tmp.delete()
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
}
