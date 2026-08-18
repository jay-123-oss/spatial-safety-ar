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
        return VisualNavigationPrompt.parse(responseQueue.removeFirstOrNull() ?: DEFAULT_CLEAR_RESPONSE)
    }

    companion object {
        const val DEFAULT_HAZARD_RESPONSE = """
        {"scene":"Uneven footpath","important_objects":[{"name":"open gutter","confidence":0.98,"position":"front","relation":"blocking the next step"}],"unknown_objects":[],"path_status":"blocked","scene_change":false,"description":"An open gutter is directly ahead and blocks the next step.","uncertainty":"low"}
        """
        const val LOW_LIGHT_RESPONSE = """
        {"scene":"Dark corridor","important_objects":[],"unknown_objects":["unknown obstacle"],"path_status":"uncertain","scene_change":false,"description":"The scene is too dark to confirm a safe path.","uncertainty":"high"}
        """
        const val SUDDEN_OBSTACLE_RESPONSE = """
        {"scene":"Busy sidewalk","important_objects":[{"name":"moving cyclist","confidence":0.91,"position":"front-right","relation":"entering the walking path"}],"unknown_objects":[],"path_status":"partially_blocked","scene_change":true,"description":"A moving cyclist has entered the walking path on the front-right.","uncertainty":"low"}
        """
        const val DEFAULT_CLEAR_RESPONSE = """
        {"scene":"Open walkway","important_objects":[],"unknown_objects":[],"path_status":"clear","scene_change":false,"description":"The immediate path is clear.","uncertainty":"low"}
        """
    }
}
