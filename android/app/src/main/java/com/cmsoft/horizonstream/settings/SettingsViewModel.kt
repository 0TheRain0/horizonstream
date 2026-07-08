// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.cmsoft.horizonstream.settings

import androidx.lifecycle.ViewModel
import com.cmsoft.horizonstream.common.AppDatabase
import com.cmsoft.horizonstream.common.Preferences
import com.cmsoft.horizonstream.common.ext.toLiveData

class SettingsViewModel(val database: AppDatabase, val preferences: Preferences): ViewModel()
{
	val registeredHostsCount by lazy {
		database.registeredHostDao().count().toLiveData()
	}

	val bitrateAuto by lazy {
		preferences.bitrateAutoObservable.toLiveData()
	}
}