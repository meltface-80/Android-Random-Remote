package com.musicd.lite.roon

/**
 * A Roon zone's volume, as a whole number of steps.
 *
 * Android's volume plumbing — the hardware rocker, the system slider, and "Hey
 * Google, turn it up" — speaks in integers: a maximum, a current position, and
 * a direction. Roon speaks in doubles with a per-output type, minimum, maximum
 * and step, where a typical DAC is -80.0 to 0.0 in halves and a fixed output
 * has no volume at all.
 *
 * This is the conversion, and it is here rather than in the Android class that
 * needs it because it is arithmetic with edge cases — a zero span, a soft
 * limit below the maximum, an output that only knows "up a bit" — and
 * arithmetic with edge cases is the part worth testing.
 */
object ZoneVolume {

    /**
     * What Android should be told about this zone.
     *
     * [steps] is the maximum, [position] where the zone sits now, and
     * [relativeOnly] marks an output that can only be nudged: some devices
     * report `incremental`, meaning they take "one louder" and have no idea
     * what number they are at. Android has a mode for exactly that, and
     * claiming an absolute scale for one would put a slider on screen whose
     * position is a fiction.
     */
    data class Scale(val steps: Int, val position: Int, val relativeOnly: Boolean)

    /**
     * Never fewer than this many steps on an absolute control.
     *
     * A zone whose range and step imply one or two positions would give the
     * system slider two places to be. The real granularity is Roon's step, so
     * a floor here only affects arithmetic that was degenerate anyway.
     */
    private const val MIN_STEPS = 1

    /**
     * How Android should see [zone], or null when there is nothing to control.
     *
     * Null is a real answer and not a failure: a DAC fed at unity or an output
     * going into an amp with its own knob reports no volume object at all, and
     * the caller should hand volume back to the phone rather than pretend.
     */
    fun scaleOf(zone: Zone?): Scale? {
        val volume = zone?.primaryVolume ?: return null
        if (volume.isIncremental) {
            // The number is meaningless, so any max will do; it is never shown.
            return Scale(steps = 100, position = 0, relativeOnly = true)
        }
        val steps = stepCount(volume)
        return Scale(steps = steps, position = positionOf(volume, steps), relativeOnly = false)
    }

    /** How many whole steps lie between the minimum and the usable maximum. */
    fun stepCount(volume: Volume): Int {
        val span = volume.effectiveMax - volume.min
        if (span <= 0.0 || volume.step <= 0.0) return MIN_STEPS
        return Math.round(span / volume.step).toInt().coerceAtLeast(MIN_STEPS)
    }

    /** Where the zone currently sits on that scale, clamped into range. */
    fun positionOf(volume: Volume, steps: Int = stepCount(volume)): Int {
        if (volume.step <= 0.0) return 0
        val offset = (volume.value - volume.min) / volume.step
        return Math.round(offset).toInt().coerceIn(0, steps)
    }

    /**
     * The Roon value for a step Android asked for.
     *
     * Clamped to the usable maximum rather than the reported one, so a soft
     * limit set on the zone is honoured by the hardware keys exactly as it is
     * by the app's own slider. Somebody who has capped a zone at -20dB did it
     * to stop the thing being turned up, and the rocker is precisely what they
     * were guarding against.
     */
    fun valueAt(volume: Volume, position: Int): Double {
        val steps = stepCount(volume)
        val clamped = position.coerceIn(0, steps)
        val value = volume.min + clamped * volume.step
        return value.coerceIn(volume.min, volume.effectiveMax)
    }
}
