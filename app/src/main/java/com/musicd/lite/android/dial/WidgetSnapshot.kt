package com.musicd.lite.android.dial

import android.content.Context
import com.musicd.lite.roon.Zone

/**
 * The little of a zone that a widget shows.
 *
 * Kept as its own value so two things are easy: telling whether a zone update
 * actually changed anything visible (Roon sends a seek update every second,
 * and re-rendering for those would be pure waste), and drawing something
 * sensible on a cold widget whose app process died hours ago.
 */
data class WidgetSnapshot(
    val zoneName: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
    val isMuted: Boolean = false,
    val volumeLabel: String = "",
    val volumeFraction: Float = -1f,
    val imageKey: String = "",
    val hasZone: Boolean = false
) {
    companion object {
        private const val PREFS = "roon_widget"

        val EMPTY = WidgetSnapshot()

        fun of(zone: Zone?): WidgetSnapshot {
            if (zone == null) return EMPTY
            val volume = zone.primaryVolume
            return WidgetSnapshot(
                zoneName = zone.displayName,
                title = zone.nowPlaying?.line1.orEmpty(),
                artist = zone.nowPlaying?.line2.orEmpty(),
                album = zone.nowPlaying?.line3.orEmpty(),
                isPlaying = zone.isPlaying,
                isMuted = volume?.isMuted ?: false,
                volumeLabel = when {
                    volume == null -> ""
                    volume.isMuted -> "muted"
                    else -> volume.format()
                },
                volumeFraction = volume?.takeIf { !it.isIncremental }?.fraction ?: -1f,
                imageKey = zone.nowPlaying?.imageKey.orEmpty(),
                hasZone = true
            )
        }

        fun load(context: Context): WidgetSnapshot {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean("has_zone", false)) return EMPTY
            return WidgetSnapshot(
                zoneName = prefs.getString("zone_name", "").orEmpty(),
                title = prefs.getString("title", "").orEmpty(),
                artist = prefs.getString("artist", "").orEmpty(),
                album = prefs.getString("album", "").orEmpty(),
                isPlaying = prefs.getBoolean("is_playing", false),
                isMuted = prefs.getBoolean("is_muted", false),
                volumeLabel = prefs.getString("volume_label", "").orEmpty(),
                volumeFraction = prefs.getFloat("volume_fraction", -1f),
                imageKey = prefs.getString("image_key", "").orEmpty(),
                hasZone = true
            )
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("zone_name", zoneName)
            .putString("title", title)
            .putString("artist", artist)
            .putString("album", album)
            .putBoolean("is_playing", isPlaying)
            .putBoolean("is_muted", isMuted)
            .putString("volume_label", volumeLabel)
            .putFloat("volume_fraction", volumeFraction)
            .putString("image_key", imageKey)
            .putBoolean("has_zone", hasZone)
            .apply()
    }
}
