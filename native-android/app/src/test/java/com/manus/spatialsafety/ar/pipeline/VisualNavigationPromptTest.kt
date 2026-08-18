package com.manus.spatialsafety.ar.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualNavigationPromptTest {
    @Test
    fun parsesExactFiveFieldSchema() {
        val result = VisualNavigationPrompt.parse(
            """
            {
              "environment":"Outdoor street",
              "primary_hazard":"Approaching bike",
              "hazard_position":"From 12 o'clock, 2 meters ahead",
              "spatial_reasoning":"The bike is moving toward the user, so forward movement is unsafe.",
              "action_command":"Stop immediately. Take one step back and wait for the bike to pass."
            }
            """.trimIndent(),
        )

        assertEquals("Outdoor street", result.environment)
        assertEquals("Approaching bike", result.primaryHazard)
        assertEquals("From 12 o'clock, 2 meters ahead", result.hazardPosition)
        assertTrue(result.spatialReasoning.contains("unsafe"))
        assertEquals("Stop immediately. Take one step back and wait for the bike to pass.", result.toTtsText())
    }

    @Test
    fun malformedJsonReturnsSafeFallback() {
        val result = VisualNavigationPrompt.parse(
            "{\"environment\":\"Outdoor street\",\"primary_hazard\":\"Bike\"",
        )

        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, result)
    }

    @Test
    fun missingRequiredFieldReturnsSafeFallback() {
        val result = VisualNavigationPrompt.parse(
            """
            {
              "environment":"Indoor hallway",
              "primary_hazard":"None",
              "hazard_position":"Not applicable",
              "action_command":"The path is clear. Continue walking straight."
            }
            """.trimIndent(),
        )

        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, result)
    }

    @Test
    fun extraFieldReturnsSafeFallbackBecauseSchemaIsExact() {
        val result = VisualNavigationPrompt.parse(
            """
            {
              "environment":"Open walkway",
              "primary_hazard":"None",
              "hazard_position":"Not applicable",
              "spatial_reasoning":"The path is clear.",
              "action_command":"Continue walking straight.",
              "path_status":"CLEAR"
            }
            """.trimIndent(),
        )

        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, result)
    }

    @Test
    fun environmentOverThreeWordsReturnsSafeFallback() {
        val result = VisualNavigationPrompt.parse(
            """{"environment":"Busy outdoor city intersection","primary_hazard":"None","hazard_position":"Not applicable","spatial_reasoning":"The path is clear.","action_command":"Continue walking straight."}""",
        )

        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, result)
    }

    @Test
    fun actionCommandOverTwoSentencesReturnsSafeFallback() {
        val result = VisualNavigationPrompt.parse(
            """{"environment":"Open walkway","primary_hazard":"None","hazard_position":"Not applicable","spatial_reasoning":"The path is clear.","action_command":"Stop. Turn left. Walk straight."}""",
        )

        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, result)
    }
}
