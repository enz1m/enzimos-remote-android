package com.enzimos.remote

import android.app.Application
import com.enzimos.remote.connection.ConnectionManager
import com.google.android.material.color.DynamicColors

class RemoteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        // Start connecting to paired computers before the first frame, so
        // the home screen opens already connected.
        ConnectionManager.init(this)
        // Battery reports and find-my-phone handling for the computers.
        com.enzimos.remote.phone.PhoneFeatures.get(this)
    }
}
