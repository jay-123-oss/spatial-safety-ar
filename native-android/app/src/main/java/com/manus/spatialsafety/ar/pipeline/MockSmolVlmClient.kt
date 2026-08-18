package com.manus.spatialsafety.ar.pipeline

/** Deterministic offline client for development and AR navigation simulation. */
class MockSmolVlmClient(
    responses: List<String> = listOf(DEFAULT_HAZARD_RESPONSE),
) : SmolVlmClient {
    private val responseQueue = ArrayDeque(responses)
    var requestCount: Int = 0
        private set

    override suspend fun analyzeJpeg(jpegBytes: ByteArray): SmolVlmResult {
        requestCount += 1
        val response = responseQueue.removeFirstOrNull() ?: DEFAULT_CLEAR_RESPONSE
        return VisualNavigationPrompt.parse(response)
    }

    companion object {
        const val DEFAULT_HAZARD_RESPONSE = """
        {
          "environment":"Uneven footpath",
          "primary_hazard":"Open gutter",
          "hazard_position":"Directly beneath your next step",
          "spatial_reasoning":"The pavement ends at an open gutter, so stepping forward without checking the gap is dangerous.",
          "action_command":"Stop immediately. Sweep your cane down to measure the gap before moving."
        }
        """

        const val DEFAULT_CLEAR_RESPONSE = """
        {
          "environment":"Open walkway",
          "primary_hazard":"None",
          "hazard_position":"Not applicable",
          "spatial_reasoning":"There are no obstacles or surface issues in the immediate path.",
          "action_command":"The path is clear. Continue walking straight at your normal pace."
        }
        """
    }
}
