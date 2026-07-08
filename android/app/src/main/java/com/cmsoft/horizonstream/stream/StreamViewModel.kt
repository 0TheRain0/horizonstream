// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.cmsoft.horizonstream.stream

import android.app.Application
import android.content.Context
import androidx.lifecycle.*
import com.cmsoft.horizonstream.common.DeviceUtils
import com.cmsoft.horizonstream.common.LogManager
import com.cmsoft.horizonstream.session.StreamSession
import com.cmsoft.horizonstream.common.Preferences
import com.cmsoft.horizonstream.lib.*
import com.cmsoft.horizonstream.session.StreamInput

class StreamViewModel(val application: Application, val connectInfo: ConnectInfo): ViewModel()
{
	val preferences = Preferences(application)
	val logManager = LogManager(application)

	private var _session: StreamSession? = null
	val input = StreamInput(application, preferences)
	val session = StreamSession(connectInfo, logManager, preferences.logVerbose, input)

	private val defaultOnScreenControlsEnabled = if (DeviceUtils.isQuest()) false else preferences.onScreenControlsEnabled
	private var _onScreenControlsEnabled = MutableLiveData<Boolean>(defaultOnScreenControlsEnabled)
	val onScreenControlsEnabled: LiveData<Boolean> get() = _onScreenControlsEnabled

	private val defaultTouchpadOnlyEnabled = if (DeviceUtils.isQuest()) false else preferences.touchpadOnlyEnabled
	private var _touchpadOnlyEnabled = MutableLiveData<Boolean>(defaultTouchpadOnlyEnabled)
	val touchpadOnlyEnabled: LiveData<Boolean> get() = _touchpadOnlyEnabled

	override fun onCleared()
	{
		super.onCleared()
		_session?.shutdown()
	}

	fun setOnScreenControlsEnabled(enabled: Boolean)
	{
		preferences.onScreenControlsEnabled = enabled
		_onScreenControlsEnabled.value = enabled
	}

	fun setTouchpadOnlyEnabled(enabled: Boolean)
	{
		preferences.touchpadOnlyEnabled = enabled
		_touchpadOnlyEnabled.value = enabled
	}
}