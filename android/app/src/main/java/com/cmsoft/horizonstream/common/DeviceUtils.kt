package com.cmsoft.horizonstream.common

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
object DeviceUtils {
    fun isQuest(): Boolean {
        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        return manufacturer.contains("Oculus", ignoreCase = true) ||
               manufacturer.contains("Meta", ignoreCase = true) ||
               model.contains("Quest", ignoreCase = true)
    }

    fun applyImmersiveMode(activity: Activity, enabled: Boolean) {
        val window = activity.window
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
