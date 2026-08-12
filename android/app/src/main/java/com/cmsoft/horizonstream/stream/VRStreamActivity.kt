// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.cmsoft.horizonstream.stream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.Keep
import com.cmsoft.horizonstream.R
import com.cmsoft.horizonstream.common.ControllerAssignmentLearner
import com.cmsoft.horizonstream.common.Preferences
import com.cmsoft.horizonstream.depth.DepthAnythingV2Bridge
import com.cmsoft.horizonstream.lib.ControllerState

class VRStreamActivity : StreamActivity(), SurfaceHolder.Callback {
    private data class ImmersiveErrorDialog(
        val title: String,
        val message: String,
        val actions: List<String>,
        val onAction: (Int) -> Unit
    )

    companion object {
        private const val TAG = "VRStreamActivity"

        init {
            try {
                System.loadLibrary("openxr_loader")
                Log.i(TAG, "openxr_loader native library loaded successfully for VRStreamActivity.")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to load openxr_loader native library: ${e.message}")
            }
            try {
                System.loadLibrary("chiaki-jni")
                Log.i(TAG, "chiaki-jni native library loaded successfully for VRStreamActivity.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load chiaki-jni native library for VRStreamActivity", e)
            }
        }
    }

    private external fun nativeInitVR(
        activity: VRStreamActivity,
        streamWidth: Int,
        streamHeight: Int,
        stereoConversionEnabled: Boolean,
        stereoDepthIntensity: Float
    ): android.view.Surface?
    private external fun nativeStartRenderLoop(surface: Any?)
    private external fun nativeSetSettingsOverlay(
        rgbaPixels: ByteArray?,
        width: Int,
        height: Int
    )
    private external fun nativeSetDepthPipelineReady(ready: Boolean)
    private external fun nativeSetDepthMap(depthMap: ByteArray, width: Int, height: Int)
    private external fun nativeStopVR()

