package com.musicd.lite.roon

import com.musicd.lite.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * Volume as reported by com.roonlabs.transport:2 for a single output.
 *
 * `type` is "number", "db" or "incremental". An incremental control has no
 * value, range or step at all — it is a pair of +/- buttons behind an IR
 * blaster, and the only legal request is a relative +1/-1.
 */
data class Volume(
    val type: String,
    val min: Double,
    val max: Double,
    val value: Double,
    val step: Double,
    val isMuted: Boolean,
    val softLimit: Double?
) {
    val isIncremental: Boolean get() = type == "incremental"

    /**
     * The highest value a control should be able to reach.
     *
     * Roon's soft limit is a ceiling the user set on the device precisely so a
     * remote cannot go past it, so a dial that swept to [max] would drive the
     * volume somewhere its owner had already said it must not go.
     */
    val effectiveMax: Double get() = softLimit?.coerceAtMost(max) ?: max

    /** Where the value sits between [min] and [effectiveMax], as 0f..1f. */
    val fraction: Float
        get() {
            if (isIncremental) return 0f
            val span = effectiveMax - min
            if (span <= 0.0) return 0f
            return ((value - min) / span).coerceIn(0.0, 1.0).toFloat()
        }

    /** How the value reads to a person, in whatever unit the device counts in. */
    fun format(): String = when {
        isMuted -> "muted"
        type == "db" -> String.format("%.1f dB", value)
        type == "incremental" -> "+/-"
        value == Math.floor(value) -> value.toInt().toString()
        else -> String.format("%.1f", value)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("value", value)
        .put("min", min)
        .put("max", max)
        .put("step", step)
        .put("soft_limit", softLimit ?: JSONObject.NULL)
        .put("type", type)

    companion object {
        fun parse(o: JSONObject?): Volume? {
            if (o == null) return null
            return Volume(
                type = o.str("type", "number"),
                min = o.optDouble("min", 0.0).orZero(),
                max = o.optDouble("max", 100.0).orZero(),
                value = o.optDouble("value", 0.0).orZero(),
                step = o.optDouble("step", 1.0).let { if (it.isNaN() || it == 0.0) 1.0 else it },
                isMuted = o.optBoolean("is_muted", false),
                softLimit = o.optDouble("soft_limit").takeIf { !it.isNaN() }
            )
        }

        private fun Double.orZero(): Double = if (isNaN()) 0.0 else this
    }
}

/**
 * One of an output's source controls: Roon's handle on the physical device
 * behind it — the amp or DAC that can be put into standby or switched to its
 * Roon input.
 */
data class SourceControl(
    val controlKey: String,
    val displayName: String,
    /** "selected" | "deselected" | "standby" | "indeterminate". */
    val status: String,
    val supportsStandby: Boolean
) {
    fun toJson(): JSONObject = JSONObject()
        .put("control_key", controlKey)
        .put("display_name", displayName)
        .put("status", status)
        .put("supports_standby", supportsStandby)

    companion object {
        private val KNOWN = setOf("selected", "deselected", "standby")

        /**
         * Only controls that can actually be addressed are kept. A control with
         * no `control_key` cannot be targeted individually and Roon's
         * toggle_standby is defined per control, so a keyless one would render
         * a power button that silently does nothing.
         */
        fun parseAll(arr: JSONArray?, fallbackName: String): List<SourceControl> {
            if (arr == null) return emptyList()
            val out = ArrayList<SourceControl>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.str("control_key").takeIf { it.isNotEmpty() } ?: continue
                val status = o.str("status")
                out += SourceControl(
                    controlKey = key,
                    displayName = o.str("display_name").takeIf { it.isNotEmpty() }
                        ?: fallbackName,
                    status = if (status in KNOWN) status else "indeterminate",
                    supportsStandby = o.optBoolean("supports_standby", false)
                )
            }
            return out
        }
    }
}

data class Output(
    val outputId: String,
    val zoneId: String?,
    val displayName: String,
    val volume: Volume?,
    /**
     * Roon's own answer to "what may this be grouped with". Null when the Core
     * doesn't send it, which clients read as "unknown, offer everything" rather
     * than "nothing is groupable".
     */
    val canGroupWith: List<String>?,
    val sourceControls: List<SourceControl>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("output_id", outputId)
        .put("zone_id", zoneId ?: JSONObject.NULL)
        .put("display_name", displayName)
        .put(
            "can_group_with_output_ids",
            canGroupWith?.let { ids -> JSONArray().also { a -> ids.forEach(a::put) } }
                ?: JSONObject.NULL
        )
        .put("source_controls", JSONArray().also { a -> sourceControls.forEach { a.put(it.toJson()) } })

    companion object {
        fun parse(o: JSONObject): Output {
            val name = o.str("display_name")
            val group = o.optJSONArray("can_group_with_output_ids")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.str(it).takeIf(String::isNotEmpty) }
            }
            return Output(
                outputId = o.str("output_id"),
                zoneId = o.str("zone_id").takeIf { it.isNotEmpty() },
                displayName = name,
                volume = Volume.parse(o.optJSONObject("volume")),
                canGroupWith = group,
                sourceControls = SourceControl.parseAll(o.optJSONArray("source_controls"), name)
            )
        }
    }
}

