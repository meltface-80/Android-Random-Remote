package com.musicd.lite.roon

enum class RoonStage { IDLE, DISCOVERING, CONNECTING, AWAITING_APPROVAL, PAIRED, ERROR }

data class RoonStatus(
    val stage: RoonStage,
    val coreId: String? = null,
    val coreName: String? = null,
    val detail: String? = null
)

/**
 * Everything the HTTP layer asks of a Roon Core.
 *
 * [RoonCore] is the real one. Splitting the surface out means the whole API —
 * routing, JSON shapes, the stale-offset ladder, playback — can be exercised
 * end to end against a scripted Core, on a plain JVM, with no Roon on the
 * network and no Android SDK in the build.
 */
interface RoonApi {

    /** Called on the network thread whenever the Core's state moves. */
    interface Listener {
        fun onZonesChanged(zones: List<Zone>) {}
        fun onStatusChanged(status: RoonStatus) {}
        /** Paired and able to walk the library. */
        fun onPaired() {}
        fun onDisconnected() {}
    }

    /**
     * Lifecycle, defaulted to nothing so a scripted Core in a test is simply
     * always up and never needs to model discovery or reconnection.
     */
    fun start() {}
    fun stop() {}
    fun addListener(listener: Listener) {}

    val isPaired: Boolean
    val status: RoonStatus

    /** Navigation and paging over the browse tree. */
    val tree: BrowseTree

    fun zones(): List<Zone>
    fun zone(id: String?): Zone?
    fun outputs(): List<Output>
    fun queue(zoneId: String, count: Int = 100, timeoutMs: Long = 5_000): List<QueueItem>

    /**
     * A counter that moves when the zone feed changes materially.
     *
     * Roon pushes zone changes to this app the instant they happen, but the
     * front-end used to ask over HTTP every 1.5 seconds regardless. These two
     * let it wait instead: hold the revision it last saw, ask to be told when
     * that number moves, and get an answer the moment Roon says something —
     * or when the wait times out, whichever comes first.
     */
    val zoneRevision: Long

    /** Blocks until [zoneRevision] passes [since], or [timeoutMs] elapses. */
    fun awaitZoneChange(since: Long, timeoutMs: Long): Long

    fun control(zoneOrOutputId: String, command: String)
    fun seek(zoneOrOutputId: String, how: String, seconds: Int)
    fun changeVolume(outputId: String, how: String, value: Double)
    fun mute(outputId: String, how: String)
    fun changeSettings(zoneOrOutputId: String, patch: org.json.JSONObject)
    fun standby(outputId: String, controlKey: String)
    fun convenienceSwitch(outputId: String, controlKey: String)
    fun groupOutputs(outputIds: List<String>)
    fun ungroupOutputs(outputIds: List<String>)
    fun transferZone(fromZoneOrOutputId: String, toZoneOrOutputId: String)
    fun playFromHere(zoneOrOutputId: String, queueItemId: Long)
    fun pauseAll()

    /**
     * A direct HTTP URL for album art on the Core, or null when unpaired. The
     * image service listens on the same host and port as the extension API, so
     * art never has to travel over the MOO socket that browse and transport
     * share.
     */
    fun imageUrl(imageKey: String, width: Int, height: Int, scale: String = "fit"): String?
}
