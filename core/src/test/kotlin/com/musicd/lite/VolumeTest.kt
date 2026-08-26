package com.musicd.lite

import com.musicd.lite.roon.Volume
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maths a volume ring sweeps through.
 *
 * These live in :core rather than beside the view because they are the part
 * that can be wrong in a way no screenshot would show — a ring that looks
 * perfectly plausible while addressing the wrong end of the range.
 */
class VolumeTest {

    private fun volume(json: String): Volume = Volume.parse(JSONObject(json))!!

    @Test
    fun aSoftLimitIsTheCeilingNotTheMaximum() {
        // Roon's soft limit is a ceiling its owner set so a remote cannot go
        // past it. A ring that swept to max would drive the volume somewhere
        // they had already said it must not go.
        val v = volume("""{"type":"db","min":-80,"max":0,"value":-20,"step":0.5,"soft_limit":-10}""")
        assertEquals(-10.0, v.effectiveMax, 0.0001)
        assertEquals("full sweep ends at the soft limit", 1f, volume(
            """{"type":"db","min":-80,"max":0,"value":-10,"step":0.5,"soft_limit":-10}"""
        ).fraction, 0.0001f)
    }

    @Test
    fun aSoftLimitAboveTheMaximumCannotRaiseIt() {
        val v = volume("""{"type":"db","min":-80,"max":0,"value":-20,"step":0.5,"soft_limit":12}""")
        assertEquals(0.0, v.effectiveMax, 0.0001)
    }

    @Test
    fun theFractionSpansMinToMax() {
        val v = volume("""{"type":"number","min":0,"max":100,"value":25,"step":1}""")
        assertEquals(0.25f, v.fraction, 0.0001f)
    }

    @Test
    fun aValueOutsideTheRangeIsClamped() {
        // Roon has been seen reporting a value below min right after a device
        // reconnects. An unclamped fraction draws an arc going backwards.
        val low = volume("""{"type":"db","min":-80,"max":0,"value":-90,"step":0.5}""")
        val high = volume("""{"type":"db","min":-80,"max":0,"value":10,"step":0.5}""")
        assertEquals(0f, low.fraction, 0.0001f)
        assertEquals(1f, high.fraction, 0.0001f)
    }

    @Test
    fun aZeroWidthRangeDoesNotDivideByZero() {
        val v = volume("""{"type":"number","min":50,"max":50,"value":50,"step":1}""")
        assertTrue(v.fraction.isFinite())
        assertEquals(0f, v.fraction, 0.0001f)
    }

    @Test
    fun anIncrementalControlHasNoFractionToShow() {
        // No range is reported at all, so any fill would be invented.
        val v = volume("""{"type":"incremental","value":0,"step":1}""")
        assertEquals(0f, v.fraction, 0.0001f)
    }

    @Test
    fun formatSpeaksTheDevicesOwnUnits() {
        assertEquals("-32.5 dB", volume(
            """{"type":"db","min":-80,"max":0,"value":-32.5,"step":0.5}"""
        ).format())
        assertEquals("42", volume(
            """{"type":"number","min":0,"max":100,"value":42,"step":1}"""
        ).format())
        assertEquals("muted", volume(
            """{"type":"db","min":-80,"max":0,"value":-32.5,"step":0.5,"is_muted":true}"""
        ).format())
    }
}
