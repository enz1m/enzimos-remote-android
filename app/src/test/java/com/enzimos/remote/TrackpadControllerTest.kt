package com.enzimos.remote

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.enzimos.remote.input.GestureMapper
import com.enzimos.remote.input.TrackpadController
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class TrackpadControllerTest {
    private lateinit var view: View
    private lateinit var controller: TrackpadController
    private val messages = mutableListOf<String>()
    private var beganAt = 0L
    private var dragReady = 0

    @Before fun setup() {
        val root = FrameLayout(RuntimeEnvironment.getApplication())
        view = View(root.context)
        root.addView(view, FrameLayout.LayoutParams(400, 800))
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 800)
        controller = TrackpadController(view, { 800 to 1600 }, { { json, _ -> messages.add(json); true } }, { 1f },
            { dragReady++ })
        beganAt = SystemClock.uptimeMillis()
    }

    @After fun cleanup() = controller.cancel()

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun touch(action: Int, x: Float = 200f, y: Float = 400f, count: Int = 1) {
        val props = Array(count) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(count) { index -> MotionEvent.PointerCoords().apply { this.x = x + index * 60; this.y = y; pressure = 1f; size = 1f } }
        val event = MotionEvent.obtain(beganAt, SystemClock.uptimeMillis(), action, count, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        controller.onTouchEvent(event); event.recycle()
    }
    private fun parsed() = messages.map(::JSONObject)
    private fun buttons() = parsed().filter { it.getString("type") == "button" }

    @Test fun twoFingerTapRightClicksAndTwoFingerSlideScrolls() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), count = 2)
        advance(80); touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), count = 2)
        touch(MotionEvent.ACTION_UP)
        assertEquals(listOf(GestureMapper.BTN_RIGHT, GestureMapper.BTN_RIGHT), buttons().map { it.getInt("code") })

        messages.clear(); advance(400)
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), count = 2)
        advance(16); touch(MotionEvent.ACTION_MOVE, 200f, 340f, count = 2)
        advance(300); touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200f, 340f, count = 2)
        touch(MotionEvent.ACTION_UP, 200f, 340f)
        assertTrue(buttons().isEmpty())
        val scrolls = parsed().filter { it.getString("type") == "scroll_pixels" }
        assertTrue(scrolls.first().getDouble("dy") > 0)
        assertTrue(scrolls.last().getBoolean("stop"))
    }

    @Test fun tapThenSlideDragsAndHoldThenSlideDrags() {
        touch(MotionEvent.ACTION_DOWN); advance(60); touch(MotionEvent.ACTION_UP)
        messages.clear(); advance(100)
        touch(MotionEvent.ACTION_DOWN)
        advance(16); touch(MotionEvent.ACTION_MOVE, 260f, 400f)
        advance(16); touch(MotionEvent.ACTION_UP, 260f, 400f)
        assertEquals(listOf(true, false), buttons().map { it.getBoolean("pressed") })

        messages.clear(); advance(600)
        touch(MotionEvent.ACTION_DOWN)
        advance(TrackpadController.HOLD_TO_DRAG_MS + 20)
        assertEquals(1, dragReady)
        touch(MotionEvent.ACTION_MOVE, 260f, 400f)
        touch(MotionEvent.ACTION_UP, 260f, 400f)
        assertEquals(listOf(true, false), buttons().map { it.getBoolean("pressed") })
    }
}
