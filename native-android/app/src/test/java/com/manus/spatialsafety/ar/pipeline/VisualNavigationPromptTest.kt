package com.manus.spatialsafety.ar.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualNavigationPromptTest {
    @Test
    fun parsesExactCompactSafetySchema() {
        val result = VisualNavigationPrompt.parse("""
            {"path_status":"blocked","hazard":"pothole","position":"center","description":"A pothole blocks the walking path. Stop.","confidence":0.88,"uncertainty":"low"}
        """.trimIndent())
        assertEquals("blocked", result.pathStatus)
        assertEquals("pothole", result.hazard)
        assertEquals("center", result.position)
        assertEquals(0.88f, result.confidence)
        assertEquals(result.description, result.toTtsText())
    }

    @Test
    fun malformedJsonReturnsSafeFallback() {
        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, VisualNavigationPrompt.parse("{broken"))
    }

    @Test
    fun missingFieldReturnsSafeFallback() {
        val missing = """{"path_status":"clear","hazard":"none","position":"unknown","description":"Clear.","confidence":0.9}"""
        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, VisualNavigationPrompt.parse(missing))
    }

    @Test
    fun extraFieldReturnsSafeFallback() {
        val extra = MockSmolVlmClient.DEFAULT_CLEAR_RESPONSE.trim().dropLast(1).plus(",\"extra\":true}")
        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, VisualNavigationPrompt.parse(extra))
    }

    @Test
    fun invalidConfidenceAndPositionReturnSafeFallback() {
        val invalidConfidence = """{"path_status":"blocked","hazard":"obstacle","position":"center","description":"Stop.","confidence":1.5,"uncertainty":"low"}"""
        val invalidPosition = """{"path_status":"blocked","hazard":"obstacle","position":"front","description":"Stop.","confidence":0.8,"uncertainty":"low"}"""
        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, VisualNavigationPrompt.parse(invalidConfidence))
        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, VisualNavigationPrompt.parse(invalidPosition))
    }

    @Test
    fun lowLightResponseRetainsHighUncertainty() {
        val result = VisualNavigationPrompt.parse(MockSmolVlmClient.LOW_LIGHT_RESPONSE)
        assertEquals("high", result.uncertainty)
        assertTrue(result.description.contains("dark"))
    }
}
