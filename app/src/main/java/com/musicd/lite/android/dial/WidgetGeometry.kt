package com.musicd.lite.android.dial

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the dial's controls end up inside a widget of a given size.
 *
 * The widget draws the app's DialView to a square bitmap and shows it with
 * fitCenter, so the dial is centred and its side is the smaller of the two
 * dimensions. These are the same formulas DialView uses, applied to the
 * widget's real size so the invisible tap targets can be placed exactly over
 * the controls that were drawn.
 *
 * Doing this with a fixed grid does not work: the four controls occupy only
 * the middle 45% or so of the dial, so a grid of thirds or quarters puts every
 * one of them in the wrong cell.
 */
data class WidgetGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float
) {
    private val side = min(widthPx, heightPx).toFloat()
    private val cx = widthPx / 2f
    private val cy = heightPx / 2f

    private val radius = side / 2f - 8f * density
    private val ringWidth = radius * 0.115f
    private val innerRadius = radius - ringWidth - 10f * density

    /** Vertical centre of the four controls. */
    private val controlsY = cy + innerRadius * 0.58f
    private val spacing = innerRadius * 0.38f
    private val buttonRadius = innerRadius * 0.16f

    /** Padding that insets a four-cell row onto the four drawn controls. */
    fun controlsPadding(): Padding {
        // Four cells of `spacing` each, centred on the row of controls.
        val half = spacing * 2f
        // Generous vertically: a finger is bigger than the glyph.
        val halfHeight = buttonRadius * 1.7f
        return Padding(
            left = (cx - half).roundToInt().coerceAtLeast(0),
            top = (controlsY - halfHeight).roundToInt().coerceAtLeast(0),
            right = (widthPx - (cx + half)).roundToInt().coerceAtLeast(0),
            bottom = (heightPx - (controlsY + halfHeight)).roundToInt().coerceAtLeast(0)
        )
    }

    /** True when the dial is too small for the row to be worth showing. */
    fun isUsable(): Boolean = innerRadius > 8f * density

    data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int)
}
