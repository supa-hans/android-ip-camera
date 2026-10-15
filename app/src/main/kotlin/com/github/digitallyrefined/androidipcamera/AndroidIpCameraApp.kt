package com.github.digitallyrefined.androidipcamera

import android.app.Application
import com.github.digitallyrefined.androidipcamera.helpers.CrashLogger

/**
 * Installs the crash-safe log (see [CrashLogger]) before anything else in the app runs, so a
 * crash in any component - the service, the activity, a camera callback thread - is always
 * caught, no matter which screen or background path happens to be running when it dies.
 */
class AndroidIpCameraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
    }
}
