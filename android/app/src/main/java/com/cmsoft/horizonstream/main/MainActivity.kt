// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.cmsoft.horizonstream.main

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.cmsoft.horizonstream.common.DeviceUtils
import com.cmsoft.horizonstream.common.Preferences
import com.cmsoft.horizonstream.common.ext.viewModelFactory
import com.cmsoft.horizonstream.common.getDatabase
import com.cmsoft.horizonstream.navigation.HorizonStreamNavGraph
import com.cmsoft.horizonstream.theme.HorizonStreamTheme

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (DeviceUtils.isQuest()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        viewModel = ViewModelProvider(this, viewModelFactory { MainViewModel(getDatabase(this), Preferences(this)) })
            .get(MainViewModel::class.java)

        setContent {
            HorizonStreamTheme {
                HorizonStreamNavGraph(mainViewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.discoveryManager.resume()
    }

    override fun onStop() {
        super.onStop()
        viewModel.discoveryManager.pause()
    }
}
