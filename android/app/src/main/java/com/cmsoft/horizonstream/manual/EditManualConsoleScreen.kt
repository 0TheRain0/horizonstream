package com.cmsoft.horizonstream.manual

import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.viewinterop.AndroidView
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ConsoleVersion(val isPS5: Boolean, val displayName: String) {
    PS5(true, "PlayStation 5"),
    PS4_GE_8(false, "PS4 (Firmware >= 8.0)"),
    PS4_GE_7(false, "PS4 (Firmware 7.0 - 7.5)"),
    PS4_LT_7(false, "PS4 (Firmware < 7.0)")
}

private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
private const val PSN_LOGIN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize?service_entity=urn:service-entity:psn&response_type=code&client_id=$CLIENT_ID&redirect_uri=https://remoteplay.dl.playstation.net/remoteplay/redirect&scope=psn:clientapp referenceDataService:countryConfig.read pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update&request_locale=en_US&ui=pr&service_logo=ps&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal&"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditManualConsoleScreen(
    navController: NavController,
    manualHostId: Long?,
    prefilledHost: String? = null
) {
    val context = LocalContext.current
    val database = remember { getDatabase(context) }

    var consoleName by remember { mutableStateOf("") }
    var hostAddress by remember { mutableStateOf(prefilledHost ?: "") }
    var psnId by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var selectedVersion by remember { mutableStateOf(ConsoleVersion.PS5) }

    // PSN Helper WebView flow state
    var showPsnWebView by remember { mutableStateOf(false) }
    var isPsnFetching by remember { mutableStateOf(false) }

    val registViewModel: RegistExecuteViewModel = viewModel(
        factory = viewModelFactory { RegistExecuteViewModel(database) }
    )
    val registState by registViewModel.state.observeAsState(RegistExecuteViewModel.State.IDLE)
    val logText by registViewModel.logText.observeAsState("")

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

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = psnId,
                            onValueChange = { psnId = it },
                            label = { 
                                Text(
                                    if (selectedVersion == ConsoleVersion.PS4_LT_7) 
                                        stringResource(R.string.hint_regist_psn_online_id) 
                                    else 
                                        stringResource(R.string.hint_regist_psn_account_id)
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Premium PSN ID Helper Button
                    TextButton(
                        onClick = { showPsnWebView = true },
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(
                            if (isPsnFetching) "Fetching Account ID..." else "Retrieve Account ID via PSN Login",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = pin,
                        onValueChange = { if (it.length <= 8) pin = it },
                        label = { Text("8-Digit PSN PIN") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Console Version Selector
                    Text(
                        text = "Console Version",
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
                        enabled = consoleName.isNotBlank() && hostAddress.isNotBlank() && psnId.isNotBlank() && pin.length == 8
                    ) {
                        Text("Register & Save", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // Fullscreen WebView Dialog for PSN Login
        if (showPsnWebView) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1E293B))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Sign In with PSN", color = Color.White, fontWeight = FontWeight.Bold)
                            TextButton(onClick = { showPsnWebView = false }) {
                                Text("Close", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                        
                        AndroidView(
                            factory = { context ->
                                WebView(context).apply {
                                    layoutParams = android.view.ViewGroup.LayoutParams(
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    webViewClient = object : WebViewClient() {
                                        private fun checkRedirect(url: String?): Boolean {
                                            if (url != null && url.startsWith("https://remoteplay.dl.playstation.net/remoteplay/redirect")) {
                                                val uri = android.net.Uri.parse(url)
                                                val code = uri.getQueryParameter("code")
                                                if (code != null) {
                                                    showPsnWebView = false
                                                    isPsnFetching = true
                                                    fetchPsnAccountId(code) { accountId ->
                                                        isPsnFetching = false
                                                        if (accountId != null) {
                                                            psnId = accountId
                                                        }
                                                    }
                                                    return true
                                                }
                                            }
                                            return false
                                        }

                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            return checkRedirect(request?.url?.toString())
                                        }

                                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            checkRedirect(url)
                                        }
                                    }
                                    loadUrl(PSN_LOGIN_URL)
                                }
                            },
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    }
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
}

// Background network logic to fetch and convert user_id to 64-bit base64 ID
private fun fetchPsnAccountId(code: String, callback: (String?) -> Unit) {
    Thread {
        try {
            // 1. Exchange redirect code for authorization token
            val tokenUrl = URL("https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token")
            val conn = tokenUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            val auth = Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $auth")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true

            val body = "grant_type=authorization_code&code=$code&scope=psn:clientapp referenceDataService:countryConfig.read pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update&redirect_uri=https://remoteplay.dl.playstation.net/remoteplay/redirect&"
            conn.outputStream.use { os ->
                os.write(body.toByteArray())
            }

            if (conn.responseCode != 200) {
                callback(null)
                return@Thread
            }

            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            val accessToken = json.getString("access_token")

            // 2. Fetch profile user_id using access token
            val userUrl = URL("https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token/$accessToken")
            val conn2 = userUrl.openConnection() as HttpURLConnection
            conn2.requestMethod = "GET"
            conn2.setRequestProperty("Authorization", "Basic $auth")
            conn2.setRequestProperty("Content-Type", "application/json")

            if (conn2.responseCode != 200) {
                callback(null)
                return@Thread
            }

            val responseText2 = conn2.inputStream.bufferedReader().use { it.readText() }
            val json2 = JSONObject(responseText2)
            val userIdStr = json2.getString("user_id")
            val userId = userIdStr.toLong()

            // 3. Serialize user_id to 8-bytes in little endian format, then encode to Base64
            val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(userId).array()
            val base64Id = Base64.encodeToString(buffer, Base64.NO_WRAP)
            
            callback(base64Id)
        } catch (e: Exception) {
            e.printStackTrace()
            callback(null)
        }
    }.start()
}
