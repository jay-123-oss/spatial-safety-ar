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
                scene = "AR depth sensor",
                description = "The visual model is unavailable and depth is unavailable. The safety layer remains in control.",
            )
            distance <= 0.8f -> SmolVlmResult(
                scene = "AR depth sensor",
                importantObjects = listOf(VlmObject("nearby obstacle", 1f, "front", "depth candidate")),
                unknownObjects = emptyList(), pathStatus = "blocked", sceneChange = false,
                description = "A nearby obstacle is directly ahead at ${"%.1f".format(distance)} meters.", uncertainty = "low",
            )
            distance <= 1.5f -> SmolVlmResult(
                scene = "AR depth sensor",
                importantObjects = listOf(VlmObject("obstacle", 1f, "front", "depth candidate")),
                unknownObjects = emptyList(), pathStatus = "partially_blocked", sceneChange = false,
                description = "A nearby obstacle is directly ahead at ${"%.1f".format(distance)} meters.", uncertainty = "medium",
            )
            else -> SmolVlmResult(
                scene = "AR depth sensor", importantObjects = emptyList(), unknownObjects = emptyList(),
                pathStatus = "clear", sceneChange = false,
                description = "The immediate path is clear by depth sensing.", uncertainty = "medium",
            )
        }
    }
}

/** Uses online VLM only within the latency budget, then falls back to ARCore depth guidance. */
class LatencyAwareSmolVlmClient(
    private val online: SmolVlmClient,
    private val fallback: DepthSensorFallback,
    private val snapshotProvider: () -> DepthSensorSnapshot,
    private val latencyBudgetMs: Long = 1_500L,
) : SmolVlmClient {
    override suspend fun analyzeJpeg(jpegBytes: ByteArray): SmolVlmResult {
        val onlineResult = withTimeoutOrNull(latencyBudgetMs.coerceAtLeast(100L)) {
            runCatching { online.analyzeJpeg(jpegBytes) }.getOrNull()
        }
        if (onlineResult != null) return onlineResult
        safeLogWarning("Online VLM exceeded ${latencyBudgetMs}ms or failed; switching to depth fallback")
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
        val now = android.os.SystemClock.elapsedRealtime()
        if (!shouldInvoke() || now - lastSubmittedAtMs < minIntervalMs || activeJob?.isActive == true) {
            image.close()
            return
        }
        lastSubmittedAtMs = now
        Log.d(VLM_TAG, "Submitting selected camera frame to SmolVLM2")
        activeJob = scope.launch(dispatcher) {
            mutex.withLock {
                try {
                    val bitmap = YuvRgbConverter.convert(image)
                    val jpeg = bitmap.toJpegBytes(jpegQuality, maxImageEdgePx)
                    bitmap.recycle()
                    onResult(client.analyzeJpeg(jpeg))
                } catch (error: Exception) {
                    Log.e(VLM_TAG, "Camera frame could not be submitted to VLM", error)
                    onResult(VisualNavigationPrompt.SAFE_FALLBACK)
                } finally {
                    image.close()
                }
            }
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
    private val localEngine: VisionLanguageModel = SmolVlmEngine(MissingLocalModelRuntime())
    private val router = VlmRouter(config = config.triggerConfig)
    private val analyzer = SmolVlmCameraAnalyzer(
        client = LatencyAwareSmolVlmClient(
            online = LocalSmolVlmClient(localEngine) {
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

    override fun close() {
        analyzer.close()
        localEngine.release()
        tts.close()
    }
}
