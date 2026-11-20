package com.enzimos.remote

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.enzimos.remote.input.AppTouchController
import com.enzimos.remote.input.MouseInputController
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
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
class AppTouchControllerTest {
    private lateinit var view: View
    private lateinit var root: FrameLayout
    private lateinit var controller: AppTouchController
    private var messages = mutableListOf<String>()
    private var destination = messages
    private var beganAt = 0L

    @Before fun setup() {
        messages = mutableListOf(); destination = messages
        root = FrameLayout(RuntimeEnvironment.getApplication())
        view = View(root.context)
        root.addView(view, FrameLayout.LayoutParams(400, 800))
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 800)
        controller = AppTouchController(view, { 800 to 1600 },
            { val captured = destination; { json, _ -> captured.add(json); true } }, { 1f },
            { _, _, _ -> }, {}, {})
        view.setOnTouchListener { _, event -> controller.onTouchEvent(event) }
        beganAt = SystemClock.uptimeMillis()
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun event(action: Int, x: Float = 100f, y: Float = 400f, second: Boolean = false,
                      source: Int = InputDevice.SOURCE_TOUCHSCREEN, buttons: Int = 0): MotionEvent {
        val count = if (second) 2 else 1
        val props = Array(count) { index -> MotionEvent.PointerProperties().apply {
            id = index
            toolType = if (source == InputDevice.SOURCE_MOUSE) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        } }
        val coords = Array(count) { index -> MotionEvent.PointerCoords().apply { this.x = x + index * 50; this.y = y; pressure = 1f; size = 1f } }
        return MotionEvent.obtain(beganAt, SystemClock.uptimeMillis(), action, count, props, coords,
            0, buttons, 1f, 1f, 0, 0, source, 0)
    }
    private fun touch(action: Int, x: Float = 100f, y: Float = 400f, second: Boolean = false) {
        val e = event(action, x, y, second)
        controller.onTouchEvent(e); e.recycle()
    }
    private fun parsed() = messages.map(::JSONObject)

    @After fun cleanup() { controller.cancel() }

    @Test fun fingerScrollUsesPixelsAndDoesNotPressButtons() {
        touch(MotionEvent.ACTION_DOWN)
        advance(40); touch(MotionEvent.ACTION_MOVE, y = 300f)
        controller.cancel()
        val events = parsed()
        assertTrue(events.all { it.getString("type") == "scroll_pixels" })
        assertEquals(200.0, events.first().getDouble("dy"), 0.001)
        assertTrue(events.last().getBoolean("stop"))
    }

    @Test fun longerHoldHasRightClickButNoLeftClickOrReleaseTap() {
        touch(MotionEvent.ACTION_DOWN)
        advance(1100)
        touch(MotionEvent.ACTION_UP)
        val buttons = parsed().filter { it.getString("type") == "button" }
        assertEquals(2, buttons.size)
        assertTrue(buttons.all { it.getInt("code") == 273 })
        assertFalse(parsed().any { it.optBoolean("click") })
    }

    @Test fun oldHoldCannotSendIntoAReplacementChannel() {
        touch(MotionEvent.ACTION_DOWN)
        val replacement = mutableListOf<String>(); destination = replacement
        advance(1100)
        assertTrue(replacement.isEmpty())
        assertTrue(messages.isNotEmpty())
    }

    @Test fun viewTransformIsNotAppliedTwiceToRemoteCoordinates() {
        view.scaleX = 2f; view.scaleY = 2f
        val down = event(MotionEvent.ACTION_DOWN, 100f, 400f)
        root.dispatchTouchEvent(down); down.recycle()
        advance(50)
        val up = event(MotionEvent.ACTION_UP, 100f, 400f)
        root.dispatchTouchEvent(up); up.recycle()
        assertEquals(300.0, parsed().single().getDouble("x"), 0.001)
    }
}
