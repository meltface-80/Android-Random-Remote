package com.musicd.lite.roon

import com.musicd.lite.str
import com.musicd.lite.Log
import com.musicd.lite.store.Store
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The app's whole relationship with a Roon Core: find it, pair with it, keep a
 * MOO session alive, and expose the transport and browse verbs the HTTP layer
 * calls.
 *
 * Lifecycle:
 *   discover -> ws://host:port/api -> registry:1/info -> registry:1/register
 *   -> (the user enables the extension in Roon, once) -> "Registered" + token
 *   -> transport:2/subscribe_zones + subscribe_outputs
 *
 * The token is persisted per Core id, so approval only ever happens once.
 */
class RoonCore(
    private val store: Store,
    private val extension: ExtensionInfo,
    /** Held while discovery runs; Android filters multicast out of userspace. */
    private val multicastLock: MulticastLock = MulticastLock.NONE
) : BrowseApi, RoonApi {

    /** How the extension introduces itself in Roon -> Settings -> Extensions. */
    data class ExtensionInfo(
        val id: String,
        val displayName: String,
        val version: String,
        val publisher: String,
        val email: String,
        val website: String
    )

    /** Android needs a WifiManager.MulticastLock for SOOD replies to arrive. */
    interface MulticastLock {
        fun acquire()
        fun release()

        companion object {
            val NONE = object : MulticastLock {
                override fun acquire() {}
                override fun release() {}
            }
        }
    }

    private companion object {
        const val TAG = "RoonCore"
        const val DISCOVERY_MS = 8_000L
        const val BACKOFF_START_MS = 1_000L
        const val BACKOFF_MAX_MS = 30_000L
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .build()

    private val net = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "roon-net").apply { isDaemon = true }
    }

    private val running = AtomicBoolean(false)

    /** Submit to the network thread, ignoring work queued after stop(). */
    private fun onNet(body: () -> Unit) {
        if (!running.get()) return
        runCatching { net.execute(body) }
    }

    private val socket = AtomicReference<MooSocket?>(null)
    private val sessionPool = BrowseSessionPool()

    private val zoneStore = ZoneStore()
    private val outputStore = OutputStore()

    @Volatile private var backoffMs = BACKOFF_START_MS
    @Volatile private var host: String? = null
    @Volatile private var port: Int = 0
    @Volatile private var coreId: String? = null
    @Volatile private var coreName: String? = null

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<RoonApi.Listener>()

    @Volatile
    private var currentStatus: RoonStatus = RoonStatus(RoonStage.IDLE)

    override val status: RoonStatus get() = currentStatus

    private fun publish(next: RoonStatus) {
        currentStatus = next
        Log.i(TAG, next.stage.name + ": " + (next.detail ?: ""))
        listeners.forEach { runCatching { it.onStatusChanged(next) } }
    }

    override val tree = BrowseTree(this)

    override val isPaired: Boolean get() = status.stage == RoonStage.PAIRED && socket.get()?.isOpen == true
    val currentHost: String? get() = host
    val currentPort: Int get() = port
    val currentCoreId: String? get() = coreId
    val currentCoreName: String? get() = coreName

    override fun addListener(listener: RoonApi.Listener) {
        listeners.add(listener)
    }

    override fun zones(): List<Zone> = zoneStore.all()
    override fun zone(id: String?): Zone? = zoneStore.byId(id)

    /**
     * Every output the Core knows about. Falls back to the outputs carried by
     * the zone feed so this still answers on a Core that never sent an outputs
     * subscription.
     */
    override fun outputs(): List<Output> =
        if (!outputStore.isEmpty()) outputStore.all()
        else zoneStore.all().flatMap { it.outputs }.distinctBy { it.outputId }

    // ---------------------------------------------------------------- control

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        onNet { connectOrDiscover() }
    }

    override fun stop() {
        running.set(false)
        socket.getAndSet(null)?.close("stopping")
        net.shutdownNow()
    }

    /** Forget the saved Core address and discover from scratch. */
    fun rediscover() {
        onNet {
            store.forgetLastCore()
            host = null
            port = 0
            socket.getAndSet(null)?.close("rediscover")
            backoffMs = BACKOFF_START_MS
            connectOrDiscover()
        }
    }

    /** Connect to a manually entered Core address. */
    fun connectTo(hostName: String, portNumber: Int) {
        onNet {
            socket.getAndSet(null)?.close("manual reconnect")
            host = hostName
            port = portNumber
            store.saveLastCore(hostName, portNumber)
            backoffMs = BACKOFF_START_MS
            openSocket(hostName, portNumber)
        }
    }

    private fun connectOrDiscover() {
        if (!running.get()) return
        val saved = store.lastCore()
        if (saved != null && saved.second > 0) {
            host = saved.first
            port = saved.second
            openSocket(saved.first, saved.second)
            return
        }
        discover()
    }

    private fun discover() {
        if (!running.get()) return
        publish(RoonStatus(RoonStage.DISCOVERING, detail = "Looking for a Roon Core on this network"))
        var found = false
        multicastLock.acquire()
        try {
            Sood.discover(DISCOVERY_MS, fun(core: Sood.Found) {
                if (found) return
                found = true
                host = core.host
                port = core.port
                coreName = core.displayName
                store.saveLastCore(core.host, core.port)
                onNet { openSocket(core.host, core.port) }
            })
        } catch (e: Exception) {
            Log.w(TAG, "discovery failed", e)
        } finally {
            multicastLock.release()
        }
        if (!found && running.get()) {
            publish(
                RoonStatus(
                    RoonStage.ERROR,
                    detail = "No Roon Core found. Check you're on the same Wi-Fi as the Core, " +
                        "or enter its address by hand."
                )
            )
            net.schedule({ connectOrDiscover() }, 10, TimeUnit.SECONDS)
        }
    }

    private fun openSocket(h: String, p: Int) {
        if (!running.get()) return
        publish(RoonStatus(RoonStage.CONNECTING, coreId, coreName, "Connecting to $h:$p"))
        val ws = MooSocket(http, "ws://$h:$p/api", SocketEvents())
        socket.set(ws)
        ws.connect()
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
        net.schedule({
            if (!running.get()) return@schedule
            val h = host
            if (h != null && port > 0) openSocket(h, port) else connectOrDiscover()
        }, delay, TimeUnit.MILLISECONDS)
    }

    // ------------------------------------------------------------ pairing

    private inner class SocketEvents : MooSocket.Events {
        override fun onOpen() {
            onNet { register() }
        }

        override fun onClosed(reason: String) {
            onNet {
                zoneStore.clear()
                outputStore.clear()
                tree.clearOffsetCache()
                publish(RoonStatus(RoonStage.ERROR, coreId, coreName, "Lost the connection: $reason"))
                listeners.forEach { runCatching { it.onDisconnected() } }
                scheduleReconnect()
            }
        }
    }

    private fun register() {
        val ws = socket.get() ?: return
        backoffMs = BACKOFF_START_MS
        try {
            val info = ws.call(RoonServices.REGISTRY, "info", expect = null)
            info.bodyText?.let { JSONObject(it) }?.let { body ->
                coreId = body.str("core_id").takeIf { it.isNotEmpty() } ?: coreId
                coreName = body.str("display_name").takeIf { it.isNotEmpty() } ?: coreName
            }

            publish(
                RoonStatus(
                    RoonStage.AWAITING_APPROVAL, coreId, coreName,
                    "Enable “${extension.displayName}” in Roon → Settings → Extensions"
                )
            )

            val reginfo = JSONObject()
                .put("extension_id", extension.id)
                .put("display_name", extension.displayName)
                .put("display_version", extension.version)
                .put("publisher", extension.publisher)
                .put("email", extension.email)
                .put("website", extension.website)
                .put(
                    "required_services",
                    JSONArray().put(RoonServices.TRANSPORT).put(RoonServices.BROWSE)
                )
                .put("optional_services", JSONArray().put(RoonServices.IMAGE))
                .put("provided_services", JSONArray().put(RoonServices.PING))
            coreId?.let { id -> store.tokenFor(id)?.let { reginfo.put("token", it) } }

            // Registration is not a one-shot reply: Roon answers "Registered"
            // only once the user has enabled the extension, which on a first
            // pair can be minutes. Everything after that arrives on the same id.
            val registered = ws.call(RoonServices.REGISTRY, "register", reginfo, expect = "Registered")
            val body = registered.bodyText?.let { JSONObject(it) }
            val id = body?.str("core_id")?.takeIf { it.isNotEmpty() } ?: coreId
            coreId = id
            coreName = body?.str("display_name")?.takeIf { it.isNotEmpty() } ?: coreName
            body?.str("token")?.takeIf { it.isNotEmpty() }?.let { token ->
                if (id != null) store.saveToken(id, token)
            }

            subscribeZones()
            subscribeOutputs()
            publish(RoonStatus(RoonStage.PAIRED, coreId, coreName, "Paired with ${coreName ?: "Roon"}"))
            listeners.forEach { runCatching { it.onPaired() } }
        } catch (e: Exception) {
            Log.w(TAG, "registration failed: ${e.message}")
            publish(RoonStatus(RoonStage.ERROR, coreId, coreName, e.message ?: "Registration failed"))
            // The socket listener drives the reconnect when the socket itself
            // died. If it is still open, this was a refusal — back off and retry.
            if (socket.get()?.isOpen == true) {
                socket.getAndSet(null)?.close("registration failed")
            }
        }
    }

    private fun subscribeZones() {
        val ws = socket.get() ?: return
        ws.send(RoonServices.TRANSPORT, "subscribe_zones", JSONObject().put("subscription_key", 0)) { msg ->
            val json = msg?.bodyText?.let { JSONObject(it) } ?: return@send
            when (msg.name) {
                "Subscribed" -> zoneStore.applySubscribed(json)
                "Changed" -> zoneStore.applyChanged(json)
                "Unsubscribed" -> zoneStore.clear()
                else -> return@send
            }
            val all = zoneStore.all()
            listeners.forEach { runCatching { it.onZonesChanged(all) } }
        }
    }

    private fun subscribeOutputs() {
        val ws = socket.get() ?: return
        ws.send(RoonServices.TRANSPORT, "subscribe_outputs", JSONObject().put("subscription_key", 1)) { msg ->
            val json = msg?.bodyText?.let { JSONObject(it) } ?: return@send
            when (msg.name) {
                "Subscribed" -> outputStore.applySubscribed(json)
                "Changed" -> outputStore.applyChanged(json)
                "Unsubscribed" -> outputStore.clear()
            }
        }
    }

    // ---------------------------------------------------------- transport

    private fun ws(): MooSocket =
        socket.get()?.takeIf { it.isOpen }
            ?: throw MooSocket.MooException("Not paired with a Roon Core")

    override fun control(zoneOrOutputId: String, command: String) {
        ws().call(
            RoonServices.TRANSPORT, "control",
            JSONObject().put("zone_or_output_id", zoneOrOutputId).put("control", command)
        )
    }

    override fun seek(zoneOrOutputId: String, how: String, seconds: Int) {
        ws().call(
            RoonServices.TRANSPORT, "seek",
            JSONObject().put("zone_or_output_id", zoneOrOutputId).put("how", how).put("seconds", seconds)
        )
    }

    override fun changeVolume(outputId: String, how: String, value: Double) {
        ws().call(
            RoonServices.TRANSPORT, "change_volume",
            JSONObject().put("output_id", outputId).put("how", how).put("value", value)
        )
    }

    override fun mute(outputId: String, how: String) {
        ws().call(
            RoonServices.TRANSPORT, "mute",
            JSONObject().put("output_id", outputId).put("how", how)
        )
    }

    override fun changeSettings(zoneOrOutputId: String, patch: JSONObject) {
        ws().call(
            RoonServices.TRANSPORT, "change_settings",
            patch.put("zone_or_output_id", zoneOrOutputId)
        )
    }

    override fun standby(outputId: String, controlKey: String) {
        ws().call(
            RoonServices.TRANSPORT, "standby",
            JSONObject().put("output_id", outputId).put("control_key", controlKey)
        )
    }

    override fun convenienceSwitch(outputId: String, controlKey: String) {
        ws().call(
            RoonServices.TRANSPORT, "convenience_switch",
            JSONObject().put("output_id", outputId).put("control_key", controlKey)
        )
    }

    override fun groupOutputs(outputIds: List<String>) {
        ws().call(
            RoonServices.TRANSPORT, "group_outputs",
            JSONObject().put("output_ids", JSONArray().also { a -> outputIds.forEach(a::put) })
        )
    }

    override fun ungroupOutputs(outputIds: List<String>) {
        ws().call(
            RoonServices.TRANSPORT, "ungroup_outputs",
            JSONObject().put("output_ids", JSONArray().also { a -> outputIds.forEach(a::put) })
        )
    }

    override fun transferZone(fromZoneOrOutputId: String, toZoneOrOutputId: String) {
        ws().call(
            RoonServices.TRANSPORT, "transfer_zone",
            JSONObject().put("from_zone_or_output_id", fromZoneOrOutputId)
                .put("to_zone_or_output_id", toZoneOrOutputId)
        )
    }

    override fun playFromHere(zoneOrOutputId: String, queueItemId: Long) {
        ws().call(
            RoonServices.TRANSPORT, "play_from_here",
            JSONObject().put("zone_or_output_id", zoneOrOutputId).put("queue_item_id", queueItemId)
        )
    }

    override fun pauseAll() {
        ws().call(RoonServices.TRANSPORT, "pause_all", JSONObject())
    }

    /**
     * One snapshot of a zone's queue.
     *
     * The queue is only available as a subscription, so this subscribes, takes
     * the first payload and unsubscribes again. Leaving it open would mean a
     * live queue feed per client for a sheet that is usually shut within
     * seconds.
     */
    override fun queue(zoneId: String, count: Int, timeoutMs: Long): List<QueueItem> {
        val ws = ws()
        val latch = CountDownLatch(1)
        val result = AtomicReference<List<QueueItem>?>(null)
        val failure = AtomicReference<String?>(null)
        val subscriptionKey = 100

        val requestId = ws.send(
            RoonServices.TRANSPORT, "subscribe_queue",
            JSONObject().put("subscription_key", subscriptionKey).put("max_item_count", count)
        ) { msg ->
            if (msg == null) {
                failure.compareAndSet(null, "Lost the connection")
                latch.countDown()
                return@send
            }
            when (msg.name) {
                "Subscribed" -> {
                    val body = msg.bodyText?.let { JSONObject(it) }
                    val arr = body?.optJSONArray("items")
                    val items = if (arr == null) emptyList() else
                        (0 until arr.length()).mapNotNull {
                            arr.optJSONObject(it)?.let(QueueItem::parse)
                        }
                    result.compareAndSet(null, items)
                    latch.countDown()
                }
                "Changed", "Unsubscribed" -> Unit
                else -> {
                    failure.compareAndSet(null, msg.name)
                    latch.countDown()
                }
            }
        }

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw MooSocket.MooException("The queue subscription timed out")
            }
            failure.get()?.let { throw MooSocket.MooException("Roon answered $it to subscribe_queue") }
            return result.get() ?: emptyList()
        } finally {
            ws.forget(requestId)
            runCatching {
                ws.send(
                    RoonServices.TRANSPORT, "unsubscribe_queue",
                    JSONObject().put("subscription_key", subscriptionKey)
                )
            }
        }
    }

    /**
     * Album art. The image service is also reachable over plain HTTP on the
     * same host and port, which lets any image loader fetch it directly.
     */
    override fun imageUrl(imageKey: String, width: Int, height: Int, scale: String): String? {
        val h = host ?: return null
        if (port <= 0) return null
        return "http://$h:$port/api/image/$imageKey" +
            "?scale=$scale&width=$width&height=$height&format=image/jpeg"
    }

    // ------------------------------------------------------------- BrowseApi

    override fun browse(opts: JSONObject): JSONObject {
        val reply = ws().call(RoonServices.BROWSE, "browse", opts, expect = "Success")
        return reply.bodyText?.let { JSONObject(it) }
            ?: throw BrowseException("Roon returned an empty browse response")
    }

    override fun load(opts: JSONObject): JSONObject {
        val reply = ws().call(RoonServices.BROWSE, "load", opts, expect = "Success")
        return reply.bodyText?.let { JSONObject(it) }
            ?: throw BrowseException("Roon returned an empty load response")
    }

    override fun <T> withSession(fn: (String) -> T): T {
        val key = sessionPool.acquire()
        try {
            return fn(key)
        } finally {
            sessionPool.release(key)
        }
    }
}
