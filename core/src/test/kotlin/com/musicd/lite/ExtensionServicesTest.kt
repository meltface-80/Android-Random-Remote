package com.musicd.lite

import com.musicd.lite.roon.ExtensionServices
import com.musicd.lite.roon.Moo
import com.musicd.lite.roon.RoonServices
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two services this extension answers, rather than asks.
 *
 * Worth testing at this level precisely because nothing else can see it: these
 * replies go to Roon over a socket, and the only other way to know a shape is
 * wrong is that the Extensions screen looks empty and does not say why. The
 * expectations here are read off RoonLabs' own node-roon-api-status and
 * node-roon-api-settings, so a mistake is a mistake about their code rather
 * than about a paraphrase of it.
 */
class ExtensionServicesTest {

    /** A panel that records what was asked of it. */
    private class FakePanel : ExtensionServices.Panel {
        var stored = JSONObject().put("radio_zone.z1", false)
        var rejectWith: String? = null
        var saves = 0

        override fun values(): JSONObject = JSONObject(stored.toString())

        override fun layout(values: JSONObject): JSONArray = JSONArray().put(
            JSONObject()
                .put("type", "dropdown")
                .put("title", "Study")
                .put("setting", "radio_zone.z1")
        )

        override fun validate(values: JSONObject): String? = rejectWith

        override fun save(values: JSONObject) {
            saves++
            stored = JSONObject(values.toString())
        }
    }

    private val panel = FakePanel()
    private val services = ExtensionServices().apply { panel = this@ExtensionServicesTest.panel }

    private fun status(name: String, id: String = "1", body: JSONObject? = null) =
        services.onRequest(RoonServices.STATUS, name, id, body)!!

    private fun settings(name: String, id: String = "1", body: JSONObject? = null) =
        services.onRequest(RoonServices.SETTINGS, name, id, body)!!

    // ---------------------------------------------------------------- status

    @Test
    fun subscribingToStatusIsAnsweredWithTheLineAsItStands() {
        services.setStatus("Paired with Fake Core", false)
        val out = status("subscribe_status", "7")

        // CONTINUE, not COMPLETE: the id stays open so Changed can be pushed
        // down it. Answering COMPLETE would close the subscription instantly
        // and the line would never update again.
        assertEquals(Moo.VERB_CONTINUE, out.reply.verb)
        assertEquals("Subscribed", out.reply.name)
        assertEquals("Paired with Fake Core", out.reply.body!!.getString("message"))
        assertEquals(false, out.reply.body!!.getBoolean("is_error"))
    }

    @Test
    fun getStatusIsAnsweredAndClosed() {
        services.setStatus("Looking for a Roon Core", false)
        val out = status("get_status")
        assertEquals(Moo.VERB_COMPLETE, out.reply.verb)
        assertEquals("Success", out.reply.name)
        assertEquals("Looking for a Roon Core", out.reply.body!!.getString("message"))
    }

    @Test
    fun aNewStatusIsPushedToEverySubscriber() {
        status("subscribe_status", "3")
        status("subscribe_status", "9")

        val pushes = services.setStatus("Lost the connection", true)

        assertEquals(listOf("3", "9"), pushes.map { it.requestId })
        assertTrue(pushes.all { it.name == "Changed" })
        assertEquals("Lost the connection", pushes[0].body.getString("message"))
        assertTrue("an error stage must set is_error", pushes[0].body.getBoolean("is_error"))
    }

    /**
     * The connection state machine republishes the same stage on every
     * reconnection attempt. Pushing an identical line each time is a Changed
     * per retry on the wire and in the Core's log, saying nothing.
     */
    @Test
    fun repeatingTheSameStatusPushesNothing() {
        status("subscribe_status", "3")
        assertEquals(1, services.setStatus("Connecting to 10.0.0.2:9330", false).size)
        assertEquals(0, services.setStatus("Connecting to 10.0.0.2:9330", false).size)
        assertEquals(1, services.setStatus("Paired with Fake Core", false).size)
    }

    @Test
    fun unsubscribingStopsThePushes() {
        status("subscribe_status", "3")
        val out = status("unsubscribe_status", "3")
        assertEquals(Moo.VERB_COMPLETE, out.reply.verb)
        assertEquals("Unsubscribed", out.reply.name)
        assertEquals(0, services.setStatus("anything", false).size)
    }