    private var isVRInitialized = false
    private var vrInitializationAttempted = false
    private var isUsingFlatStreamFallback = false
    override val pauseStreamWhenBackgrounded = false
    @Volatile
    private var immersiveSettingsVisible = false
    private var immersiveSettingsSelection = 0
    private var immersiveSettingsLearning = false
    @Volatile
    private var immersiveErrorDialog: ImmersiveErrorDialog? = null
    private var immersiveErrorSelection = 0
    @Volatile
    private var immersivePinVisible = false
    private val immersivePinDigits = IntArray(4)
    private var immersivePinIndex = 0
    private var immersivePinIncorrect = false
    private var pinHorizontalLatched = false
    private var pinVerticalLatched = false
    private var settingsStickLatched = false
    @Volatile
    private var acceptQuestControllerInput = false
    private val controllerHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var questMenuHeld = false
    @Volatile
    private var questMenuLongPressTriggered = false
    private var questMenuAwaitingSecondTap = false
    private var questMenuSecondTapInProgress = false
    private var questMenuGestureSuppressed = false
    // OpenXR can deliver a carried/stale Menu-down state when a session first
    // gains focus. Inactive action samples before the interaction profile is
    // ready look neutral, so wait for the profile to stabilize and then require
    // a released sample before accepting a new gesture.
    private var questMenuReleasedSinceResume = false
    private var questMenuArmAfterUptimeMs = 0L
    @Volatile
    private var immersiveExitHintVisible = false
    private var previousQuestButtons = 0U
    private val questMenuLongPress = Runnable {
        if (questMenuHeld && acceptQuestControllerInput) {
            questMenuLongPressTriggered = true
            questMenuAwaitingSecondTap = false
            questMenuSecondTapInProgress = false
            controllerHandler.removeCallbacks(dispatchQuestMenuSingleTap)
            Log.i(TAG, "Quest Menu long-press recognized; exiting stream.")
            finish()
        }
    }
    private val dispatchQuestMenuSingleTap = Runnable {
        if(questMenuAwaitingSecondTap && acceptQuestControllerInput) {
            questMenuAwaitingSecondTap = false
            showImmersiveExitHint()
            viewModel.input.pulseQuestControllerButton(
                ControllerState.BUTTON_OPTIONS)
            Log.d(TAG, "Quest Menu single tap sent as PS5 Options.")
        }
    }
    private val hideImmersiveExitHint = Runnable {
        if(immersiveExitHintVisible) {
            immersiveExitHintVisible = false
            if(!immersiveSettingsVisible &&
                immersiveErrorDialog == null &&
                !immersivePinVisible) {
                nativeSetSettingsOverlay(null, 0, 0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "VRStreamActivity created; OpenXR will initialize after the activity resumes.")
    }

    /**
     * Quest only grants an immersive OpenXR session to a resumed activity. Initializing in
     * onCreate() can race the Horizon OS panel-to-immersive transition and leave the stream
     * running as a flat Android panel. The StreamSession tolerates receiving its decoder
     * surface after it has started, so initializing here keeps the lifecycle ordering safe.
     */
    private fun initializeVrAfterResume() {
        if (vrInitializationAttempted || isFinishing || isDestroyed) return
        vrInitializationAttempted = true
        Log.i(TAG, "VRStreamActivity resumed; initializing OpenXR 3D VR Engine.")

        try {
            val profile = viewModel.session.connectInfo.videoProfile
            val preferences = com.cmsoft.horizonstream.common.Preferences(this)
            val depthIntensity = when(preferences.simulated3dIntensity) {
                "low" -> 0.008f
                "high" -> 0.025f
                "strong" -> 0.070f
                else -> 0.015f
            }
            val xrSurface = nativeInitVR(
                this,
                profile.width,
                profile.height,
                preferences.simulated3dEnabled,
                depthIntensity
            )
            isVRInitialized = xrSurface != null
            if (xrSurface != null) {
                Log.i(TAG, "OpenXR initialized successfully. Received Android Surface Swapchain.")
                if(preferences.simulated3dEnabled) {
                    DepthAnythingV2Bridge.initialize(
                        context = this,
                        onReady = { nativeSetDepthPipelineReady(true) },
                        onDepthMap = { map, width, height ->
                            nativeSetDepthMap(map, width, height)
                        }
                    )
                }
                // Pass the OpenXR Surface to the ViewModel's session so the PlayStation video is drawn to the VR quad
                viewModel.session.setSurface(xrSurface)
                binding.root.visibility = android.view.View.GONE
            } else {
                Log.e(TAG, "Failed to initialize OpenXR VR Engine or create Surface.")
                useFlatStreamFallback()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "OpenXR Native VR Initialization notice: ${e.message}")
            useFlatStreamFallback()
        }
    }

    private fun useFlatStreamFallback() {
        if (isUsingFlatStreamFallback) return
        isUsingFlatStreamFallback = true
        binding.root.visibility = android.view.View.VISIBLE
        viewModel.session.attachToSurfaceView(binding.surfaceView)
    }

    private fun depthStrengthLabel(value: String): String = getString(when(value) {
        "low" -> R.string.preferences_simulated_3d_intensity_low
        "high" -> R.string.preferences_simulated_3d_intensity_high
        "strong" -> R.string.preferences_simulated_3d_intensity_strong
        else -> R.string.preferences_simulated_3d_intensity_medium
    })

    override fun openStreamSettings() {
        if(!isVRInitialized) {
            super.openStreamSettings()
            return
        }
        runOnUiThread {
            if(immersiveErrorDialog != null || immersivePinVisible)
                return@runOnUiThread
            cancelImmersiveExitHint()
            immersiveSettingsVisible = true
            immersiveSettingsSelection = 0
            immersiveSettingsLearning = false
            renderImmersiveSettingsOverlay()
            Log.i(TAG, "Immersive stream settings opened.")
        }
    }

    override fun showImmersiveQuitError(message: String): Boolean {
        if(!isVRInitialized)
            return false
        showImmersiveError(
            title = "Stream connection error",
            message = message,
            actions = listOf(
                getString(R.string.action_reconnect),
                getString(R.string.action_wakeup),
                getString(R.string.action_quit_session)
            )
        ) { action ->
            when(action) {
                0 -> reconnectStream()
                1 -> wakeConsoleAndFinish()
                else -> finish()
            }
        }
        return true
    }

    override fun showImmersiveCreateError(message: String): Boolean {
        if(!isVRInitialized)
            return false
        showImmersiveError(
            title = "Unable to start stream",
            message = message,
            actions = listOf(getString(R.string.action_quit_session))
        ) { finish() }
        return true
    }

    override fun showImmersiveLoginPin(pinIncorrect: Boolean): Boolean {
        if(!isVRInitialized)
            return false
        runOnUiThread {
            cancelImmersiveExitHint()
            immersiveSettingsVisible = false
            immersiveSettingsLearning = false
            immersiveErrorDialog = null
            immersivePinVisible = true
            immersivePinIncorrect = pinIncorrect
            immersivePinDigits.fill(0)
            immersivePinIndex = 0
            pinHorizontalLatched = false
            pinVerticalLatched = false
            renderImmersivePinOverlay()
            Log.i(TAG, "Immersive login PIN dialog opened.")
        }
        return true
    }

    private fun showImmersiveError(
        title: String,
        message: String,
        actions: List<String>,
        onAction: (Int) -> Unit
    ) {
        runOnUiThread {
            cancelImmersiveExitHint()
            immersiveSettingsVisible = false
            immersiveSettingsLearning = false
            immersivePinVisible = false
            immersiveErrorSelection = 0
            settingsStickLatched = false
            immersiveErrorDialog = ImmersiveErrorDialog(
                title, message, actions, onAction)
            renderImmersiveErrorOverlay()
            Log.i(TAG, "Immersive error dialog opened: $title")
        }
    }

    private fun closeImmersiveErrorDialog() {
        immersiveErrorDialog = null
        settingsStickLatched = false
        nativeSetSettingsOverlay(null, 0, 0)
    }

    private fun cancelImmersiveExitHint() {
        controllerHandler.removeCallbacks(hideImmersiveExitHint)
        immersiveExitHintVisible = false
    }

    private fun showImmersiveExitHint() {
        runOnUiThread {
            if(isFinishing || isDestroyed ||
                immersiveSettingsVisible ||
                immersiveErrorDialog != null ||
                immersivePinVisible) {
                return@runOnUiThread
            }

            cancelImmersiveExitHint()
            immersiveExitHintVisible = true

            val width = 960
            val height = 640
            val bitmap = Bitmap.createBitmap(
                width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val toastBounds = RectF(92f, 438f, 868f, 554f)

            paint.color = Color.argb(238, 10, 18, 32)
            canvas.drawRoundRect(toastBounds, 30f, 30f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.rgb(56, 189, 248)
            canvas.drawRoundRect(toastBounds, 30f, 30f, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = 29f
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                getString(R.string.quest_menu_exit_hint),
                width / 2f,
                508f,
                paint
            )

            submitImmersiveOverlay(bitmap, width, height)
            controllerHandler.postDelayed(hideImmersiveExitHint, 2500L)
        }
    }

    private fun closeImmersivePinOverlay() {
        immersivePinVisible = false
        pinHorizontalLatched = false
        pinVerticalLatched = false
        nativeSetSettingsOverlay(null, 0, 0)
    }

    private fun handleImmersivePinInput(
        leftX: Float,
        leftY: Float,
        newlyPressed: UInt
    ) {
        if(kotlin.math.abs(leftX) < 0.35f)
            pinHorizontalLatched = false
        if(!pinHorizontalLatched && kotlin.math.abs(leftX) > 0.65f) {
            immersivePinIndex = if(leftX > 0f)
                (immersivePinIndex + 1) % immersivePinDigits.size
            else
                (immersivePinIndex - 1 + immersivePinDigits.size) %
                    immersivePinDigits.size
            pinHorizontalLatched = true
            renderImmersivePinOverlay()
        }
        if(kotlin.math.abs(leftY) < 0.35f)
            pinVerticalLatched = false
        if(!pinVerticalLatched && kotlin.math.abs(leftY) > 0.65f) {
            val delta = if(leftY > 0f) 1 else -1
            immersivePinDigits[immersivePinIndex] =
                (immersivePinDigits[immersivePinIndex] + delta + 10) % 10
            pinVerticalLatched = true
            renderImmersivePinOverlay()
        }
        when {
            newlyPressed and ControllerState.BUTTON_CROSS != 0U -> {
                val pin = immersivePinDigits.joinToString(separator = "")
                closeImmersivePinOverlay()
                runOnUiThread { submitLoginPin(pin) }
            }
            newlyPressed and ControllerState.BUTTON_MOON != 0U -> {
                closeImmersivePinOverlay()
                runOnUiThread { finish() }
            }
            newlyPressed and ControllerState.BUTTON_OPTIONS != 0U -> {
                closeImmersivePinOverlay()
                runOnUiThread { finish() }
            }
        }
    }

    private fun activateImmersiveErrorAction(index: Int) {
        val activeDialog = immersiveErrorDialog ?: return
        closeImmersiveErrorDialog()
        val safeIndex = index.coerceIn(activeDialog.actions.indices)
        runOnUiThread { activeDialog.onAction(safeIndex) }
    }

    private fun handleImmersiveErrorInput(leftY: Float, newlyPressed: UInt) {
        val activeDialog = immersiveErrorDialog ?: return
        if(kotlin.math.abs(leftY) < 0.35f)
            settingsStickLatched = false
        if(!settingsStickLatched && kotlin.math.abs(leftY) > 0.65f) {
            val count = activeDialog.actions.size
            immersiveErrorSelection = if(leftY > 0f)
                (immersiveErrorSelection - 1 + count) % count
            else
                (immersiveErrorSelection + 1) % count
            settingsStickLatched = true
            renderImmersiveErrorOverlay()
        }
        when {
            newlyPressed and ControllerState.BUTTON_CROSS != 0U ->
                activateImmersiveErrorAction(immersiveErrorSelection)
            newlyPressed and ControllerState.BUTTON_MOON != 0U ->
                activateImmersiveErrorAction(activeDialog.actions.lastIndex)
            newlyPressed and ControllerState.BUTTON_OPTIONS != 0U ->
                activateImmersiveErrorAction(activeDialog.actions.lastIndex)
        }
    }

    private fun closeImmersiveSettings() {
        immersiveSettingsVisible = false
        immersiveSettingsLearning = false
        settingsStickLatched = false
        nativeSetSettingsOverlay(null, 0, 0)
        Log.i(TAG, "Immersive stream settings closed.")
    }

    private fun activateImmersiveSetting() {
        val preferences = Preferences(this)
        when(immersiveSettingsSelection) {
            0 -> preferences.simulated3dEnabled = !preferences.simulated3dEnabled
            1 -> {
                val strengths = listOf("low", "medium", "high", "strong")
                val index = strengths.indexOf(preferences.simulated3dIntensity)
                    .coerceAtLeast(0)
                preferences.simulated3dIntensity =
                    strengths[(index + 1) % strengths.size]
            }
            2 -> immersiveSettingsLearning = true
            3 -> {
                closeImmersiveSettings()
                return
            }
        }
        renderImmersiveSettingsOverlay()
    }

    private fun handleImmersiveSettingsInput(
        leftY: Float,
        newlyPressed: UInt
    ) {
        if(immersiveSettingsLearning) {
            val learnedButton = Integer.lowestOneBit(newlyPressed.toInt()).toUInt()
            if(learnedButton != 0U &&
                learnedButton != ControllerState.BUTTON_OPTIONS) {
                Preferences(this).streamSettingsButtonBinding =
                    "quest:$learnedButton"
                immersiveSettingsLearning = false
                renderImmersiveSettingsOverlay()
            }
            return
        }

        if(kotlin.math.abs(leftY) < 0.35f)
            settingsStickLatched = false
        if(!settingsStickLatched && kotlin.math.abs(leftY) > 0.65f) {
            immersiveSettingsSelection =
                if(leftY > 0f)
                    (immersiveSettingsSelection - 1 + 4) % 4
                else
                    (immersiveSettingsSelection + 1) % 4
            settingsStickLatched = true
            renderImmersiveSettingsOverlay()
        }

        when {
            newlyPressed and ControllerState.BUTTON_CROSS != 0U ->
                activateImmersiveSetting()
            newlyPressed and ControllerState.BUTTON_MOON != 0U ->
                closeImmersiveSettings()
            newlyPressed and ControllerState.BUTTON_OPTIONS != 0U ->
                closeImmersiveSettings()
        }
    }

    private fun renderImmersiveSettingsOverlay() {
        if(!immersiveSettingsVisible)
            return

        val width = 960
        val height = 640
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.argb(242, 10, 18, 32)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()),
            36f, 36f, paint)
        paint.color = Color.rgb(56, 189, 248)
        paint.textSize = 52f
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText("Stream Settings", 56f, 78f, paint)

        val preferences = Preferences(this)
        val assignment = ControllerAssignmentLearner.label(
            preferences.streamSettingsButtonBinding)
        val items = arrayOf(
            "AI 2D-to-3D (Experimental): ${if(preferences.simulated3dEnabled) "On" else "Off"}",
            "AI depth strength: ${depthStrengthLabel(preferences.simulated3dIntensity)}",
            "Exit-stream button: $assignment",
            "Close"
        )
        items.forEachIndexed { index, item ->
            val top = 112f + index * 98f
            if(index == immersiveSettingsSelection) {
                paint.color = Color.argb(255, 14, 116, 144)
                canvas.drawRoundRect(RectF(38f, top, 922f, top + 78f),
                    18f, 18f, paint)
            }
            paint.color = Color.WHITE
            paint.textSize = 32f
            paint.typeface = if(index == immersiveSettingsSelection)
                android.graphics.Typeface.DEFAULT_BOLD
            else
                android.graphics.Typeface.DEFAULT
            canvas.drawText(item, 64f, top + 51f, paint)
        }

        paint.color = Color.rgb(148, 163, 184)
        paint.textSize = 25f
        paint.typeface = android.graphics.Typeface.DEFAULT
        val footer = if(immersiveSettingsLearning)
            "Press a Quest controller button now (Menu remains reserved)"
        else
            "Left stick: select    A: change    B or Menu: close"
        canvas.drawText(footer, 56f, 590f, paint)

        submitImmersiveOverlay(bitmap, width, height)
    }

    private fun renderImmersiveErrorOverlay() {
        val activeDialog = immersiveErrorDialog ?: return
        val width = 960
        val height = 640
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.argb(248, 24, 15, 24)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()),
            36f, 36f, paint)
        paint.color = Color.rgb(251, 146, 60)
        paint.textSize = 48f
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText(activeDialog.title, 56f, 74f, paint)

        paint.color = Color.WHITE
        paint.textSize = 28f
        paint.typeface = android.graphics.Typeface.DEFAULT
        var textY = 126f
        activeDialog.message.lines().forEach { paragraph ->
            var line = ""
            paragraph.split(' ').forEach { word ->
                val candidate = if(line.isEmpty()) word else "$line $word"
                if(paint.measureText(candidate) > 840f && line.isNotEmpty()) {
                    canvas.drawText(line, 58f, textY, paint)
                    textY += 38f
                    line = word
                } else {
                    line = candidate
                }
            }
            if(line.isNotEmpty()) {
                canvas.drawText(line, 58f, textY, paint)
                textY += 42f
            }
        }

        val actionTop = maxOf(305f, textY + 18f)
        activeDialog.actions.forEachIndexed { index, action ->
            val top = actionTop + index * 74f
            if(index == immersiveErrorSelection) {
                paint.color = Color.rgb(194, 65, 12)
                canvas.drawRoundRect(RectF(42f, top, 918f, top + 60f),
                    16f, 16f, paint)
            }
            paint.color = Color.WHITE
            paint.textSize = 29f
            paint.typeface = if(index == immersiveErrorSelection)
                android.graphics.Typeface.DEFAULT_BOLD
            else
                android.graphics.Typeface.DEFAULT
            canvas.drawText(action, 66f, top + 40f, paint)
        }

        paint.color = Color.rgb(148, 163, 184)
        paint.textSize = 24f
        paint.typeface = android.graphics.Typeface.DEFAULT
        canvas.drawText(
            "Left stick: select    A: choose    B or Menu: quit",
            56f,
            608f,
            paint
        )
        submitImmersiveOverlay(bitmap, width, height)
    }

