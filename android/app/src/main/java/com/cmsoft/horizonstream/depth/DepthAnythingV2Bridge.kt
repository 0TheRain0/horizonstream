// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.cmsoft.horizonstream.depth

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/** Asynchronous Depth Anything V2 inference for the OpenXR video compositor. */
@Keep
object DepthAnythingV2Bridge {
    private const val TAG = "DepthAnythingV2"
    private const val MODEL_ASSET = "models/depth_anything_v2_small_q4.onnx"
    private const val MODEL_FILE = "depth_anything_v2_small_q4.onnx"
    private const val INPUT_WIDTH = 322
    private const val INPUT_HEIGHT = 182
    private const val TEMPORAL_ALPHA = 0.38f

    private data class Listener(
        val generation: Long,
        val onReady: () -> Unit,
        val onDepthMap: (ByteArray, Int, Int) -> Unit
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DepthAnythingV2").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val initializing = AtomicBoolean(false)
    private val inferenceInFlight = AtomicBoolean(false)
    private val generation = AtomicLong(0L)

    @Volatile
    private var session: OrtSession? = null
    @Volatile
    private var environment: OrtEnvironment? = null
    @Volatile
    private var listener: Listener? = null
    @Volatile
    private var previousDepth: ByteArray? = null
    private var inferenceCount = 0L

    fun initialize(
        context: Context,
        onReady: () -> Unit,
        onDepthMap: (ByteArray, Int, Int) -> Unit
    ) {
        val activeGeneration = generation.incrementAndGet()
        val activeListener = Listener(activeGeneration, onReady, onDepthMap)
        listener = activeListener
        previousDepth = null
        inferenceCount = 0L

        if(session != null) {
            onReady()
            return
        }
        if(!initializing.compareAndSet(false, true))
            return

        val appContext = context.applicationContext
        executor.execute {
            try {
                val model = installModelAsset(appContext)
                val env = OrtEnvironment.getEnvironment("horizonstream-depth-anything-v2")
                environment = env
                session = createSession(env, model)
                val activeSession = session!!
                Log.i(
                    TAG,
                    "Depth Anything V2 ready; inputs=${activeSession.inputNames} " +
                        "outputs=${activeSession.outputNames}"
                )
                listener?.onReady?.invoke()
            } catch(t: Throwable) {
                Log.e(TAG, "Unable to initialize Depth Anything V2; stereo remains flat", t)
            } finally {
                initializing.set(false)
            }
        }
    }

    /** Called synchronously by the OpenXR render thread; the native buffer is copied. */
    @Keep
    @JvmStatic
    fun submitFrame(rgbaFrame: ByteBuffer, width: Int, height: Int): Boolean {
        val activeSession = session ?: return false
        val activeEnvironment = environment ?: return false
        val activeListener = listener ?: return false
        if(width != INPUT_WIDTH || height != INPUT_HEIGHT ||
            !inferenceInFlight.compareAndSet(false, true)) {
            return false
        }

        val expectedSize = width * height * 4
        if(rgbaFrame.remaining() < expectedSize) {
            inferenceInFlight.set(false)
            return false
        }
        val rgba = ByteArray(expectedSize)
        rgbaFrame.get(rgba)
        executor.execute {
            try {
                val depthMap = infer(
                    activeEnvironment, activeSession, rgba, width, height)
                listener
                    ?.takeIf { it.generation == activeListener.generation }
                    ?.onDepthMap
                    ?.invoke(depthMap, width, height)
            } catch(t: Throwable) {
                Log.e(TAG, "Depth inference failed", t)
                val fallback = previousDepth?.takeIf { it.size == width * height }
                    ?: ByteArray(width * height) { 128.toByte() }
                listener
                    ?.takeIf { it.generation == activeListener.generation }
                    ?.onDepthMap
                    ?.invoke(fallback, width, height)
            } finally {
                inferenceInFlight.set(false)
            }
        }
        return true
    }

    fun shutdown() {
        generation.incrementAndGet()
        listener = null
        previousDepth = null
        executor.execute {
            if(listener == null) {
                session?.close()
                session = null
                environment?.close()
                environment = null
                inferenceInFlight.set(false)
                Log.i(TAG, "Depth Anything V2 session released")
            }
        }
    }

    private fun infer(
        env: OrtEnvironment,
        activeSession: OrtSession,
        rgba: ByteArray,
        width: Int,
        height: Int
    ): ByteArray {
        val pixelCount = width * height
        val input = FloatArray(pixelCount * 3)
        // glReadPixels starts at the bottom row. Flip into conventional image
        // order for the model; GL's upload convention aligns the returned map.
        for(y in 0 until height) {
            val sourceRow = height - 1 - y
            for(x in 0 until width) {
                val pixel = y * width + x
                val source = (sourceRow * width + x) * 4
                val r = (rgba[source].toInt() and 0xFF) / 255f
                val g = (rgba[source + 1].toInt() and 0xFF) / 255f
                val b = (rgba[source + 2].toInt() and 0xFF) / 255f
                input[pixel] = (r - 0.485f) / 0.229f
                input[pixelCount + pixel] = (g - 0.456f) / 0.224f
                input[pixelCount * 2 + pixel] = (b - 0.406f) / 0.225f
            }
        }

        val started = System.nanoTime()
        val depthBytes = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, height.toLong(), width.toLong())
        ).use { tensor ->
            activeSession.run(mapOf("pixel_values" to tensor)).use { result ->
                val output = (result[0] as OnnxTensor).floatBuffer
                val rawDepth = FloatArray(pixelCount)
                output.get(rawDepth)
                normalizeAndSmooth(rawDepth)
            }
        }

