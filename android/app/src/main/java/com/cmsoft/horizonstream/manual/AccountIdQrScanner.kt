package com.cmsoft.horizonstream.manual

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val QR_TRANSFER_PREFIX = "HORIZONSTREAM:ACCOUNT_ID:"
private const val QR_REDIRECT_TRANSFER_PREFIX = "HORIZONSTREAM:PSN_REDIRECT:"
private const val HEADSET_CAMERA_PERMISSION = "horizonos.permission.HEADSET_CAMERA"
private const val META_CAMERA_SOURCE_KEY = "com.meta.extra_metadata.camera_source"
private const val META_CAMERA_SOURCE_PASSTHROUGH = 0

private val passthroughCameraSourceKey = CameraCharacteristics.Key(
    META_CAMERA_SOURCE_KEY,
    Int::class.javaObjectType
)

internal sealed interface PsnQrTransfer {
    data class AccountId(val value: String) : PsnQrTransfer
    data class RedirectUrl(val value: String) : PsnQrTransfer
}

@Composable
internal fun AccountIdQrScanner(
    onTransferScanned: (PsnQrTransfer) -> Unit,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(context.hasPassthroughCameraPermission())
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted && context.hasPassthroughCameraPermission()
        if (!hasCameraPermission) {
            cameraError = "Passthrough camera access is required to scan the QR code."
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(HEADSET_CAMERA_PERMISSION)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF020617)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Scan PSN sign-in QR code", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Look directly at the QR code displayed by the Horizon Stream Chrome extension. The headset camera moves with your head, so keep the code inside the center frame.",
                style = MaterialTheme.typography.bodyMedium
            )
            if (hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    QrCameraPreview(
                        onTransferScanned = onTransferScanned,
                        onCameraError = { cameraError = it }
                    )
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .border(
                                width = 3.dp,
                                color = Color.White,
                                shape = RoundedCornerShape(20.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color.White, RoundedCornerShape(50))
                        )
                    }
                    Text(
                        "Move your head to aim the center frame",
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp)
                        .background(Color(0xFF111827)),
                    contentAlignment = Alignment.Center
                ) {
                    Button(onClick = { permissionLauncher.launch(HEADSET_CAMERA_PERMISSION) }) {
                        Text("Allow passthrough camera access")
                    }
                }
            }
            cameraError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onDismissRequest) { Text("Cancel") }
        }
    }
}

@Composable
private fun QrCameraPreview(
    onTransferScanned: (PsnQrTransfer) -> Unit,
    onCameraError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    val latestOnTransferScanned by rememberUpdatedState(onTransferScanned)
    val latestOnCameraError by rememberUpdatedState(onCameraError)
    val isProcessing = remember { AtomicBoolean(false) }
    val hasCompleted = remember { AtomicBoolean(false) }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            try {
                provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    if (hasCompleted.get() || !isProcessing.compareAndSet(false, true)) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val mediaImage = imageProxy.image
                    if (mediaImage == null) {
                        isProcessing.set(false)
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener(mainExecutor) { barcodes ->
                            val transfer = barcodes
                                .asSequence()
                                .mapNotNull { barcode -> decodeQrTransfer(barcode.rawValue) }
                                .firstOrNull()
                            if (transfer != null && hasCompleted.compareAndSet(false, true)) {
                                latestOnTransferScanned(transfer)
                            }
                        }
                        .addOnCompleteListener {
                            isProcessing.set(false)
                            imageProxy.close()
                        }
                }
                val cameraSelector = passthroughCameraSelector(provider)
                provider?.unbindAll()
                provider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    analysis
                )
            } catch (error: Exception) {
                latestOnCameraError("Quest could not start its passthrough camera. Update Horizon OS and allow camera access, or paste the Account ID manually.")
            }
        }, mainExecutor)

        onDispose {
            provider?.unbindAll()
            scanner.close()
            analysisExecutor.shutdown()
        }
    }
}

private fun passthroughCameraSelector(provider: ProcessCameraProvider?): CameraSelector {
    val selector = CameraSelector.Builder()
        .addCameraFilter { cameraInfos ->
            cameraInfos.filter { cameraInfo ->
                runCatching {
                    Camera2CameraInfo.from(cameraInfo)
                        .getCameraCharacteristic(passthroughCameraSourceKey) == META_CAMERA_SOURCE_PASSTHROUGH
                }.getOrDefault(false)
            }
        }
        .build()

    if (provider?.hasCamera(selector) == true) return selector
    throw IllegalStateException("No Meta passthrough camera is available")
}

private fun Context.hasPassthroughCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, HEADSET_CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED

internal fun decodeQrTransfer(value: String?): PsnQrTransfer? {
    val payload = value ?: return null
    if (payload.startsWith(QR_REDIRECT_TRANSFER_PREFIX)) {
        val redirectUrl = payload.removePrefix(QR_REDIRECT_TRANSFER_PREFIX)
        return try {
            PsnAccountIdLogin.validateRedirectUrl(redirectUrl)
            PsnQrTransfer.RedirectUrl(redirectUrl)
        } catch (_: PsnLoginException) {
            null
        }
    }

    val accountId = payload.removePrefix(QR_TRANSFER_PREFIX)
    if (accountId == payload || !accountId.matches(Regex("[A-Za-z0-9+/]{11}="))) return null
    return try {
        accountId.takeIf { Base64.decode(it, Base64.DEFAULT).size == 8 }?.let(PsnQrTransfer::AccountId)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun Context.findLifecycleOwner(): LifecycleOwner {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is LifecycleOwner) return current
        current = current.baseContext
    }
    if (current is LifecycleOwner) return current
    error("Horizon Stream needs an activity lifecycle to scan QR codes.")
}
