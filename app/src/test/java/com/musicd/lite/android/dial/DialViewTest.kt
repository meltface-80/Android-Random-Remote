package com.musicd.lite.android.dial

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.musicd.lite.roon.Zone
import com.musicd.lite.roon.ZoneStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders DialView with Robolectric's native graphics, which runs the real
 * Skia pipeline on the JVM, and drives real MotionEvents through the ring.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DialViewTest {

    private val size = 900
    private val previewDir = File("build/preview").apply { mkdirs() }

    private fun zone(json: String): Zone {
        val store = ZoneStore()
        store.applySubscribed(JSONObject("""{"zones":[$json]}"""))
        return store.all().first()
    }

    private val playingZone = zone(
        """
        {"zone_id":"z1","display_name":"Living Room","state":"playing",
         "is_pause_allowed":true,"is_next_allowed":true,"is_previous_allowed":true,
         "outputs":[{"output_id":"o1","display_name":"Living Room",
           "volume":{"type":"db","min":-80,"max":0,"value":-32.5,"step":0.5,"is_muted":false}}],
         "now_playing":{"length":300,"seek_position":126,
           "three_line":{"line1":"Teardrop","line2":"Massive Attack","line3":"Mezzanine"}}}
        """.trimIndent()
    )

    private fun makeView(): DialView {
        val view = DialView(RuntimeEnvironment.getApplication())
        view.measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, size, size)
        return view
    }

    private fun render(view: DialView, name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(previewDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }

    private class Recorder : DialView.Callbacks {
        var steps = 0
        var playPause = 0
        var next = 0
        var previous = 0
        var zoneTaps = 0
        var muteTaps = 0
        var voiceTaps = 0
        override fun onVolumeSteps(steps: Int) { this.steps += steps }
        override fun onPlayPause() { playPause++ }
        override fun onNext() { next++ }
        override fun onPrevious() { previous++ }
        override fun onZoneTapped() { zoneTaps++ }
        override fun onMuteTapped() { muteTaps++ }
        override fun onVoiceTapped() { voiceTaps++ }
        override fun onLongPress() { }
    }

    /** A point on the volume ring at the given compass-style angle. */
    private fun ringPoint(degrees: Double): Pair<Float, Float> {
        val centre = size / 2.0
        val radius = centre - 8 - (centre - 8) * 0.115 / 2
        val rad = Math.toRadians(degrees)
        return Pair(
            (centre + radius * cos(rad)).toFloat(),
            (centre + radius * sin(rad)).toFloat()
        )
    }

    private fun send(view: DialView, action: Int, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(t, t, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    @Test
    fun rendersNowPlaying() {
        val view = makeView()
        view.setZone(playingZone)
        val bitmap = render(view, "now-playing")

        // -32.5 dB across -80..0 is 59.4% of the ring, so 3 o'clock is filled
        // and 9 o'clock is still bare track.
        val ringMid = (size / 2f - 8f) - (size / 2f - 8f) * 0.115f / 2f
        assertColourNear(0xFF7AC8FF.toInt(), bitmap.getPixel((size / 2 + ringMid).toInt() - 1, size / 2))
        assertColourNear(0xFF1C222A.toInt(), bitmap.getPixel((size / 2 - ringMid).toInt() + 1, size / 2))
        // Inside the art circle, clear of any text, is the artwork placeholder
        // rather than the page background.
        val innerRadius = (size / 2f - 8f) * (1f - 0.115f) - 10f
        assertColourNear(
            0xFF141A21.toInt(),
            bitmap.getPixel(size / 2, (size / 2 - innerRadius * 0.85f).toInt())
        )
    }

    @Test
    fun rendersWhileListening() {
        val view = makeView()
        view.setZone(playingZone)
        view.voice = DialView.Voice.Listening("play iron maiden")
        render(view, "listening")
    }

    @Test
    fun rendersDisconnectedState() {
        val view = makeView()
        view.setZone(null)
        view.setStatus("Enable Dial for Roon in Roon Settings Extensions")
        render(view, "waiting")
    }

    @Test
    fun ringSweepProducesTheExpectedNumberOfSteps() {
        val view = makeView()
        val recorder = Recorder()
        view.callbacks = recorder
        view.setZone(playingZone)

        // Sweep 90 degrees clockwise starting at 12 o'clock.
        var (x, y) = ringPoint(-90.0)
        send(view, MotionEvent.ACTION_DOWN, x, y)
        var deg = -90.0
        while (deg < 0.0) {
            deg += 5.0
            val p = ringPoint(deg)
            send(view, MotionEvent.ACTION_MOVE, p.first, p.second)
        }
        val end = ringPoint(0.0)
        send(view, MotionEvent.ACTION_UP, end.first, end.second)
        shadowOf(Looper.getMainLooper()).idle()

        // 90 degrees of a 320-degree full sweep over an 80 dB range is 22.5 dB,
        // which is 45 steps of 0.5 dB.
        assertEquals(45, recorder.steps.toLong().toInt())

        // And the ring shows the new value while the gesture is still warm.
        render(view, "adjusting")
    }

    @Test
    fun counterClockwiseSweepLowersVolume() {
        val view = makeView()
        val recorder = Recorder()
        view.callbacks = recorder
        view.setZone(playingZone)

        var deg = 0.0
        val start = ringPoint(deg)
        send(view, MotionEvent.ACTION_DOWN, start.first, start.second)
        while (deg > -60.0) {
            deg -= 5.0
            val p = ringPoint(deg)
            send(view, MotionEvent.ACTION_MOVE, p.first, p.second)
        }
        val end = ringPoint(-60.0)
        send(view, MotionEvent.ACTION_UP, end.first, end.second)
        shadowOf(Looper.getMainLooper()).idle()

        // 60 degrees down is 15 dB, i.e. 30 steps of 0.5 dB.
        assertEquals(-30, recorder.steps)
    }

    @Test
    fun sweepAcrossTwelveOClockDoesNotJump() {
        val view = makeView()
        val recorder = Recorder()
        view.callbacks = recorder
        view.setZone(playingZone)

        // Cross the -180/+180 seam, where a naive angle diff would spike.
        var deg = -170.0
        val start = ringPoint(deg)
        send(view, MotionEvent.ACTION_DOWN, start.first, start.second)
        while (deg > -190.0) {
            deg -= 5.0
            val p = ringPoint(deg)
            send(view, MotionEvent.ACTION_MOVE, p.first, p.second)
        }
        send(view, MotionEvent.ACTION_UP, ringPoint(-190.0).first, ringPoint(-190.0).second)
        shadowOf(Looper.getMainLooper()).idle()

        // 20 degrees is 5 dB, i.e. 10 steps down. A seam bug would give ~ +170.
        assertEquals(-10, recorder.steps)
    }

    @Test
    fun fixedVolumeZoneIgnoresTheRing() {
        val view = makeView()
        val recorder = Recorder()
        view.callbacks = recorder
        view.setZone(
            zone("""{"zone_id":"z9","display_name":"Fixed","state":"playing","outputs":[{"output_id":"o9","display_name":"DAC"}]}""")
        )

        val start = ringPoint(-90.0)
        send(view, MotionEvent.ACTION_DOWN, start.first, start.second)
        var deg = -90.0
        while (deg < 0.0) {
            deg += 10.0
            val p = ringPoint(deg)
            send(view, MotionEvent.ACTION_MOVE, p.first, p.second)
        }
        send(view, MotionEvent.ACTION_UP, ringPoint(0.0).first, ringPoint(0.0).second)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, recorder.steps)
    }

    @Test
    fun tappingTheCentreHitsTransport() {
        val view = makeView()
        val recorder = Recorder()
        view.callbacks = recorder
        view.setZone(playingZone)

        val centre = size / 2f
        val radius = centre - 8f
        val innerRadius = radius - radius * 0.115f - 10f * 1f
        val transportY = centre + innerRadius * 0.58f
        val spacing = innerRadius * 0.38f

        fun tap(x: Float) {
            send(view, MotionEvent.ACTION_DOWN, x, transportY)
            send(view, MotionEvent.ACTION_UP, x, transportY)
        }

        tap(centre - spacing * 1.5f)
        assertEquals(1, recorder.previous)

        tap(centre - spacing * 0.5f)
        assertEquals(1, recorder.playPause)

        tap(centre + spacing * 0.5f)
        assertEquals(1, recorder.next)

        // The microphone: a tap, then speak. The whole point is that it needs
        // no assistant, so it has to be a control you can actually hit.
        tap(centre + spacing * 1.5f)
        assertEquals(1, recorder.voiceTaps)

        // Zone name sits in the upper part of the inner circle.
        val zoneY = centre - innerRadius * 0.60f
        send(view, MotionEvent.ACTION_DOWN, centre, zoneY)
        send(view, MotionEvent.ACTION_UP, centre, zoneY)
        assertEquals(1, recorder.zoneTaps)
    }

    private fun assertColourNear(expected: Int, actual: Int) {
        val channels = listOf(16, 8, 0)
        for (shift in channels) {
            val e = (expected shr shift) and 0xFF
            val a = (actual shr shift) and 0xFF
            assertTrue(
                "expected ${Integer.toHexString(expected)} but was ${Integer.toHexString(actual)}",
                abs(e - a) <= 12
            )
        }
    }
}
