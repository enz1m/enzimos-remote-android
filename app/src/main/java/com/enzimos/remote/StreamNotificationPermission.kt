package com.enzimos.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

internal object StreamNotificationPermission {
    fun requestOnce(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val preferences = context.getSharedPreferences("stream_notifications", Context.MODE_PRIVATE)
        if (preferences.getBoolean("permission_considered", false)) return false
        preferences.edit().putBoolean("permission_considered", true).apply()
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }
}
