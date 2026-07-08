package com.cmsoft.horizonstream.common

import android.os.Build

object DeviceUtils {
    fun isQuest(): Boolean {
        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        return manufacturer.contains("Oculus", ignoreCase = true) ||
               manufacturer.contains("Meta", ignoreCase = true) ||
               model.contains("Quest", ignoreCase = true)
    }
}
