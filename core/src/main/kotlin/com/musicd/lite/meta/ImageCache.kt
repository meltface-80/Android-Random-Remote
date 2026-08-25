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
    private val http: OkHttpClient,
    private val diskDir: File?,
    private val memoryBudgetBytes: Long = 24L * 1024 * 1024
) {

    private companion object {
        const val TAG = "Art"
    }

    class Art(val contentType: String, val bytes: ByteArray)

    private var memoryBytes = 0L

    private val memory = object : LinkedHashMap<String, Art>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Art>?): Boolean {
            if (eldest == null) return false
            if (memoryBytes <= memoryBudgetBytes) return false
            memoryBytes -= eldest.value.bytes.size
            return true
        }
    }

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
        // Trim by touching the map so removeEldestEntry runs against the budget.
        while (memoryBytes > memoryBudgetBytes && memory.isNotEmpty()) {
            val eldest = memory.entries.iterator()
            if (!eldest.hasNext()) break
            val e = eldest.next()
            eldest.remove()
            memoryBytes -= e.value.bytes.size
        }
    }

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

        val art = fetch(url) ?: return null
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

    private fun fetch(url: String): Art? = try {
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

    @Synchronized
    fun clearMemory() {
        memory.clear()
        memoryBytes = 0
    }
}
