package com.manus.spatialsafety.ar.pipeline

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyAwareSmolVlmClientTest {
    private val onlineResult = SmolVlmResult(
        environment = "Indoor hallway",
        primaryHazard = "None",
        hazardPosition = "Not applicable",
        spatialReasoning = "The online VLM found no immediate obstacle.",
        actionCommand = "The path is clear. Continue walking straight.",
    )

    @Test
    fun fastOnlineResponseWinsWithinLatencyBudget() = runBlocking {
        val client = LatencyAwareSmolVlmClient(
            online = FakeClient(onlineResult, delayMs = 5),
            fallback = ArCoreDepthSensorFallback,
            snapshotProvider = { DepthSensorSnapshot(distanceMeters = 0.4f) },
            latencyBudgetMs = 100,
        )

        val result = client.analyzeJpeg(byteArrayOf(1))

        assertEquals(onlineResult, result)
    }

    @Test
    fun slowOnlineResponseFallsBackToDepthSensor() = runBlocking {
        val client = LatencyAwareSmolVlmClient(
            online = FakeClient(onlineResult, delayMs = 250),
            fallback = ArCoreDepthSensorFallback,
            snapshotProvider = { DepthSensorSnapshot(distanceMeters = 0.4f) },
            latencyBudgetMs = 30,
        )

        val result = client.analyzeJpeg(byteArrayOf(2))

        assertEquals("Nearby obstacle", result.primaryHazard)
        assertTrue(result.actionCommand.startsWith("Stop immediately"))
    }

    @Test
    fun unavailableDepthUsesConservativeFallback() = runBlocking {
        val client = LatencyAwareSmolVlmClient(
            online = FakeClient(onlineResult, delayMs = 250),
            fallback = ArCoreDepthSensorFallback,
            snapshotProvider = { DepthSensorSnapshot(distanceMeters = null) },
            latencyBudgetMs = 30,
        )

        val result = client.analyzeJpeg(byteArrayOf(3))

        assertEquals("Unknown obstacle", result.primaryHazard)
        assertTrue(result.actionCommand.contains("remain in place"))
    }

    private class FakeClient(
        private val result: SmolVlmResult,
        private val delayMs: Long,
    ) : SmolVlmClient {
        override suspend fun analyzeJpeg(jpegBytes: ByteArray): SmolVlmResult {
            delay(delayMs)
            return result
        }
    }
}