/**
 * Roon's per-zone playback modes, normalised so no caller has to cope with a
 * missing `settings` block (a zone that has never been played doesn't get one).
 * `loop` keeps Roon's own vocabulary; anything unrecognised reads as off rather
 * than being passed through — a value the UI can't render is worse than off.
 */
data class ZoneSettings(
    val shuffle: Boolean,
    val loop: String,
    val autoRadio: Boolean
) {
    fun toJson(): JSONObject = JSONObject()
        .put("shuffle", shuffle)
        .put("loop", loop)
        .put("auto_radio", autoRadio)

    companion object {
        val LOOP_MODES = listOf("disabled", "loop", "loop_one")

        fun parse(o: JSONObject?): ZoneSettings {
            val loop = o?.str("loop")
            return ZoneSettings(
                shuffle = o?.optBoolean("shuffle", false) ?: false,
                loop = if (loop == "loop" || loop == "loop_one") loop else "disabled",
                autoRadio = o?.optBoolean("auto_radio", false) ?: false
            )
        }
    }
}

data class NowPlaying(
    val line1: String,
    val line2: String,
    val line3: String,
    val lengthSeconds: Int?,
    val seekPosition: Int?,
    val imageKey: String?
) {
    companion object {
        fun parse(o: JSONObject?): NowPlaying? {
            if (o == null) return null
            val three = o.optJSONObject("three_line")
            val two = o.optJSONObject("two_line")
            val one = o.optJSONObject("one_line")
            val src = three ?: two ?: one
            return NowPlaying(
                line1 = src?.str("line1").orEmpty(),
                line2 = src?.str("line2").orEmpty(),
                line3 = src?.str("line3").orEmpty(),
                lengthSeconds = o.optInt("length", -1).takeIf { it >= 0 },
                seekPosition = o.optInt("seek_position", -1).takeIf { it >= 0 },
                imageKey = o.str("image_key").takeIf { it.isNotEmpty() }
            )
        }
    }
}

data class Zone(
    val zoneId: String,
    val displayName: String,
    val state: String,
    val isPlayAllowed: Boolean,
    val isPauseAllowed: Boolean,
    val isNextAllowed: Boolean,
    val isPreviousAllowed: Boolean,
    val isSeekAllowed: Boolean,
    val settings: ZoneSettings,
    val outputs: List<Output>,
    val nowPlaying: NowPlaying?
) {
    val isPlaying: Boolean get() = state == "playing"

    /**
     * Volume lives on outputs, not zones. A grouped zone has one output per
     * device, each with its own type, range and step.
     */
    val volumeOutputs: List<Output> get() = outputs.filter { it.volume != null }

    val primaryVolume: Volume? get() = volumeOutputs.firstOrNull()?.volume

    /**
     * Whether anything in this zone has a volume control at all.
     *
     * Plenty do not: a DAC fed at unity, or an output going into an amp with
     * its own knob, reports no volume object. Anything drawing a control has to
     * ask first — offering a slider or a pair of buttons that cannot move
     * anything is worse than showing none, because the user presses them and
     * concludes the remote is broken.
     */
    val hasVolumeControl: Boolean get() = volumeOutputs.isNotEmpty()

    companion object {
        fun parse(o: JSONObject): Zone {
            val outs = ArrayList<Output>()
            o.optJSONArray("outputs")?.let { arr ->
                for (i in 0 until arr.length()) outs += Output.parse(arr.getJSONObject(i))
            }
            return Zone(
                zoneId = o.str("zone_id"),
                displayName = o.str("display_name"),
                state = o.str("state", "stopped"),
                isPlayAllowed = o.optBoolean("is_play_allowed", false),
                isPauseAllowed = o.optBoolean("is_pause_allowed", false),
                isNextAllowed = o.optBoolean("is_next_allowed", false),
                isPreviousAllowed = o.optBoolean("is_previous_allowed", false),
                isSeekAllowed = o.optBoolean("is_seek_allowed", false),
                settings = ZoneSettings.parse(o.optJSONObject("settings")),
                outputs = outs,
                nowPlaying = NowPlaying.parse(o.optJSONObject("now_playing"))
            )
        }
    }
}

/**
 * Applies the subscribe_zones stream. The first message is "Subscribed" with
 * the full set; everything after is "Changed" with added/removed/changed
 * deltas plus a separate, much more frequent seek-position delta that must not
 * clobber anything else.
 */
class ZoneStore {
    /**
     * Guards [zones] and [revision], and is what a waiter blocks on.
     *
     * This feed is written by the MOO socket thread and read by every HTTP
     * worker, which was an unguarded LinkedHashMap across threads. The lock
     * was needed anyway; the waiting is what it was added for.
     */
    private val lock = Object()
    private val zones = LinkedHashMap<String, Zone>()
    private var revision = 0L

