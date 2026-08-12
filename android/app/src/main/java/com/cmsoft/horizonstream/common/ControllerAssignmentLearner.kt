package com.cmsoft.horizonstream.common

import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import com.cmsoft.horizonstream.lib.ControllerState

object ControllerAssignmentLearner {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: ((String) -> Unit)? = null

    val isLearning: Boolean
        get() = listener != null

    fun begin(onLearned: (String) -> Unit) {
        listener = onLearned
    }

    fun cancel() {
        listener = null
    }

    fun captureKeyEvent(event: KeyEvent): Boolean {
        if (!isLearning ||
            event.action != KeyEvent.ACTION_DOWN ||
            event.repeatCount != 0 ||
            !event.isFromSource(InputDevice.SOURCE_GAMEPAD)
        ) {
            return false
        }
        complete("key:${event.keyCode}")
        return true
    }

    fun captureQuestButtons(newlyPressedButtons: UInt): Boolean {
        val assignableButtons =
            newlyPressedButtons and ControllerState.BUTTON_OPTIONS.inv()
        if (!isLearning || assignableButtons == 0U)
            return false
        val button = assignableButtons.takeLowestOneBit()
        complete("quest:$button")
        return true
    }

    fun normalizedBinding(binding: String?): String? =
        binding?.takeUnless {
            it == "quest:${ControllerState.BUTTON_OPTIONS}"
        }

    private fun complete(binding: String) {
        val callback = listener ?: return
        listener = null
        if (Looper.myLooper() == Looper.getMainLooper())
            callback(binding)
        else
            mainHandler.post { callback(binding) }
    }

    fun label(binding: String?): String {
        val normalizedBinding = normalizedBinding(binding)
        if (normalizedBinding.isNullOrBlank())
            return "Not assigned"
        val parts = normalizedBinding.split(':', limit = 2)
        val value = parts.getOrNull(1)?.toIntOrNull() ?: return "Not assigned"
        return when (parts.firstOrNull()) {
            "key" -> KeyEvent.keyCodeToString(value)
                .removePrefix("KEYCODE_")
                .replace('_', ' ')
            "quest" -> when (value.toUInt()) {
                ControllerState.BUTTON_CROSS -> "Quest A / Cross"
                ControllerState.BUTTON_MOON -> "Quest B / Circle"
                ControllerState.BUTTON_BOX -> "Quest X / Square"
                ControllerState.BUTTON_PYRAMID -> "Quest Y / Triangle"
                ControllerState.BUTTON_L3 -> "Quest Left Stick Click"
                ControllerState.BUTTON_R3 -> "Quest Right Stick Click"
                else -> "Quest button $value"
            }
            else -> "Not assigned"
        }
    }
}
