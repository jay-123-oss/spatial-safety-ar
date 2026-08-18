package com.manus.spatialsafety.ar.pipeline

/** Deterministic offline client for unit tests only; never used as production inference. */
class MockSmolVlmClient(
    responses: List<String> = listOf(DEFAULT_HAZARD_RESPONSE),
) : SmolVlmClient {
    private val responseQueue = ArrayDeque(responses)
    var requestCount: Int = 0
        private set

    override suspend fun analyzeJpeg(jpegBytes: ByteArray): SmolVlmResult {
        requestCount += 1
        return VisualNavigationPrompt.parse(responseQueue.removeFirstOrNull() ?: DEFAULT_CLEAR_RESPONSE)
    }

    companion object {
        const val DEFAULT_HAZARD_RESPONSE = """
        {"path_status":"blocked","hazard":"obstacle","position":"center","description":"An open drain blocks the walking path ahead. Stop.","confidence":0.98,"uncertainty":"low"}
        """
        const val LOW_LIGHT_RESPONSE = """
        {"path_status":"uncertain","hazard":"unknown","position":"unknown","description":"The scene is too dark to confirm a safe path. Stop and wait.","confidence":0.25,"uncertainty":"high"}
        """
        const val SUDDEN_OBSTACLE_RESPONSE = """
        {"path_status":"partially_blocked","hazard":"vehicle","position":"right","description":"A moving vehicle is entering the walking path from the right. Stop.","confidence":0.91,"uncertainty":"low"}
        """
        const val DEFAULT_CLEAR_RESPONSE = """
        {"path_status":"clear","hazard":"none","position":"unknown","description":"The immediate walking path is clear.","confidence":0.91,"uncertainty":"low"}
        """
    }
}
