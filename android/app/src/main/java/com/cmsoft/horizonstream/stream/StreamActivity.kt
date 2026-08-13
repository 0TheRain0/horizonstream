// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.cmsoft.horizonstream.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Matrix
import android.os.*
import android.view.*
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.cmsoft.horizonstream.R
import com.cmsoft.horizonstream.common.ControllerAssignmentLearner
import com.cmsoft.horizonstream.common.DeviceUtils
import com.cmsoft.horizonstream.common.Preferences
import com.cmsoft.horizonstream.common.ext.viewModelFactory
import com.cmsoft.horizonstream.databinding.ActivityStreamBinding
import com.cmsoft.horizonstream.lib.ConnectInfo
import com.cmsoft.horizonstream.lib.ConnectVideoProfile
import com.cmsoft.horizonstream.main.MainActivity
import com.cmsoft.horizonstream.session.*
import com.cmsoft.horizonstream.touchcontrols.DefaultTouchControlsFragment
import com.cmsoft.horizonstream.touchcontrols.TouchControlsFragment
import com.cmsoft.horizonstream.touchcontrols.TouchpadOnlyFragment
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import kotlin.math.min

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()

open class StreamActivity : AppCompatActivity(), View.OnSystemUiVisibilityChangeListener
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val HIDE_UI_TIMEOUT_MS = 2000L
	}

	protected lateinit var viewModel: StreamViewModel
	protected lateinit var binding: ActivityStreamBinding

	private val uiVisibilityHandler = Handler(Looper.getMainLooper())

	/**
	 * Flat Android activities own the stream only while they are in the
	 * foreground.  An OpenXR activity, however, can briefly lose Android focus
	 * while the Quest runtime changes session state.  Stopping Chiaki for that
	 * transient focus change turns the resulting normal QuitEvent into an
	 * unintended activity finish.
	 */
	protected open val pauseStreamWhenBackgrounded = true

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		if (DeviceUtils.isQuest()) {
			requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
		}

		val connectInfo = intent.getParcelableExtra<ConnectInfo>(EXTRA_CONNECT_INFO)
		if(connectInfo == null)
		{
			// Meta and Horizon OS may resolve the exported VR entry directly. It
			// cannot stream without a selected console, so return to the app's 2D
			// connection panel instead of presenting an empty immersive activity.
			if(this is VRStreamActivity) {
				startActivity(Intent(this, MainActivity::class.java).apply {
					action = Intent.ACTION_MAIN
					addCategory("com.oculus.intent.category.2D")
					addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
				})
			}
			finish()
			return
		}

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)
		// The legacy bottom control strip obscures the flat streaming view.
		// Stream settings remain available through the learned controller button.
		if(this !is VRStreamActivity)
			binding.overlay.isGone = true
		window.decorView.setOnSystemUiVisibilityChangeListener(this)

		viewModel.onScreenControlsEnabled.observe(this, Observer {
			if(binding.onScreenControlsSwitch.isChecked != it)
				binding.onScreenControlsSwitch.isChecked = it
			if(binding.onScreenControlsSwitch.isChecked)
				binding.touchpadOnlySwitch.isChecked = false
		})
		binding.onScreenControlsSwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setOnScreenControlsEnabled(isChecked)
			showOverlay()
		}

		viewModel.touchpadOnlyEnabled.observe(this, Observer {
			if(binding.touchpadOnlySwitch.isChecked != it)
				binding.touchpadOnlySwitch.isChecked = it
			if(binding.touchpadOnlySwitch.isChecked)
				binding.onScreenControlsSwitch.isChecked = false
		})
		binding.touchpadOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setTouchpadOnlyEnabled(isChecked)
			showOverlay()
		}

		binding.displayModeToggle.addOnButtonCheckedListener { _, _, _ ->
			adjustStreamViewAspect()
			showOverlay()
		}

		// AI depth conversion is implemented only by VRStreamActivity's OpenXR
		// compositor. The normal activity always keeps the direct 2D path.
		if (this !is VRStreamActivity) {
			viewModel.session.attachToSurfaceView(binding.surfaceView)
		}
		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		adjustStreamViewAspect()

		if(Preferences(this).rumbleEnabled)
		{
			val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
			viewModel.session.rumbleState.observe(this, Observer {
				val amplitude = min(255, (it.left.toInt() + it.right.toInt()) / 2)
				vibrator.cancel()
				if(amplitude == 0)
					return@Observer
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
					vibrator.vibrate(VibrationEffect.createOneShot(1000, amplitude))
				else
					vibrator.vibrate(1000)
			})
		}
	}

	private val controlsDisposable = CompositeDisposable()

	override fun onAttachFragment(fragment: Fragment)
	{
		super.onAttachFragment(fragment)
		if(fragment is TouchControlsFragment)
		{
			fragment.controllerState
				.subscribe { viewModel.input.touchControllerState = it }
				.addTo(controlsDisposable)
			fragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
			if(fragment is TouchpadOnlyFragment)
				fragment.touchpadOnlyEnabled = viewModel.touchpadOnlyEnabled
		}
	}

	override fun onResume()
	{
		super.onResume()
		hideSystemUI()
		prepareStreamOnResume()
		viewModel.session.resume()
	}

	/**
	 * Allows specialized stream activities to prepare their decoder target after
	 * Android has resumed the activity but before the Chiaki session starts.
	 */
	protected open fun prepareStreamOnResume() = Unit

	override fun onPause()
	{
		super.onPause()
		if(pauseStreamWhenBackgrounded)
			viewModel.session.pause()
	}

	override fun onDestroy()
	{
		super.onDestroy()
		controlsDisposable.dispose()
	}

	protected fun reconnectStream()
	{
		viewModel.session.shutdown()
		viewModel.session.resume()
	}

	protected fun wakeConsoleAndFinish()
	{
		intent.getParcelableExtra<ConnectInfo>(EXTRA_CONNECT_INFO)?.let { info ->
			com.cmsoft.horizonstream.discovery.DiscoveryManager().sendWakeup(
				info.host, info.registKey, info.ps5
			)
		}
		finish()
	}

	protected open fun showImmersiveQuitError(message: String): Boolean = false
	protected open fun showImmersiveCreateError(message: String): Boolean = false
	protected open fun showImmersiveLoginPin(pinIncorrect: Boolean): Boolean = false

	protected fun submitLoginPin(pin: String)
	{
		dialogContents = null
		viewModel.session.setLoginPin(pin)
	}

	private val hideSystemUIRunnable = Runnable { hideSystemUI() }

	override fun onSystemUiVisibilityChange(visibility: Int)
	{
		if(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0)
			showOverlay()
		else
			hideOverlay()
	}

	private fun showOverlay()
	{
		if(this !is VRStreamActivity)
			return
		binding.overlay.isVisible = true
		binding.overlay.animate()
			.alpha(1.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.alpha = 1.0f
				}
			})
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
	}

	private fun hideOverlay()
	{
		if(this !is VRStreamActivity) {
			binding.overlay.isGone = true
			return
		}
		binding.overlay.animate()
			.alpha(0.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.isGone = true
				}
			})
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus)
			hideSystemUI()
	}

	private fun hideSystemUI()
	{
		DeviceUtils.applyImmersiveMode(this, Preferences(this).immersiveVrModeEnabled)
	}

	private var streamSettingsDialog: AlertDialog? = null

	protected open fun openStreamSettings()
	{
		runOnUiThread {
			if(isFinishing || isDestroyed || streamSettingsDialog?.isShowing == true)
				return@runOnUiThread

			val preferences = Preferences(this)
			val enabledLabel = if(preferences.simulated3dEnabled) "On" else "Off"
			val intensityLabel = getString(when(preferences.simulated3dIntensity) {
				"low" -> R.string.preferences_simulated_3d_intensity_low
				"high" -> R.string.preferences_simulated_3d_intensity_high
				"strong" -> R.string.preferences_simulated_3d_intensity_strong
				else -> R.string.preferences_simulated_3d_intensity_medium
			})
			val assignmentLabel = ControllerAssignmentLearner.label(
				preferences.streamSettingsButtonBinding)
			val items = arrayOf(
				getString(R.string.stream_settings_spatial_restart, enabledLabel),
				getString(R.string.stream_settings_depth, intensityLabel),
				getString(R.string.stream_settings_assignment, assignmentLabel),
				getString(R.string.stream_settings_learn)
			)

			streamSettingsDialog = MaterialAlertDialogBuilder(this)
				.setTitle(R.string.stream_settings_title)
				.setItems(items) { activeDialog, which ->
					when(which) {
						0 -> {
							preferences.simulated3dEnabled =
								!preferences.simulated3dEnabled
							activeDialog.dismiss()
							streamSettingsDialog = null
							openStreamSettings()
						}
						1 -> {
							val strengths = listOf("low", "medium", "high", "strong")
							val currentIndex = strengths.indexOf(
								preferences.simulated3dIntensity).coerceAtLeast(0)
							preferences.simulated3dIntensity =
								strengths[(currentIndex + 1) % strengths.size]
							activeDialog.dismiss()
							streamSettingsDialog = null
							openStreamSettings()
						}
						3 -> {
							activeDialog.dismiss()
							streamSettingsDialog = null
							showControllerLearnDialog()
						}
					}
				}
				.setNegativeButton(android.R.string.cancel, null)
				.setOnDismissListener { streamSettingsDialog = null }
				.show()
		}
	}

	private fun showControllerLearnDialog()
	{
		runOnUiThread {
			ControllerAssignmentLearner.begin { learnedBinding ->
				Preferences(this).streamSettingsButtonBinding = learnedBinding
				streamSettingsDialog?.dismiss()
				streamSettingsDialog = null
				openStreamSettings()
			}
			streamSettingsDialog = MaterialAlertDialogBuilder(this)
				.setTitle(R.string.preferences_stream_settings_button_title)
				.setMessage(R.string.preferences_stream_settings_button_learning)
				.setNegativeButton(android.R.string.cancel) { _, _ ->
					ControllerAssignmentLearner.cancel()
				}
				.setOnCancelListener { ControllerAssignmentLearner.cancel() }
				.setOnDismissListener {
					if(ControllerAssignmentLearner.isLearning)
						ControllerAssignmentLearner.cancel()
					streamSettingsDialog = null
				}
				.show()
		}
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value
			if(value == null)
				dialogContents = null
		}

	private fun stateChanged(state: StreamState)
	{
		binding.progressBar.visibility = if(state == StreamStateConnecting) View.VISIBLE else View.GONE
		if(state == StreamStateConnecting || state == StreamStateConnected)
			dialogContents = null

		when(state)
		{
			is StreamStateQuit ->
			{
				if(dialogContents != StreamQuitDialog)
				{
					if(state.reason.isError)
					{
						dialog?.dismiss()
						val reasonStr = state.reasonString
						val message = getString(
							R.string.alert_message_session_quit,
							state.reason.toString()
						) + (if(reasonStr != null) "\n$reasonStr" else "")
						if(showImmersiveQuitError(message)) {
							dialogContents = StreamQuitDialog
							return
						}
						val dialog = MaterialAlertDialogBuilder(this)
							.setMessage(message)
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								reconnectStream()
							}
							.setNeutralButton(R.string.action_wakeup) { _, _ ->
								dialog = null
								wakeConsoleAndFinish()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()
						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else
						finish()
				}
			}

			is StreamStateCreateError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()
					val message = getString(
						R.string.alert_message_session_create_error,
						state.error.errorCode.toString()
					)
					if(showImmersiveCreateError(message)) {
						dialogContents = CreateErrorDialog
						return
					}
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(message)
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()
					if(showImmersiveLoginPin(state.pinIncorrect)) {
						dialogContents = PinRequestDialog
						return
					}

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							submitLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = PinRequestDialog
					dialog.show()
				}
			}
			else ->{}
		}
	}

	private fun adjustTextureViewAspect(textureView: TextureView)
	{
		val trans = TextureViewTransform(viewModel.session.connectInfo.videoProfile, textureView)
		val resolution = trans.resolutionFor(TransformMode.fromButton(binding.displayModeToggle.checkedButtonId))
		Matrix().also {
			textureView.getTransform(it)
			it.setScale(resolution.width / trans.viewWidth, resolution.height / trans.viewHeight)
			it.postTranslate((trans.viewWidth - resolution.width) * 0.5f, (trans.viewHeight - resolution.height) * 0.5f)
			textureView.setTransform(it)
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		binding.aspectRatioLayout.aspectRatio = videoProfile.width.toFloat() / videoProfile.height.toFloat()
		binding.aspectRatioLayout.mode = TransformMode.fromButton(binding.displayModeToggle.checkedButtonId)
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if(ControllerAssignmentLearner.captureKeyEvent(event))
			return true

		val assignedKey = Preferences(this).streamSettingsButtonBinding
			?.takeIf { it.startsWith("key:") }
			?.substringAfter(':')
			?.toIntOrNull()
		if(assignedKey == event.keyCode) {
			if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
				finish()
			return true
		}
		return viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	}
	override fun onGenericMotionEvent(event: MotionEvent) = viewModel.input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)
}

