package com.manus.spatialsafety.ar.pipeline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class VlmState { IDLE, LOADING, READY, PROCESSING, COOLDOWN, ERROR, DISABLED }

data class VlmInputContext(
    val perception: PerceptionContext,
    val detectedObjects: String = "None",
)

interface VisionLanguageModel {
    val state: VlmState
    suspend fun initialize(): Boolean
    suspend fun analyze(imageBytes: ByteArray, context: VlmInputContext): SmolVlmResult
    fun release()
}

/** Stable model abstraction for SmolVLM2-500M and future Moondream/PaliGemma/Qwen engines. */
class SmolVlmEngine(
    private val runtime: LocalVlmRuntime,
) : VisionLanguageModel {
    @Volatile override var state: VlmState = VlmState.IDLE
        private set

    override suspend fun initialize(): Boolean = withContext(Dispatchers.Default) {
        if (state == VlmState.READY) return@withContext true
        state = VlmState.LOADING
        state = if (runtime.initialize()) VlmState.READY else VlmState.DISABLED
        state == VlmState.READY
    }

    override suspend fun analyze(imageBytes: ByteArray, context: VlmInputContext): SmolVlmResult {
        if (state != VlmState.READY && !initialize()) return VisualNavigationPrompt.SAFE_FALLBACK
        state = VlmState.PROCESSING
        return try {
            runtime.analyze(imageBytes, context).also { state = VlmState.READY }
        } catch (_: Throwable) {
            state = VlmState.ERROR
            VisualNavigationPrompt.SAFE_FALLBACK
        }
    }

    override fun release() {
        runtime.release()
        state = VlmState.IDLE
    }
}

interface LocalVlmRuntime {
    fun initialize(): Boolean
    suspend fun analyze(imageBytes: ByteArray, context: VlmInputContext): SmolVlmResult
    fun release()
}

/** Explicit state for builds that do not yet package the local SmolVLM2-500M artifact. */
class LocalSmolVlmClient(
    private val engine: VisionLanguageModel,
    private val contextProvider: () -> VlmInputContext,
) : SmolVlmClient {
    override suspend fun analyzeJpeg(jpegBytes: ByteArray): SmolVlmResult {
        if (!engine.initialize()) error("Local SmolVLM2-500M model is unavailable")
        return engine.analyze(jpegBytes, contextProvider())
    }
}

/**
 * Production-safe gate for the real local runtime. It never fabricates inference: until the
 * official .litertlm bundle and LiteRT-LM Android runtime are packaged, initialization is false
 * and the caller must use its safety fallback.
 */
class ArtifactGatedLocalVlmRuntime(
    private val modelAssetName: String = "SmolVLM2-500M.litertlm",
) : LocalVlmRuntime {
    override fun initialize(): Boolean = false
    override suspend fun analyze(imageBytes: ByteArray, context: VlmInputContext): SmolVlmResult =
        error("Local model artifact unavailable: $modelAssetName")
    override fun release() = Unit
}
