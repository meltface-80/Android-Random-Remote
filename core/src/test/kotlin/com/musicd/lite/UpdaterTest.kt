package com.musicd.lite

import com.musicd.lite.meta.Updater
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of an update that are not Android.
 *
 * Downloading and installing need a device; deciding WHETHER to update, and
 * refusing a manifest that should not be trusted, do not — and those are the
 * parts that can be wrong without anyone noticing until an update either never
 * offers itself or offers the wrong thing.
 */
class UpdaterTest {

    private fun updater(current: String) = Updater(
        http = okhttp3.OkHttpClient(),
        currentVersion = current,
        manifestUrl = "https://example.invalid/latest.json",
        downloadDir = kotlin.io.path.createTempDirectory("updater-test").toFile(),
        install = { throw AssertionError("no install should happen in these tests") }
    )

    @Test
    fun versionsCompareNumericallyNotAsText() {
        // The one that bites: as strings, "0.1.10" sorts BEFORE "0.1.9", so a
        // tenth release would never offer itself as an update.
        assertTrue(Updater.compareVersions("0.1.10", "0.1.9") > 0)
        assertTrue(Updater.compareVersions("0.2.0", "0.1.99") > 0)
        assertTrue(Updater.compareVersions("1.0.0", "0.9.9") > 0)
        assertEquals(0, Updater.compareVersions("0.1.8", "0.1.8"))
        assertTrue(Updater.compareVersions("0.1.7", "0.1.8") < 0)
        // Shorter is not smaller when the tail is zeros.
        assertEquals(0, Updater.compareVersions("1.0", "1.0.0"))
    }

    @Test
    fun aManifestNeedsAVersionAndAnHttpsUrl() {
        val u = updater("0.1.8")
        assertNull(u.parseManifest(JSONObject("""{"url":"https://x/a.apk"}""")))
        assertNull(u.parseManifest(JSONObject("""{"version":"0.1.9"}""")))
        // An update is an APK this app will ask Android to install. A manifest
        // that could name a plain-HTTP URL is one an attacker on the network
        // could rewrite, so http:// is refused outright.
        assertNull(u.parseManifest(JSONObject("""{"version":"0.1.9","url":"http://x/a.apk"}""")))

        val ok = u.parseManifest(
            JSONObject("""{"version":"0.1.9","url":"https://x/a.apk","sha256":"ab","notes":"n"}""")
        )
        assertEquals("0.1.9", ok!!.version)
        assertEquals("ab", ok.sha256)
        assertEquals("n", ok.notes)
    }

    @Test
    fun statusIsTheShapeTheUpdateBannerReads() {
        // The banner reads available/current/latest/notes/isDowngrade and
        // apply.phase, and treats an unknown phase as "nothing happening".
        val s = updater("0.1.8").status()
        assertEquals("0.1.8", s.getString("current"))
        assertFalse(s.getBoolean("available"))
        assertTrue(s.isNull("latest"))
        assertEquals("idle", s.getJSONObject("apply").getString("phase"))
        assertTrue(s.getJSONObject("apply").isNull("error"))
    }

    /**
     * The phase names belong to the front-end, which maps exactly these to its
     * progress text. "extracting" is the verify step — inherited from the
     * Docker build, where it really did unpack a tarball. Renaming it to
     * something honest would leave the banner blank during that phase, so the
     * name stays and this test records why.
     */
    @Test
    fun thePhaseNamesAreTheOnesTheBannerUnderstands() {
        assertEquals(
            listOf("idle", "checking", "downloading", "extracting", "restarting", "error"),
            Updater.Phase.values().map { it.wire }
        )
    }
}
