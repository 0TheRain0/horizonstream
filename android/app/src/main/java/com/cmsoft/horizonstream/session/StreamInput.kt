package com.cmsoft.horizonstream.session

import android.content.Context
import android.hardware.*
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.cmsoft.horizonstream.common.Preferences
import com.cmsoft.horizonstream.lib.ControllerState

class StreamInput(val context: Context, val preferences: Preferences)
{
	var controllerStateChangedCallback: ((ControllerState) -> Unit)? = null
	/**
	 * Lets a modal stream UI consume a physical-controller update before it is
	 * forwarded to Remote Play. This is used by the immersive controls so a
	 * paired Bluetooth gamepad can navigate them without also operating the
	 * game behind the panel.
	 */
	var controllerStateInterceptor: ((ControllerState, Boolean) -> Boolean)? = null
	@Volatile
	var suppressedPhysicalButtons: UInt = 0U
	private var gameplayInputSuspended = false
	private var physicalInputNeedsNeutral = false
	private var openXrOwnsQuestInput = false
	private val ignoredQuestDevices = mutableSetOf<Int>()
	private var lastSuppressedButtons: UInt? = null

	val controllerState: ControllerState get() = combinedControllerState(false)

	private fun combinedControllerState(suppressPhysicalButtons: Boolean): ControllerState
	{
		val controllerState = if(physicalInputNeedsNeutral)
			sensorControllerState or ControllerState()
		else
			sensorControllerState or keyControllerState or motionControllerState
		if(suppressPhysicalButtons) {
			controllerState.buttons = controllerState.buttons and
				suppressedPhysicalButtons.inv()
		}

		val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
		@Suppress("DEPRECATION")
		when(windowManager.defaultDisplay.rotation)
		{
			Surface.ROTATION_90 -> {
				controllerState.accelX *= -1.0f
				controllerState.accelZ *= -1.0f
				controllerState.gyroX *= -1.0f
				controllerState.gyroZ *= -1.0f
				controllerState.orientX *= -1.0f
				controllerState.orientZ *= -1.0f
			}
			else -> {}
		}

		// prioritize motion controller's l2 and r2 over key
		// (some controllers send only key, others both but key earlier than full press)
		if(!physicalInputNeedsNeutral && motionControllerState.l2State > 0U)
			controllerState.l2State = motionControllerState.l2State
		if(!physicalInputNeedsNeutral && motionControllerState.r2State > 0U)
			controllerState.r2State = motionControllerState.r2State

		return (controllerState or touchControllerState or questControllerState).also {
			// Keep synthetic gesture buttons asserted across OpenXR frames long
			// enough for Chiaki's input sender and the console to observe them.
			it.buttons = it.buttons or pulsedQuestButtons
		}
	}

	private val sensorControllerState = ControllerState() // from Motion Sensors
	private var keyControllerState = ControllerState() // from KeyEvents
	private var motionControllerState = ControllerState() // from MotionEvents
	private var questControllerState = ControllerState() // from OpenXR Touch controllers
	private var keyControllerDeviceId = -1
	private var motionControllerDeviceId = -1
	@Volatile private var pulsedQuestButtons = 0U
	private val questButtonPulseHandler = Handler(Looper.getMainLooper())
	private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager

	@Synchronized
	fun useOpenXrQuestInput(enabled: Boolean) {
		openXrOwnsQuestInput = enabled
		if(enabled) {
			if(isDuplicateQuestDevice(keyControllerDeviceId)) {
				keyControllerState = ControllerState()
				keyControllerDeviceId = -1
			}
			if(isDuplicateQuestDevice(motionControllerDeviceId)) {
				motionControllerState = ControllerState()
				motionControllerDeviceId = -1
			}
			controllerStateUpdated(isQuestInput = true)
		}
	}

