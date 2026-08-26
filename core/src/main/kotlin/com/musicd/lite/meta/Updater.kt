package com.musicd.lite.meta

import com.musicd.lite.Log
import com.musicd.lite.str
import com.musicd.lite.strOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * In-app updates: notice a newer APK, fetch it, hand it to Android to install.
 *
 * The Docker build could update itself because it controlled its own process.
 * An app cannot: only the system installer may replace an APK, and it always
 * asks the user. So "apply" here means download and then present the install —
 * everything up to the point where Android takes over.
 *
 * The whole thing rests on stable signing. Android refuses to install an APK
 * over one signed with a different key, so an update downloaded here is simply
 * rejected unless both builds carry the same certificate. That is why this
 * arrived alongside the signing fix rather than before it.
 *
 * The download and the install are separated by an [install] callback so
 * everything except the Android intent is a plain JVM class this repository can
 * test: the version compare, the manifest parsing, the phase machine and the
 * digest check all run without a device.
 */
class Updater(
    private val http: OkHttpClient,
    private val currentVersion: String,
    private val manifestUrl: String,
    private val downloadDir: File,
    /** Hands the finished file to the system installer. */
    private val install: (File) -> Unit
) {

    // (constants and the version compare live in the companion at the bottom)

    /**
     * The phase names are the front-end's, not ours.
     *
     * The update banner maps exactly these to its progress text and treats
     * anything else as "no update in progress", so they are a contract:
     * checking / downloading / extracting / restarting / error.
     *
     * "extracting" is the verify step. The name is inherited from the Docker
     * build, where it really did unpack a tarball; renaming it here would only
     * mean the banner showed nothing during the check.
     */
    enum class Phase(val wire: String) {
        IDLE("idle"),
        CHECKING("checking"),
        DOWNLOADING("downloading"),
        VERIFYING("extracting"),
        AWAITING_INSTALL("restarting"),
        ERROR("error")
    }

    data class Release(val version: String, val url: String, val sha256: String?, val notes: String?)

    data class State(
        val phase: Phase = Phase.IDLE,
        val error: String? = null,
        val latest: Release? = null
    )

    private val state = AtomicReference(State())
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    fun status(): JSONObject {
        val s = state.get()
        val latest = s.latest
        val newer = latest != null && compareVersions(latest.version, currentVersion) != 0
        return JSONObject()
            .put("current", currentVersion)
            .put("available", newer)
            .put("latest", latest?.version ?: JSONObject.NULL)
            .put("notes", latest?.notes ?: JSONObject.NULL)
            .put(
                "isDowngrade",
                latest != null && compareVersions(latest.version, currentVersion) < 0
            )
            .put(
                "apply",
                JSONObject()
                    .put("phase", s.phase.wire)
                    .put("error", s.error ?: JSONObject.NULL)
            )
    }

    /** Re-reads the manifest. Synchronous: the client asks, then reads status. */
    fun check(): JSONObject {
        if (state.get().phase.let { it == Phase.DOWNLOADING || it == Phase.AWAITING_INSTALL }) {
            return status()
        }
        val release = runCatching { fetchManifest() }.getOrNull()
        state.set(
            if (release == null) State(Phase.IDLE, "Couldn't reach the update server", null)
            else State(Phase.IDLE, null, release)
        )
        return status()
    }

    internal fun fetchManifest(): Release? {
        val body = get(manifestUrl) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return parseManifest(json)
    }

    internal fun parseManifest(json: JSONObject): Release? {
        val version = json.strOrNull("version") ?: return null
        val url = json.strOrNull("url") ?: return null
        // Only ever fetch the APK from the host the manifest itself came from.
        // A manifest that could point the installer anywhere is a manifest that
        // can be used to install anything.
        if (!url.startsWith("https://")) return null
        return Release(version, url, json.strOrNull("sha256"), json.strOrNull("notes"))
    }

    /**
     * Downloads the newest APK and hands it to the installer.
     *
     * Returns immediately; the caller polls [status]. One at a time — a second
     * tap while a download is running must not start a second one writing the
     * same file.
     */
    fun apply(runner: (Runnable) -> Unit): JSONObject {
        val release = state.get().latest ?: check().let { state.get().latest }
        if (release == null) {
            state.set(State(Phase.ERROR, "No update to install", null))
            return status()
        }
        if (!busy.compareAndSet(false, true)) return status()
        state.set(State(Phase.DOWNLOADING, null, release))
        runner(Runnable { download(release) })
        return status()
    }

    private fun download(release: Release) {
        try {
            downloadDir.mkdirs()
            // One file, replaced each time: a cache of every version ever
            // offered would grow without bound on a device that has no easy way
            // to clear it.
            downloadDir.listFiles()?.forEach { it.delete() }
            val target = File(downloadDir, "update.apk")

            val request = Request.Builder().url(release.url).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    fail("The download failed (HTTP ${response.code})")
                    return
                }
                val body = response.body ?: run { fail("The download was empty"); return }
                if (body.contentLength() > MAX_BYTES) {
                    fail("That download is far too large to be the app")
                    return
                }
                target.outputStream().use { out -> body.byteStream().copyTo(out) }
            }

            state.set(State(Phase.VERIFYING, null, release))
            if (release.sha256 != null) {
                val actual = sha256(target)
                if (!actual.equals(release.sha256, ignoreCase = true)) {
                    target.delete()
                    fail("The download didn't match its checksum")
                    return
                }
            }

            // Android takes it from here: the system installer asks the user,
            // and on confirmation this process is replaced. The banner keeps
            // polling and reloads when the server comes back on the new
            // version — or the user declines, and nothing happens.
            state.set(State(Phase.AWAITING_INSTALL, null, release))
            install(target)
        } catch (e: Exception) {
            Log.w(TAG, "update failed", e)
            fail(e.message ?: "The update failed")
        } finally {
            busy.set(false)
        }
    }

    private fun fail(message: String) {
        state.set(State(Phase.ERROR, message, state.get().latest))
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun get(url: String): String? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (e: Exception) {
        Log.d(TAG, "$url failed: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "Updater"

        /** Anything bigger than this is not our APK. */
        private const val MAX_BYTES = 200L * 1024 * 1024

        /**
         * Compares dotted versions numerically: 0.1.10 is newer than 0.1.9,
         * which a string compare gets backwards.
         */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.trim().split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val pb = b.trim().split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = (pa.getOrNull(i) ?: 0).compareTo(pb.getOrNull(i) ?: 0)
                if (d != 0) return d
            }
            return 0
        }
    }
}