    @Test
    fun aLostSocketTakesEverySubscriptionWithIt() {
        status("subscribe_status", "3")
        settings("subscribe_settings", "4")
        services.onDisconnected()
        assertEquals(0, services.setStatus("reconnected", false).size)
        // And a save no longer tries to push down a dead id.
        val out = settings("save_settings", "9", saveBody(dryRun = false))
        assertEquals(0, out.pushes.size)
    }

    // -------------------------------------------------------------- settings

    @Test
    fun settingsAreOfferedAsValuesLayoutAndHasError() {
        val out = settings("subscribe_settings", "5")
        assertEquals(Moo.VERB_CONTINUE, out.reply.verb)
        assertEquals("Subscribed", out.reply.name)

        val s = out.reply.body!!.getJSONObject("settings")
        assertTrue(s.has("values"))
        assertTrue(s.has("layout"))
        assertEquals(false, s.getBoolean("has_error"))
        assertEquals("radio_zone.z1", s.getJSONArray("layout").getJSONObject(0).getString("setting"))
    }

    /**
     * Roon sends a dry run while the panel is still open and the user is still
     * changing things. Applying one would save half-finished edits.
     */
    @Test
    fun aDryRunIsValidatedButNotSaved() {
        val out = settings("save_settings", "5", saveBody(dryRun = true))
        assertEquals("Success", out.reply.name)
        assertEquals(0, panel.saves)
    }

    @Test
    fun aRealSaveAppliesAndTellsTheOtherSubscribers() {
        settings("subscribe_settings", "5")
        settings("subscribe_settings", "6")

        val out = settings("save_settings", "5", saveBody(dryRun = false))

        assertEquals("Success", out.reply.name)
        assertEquals(1, panel.saves)
        assertEquals(true, panel.stored.getBoolean("radio_zone.z1"))

        // The one that asked is answered directly; telling it again through its
        // subscription would be the same news twice.
        assertEquals(listOf("6"), out.pushes.map { it.requestId })
        assertEquals("Changed", out.pushes[0].name)
    }

    @Test
    fun aRejectedSaveComesBackNotValidWithTheReasonAndChangesNothing() {
        panel.rejectWith = "That zone is gone"
        val out = settings("save_settings", "5", saveBody(dryRun = false))

        assertEquals("NotValid", out.reply.name)
        assertEquals(0, panel.saves)

        val s = out.reply.body!!.getJSONObject("settings")
        assertTrue("has_error is what actually blocks the save", s.getBoolean("has_error"))
        val last = s.getJSONArray("layout").let { it.getJSONObject(it.length() - 1) }
        assertEquals("status", last.getString("type"))
        assertEquals("That zone is gone", last.getString("title"))
    }

    // ----------------------------------------------------------------- other

    /**
     * A host with nothing to configure does not advertise the settings service,
     * so this is the Core asking for something never offered. Refusing says so;
     * answering with an empty layout would draw a blank screen behind the gear
     * and look like a bug in the panel.
     */
    @Test
    fun settingsWithNoPanelBehindThemAreRefused() {
        val bare = ExtensionServices()
        val out = bare.onRequest(RoonServices.SETTINGS, "subscribe_settings", "1", null)!!
        assertEquals(Moo.VERB_COMPLETE, out.reply.verb)
        assertEquals("InvalidRequest", out.reply.name)
    }

    @Test
    fun aServiceWeDoNotProvideIsNotOursToAnswer() {
        assertNull(services.onRequest("com.roonlabs.transport:2", "control", "1", null))
    }

    @Test
    fun anUnknownMethodOnOurOwnServiceIsRefused() {
        val out = services.onRequest(RoonServices.STATUS, "set_status", "1", null)!!
        assertEquals(Moo.VERB_COMPLETE, out.reply.verb)
        assertEquals("InvalidRequest", out.reply.name)
    }

    /** The body Roon sends: `{is_dry_run, settings: {values}}`. */
    private fun saveBody(dryRun: Boolean): JSONObject = JSONObject()
        .put("is_dry_run", dryRun)
        .put(
            "settings",
            JSONObject().put("values", JSONObject().put("radio_zone.z1", true))
        )
}
