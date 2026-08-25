package com.musicd.lite.roon

import com.musicd.lite.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * One MOO session over a WebSocket to `ws://<core>:<port>/api`.
 *
 * Roon correlates the two directions with Request-Id. A plain call gets one
 * COMPLETE and is done; a subscription gets a first reply and then a stream of
 * CONTINUE messages on the *same* id until it is unsubscribed, which is why
 * handlers are registered per id and only removed on COMPLETE.
 *
 * Callers are the HTTP request threads, so the surface here is blocking with a
 * deadline rather than callback-based: a stuck Core must fail a single request,
 * not wedge the app.
 */
class MooSocket(
    private val http: OkHttpClient,
    private val url: String,
    private val events: Events
) {

    interface Events {
        fun onOpen()
        /** Terminal: the socket is gone and this object will not be reused. */
        fun onClosed(reason: String)
        /** A REQUEST the Core made of us, other than ping (already answered). */
        fun onCoreRequest(msg: Moo.Message) {}
    }

    /** Thrown when the Core does not answer, or answers with a failure name. */
    class MooException(message: String, val roonName: String? = null) : IOException(message)

    private companion object {
        const val TAG = "Moo"

        /**
         * A stuck-call backstop, not a performance budget: a slow-but-working
         * Core must not be broken by it. MusicD-Remote uses the same 90s.
         */
        const val CALL_TIMEOUT_MS = 90_000L
    }

    private val nextRequestId = AtomicInteger(0)
    private val handlers = ConcurrentHashMap<String, (Moo.Message?) -> Unit>()
    private val socket = AtomicReference<WebSocket?>(null)
    private val dead = AtomicBoolean(false)

    val isOpen: Boolean get() = socket.get() != null && !dead.get()

    fun connect() {
        val request = Request.Builder().url(url).build()
        socket.set(http.newWebSocket(request, Listener()))
    }

    fun close(reason: String = "closing") {
        if (dead.getAndSet(true)) return
        try {
            socket.getAndSet(null)?.close(1000, reason)
        } catch (e: Exception) {
            Log.d(TAG, "close failed: ${e.message}")
        }
        failAllPending()
    }

    // ------------------------------------------------------------------ send

    /**
     * Fire a request and register [onReply] for every response on its id. The
     * handler is called with null when the socket dies, so blocking callers
     * always wake up.
     *
     * @return the Request-Id, for a later unsubscribe.
     */
    fun send(
        service: String,
        method: String,
        body: JSONObject? = null,
        onReply: ((Moo.Message?) -> Unit)? = null
    ): Int {
        val ws = socket.get() ?: throw MooException("Not connected to a Roon Core")
        val id = nextRequestId.getAndIncrement()
        if (onReply != null) handlers[id.toString()] = onReply
        val payload = Moo.encode(
            Moo.VERB_REQUEST,
            "$service/$method",
            id,
            body?.toString()?.toByteArray(Charsets.UTF_8)
        )
        Log.d(TAG, "-> $service/$method id=$id ${body?.toString()?.take(200) ?: ""}")
        if (!ws.send(payload.toByteString(0, payload.size))) {
            handlers.remove(id.toString())
            throw MooException("Could not queue $service/$method — the socket is closing")
        }
        return id
    }

    /**
     * Send a request and block until the first response arrives.
     *
     * @param expect the Roon result name that means success ("Success",
     *               "Registered", "Subscribed", ...). Anything else is an
     *               error carrying Roon's own name, which is the most useful
     *               thing we can tell the user.
     */
    fun call(
        service: String,
        method: String,
        body: JSONObject? = null,
        expect: String? = "Success"
    ): Moo.Message {
        val latch = CountDownLatch(1)
        val reply = AtomicReference<Moo.Message?>(null)
        send(service, method, body) { msg ->
            if (reply.compareAndSet(null, msg)) latch.countDown()
        }
        if (!latch.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw MooException("Roon did not answer $service/$method in time")
        }
        val msg = reply.get() ?: throw MooException("Lost the connection during $service/$method")
        if (expect != null && msg.name != expect) {
            throw MooException(
                "Roon answered ${msg.name} to $service/$method" +
                    (msg.bodyText?.let { ": ${it.take(300)}" } ?: ""),
                msg.name
            )
        }
        return msg
    }

    /** Drop the handler for a request id (after unsubscribing). */
    fun forget(requestId: Int) {
        handlers.remove(requestId.toString())
    }

    // --------------------------------------------------------------- receive

    private fun handle(msg: Moo.Message) {
        if (msg.verb == Moo.VERB_REQUEST) {
            // The Core calls into the services we advertise. Ping is the one we
            // must answer or the Core drops us as unresponsive.
            if (msg.service == RoonServices.PING && msg.name == "ping") {
                reply(Moo.VERB_COMPLETE, "Success", msg.requestId)
            } else {
                events.onCoreRequest(msg)
                reply(Moo.VERB_COMPLETE, "InvalidRequest", msg.requestId)
            }
            return
        }

        val handler = handlers[msg.requestId]
        if (handler == null) {
            Log.d(TAG, "unmatched ${msg.verb} ${msg.name} id=${msg.requestId}")
            return
        }
        // A COMPLETE is the last word on that id; a CONTINUE keeps it open,
        // which is how every subscription streams.
        if (msg.verb == Moo.VERB_COMPLETE) handlers.remove(msg.requestId)
        try {
            handler(msg)
        } catch (e: Exception) {
            Log.w(TAG, "handler for ${msg.name} threw: ${e.message}", e)
        }
    }

    private fun reply(verb: String, name: String, requestId: String, body: JSONObject? = null) {
        val ws = socket.get() ?: return
        val id = requestId.toIntOrNull() ?: return
        val payload = Moo.encode(verb, name, id, body?.toString()?.toByteArray(Charsets.UTF_8))
        try {
            ws.send(payload.toByteString(0, payload.size))
        } catch (e: Exception) {
            Log.d(TAG, "reply failed: ${e.message}")
        }
    }

    /**
     * Wake every blocked caller with null. Without this a request in flight when
     * the Core goes away would sit on its latch for the full 90s deadline.
     */
    private fun failAllPending() {
        val snapshot = handlers.keys.toList()
        for (key in snapshot) {
            val h = handlers.remove(key) ?: continue
            try {
                h(null)
            } catch (e: Exception) {
                Log.d(TAG, "pending wake threw: ${e.message}")
            }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (socket.get() !== webSocket) return
            Log.i(TAG, "connected to $url")
            events.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (socket.get() !== webSocket) return
            Moo.parse(bytes.toByteArray())?.let(::handle)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket.get() !== webSocket) return
            Moo.parse(text.toByteArray(Charsets.UTF_8))?.let(::handle)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket.get() !== webSocket && !dead.get()) return
            Log.w(TAG, "socket failure: ${t.message}")
            terminate(t.message ?: "connection failed")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            terminate(reason.ifEmpty { "disconnected" })
        }

        private fun terminate(reason: String) {
            if (dead.getAndSet(true)) return
            socket.set(null)
            failAllPending()
            events.onClosed(reason)
        }
    }
}

/** Service names, in one place so a typo can't be spelled two different ways. */
object RoonServices {
    const val REGISTRY = "com.roonlabs.registry:1"
    const val TRANSPORT = "com.roonlabs.transport:2"
    const val BROWSE = "com.roonlabs.browse:1"
    const val IMAGE = "com.roonlabs.image:1"
    const val PING = "com.roonlabs.ping:1"
}
