package com.enzimos.remote.video

import android.graphics.Color
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.MaterialColors

object StreamSystemBars {
    fun configure(window: Window, hideNavigation: Boolean) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.statusBars())
        if (hideNavigation) controller.hide(WindowInsetsCompat.Type.navigationBars())
        else controller.show(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val surface = MaterialColors.getColor(window.decorView, com.google.android.material.R.attr.colorSurface, Color.BLACK)
        val light = MaterialColors.isColorLight(surface)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }
}