    private fun renderImmersivePinOverlay() {
        if(!immersivePinVisible)
            return
        val width = 960
        val height = 640
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.argb(248, 10, 18, 32)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()),
            36f, 36f, paint)
        paint.color = if(immersivePinIncorrect)
            Color.rgb(251, 146, 60)
        else
            Color.rgb(56, 189, 248)
        paint.textSize = 48f
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText(
            if(immersivePinIncorrect) "Incorrect PIN — try again" else "Enter PS5 Link PIN",
            56f,
            78f,
            paint
        )

        paint.color = Color.rgb(203, 213, 225)
        paint.textSize = 27f
        paint.typeface = android.graphics.Typeface.DEFAULT
        canvas.drawText(
            "Use the PIN currently displayed by your PlayStation.",
            56f,
            132f,
            paint
        )

        immersivePinDigits.forEachIndexed { index, digit ->
            val left = 186f + index * 150f
            paint.color = if(index == immersivePinIndex)
                Color.rgb(14, 116, 144)
            else
                Color.rgb(30, 41, 59)
            canvas.drawRoundRect(RectF(left, 210f, left + 112f, 350f),
                18f, 18f, paint)
            paint.color = Color.WHITE
            paint.textSize = 76f
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.drawText(digit.toString(), left + 34f, 310f, paint)
        }

        paint.color = Color.rgb(148, 163, 184)
        paint.textSize = 25f
        paint.typeface = android.graphics.Typeface.DEFAULT
        canvas.drawText(
            "Left/right: select digit    Up/down: change digit",
            120f,
            440f,
            paint
        )
        canvas.drawText(
            "A: connect    B or Menu: quit",
            270f,
            500f,
            paint
        )
        submitImmersiveOverlay(bitmap, width, height)
    }

    private fun submitImmersiveOverlay(bitmap: Bitmap, width: Int, height: Int) {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        bitmap.recycle()
        val rgba = ByteArray(argb.size * 4)
        argb.forEachIndexed { index, pixel ->
            val offset = index * 4
            rgba[offset] = Color.red(pixel).toByte()
            rgba[offset + 1] = Color.green(pixel).toByte()
            rgba[offset + 2] = Color.blue(pixel).toByte()
            rgba[offset + 3] = Color.alpha(pixel).toByte()
        }
        nativeSetSettingsOverlay(rgba, width, height)
    }

    @Keep
    @Suppress("unused")
    private fun onNativeControllerState(
        leftX: Float,
        leftY: Float,
        rightX: Float,
        rightY: Float,
        leftTrigger: Float,
        rightTrigger: Float,
        leftGrip: Float,
        rightGrip: Float,
        buttons: Int
    ) {
        if(!acceptQuestControllerInput)
            return

        val currentButtons = buttons.toUInt()
        val newlyPressed = currentButtons and previousQuestButtons.inv()
        var forwardedButtons = currentButtons

        if(immersivePinVisible) {
            previousQuestButtons = currentButtons
            handleImmersivePinInput(leftX, leftY, newlyPressed)
            viewModel.input.updateQuestControllerState(
                leftX = 0f,
                leftY = 0f,
                rightX = 0f,
                rightY = 0f,
                leftTrigger = 0f,
                rightTrigger = 0f,
                leftGrip = 0f,
                rightGrip = 0f,
                buttons = 0U
            )
            return
        }

        if(immersiveErrorDialog != null) {
            previousQuestButtons = currentButtons
            handleImmersiveErrorInput(leftY, newlyPressed)
            viewModel.input.updateQuestControllerState(
                leftX = 0f,
                leftY = 0f,
                rightX = 0f,
                rightY = 0f,
                leftTrigger = 0f,
                rightTrigger = 0f,
                leftGrip = 0f,
                rightGrip = 0f,
                buttons = 0U
            )
            return
        }

        if(immersiveSettingsVisible) {
            previousQuestButtons = currentButtons
            handleImmersiveSettingsInput(leftY, newlyPressed)
            viewModel.input.updateQuestControllerState(
                leftX = 0f,
                leftY = 0f,
                rightX = 0f,
                rightY = 0f,
                leftTrigger = 0f,
                rightTrigger = 0f,
                leftGrip = 0f,
                rightGrip = 0f,
                buttons = 0U
            )
            return
        }

        if(ControllerAssignmentLearner.captureQuestButtons(newlyPressed))
            forwardedButtons = forwardedButtons and newlyPressed.inv()

        val assignedQuestButton = ControllerAssignmentLearner.normalizedBinding(
            Preferences(this).streamSettingsButtonBinding)
            ?.takeIf { it.startsWith("quest:") }
            ?.substringAfter(':')
            ?.toUIntOrNull()
        if(assignedQuestButton != null &&
            currentButtons and assignedQuestButton != 0U) {
            forwardedButtons = forwardedButtons and assignedQuestButton.inv()
            if(newlyPressed and assignedQuestButton != 0U) {
                runOnUiThread { finish() }
            }
        }

        // Existing Menu chords take precedence over the tap gesture: Menu+X
        // remains Share, Menu+Y remains PS, and Menu+L3 remains touchpad.
        val mappedMenuChordActive = currentButtons and (
            ControllerState.BUTTON_SHARE or
                ControllerState.BUTTON_TOUCHPAD or
                ControllerState.BUTTON_PS) != 0U
        if(mappedMenuChordActive) {
            questMenuGestureSuppressed = true
            questMenuAwaitingSecondTap = false
            questMenuSecondTapInProgress = false
            questMenuHeld = false
            questMenuLongPressTriggered = false
            controllerHandler.removeCallbacks(dispatchQuestMenuSingleTap)
            controllerHandler.removeCallbacks(questMenuLongPress)
        }

        val menuPressed = currentButtons and ControllerState.BUTTON_OPTIONS != 0U
        var sendDoubleTapPs = false
        if(!menuPressed &&
            SystemClock.uptimeMillis() >= questMenuArmAfterUptimeMs)
            questMenuReleasedSinceResume = true

        val freshMenuPress = menuPressed &&
            questMenuReleasedSinceResume &&
            newlyPressed and ControllerState.BUTTON_OPTIONS != 0U
        if(freshMenuPress && !questMenuHeld) {
            if(questMenuAwaitingSecondTap && !questMenuGestureSuppressed) {
                questMenuAwaitingSecondTap = false
                questMenuSecondTapInProgress = true
                controllerHandler.removeCallbacks(dispatchQuestMenuSingleTap)
            }
            questMenuHeld = true
            questMenuLongPressTriggered = false
            if(!questMenuGestureSuppressed) {
                Log.d(TAG, "Quest Menu pressed; starting long-press timer.")
                controllerHandler.postDelayed(questMenuLongPress, 750L)
            }
        } else if(!menuPressed && questMenuHeld) {
            val shortPress = !questMenuLongPressTriggered &&
                !questMenuGestureSuppressed
            questMenuHeld = false
            questMenuLongPressTriggered = false
            controllerHandler.removeCallbacks(questMenuLongPress)
            if(shortPress && questMenuSecondTapInProgress) {
                questMenuSecondTapInProgress = false
                sendDoubleTapPs = true
            } else if(shortPress) {
                questMenuAwaitingSecondTap = true
                controllerHandler.removeCallbacks(dispatchQuestMenuSingleTap)
                controllerHandler.postDelayed(dispatchQuestMenuSingleTap, 350L)
            }
            questMenuGestureSuppressed = false
        } else if(!menuPressed && !mappedMenuChordActive &&
            questMenuGestureSuppressed) {
            // The chord and Menu were released together.
            questMenuGestureSuppressed = false
        }
        // Hold Options while resolving single, double, and long-press gestures.
        forwardedButtons =
            forwardedButtons and ControllerState.BUTTON_OPTIONS.inv()
        previousQuestButtons = currentButtons

        if(sendDoubleTapPs) {
            viewModel.input.pulseQuestControllerButton(ControllerState.BUTTON_PS)
            Log.d(TAG, "Quest Menu double tap sent as the PS button.")
        }
        viewModel.input.updateQuestControllerState(
            leftX = leftX,
            leftY = leftY,
            rightX = rightX,
            rightY = rightY,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            leftGrip = leftGrip,
            rightGrip = rightGrip,
            buttons = forwardedButtons
        )
    }

    override fun onResume() {
        questMenuReleasedSinceResume = false
        questMenuArmAfterUptimeMs = SystemClock.uptimeMillis() + 1500L
        super.onResume()
        initializeVrAfterResume()
        acceptQuestControllerInput = true
        if (isVRInitialized) {
            Log.i(TAG, "VRStreamActivity onResume - Resuming OpenXR 3D VR Render Loop.")
            try {
                nativeStartRenderLoop(null)
            } catch (e: Throwable) {
                Log.w(TAG, "OpenXR Native VR Render Loop resume notice: ${e.message}")
            }
        }
    }

    override fun onPause() {
        acceptQuestControllerInput = false
        questMenuHeld = false
        questMenuLongPressTriggered = false
        questMenuAwaitingSecondTap = false
        questMenuSecondTapInProgress = false
        questMenuGestureSuppressed = false
        questMenuReleasedSinceResume = false
        previousQuestButtons = 0U
        controllerHandler.removeCallbacks(questMenuLongPress)
        controllerHandler.removeCallbacks(dispatchQuestMenuSingleTap)
        cancelImmersiveExitHint()
        if(immersiveSettingsVisible)
            closeImmersiveSettings()
        super.onPause()
        Log.i(TAG, "VRStreamActivity onPause - Pausing OpenXR 3D VR Session.")
    }

    override fun onDestroy() {
        DepthAnythingV2Bridge.shutdown()
        super.onDestroy()
        Log.i(TAG, "VRStreamActivity onDestroy - Stopping OpenXR 3D VR Engine.")
        try {
            nativeStopVR()
        } catch (e: Throwable) {
            Log.w(TAG, "OpenXR Native VR stop notice: ${e.message}")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (isVRInitialized) {
            // We ignore the standard Android SurfaceView because we are using our OpenXR Surface Swapchain.
            Log.i(TAG, "VRStreamActivity surfaceCreated - ignored because we use OpenXR surface.")
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {}
}
