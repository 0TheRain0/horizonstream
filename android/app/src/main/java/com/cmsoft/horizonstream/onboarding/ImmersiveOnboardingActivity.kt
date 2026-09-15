package com.cmsoft.horizonstream.onboarding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Path
import android.graphics.RectF
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import androidx.annotation.Keep
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.cmsoft.horizonstream.common.ManualHost
import com.cmsoft.horizonstream.common.Preferences
import com.cmsoft.horizonstream.common.getDatabase
import com.cmsoft.horizonstream.common.ext.viewModelFactory
import com.cmsoft.horizonstream.manual.PsnAccountIdLogin
import com.cmsoft.horizonstream.manual.PsnLoginException
import com.cmsoft.horizonstream.manual.PsnQrTransfer
import com.cmsoft.horizonstream.manual.decodeQrTransfer
import com.cmsoft.horizonstream.lib.ConnectInfo
import com.cmsoft.horizonstream.lib.RegistInfo
import com.cmsoft.horizonstream.lib.Target
import com.cmsoft.horizonstream.regist.RegistExecuteViewModel
import com.cmsoft.horizonstream.stream.StreamActivity
import com.cmsoft.horizonstream.stream.VRStreamActivity
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.launch

/** A real OpenXR entry point for first-time setup; it owns no stream session. */
class ImmersiveOnboardingActivity : ComponentActivity() {
    private external fun nativeInitOnboardingVR(activity: ImmersiveOnboardingActivity): android.view.Surface?
    private external fun nativeStartOnboardingRenderLoop()
    private external fun nativeSetOnboardingOverlay(bytes: ByteArray, width: Int, height: Int)
    private external fun nativeSetOnboardingHeadLocked(enabled: Boolean)
    private external fun nativeStopOnboardingVR()
    private var page = 0
    private val selectedConsoleName by lazy { intent.getStringExtra(EXTRA_CONSOLE_NAME)?.takeIf { it.isNotBlank() } ?: "Your PlayStation" }
    private val selectedConsoleAddress by lazy { intent.getStringExtra(EXTRA_CONSOLE_ADDRESS)?.takeIf { it.isNotBlank() }.orEmpty() }
    private val preferences by lazy { Preferences(applicationContext) }
    private val pages by lazy {
        val hasSavedAccount = accountId != null
        listOf(
            "Ready to pair" to if (hasSavedAccount) {
                listOf("$selectedConsoleName was found on this network and is ready to pair.", "Your PlayStation Network sign-in is already saved on this headset.", "We’ll go straight to the PS5 Link Device step.")
            } else {
                listOf("$selectedConsoleName was found on this network and is ready to pair.", "We’ll securely retrieve the Remote Play details it needs next.")
            },
            "Sign in to PlayStation Network on your computer" to listOf("Use a computer with Google Chrome.", "Search Google for “Horizon Stream Chrome extension” and install it.", "Open the extension and sign in to PlayStation Network. When it finishes, it shows a QR code.", "Leave that QR code open: we’ll scan it in the next step."),
            "Scan the one-time QR code" to listOf("In the Chrome extension, complete the PlayStation Network sign-in.", "Keep its newly generated QR code open on the computer for Quest to scan.", "The QR code is sensitive, expires quickly, and is never shared."),
            "PlayStation Network connected" to listOf("Your sign-in is complete and ready to use.", "On your PS5, open Settings → System → Remote Play.", "Choose Link Device. If Remote Play is off, turn it on first.", "When the eight-digit code appears, leave it visible and press any button on your Quest controller to scan it."),
            "Scan the Link Device code" to listOf("Keep the eight-digit code on your PS5 screen.", "We’ll read it from the camera and use it to securely pair this headset."),
            "Link Device code captured" to listOf("The eight-digit code was read successfully.", "Press any button on your Quest controller to connect to your PlayStation."),
            "Pairing your PlayStation" to listOf("Keep the PS5 on and leave the Link Device screen open.", "We’re saving the secure Remote Play keys now. This can take a few seconds."),
            "Controllers ready" to listOf(
                "A / B  →  Cross / Circle",
                "X / Y  →  Square / Triangle",
                "Triggers  →  L2 / R2",
                "Thumbsticks  →  movement / camera",
                "Grips  →  L1 / R1",
                "Left grip + left stick  →  D-pad",
                "Stick clicks  →  L3 / R3",
                "Menu tap  →  Options",
                "Menu double-tap  →  PlayStation button",
                "Menu hold  →  exit stream",
                "Other controllers: pair a DualSense, DualShock 4, or Bluetooth gamepad in Quest Settings → Devices → Bluetooth.",
                "Press any button when you’re ready to connect."
            ),
            "Connected" to listOf("Your PlayStation is paired and ready.", "Starting your immersive stream…")
        )
    }
    private val database by lazy { getDatabase(applicationContext) }
    private val registrationViewModel by lazy {
        ViewModelProvider(this, viewModelFactory { RegistExecuteViewModel(database) })[RegistExecuteViewModel::class.java]
    }
    private val disposables = CompositeDisposable()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var accountId: String? = null
    private var linkDeviceCode: Int? = null
    private var manualHostId: Long? = null
    private var pairingFailed = false
    private var streamLaunchScheduled = false
    private var onboardingVrStarted = false
    private var onboardingVrStopped = false
    private var previousButtons = 0
    private var scanning = false
    private var scanStatus = ""
    @Volatile private var cameraFrame: ByteArray? = null
    // ML Kit's component graph is initialized by a ContentProvider. Never create
    // these while Android is constructing the Activity: a broken or delayed graph
    // must show a recoverable setup error, not prevent the Activity from launching.
    private var scanner: BarcodeScanner? = null
    private var textRecognizer: TextRecognizer? = null
    private var stableCode = ""
    private var stableCodeFrames = 0
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private val scanCompleted = AtomicBoolean(false)
    private val qrFrameInFlight = AtomicBoolean(false)
    private val codeFrameInFlight = AtomicBoolean(false)
    private var lastPreviewUpdateMs = 0L
    @Volatile private var rejectedQrPayload: String? = null
    private val previewLock = Any()
    private val previewBuffers = Array(2) { ByteArray(PREVIEW_BYTE_COUNT) }
    private var nextPreviewBuffer = 0
    private val previewPixels = IntArray(PREVIEW_SIZE * PREVIEW_SIZE)
    private val previewBitmap = Bitmap.createBitmap(PREVIEW_SIZE, PREVIEW_SIZE, Bitmap.Config.ARGB_8888)
    private val overlayBitmap = Bitmap.createBitmap(OVERLAY_WIDTH, OVERLAY_HEIGHT, Bitmap.Config.ARGB_8888)
    private val overlayCanvas = Canvas(overlayBitmap)
    private val overlayPixels = IntArray(OVERLAY_WIDTH * OVERLAY_HEIGHT)
    private val overlayBytes = ByteArray(OVERLAY_WIDTH * OVERLAY_HEIGHT * 4)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        accountId = preferences.psnAccountId
        registrationViewModel.state.observe(this) { handleRegistrationState(it) }
    }
    override fun onResume() {
        super.onResume()
        if (onboardingVrStarted) {
            // Returning from Android's permission sheet must not try to create
            // a second OpenXR session. Restart a scan only after the user has
            // granted headset-camera permission.
            if ((page == QR_PAGE || page == LINK_PAGE) && !scanning && hasCameraPermissions()) {
                if (page == QR_PAGE) beginQrScan() else beginCodeScan()
            }
            return
        }
        if (nativeInitOnboardingVR(this) != null) {
            onboardingVrStarted = true
            draw()
            nativeSetOnboardingHeadLocked(isHeadLockedPage())
            nativeStartOnboardingRenderLoop()
        } else finish()
    }
    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopCameraAnalysis()
        scanner?.close()
        textRecognizer?.close()
        scanExecutor.shutdown()
        stopOnboardingVr()
        disposables.dispose()
        super.onDestroy()
    }

    private fun qrScannerOrNull(): BarcodeScanner? {
        scanner?.let { return it }
        return runCatching {
            BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
            )
        }.onFailure { error ->
            Log.e(TAG, "Unable to initialize the QR scanner", error)
        }.getOrNull()?.also { scanner = it }
    }

    private fun textRecognizerOrNull(): TextRecognizer? {
        textRecognizer?.let { return it }
        return runCatching {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }.onFailure { error ->
            Log.e(TAG, "Unable to initialize the Link Device code scanner", error)
        }.getOrNull()?.also { textRecognizer = it }
    }
    @Keep private fun onNativeControllerState(lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float, lg: Float, rg: Float, buttons: Int) {
        val pressed = buttons and previousButtons.inv(); previousButtons = buttons
        if (pressed == 0) return
        runOnUiThread {
            when {
                page == PAIRING_PAGE && pairingFailed -> retryLinkScan()
                page == PAIRING_PAGE || page == STREAM_SUCCESS_PAGE || scanning -> Unit
                page == LINK_CONFIRMED_PAGE && linkDeviceCode != null -> beginPairing()
                page == LINK_PAGE && linkDeviceCode != null -> beginPairing()
                page == READY_PAGE && accountId != null -> showPage(ACCOUNT_CONFIRMED_PAGE)
                page == CONTROLLER_PAGE -> {
                    showPage(STREAM_SUCCESS_PAGE)
                    scanStatus = "Pairing complete. Starting your immersive stream…"
                    draw()
                    mainHandler.postDelayed({ launchConnectedStream() }, SUCCESS_SCREEN_DELAY_MS)
                }
                page + 1 < pages.size -> showPage(page + 1)
            }
        }
    }
    private fun isHeadLockedPage() = page == QR_PAGE || page == LINK_PAGE

    private fun showPage(nextPage: Int) {
        if (nextPage !in pages.indices) return
        if (nextPage != QR_PAGE && nextPage != LINK_PAGE) stopCameraAnalysis()
        page = nextPage
        nativeSetOnboardingHeadLocked(isHeadLockedPage())
        draw()
        when (page) {
            QR_PAGE -> beginQrScan()
            LINK_PAGE -> beginCodeScan()
        }
    }

    private fun stopCameraAnalysis() {
        scanCompleted.set(true)
        qrFrameInFlight.set(false)
        codeFrameInFlight.set(false)
        scanning = false
        synchronized(previewLock) { cameraFrame = null }
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({ runCatching { providerFuture.get().unbindAll() } }, ContextCompat.getMainExecutor(this))
    }

    private fun stopOnboardingVr() {
        if (onboardingVrStopped) return
        onboardingVrStopped = true
        nativeStopOnboardingVR()
    }

    private fun retryLinkScan() {
        val oldManualHostId = manualHostId
        if (oldManualHostId != null) {
            val cleanup = database.manualHostDao().getById(oldManualHostId)
                .flatMapCompletable { database.manualHostDao().delete(it) }
                .subscribeOn(Schedulers.io())
                .subscribe({}, {})
            disposables.add(cleanup)
        }
        registrationViewModel.reset()
        manualHostId = null
        pairingFailed = false
        linkDeviceCode = null
        showPage(LINK_PAGE)
    }

    private fun passthroughCameraSelector(provider: ProcessCameraProvider): CameraSelector {
        val sourceKey = CameraCharacteristics.Key(
            "com.meta.extra_metadata.camera_source",
            Int::class.javaObjectType
        )
        val positionKey = CameraCharacteristics.Key(
            "com.meta.extra_metadata.position",
            Int::class.javaObjectType
        )
        val leftCameraSelector = CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter {
                    runCatching {
                        val info = Camera2CameraInfo.from(it)
                        info.getCameraCharacteristic(sourceKey) == 0 &&
                            info.getCameraCharacteristic(positionKey) == 0
                    }.getOrDefault(false)
                }
            }
            .build()
        if (provider.hasCamera(leftCameraSelector)) return leftCameraSelector
        // Older firmware may not surface the position vendor tag; never fall
        // back to an avatar camera, only to another passthrough RGB camera.
        val passthroughSelector = CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter {
                runCatching {
                    Camera2CameraInfo.from(it).getCameraCharacteristic(sourceKey) == 0
                }.getOrDefault(false)
            } }
            .build()
        if (provider.hasCamera(passthroughSelector)) return passthroughSelector
        throw IllegalStateException("No Meta passthrough camera is available")
    }

    private fun beginQrScan() {
        if (scanning) return
        if (!hasCameraPermissions()) {
            scanStatus = "Allow camera access to scan the QR code."; requestPermissions(cameraPermissions, CAMERA_REQUEST); draw(); return
        }
        val barcodeScanner = qrScannerOrNull() ?: run {
            scanning = false
            scanStatus = "QR scanning could not start. Return to the main screen and use manual setup."
            draw()
            return
        }
        scanning = true
        scanCompleted.set(false)
        qrFrameInFlight.set(false)
        rejectedQrPayload = null
        lastPreviewUpdateMs = 0L
        synchronized(previewLock) { cameraFrame = null }
        scanStatus = "Looking for the sign-in QR code…"
        draw()
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(scanExecutor) { image ->
                    val media = image.image
                    if (media == null || scanCompleted.get() || !qrFrameInFlight.compareAndSet(false, true)) { image.close(); return@setAnalyzer }
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastPreviewUpdateMs >= PREVIEW_INTERVAL_MS) {
                        updateCameraPreview(image)
                        lastPreviewUpdateMs = now
                        runOnUiThread { if (page == QR_PAGE) draw() }
                    }
                    barcodeScanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            codes.asSequence().mapNotNull { barcode ->
                                val payload = barcode.rawValue ?: return@mapNotNull null
                                if (payload == rejectedQrPayload) return@mapNotNull null
                                decodeQrTransfer(payload)?.let { payload to it }
                            }.firstOrNull()?.let { (payload, transfer) ->
                                if (scanCompleted.compareAndSet(false, true)) handleTransfer(payload, transfer)
                            }
                        }.addOnCompleteListener { qrFrameInFlight.set(false); image.close() }
                }
                val selector = passthroughCameraSelector(provider)
                provider.unbindAll(); provider.bindToLifecycle(this, selector, analysis)
            }.onFailure { error ->
                Log.e(TAG, "Unable to bind the passthrough camera for QR scanning", error)
                scanStatus = cameraUnavailableMessage(error)
                scanning = false
                draw()
            }
        }, ContextCompat.getMainExecutor(this))
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grants)
        if (requestCode == CAMERA_REQUEST && hasCameraPermissions()) {
            if (page == LINK_PAGE) beginCodeScan() else beginQrScan()
        } else {
            scanStatus = "Headset camera access is needed for scanning. Allow it in Quest settings."
            scanning = false
            draw()
        }
    }
    private fun handleTransfer(payload: String, transfer: PsnQrTransfer) {
        lifecycleScope.launch {
            scanStatus = "Securing your PlayStation account…"
            draw()
            try {
                accountId = when (transfer) {
                    is PsnQrTransfer.AccountId -> transfer.value
                    is PsnQrTransfer.RedirectUrl -> PsnAccountIdLogin.retrieveAccountId(transfer.value)
                }
                preferences.psnAccountId = accountId
                scanning = false
                showPage(ACCOUNT_CONFIRMED_PAGE)
            } catch (e: PsnLoginException) {
                rejectedQrPayload = payload
                scanCompleted.set(false)
                qrFrameInFlight.set(false)
                scanning = true
                scanStatus = "That code expired. Make a fresh QR code in Chrome; I’m watching for the new one."
                draw()
            }
        }
    }
    private fun beginCodeScan() {
        if (scanning) return
        if (!hasCameraPermissions()) { scanStatus = "Allow camera access to scan the Link Device code."; requestPermissions(cameraPermissions, CAMERA_REQUEST); draw(); return }
        val recognizer = textRecognizerOrNull() ?: run {
            scanning = false
            scanStatus = "Link Device scanning could not start. Restart setup and try again."
            draw()
            return
        }
        scanning = true
        linkDeviceCode = null
        stableCode = ""
        stableCodeFrames = 0
        codeFrameInFlight.set(false)
        lastPreviewUpdateMs = 0L
        synchronized(previewLock) { cameraFrame = null }
        scanStatus = "Looking for the eight-digit Link Device code…"
        draw()
        ProcessCameraProvider.getInstance(this).addListener({ runCatching {
            val provider = ProcessCameraProvider.getInstance(this).get(); val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(scanExecutor) { image ->
                val media = image.image
                if (media == null || !scanning || !codeFrameInFlight.compareAndSet(false, true)) {
                    image.close()
                    return@setAnalyzer
                }
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastPreviewUpdateMs >= PREVIEW_INTERVAL_MS) {
                    updateCameraPreview(image)
                    lastPreviewUpdateMs = now
                    runOnUiThread { if (page == LINK_PAGE) draw() }
                }
                recognizer.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)).addOnSuccessListener { text ->
                    Regex("(?<!\\d)(\\d{4})\\s?(\\d{4})(?!\\d)").find(text.text)?.let { match ->
                        val code = match.groupValues[1] + match.groupValues[2]
                        if (code == stableCode) stableCodeFrames++ else { stableCode=code; stableCodeFrames=1 }
                        if (stableCodeFrames >= 3) {
                            linkDeviceCode = code.toIntOrNull()
                            scanning = false
                            stopCameraAnalysis()
                            runOnUiThread {
                                showPage(LINK_CONFIRMED_PAGE)
                                scanStatus = "Code $code found. Press a button on your Quest controller to connect."
                                draw()
                            }
                        }
                    }
                }.addOnCompleteListener { codeFrameInFlight.set(false); image.close() }
            }
            val selector = passthroughCameraSelector(provider); provider.unbindAll(); provider.bindToLifecycle(this,selector,analysis)
        }.onFailure { error ->
            Log.e(TAG, "Unable to bind the passthrough camera for Link Device scanning", error)
            scanning = false
            scanStatus = cameraUnavailableMessage(error)
            draw()
        } }, ContextCompat.getMainExecutor(this))
    }

    private fun hasCameraPermissions(): Boolean = cameraPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun cameraUnavailableMessage(error: Throwable): String {
        val message = error.message.orEmpty()
        return if (message.contains("No Meta passthrough camera", ignoreCase = true) ||
            message.contains("no camera", ignoreCase = true)) {
            "This headset does not expose a passthrough camera to apps. Quest 2 cannot scan setup codes; use a Quest 3 or Quest 3S, or return to the main screen and use manual setup."
        } else {
            "The headset camera could not start. Confirm headset camera access is allowed, then try setup again."
        }
    }

    private fun beginPairing() {
        val encodedAccount = accountId
        val address = selectedConsoleAddress
        val pin = linkDeviceCode
        if (encodedAccount.isNullOrBlank() || address.isBlank() || pin == null) {
            pairingFailed = true
            scanning = false
            scanStatus = "Pairing needs a fresh account sign-in and Link Device code. Press a button to scan again."
            page = PAIRING_PAGE
            nativeSetOnboardingHeadLocked(false)
            draw()
            return
        }
        val accountBytes = runCatching { Base64.decode(encodedAccount, Base64.DEFAULT) }.getOrNull()
        if (accountBytes == null || accountBytes.size != RegistInfo.ACCOUNT_ID_SIZE) {
            pairingFailed = true
            scanning = false
            scanStatus = "The PlayStation account sign-in was incomplete. Press a button to start again."
            page = PAIRING_PAGE
            nativeSetOnboardingHeadLocked(false)
            draw()
            return
        }
        pairingFailed = false
        scanning = true
        showPage(PAIRING_PAGE)
        scanStatus = "Pairing with $selectedConsoleName…"
        draw()
        val target = Target.PS5_1
        val info = RegistInfo(target, address, false, null, accountBytes, pin)
        val save = database.manualHostDao().insert(ManualHost(host = address, registeredHost = null))
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ id ->
                manualHostId = id
                registrationViewModel.start(info, id)
            }, {
                showPairingFailure("We couldn’t prepare this console for pairing. Check that it is awake and on the same network.")
            })
        disposables.add(save)
    }

    private fun handleRegistrationState(state: RegistExecuteViewModel.State) {
        when (state) {
            RegistExecuteViewModel.State.RUNNING -> {
                page = PAIRING_PAGE
                pairingFailed = false
                scanning = true
                scanStatus = "Pairing with $selectedConsoleName…"
                draw()
            }
            RegistExecuteViewModel.State.SUCCESSFUL_DUPLICATE -> {
                page = PAIRING_PAGE
                scanning = true
                scanStatus = "Refreshing the saved PlayStation registration…"
                draw()
                registrationViewModel.saveHost()
            }
            RegistExecuteViewModel.State.SUCCESSFUL -> {
                scanning = false
                pairingFailed = false
                showPage(CONTROLLER_PAGE)
                scanStatus = "Pairing complete. Review your controller options, then press a button to connect."
                draw()
            }
            RegistExecuteViewModel.State.FAILED,
            RegistExecuteViewModel.State.STOPPED -> {
                showPairingFailure("Pairing didn’t complete. Make sure the PS5 is awake, Remote Play is enabled, and the code is still visible.")
            }
            RegistExecuteViewModel.State.IDLE -> Unit
        }
    }

    private fun showPairingFailure(message: String) {
        scanning = false
        pairingFailed = true
        page = PAIRING_PAGE
        nativeSetOnboardingHeadLocked(false)
        scanStatus = "$message Press a button to scan a fresh code."
        draw()
    }

    private fun launchConnectedStream() {
        if (streamLaunchScheduled || isFinishing) return
        val host = registrationViewModel.host ?: run {
            showPairingFailure("The console paired, but its connection keys were not available yet.")
            return
        }
        streamLaunchScheduled = true
        val connectInfo = ConnectInfo(
            ps5 = true,
            host = selectedConsoleAddress,
            registKey = host.rpRegistKey,
            morning = host.rpKey,
            videoProfile = Preferences(this).videoProfile
        )
        stopCameraAnalysis()
        // Give Horizon OS a moment to release the onboarding OpenXR task before
        // the stream task requests input focus. Starting the second session in
        // the same turn can leave its controller profile partially re-bound.
        stopOnboardingVr()
        val streamIntent = Intent(this, VRStreamActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
        }
        finish()
        Handler(Looper.getMainLooper()).postDelayed({
            applicationContext.startActivity(streamIntent)
        }, STREAM_HANDOFF_DELAY_MS)
    }
    private fun updateCameraPreview(image: androidx.camera.core.ImageProxy) {
        synchronized(previewLock) {
            val frame = previewBuffers[nextPreviewBuffer]
            yuvToRgba(image, frame)
            cameraFrame = frame
            nextPreviewBuffer = (nextPreviewBuffer + 1) % previewBuffers.size
        }
    }
    private fun yuvToRgba(image: androidx.camera.core.ImageProxy, out: ByteArray) {
        val sourceW = image.width; val sourceH = image.height; val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
        val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
        // Both scanner steps show a centered 2× crop. Barcode/OCR still
        // receive the complete camera image, preserving their read accuracy.
        val crop = (minOf(sourceW, sourceH) / PREVIEW_ZOOM / 2) * 2
        val offsetX = (sourceW - crop) / 2
        val offsetY = (sourceH - crop) / 2
        for (row in 0 until PREVIEW_SIZE) for (col in 0 until PREVIEW_SIZE) {
            val sourceRow = offsetY + row * crop / PREVIEW_SIZE; val sourceCol = offsetX + col * crop / PREVIEW_SIZE
            val yy = yb.get(sourceRow * y.rowStride + sourceCol * y.pixelStride).toInt() and 255
            val ci = (sourceRow / 2) * u.rowStride + (sourceCol / 2) * u.pixelStride
            val uu = (ub.get(ci).toInt() and 255) - 128; val vv = (vb.get(ci).toInt() and 255) - 128
            val r = (yy + 1.402f * vv).toInt().coerceIn(0,255); val g = (yy - .344f * uu - .714f * vv).toInt().coerceIn(0,255); val b = (yy + 1.772f * uu).toInt().coerceIn(0,255)
            val o=(row*PREVIEW_SIZE+col)*4; out[o]=r.toByte(); out[o+1]=g.toByte(); out[o+2]=b.toByte(); out[o+3]=255.toByte()
        }
    }
    private fun drawWrappedText(c: Canvas, text: String, x: Float, baseline: Float, maxWidth: Float, lineHeight: Float, p: Paint): Float {
        var y = baseline
        text.split('\n').forEach { paragraph ->
            var remaining = paragraph.trim()
            while (remaining.isNotEmpty()) {
                var count = p.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
                if (count < remaining.length) {
                    val wordBreak = remaining.lastIndexOf(' ', count - 1)
                    if (wordBreak > 0) count = wordBreak
                }
                c.drawText(remaining.substring(0, count).trimEnd(), x, y, p)
                y += lineHeight
                remaining = remaining.substring(count).trimStart()
            }
        }
        return y
    }
    private fun drawPanel(c: Canvas, p: Paint, w: Int, h: Int): RectF {
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val panel = RectF(76f, 28f, w - 76f, h - 28f)
        p.color = Color.rgb(5, 12, 29); c.drawRoundRect(panel, 38f, 38f, p)
        p.color = Color.argb(118, 125, 211, 252); p.style = Paint.Style.STROKE; p.strokeWidth = 2f
        c.drawRoundRect(panel, 38f, 38f, p); p.style = Paint.Style.FILL
        return panel
    }
    private fun draw() {
        val w = OVERLAY_WIDTH; val h = OVERLAY_HEIGHT; val c = overlayCanvas; val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val panel = drawPanel(c, p, w, h); val contentX = panel.left + 48f; val textWidth = panel.width() - 96f
        p.color = Color.WHITE; p.typeface = android.graphics.Typeface.DEFAULT_BOLD; p.textSize = if (page == 1) 43f else 52f
        val titleBottom = drawWrappedText(c, pages[page].first, contentX, panel.top + 88f, textWidth, 54f, p)
        p.color = Color.rgb(203,213,225); p.typeface = android.graphics.Typeface.DEFAULT; p.textSize=28f
        if (page == QR_PAGE || page == LINK_PAGE) {
            p.textSize = 25f
            val instructionsBottom = if (page == QR_PAGE) drawWrappedText(c, "On your computer, sign in to PlayStation Network in the Chrome extension. Then keep its fresh QR code visible for Quest.", contentX, titleBottom + 26f, textWidth, 31f, p) else drawWrappedText(c, "Look at the eight-digit code on your PS5 Link Device screen.", contentX, titleBottom + 26f, textWidth, 31f, p)
            val cameraTop = maxOf(panel.top + 252f, instructionsBottom + 28f)
            val card = RectF(505f,cameraTop,775f,cameraTop + 270f)
            synchronized(previewLock) {
                cameraFrame?.let { frame ->
                    for (i in previewPixels.indices) {
                        val o = i * 4
                        previewPixels[i] = Color.rgb(
                            frame[o].toInt() and 255,
                            frame[o + 1].toInt() and 255,
                            frame[o + 2].toInt() and 255,
                        )
                    }
                    previewBitmap.setPixels(previewPixels, 0, PREVIEW_SIZE, 0, 0, PREVIEW_SIZE, PREVIEW_SIZE)
                    val path = Path().apply { addRoundRect(card, 32f, 32f, Path.Direction.CW) }
                    c.save(); c.clipPath(path); c.drawBitmap(previewBitmap, null, card, p); c.restore()
                }
            }
            p.color = Color.rgb(56,189,248); p.style=Paint.Style.STROKE; p.strokeWidth=7f
            c.drawRoundRect(card,32f,32f,p)
            p.style=Paint.Style.FILL; p.textSize=27f
            drawWrappedText(c, scanStatus.ifBlank { "Press a controller button to start scanning" }, contentX, panel.bottom - 94f, textWidth, 30f, p)
        } else {
            val controllerGuide = page == CONTROLLER_PAGE
            if (controllerGuide) {
                p.textSize = 21f
            }
            var bodyY = maxOf(panel.top + 174f, titleBottom + if (controllerGuide) 18f else 28f)
            pages[page].second.forEach { line ->
                bodyY = drawWrappedText(
                    c,
                    line,
                    contentX,
                    bodyY,
                    textWidth,
                    if (controllerGuide) 27f else 38f,
                    p
                ) + if (controllerGuide) 3f else 10f
            }
            if (page == PAIRING_PAGE || page == STREAM_SUCCESS_PAGE) {
                p.color = if (page == STREAM_SUCCESS_PAGE) Color.rgb(74, 222, 128) else Color.rgb(125, 211, 252)
                p.textSize = 29f
                drawWrappedText(c, scanStatus, contentX, panel.bottom - 112f, textWidth, 34f, p)
            }
        }
        p.color = Color.rgb(125,211,252); p.textSize=26f
        val footer = when {
            page == PAIRING_PAGE && pairingFailed -> "Press a controller button to scan a fresh code"
            page == PAIRING_PAGE -> "Pairing in progress…"
            page == STREAM_SUCCESS_PAGE -> "Connected"
            scanning && page == QR_PAGE -> "Keep the QR code inside the blue frame"
            scanning && page == LINK_PAGE -> "Keep the Link Device code inside the blue frame"
            page == LINK_CONFIRMED_PAGE -> "Press any button on your Quest controller to connect"
            page == LINK_PAGE && linkDeviceCode != null -> "Press a controller button to pair"
            else -> "Press any controller button to continue"
        }
        c.drawText(footer, contentX, panel.bottom - 26f, p)
        overlayBitmap.getPixels(overlayPixels, 0, w, 0, 0, w, h)
        var source = 0
        var target = 0
        while (source < overlayPixels.size) {
            val color = overlayPixels[source++]
            overlayBytes[target++] = (color shr 16).toByte()
            overlayBytes[target++] = (color shr 8).toByte()
            overlayBytes[target++] = color.toByte()
            overlayBytes[target++] = (color ushr 24).toByte()
        }
        nativeSetOnboardingOverlay(overlayBytes, w, h)
    }
    companion object { init { System.loadLibrary("openxr_loader"); System.loadLibrary("chiaki-jni") }
        private const val TAG = "ImmersiveOnboarding"
        const val EXTRA_CONSOLE_NAME = "onboarding_console_name"
        const val EXTRA_CONSOLE_ADDRESS = "onboarding_console_address"
        private const val READY_PAGE=0; private const val QR_PAGE=2; private const val ACCOUNT_CONFIRMED_PAGE=3; private const val LINK_PAGE=4
        private const val LINK_CONFIRMED_PAGE=5; private const val PAIRING_PAGE=6; private const val CONTROLLER_PAGE=7; private const val STREAM_SUCCESS_PAGE=8; private const val CAMERA_REQUEST=411
        private const val SUCCESS_SCREEN_DELAY_MS=1400L
        private const val STREAM_HANDOFF_DELAY_MS=350L
        private const val OVERLAY_WIDTH=1280; private const val OVERLAY_HEIGHT=720
        private const val PREVIEW_SIZE=256; private const val PREVIEW_ZOOM=2
        private const val PREVIEW_BYTE_COUNT=PREVIEW_SIZE * PREVIEW_SIZE * 4
        private const val PREVIEW_INTERVAL_MS=80L
        const val HEADSET_CAMERA_PERMISSION="horizonos.permission.HEADSET_CAMERA"
        val cameraPermissions = arrayOf(HEADSET_CAMERA_PERMISSION)
    }
}
