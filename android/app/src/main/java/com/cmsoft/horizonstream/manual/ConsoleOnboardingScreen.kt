package com.cmsoft.horizonstream.manual

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.cmsoft.horizonstream.common.ManualHost
import com.cmsoft.horizonstream.common.Preferences
import com.cmsoft.horizonstream.common.ext.viewModelFactory
import com.cmsoft.horizonstream.common.getDatabase
import com.cmsoft.horizonstream.lib.RegistInfo
import com.cmsoft.horizonstream.lib.Target
import com.cmsoft.horizonstream.regist.RegistExecuteViewModel
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.launch

private enum class OnboardingStep { DISCOVER, SIGN_IN, LINK_DEVICE }

/**
 * The normal first-console experience. It deliberately keeps redirect URLs and Account IDs out
 * of the UI: the QR transfer is the supported path, while the old editor remains an advanced
 * recovery route for legacy consoles and unusual network setups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleOnboardingScreen(navController: NavController, discoveredHost: String?) {
    val context = LocalContext.current
    val database = remember { getDatabase(context) }
    val preferences = remember { Preferences(context) }
    val scope = rememberCoroutineScope()
    val registViewModel: RegistExecuteViewModel = viewModel(
        factory = viewModelFactory { RegistExecuteViewModel(database) }
    )
    val registState by registViewModel.state.observeAsState(RegistExecuteViewModel.State.IDLE)

    var accountId by remember { mutableStateOf(preferences.psnAccountId) }
    var step by remember {
        mutableStateOf(
            when {
                discoveredHost.isNullOrBlank() -> OnboardingStep.DISCOVER
                accountId.isNullOrBlank() -> OnboardingStep.SIGN_IN
                else -> OnboardingStep.LINK_DEVICE
            }
        )
    }
    var hostAddress by remember { mutableStateOf(discoveredHost.orEmpty()) }
    var showManualAddress by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var lookupError by remember { mutableStateOf<String?>(null) }
    var retrievingAccount by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var consoleVersion by remember { mutableStateOf(ConsoleVersion.PS5) }

    fun retrieveAccountId(redirectUrl: String) {
        scope.launch {
            retrievingAccount = true
            lookupError = null
            try {
                accountId = PsnAccountIdLogin.retrieveAccountId(redirectUrl)
                preferences.psnAccountId = accountId
                step = OnboardingStep.LINK_DEVICE
            } catch (error: PsnLoginException) {
                lookupError = error.message ?: "That sign-in code could not be used. Create a new code and scan it once."
            } finally {
                retrievingAccount = false
            }
        }
    }

    LaunchedEffect(registState) {
        if (registState == RegistExecuteViewModel.State.SUCCESSFUL) navController.popBackStack()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Connect your PlayStation", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF020617)))
            ).padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier.width(600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Progress(step)
                when (step) {
                    OnboardingStep.DISCOVER -> DiscoveryStep(
                        address = hostAddress,
                        showManualAddress = showManualAddress,
                        onShowManualAddress = { showManualAddress = true },
                        onAddressChange = { hostAddress = it },
                        onContinue = { step = if (accountId.isNullOrBlank()) OnboardingStep.SIGN_IN else OnboardingStep.LINK_DEVICE },
                        onAdvanced = { navController.navigate("edit_manual_console/0") }
                    )
                    OnboardingStep.SIGN_IN -> SignInStep(
                        isRetrieving = retrievingAccount,
                        error = lookupError,
                        onScan = { showScanner = true },
                        onAdvanced = { navController.navigate("edit_manual_console/0") }
                    )
                    OnboardingStep.LINK_DEVICE -> LinkDeviceStep(
                        version = consoleVersion,
                        pin = pin,
                        onVersionChange = { consoleVersion = it },
                        onPinChange = { if (it.length <= 8 && it.all(Char::isDigit)) pin = it },
                        onPair = {
                            val target = when (consoleVersion) {
                                ConsoleVersion.PS5 -> Target.PS5_1
                                ConsoleVersion.PS4_GE_8 -> Target.PS4_10
                                ConsoleVersion.PS4_GE_7 -> Target.PS4_9
                                ConsoleVersion.PS4_LT_7 -> Target.PS4_8
                            }
                            val id = accountId ?: return@LinkDeviceStep
                            database.manualHostDao().insert(ManualHost(host = hostAddress, registeredHost = null))
                                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                                .subscribe({ manualId ->
                                    registViewModel.start(
                                        RegistInfo(target, hostAddress, false, null, Base64.decode(id, Base64.DEFAULT), pin.toInt()),
                                        manualId
                                    )
                                }, { lookupError = "We couldn't save this console. Please try again." })
                        },
                        onOlderConsole = { navController.navigate("edit_manual_console/0") }
                    )
                }
            }
        }
    }

    if (showScanner) {
        Dialog(onDismissRequest = { showScanner = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            AccountIdQrScanner(
                onTransferScanned = { transfer ->
                    showScanner = false
                    when (transfer) {
                        is PsnQrTransfer.AccountId -> {
                            accountId = transfer.value
                            preferences.psnAccountId = accountId
                            step = OnboardingStep.LINK_DEVICE
                        }
                        is PsnQrTransfer.RedirectUrl -> retrieveAccountId(transfer.value)
                    }
                },
                onDismissRequest = { showScanner = false }
            )
        }
    }

    when (registState) {
        RegistExecuteViewModel.State.RUNNING -> ProgressOverlay("Pairing with your console", "Keep the Link Device screen open. This usually takes a few seconds.")
        RegistExecuteViewModel.State.FAILED -> FailureOverlay(
            title = "We couldn’t pair with that console",
            detail = "Check that Remote Play is enabled, the console and headset are on the same network, and the eight-digit Link Device code is still visible. Get a fresh code before trying again.",
            onRetry = { navController.popBackStack() }, onAdvanced = { navController.navigate("edit_manual_console/0") },
            retryLabel = "Return to console list"
        )
        RegistExecuteViewModel.State.SUCCESSFUL_DUPLICATE -> FailureOverlay(
            title = "This console is already saved", detail = "You can return to your consoles, or replace the existing saved registration.",
            onRetry = { registViewModel.saveHost() }, onAdvanced = { registViewModel.stop() }, retryLabel = "Replace saved console", secondaryLabel = "Keep existing"
        )
        else -> Unit
    }
}

@Composable private fun Progress(step: OnboardingStep) {
    val labels = listOf("Find console", "Sign in", "Link device")
    val current = step.ordinal
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEachIndexed { index, label -> Text("${if (index <= current) "●" else "○"} $label", color = if (index <= current) MaterialTheme.colorScheme.primary else Color.LightGray, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable private fun DiscoveryStep(address: String, showManualAddress: Boolean, onShowManualAddress: () -> Unit, onAddressChange: (String) -> Unit, onContinue: () -> Unit, onAdvanced: () -> Unit) {
    StepCard("First, find your console", "Make sure your Quest and PlayStation are on the same Wi‑Fi network. Turn the console on, or wake it before continuing.") {
        if (address.isNotBlank()) {
            StatusLine(true, "Console found at $address")
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        } else {
            StatusLine(false, "No console was discovered yet")
            Text("Wait a moment, then return here after checking that both devices are on the same local network.", style = MaterialTheme.typography.bodyMedium)
            if (showManualAddress) OutlinedTextField(address, onAddressChange, label = { Text("Console IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            if (showManualAddress) Button(onClick = onContinue, enabled = address.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Use this address") }
            else TextButton(onClick = onShowManualAddress) { Text("I know my console’s IP address") }
            TextButton(onClick = onAdvanced) { Text("Use advanced setup instead") }
        }
    }
}

@Composable private fun SignInStep(isRetrieving: Boolean, error: String?, onScan: () -> Unit, onAdvanced: () -> Unit) {
    StepCard("Sign in on your computer", "On your computer, open the Horizon Stream Chrome extension side panel. Choose “Open PS Remote Play sign-in,” finish Sony sign-in, and keep the side panel open.") {
        Text("When the panel shows its QR code, look at it through your headset and select Scan. The code is one-time and sensitive—scan it immediately and do not share it.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onScan, enabled = !isRetrieving, modifier = Modifier.fillMaxWidth()) {
            if (isRetrieving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Getting your account…") } else Text("Scan sign-in QR code")
        }
        error?.let { StatusLine(false, it) }
        TextButton(onClick = onAdvanced) { Text("QR scanning isn’t working") }
    }
}

@Composable private fun LinkDeviceStep(version: ConsoleVersion, pin: String, onVersionChange: (ConsoleVersion) -> Unit, onPinChange: (String) -> Unit, onPair: () -> Unit, onOlderConsole: () -> Unit) {
    StepCard("Link your device", if (version.isPS5) "On PS5: Settings → System → Remote Play → Link Device. First enable Remote Play if asked." else "On PS4: Settings → Remote Play Connection Settings → Add Device.") {
        Text("Enter the fresh eight-digit code shown on the console. It expires quickly and is not your PSN password or sign-in PIN.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(pin, onPinChange, label = { Text("8-digit Link Device code") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        TextButton(onClick = { onVersionChange(if (version == ConsoleVersion.PS5) ConsoleVersion.PS4_GE_8 else ConsoleVersion.PS5) }) { Text(if (version.isPS5) "Using a PS4 instead?" else "Using a PS5 instead?") }
        Button(onClick = onPair, enabled = pin.length == 8, modifier = Modifier.fillMaxWidth()) { Text("Pair and save console") }
        TextButton(onClick = onOlderConsole) { Text("Set up a PS4 below firmware 7.0") }
    }
}

@Composable private fun StepCard(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF172554))) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(description, style = MaterialTheme.typography.bodyLarge); content() }) }
}

@Composable private fun StatusLine(ok: Boolean, text: String) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.Info, null, tint = if (ok) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Text(text, color = if (ok) Color(0xFFBBF7D0) else Color(0xFFFECACA)) } }

@Composable private fun ProgressOverlay(title: String, detail: String) { Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .8f)), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) { CircularProgressIndicator(); Spacer(Modifier.height(20.dp)); Text(title, style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(8.dp)); Text(detail) } } }

@Composable private fun FailureOverlay(title: String, detail: String, onRetry: () -> Unit, onAdvanced: () -> Unit, retryLabel: String = "Start over", secondaryLabel: String = "Advanced setup") { Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .86f)), contentAlignment = Alignment.Center) { Card(Modifier.padding(24.dp)) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(detail); Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(retryLabel) }; TextButton(onClick = onAdvanced, modifier = Modifier.align(Alignment.End)) { Text(secondaryLabel) } } } } }
