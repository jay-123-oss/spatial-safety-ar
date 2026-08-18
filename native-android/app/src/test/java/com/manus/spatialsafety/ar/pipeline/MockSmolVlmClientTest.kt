package com.manus.spatialsafety.ar.pipeline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockSmolVlmClientTest {
    @Test
    fun offlineCameraFrameSimulationReturnsHazardGuidance() = runBlocking {
        val client = MockSmolVlmClient()
        val syntheticCameraJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

        val result = client.analyzeJpeg(syntheticCameraJpeg)

        assertEquals(1, client.requestCount)
        assertEquals("Open gutter", result.primaryHazard)
        assertTrue(result.actionCommand.startsWith("Stop immediately"))
        assertEquals(result.actionCommand, result.toTtsText())
    }

    @Test
    fun offlineSimulationCanAdvanceFromHazardToClearPath() = runBlocking {
        val client = MockSmolVlmClient(
            listOf(MockSmolVlmClient.DEFAULT_HAZARD_RESPONSE, MockSmolVlmClient.DEFAULT_CLEAR_RESPONSE),
        )

        val hazard = client.analyzeJpeg(byteArrayOf(1))
        val clear = client.analyzeJpeg(byteArrayOf(2))

        assertEquals("Open gutter", hazard.primaryHazard)
        assertEquals("None", clear.primaryHazard)
        assertEquals("The path is clear. Continue walking straight at your normal pace.", clear.toTtsText())
        assertEquals(2, client.requestCount)
    }

    @Test
    fun invalidOfflineModelResponseUsesSafeFallback() = runBlocking {
        val client = MockSmolVlmClient(listOf("{\"environment\":\"Broken"))

        val result = client.analyzeJpeg(byteArrayOf(3))

        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, result)
    }
}
