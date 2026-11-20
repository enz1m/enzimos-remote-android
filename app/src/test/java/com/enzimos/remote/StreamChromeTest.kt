package com.enzimos.remote

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.WindowInsetsCompat
import com.enzimos.remote.databinding.ActivityStreamBinding
import com.enzimos.remote.databinding.ViewStreamDockBinding
import com.enzimos.remote.video.StreamInsets
import com.enzimos.remote.video.StreamSystemBars
import com.enzimos.remote.video.VideoCornerMask
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class StreamChromeTest {
    private fun context(fontScale: Float = 1f): ContextThemeWrapper {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply { this.fontScale = fontScale }
        return ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_Enzremote_Stream)
    }

    @Test fun statusCutoutWaterfallAndKeyboardAreUnitedWithoutDoublePadding() {
        val result = StreamInsets.merge(
            Insets.of(0, 117, 0, 132), Insets.of(0, 96, 0, 0),
            Insets.of(4, 0, 6, 8), Insets.of(0, 0, 0, 900),
        )
        assertEquals(Insets.of(4, 117, 6, 900), result)
    }

    @Test fun immersivePreferenceCannotHideTheStatusBar() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Enzremote_Stream)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        StreamSystemBars.configure(activity.window, hideNavigation = true)
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN)
        assertEquals(0, activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN)
        assertTrue(activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
        StreamSystemBars.configure(activity.window, hideNavigation = false)
        assertEquals(0, activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
    }

    @Test fun maskOnlyConsumesTouchesInTheInvisibleTopCorners() {
        val mask = VideoCornerMask(context()).apply { layout(0, 0, 200, 400) }
        val corner = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        val center = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100f, 10f, 0)
        assertTrue(mask.onTouchEvent(corner))
        assertFalse(mask.onTouchEvent(center))
        assertTrue(VideoCornerMask.containsVideoPoint(1f, 399f, 200f, 400f, 20f))
        assertTrue(VideoCornerMask.containsVideoPoint(20f, 0f, 200f, 400f, 20f))
        assertFalse(VideoCornerMask.containsVideoPoint(199f, 1f, 200f, 400f, 20f))
        corner.recycle(); center.recycle()
    }

    private fun dock(fontScale: Float = 1f, width: Int = 360): ViewStreamDockBinding {
        val binding = ViewStreamDockBinding.inflate(LayoutInflater.from(context(fontScale)))
        binding.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        binding.root.layout(0, 0, binding.root.measuredWidth, binding.root.measuredHeight)
        return binding
    }

    @Test fun keyboardIndicatorFollowsActualImeStateNotAnOptimisticClick() {
        val button = dock().dockKeyboard
        button.isCheckable = true
        button.setToggleCheckedStateOnClick(false)
        button.performClick()
        assertFalse(button.isChecked)
        button.isChecked = true
        button.performClick()
        assertTrue(button.isChecked)
    }

    @Test fun videoAndDockUseThePaddedAreaWithoutCoveringEachOther() {
        val binding = ActivityStreamBinding.inflate(LayoutInflater.from(context()))
        binding.streamContent.setPadding(40, 117, 20, 500)
        binding.root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        binding.root.layout(0, 0, 1080, 2400)
        assertEquals(1020, binding.videoContainer.width)
        val available = 2400 - 117 - 500
        assertEquals(available, binding.videoContainer.height + binding.streamDock.root.height)
        assertEquals(binding.videoContainer.bottom, binding.streamDock.root.top)
    }
}
