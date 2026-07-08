package com.cmsoft.horizonstream.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.cmsoft.horizonstream.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "General",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp)
            )
            
            // Placeholder for preferences
            ListItem(
                headlineContent = { Text(stringResource(R.string.preferences_registered_hosts_title)) },
                supportingContent = { Text(stringResource(R.string.preferences_registered_hosts_summary)) },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            
            Divider()
            
            Text(
                text = "Stream",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(16.dp)
            )
            
            ListItem(
                headlineContent = { Text(stringResource(R.string.preferences_resolution_title)) },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            
            ListItem(
                headlineContent = { Text(stringResource(R.string.preferences_fps_title)) },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            
            ListItem(
                headlineContent = { Text(stringResource(R.string.preferences_bitrate_title)) },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            
            ListItem(
                headlineContent = { Text(stringResource(R.string.preferences_codec_title)) },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}
