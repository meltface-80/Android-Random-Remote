package com.musicd.lite.android.dial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.musicd.lite.roon.Zone
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A round control surface: a volume ring you sweep with your thumb, album art
 * in the middle, transport under it.
 *
 * The ring is the interesting part. A hardware knob has detents, so the ring
 * quantises to the output's own `step` and fires one haptic tick per step
 * rather than streaming a continuous value. Sweeping the full range takes
 * [DEGREES_FOR_FULL_RANGE] of rotation regardless of whether the device counts
 * in dB or in arbitrary units.
 */
class DialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** Rotation needed to travel from minimum to maximum volume. */
        private const val DEGREES_FOR_FULL_RANGE = 320.0

        /** Rotation per +/-1 on an "incremental" control, which has no range. */
        private const val DEGREES_PER_INCREMENT = 14.0

        /** Volume requests are coalesced into one send per interval. */
        private const val SEND_INTERVAL_MS = 60L

        /** How long the local value wins over the Core's echo after a gesture. */
        private const val OPTIMISTIC_WINDOW_MS = 900L

        private const val BG = 0xFF07080A.toInt()
        private const val RING_TRACK = 0xFF1C222A.toInt()
        private const val RING_FILL = 0xFF7AC8FF.toInt()
        private const val RING_MUTED = 0xFF5A6675.toInt()
        private const val THUMB = 0xFFEAF4FF.toInt()
        private const val TEXT_PRIMARY = 0xFFF2F5F8.toInt()
        private const val TEXT_SECONDARY = 0xFF98A3AF.toInt()
        private const val ART_PLACEHOLDER = 0xFF141A21.toInt()
        private const val PROGRESS = 0xFF3C77A8.toInt()
    }

    /**
     * Draws for a home-screen widget rather than the app.
     *
     * A widget is a bitmap behind tap targets: there is no gesture to follow,
     * so the ring gains small +/- marks to show that volume is tappable, and
     * the second-by-second progress arc is dropped because redrawing and
     * re-sending the whole image every second is not what a widget is for. The
     * background is left transparent so the widget's own rounded corners show.
     */
    var widgetMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    interface Callbacks {
        fun onVolumeSteps(steps: Int)
        fun onPlayPause()
        fun onNext()
        fun onPrevious()
        fun onZoneTapped()
        fun onMuteTapped()
        fun onVoiceTapped()
        fun onLongPress()
    }

    var callbacks: Callbacks? = null

    private var zone: Zone? = null
    private var statusText: String = "Starting…"
    private var artwork: Bitmap? = null

    /**
     * What the microphone is doing. While it is anything but idle the dial
     * shows it instead of what is playing, because that is what the user is
     * looking at the screen for.
     */
    sealed class Voice {
        object Idle : Voice()
        data class Listening(val heard: String) : Voice()
        data class Working(val query: String) : Voice()
        data class Said(val message: String) : Voice()
    }

    var voice: Voice = Voice.Idle
        set(value) {
            field = value
            invalidate()
        }

    // Geometry, recomputed on layout.
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var ringWidth = 0f
    private var innerRadius = 0f

    private val ringRect = RectF()
    private val progressRect = RectF()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    // Gesture state.
    private enum class Mode { NONE, RING, INNER }
    private var mode = Mode.NONE
    private var lastAngle = 0.0
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var longPressFired = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** Fractional volume carried between quantised steps. */
    private var residual = 0.0
    private var residualDegrees = 0.0

    /** Locally applied value, shown until the Core's echo catches up. */
    private var optimisticValue: Double? = null
    private var lastGestureAt = 0L

    private var pendingSteps = 0
    private var lastSendAt = 0L

    private val handler = Handler(Looper.getMainLooper())

    private val sendRunnable = Runnable { flushSteps() }
    private val longPressRunnable = Runnable {
        if (mode == Mode.INNER && !moved) {
            longPressFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            callbacks?.onLongPress()
        }
    }

    fun setZone(newZone: Zone?) {
        zone = newZone
        if (System.currentTimeMillis() - lastGestureAt > OPTIMISTIC_WINDOW_MS) {
            optimisticValue = null
        }
        invalidate()
    }

    fun setStatus(text: String) {
        statusText = text
        invalidate()
    }

    fun setArtwork(bitmap: Bitmap?) {
        artwork = bitmap
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - dp(8f)
        ringWidth = radius * 0.115f
        innerRadius = radius - ringWidth - dp(10f)
        val ringMid = radius - ringWidth / 2f
        ringRect.set(cx - ringMid, cy - ringMid, cx + ringMid, cy + ringMid)
        val pr = innerRadius + dp(5f)
        progressRect.set(cx - pr, cy - pr, cx + pr, cy + pr)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    // ------------------------------------------------------------- rendering

    override fun onDraw(canvas: Canvas) {
        if (!widgetMode) canvas.drawColor(BG)
        val z = zone

        drawRing(canvas, z)
        if (!widgetMode) drawProgress(canvas, z)
        drawArtwork(canvas)
        drawText(canvas, z)
        drawTransport(canvas, z)
        if (widgetMode) drawVolumeButtons(canvas, z)
    }

    /**
     * The widget's volume control.
     *
     * On the home screen the ring cannot be swept — a drag there belongs to the
     * launcher, and reaching for one gets you the notification shade instead —
     * so volume is two buttons. They are drawn the size of the transport
     * controls, because that is what they are: the only way to change volume
     * from the widget, not a hint about one.
     */
    private fun drawVolumeButtons(canvas: Canvas, z: Zone?) {
        if (z?.hasVolumeControl != true) return

        val r = radius - ringWidth / 2f
        val buttonRadius = innerRadius * 0.17f
        val border = dp(2f)

        for (degrees in intArrayOf(180, 0)) {
            val radians = Math.toRadians(degrees.toDouble())
            val x = (cx + r * Math.cos(radians)).toFloat()
            val y = (cy + r * Math.sin(radians)).toFloat()

            paint.style = Paint.Style.FILL
            paint.color = 0xF20E141C.toInt()
            canvas.drawCircle(x, y, buttonRadius, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = border
            paint.color = if (z.primaryVolume?.isMuted == true) RING_MUTED else RING_FILL
            canvas.drawCircle(x, y, buttonRadius - border / 2f, paint)

            paint.style = Paint.Style.FILL
            paint.color = TEXT_PRIMARY
            val arm = buttonRadius * 0.44f
            val thickness = buttonRadius * 0.125f
            canvas.drawRect(x - arm, y - thickness, x + arm, y + thickness, paint)
            if (degrees == 0) {
                canvas.drawRect(x - thickness, y - arm, x + thickness, y + arm, paint)
            }
        }
    }

    private fun displayedVolume(): Double? {
        val vol = zone?.primaryVolume ?: return null
        if (vol.isIncremental) return null
        return optimisticValue ?: vol.value
    }

    private fun displayedFraction(): Float {
        val vol = zone?.primaryVolume ?: return 0f
        if (vol.isIncremental) return 0f
        val value = optimisticValue ?: vol.value
        val span = vol.effectiveMax - vol.min
        if (span <= 0.0) return 0f
        return (((value - vol.min) / span).coerceIn(0.0, 1.0)).toFloat()
    }

    private fun drawRing(canvas: Canvas, z: Zone?) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = ringWidth
        paint.color = RING_TRACK
        canvas.drawCircle(cx, cy, radius - ringWidth / 2f, paint)

        val vol = z?.primaryVolume
        if (vol == null) {
            // Fixed-volume output, or nothing connected: no level to show.
            return
        }

        if (vol.isIncremental) {
            // No range is reported, so there is nothing to fill. Show detents
            // to signal that the ring still works as a +/- control.
            paint.style = Paint.Style.FILL
            paint.color = if (vol.isMuted) RING_MUTED else RING_FILL
            var deg = -90.0
            while (deg < 270.0) {
                val rad = Math.toRadians(deg)
                val r = radius - ringWidth / 2f
                canvas.drawCircle(
                    (cx + r * Math.cos(rad)).toFloat(),
                    (cy + r * Math.sin(rad)).toFloat(),
                    ringWidth * 0.12f,
                    paint
                )
                deg += 12.0
            }
            return
        }

        val fraction = displayedFraction()
        paint.style = Paint.Style.STROKE
        paint.color = if (vol.isMuted) RING_MUTED else RING_FILL
        canvas.drawArc(ringRect, -90f, 360f * fraction, false, paint)

        // Thumb at the current position.
        val angle = Math.toRadians((-90f + 360f * fraction).toDouble())
        val r = radius - ringWidth / 2f
        paint.style = Paint.Style.FILL
        paint.color = if (vol.isMuted) RING_MUTED else THUMB
        canvas.drawCircle(
            (cx + r * Math.cos(angle)).toFloat(),
            (cy + r * Math.sin(angle)).toFloat(),
            ringWidth * 0.30f,
            paint
        )
    }

    private fun drawProgress(canvas: Canvas, z: Zone?) {
        val np = z?.nowPlaying ?: return
        val length = np.lengthSeconds ?: return
        val pos = np.seekPosition ?: return
        if (length <= 0) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = PROGRESS
        canvas.drawArc(progressRect, -90f, 360f * (pos.toFloat() / length), false, paint)
    }

    private fun drawArtwork(canvas: Canvas) {
        val bmp = artwork
        if (bmp == null || bmp.isRecycled) {
            paint.style = Paint.Style.FILL
            paint.color = ART_PLACEHOLDER
            canvas.drawCircle(cx, cy, innerRadius, paint)
            return
        }

        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val size = 2f * innerRadius
        val scale = maxOf(size / bmp.width, size / bmp.height)
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            cx - bmp.width * scale / 2f,
            cy - bmp.height * scale / 2f
        )
        shader.setLocalMatrix(matrix)
        artPaint.shader = shader
        canvas.drawCircle(cx, cy, innerRadius, artPaint)
        artPaint.shader = null

        // Scrim so the overlaid text stays legible on bright covers.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(150, 4, 6, 9)
        canvas.drawCircle(cx, cy, innerRadius, paint)
    }

    private fun drawText(canvas: Canvas, z: Zone?) {
        val adjusting = System.currentTimeMillis() - lastGestureAt < OPTIMISTIC_WINDOW_MS

        // Zone name, top of the inner circle.
        textPaint.color = TEXT_SECONDARY
        textPaint.isFakeBoldText = false
        textPaint.textSize = innerRadius * 0.11f
        val zoneLabel = z?.displayName ?: "No zone"
        canvas.drawText(ellipsise(zoneLabel, innerRadius * 1.5f), cx, cy - innerRadius * 0.60f, textPaint)

        if (adjusting) {
            // While the ring is moving, the number is the point.
            val vol = z?.primaryVolume
            textPaint.color = TEXT_PRIMARY
            textPaint.isFakeBoldText = true
            textPaint.textSize = innerRadius * 0.42f
            val label = when {
                vol == null -> "—"
                vol.isMuted -> "muted"
                vol.isIncremental -> "+/-"
                vol.type == "db" -> String.format("%.1f", displayedVolume() ?: 0.0)
                else -> (displayedVolume() ?: 0.0).roundToInt().toString()
            }
            canvas.drawText(label, cx, cy + innerRadius * 0.10f, textPaint)

            textPaint.isFakeBoldText = false
            textPaint.color = TEXT_SECONDARY
            textPaint.textSize = innerRadius * 0.12f
            val units = when {
                vol == null -> "no volume control"
                vol.type == "db" -> "dB"
                else -> "volume"
            }
            canvas.drawText(units, cx, cy + innerRadius * 0.28f, textPaint)
            return
        }

        val currentVoice = voice
        if (currentVoice !is Voice.Idle) {
            val heading = when (currentVoice) {
                is Voice.Listening -> "Listening…"
                is Voice.Working -> "Searching Roon"
                is Voice.Said -> ""
                else -> ""
            }
            val detail = when (currentVoice) {
                is Voice.Listening -> currentVoice.heard
                is Voice.Working -> currentVoice.query
                is Voice.Said -> currentVoice.message
                else -> ""
            }
            if (heading.isNotEmpty()) {
                textPaint.color = TEXT_SECONDARY
                textPaint.isFakeBoldText = false
                textPaint.textSize = innerRadius * 0.12f
                canvas.drawText(heading, cx, cy - innerRadius * 0.16f, textPaint)
            }
            textPaint.color = TEXT_PRIMARY
            textPaint.isFakeBoldText = true
            textPaint.textSize = innerRadius * 0.15f
            var y = cy + innerRadius * 0.04f
            for (line in wrap(detail, 22).take(3)) {
                canvas.drawText(ellipsise(line, innerRadius * 1.6f), cx, y, textPaint)
                y += innerRadius * 0.19f
            }
            textPaint.isFakeBoldText = false
            return
        }

        val np = z?.nowPlaying
        if (np == null) {
            textPaint.color = TEXT_SECONDARY
            textPaint.textSize = innerRadius * 0.11f
            var y = cy - innerRadius * 0.05f
            for (line in wrap(statusText, 26)) {
                canvas.drawText(line, cx, y, textPaint)
                y += innerRadius * 0.15f
            }
            return
        }

        textPaint.color = TEXT_PRIMARY
        textPaint.isFakeBoldText = true
        textPaint.textSize = innerRadius * 0.155f
        canvas.drawText(ellipsise(np.line1, innerRadius * 1.6f), cx, cy - innerRadius * 0.10f, textPaint)

        textPaint.isFakeBoldText = false
        textPaint.color = TEXT_SECONDARY
        textPaint.textSize = innerRadius * 0.125f
        canvas.drawText(ellipsise(np.line2, innerRadius * 1.6f), cx, cy + innerRadius * 0.09f, textPaint)
        textPaint.textSize = innerRadius * 0.105f
        canvas.drawText(ellipsise(np.line3, innerRadius * 1.6f), cx, cy + innerRadius * 0.25f, textPaint)

        // Small persistent volume readout under the zone name.
        val vol = z.primaryVolume
        if (vol != null) {
            textPaint.color = if (vol.isMuted) RING_MUTED else TEXT_SECONDARY
            textPaint.textSize = innerRadius * 0.10f
            val label = if (vol.isMuted) "muted" else vol.format()
            canvas.drawText(label, cx, cy - innerRadius * 0.44f, textPaint)
        }
    }

    /** Previous, play/pause, next, microphone — evenly spaced across the dial. */
    private fun controlCentres(): FloatArray {
        transportY = cy + innerRadius * 0.58f
        val spacing = innerRadius * 0.38f
        return floatArrayOf(
            cx - spacing * 1.5f,
            cx - spacing * 0.5f,
            cx + spacing * 0.5f,
            cx + spacing * 1.5f
        )
    }

    private var transportY = 0f
    private val transportRadius: Float get() = innerRadius * 0.16f

    private fun drawTransport(canvas: Canvas, z: Zone?) {
        val centres = controlCentres()
        val y = transportY
        val r = transportRadius
        val listening = voice !is Voice.Idle

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(70, 255, 255, 255)
        canvas.drawCircle(centres[1], y, r, paint)
        paint.color = if (listening) RING_FILL else Color.argb(70, 255, 255, 255)
        canvas.drawCircle(centres[3], y, r, paint)

        iconPaint.style = Paint.Style.FILL
        iconPaint.color = if (z == null) TEXT_SECONDARY else TEXT_PRIMARY

        drawSkip(canvas, centres[0], y, r * 0.62f, back = true)
        if (z?.isPlaying == true) drawPause(canvas, centres[1], y, r * 0.52f)
        else drawPlay(canvas, centres[1], y, r * 0.58f)
        drawSkip(canvas, centres[2], y, r * 0.62f, back = false)

        iconPaint.color = if (listening) 0xFF07080A.toInt() else TEXT_PRIMARY
        drawMicrophone(canvas, centres[3], y, r * 0.62f)
    }

    private fun drawMicrophone(canvas: Canvas, x: Float, y: Float, s: Float) {
        // A capsule, a cradle under it, a stem: narrow enough to read as a
        // microphone rather than as a circle at this size.
        val capsuleHalfWidth = s * 0.30f
        canvas.drawRoundRect(
            x - capsuleHalfWidth, y - s * 0.95f,
            x + capsuleHalfWidth, y + s * 0.12f,
            capsuleHalfWidth, capsuleHalfWidth, iconPaint
        )

        val cradle = s * 0.60f
        val stroke = s * 0.17f
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = stroke
        iconPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(
            x - cradle, y - cradle * 0.55f,
            x + cradle, y + cradle * 1.00f,
            0f, 180f, false, iconPaint
        )
        iconPaint.style = Paint.Style.FILL

        canvas.drawRect(
            x - stroke * 0.45f, y + cradle * 0.72f,
            x + stroke * 0.45f, y + s * 1.02f, iconPaint
        )
    }

    private fun drawPlay(canvas: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x - s * 0.55f, y - s)
        path.lineTo(x + s * 0.85f, y)
        path.lineTo(x - s * 0.55f, y + s)
        path.close()
        canvas.drawPath(path, iconPaint)
    }

    private fun drawPause(canvas: Canvas, x: Float, y: Float, s: Float) {
        val w = s * 0.42f
        canvas.drawRect(x - s * 0.75f, y - s, x - s * 0.75f + w, y + s, iconPaint)
        canvas.drawRect(x + s * 0.33f, y - s, x + s * 0.33f + w, y + s, iconPaint)
    }

    private fun drawSkip(canvas: Canvas, x: Float, y: Float, s: Float, back: Boolean) {
        val dir = if (back) -1f else 1f
        path.reset()
        path.moveTo(x - dir * s * 0.9f, y - s)
        path.lineTo(x + dir * s * 0.1f, y)
        path.lineTo(x - dir * s * 0.9f, y + s)
        path.close()
        path.moveTo(x + dir * s * 0.05f, y - s)
        path.lineTo(x + dir * s * 1.05f, y)
        path.lineTo(x + dir * s * 0.05f, y + s)
        path.close()
        canvas.drawPath(path, iconPaint)
    }

    private fun ellipsise(text: String, maxWidth: Float): String {
        if (text.isEmpty()) return text
        if (textPaint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && textPaint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    private fun wrap(text: String, perLine: Int): List<String> {
        val words = text.split(' ')
        val lines = ArrayList<String>()
        var current = StringBuilder()
        for (word in words) {
            if (current.isEmpty()) current.append(word)
            else if (current.length + 1 + word.length <= perLine) current.append(' ').append(word)
            else { lines += current.toString(); current = StringBuilder(word) }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    // -------------------------------------------------------------- gestures

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val dist = hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                moved = false
                longPressFired = false
                residual = 0.0
                residualDegrees = 0.0
                parent?.requestDisallowInterceptTouchEvent(true)

                mode = if (dist >= radius - ringWidth * 1.7f && dist <= radius + ringWidth) {
                    lastAngle = angleOf(x, y)
                    lastGestureAt = System.currentTimeMillis()
                    Mode.RING
                } else {
                    handler.postDelayed(longPressRunnable, 550)
                    Mode.INNER
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!moved && hypot((x - downX).toDouble(), (y - downY).toDouble()) > touchSlop) {
                    moved = true
                    handler.removeCallbacks(longPressRunnable)
                }
                if (mode == Mode.RING) {
                    val angle = angleOf(x, y)
                    var delta = angle - lastAngle
                    // Keep the sweep continuous across the 12 o'clock seam.
                    if (delta > 180) delta -= 360
                    if (delta < -180) delta += 360
                    lastAngle = angle
                    if (abs(delta) < 90) applyRotation(delta)
                    lastGestureAt = System.currentTimeMillis()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (mode == Mode.RING) {
                    flushSteps()
                    // Drop back to the now-playing view once the Core has
                    // had time to echo the new value.
                    handler.postDelayed({
                        if (System.currentTimeMillis() - lastGestureAt >= OPTIMISTIC_WINDOW_MS) {
                            optimisticValue = null
                            invalidate()
                        }
                    }, OPTIMISTIC_WINDOW_MS + 50)
                } else if (!moved && !longPressFired) {
                    handleTap(x, y)
                }
                mode = Mode.NONE
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                mode = Mode.NONE
                flushSteps()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun angleOf(x: Float, y: Float): Double =
        Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))

    /**
     * Turns rotation into whole volume steps. Everything below one step is
     * kept in [residual] so slow sweeps still accumulate instead of being
     * rounded away.
     */
    private fun applyRotation(degrees: Double) {
        val vol = zone?.primaryVolume ?: return

        val steps: Int
        if (vol.isIncremental) {
            residualDegrees += degrees
            steps = (residualDegrees / DEGREES_PER_INCREMENT).toInt()
            if (steps == 0) return
            residualDegrees -= steps * DEGREES_PER_INCREMENT
        } else {
            val span = vol.effectiveMax - vol.min
            if (span <= 0.0) return
            residual += degrees * (span / DEGREES_FOR_FULL_RANGE)
            steps = (residual / vol.step).toInt()
            if (steps == 0) return
            residual -= steps * vol.step

            val base = optimisticValue ?: vol.value
            optimisticValue = (base + steps * vol.step).coerceIn(vol.min, vol.effectiveMax)
        }

        performHapticFeedback(
            HapticFeedbackConstants.CLOCK_TICK,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )

        pendingSteps += steps
        val now = System.currentTimeMillis()
        if (now - lastSendAt >= SEND_INTERVAL_MS) {
            flushSteps()
        } else {
            handler.removeCallbacks(sendRunnable)
            handler.postDelayed(sendRunnable, SEND_INTERVAL_MS - (now - lastSendAt))
        }
    }

    private fun flushSteps() {
        handler.removeCallbacks(sendRunnable)
        if (pendingSteps == 0) return
        val steps = pendingSteps
        pendingSteps = 0
        lastSendAt = System.currentTimeMillis()
        callbacks?.onVolumeSteps(steps)
    }

    private fun handleTap(x: Float, y: Float) {
        val centres = controlCentres()
        val r = transportRadius * 1.30f

        for ((index, centre) in centres.withIndex()) {
            if (hypot((x - centre).toDouble(), (y - transportY).toDouble()) > r) continue
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            when (index) {
                0 -> callbacks?.onPrevious()
                1 -> callbacks?.onPlayPause()
                2 -> callbacks?.onNext()
                else -> callbacks?.onVoiceTapped()
            }
            return
        }
        // Upper third of the inner circle: zone name and volume readout.
        if (y < cy - innerRadius * 0.50f) { callbacks?.onZoneTapped(); return }
        if (y < cy - innerRadius * 0.30f) { callbacks?.onMuteTapped(); return }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(sendRunnable)
        handler.removeCallbacks(longPressRunnable)
    }
}
