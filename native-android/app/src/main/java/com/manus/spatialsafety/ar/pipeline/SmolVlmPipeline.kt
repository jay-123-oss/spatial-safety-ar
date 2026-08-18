package com.manus.spatialsafety.ar.pipeline

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64

/** Configuration for an OpenAI-compatible SmolVLM2 gateway. Keep credentials off-device when possible. */
data class SmolVlmConfig(
    val endpoint: String,
    val apiKey: String? = null,
    val model: String = VisualNavigationPrompt.MODEL_ID,
    val connectTimeoutMs: Int = 5_000,
    val readTimeoutMs: Int = 20_000,
)

interface SmolVlmClient {
    suspend fun analyzeJpeg(jpegBytes: ByteArray): SmolVlmResult
}

/** Calls a server-side SmolVLM2 endpoint using the OpenAI-compatible vision message format. */
class OpenAiCompatibleSmolVlmClient(
    private val config: SmolVlmConfig,
) : SmolVlmClient {
    override suspend fun analyzeJpeg(jpegBytes: ByteArray): SmolVlmResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
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
                .put("max_tokens", 220)
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
            if (connection.responseCode !in 200..299) return@withContext VisualNavigationPrompt.SAFE_FALLBACK
            val content = JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            VisualNavigationPrompt.parse(content)
        } catch (_: Exception) {
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
    private val minIntervalMs: Long = 1_000L,
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
        activeJob = scope.launch(dispatcher) {
            mutex.withLock {
                try {
                    val bitmap = YuvRgbConverter.convert(image)
                    val jpeg = bitmap.toJpegBytes()
                    bitmap.recycle()
                    onResult(client.analyzeJpeg(jpeg))
                } catch (_: Exception) {
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

private fun Bitmap.toJpegBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, 80, output)
    return output.toByteArray()
}

/** End-to-end coordinator for CameraX -> SmolVLM2 -> action_command TTS. */
class SmolVlmNavigationPipeline(
    context: android.content.Context,
    config: SmolVlmConfig,
    scope: CoroutineScope,
    shouldInvoke: () -> Boolean,
) : AutoCloseable {
    private val tts = com.manus.spatialsafety.ar.safety.NavigationTtsController(context)
    private val analyzer = SmolVlmCameraAnalyzer(
        client = OpenAiCompatibleSmolVlmClient(config),
        scope = scope,
        shouldInvoke = shouldInvoke,
        onResult = tts::speak,
    )
    private val camera = TrinetraCameraController(context, analyzer)

    fun bind(owner: androidx.lifecycle.LifecycleOwner) = camera.bind(owner)

    override fun close() {
        camera.close()
        analyzer.close()
        tts.close()
    }
}
