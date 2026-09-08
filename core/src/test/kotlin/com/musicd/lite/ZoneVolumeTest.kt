package com.musicd.lite

import com.musicd.lite.roon.Volume
import com.musicd.lite.roon.Zone
import com.musicd.lite.roon.ZoneVolume
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Roon's volume, as Android's whole numbers.
 *
 * "Hey Google, turn it up" reached the phone's own volume rather than the zone
 * because the media session never offered one, and offering one means handing
 * Android a maximum and a position as integers. Roon deals in doubles with a
 * minimum, a step and sometimes a soft limit, so the conversion is arithmetic
 * with corners — and the corners are what is tested here, because the loud
 * failure mode is a rocker that jumps a room from quiet to full scale.
 */
class ZoneVolumeTest {

    private fun db(
        value: Double = -30.0,
        min: Double = -80.0,
        max: Double = 0.0,
        step: Double = 0.5,
        softLimit: Double? = null
    ) = Volume(
        type = "db", min = min, max = max, value = value, step = step,
        isMuted = false, softLimit = softLimit
    )

    private fun zoneWith(json: String): Zone = Zone.parse(JSONObject(json))

    @Test
    fun aTypicalDacBecomesItsOwnNumberOfSteps() {
        // -80..0 in halves is 160 steps, and -30 is 100 of them up.
        val v = db()
        assertEquals(160, ZoneVolume.stepCount(v))
        assertEquals(100, ZoneVolume.positionOf(v))
    }

    @Test
    fun aStepMapsBackToTheValueRoonWants() {
        val v = db()
        assertEquals(-80.0, ZoneVolume.valueAt(v, 0), 0.001)
        assertEquals(-30.0, ZoneVolume.valueAt(v, 100), 0.001)
        assertEquals(0.0, ZoneVolume.valueAt(v, 160), 0.001)
    }

    /**
     * Somebody who capped a zone did it to stop the thing being turned up, and
     * the hardware rocker is exactly what they were guarding against.
     */
    @Test
    fun aSoftLimitCapsTheHardwareKeysToo() {
        val v = db(softLimit = -20.0)
        assertEquals("-80..-20 in halves", 120, ZoneVolume.stepCount(v))
        // Asking beyond the top of the scale lands on the limit, not the max.
        assertEquals(-20.0, ZoneVolume.valueAt(v, 999), 0.001)
    }

    @Test
    fun aPositionOutsideTheRangeIsClampedRatherThanWrapped() {
        val v = db()
        assertEquals(-80.0, ZoneVolume.valueAt(v, -50), 0.001)
        assertEquals(0.0, ZoneVolume.valueAt(v, 10_000), 0.001)
    }

    /**
     * A degenerate range would otherwise divide by zero or hand Android a
     * maximum of nought, which it treats as a broken control.
     */
    @Test
    fun aZeroSpanOrZeroStepStillGivesAUsableScale() {
        assertTrue(ZoneVolume.stepCount(db(min = 0.0, max = 0.0)) >= 1)
        assertTrue(ZoneVolume.stepCount(db(step = 0.0)) >= 1)
        assertEquals(0, ZoneVolume.positionOf(db(step = 0.0)))
    }

    /**
     * An `incremental` output takes "one louder" and does not know where it is.
     * Claiming an absolute scale would draw a slider whose position is fiction.
     */
    @Test
    fun anIncrementalOutputIsRelativeOnly() {
        val zone = zoneWith(
            """
            {"zone_id":"z1","display_name":"Amp","state":"playing",
             "outputs":[{"output_id":"o1","display_name":"Amp",
               "volume":{"type":"incremental","min":0,"max":0,"value":0,"step":1,
                         "is_muted":false}}]}
            """.trimIndent()
        )
        val scale = ZoneVolume.scaleOf(zone)!!
        assertTrue("an incremental output cannot be placed on a scale", scale.relativeOnly)
        assertTrue(scale.steps > 0)
    }

    @Test
    fun aZoneWithARealControlIsDescribedAbsolutely() {
        val zone = zoneWith(
            """
            {"zone_id":"z1","display_name":"Study","state":"playing",
             "outputs":[{"output_id":"o1","display_name":"Study",
               "volume":{"type":"db","min":-80,"max":0,"value":-30,"step":0.5,
                         "is_muted":false}}]}
            """.trimIndent()
        )
        val scale = ZoneVolume.scaleOf(zone)!!
        assertEquals(false, scale.relativeOnly)
        assertEquals(160, scale.steps)
        assertEquals(100, scale.position)
    }

    /**
     * A fixed output reports no volume at all, and null is the honest answer:
     * the caller hands volume back to the phone rather than pretending to a
     * control that does not exist.
     */
    @Test
    fun aZoneWithNoVolumeControlHasNoScale() {
        val zone = zoneWith(
            """
            {"zone_id":"z1","display_name":"DAC","state":"playing",
             "outputs":[{"output_id":"o1","display_name":"DAC"}]}
            """.trimIndent()
        )
        assertNull(ZoneVolume.scaleOf(zone))
        assertNull(ZoneVolume.scaleOf(null))
    }
}
