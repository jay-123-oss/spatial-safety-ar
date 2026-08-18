package com.manus.spatialsafety.ar.pipeline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockSmolVlmClientTest {
    @Test
    fun hazardResponseIsCompactAndTtsReady() = runBlocking {
        val result = MockSmolVlmClient().analyzeJpeg(byteArrayOf(1, 2))
        assertEquals("blocked", result.pathStatus)
        assertEquals("obstacle", result.hazard)
        assertEquals("center", result.position)
        assertEquals(result.description, result.toTtsText())
    }

    @Test
    fun lowLightResponseIsUncertain() = runBlocking {
        val result = MockSmolVlmClient(listOf(MockSmolVlmClient.LOW_LIGHT_RESPONSE)).analyzeJpeg(byteArrayOf(1))
        assertEquals("high", result.uncertainty)
        assertEquals("unknown", result.hazard)
    }

    @Test
    fun suddenObstacleResponseIdentifiesRightVehicle() = runBlocking {
        val result = MockSmolVlmClient(listOf(MockSmolVlmClient.SUDDEN_OBSTACLE_RESPONSE)).analyzeJpeg(byteArrayOf(1))
        assertEquals("vehicle", result.hazard)
        assertEquals("right", result.position)
        assertEquals("partially_blocked", result.pathStatus)
    }

    @Test
    fun malformedResponseUsesSafeFallback() = runBlocking {
        val result = MockSmolVlmClient(listOf("{bad")).analyzeJpeg(byteArrayOf(1))
        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, result)
    }

    @Test
    fun requestCountIsBoundedByCallCount() = runBlocking {
        val client = MockSmolVlmClient()
        client.analyzeJpeg(ByteArray(0))
        client.analyzeJpeg(ByteArray(0))
        assertTrue(client.requestCount <= 2)
    }
}