        inferenceCount++
        val elapsedMs = (System.nanoTime() - started) / 1_000_000f
        if(inferenceCount <= 3L || inferenceCount % 30L == 0L) {
            Log.i(
                TAG,
                "Inference #$inferenceCount completed in ${"%.1f".format(elapsedMs)} ms"
            )
        }
        return depthBytes
    }

    private fun normalizeAndSmooth(rawDepth: FloatArray): ByteArray {
        val samples = FloatArray((rawDepth.size + 7) / 8)
        var sampleCount = 0
        var index = 0
        while(index < rawDepth.size) {
            val value = rawDepth[index]
            if(value.isFinite())
                samples[sampleCount++] = value
            index += 8
        }
        if(sampleCount == 0)
            return ByteArray(rawDepth.size) { 128.toByte() }

        samples.sort(0, sampleCount)
        val low = samples[min(sampleCount - 1, (sampleCount * 0.02f).toInt())]
        val high = samples[min(sampleCount - 1, max(0, (sampleCount * 0.98f).toInt()))]
        val scale = 1f / max(0.0001f, high - low)
        val previous = previousDepth
        val output = ByteArray(rawDepth.size)
        for(i in rawDepth.indices) {
            val normalized = ((rawDepth[i] - low) * scale).coerceIn(0f, 1f)
            val current = (normalized * 255f).toInt()
            val smoothed = if(previous != null && previous.size == output.size) {
                val old = previous[i].toInt() and 0xFF
                (old + (current - old) * TEMPORAL_ALPHA).toInt()
            } else {
                current
            }
            output[i] = smoothed.coerceIn(0, 255).toByte()
        }
        previousDepth = output
        return output
    }

    private fun createSession(env: OrtEnvironment, model: File): OrtSession {
        try {
            OrtSession.SessionOptions().use { options ->
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setIntraOpNumThreads(2)
                options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                return env.createSession(model.absolutePath, options)
            }
        } catch(t: Throwable) {
            Log.w(TAG, "NNAPI unavailable; using optimized CPU depth inference", t)
            OrtSession.SessionOptions().use { options ->
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setIntraOpNumThreads(4)
                return env.createSession(model.absolutePath, options)
            }
        }
    }

    private fun installModelAsset(context: Context): File {
        val destination = File(context.noBackupFilesDir, MODEL_FILE)
        val expectedSize = context.assets.openFd(MODEL_ASSET).use { it.length }
        if(!destination.isFile || destination.length() != expectedSize) {
            context.assets.open(MODEL_ASSET).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return destination
    }
}