    /**
     * Bumped when something MATERIAL changes — a zone appears, disappears,
     * starts, stops, or moves to another track.
     *
     * Deliberately NOT bumped by a seek-position update. Roon sends one of
     * those roughly every second for every playing zone, so waking a waiting
     * client on them would turn a long poll straight back into a 1 Hz poll.
     * The page interpolates the progress bar between updates and resynchronises
     * whenever a wait times out, which is far more often than the drift matters.
     */
    val version: Long get() = synchronized(lock) { revision }

    /**
     * Blocks until [version] moves past [since], then returns it. Returns the
     * current version immediately if it has already moved, and after
     * [timeoutMs] if nothing happens.
     */
    fun awaitChange(since: Long, timeoutMs: Long): Long = synchronized(lock) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (revision <= since) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            // A spurious wakeup returns to the loop condition, not out of it.
            (lock as Object).wait(remaining)
        }
        revision
    }

    private fun bump() {
        revision++
        (lock as Object).notifyAll()
    }

    fun applySubscribed(body: JSONObject) = synchronized(lock) {
        zones.clear()
        body.optJSONArray("zones")?.let { arr ->
            for (i in 0 until arr.length()) {
                val z = Zone.parse(arr.getJSONObject(i))
                zones[z.zoneId] = z
            }
        }
        bump()
    }

    fun applyChanged(body: JSONObject) = synchronized(lock) {
        var material = false
        body.optJSONArray("zones_removed")?.let { arr ->
            for (i in 0 until arr.length()) zones.remove(arr.getString(i))
            if (arr.length() > 0) material = true
        }
        body.optJSONArray("zones_added")?.let { arr ->
            for (i in 0 until arr.length()) {
                val z = Zone.parse(arr.getJSONObject(i))
                zones[z.zoneId] = z
            }
            if (arr.length() > 0) material = true
        }
        body.optJSONArray("zones_changed")?.let { arr ->
            for (i in 0 until arr.length()) {
                val z = Zone.parse(arr.getJSONObject(i))
                zones[z.zoneId] = z
            }
            if (arr.length() > 0) material = true
        }
        body.optJSONArray("zones_seek_changed")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val zoneId = e.str("zone_id")
                val existing = zones[zoneId] ?: continue
                val np = existing.nowPlaying ?: continue
                val pos = e.optInt("seek_position", -1).takeIf { it >= 0 } ?: continue
                zones[zoneId] = existing.copy(nowPlaying = np.copy(seekPosition = pos))
            }
            // No bump: see [version]. A ticking clock is not news.
        }
        if (material) bump()
    }

    fun clear() = synchronized(lock) {
        zones.clear()
        bump()
    }

    fun all(): List<Zone> = synchronized(lock) {
        zones.values.sortedBy { it.displayName.lowercase() }
    }

    fun byId(id: String?): Zone? =
        if (id == null) null else synchronized(lock) { zones[id] }
}

/**
 * The outputs feed, which is a separate subscription from zones. It is what the
 * zone-grouping sheet needs: every output the Core knows about, including ones
 * that are not currently part of any zone the user is looking at.
 */
class OutputStore {
    private val outputs = LinkedHashMap<String, Output>()

    fun applySubscribed(body: JSONObject) {
        outputs.clear()
        body.optJSONArray("outputs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = Output.parse(arr.getJSONObject(i))
                outputs[o.outputId] = o
            }
        }
    }

    fun applyChanged(body: JSONObject) {
        body.optJSONArray("outputs_removed")?.let { arr ->
            for (i in 0 until arr.length()) outputs.remove(arr.getString(i))
        }
        for (key in listOf("outputs_added", "outputs_changed")) {
            body.optJSONArray(key)?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = Output.parse(arr.getJSONObject(i))
                    outputs[o.outputId] = o
                }
            }
        }
    }

    fun clear() = outputs.clear()

    fun all(): List<Output> = outputs.values.sortedBy { it.displayName.lowercase() }

    fun isEmpty(): Boolean = outputs.isEmpty()
}

/** One entry of a zone's play queue, as the UI's queue sheet renders it. */
data class QueueItem(
    val queueItemId: Long,
    val title: String,
    val subtitle: String,
    val imageKey: String?,
    val lengthSeconds: Int?
) {
    fun toJson(): JSONObject = JSONObject()
        .put("queue_item_id", queueItemId)
        .put("title", title)
        .put("subtitle", subtitle)
        .put("image_key", imageKey ?: JSONObject.NULL)
        .put("length", lengthSeconds ?: JSONObject.NULL)

    companion object {
        fun parse(o: JSONObject): QueueItem {
            val one = o.optJSONObject("one_line")
            val three = o.optJSONObject("three_line")
            return QueueItem(
                queueItemId = o.optLong("queue_item_id"),
                title = one?.str("line1").orEmpty().ifEmpty {
                    three?.str("line1").orEmpty()
                },
                subtitle = three?.str("line2").orEmpty(),
                imageKey = o.str("image_key").takeIf { it.isNotEmpty() },
                lengthSeconds = o.optInt("length", -1).takeIf { it >= 0 }
            )
        }
    }
}
