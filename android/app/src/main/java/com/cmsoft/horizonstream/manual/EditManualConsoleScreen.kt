package com.cmsoft.horizonstream.manual

import android.util.Base64
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.cmsoft.horizonstream.R
import com.cmsoft.horizonstream.common.ManualHost
import com.cmsoft.horizonstream.common.ext.viewModelFactory
import com.cmsoft.horizonstream.common.getDatabase
import com.cmsoft.horizonstream.lib.RegistInfo
import com.cmsoft.horizonstream.lib.Target
import com.cmsoft.horizonstream.regist.RegistExecuteViewModel
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.launch

enum class ConsoleVersion(val isPS5: Boolean, val displayName: String) {
    PS5(true, "PlayStation 5"),
    PS4_GE_8(false, "PS4 (Firmware >= 8.0)"),
    PS4_GE_7(false, "PS4 (Firmware 7.0 - 7.5)"),
    PS4_LT_7(false, "PS4 (Firmware < 7.0)")
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditManualConsoleScreen(
    navController: NavController,
    manualHostId: Long?,
    prefilledHost: String? = null
) {
    val context = LocalContext.current
    val database = remember { getDatabase(context) }

    var consoleName by remember {
        mutableStateOf(if ((manualHostId ?: 0L) == 0L) "Playstation" else "")
    }
    var hostAddress by remember { mutableStateOf(prefilledHost ?: "") }
    var psnId by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var selectedVersion by remember { mutableStateOf(ConsoleVersion.PS5) }

    var showQrScanner by remember { mutableStateOf(false) }
    var psnRedirectUrl by remember { mutableStateOf("") }
    var psnLookupError by remember { mutableStateOf<String?>(null) }
    var isRetrievingPsnAccountId by remember { mutableStateOf(false) }
    var isPsnAccountIdCapturedFromQr by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val registViewModel: RegistExecuteViewModel = viewModel(
        factory = viewModelFactory { RegistExecuteViewModel(database) }
    )
    val registState by registViewModel.state.observeAsState(RegistExecuteViewModel.State.IDLE)
    val logText by registViewModel.logText.observeAsState("")

    fun retrievePsnAccountId(redirectUrl: String, wasScannedFromQr: Boolean = false) {
        coroutineScope.launch {
            isRetrievingPsnAccountId = true
            psnLookupError = null
            try {
                psnId = PsnAccountIdLogin.retrieveAccountId(redirectUrl)
                psnRedirectUrl = ""
                isPsnAccountIdCapturedFromQr = wasScannedFromQr
            } catch (error: PsnLoginException) {
                psnLookupError = error.message
            } finally {
                isRetrievingPsnAccountId = false
            }
        }
    }

    // Handle navigation pop on successful registration
    LaunchedEffect(registState) {
        if (registState == RegistExecuteViewModel.State.SUCCESSFUL) {
            navController.popBackStack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF020617)
                    )
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { 
                        Text(
                            stringResource(R.string.title_regist),
                            fontWeight = FontWeight.Bold
                        ) 
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { navController.navigate("help") }) {
                            Icon(Icons.Default.Info, contentDescription = "Help")
                        }
                    }
                )
            }
        ) { paddingValues ->
            // Center the form and constrain width to portrait size (max 420.dp) to provide nice negative space in landscape
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    OutlinedTextField(
                        value = consoleName,
                        onValueChange = { consoleName = it },
                        label = { Text("Console Name / Nickname") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = hostAddress,
                        onValueChange = { hostAddress = it },
                        label = { Text("Console IP Address") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        enabled = prefilledHost == null // Disable editing if pre-filled from discovery
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (selectedVersion != ConsoleVersion.PS4_LT_7) {
                        Text(
                            "1. Get your PSN Account ID",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.Start)
                        )
                        Text(
                            "Do this before getting the temporary Link Device code from your console. The recommended method is to sign in on your computer and scan the redirect QR code with your Quest.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                        )

                        if (!isPsnAccountIdCapturedFromQr) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        "Recommended: scan a sign-in QR code",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "1. On your computer, open the Horizon Stream Chrome extension.\n" +
                                            "2. Select Open PS Remote Play sign-in and finish signing in to PSN.\n" +
                                            "3. Reopen the extension when it shows the QR code, then scan it below.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = { showQrScanner = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            "Scan sign-in QR code",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        if (!isPsnAccountIdCapturedFromQr) {
                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                "Other ways to provide your Account ID",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Text(
                                "If you obtained an Account ID elsewhere, paste its Base64 value below. Or paste a one-time PS Remote Play redirect URL and let Horizon Stream retrieve the ID.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray,
                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                            )
                        }

                        OutlinedTextField(
                            value = psnId,
                            onValueChange = {
                                psnId = it
                                isPsnAccountIdCapturedFromQr = false
                            },
                            label = { Text(stringResource(R.string.hint_regist_psn_account_id)) },
                            placeholder = { Text("8-byte Base64 Account ID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        if (isPsnAccountIdCapturedFromQr) {
                            Text(
                                "Sign-in QR code captured successfully. Your Account ID is ready.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .align(Alignment.Start)
                                    .padding(top = 6.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = psnRedirectUrl,
                                onValueChange = {
                                    psnRedirectUrl = it
                                    psnLookupError = null
                                },
                                label = { Text("PS Remote Play redirect URL") },
                                placeholder = { Text("https://remoteplay.dl.playstation.net/remoteplay/redirect?code=…") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                minLines = 3,
                                maxLines = 4
                            )
                            Button(
                                onClick = {
                                    retrievePsnAccountId(psnRedirectUrl)
                                },
                                enabled = psnRedirectUrl.isNotBlank() && !isRetrievingPsnAccountId,
                                modifier = Modifier.align(Alignment.Start)
                            ) {
                                if (isRetrievingPsnAccountId) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(if (isRetrievingPsnAccountId) "Retrieving Account ID…" else "Use redirect URL")
                            }
                            psnLookupError?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    } else {
                        Text(
                            "1. Enter your PSN Online ID",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.Start)
                        )
                        Text(
                            "Pre-7.0 PS4 registration uses your PSN Online ID instead of an Account ID.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                        )
                        OutlinedTextField(
                            value = psnId,
                            onValueChange = { psnId = it },
                            label = { Text(stringResource(R.string.hint_regist_psn_online_id)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        "2. Get your console Link Device code",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Text(
                        if (selectedVersion.isPS5) {
                            "Now, on your PS5, open Settings → System → Remote Play → Link Device. Enter the temporary 8-digit code shown there. It expires quickly and is not your PSN password or sign-in PIN."
                        } else {
                            "Now, on your PS4, open Settings → Remote Play Connection Settings → Add Device. Enter the temporary 8-digit code shown there."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { if (it.length <= 8) pin = it },
                        label = { Text("8-Digit Console Link Device Code") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Console Version Selector
                    Text(
                        text = "3. Select console version",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.align(Alignment.Start),
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    ConsoleVersion.values().forEach { version ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clickable { selectedVersion = version },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (selectedVersion == version),
                                onClick = { selectedVersion = version }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = version.displayName, color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = {
                            // First, create the ManualHost entry to get an ID, then register
                            val manualHost = ManualHost(host = hostAddress, registeredHost = null)
                            database.manualHostDao().insert(manualHost)
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe({ generatedId ->
                                    val target = when (selectedVersion) {
                                        ConsoleVersion.PS5 -> Target.PS5_1
                                        ConsoleVersion.PS4_GE_8 -> Target.PS4_10
                                        ConsoleVersion.PS4_GE_7 -> Target.PS4_9
                                        ConsoleVersion.PS4_LT_7 -> Target.PS4_8
                                    }
                                    
                                    val psnOnlineId: String? = if (selectedVersion == ConsoleVersion.PS4_LT_7) psnId else null
                                    val psnAccountId: ByteArray? = if (selectedVersion != ConsoleVersion.PS4_LT_7) {
                                        try { Base64.decode(psnId, Base64.DEFAULT) } catch (e: Exception) { null }
                                    } else null

                                    val pinInt = pin.toIntOrNull() ?: 0
                                    
                                    val registInfo = RegistInfo(target, hostAddress, false, psnOnlineId, psnAccountId, pinInt)
                                    registViewModel.start(registInfo, generatedId)
                                }, {
                                    it.printStackTrace()
                                })
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = consoleName.isNotBlank() && hostAddress.isNotBlank() && psnId.isNotBlank() && pin.length == 8 && pin.all(Char::isDigit)
                    ) {
                        Text("Register & Save", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // Fullscreen overlay for registration logs/progress
        if (registState == RegistExecuteViewModel.State.RUNNING || 
            registState == RegistExecuteViewModel.State.FAILED || 
            registState == RegistExecuteViewModel.State.SUCCESSFUL_DUPLICATE) {
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (registState == RegistExecuteViewModel.State.RUNNING) {
                        CircularProgressIndicator(modifier = Modifier.size(56.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Registering with console...", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    } else if (registState == RegistExecuteViewModel.State.FAILED) {
                        Text("Registration Failed", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.regist_info_failed), color = Color.LightGray)
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { registViewModel.stop() }) {
                            Text("Go Back")
                        }
                    } else if (registState == RegistExecuteViewModel.State.SUCCESSFUL_DUPLICATE) {
                        Text("Duplicate Registration", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("This console's MAC address is already registered. Do you want to overwrite it?", color = Color.LightGray)
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TextButton(onClick = { registViewModel.stop() }) {
                                Text("Discard", color = Color.White)
                            }
                            Button(onClick = { registViewModel.saveHost() }) {
                                Text("Overwrite")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Display logs
                    Card(
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                    ) {
                        Box(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
                            Text(logText, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                        }
                    }
                }
            }
        }
    }

    if (showQrScanner) {
        Dialog(
            onDismissRequest = { showQrScanner = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            AccountIdQrScanner(
                onTransferScanned = { transfer ->
                    showQrScanner = false
                    when (transfer) {
                        is PsnQrTransfer.AccountId -> {
                            psnId = transfer.value
                            isPsnAccountIdCapturedFromQr = true
                        }
                        is PsnQrTransfer.RedirectUrl -> {
                            psnRedirectUrl = transfer.value
                            retrievePsnAccountId(transfer.value, wasScannedFromQr = true)
                        }
                    }
                },
                onDismissRequest = { showQrScanner = false }
            )
        }
    }
}
