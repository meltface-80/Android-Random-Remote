package com.musicd.lite.api

import com.musicd.lite.str
import com.musicd.lite.strOrNull
import com.musicd.lite.store.Store
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's own preferences, stored as JSON documents in the [Store].
 *
 * MusicD-Remote keeps these in a settings file next to its database; there is
 * no reason for the shapes to differ, because the same front-end reads them.
 */
class Settings(private val store: Store) {

    companion object {
        /**
         * Home rows, in the order they are offered. Stored as a list of
         * {id, on} rather than a set of booleans plus a separate order, because
         * order and membership are one fact and splitting them is how they end
         * up contradicting each other.
         */
        /**
         * The Home rows this build can serve.
         *
         * "lotw" (Label of the week) is deliberately absent rather than listed
         * as unavailable. The settings screen renders its list from whatever
         * this returns, so a row kept here appears there — greyed out, with a
         * switch that cannot move and a note saying why. For a feature that is
         * never coming back in this build that is one more thing to read, not
         * information. The Home section itself is hidden in index.html, since
         * with the row gone from here nothing positions it.
         */
        val HOME_ROW_IDS = listOf("unplayed", "history", "picks", "random", "library", "genres")

        const val KEY_HOME_ROWS = "home_rows"
        const val KEY_SMART_PICKS = "smart_picks"
        const val KEY_RADIO_ZONES = "radio_zones"
        const val KEY_DISCOGS_TOKEN = "discogs_token"
        const val KEY_FANART_KEY = "fanart_key"
        const val KEY_LAST_ZONE = "last_zone"

        /**
         * Why every label route in this build answers the way it does.
         *
         * Record labels come from a scan of file tags on a mounted music
         * directory, resolved against five external metadata services. Neither
         * half is here, so the label browser, the label filter and the
         * label-of-the-week row are all out — see notInLite.
         */
        const val LABELS_UNAVAILABLE =
            "Record labels aren't part of the lite build — it has no music folder to read tags from."
    }

    private fun doc(key: String): JSONObject =
        store.setting(key)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    private fun save(key: String, value: JSONObject) = store.putSetting(key, value.toString())

    // ------------------------------------------------------------- home rows

    /**
     * The stored layout, repaired against the current vocabulary: unknown ids
     * dropped (a row removed by an update), missing ids appended and switched on
     * (a row ADDED by an update must appear, not silently stay hidden because an
     * old layout predates it).
     */
    fun homeRows(): List<Pair<String, Boolean>> {
        val stored = doc(KEY_HOME_ROWS).optJSONArray("rows")
        val out = ArrayList<Pair<String, Boolean>>()
        val seen = HashSet<String>()
        if (stored != null) {
            for (i in 0 until stored.length()) {
                val r = stored.optJSONObject(i) ?: continue
                val id = r.str("id").takeIf { it in HOME_ROW_IDS } ?: continue
                if (!seen.add(id)) continue
                out += id to r.optBoolean("on", true)
            }
        }
        for (id in HOME_ROW_IDS) if (id !in seen) out += id to true
        return out
    }

    fun saveHomeRows(rows: List<Pair<String, Boolean>>) {
        val arr = JSONArray()
        for ((id, on) in rows) arr.put(JSONObject().put("id", id).put("on", on))
        save(KEY_HOME_ROWS, JSONObject().put("rows", arr))
    }

    /**
     * A row whose FEATURE is off is not a layout choice, and the two must not be
     * confused: the user's stored `on` stays exactly as they left it while the
     * row is unavailable everywhere.
     */
    fun homeRowUnavailable(id: String): String? = when {
        id == "picks" && !smartPicksEnabled() -> "Smart Picks is off in Settings"
        else -> null
    }

    // ------------------------------------------------------------- last zone

    /**
     * The zone the user is currently looking at.
     *
     * Nothing needed this while the only client was the page, which keeps its
     * own selection. The notification and the media session are outside the
     * page and have to be told which zone they are about.
     */
    fun lastZone(): String? = doc(KEY_LAST_ZONE).strOrNull("zone")

    fun saveLastZone(zoneId: String) {
        if (zoneId.isBlank() || zoneId == lastZone()) return
        save(KEY_LAST_ZONE, JSONObject().put("zone", zoneId))
    }

    // ----------------------------------------------------------- smart picks

    fun smartPicksEnabled(): Boolean = doc(KEY_SMART_PICKS).optBoolean("enabled", true)
    fun smartPicksHour(): Int = doc(KEY_SMART_PICKS).optInt("hour", 7).coerceIn(0, 23)
    fun smartPicksAutoAdd(): Boolean = doc(KEY_SMART_PICKS).optBoolean("auto_add", false)

    fun saveSmartPicks(enabled: Boolean?, hour: Int?, autoAdd: Boolean?) {
        val d = doc(KEY_SMART_PICKS)
        if (enabled != null) d.put("enabled", enabled)
        if (hour != null && hour in 0..23) d.put("hour", hour)
        if (autoAdd != null) d.put("auto_add", autoAdd)
        save(KEY_SMART_PICKS, d)
    }

    // ----------------------------------------------------------------- radio

    fun radioZones(): Set<String> {
        val arr = doc(KEY_RADIO_ZONES).optJSONArray("zones") ?: return emptySet()
        return (0 until arr.length()).mapNotNull { arr.str(it).takeIf(String::isNotEmpty) }
            .toSet()
    }

    // ------------------------------------------------------- service tokens

    /**
     * The Discogs token and FanArt key, kept because they belong to the user
     * and not to a feature: they are typed once and must survive whatever the
     * app does or does not do with them yet.
     */
    fun secret(key: String): String? = doc(key).str("value").takeIf { it.isNotEmpty() }

    fun saveSecret(key: String, value: String) = save(key, JSONObject().put("value", value))

    fun clearSecret(key: String) = save(key, JSONObject())

    /** Never echo a secret back to the page — only enough to recognise it. */
    fun maskSecret(key: String): String =
        secret(key)?.let { "••••••••" + it.takeLast(4) } ?: ""

    fun saveRadioZones(zones: Set<String>) {
        save(KEY_RADIO_ZONES, JSONObject().put("zones", JSONArray().also { a -> zones.forEach(a::put) }))
    }
}
