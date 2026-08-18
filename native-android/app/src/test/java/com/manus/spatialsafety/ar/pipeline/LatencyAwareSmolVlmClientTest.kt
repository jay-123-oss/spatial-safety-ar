package com.manus.spatialsafety.ar.pipeline

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyAwareSmolVlmClientTest {
    private val onlineResult = SmolVlmResult(
        scene = "Indoor hallway",
        importantObjects = emptyList(),
        unknownObjects = emptyList(),
        pathStatus = "clear",
        sceneChange = false,
        description = "The online VLM found no immediate obstacle.",
        uncertainty = "low",
    )

    @Test
    fun fastOnlineResponseWinsWithinLatencyBudget() = runBlocking {
        val client = LatencyAwareSmolVlmClient(FakeClient(onlineResult, 5), ArCoreDepthSensorFallback, { DepthSensorSnapshot(0.4f) }, 100)
        assertEquals(onlineResult, client.analyzeJpeg(byteArrayOf(1)))
    }

    @Test
    fun slowOnlineResponseFallsBackToDepthSensor() = runBlocking {
        val client = LatencyAwareSmolVlmClient(FakeClient(onlineResult, 250), ArCoreDepthSensorFallback, { DepthSensorSnapshot(0.4f) }, 30)
        val result = client.analyzeJpeg(byteArrayOf(2))
        assertEquals("blocked", result.pathStatus)
        assertTrue(result.description.contains("obstacle"))
    }

    @Test
    fun unavailableDepthUsesConservativeFallback() = runBlocking {
        val client = LatencyAwareSmolVlmClient(FakeClient(onlineResult, 250), ArCoreDepthSensorFallback, { DepthSensorSnapshot(null) }, 30)
        val result = client.analyzeJpeg(byteArrayOf(3))
        assertEquals("AR depth sensor", result.scene)
        assertEquals("high", result.uncertainty)
    }

    private class FakeClient(private val result: SmolVlmResult, private val delayMs: Long) : SmolVlmClient {
        override suspend fun analyzeJpeg(jpegBytes: ByteArray): SmolVlmResult {
            delay(delayMs)
            return result
        }
    }
}
