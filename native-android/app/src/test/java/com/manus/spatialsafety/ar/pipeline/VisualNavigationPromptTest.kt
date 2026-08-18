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
    fun exposesAllAllowedPathStatuses() {
        assertEquals(
            setOf("CLEAR", "BLOCKED", "PARTIALLY_BLOCKED", "HAZARDOUS"),
            PathStatus.entries.map { it.name }.toSet(),
        )
    }
}