	private fun isDuplicateQuestDevice(deviceId: Int): Boolean {
		if(!openXrOwnsQuestInput || deviceId < 0)
			return false
		val device = inputManager.getInputDevice(deviceId) ?: return false
		// Oculus Touch is also exposed as an Android joystick on Horizon OS.
		// Its cached Android events must not be ORed with authoritative XR input.
		if(device.vendorId != 0x2833)
			return false
		if(ignoredQuestDevices.add(deviceId))
			android.util.Log.i("StreamInput", "OpenXR owns Touch input; ignoring duplicate Android device $deviceId (${device.name})")
		return true
	}
	private val inputDeviceListener = object: InputManager.InputDeviceListener {
		override fun onInputDeviceAdded(deviceId: Int) = Unit

		override fun onInputDeviceChanged(deviceId: Int) = releaseDeviceInput(deviceId)

		override fun onInputDeviceRemoved(deviceId: Int) = releaseDeviceInput(deviceId)
	}
	private val inputDeviceLifecycleObserver = object: LifecycleObserver {
		@OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
		fun onResume() {
			inputManager.registerInputDeviceListener(inputDeviceListener, questButtonPulseHandler)
		}

		@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
		fun onPause() {
			inputManager.unregisterInputDeviceListener(inputDeviceListener)
			releaseAllControllerInputs()
		}
	}
	var touchControllerState = ControllerState()
		set(value)
		{
			field = value
			controllerStateUpdated()
		}

	private val swapCrossMoon = preferences.swapCrossMoon

	private val sensorEventListener = object: SensorEventListener {
		override fun onSensorChanged(event: SensorEvent)
		{
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> {
					sensorControllerState.accelX = event.values[1] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelY = event.values[2] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelZ = event.values[0] / SensorManager.GRAVITY_EARTH
				}
				Sensor.TYPE_GYROSCOPE -> {
					sensorControllerState.gyroX = event.values[1]
					sensorControllerState.gyroY = event.values[2]
					sensorControllerState.gyroZ = event.values[0]
				}
				Sensor.TYPE_ROTATION_VECTOR -> {
					val q = floatArrayOf(0f, 0f, 0f, 0f)
					SensorManager.getQuaternionFromVector(q, event.values)
					sensorControllerState.orientX = q[2]
					sensorControllerState.orientY = q[3]
					sensorControllerState.orientZ = q[1]
					sensorControllerState.orientW = q[0]
				}
				else -> return
			}
			controllerStateUpdated()
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}

	private val motionLifecycleObserver = object: LifecycleObserver {
		@OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
		fun onResume()
		{
			val samplingPeriodUs = 4000
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			listOfNotNull(
				sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
				sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
				sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
			).forEach {
				sensorManager.registerListener(sensorEventListener, it, samplingPeriodUs)
			}
		}

		@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
		fun onPause()
		{
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			sensorManager.unregisterListener(sensorEventListener)
		}
	}

	fun observe(lifecycleOwner: LifecycleOwner)
	{
		lifecycleOwner.lifecycle.addObserver(inputDeviceLifecycleObserver)
		if(preferences.motionEnabled)
			lifecycleOwner.lifecycle.addObserver(motionLifecycleObserver)
	}

	@Synchronized
	private fun releaseDeviceInput(deviceId: Int) {
		var changed = false
		if(keyControllerDeviceId == deviceId) {
			keyControllerState = ControllerState()
			keyControllerDeviceId = -1
			changed = true
		}
		if(motionControllerDeviceId == deviceId) {
			motionControllerState = ControllerState()
			motionControllerDeviceId = -1
			changed = true
		}
		if(changed)
			controllerStateUpdated()
	}

	@Synchronized
	fun releaseAllControllerInputs() {
		keyControllerState = ControllerState()
		motionControllerState = ControllerState()
		questControllerState = ControllerState()
		keyControllerDeviceId = -1
		motionControllerDeviceId = -1
		pulsedQuestButtons = 0U
		questButtonPulseHandler.removeCallbacksAndMessages(null)
		// A release must reach Chiaki even when an immersive menu is consuming
		// controller events or the OpenXR session has stopped producing samples.
		lastSuppressedButtons = 0U
		controllerStateChangedCallback?.invoke(ControllerState())
	}

	@Synchronized
	fun isPhysicalControllerNeutral(): Boolean {
		val state = keyControllerState or motionControllerState
		return state.buttons == 0U && state.l2State < 25U && state.r2State < 25U &&
			kotlin.math.abs(state.leftX.toInt()) < 6500 &&
			kotlin.math.abs(state.leftY.toInt()) < 6500 &&
			kotlin.math.abs(state.rightX.toInt()) < 6500 &&
			kotlin.math.abs(state.rightY.toInt()) < 6500
	}

