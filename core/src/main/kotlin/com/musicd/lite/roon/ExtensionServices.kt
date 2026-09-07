package com.musicd.lite.roon

import org.json.JSONArray
import org.json.JSONObject

/**
 * The two services this extension PROVIDES to the Core, rather than consumes.
 *
 * Every other Roon call in this app goes one way: we ask, the Core answers. A
 * provided service is the other direction — the Core makes requests of US, and
 * an extension that advertises one and then does not answer it is worse than
 * one that never advertised it. Ping was the only one, and it is answered in
 * [MooSocket] because dropping it gets the extension disconnected.
 *
 * These two are why the app appeared in Roon → Settings → Extensions as a bare
 * name with nothing beside it:
 *
 *   - **status** is the line of text under the extension's name. It is the only
 *     place the phone can say "I am connected, and here is what to" on a screen
 *     that is NOT the phone — which is exactly the screen you are looking at
 *     when the phone is the thing that has stopped working.
 *   - **settings** is the panel behind the gear. Roon renders it from a layout
 *     we send; there is no HTML and no callback into our UI.
 *
 * ALL OF IT IS SOCKET-FREE ON PURPOSE. A request comes in as three strings and
 * a body, and an [Outcome] goes back — so every reply shape in here is asserted
 * in a plain JVM test rather than by watching Roon's UI and guessing. The
 * shapes themselves are taken from RoonLabs' own node-roon-api-status and
 * node-roon-api-settings, not from documentation.
 */
class ExtensionServices {

    /**
     * What the settings panel shows and what happens when it is saved.
     *
     * An interface rather than a direct reference to the app's own settings
     * because this package must not depend on the HTTP layer, and because a
     * test needs to be able to hand over a panel with no store behind it.
     */
    interface Panel {
        /** Current values, keyed by the `setting` name of each widget. */
        fun values(): JSONObject

        /** The widgets Roon draws, in order. */
        fun layout(values: JSONObject): JSONArray

        /**
         * Null when [values] are acceptable; otherwise the reason, which Roon
         * shows in the panel and which blocks the save.
         */
        fun validate(values: JSONObject): String?

        /** Apply. Only ever called with values that [validate] accepted. */
        fun save(values: JSONObject)
    }

    /** One response to one Core request. */
    data class Reply(val verb: String, val name: String, val body: JSONObject? = null)

    /** A CONTINUE pushed to an existing subscription, on that request's id. */
    data class Push(val requestId: String, val name: String, val body: JSONObject)

    /** A [Reply] to the request, plus anything it caused to be pushed. */
    data class Outcome(val reply: Reply, val pushes: List<Push> = emptyList())

    private val statusSubscribers = LinkedHashSet<String>()
    private val settingsSubscribers = LinkedHashSet<String>()

    private var message: String = ""
    private var isError: Boolean = false

    /**
     * Supplied by the host before the Core is contacted, because whether the
     * settings service is advertised AT ALL is decided at registration — a host
     * with nothing to configure must not claim a panel it cannot draw.
     *
     * Held rather than taken in the constructor so there is no order to get
     * wrong: this object exists from the moment the socket does, and a panel
     * that arrives later simply starts being used.
     */
    @Volatile
    var panel: Panel? = null

    /**
     * Answer one request from the Core, or null when it is for a service this
     * does not provide — the caller then says InvalidRequest, which is what
     * Roon expects for a method it should not have called.
     */
    @Synchronized
    fun onRequest(service: String, name: String, requestId: String, body: JSONObject?): Outcome? =
        when (service) {
            RoonServices.STATUS -> status(name, requestId)
            RoonServices.SETTINGS -> settings(name, requestId, body)
            else -> null
        }

    /**
     * The new status line, and the pushes that tell every subscriber about it.
     *
     * Returns an empty list when the text has not actually changed. Roon's own
     * status service pushes unconditionally, but this is driven by a connection
     * state machine that republishes the same stage repeatedly, and a Changed
     * per reconnection attempt is noise on the wire and in the Core's log.
     */
    @Synchronized
    fun setStatus(message: String, isError: Boolean): List<Push> {
        if (message == this.message && isError == this.isError) return emptyList()
        this.message = message
        this.isError = isError
        return statusSubscribers.map { Push(it, "Changed", statusBody()) }
    }