enum class TransformMode
{
	FIT,
	STRETCH,
	ZOOM;

	companion object
	{
		fun fromButton(displayModeButtonId: Int)
			= when (displayModeButtonId)
			{
				R.id.display_mode_stretch_button -> STRETCH
				R.id.display_mode_zoom_button -> ZOOM
				else -> FIT
			}
	}
}

class TextureViewTransform(private val videoProfile: ConnectVideoProfile, private val textureView: TextureView)
{
	private val contentWidth : Float get() = videoProfile.width.toFloat()
	private val contentHeight : Float get() = videoProfile.height.toFloat()
	val viewWidth : Float get() = textureView.width.toFloat()
	val viewHeight : Float get() = textureView.height.toFloat()
	private val contentAspect : Float get() =  contentHeight / contentWidth

	fun resolutionFor(mode: TransformMode): Resolution
		= when(mode)
		{
			TransformMode.STRETCH -> strechedResolution
			TransformMode.ZOOM -> zoomedResolution
			TransformMode.FIT -> normalResolution
		}

	private val strechedResolution get() = Resolution(viewWidth, viewHeight)

	private val zoomedResolution get() =
		if(viewHeight > viewWidth * contentAspect)
		{
			val zoomFactor = viewHeight / contentHeight
			Resolution(contentWidth * zoomFactor, viewHeight)
		}
		else
		{
			val zoomFactor = viewWidth / contentWidth
			Resolution(viewWidth, contentHeight * zoomFactor)
		}

	private val normalResolution get() =
		if(viewHeight > viewWidth * contentAspect)
			Resolution(viewWidth, viewWidth * contentAspect)
		else
			Resolution(viewHeight / contentAspect, viewHeight)
}


data class Resolution(val width: Float, val height: Float)
