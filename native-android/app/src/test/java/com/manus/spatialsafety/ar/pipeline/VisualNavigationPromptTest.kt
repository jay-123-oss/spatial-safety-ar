package com.manus.spatialsafety.ar.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualNavigationPromptTest {
    @Test
    fun parsesStructuredMasterPromptResponse() {
        val result = VisualNavigationPrompt.parse(MockSmolVlmClient.DEFAULT_HAZARD_RESPONSE)
        assertEquals("Uneven footpath", result.scene)
        assertEquals("open gutter", result.importantObjects.single().name)
        assertEquals("front", result.importantObjects.single().position)
        assertEquals("blocked", result.pathStatus)
        assertEquals(result.description, result.toTtsText())
    }

    @Test
    fun malformedJsonReturnsSafeFallback() {
        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, VisualNavigationPrompt.parse("{broken"))
    }

    @Test
    fun missingFieldReturnsSafeFallback() {
        val missing = """{"scene":"Hallway","important_objects":[],"unknown_objects":[],"path_status":"clear","scene_change":false,"description":"Clear."}"""
        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, VisualNavigationPrompt.parse(missing))
    }

    @Test
    fun extraFieldReturnsSafeFallback() {
        val extra = MockSmolVlmClient.DEFAULT_CLEAR_RESPONSE.trim().dropLast(1).plus(",\"extra\":true}")
        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, VisualNavigationPrompt.parse(extra))
    }

    @Test
    fun invalidPositionAndConfidenceReturnSafeFallback() {
        val invalid = """{"scene":"Road","important_objects":[{"name":"car","confidence":1.5,"position":"nearby","relation":"blocking"}],"unknown_objects":[],"path_status":"blocked","scene_change":false,"description":"A car blocks the path.","uncertainty":"low"}"""
        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, VisualNavigationPrompt.parse(invalid))
    }

    @Test
    fun lowLightResponseRetainsHighUncertainty() {
        val result = VisualNavigationPrompt.parse(MockSmolVlmClient.LOW_LIGHT_RESPONSE)
        assertEquals("high", result.uncertainty)
        assertTrue(result.description.contains("dark"))
    }
}