	@Synchronized
	fun setGameplayInputSuspended(suspended: Boolean) {
		if(gameplayInputSuspended == suspended)
			return
		gameplayInputSuspended = suspended
		if(!suspended) {
			// Keep physical state until its real release arrives. Clearing it
			// makes a held button's next repeat look like a fresh press.
			physicalInputNeedsNeutral = !isPhysicalControllerNeutral()
			questControllerState = ControllerState()
		}
		// Preserve the short synthetic Options press that opens the menu, but
		// release every held gameplay button, trigger, and stick immediately.
		lastSuppressedButtons = if(suspended) pulsedQuestButtons else null
		controllerStateChangedCallback?.invoke(ControllerState(buttons = pulsedQuestButtons))
	}

	@Synchronized
	private fun controllerStateUpdated(isQuestInput: Boolean = false)
	{
		// The immersive menu should see only Android gamepad input here. Mixing
		// Quest buttons into this state can make Bluetooth navigation appear held.
		val physicalState = keyControllerState or motionControllerState
		if(!gameplayInputSuspended && physicalInputNeedsNeutral && !isQuestInput &&
			isPhysicalControllerNeutral())
			physicalInputNeedsNeutral = false
		val intercepted = controllerStateInterceptor?.invoke(physicalState, isQuestInput) == true
		if(gameplayInputSuspended || intercepted) {
			// A Bluetooth stick can report at a high rate while navigating the
			// menu. Send only transitions, not a neutral packet per sample.
			if(lastSuppressedButtons != pulsedQuestButtons) {
				lastSuppressedButtons = pulsedQuestButtons
				controllerStateChangedCallback?.invoke(
					ControllerState(buttons = pulsedQuestButtons))
			}
		} else {
			lastSuppressedButtons = null
			controllerStateChangedCallback?.invoke(combinedControllerState(true))
		}
	}

	@Synchronized
	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if(isDuplicateQuestDevice(event.deviceId))
			return true
		//Log.i("StreamSession", "key event $event")
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
			return false

