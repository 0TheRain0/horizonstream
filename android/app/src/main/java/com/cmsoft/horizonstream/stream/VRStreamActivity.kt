// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.cmsoft.horizonstream.stream

import android.app.PendingIntent
import android.content.Intent
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
import com.cmsoft.horizonstream.common.Preferences
import com.cmsoft.horizonstream.depth.DepthAnythingV2Bridge
import com.cmsoft.horizonstream.lib.ControllerState
import com.cmsoft.horizonstream.main.MainActivity

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
    private external fun nativeSetImmersiveViewOptions(curved: Boolean, scale: Float)
    private external fun nativeSetImmersivePointerEnabled(enabled: Boolean)
    private external fun nativeSetStereoConversionEnabled(
        enabled: Boolean,
        depthIntensity: Float
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
    @Volatile private var immersivePointerX = 0.5f
    @Volatile private var immersivePointerY = 0.5f
    @Volatile private var immersivePointerActive = false
    @Volatile private var immersivePointerPressed = false
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
    @Volatile
    private var finishRequested = false
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
    private var previousExternalControllerButtons = 0U
    // Bluetooth motion events can arrive much faster than UI-frame updates.
    // Latch the axis before posting work to the UI thread so one deflection
    // can only advance one row until the stick returns to its dead zone.
    private var externalNavigationStickLatched = false
    private var externalMenuHeld = false
    private var externalMenuLongPressTriggered = false
    private var externalMenuAwaitingSecondTap = false
    private var externalMenuSecondTapInProgress = false
    private val externalMenuLongPress = Runnable {
        if(externalMenuHeld && isVRInitialized && !isFinishing && !isDestroyed) {
            externalMenuLongPressTriggered = true
            externalMenuAwaitingSecondTap = false
            externalMenuSecondTapInProgress = false
            controllerHandler.removeCallbacks(dispatchExternalMenuSingleTap)
            viewModel.input.pulseQuestControllerButton(
                ControllerState.BUTTON_OPTIONS, force = true)
            externalMenuHeld = false
            runOnUiThread { openImmersiveControls() }
        }
    }
    private val dispatchExternalMenuSingleTap = Runnable {
        if(externalMenuAwaitingSecondTap && isVRInitialized) {
            externalMenuAwaitingSecondTap = false
            showImmersiveExitHint()
            viewModel.input.pulseQuestControllerButton(
                ControllerState.BUTTON_OPTIONS, force = true)
        }
    }
    private val questMenuLongPress = Runnable {
        if (questMenuHeld && acceptQuestControllerInput) {
            questMenuLongPressTriggered = true
            questMenuAwaitingSecondTap = false
            questMenuSecondTapInProgress = false
            controllerHandler.removeCallbacks(dispatchQuestMenuSingleTap)
            // Preserve the familiar single Menu action (Options) first, then
            // expose the spatial controls without leaving the game.
            viewModel.input.pulseQuestControllerButton(
                ControllerState.BUTTON_OPTIONS,
                force = true)
            questMenuHeld = false
            Log.i(TAG, "Quest Menu long-press recognized; opening immersive stream controls.")
            runOnUiThread { openImmersiveControls() }
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

    /**
     * Return from the volumetric task to the app's 2D panel. Horizon OS needs
     * the panel launch delivered through Home; directly starting MainActivity
     * from a volumetric task is subject to background-launch hardening and can
     * leave the user looking at the system shell.
     */
    private fun exitImmersiveStream() {
        if (finishRequested || isFinishing || isDestroyed)
            return
        finishRequested = true
        acceptQuestControllerInput = false
        questMenuHeld = false
        questMenuLongPressTriggered = false
        questMenuAwaitingSecondTap = false
        questMenuSecondTapInProgress = false
        questMenuGestureSuppressed = false
        controllerHandler.removeCallbacks(questMenuLongPress)
        controllerHandler.removeCallbacks(dispatchQuestMenuSingleTap)
        cancelImmersiveExitHint()

        runCatching {
            val panelIntent = Intent(applicationContext, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory("com.oculus.intent.category.2D")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val panelPendingIntent = PendingIntent.getActivity(
                applicationContext,
                0,
                panelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("extra_launch_in_home_pending_intent", panelPendingIntent)
            }
            startActivity(homeIntent)
            // The panel is queued by Home, so the volumetric task can be
            // removed immediately without racing the panel launch.
            finishAndRemoveTask()
        }.onFailure {
            Log.w(TAG, "Unable to hand the immersive task back to the main panel.", it)
            if (!isFinishing)
                super.finish()
        }
    }

    override fun finish() {
        // StreamActivity can receive a quit event during the same pause that
        // the controller long-press is finishing this activity.
        if (finishRequested)
            return
        finishRequested = true
        super.finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.input.controllerStateInterceptor = ::interceptImmersiveControllerInput
        Log.i(TAG, "VRStreamActivity created; OpenXR will initialize after resume and before stream startup.")
    }

    /**
     * Bluetooth gamepads are delivered through Android input events, whereas
     * Quest Touch input arrives through OpenXR. Consume the former while a
     * modal immersive panel is open, so both controller types navigate the
     * same UI and neither leaks menu actions into the active game.
     */
    private fun interceptImmersiveControllerInput(
        state: ControllerState,
        isQuestInput: Boolean
    ): Boolean {
        if(isQuestInput)
            return false
        if(!isVRInitialized) {
            previousExternalControllerButtons = 0U
            return false
        }

        if(immersiveSettingsVisible || immersiveErrorDialog != null || immersivePinVisible) {
            val leftX = state.leftX.toFloat() / Short.MAX_VALUE.toFloat()
            val rawLeftY = state.leftY.toFloat() / Short.MAX_VALUE.toFloat()
            var navigationButtons =
                state.buttons and previousExternalControllerButtons.inv()
            previousExternalControllerButtons = state.buttons
            if(kotlin.math.abs(rawLeftY) < 0.35f) {
                externalNavigationStickLatched = false
            } else if(!externalNavigationStickLatched && kotlin.math.abs(rawLeftY) > 0.65f) {
                // Android reports a physical stick's up direction as negative
                // Y. Translate it to the menu's explicit D-pad direction.
                navigationButtons = navigationButtons or if(rawLeftY < 0f)
                    ControllerState.BUTTON_DPAD_UP
                else
                    ControllerState.BUTTON_DPAD_DOWN
                externalNavigationStickLatched = true
            }
            if(state.buttons and ControllerState.BUTTON_OPTIONS == 0U)
                viewModel.input.suppressedPhysicalButtons = 0U
            runOnUiThread {
                when {
                    immersivePinVisible -> handleImmersivePinInput(leftX, 0f, navigationButtons)
                    immersiveErrorDialog != null -> handleImmersiveErrorInput(0f, navigationButtons)
                    immersiveSettingsVisible -> handleImmersiveSettingsInput(0f, navigationButtons)
                }
            }
            return true
        }

        val currentButtons = state.buttons
        val newlyPressed = currentButtons and previousExternalControllerButtons.inv()
        previousExternalControllerButtons = currentButtons
        val menuPressed = currentButtons and ControllerState.BUTTON_OPTIONS != 0U
        var consumeEvent = menuPressed
        if(menuPressed && newlyPressed and ControllerState.BUTTON_OPTIONS != 0U) {
            viewModel.input.suppressedPhysicalButtons =
                viewModel.input.suppressedPhysicalButtons or ControllerState.BUTTON_OPTIONS
            if(externalMenuAwaitingSecondTap) {
                externalMenuAwaitingSecondTap = false
                externalMenuSecondTapInProgress = true
                controllerHandler.removeCallbacks(dispatchExternalMenuSingleTap)
            }
            externalMenuHeld = true
            externalMenuLongPressTriggered = false
            controllerHandler.postDelayed(externalMenuLongPress, 750L)
        } else if(!menuPressed && externalMenuHeld) {
            consumeEvent = true
            externalMenuHeld = false
            viewModel.input.suppressedPhysicalButtons =
                viewModel.input.suppressedPhysicalButtons and ControllerState.BUTTON_OPTIONS.inv()
            controllerHandler.removeCallbacks(externalMenuLongPress)
            if(!externalMenuLongPressTriggered && externalMenuSecondTapInProgress) {
                externalMenuSecondTapInProgress = false
                viewModel.input.pulseQuestControllerButton(
                    ControllerState.BUTTON_PS, force = true)
            } else if(!externalMenuLongPressTriggered) {
                externalMenuAwaitingSecondTap = true
                controllerHandler.removeCallbacks(dispatchExternalMenuSingleTap)
                controllerHandler.postDelayed(dispatchExternalMenuSingleTap, 350L)
            }
            externalMenuLongPressTriggered = false
        }
        return consumeEvent
    }

    /**
     * Quest grants an immersive OpenXR session only after the activity is resumed. This
     * hook is dispatched by StreamActivity after super.onResume(), but before it starts
     * Chiaki, so MediaCodec is configured with the OpenXR SurfaceTexture from frame one.
     */
    override fun prepareStreamOnResume() {
        if (vrInitializationAttempted || isFinishing || isDestroyed) return
        vrInitializationAttempted = true
        Log.i(TAG, "Preparing OpenXR decoder surface before starting the stream.")

        try {
            val profile = viewModel.session.connectInfo.videoProfile
            val preferences = com.cmsoft.horizonstream.common.Preferences(this)
            val xrSurface = nativeInitVR(
                this,
                profile.width,
                profile.height,
                preferences.simulated3dEnabled,
                simulated3dIntensity(preferences)
            )
            isVRInitialized = xrSurface != null
            if (xrSurface != null) {
                Log.i(TAG, "OpenXR initialized successfully. Received Android Surface Swapchain.")
                nativeSetImmersiveViewOptions(
                    preferences.curvedViewEnabled,
                    preferences.immersiveViewScale)
                applySimulated3dSetting(preferences)
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

    private fun immersiveSettingsItemCount(): Int = 7

    private fun openImmersiveControls() {
        if (isFinishing || isDestroyed || !isVRInitialized)
            return
        cancelImmersiveExitHint()
        immersiveErrorDialog = null
        immersivePinVisible = false
        immersiveSettingsVisible = true
        immersiveSettingsSelection = 0
        externalNavigationStickLatched = false
        immersivePointerActive = false
        immersivePointerPressed = false
        nativeSetImmersivePointerEnabled(true)
        renderImmersiveSettingsOverlay()
    }

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
            previousExternalControllerButtons = 0U
            externalNavigationStickLatched = false
            nativeSetImmersivePointerEnabled(true)
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
            previousExternalControllerButtons = 0U
            externalNavigationStickLatched = false
            nativeSetImmersivePointerEnabled(false)
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
            previousExternalControllerButtons = 0U
            externalNavigationStickLatched = false
            nativeSetImmersivePointerEnabled(false)
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
        previousExternalControllerButtons = 0U
        externalNavigationStickLatched = false
        viewModel.input.suppressedPhysicalButtons = 0U
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
        previousExternalControllerButtons = 0U
        externalNavigationStickLatched = false
        viewModel.input.suppressedPhysicalButtons = 0U
        pinHorizontalLatched = false
        pinVerticalLatched = false
        nativeSetSettingsOverlay(null, 0, 0)
    }

    private fun handleImmersivePinInput(
        leftX: Float,
        leftY: Float,
        newlyPressed: UInt
    ) {
        val dpadLeft = newlyPressed and ControllerState.BUTTON_DPAD_LEFT != 0U
        val dpadRight = newlyPressed and ControllerState.BUTTON_DPAD_RIGHT != 0U
        val dpadUp = newlyPressed and ControllerState.BUTTON_DPAD_UP != 0U
        val dpadDown = newlyPressed and ControllerState.BUTTON_DPAD_DOWN != 0U
        if(kotlin.math.abs(leftX) < 0.35f)
            pinHorizontalLatched = false
        if(dpadLeft || dpadRight ||
            (!pinHorizontalLatched && kotlin.math.abs(leftX) > 0.65f)) {
            immersivePinIndex = if(dpadRight || (!dpadLeft && leftX > 0f))
                (immersivePinIndex + 1) % immersivePinDigits.size
            else
                (immersivePinIndex - 1 + immersivePinDigits.size) %
                    immersivePinDigits.size
            pinHorizontalLatched = true
            renderImmersivePinOverlay()
        }
        if(kotlin.math.abs(leftY) < 0.35f)
            pinVerticalLatched = false
        if(dpadUp || dpadDown ||
            (!pinVerticalLatched && kotlin.math.abs(leftY) > 0.65f)) {
            val delta = if(dpadUp || (!dpadDown && leftY > 0f)) 1 else -1
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
        val dpadUp = newlyPressed and ControllerState.BUTTON_DPAD_UP != 0U
        val dpadDown = newlyPressed and ControllerState.BUTTON_DPAD_DOWN != 0U
        if(kotlin.math.abs(leftY) < 0.35f)
            settingsStickLatched = false
        if(dpadUp || dpadDown ||
            (!settingsStickLatched && kotlin.math.abs(leftY) > 0.65f)) {
            val count = activeDialog.actions.size
            immersiveErrorSelection = if(dpadUp || (!dpadDown && leftY > 0f))
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
        previousExternalControllerButtons = 0U
        externalNavigationStickLatched = false
        viewModel.input.suppressedPhysicalButtons = 0U
        immersivePointerActive = false
        immersivePointerPressed = false
        nativeSetImmersivePointerEnabled(false)
        settingsStickLatched = false
        nativeSetSettingsOverlay(null, 0, 0)
        Log.i(TAG, "Immersive stream settings closed.")
    }

    private fun simulated3dIntensity(preferences: Preferences): Float = when(
        preferences.simulated3dIntensity
    ) {
        "low" -> 0.008f
        "high" -> 0.025f
        "strong" -> 0.070f
        else -> 0.015f
    }

    private fun applySimulated3dSetting(preferences: Preferences) {
        val enabled = preferences.simulated3dEnabled
        nativeSetStereoConversionEnabled(enabled, simulated3dIntensity(preferences))
        if(enabled) {
            DepthAnythingV2Bridge.initialize(
                context = this,
                onReady = { nativeSetDepthPipelineReady(true) },
                onDepthMap = { map, width, height ->
                    nativeSetDepthMap(map, width, height)
                }
            )
        } else {
            nativeSetDepthPipelineReady(false)
        }
    }

    private fun activateImmersiveSetting() {
        val preferences = Preferences(this)
        when(immersiveSettingsSelection) {
            0 -> {
                val sizes = listOf(0.8f, 1.0f, 1.2f, 1.4f)
                val index = sizes.indices.minByOrNull {
                    kotlin.math.abs(sizes[it] - preferences.immersiveViewScale)
                } ?: 1
                preferences.immersiveViewScale = sizes[(index + 1) % sizes.size]
                nativeSetImmersiveViewOptions(
                    preferences.curvedViewEnabled,
                    preferences.immersiveViewScale)
            }
            1 -> {
                preferences.curvedViewEnabled = !preferences.curvedViewEnabled
                nativeSetImmersiveViewOptions(
                    preferences.curvedViewEnabled,
                    preferences.immersiveViewScale)
            }
            2 -> preferences.questControllerEmulationEnabled =
                !preferences.questControllerEmulationEnabled
            3 -> {
                preferences.simulated3dEnabled = !preferences.simulated3dEnabled
                applySimulated3dSetting(preferences)
            }
            4 -> {
                val strengths = listOf("low", "medium", "high", "strong")
                val index = strengths.indexOf(preferences.simulated3dIntensity)
                    .coerceAtLeast(0)
                preferences.simulated3dIntensity =
                    strengths[(index + 1) % strengths.size]
                if(preferences.simulated3dEnabled)
                    applySimulated3dSetting(preferences)
            }
            5 -> {
                closeImmersiveSettings()
                return
            }
            6 -> {
                closeImmersiveSettings()
                exitImmersiveStream()
                return
            }
        }
        renderImmersiveSettingsOverlay()
    }

    private fun handleImmersiveSettingsInput(
        leftY: Float,
        newlyPressed: UInt
    ) {
        val dpadUp = newlyPressed and ControllerState.BUTTON_DPAD_UP != 0U
        val dpadDown = newlyPressed and ControllerState.BUTTON_DPAD_DOWN != 0U
        if(kotlin.math.abs(leftY) < 0.35f)
            settingsStickLatched = false
        if(dpadUp || dpadDown ||
            (!settingsStickLatched && kotlin.math.abs(leftY) > 0.65f)) {
            immersiveSettingsSelection =
                if(dpadUp || (!dpadDown && leftY > 0f))
                    (immersiveSettingsSelection - 1 + immersiveSettingsItemCount()) % immersiveSettingsItemCount()
                else
                    (immersiveSettingsSelection + 1) % immersiveSettingsItemCount()
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
        paint.textSize = 48f
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText("Stream Settings", 56f, 78f, paint)

        val preferences = Preferences(this)
        val sizeLabel = "${(preferences.immersiveViewScale * 100f).toInt()}%"
        val items = arrayOf(
            "Screen size: $sizeLabel",
            "Curved immersive screen: ${if(preferences.curvedViewEnabled) "On" else "Off"}",
            "Quest controller support: ${if(preferences.questControllerEmulationEnabled) "On" else "Off"}",
            "AI 2D-to-3D: ${if(preferences.simulated3dEnabled) "On" else "Off"}",
            "AI depth strength: ${depthStrengthLabel(preferences.simulated3dIntensity)}",
            "Close",
            "Exit streaming"
        )
        items.forEachIndexed { index, item ->
            val top = 94f + index * 68f
            if(index == immersiveSettingsSelection) {
                paint.color = Color.argb(255, 14, 116, 144)
                canvas.drawRoundRect(RectF(38f, top, 922f, top + 56f),
                    18f, 18f, paint)
            }
            paint.color = Color.WHITE
            paint.textSize = 27f
            paint.typeface = if(index == immersiveSettingsSelection)
                android.graphics.Typeface.DEFAULT_BOLD
            else
                android.graphics.Typeface.DEFAULT
            canvas.drawText(item, 64f, top + 37f, paint)
        }

        paint.color = Color.rgb(148, 163, 184)
        paint.textSize = 22f
        paint.typeface = android.graphics.Typeface.DEFAULT
        val footer = "Aim + trigger: choose    D-pad / left stick: select    B/Menu: close"
        canvas.drawText(footer, 56f, 585f, paint)

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

    private fun activateImmersiveSettingAt(x: Float, y: Float) {
        if (!immersiveSettingsVisible)
            return
        val pixelX = ((x - 0.125f) / 0.75f).coerceIn(0f, 1f) * 960f
        val pixelY = ((y - 0.1f) / 0.8f).coerceIn(0f, 1f) * 640f
        if (pixelX < 38f || pixelX > 922f)
            return
        val index = ((pixelY - 94f) / 68f).toInt()
        if (index !in 0 until immersiveSettingsItemCount())
            return
        val rowTop = 94f + index * 68f
        if (pixelY < rowTop || pixelY > rowTop + 56f)
            return
        immersiveSettingsSelection = index
        activateImmersiveSetting()
    }

    @Keep
    @Suppress("unused")
    private fun onNativeControllerPointer(
        x: Float,
        y: Float,
        trigger: Float,
        active: Boolean
    ) {
        val wasPressed = immersivePointerPressed
        immersivePointerX = x
        immersivePointerY = y
        immersivePointerActive = active && immersiveSettingsVisible
        immersivePointerPressed = immersivePointerActive && trigger >= 0.62f
        val released = wasPressed && !immersivePointerPressed
        if (!immersiveSettingsVisible)
            return
        if (released && active) {
            runOnUiThread { activateImmersiveSettingAt(x, y) }
        }
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
        nativeSetImmersivePointerEnabled(false)
        questMenuHeld = false
        questMenuLongPressTriggered = false
        questMenuAwaitingSecondTap = false
        questMenuSecondTapInProgress = false
        questMenuGestureSuppressed = false
        questMenuReleasedSinceResume = false
        previousQuestButtons = 0U
        previousExternalControllerButtons = 0U
        externalNavigationStickLatched = false
        controllerHandler.removeCallbacks(questMenuLongPress)
        controllerHandler.removeCallbacks(dispatchQuestMenuSingleTap)
        controllerHandler.removeCallbacks(externalMenuLongPress)
        controllerHandler.removeCallbacks(dispatchExternalMenuSingleTap)
        externalMenuHeld = false
        externalMenuLongPressTriggered = false
        externalMenuAwaitingSecondTap = false
        externalMenuSecondTapInProgress = false
        viewModel.input.suppressedPhysicalButtons = 0U
        cancelImmersiveExitHint()
        if(immersiveSettingsVisible)
            closeImmersiveSettings()
        super.onPause()
        Log.i(TAG, "VRStreamActivity onPause - Pausing OpenXR 3D VR Session.")
    }

    override fun onDestroy() {
        viewModel.input.controllerStateInterceptor = null
        viewModel.input.suppressedPhysicalButtons = 0U
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
