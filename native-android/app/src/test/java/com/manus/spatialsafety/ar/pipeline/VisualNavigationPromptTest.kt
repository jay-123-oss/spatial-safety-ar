package com.manus.spatialsafety.ar.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualNavigationPromptTest {
    @Test
    fun parsesRequiredStrictSchema() {
        val result = VisualNavigationPrompt.parse(
            """
            {
              "environment":"Outdoor street",
              "path_status":"HAZARDOUS",
              "hazards_detected":[{"object":"Open manhole","position":"Directly ahead, about 2 steps away"}],
              "text_signs":"None",
              "actionable_guidance":"Stop immediately. Tap your cane to the right to find a safe path around it."
            }
            """.trimIndent(),
        )

        assertEquals(PathStatus.HAZARDOUS, result.pathStatus)
        assertEquals("Open manhole", result.hazardsDetected.single().objectName)
        assertTrue(result.actionableGuidance.contains("cane"))
    }

    @Test
    fun malformedOrUnexpectedSchemaReturnsSafeFallback() {
        val result = VisualNavigationPrompt.parse(
            """{"environment":"Hallway","path_status":"CLEAR","hazards_detected":[],"text_signs":"None","actionable_guidance":"Continue straight","unexpected":"reject"}""",
        )

        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, result)
    }

    @Test
    fun malformedJsonReturnsSafeFallback() {
        val result = VisualNavigationPrompt.parse(
            "{\"environment\":\"Outdoor street\",\"path_status\":\"HAZARDOUS\"",
        )

        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, result)
    }

    @Test
    fun missingRequiredFieldReturnsSafeFallback() {
        val result = VisualNavigationPrompt.parse(
            """
            {
              "environment":"Indoor corridor",
              "path_status":"CLEAR",
              "hazards_detected":[],
              "text_signs":"None"
            }
            """.trimIndent(),
        )

        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, result)
    }

    @Test
    fun missingHazardPositionReturnsSafeFallback() {
        val result = VisualNavigationPrompt.parse(
            """
            {
              "environment":"Busy street",
              "path_status":"PARTIALLY_BLOCKED",
              "hazards_detected":[{"object":"Parked scooter"}],
              "text_signs":"None",
              "actionable_guidance":"Move one step right and continue straight."
            }
            """.trimIndent(),
        )

        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, result)
    }

    @Test
    fun invalidPathStatusFromSimulatedCameraFrameReturnsSafeFallback() {
        // Simulates the raw model response received after an AR camera frame.
        val simulatedCameraFrameResponse =
            """{"environment":"Indoor hallway","path_status":"DANGER","hazards_detected":[],"text_signs":"None","actionable_guidance":"Stop and wait."}"""

        val result = VisualNavigationPrompt.parse(simulatedCameraFrameResponse)

        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, result)
        assertEquals("Stop and remain in place. The scene is unclear. Sweep your cane slowly around you and wait for a clearer view before moving.", result.toTtsText())
    }

    @Test
    fun exposesAllAllowedPathStatuses() {
        assertEquals(
            setOf("CLEAR", "BLOCKED", "PARTIALLY_BLOCKED", "HAZARDOUS"),
            PathStatus.entries.map { it.name }.toSet(),
        )
    }
}
