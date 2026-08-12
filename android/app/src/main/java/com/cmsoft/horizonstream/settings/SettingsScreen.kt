package com.cmsoft.horizonstream.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.cmsoft.horizonstream.R
import com.cmsoft.horizonstream.common.ControllerAssignmentLearner
import com.cmsoft.horizonstream.common.Preferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val preferences = remember { Preferences(context) }

    // Preference states
    var resolution by remember { mutableStateOf(preferences.resolution) }
    var fps by remember { mutableStateOf(preferences.fps) }
    var codec by remember { mutableStateOf(preferences.codec) }
    var questControllerEmulation by remember { mutableStateOf(preferences.questControllerEmulationEnabled) }
    var immersiveVrMode by remember { mutableStateOf(preferences.immersiveVrModeEnabled) }
    var simulated3dEnabled by remember { mutableStateOf(preferences.simulated3dEnabled) }
    var simulated3dIntensity by remember { mutableStateOf(preferences.simulated3dIntensity) }
    var swapCrossMoon by remember { mutableStateOf(preferences.swapCrossMoon) }
    var streamSettingsButtonBinding by remember {
        mutableStateOf(preferences.streamSettingsButtonBinding)
    }

    // Dialog state for selections
    var showResolutionDialog by remember { mutableStateOf(false) }
    var showFpsDialog by remember { mutableStateOf(false) }
    var showCodecDialog by remember { mutableStateOf(false) }
    var show3dIntensityDialog by remember { mutableStateOf(false) }
    var showControllerLearnDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0F172A)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF0F172A))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // STREAM SECTION
            SettingsCategoryHeader(title = stringResource(R.string.preferences_category_title_stream))

            SettingClickableItem(
                title = stringResource(R.string.preferences_resolution_title),
                subtitle = stringResource(resolution.title),
                onClick = { showResolutionDialog = true }
            )

            SettingClickableItem(
                title = stringResource(R.string.preferences_fps_title),
                subtitle = stringResource(fps.title),
                onClick = { showFpsDialog = true }
            )

            SettingClickableItem(
                title = stringResource(R.string.preferences_codec_title),
                subtitle = stringResource(codec.title),
                onClick = { showCodecDialog = true }
            )

            Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF334155))

            // QUEST & VR HARDWARE SECTION
            SettingsCategoryHeader(title = stringResource(R.string.preferences_category_title_quest_vr))

            SettingSwitchItem(
                title = stringResource(R.string.preferences_immersive_vr_mode_title),
                subtitle = stringResource(R.string.preferences_immersive_vr_mode_summary),
                checked = immersiveVrMode,
                onCheckedChange = { enabled ->
                    immersiveVrMode = enabled
                    preferences.immersiveVrModeEnabled = enabled
                    if (!enabled) {
                        questControllerEmulation = false
                        preferences.questControllerEmulationEnabled = false
                        simulated3dEnabled = false
                        preferences.simulated3dEnabled = false
                    }
                }
            )

            SettingSwitchItem(
                title = stringResource(R.string.preferences_quest_controller_emulation_title),
                subtitle = stringResource(R.string.preferences_quest_controller_emulation_summary) + " (Requires Immersive VR Mode)",
                checked = questControllerEmulation,
                onCheckedChange = { enabled ->
                    questControllerEmulation = enabled
                    preferences.questControllerEmulationEnabled = enabled
                    if (enabled && !immersiveVrMode) {
                        immersiveVrMode = true
                        preferences.immersiveVrModeEnabled = true
                    }
                }
            )

            Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF334155))

            // SPATIAL 3D RENDERING SECTION
            SettingsCategoryHeader(title = stringResource(R.string.preferences_category_title_spatial_3d))

            SettingSwitchItem(
                title = stringResource(R.string.preferences_simulated_3d_enabled_title),
                subtitle = stringResource(R.string.preferences_simulated_3d_enabled_summary) + " (Requires Immersive VR Mode)",
                checked = simulated3dEnabled,
                onCheckedChange = { enabled ->
                    simulated3dEnabled = enabled
                    preferences.simulated3dEnabled = enabled
                    if (enabled && !immersiveVrMode) {
                        immersiveVrMode = true
                        preferences.immersiveVrModeEnabled = true
                    }
                }
            )

            if (simulated3dEnabled) {
                val intensityLabel = when (simulated3dIntensity) {
                    "low" -> stringResource(R.string.preferences_simulated_3d_intensity_low)
                    "high" -> stringResource(R.string.preferences_simulated_3d_intensity_high)
                    "strong" -> stringResource(R.string.preferences_simulated_3d_intensity_strong)
                    else -> stringResource(R.string.preferences_simulated_3d_intensity_medium)
                }
                SettingClickableItem(
                    title = stringResource(R.string.preferences_simulated_3d_intensity_title),
                    subtitle = intensityLabel,
                    onClick = { show3dIntensityDialog = true }
                )
            }

            Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFF334155))

            // CONTROLS & INPUT SECTION
            SettingsCategoryHeader(title = "Controls & Input")

            SettingSwitchItem(
                title = stringResource(R.string.preferences_swap_cross_moon_title),
                subtitle = stringResource(R.string.preferences_swap_cross_moon_summary),
                checked = swapCrossMoon,
                onCheckedChange = {
                    swapCrossMoon = it
                    preferences.swapCrossMoon = it
                }
            )

            SettingClickableItem(
                title = stringResource(R.string.preferences_stream_settings_button_title),
                subtitle = stringResource(
                    R.string.preferences_stream_settings_button_summary,
                    ControllerAssignmentLearner.label(streamSettingsButtonBinding)
                ),
                onClick = { showControllerLearnDialog = true }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // RESOLUTION DIALOG
    if (showResolutionDialog) {
        OptionSelectionDialog(
            title = stringResource(R.string.preferences_resolution_title),
            options = Preferences.Resolution.values().toList(),
            selectedOption = resolution,
            optionLabel = { stringResource(it.title) },
            onOptionSelected = {
                resolution = it
                preferences.resolution = it
                showResolutionDialog = false
            },
            onDismiss = { showResolutionDialog = false }
        )
    }

    // FPS DIALOG
    if (showFpsDialog) {
        OptionSelectionDialog(
            title = stringResource(R.string.preferences_fps_title),
            options = Preferences.FPS.values().toList(),
            selectedOption = fps,
            optionLabel = { stringResource(it.title) },
            onOptionSelected = {
                fps = it
                preferences.fps = it
                showFpsDialog = false
            },
            onDismiss = { showFpsDialog = false }
        )
    }

    // CODEC DIALOG
    if (showCodecDialog) {
        OptionSelectionDialog(
            title = stringResource(R.string.preferences_codec_title),
            options = Preferences.Codec.values().toList(),
            selectedOption = codec,
            optionLabel = { stringResource(it.title) },
            onOptionSelected = {
                codec = it
                preferences.codec = it
                showCodecDialog = false
            },
            onDismiss = { showCodecDialog = false }
        )
    }

    // 3D INTENSITY DIALOG
    if (show3dIntensityDialog) {
        val intensities = listOf("low", "medium", "high", "strong")
        OptionSelectionDialog(
            title = stringResource(R.string.preferences_simulated_3d_intensity_title),
            options = intensities,
            selectedOption = simulated3dIntensity,
            optionLabel = {
                when (it) {
                    "low" -> stringResource(R.string.preferences_simulated_3d_intensity_low)
                    "high" -> stringResource(R.string.preferences_simulated_3d_intensity_high)
                    "strong" -> stringResource(R.string.preferences_simulated_3d_intensity_strong)
                    else -> stringResource(R.string.preferences_simulated_3d_intensity_medium)
                }
            },
            onOptionSelected = {
                simulated3dIntensity = it
                preferences.simulated3dIntensity = it
                show3dIntensityDialog = false
            },
            onDismiss = { show3dIntensityDialog = false }
        )
    }

    if (showControllerLearnDialog) {
        DisposableEffect(Unit) {
            ControllerAssignmentLearner.begin { learnedBinding ->
                preferences.streamSettingsButtonBinding = learnedBinding
                streamSettingsButtonBinding = learnedBinding
                showControllerLearnDialog = false
            }
            onDispose {
                ControllerAssignmentLearner.cancel()
            }
        }
        AlertDialog(
            onDismissRequest = { showControllerLearnDialog = false },
            title = {
                Text(stringResource(R.string.preferences_stream_settings_button_title))
            },
            text = {
                Text(stringResource(R.string.preferences_stream_settings_button_learning))
            },
            confirmButton = {
                TextButton(onClick = { showControllerLearnDialog = false }) {
                    Text("Cancel")
                }
            },
            dismissButton = {
                if (ControllerAssignmentLearner.normalizedBinding(
                        streamSettingsButtonBinding) != null
                ) {
                    TextButton(
                        onClick = {
                            preferences.streamSettingsButtonBinding = null
                            streamSettingsButtonBinding = null
                            showControllerLearnDialog = false
                        }
                    ) {
                        Text(stringResource(R.string.action_clear_assignment))
                    }
                }
            }
        )
    }
}

@Composable
fun SettingsCategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF38BDF8),
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
fun SettingSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF38BDF8)
            )
        )
    }
}

@Composable
fun SettingClickableItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = Color.White)
        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF38BDF8))
    }
}

@Composable
fun <T> OptionSelectionDialog(
    title: String,
    options: List<T>,
    selectedOption: T,
    optionLabel: @Composable (T) -> String,
    onOptionSelected: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, color = Color.White) },
        containerColor = Color(0xFF1E293B),
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOptionSelected(option) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (option == selectedOption),
                            onClick = { onOptionSelected(option) },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF38BDF8))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = optionLabel(option), color = Color.White)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF38BDF8))
            }
        }
    )
}
