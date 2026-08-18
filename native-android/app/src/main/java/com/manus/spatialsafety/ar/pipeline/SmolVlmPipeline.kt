package com.manus.spatialsafety.ar.pipeline

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

private const val VLM_TAG = "TrinetraSmolVlm"

/** Configuration for the local SmolVLM2 engine and bounded inference path. */
enum class SmolVlmQuantization(val wireName: String) {
    INT4("int4"),
    INT8("int8"),
    FP16("fp16"),
    AUTO("auto"),
}

data class SmolVlmMobileProfile(
    val quantization: SmolVlmQuantization = SmolVlmQuantization.INT4,
    val maxTokens: Int = 160,
    val jpegQuality: Int = 65,
    val maxImageEdgePx: Int = 768,
    val frameIntervalMs: Long = 1_200L,
)

data class SmolVlmConfig(
    val model: String = VisualNavigationPrompt.MODEL_ID,
    val profile: SmolVlmMobileProfile = SmolVlmMobileProfile(),
    val inferenceTimeoutMs: Long = 1_500L,
    val triggerConfig: VlmTriggerConfig = VlmTriggerConfig(),
)

data class DepthSensorSnapshot(
    val distanceMeters: Float? = null,
    val confidence: Float = 0f,
)

fun interface DepthSensorFallback {
    fun guidance(snapshot: DepthSensorSnapshot): SmolVlmResult
}

object ArCoreDepthSensorFallback : DepthSensorFallback {
    override fun guidance(snapshot: DepthSensorSnapshot): SmolVlmResult {
        val distance = snapshot.distanceMeters
        return when {
            distance == null || !distance.isFinite() -> VisualNavigationPrompt.SAFE_FALLBACK.copy(
                description = "Visual model unavailable and depth is unavailable. The safety layer remains in control.",
            )
            distance <= 0.8f -> SmolVlmResult(
                pathStatus = "blocked", hazard = "obstacle", position = "center",
                description = "A nearby obstacle is directly ahead. Stop.", confidence = snapshot.confidence.coerceIn(0f, 1f),
                uncertainty = "low",
            )
            distance <= 1.5f -> SmolVlmResult(
                pathStatus = "partially_blocked", hazard = "obstacle", position = "center",
                description = "An obstacle is ahead. Proceed with caution.", confidence = snapshot.confidence.coerceIn(0f, 1f),
                uncertainty = "medium",
            )
            else -> SmolVlmResult(
                pathStatus = "clear", hazard = "none", position = "unknown",
                description = "The immediate path is clear by depth sensing.", confidence = snapshot.confidence.coerceIn(0f, 1f),
                uncertainty = "medium",
            )
        }
    }
}

/** Uses the local VLM within a bounded inference budget, then falls back to ARCore depth guidance. */
class SafeLocalVlmClient(
    private val local: SmolVlmClient,
    private val fallback: DepthSensorFallback,
    private val snapshotProvider: () -> DepthSensorSnapshot,
    private val latencyBudgetMs: Long = 1_500L,
) : SmolVlmClient {
    override suspend fun analyzeJpeg(jpegBytes: ByteArray): SmolVlmResult {
        val onlineResult = withTimeoutOrNull(latencyBudgetMs.coerceAtLeast(100L)) {
            runCatching { local.analyzeJpeg(jpegBytes) }.getOrNull()
        }
        if (onlineResult != null) return onlineResult
        safeLogWarning("Local VLM exceeded ${latencyBudgetMs}ms or failed; switching to depth fallback")
        return fallback.guidance(snapshotProvider())
    }
}

private fun safeLogWarning(message: String) {
    runCatching { Log.w(VLM_TAG, message) }
}

interface SmolVlmClient {
    suspend fun analyzeJpeg(jpegBytes: ByteArray): SmolVlmResult
}

