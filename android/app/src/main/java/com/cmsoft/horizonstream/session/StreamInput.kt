package com.cmsoft.horizonstream.session

import android.content.Context
import android.hardware.*
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

	val controllerState: ControllerState get()
	{
		val controllerState = sensorControllerState or keyControllerState or motionControllerState

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
		if(motionControllerState.l2State > 0U)
			controllerState.l2State = motionControllerState.l2State
		if(motionControllerState.r2State > 0U)
			controllerState.r2State = motionControllerState.r2State

		return (controllerState or touchControllerState or questControllerState).also {
			// Keep synthetic gesture buttons asserted across OpenXR frames long
			// enough for Chiaki's input sender and the console to observe them.
			it.buttons = it.buttons or pulsedQuestButtons
		}
	}

	private val sensorControllerState = ControllerState() // from Motion Sensors
	private val keyControllerState = ControllerState() // from KeyEvents
	private val motionControllerState = ControllerState() // from MotionEvents
	private var questControllerState = ControllerState() // from OpenXR Touch controllers
	@Volatile private var pulsedQuestButtons = 0U
	private val questButtonPulseHandler = Handler(Looper.getMainLooper())
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
		if(preferences.motionEnabled)
			lifecycleOwner.lifecycle.addObserver(motionLifecycleObserver)
	}

	private fun controllerStateUpdated()
	{
		controllerStateChangedCallback?.let { it(controllerState) }
	}

	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		//Log.i("StreamSession", "key event $event")
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
			return false

		when(event.keyCode)
		{
			KeyEvent.KEYCODE_BUTTON_L2 -> {
				keyControllerState.l2State = if(event.action == KeyEvent.ACTION_DOWN) UByte.MAX_VALUE else 0U
				controllerStateUpdated()
				return true
			}
			KeyEvent.KEYCODE_BUTTON_R2 -> {
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

	fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK))
			return false

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
		if(!preferences.questControllerEmulationEnabled)
			return

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
			controllerStateUpdated()
		}
	}

	@Synchronized
	fun pulseQuestControllerButton(button: UInt) {
		if(!preferences.questControllerEmulationEnabled || button == 0U ||
			questControllerState.buttons and button != 0U ||
			pulsedQuestButtons and button != 0U)
			return

		pulsedQuestButtons = pulsedQuestButtons or button
		controllerStateUpdated()
		questButtonPulseHandler.postDelayed({
			synchronized(this) {
				pulsedQuestButtons = pulsedQuestButtons and button.inv()
				controllerStateUpdated()
			}
		}, 120L)
	}
}