		when(event.keyCode)
		{
			KeyEvent.KEYCODE_BUTTON_L2 -> {
				if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0)
					return true
				trackKeyDevice(event.deviceId)
				keyControllerState.l2State = if(event.action == KeyEvent.ACTION_DOWN) UByte.MAX_VALUE else 0U
				controllerStateUpdated()
				return true
			}
			KeyEvent.KEYCODE_BUTTON_R2 -> {
				if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0)
					return true
				trackKeyDevice(event.deviceId)
				keyControllerState.r2State = if(event.action == KeyEvent.ACTION_DOWN) UByte.MAX_VALUE else 0U
				controllerStateUpdated()
				return true
			}
		}

		val buttonMask: UInt = when(event.keyCode)
		{
			// Some controllers expose the D-pad as keys rather than hat axes.
			KeyEvent.KEYCODE_DPAD_LEFT -> ControllerState.BUTTON_DPAD_LEFT
			KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerState.BUTTON_DPAD_RIGHT
			KeyEvent.KEYCODE_DPAD_UP -> ControllerState.BUTTON_DPAD_UP
			KeyEvent.KEYCODE_DPAD_DOWN -> ControllerState.BUTTON_DPAD_DOWN
			KeyEvent.KEYCODE_BUTTON_A -> if(swapCrossMoon) ControllerState.BUTTON_MOON else ControllerState.BUTTON_CROSS
			KeyEvent.KEYCODE_BUTTON_B -> if(swapCrossMoon) ControllerState.BUTTON_CROSS else ControllerState.BUTTON_MOON
			KeyEvent.KEYCODE_BUTTON_X -> if(swapCrossMoon) ControllerState.BUTTON_PYRAMID else ControllerState.BUTTON_BOX
			KeyEvent.KEYCODE_BUTTON_Y -> if(swapCrossMoon) ControllerState.BUTTON_BOX else ControllerState.BUTTON_PYRAMID
			KeyEvent.KEYCODE_BUTTON_L1 -> ControllerState.BUTTON_L1
			KeyEvent.KEYCODE_BUTTON_R1 -> ControllerState.BUTTON_R1
			KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerState.BUTTON_L3
			KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerState.BUTTON_R3
			KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerState.BUTTON_SHARE
			KeyEvent.KEYCODE_BUTTON_START -> ControllerState.BUTTON_OPTIONS
			KeyEvent.KEYCODE_BUTTON_C -> ControllerState.BUTTON_PS
			KeyEvent.KEYCODE_BUTTON_MODE -> ControllerState.BUTTON_PS
			else -> return false
		}

		// Only mapped gamepad keys are consumed. A held control remains down
		// until ACTION_UP; repeat-down is not a new press after a menu transition.
		if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0)
			return true
		trackKeyDevice(event.deviceId)
		keyControllerState.buttons = keyControllerState.buttons.run {
			when(event.action)
			{
				KeyEvent.ACTION_DOWN -> this or buttonMask
				KeyEvent.ACTION_UP -> this and buttonMask.inv()
				else -> this
			}
		}

		controllerStateUpdated()
		return true
	}

	private fun trackKeyDevice(deviceId: Int) {
		if(deviceId >= 0 && keyControllerDeviceId != deviceId) {
			keyControllerState = ControllerState()
			keyControllerDeviceId = deviceId
		}
	}

	@Synchronized
	fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(isDuplicateQuestDevice(event.deviceId))
			return true
		// Android gamepads do not all report the complete SOURCE_JOYSTICK
		// value.  In particular, Bluetooth HID controllers on Horizon OS can
		// expose only the joystick source class (with no source-id bits).  The
		// old flat streaming path accepted that form; keep doing so here so a
		// DualSense remains usable while the immersive Quest actions are active.
		val joystickSource = event.source and InputDevice.SOURCE_CLASS_JOYSTICK ==
			InputDevice.SOURCE_CLASS_JOYSTICK
		if(!joystickSource && !event.isFromSource(InputDevice.SOURCE_GAMEPAD))
			return false
		if(event.deviceId >= 0 && motionControllerDeviceId != event.deviceId) {
			motionControllerState = ControllerState()
			motionControllerDeviceId = event.deviceId
		}
		if(event.actionMasked == MotionEvent.ACTION_CANCEL) {
			motionControllerState = ControllerState()
			controllerStateUpdated()
			return true
		}

		fun axis(axis: Int, fallbackAxis: Int? = null): Float {
			val primaryRange = event.device?.getMotionRange(axis, event.source)
			if(primaryRange != null) {
				val value = event.getAxisValue(axis)
				return if(kotlin.math.abs(value) <= primaryRange.flat) 0f else value
			}
			return fallbackAxis?.let(event::getAxisValue) ?: 0f
		}
		fun Float.signedAxis() = (coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
		fun Float.unsignedAxis() = (coerceIn(0f, 1f) * UByte.MAX_VALUE.toFloat()).toUInt().toUByte()

		motionControllerState.leftX = axis(MotionEvent.AXIS_X).signedAxis()
		motionControllerState.leftY = axis(MotionEvent.AXIS_Y).signedAxis()
		motionControllerState.rightX = axis(MotionEvent.AXIS_Z, MotionEvent.AXIS_RX).signedAxis()
		motionControllerState.rightY = axis(MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY).signedAxis()
		motionControllerState.l2State = axis(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE).unsignedAxis()
		motionControllerState.r2State = axis(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS).unsignedAxis()
		motionControllerState.buttons = motionControllerState.buttons.let {
			val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
			val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
			val dpadButtons =
				(if(dpadX > 0.5f) ControllerState.BUTTON_DPAD_RIGHT else 0U) or
						(if(dpadX < -0.5f) ControllerState.BUTTON_DPAD_LEFT else 0U) or
						(if(dpadY > 0.5f) ControllerState.BUTTON_DPAD_DOWN else 0U) or
						(if(dpadY < -0.5f) ControllerState.BUTTON_DPAD_UP else 0U)
			it and (ControllerState.BUTTON_DPAD_RIGHT or
					ControllerState.BUTTON_DPAD_LEFT or
					ControllerState.BUTTON_DPAD_DOWN or
					ControllerState.BUTTON_DPAD_UP).inv() or
					dpadButtons
		}
		//Log.i("StreamSession", "motionEvent => $motionControllerState")
		controllerStateUpdated()
		return true
	}

	@Synchronized
	fun updateQuestControllerState(
		leftX: Float,
		leftY: Float,
		rightX: Float,
		rightY: Float,
		leftTrigger: Float,
		rightTrigger: Float,
		leftGrip: Float,
		rightGrip: Float,
		buttons: UInt
	) {
		if(!preferences.questControllerEmulationEnabled) {
			// The setting can be changed from the flat settings screen while an
			// immersive activity is still alive.  Do not leave a held Quest button
			// or stick value in the combined state after emulation is disabled.
			if(questControllerState != ControllerState() || pulsedQuestButtons != 0U) {
				questControllerState = ControllerState()
				pulsedQuestButtons = 0U
				controllerStateUpdated(isQuestInput = true)
			}
			return
		}

		fun Float.stickAxis(invert: Boolean = false): Short {
			val deadzoned = if(kotlin.math.abs(this) < 0.12f) 0f else this
			val value = if(invert) -deadzoned else deadzoned
			return (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
		}
		fun Float.rightStickAxis(invert: Boolean = false): Short {
			val clamped = coerceIn(-1f, 1f)
			val magnitude = kotlin.math.abs(clamped)
			if(magnitude < 0.14f)
				return 0
			// Slightly soften center and mid-range camera movement while keeping
			// full stick deflection at the full PS5 input range.
			val curvedMagnitude =
				0.85f * magnitude + 0.15f * magnitude * magnitude * magnitude
			val signed = kotlin.math.sign(clamped) * curvedMagnitude
			val value = if(invert) -signed else signed
			return (value * Short.MAX_VALUE).toInt().toShort()
		}
		fun Float.triggerAxis() =
			(coerceIn(0f, 1f) * UByte.MAX_VALUE.toFloat()).toUInt().toUByte()

		val dpadButtons = if(leftGrip >= 0.55f) {
			(if(leftX > 0.65f) ControllerState.BUTTON_DPAD_RIGHT else 0U) or
			(if(leftX < -0.65f) ControllerState.BUTTON_DPAD_LEFT else 0U) or
			(if(leftY > 0.65f) ControllerState.BUTTON_DPAD_UP else 0U) or
			(if(leftY < -0.65f) ControllerState.BUTTON_DPAD_DOWN else 0U)
		} else 0U
		val gripButtons =
			(if(leftGrip >= 0.55f && dpadButtons == 0U) ControllerState.BUTTON_L1 else 0U) or
			(if(rightGrip >= 0.55f) ControllerState.BUTTON_R1 else 0U)
		val faceButtonMask = ControllerState.BUTTON_CROSS or
			ControllerState.BUTTON_MOON or
			ControllerState.BUTTON_BOX or
			ControllerState.BUTTON_PYRAMID
		val mappedButtons = if(swapCrossMoon) {
			(buttons and faceButtonMask.inv()) or
			(if(buttons and ControllerState.BUTTON_CROSS != 0U) ControllerState.BUTTON_MOON else 0U) or
			(if(buttons and ControllerState.BUTTON_MOON != 0U) ControllerState.BUTTON_CROSS else 0U) or
			(if(buttons and ControllerState.BUTTON_BOX != 0U) ControllerState.BUTTON_PYRAMID else 0U) or
			(if(buttons and ControllerState.BUTTON_PYRAMID != 0U) ControllerState.BUTTON_BOX else 0U)
		} else buttons
		val nextState = ControllerState(
			buttons = mappedButtons or gripButtons or dpadButtons,
			l2State = leftTrigger.triggerAxis(),
			r2State = rightTrigger.triggerAxis(),
			leftX = if(dpadButtons == 0U) leftX.stickAxis() else 0,
			leftY = if(dpadButtons == 0U) leftY.stickAxis(invert = true) else 0,
			rightX = rightX.rightStickAxis(),
			rightY = rightY.rightStickAxis(invert = true)
		)
		if(nextState != questControllerState) {
			questControllerState = nextState
			controllerStateUpdated(isQuestInput = true)
		}
	}

	@Synchronized
	fun pulseQuestControllerButton(button: UInt, force: Boolean = false) {
		if((!force && !preferences.questControllerEmulationEnabled) || button == 0U ||
			questControllerState.buttons and button != 0U ||
			pulsedQuestButtons and button != 0U)
			return

		pulsedQuestButtons = pulsedQuestButtons or button
		controllerStateUpdated(isQuestInput = true)
		questButtonPulseHandler.postDelayed({
			synchronized(this) {
				pulsedQuestButtons = pulsedQuestButtons and button.inv()
				controllerStateUpdated(isQuestInput = true)
			}
		}, 120L)
	}
}
