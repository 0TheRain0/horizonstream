package com.cmsoft.horizonstream.main

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.cmsoft.horizonstream.R
import com.cmsoft.horizonstream.common.DiscoveredDisplayHost
import com.cmsoft.horizonstream.common.DisplayHost
import com.cmsoft.horizonstream.common.ManualDisplayHost
import com.cmsoft.horizonstream.common.DeviceUtils
import com.cmsoft.horizonstream.common.Preferences
import com.cmsoft.horizonstream.lib.DiscoveryHost
import com.cmsoft.horizonstream.lib.ConnectInfo
import com.cmsoft.horizonstream.stream.StreamActivity
import com.cmsoft.horizonstream.BuildConfig
import com.cmsoft.horizonstream.onboarding.ImmersiveOnboardingActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val displayHosts by viewModel.displayHosts.observeAsState(emptyList())
    val discoveryActive by viewModel.discoveryActive.observeAsState(false)

    // Dialog state
    var hostToWakeup by remember { mutableStateOf<DisplayHost?>(null) }
    var hostToDelete by remember { mutableStateOf<ManualDisplayHost?>(null) }
    var pendingOnboardingHost by remember { mutableStateOf<DisplayHost?>(null) }
    var showCameraPermissionDialog by remember { mutableStateOf(false) }
    var cameraPermissionDenied by remember { mutableStateOf(false) }

    fun startImmersiveOnboarding(host: DisplayHost) {
        context.startActivity(Intent(context, ImmersiveOnboardingActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ImmersiveOnboardingActivity.EXTRA_CONSOLE_NAME, host.name ?: "this PlayStation")
            putExtra(ImmersiveOnboardingActivity.EXTRA_CONSOLE_ADDRESS, host.host)
        })
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val host = pendingOnboardingHost
        pendingOnboardingHost = null
        if (granted && host != null) {
            startImmersiveOnboarding(host)
        } else if (!granted) {
            cameraPermissionDenied = true
        }
    }

    fun requestCameraThenStartOnboarding(host: DisplayHost) {
        if (ContextCompat.checkSelfPermission(context, ImmersiveOnboardingActivity.HEADSET_CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startImmersiveOnboarding(host)
        } else {
            pendingOnboardingHost = host
            showCameraPermissionDialog = true
        }
    }

    fun launchStream(host: DisplayHost) {
        val registeredHost = host.registeredHost ?: return
        val connectInfo = ConnectInfo(
            host.isPS5, 
            host.host, 
            registeredHost.rpRegistKey, 
            registeredHost.rpKey, 
            Preferences(context).videoProfile
        )
        val targetActivityClass = if (DeviceUtils.isQuest() && Preferences(context).immersiveVrModeEnabled) {
            com.cmsoft.horizonstream.stream.VRStreamActivity::class.java
        } else {
            StreamActivity::class.java
        }
        // Keep the immersive activity explicit. It requires ConnectInfo and is
        // launched as a separate Quest VR task. Without ACTION_MAIN and
        // FLAG_ACTIVITY_NEW_TASK, Horizon OS keeps it inside the 2D panel task;
        // OpenXR then remains VISIBLE but never FOCUSED and xrWaitFrame stalls.
        val intent = Intent(context, targetActivityClass).apply {
            putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
            if(targetActivityClass == com.cmsoft.horizonstream.stream.VRStreamActivity::class.java) {
                action = Intent.ACTION_MAIN
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }

    // VR-style radial/linear dark gradient background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A), // Deep dark slate
                        Color(0xFF020617)  // Near black
                    )
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent, // Transparent to show gradient
            topBar = {
                TopAppBar(
                    title = { },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    actions = {
                        IconButton(onClick = { navController.navigate("help") }) {
                            Icon(Icons.Default.Info, contentDescription = "Help", tint = Color.White)
                        }
                        IconButton(onClick = { navController.navigate("settings") }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                    }
                )
            },

            floatingActionButton = {
                FloatingActionButton(
                    onClick = viewModel::refreshDiscovery,
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh discovered consoles")
                }
            }
        ) { paddingValues ->
            // Center the main content with max-width optimization for Quest goggles (no neck strain)
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                if (displayHosts.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(top = 100.dp, bottom = 100.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(if (discoveryActive) R.drawable.ic_discover_on else R.drawable.ic_discover_off),
                            contentDescription = null,
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No PlayStation found yet",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (discoveryActive) "Turn on your PlayStation and make sure it shares this Quest’s Wi‑Fi. Then scan again." else "Discovery is paused. Turn on your PlayStation, then start a scan.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(max = 360.dp)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(onClick = viewModel::refreshDiscovery) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (discoveryActive) "Refresh search" else "Start discovery")
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .widthIn(max = 640.dp)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, top = 100.dp, end = 16.dp, bottom = 100.dp)
                    ) {
                        items(displayHosts) { host ->
                            HostItem(
                                host = host,
                                onClick = {
                                    val registeredHost = host.registeredHost
                                    if (registeredHost != null) {
                                        if (host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.STANDBY) {
                                            hostToWakeup = host
                                        } else {
                                            launchStream(host)
                                        }
                                    } else {
                                        // First-time setup is a dedicated Quest VR activity so
                                        // discovery, scan guidance, pairing, and connection stay
                                        // in the user's view instead of opening a 2D panel.
                                        requestCameraThenStartOnboarding(host)
                                    }
                                },
                                onEdit = {
                                    if (host is ManualDisplayHost) {
                                        navController.navigate("edit_manual_console/${host.manualHost.id}")
                                    }
                                },
                                onDelete = {
                                    if (host is ManualDisplayHost) {
                                        hostToDelete = host
                                    }
                                }
                            )
                        }
                    }
                }

                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }

    // Wakeup dialog
    if (hostToWakeup != null) {
        AlertDialog(
            onDismissRequest = { hostToWakeup = null },
            title = { Text(stringResource(R.string.action_wakeup)) },
            text = { Text(stringResource(R.string.alert_message_standby_wakeup)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val host = hostToWakeup
                        val registeredHost = host?.registeredHost
                        if (host != null && registeredHost != null) {
                            viewModel.discoveryManager.sendWakeup(host.host, registeredHost.rpRegistKey, registeredHost.target.isPS5)
                        }
                        hostToWakeup = null
                    }
                ) {
                    Text(stringResource(R.string.action_wakeup))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        hostToWakeup?.let { launchStream(it) }
                        hostToWakeup = null
                    }
                ) {
                    Text(stringResource(R.string.action_connect_immediately))
                }
            }
        )
    }

    // Delete Manual Host Confirmation
    if (hostToDelete != null) {
        AlertDialog(
            onDismissRequest = { hostToDelete = null },
            title = { Text("Delete Console") },
            text = { Text(stringResource(R.string.alert_message_delete_manual_host, hostToDelete?.host ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        hostToDelete?.let { viewModel.deleteManualHost(it.manualHost) }
                        hostToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { hostToDelete = null }) {
                    Text(stringResource(R.string.action_keep))
                }
            }
        )
    }

    if (showCameraPermissionDialog || cameraPermissionDenied) {
        val denied = cameraPermissionDenied
        AlertDialog(
            onDismissRequest = {
                showCameraPermissionDialog = false
                cameraPermissionDenied = false
                pendingOnboardingHost = null
            },
            title = { Text(if (denied) "Camera access is required" else "Allow camera access for setup") },
            text = {
                Text(
                    if (denied) {
                        "Camera access was not granted, so Horizon Stream can’t scan the sign-in QR code or PS5 Link Device code. Allow it in system permissions to continue setup."
                    } else {
                        "Horizon Stream needs headset-camera access during setup to scan the PlayStation Network QR code and the eight-digit Link Device code. The camera is only used while scanning."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (denied) {
                        cameraPermissionDenied = false
                        showCameraPermissionDialog = true
                    } else {
                        showCameraPermissionDialog = false
                        cameraPermissionLauncher.launch(ImmersiveOnboardingActivity.HEADSET_CAMERA_PERMISSION)
                    }
                }) {
                    Text(if (denied) "Try again" else "Allow camera")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCameraPermissionDialog = false
                    cameraPermissionDenied = false
                    pendingOnboardingHost = null
                }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun HostItem(
    host: DisplayHost,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E293B) // Dark greyish-blue card color
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(if (host.isPS5) R.drawable.ic_console_ps5 else R.drawable.ic_console),
                contentDescription = null,
                modifier = Modifier.size(56.dp), // Slightly larger for VR targeting
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                val registeredHost = host.registeredHost
                if (registeredHost != null) {
                    Text(
                        text = registeredHost.serverNickname ?: "Registered Console", 
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = host.host, 
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (host is DiscoveredDisplayHost) {
                    Text(
                        text = host.discoveredHost.hostName ?: "Discovered Console", 
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = host.host, 
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (host is ManualDisplayHost) {
                    Text(
                        text = "Manual Host", 
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = host.host, 
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (host is ManualDisplayHost) {
                Row {
                    // Once registration has completed, the row represents the
                    // console's discovered/registered identity. Keep delete
                    // available, but don't expose the incomplete manual-host
                    // editor for that registered device.
                    if (!host.isRegistered) {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
