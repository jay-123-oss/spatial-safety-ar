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
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL

private const val VLM_TAG = "TrinetraSmolVlm"

/** Configuration for an OpenAI-compatible SmolVLM2 gateway. Keep credentials off-device when possible. */
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
    val endpoint: String,
    val apiKey: String? = null,
    val model: String = VisualNavigationPrompt.MODEL_ID,
    val profile: SmolVlmMobileProfile = SmolVlmMobileProfile(),
    val connectTimeoutMs: Int = 5_000,
    val readTimeoutMs: Int = 20_000,
    val networkLatencyBudgetMs: Long = 1_500L,
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
                environment = "AR depth sensor",
                primaryHazard = "Unknown obstacle",
                spatialReasoning = "Online visual inference exceeded its latency budget and depth is unavailable.",
                actionCommand = "Stop immediately and remain in place. Sweep your cane slowly ahead while the depth view recovers.",
            )
            distance <= 0.8f -> SmolVlmResult(
                environment = "AR depth sensor",
                primaryHazard = "Nearby obstacle",
                hazardPosition = "Directly ahead, ${"%.1f".format(distance)} meters away",
                spatialReasoning = "The depth sensor reports an obstacle inside the immediate stopping distance.",
                actionCommand = "Stop immediately. Use your cane to locate the obstacle before changing direction.",
            )
            distance <= 1.5f -> SmolVlmResult(
                environment = "AR depth sensor",
                primaryHazard = "Obstacle",
                hazardPosition = "Directly ahead, ${"%.1f".format(distance)} meters away",
                spatialReasoning = "The depth sensor reports a nearby obstacle, but no safe side to bypass it.",
                actionCommand = "Pause. Sweep your cane left and right to find a clear path before moving.",
            )
            else -> SmolVlmResult(
                environment = "AR depth sensor",
                primaryHazard = "None",
                hazardPosition = "Not applicable",
                spatialReasoning = "The nearest depth reading is outside the immediate hazard range.",
                actionCommand = "The immediate path is clear by depth sensing. Continue slowly and keep your cane sweeping.",
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

/** Explicit offline mode; the latency wrapper immediately selects depth fallback. */
class DisabledSmolVlmClient : SmolVlmClient {
    override suspend fun analyzeJpeg(jpegBytes: ByteArray): SmolVlmResult {
        error("SmolVLM2 endpoint is not configured")
    }
}

/** Calls a server-side SmolVLM2 endpoint using the OpenAI-compatible vision message format. */
class OpenAiCompatibleSmolVlmClient(
    private val config: SmolVlmConfig,
) : SmolVlmClient {
    override suspend fun analyzeJpeg(jpegBytes: ByteArray): SmolVlmResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
        Log.i(VLM_TAG, "VLM request start model=${config.model} quantization=${config.profile.quantization.wireName} imageBytes=${jpegBytes.size}")
        val connection = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.connectTimeoutMs
            readTimeout = config.readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            config.apiKey?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            val request = JSONObject()
                .put("model", config.model)
                .put("temperature", 0)
                .put("max_tokens", config.profile.maxTokens)
                // The gateway must honor this provider-specific hint when loading SmolVLM2.
                .put("quantization", config.profile.quantization.wireName)
                .put("image_detail", "low")
                .put("messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", JSONArray()
                            .put(JSONObject().put("type", "text").put("text", VisualNavigationPrompt.SYSTEM_PROMPT))
                            .put(JSONObject().put("type", "image_url").put(
                                "image_url", JSONObject().put("url", "data:image/jpeg;base64,${Base64.encodeToString(jpegBytes, Base64.NO_WRAP)}"),
                            )),
                        ),
                ))
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val responseText = BufferedReader(InputStreamReader(
                if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream,
                Charsets.UTF_8,
            )).use { it.readText() }
            if (connection.responseCode !in 200..299) {
                Log.e(VLM_TAG, "VLM HTTP failure status=${connection.responseCode}")
                return@withContext VisualNavigationPrompt.SAFE_FALLBACK
            }
            val content = JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            VisualNavigationPrompt.parse(content)
        } catch (error: Exception) {
            Log.e(VLM_TAG, "VLM request or response parsing failed", error)
            VisualNavigationPrompt.SAFE_FALLBACK
        } finally {
            connection.disconnect()
        }
    }
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
    shouldInvoke: () -> Boolean,
    depthSnapshot: () -> DepthSensorSnapshot = { DepthSensorSnapshot() },
) : AutoCloseable {
    private val tts = com.manus.spatialsafety.ar.safety.NavigationTtsController(context)
    private val analyzer = SmolVlmCameraAnalyzer(
        client = LatencyAwareSmolVlmClient(
            online = if (config.endpoint.isBlank()) DisabledSmolVlmClient() else OpenAiCompatibleSmolVlmClient(config),
            fallback = ArCoreDepthSensorFallback,
            snapshotProvider = depthSnapshot,
            latencyBudgetMs = config.networkLatencyBudgetMs,
        ),
        scope = scope,
        shouldInvoke = shouldInvoke,
        onResult = tts::speak,
        minIntervalMs = config.profile.frameIntervalMs,
        jpegQuality = config.profile.jpegQuality,
        maxImageEdgePx = config.profile.maxImageEdgePx,
    )
    private val camera = TrinetraCameraController(context, analyzer)

    fun bind(owner: androidx.lifecycle.LifecycleOwner) = camera.bind(owner)

    override fun close() {
        camera.close()
        analyzer.close()
        tts.close()
    }
}
