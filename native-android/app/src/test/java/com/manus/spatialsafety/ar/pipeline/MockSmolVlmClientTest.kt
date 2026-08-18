package com.manus.spatialsafety.ar.pipeline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockSmolVlmClientTest {
    @Test
    fun hazardScenarioProducesStructuredContext() = runBlocking {
        val result = MockSmolVlmClient().analyzeJpeg(byteArrayOf(1))
        assertEquals("blocked", result.pathStatus)
        assertEquals("open gutter", result.importantObjects.single().name)
        assertEquals(result.description, result.toTtsText())
    }

    @Test
    fun lowLightScenarioIsUncertain() = runBlocking {
        val result = MockSmolVlmClient(listOf(MockSmolVlmClient.LOW_LIGHT_RESPONSE)).analyzeJpeg(byteArrayOf(2))
        assertEquals("high", result.uncertainty)
        assertTrue(result.unknownObjects.isNotEmpty())
    }

    @Test
    fun suddenObstacleScenarioMarksSceneChange() = runBlocking {
        val result = MockSmolVlmClient(listOf(MockSmolVlmClient.SUDDEN_OBSTACLE_RESPONSE)).analyzeJpeg(byteArrayOf(3))
        assertTrue(result.sceneChange)
        assertEquals("front-right", result.importantObjects.single().position)
    }

    @Test
    fun malformedResponseUsesSafeFallback() = runBlocking {
        val result = MockSmolVlmClient(listOf("{broken")).analyzeJpeg(byteArrayOf(4))
        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, result)
    }

    @Test
    fun requestCountIsBoundedByCalls() = runBlocking {
        val client = MockSmolVlmClient()
        client.analyzeJpeg(byteArrayOf(5))
        client.analyzeJpeg(byteArrayOf(6))
        assertEquals(2, client.requestCount)
    }
}
