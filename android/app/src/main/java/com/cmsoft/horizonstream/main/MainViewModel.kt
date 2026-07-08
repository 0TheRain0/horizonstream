// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.cmsoft.horizonstream.main

import androidx.lifecycle.ViewModel
import com.cmsoft.horizonstream.common.*
import com.cmsoft.horizonstream.common.ext.toLiveData
import com.cmsoft.horizonstream.discovery.DiscoveryManager
import com.cmsoft.horizonstream.discovery.serverMac
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers

class MainViewModel(val database: AppDatabase, val preferences: Preferences): ViewModel()
{
	private val disposable = CompositeDisposable()

	val discoveryManager = DiscoveryManager().also {
		it.active = preferences.discoveryEnabled
		it.discoveryActive
			.observeOn(AndroidSchedulers.mainThread())
			.subscribe { preferences.discoveryEnabled = it }
			.addTo(disposable)
	}

	val displayHosts by lazy {
		Observables.combineLatest(
			database.manualHostDao().getAll().toObservable(),
			database.registeredHostDao().getAll().toObservable(),
			discoveryManager.discoveredHosts)
			{ manualHosts, registeredHosts, discoveredHosts ->
				val macRegisteredHosts = registeredHosts.associateBy { it.serverMac }
				val idRegisteredHosts = registeredHosts.associateBy { it.id }
				
				val mappedManual = manualHosts.map {
					ManualDisplayHost(it.registeredHost?.let { id -> idRegisteredHosts[id] }, it)
				}
				
				val manualIps = manualHosts.map { it.host }.toSet()
				val manualMacs = mappedManual.mapNotNull { it.registeredHost?.serverMac }.toSet()
				
				val filteredDiscovered = discoveredHosts.filter { discovered ->
					val ipMatches = discovered.hostAddr in manualIps
					val macMatches = discovered.serverMac in manualMacs
					!ipMatches && !macMatches
				}.map {
					DiscoveredDisplayHost(it.serverMac?.let { mac -> macRegisteredHosts[mac] }, it)
				}
				
				filteredDiscovered + mappedManual
			}
			.toLiveData()
	}

	val discoveryActive by lazy {
		discoveryManager.discoveryActive.toLiveData()
	}

	fun deleteManualHost(manualHost: ManualHost)
	{
		database.manualHostDao()
			.delete(manualHost)
			.onErrorComplete()
			.subscribeOn(Schedulers.io())
			.subscribe()
			.addTo(disposable)
	}

	override fun onCleared()
	{
		super.onCleared()
		disposable.dispose()
		discoveryManager.dispose()
	}
}