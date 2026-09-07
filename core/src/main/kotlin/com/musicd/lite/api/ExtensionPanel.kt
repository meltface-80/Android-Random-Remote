package com.musicd.lite.api

import com.musicd.lite.MusicdLite
import com.musicd.lite.roon.ExtensionServices
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Roon draws behind the gear next to this extension's name.
 *
 * ONE SETTING IS HERE AND THE REST ARE NOT, and that is the whole design. The
 * app's settings live in the app, on the screen in your hand — putting them
 * here as well would be two places to change the same thing. What belongs on a
 * desktop is the setting that acts when nobody is holding the phone at all:
 * Random Album Radio, which puts another record on when a zone's queue runs
 * dry. That runs unattended, so "is it on for the kitchen?" is a question you
 * ask while looking at the kitchen zone in Roon, not while unlocking a phone.
 *
 * The layout is therefore built from the LIVE ZONE LIST at the moment Roon
 * asks: one Yes/No per zone. Roon has no multi-select widget, and a set of
 * zones is what this setting is — a row each is the shape that fits.
 *
 * Zone ids do not survive a Core restart, a regroup or a rename. That is
 * already true of the stored set this reads and writes, so nothing new is
 * broken here; a zone that has gone simply stops appearing as a row, and its
 * dead id is dropped on the next save rather than lingering forever.
 */
class ExtensionPanel(private val app: MusicdLite) : ExtensionServices.Panel {

    private companion object {
        /** Prefixed so a zone id can never collide with a future setting. */
        const val ZONE_PREFIX = "radio_zone."
    }

    override fun values(): JSONObject {
        val on = app.settings.radioZones()
        val values = JSONObject()
        for (zone in app.roon.zones()) {
            values.put(ZONE_PREFIX + zone.zoneId, zone.zoneId in on)
        }
        return values
    }

    override fun layout(values: JSONObject): JSONArray {
        val layout = JSONArray()
        layout.put(
            JSONObject()
                .put("type", "status")
                .put("title", summary())
        )

        val zones = app.roon.zones()
        if (zones.isEmpty()) {
            // A panel with no rows reads as broken. Say why there are none.
            layout.put(
                JSONObject()
                    .put("type", "status")
                    .put("title", "No zones are visible yet.")
            )
            return layout
        }

        val yesNo = JSONArray()
            .put(JSONObject().put("title", "On").put("value", true))
            .put(JSONObject().put("title", "Off").put("value", false))

        for (zone in zones) {
            layout.put(
                JSONObject()
                    .put("type", "dropdown")
                    .put("title", zone.displayName)
                    .put("subtitle", "Put another album on when this zone's queue runs out")
                    .put("values", JSONArray(yesNo.toString()))
                    .put("setting", ZONE_PREFIX + zone.zoneId)
            )
        }
        return layout
    }

    /**
     * Nothing a person can type, so nothing to reject. Kept explicit rather
     * than absent: a later text field here without a validator is the kind of
     * omission that only shows up as a saved empty string.
     */
    override fun validate(values: JSONObject): String? = null

    override fun save(values: JSONObject) {
        val on = LinkedHashSet<String>()
        for (key in values.keys()) {
            if (!key.startsWith(ZONE_PREFIX)) continue
            if (values.optBoolean(key, false)) on += key.removePrefix(ZONE_PREFIX)
        }
        app.settings.saveRadioZones(on)
    }

    /** The read-only line at the top: what this extension currently is. */
    private fun summary(): String {
        val albums = app.index.count
        val library = when {
            app.index.isBuilding -> "Reading the library…"
            albums > 0 -> "$albums albums"
            else -> "No library yet"
        }
        return "MusicD Remote Lite ${app.version} — $library"
    }
}