/** CameraX analyzer that sends only selected latest frames to SmolVLM2. */
class SmolVlmCameraAnalyzer(
    private val client: SmolVlmClient,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val shouldInvoke: () -> Boolean,
    private val onResult: (SmolVlmResult) -> Unit,
    private val minIntervalMs: Long = 1_200L,
    private val jpegQuality: Int = 65,
    private val maxImageEdgePx: Int = 768,
) : ImageAnalysis.Analyzer {
    private val mutex = Mutex()
    @Volatile private var lastSubmittedAtMs = 0L
    @Volatile private var activeJob: Job? = null

    override fun analyze(image: ImageProxy) {
        if (!claimFrame()) {
            image.close()
            return
        }
        activeJob = scope.launch(dispatcher) {
            mutex.withLock {
                try {
                    val bitmap = YuvRgbConverter.convert(image)
                    submitBitmap(bitmap)
                } catch (error: Exception) {
                    Log.e(VLM_TAG, "Camera frame could not be submitted to VLM", error)
                    onResult(VisualNavigationPrompt.SAFE_FALLBACK)
                } finally {
                    image.close()
                }
            }
        }
    }

    /** Copies the current ARCore camera image while ARCore owns the camera session. */
    fun submitArCoreFrame(frame: com.google.ar.core.Frame) {
        if (!claimFrame()) return
        val image = try {
            frame.acquireCameraImage()
        } catch (error: Throwable) {
            lastSubmittedAtMs = 0L
            Log.w(VLM_TAG, "ARCore camera image unavailable for selected VLM trigger", error)
            return
        }
        val bitmap = try {
            YuvRgbConverter.convert(image)
        } catch (error: Throwable) {
            lastSubmittedAtMs = 0L
            Log.e(VLM_TAG, "ARCore camera image conversion failed", error)
            return
        } finally {
            image.close()
        }
        activeJob = scope.launch(dispatcher) {
            mutex.withLock { submitBitmap(bitmap) }
        }
    }

    private fun claimFrame(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!shouldInvoke() || now - lastSubmittedAtMs < minIntervalMs || activeJob?.isActive == true) return false
        lastSubmittedAtMs = now
        Log.d(VLM_TAG, "Submitting selected AR frame to SmolVLM2")
        return true
    }

    private suspend fun submitBitmap(bitmap: Bitmap) {
        try {
            val jpeg = bitmap.toJpegBytes(jpegQuality, maxImageEdgePx)
            onResult(client.analyzeJpeg(jpeg))
        } catch (error: Exception) {
            Log.e(VLM_TAG, "Frame could not be submitted to VLM", error)
            onResult(VisualNavigationPrompt.SAFE_FALLBACK)
        } finally {
            bitmap.recycle()
        }
    }

    fun close() {
        activeJob?.cancel()
    }
}

private fun Bitmap.toJpegBytes(quality: Int, maxEdgePx: Int): ByteArray {
    val boundedQuality = quality.coerceIn(40, 90)
    val boundedEdge = maxEdgePx.coerceAtLeast(256)
    val scale = minOf(1f, boundedEdge.toFloat() / maxOf(width, height).toFloat())
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    } else this
    return try {
        ByteArrayOutputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, boundedQuality, output)
            output.toByteArray()
        }
    } finally {
        if (scaled !== this) scaled.recycle()
    }
}

/** End-to-end coordinator for CameraX -> SmolVLM2 -> action_command TTS. */
class SmolVlmNavigationPipeline(
    context: android.content.Context,
    config: SmolVlmConfig,
    scope: CoroutineScope,
    perceptionContext: () -> PerceptionContext,
    depthSnapshot: () -> DepthSensorSnapshot = { DepthSensorSnapshot() },
) : AutoCloseable {
    private val tts = com.manus.spatialsafety.ar.safety.NavigationTtsController(context)
    private val localEngine: VisionLanguageModel = SmolVlmEngine(ArtifactGatedLocalVlmRuntime())
    private val router = VlmRouter(config = config.triggerConfig)
    private val analyzer = SmolVlmCameraAnalyzer(
        client = SafeLocalVlmClient(
            local = LocalSmolVlmClient(localEngine) {
                VlmInputContext(perception = perceptionContext())
            },
            fallback = ArCoreDepthSensorFallback,
            snapshotProvider = depthSnapshot,
            latencyBudgetMs = config.inferenceTimeoutMs,
        ),
        scope = scope,
        shouldInvoke = {
            router.request(perceptionContext(), android.os.SystemClock.elapsedRealtime()).enabled
        },
        onResult = { result ->
            router.complete()
            tts.speak(result)
        },
        minIntervalMs = config.profile.frameIntervalMs,
        jpegQuality = config.profile.jpegQuality,
        maxImageEdgePx = config.profile.maxImageEdgePx,
    )
    /** CameraX is intentionally not bound here: ARCore owns the camera session. */
    fun analyzer(): ImageAnalysis.Analyzer = analyzer

    fun submitArCoreFrame(frame: com.google.ar.core.Frame) = analyzer.submitArCoreFrame(frame)

    override fun close() {
        analyzer.close()
        localEngine.release()
        tts.close()
    }
}
