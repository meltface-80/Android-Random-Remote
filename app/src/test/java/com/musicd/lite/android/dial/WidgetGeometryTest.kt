package com.musicd.lite.android.dial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

/**
 * The tap targets have to sit on the controls the dial actually drew.
 *
 * These recompute DialView's own geometry independently and check the cells
 * land on it, because the failure is silent and horrible: the widget looks
 * right and does the wrong thing.
 */
class WidgetGeometryTest {

    private val density = 3f

    /** DialView's control positions, derived from its own constants. */
    private fun drawnControlCentres(width: Int, height: Int): List<Float> {
        val side = min(width, height).toFloat()
        val radius = side / 2f - 8f * density
        val ringWidth = radius * 0.115f
        val innerRadius = radius - ringWidth - 10f * density
        val cx = width / 2f
        val spacing = innerRadius * 0.38f
        return listOf(
            cx - spacing * 1.5f,
            cx - spacing * 0.5f,
            cx + spacing * 0.5f,
            cx + spacing * 1.5f
        )
    }

    /** Centres of the four weighted cells inside the padded row. */
    private fun cellCentres(geometry: WidgetGeometry, width: Int): List<Float> {
        val padding = geometry.controlsPadding()
        val content = width - padding.left - padding.right
        val cell = content / 4f
        return (0 until 4).map { padding.left + cell * (it + 0.5f) }
    }

    @Test
    fun everyCellSitsOnTheControlItTriggers() {
        val width = 900
        val height = 900
        val geometry = WidgetGeometry(width, height, density)

        val drawn = drawnControlCentres(width, height)
        val cells = cellCentres(geometry, width)

        for (i in 0 until 4) {
            assertEquals("control $i", drawn[i], cells[i], 2f)
        }
    }

    @Test
    fun theRowStraddlesTheControlsVertically() {
        val height = 900
        val geometry = WidgetGeometry(900, height, density)
        val padding = geometry.controlsPadding()

        val side = 900f
        val radius = side / 2f - 8f * density
        val innerRadius = radius - radius * 0.115f - 10f * density
        val controlsY = height / 2f + innerRadius * 0.58f

        assertTrue("row starts above the controls", padding.top < controlsY)
        assertTrue("row ends below the controls", height - padding.bottom > controlsY)
    }

    @Test
    fun aGridOfQuartersWouldPutTheMicrophoneOnTheWrongControl() {
        // The bug this replaced. Four controls occupy the middle of the dial,
        // so quarter-width cells land nowhere near them: tapping the drawn
        // microphone fell in the "next" cell and skipped a track.
        val width = 900
        val drawn = drawnControlCentres(width, width)
        val naiveCellForMic = (drawn[3] / (width / 4f)).toInt()
        assertEquals("the microphone falls in the third quarter, which is 'next'", 2, naiveCellForMic)
    }

    @Test
    fun holdsUpOnAWideWidget() {
        // fitCenter letterboxes a square dial into a wide widget, so the dial
        // is smaller and centred; the cells must follow it in.
        val width = 1200
        val height = 600
        val geometry = WidgetGeometry(width, height, density)
        val drawn = drawnControlCentres(width, height)
        val cells = cellCentres(geometry, width)
        for (i in 0 until 4) {
            assertEquals("control $i on a wide widget", drawn[i], cells[i], 2f)
        }
    }

    @Test
    fun paddingNeverGoesNegative() {
        val geometry = WidgetGeometry(120, 120, density)
        val padding = geometry.controlsPadding()
        assertTrue(padding.left >= 0 && padding.top >= 0)
        assertTrue(padding.right >= 0 && padding.bottom >= 0)
    }

    @Test
    fun tinyWidgetsAreNotWorthPlacing() {
        assertFalse(WidgetGeometry(60, 60, density).isUsable())
        assertTrue(WidgetGeometry(900, 900, density).isUsable())
    }
}