    /** The socket is gone; the subscriptions on it went with it. */
    @Synchronized
    fun onDisconnected() {
        statusSubscribers.clear()
        settingsSubscribers.clear()
    }

    // ---------------------------------------------------------------- status

    private fun status(name: String, requestId: String): Outcome = when (name) {
        "subscribe_status" -> {
            statusSubscribers.add(requestId)
            Outcome(Reply(Moo.VERB_CONTINUE, "Subscribed", statusBody()))
        }
        "unsubscribe_status" -> {
            statusSubscribers.remove(requestId)
            Outcome(Reply(Moo.VERB_COMPLETE, "Unsubscribed"))
        }
        "get_status" -> Outcome(Reply(Moo.VERB_COMPLETE, "Success", statusBody()))
        else -> Outcome(Reply(Moo.VERB_COMPLETE, "InvalidRequest"))
    }

    private fun statusBody(): JSONObject =
        JSONObject().put("message", message).put("is_error", isError)

    // -------------------------------------------------------------- settings

    private fun settings(name: String, requestId: String, body: JSONObject?): Outcome {
        // Not advertised without a panel, so this is the Core asking for
        // something we never offered. Refusing beats drawing an empty screen.
        val panel = this.panel ?: return Outcome(Reply(Moo.VERB_COMPLETE, "InvalidRequest"))
        return when (name) {
            "subscribe_settings" -> {
                settingsSubscribers.add(requestId)
                Outcome(Reply(Moo.VERB_CONTINUE, "Subscribed", settingsBody(panel, panel.values())))
            }
            "unsubscribe_settings" -> {
                settingsSubscribers.remove(requestId)
                Outcome(Reply(Moo.VERB_COMPLETE, "Unsubscribed"))
            }
            "get_settings" ->
                Outcome(Reply(Moo.VERB_COMPLETE, "Success", settingsBody(panel, panel.values())))
            "save_settings" -> save(panel, requestId, body)
            else -> Outcome(Reply(Moo.VERB_COMPLETE, "InvalidRequest"))
        }
    }

    /**
     * A save arrives twice: once as a dry run while the user is still typing,
     * and again for real when they press Save. Both are answered with the whole
     * panel — Roon redraws from the reply, so returning nothing on a rejection
     * would blank the settings screen rather than show the reason.
     *
     * Only the real save applies anything, and only a real save that changed
     * something pushes Changed to the OTHER subscribers — the one that asked is
     * being answered directly and does not want to be told twice.
     */
    private fun save(panel: Panel, requestId: String, body: JSONObject?): Outcome {
        val submitted = body?.optJSONObject("settings")?.optJSONObject("values") ?: JSONObject()
        val dryRun = body?.optBoolean("is_dry_run", false) ?: false

        val error = panel.validate(submitted)
        if (error != null) {
            return Outcome(Reply(Moo.VERB_COMPLETE, "NotValid", settingsBody(panel, submitted, error)))
        }
        if (dryRun) {
            return Outcome(Reply(Moo.VERB_COMPLETE, "Success", settingsBody(panel, submitted)))
        }

        panel.save(submitted)
        val saved = panel.values()
        return Outcome(
            Reply(Moo.VERB_COMPLETE, "Success", settingsBody(panel, saved)),
            settingsSubscribers.filter { it != requestId }
                .map { Push(it, "Changed", settingsBody(panel, saved)) }
        )
    }

    /**
     * `{ settings: { values, layout, has_error } }` — the shape Roon renders.
     *
     * [error] is drawn as a `status` widget appended to the layout, which is how
     * every extension that does this reports a bad value, and `has_error` is
     * what actually stops the save.
     */
    private fun settingsBody(panel: Panel, values: JSONObject, error: String? = null): JSONObject {
        val layout = panel.layout(values)
        if (error != null) {
            layout.put(JSONObject().put("type", "status").put("title", error))
        }
        return JSONObject().put(
            "settings",
            JSONObject()
                .put("values", values)
                .put("layout", layout)
                .put("has_error", error != null)
        )
    }
}
