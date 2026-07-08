package com.cmsoft.horizonstream.main

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.navigation.NavController
import com.cmsoft.horizonstream.R
import com.cmsoft.horizonstream.common.DiscoveredDisplayHost
import com.cmsoft.horizonstream.common.DisplayHost
import com.cmsoft.horizonstream.common.ManualDisplayHost
import com.cmsoft.horizonstream.common.Preferences
import com.cmsoft.horizonstream.lib.DiscoveryHost
import com.cmsoft.horizonstream.lib.ConnectInfo
import com.cmsoft.horizonstream.stream.StreamActivity

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

    fun launchStream(host: DisplayHost) {
        val registeredHost = host.registeredHost ?: return
        val connectInfo = ConnectInfo(
            host.isPS5, 
            host.host, 
            registeredHost.rpRegistKey, 
            registeredHost.rpKey, 
            Preferences(context).videoProfile
        )
        val intent = Intent(context, StreamActivity::class.java).apply {
            putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
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
                CenterAlignedTopAppBar(
                    title = { 
                        Text(
                            stringResource(R.string.app_name), 
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        ) 
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { navController.navigate("edit_manual_console/0") },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Manual Console")
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
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(if (discoveryActive) R.drawable.ic_discover_on else R.drawable.ic_discover_off),
                            contentDescription = null,
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(if (discoveryActive) R.string.display_hosts_empty_discovery_on_info else R.string.display_hosts_empty_discovery_off_info),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .widthIn(max = 640.dp)
                            .fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
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
                                        // Register console
                                        navController.navigate("register/${host.host}?broadcast=false&manualHostId=${if (host is ManualDisplayHost) host.manualHost.id else 0}")
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
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
