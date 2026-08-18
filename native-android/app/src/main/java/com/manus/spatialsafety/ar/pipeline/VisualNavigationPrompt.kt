package com.manus.spatialsafety.ar.pipeline

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Strict response contract for the Trinetra visual cognition and navigation engine. */
data class VisualNavigationHazard(
    val objectName: String,
    val position: String,
)

data class VisualNavigationResult(
    val environment: String,
    val pathStatus: PathStatus,
    val hazardsDetected: List<VisualNavigationHazard>,
    val textSigns: String,
    val actionableGuidance: String,
)

enum class PathStatus {
    CLEAR,
    BLOCKED,
    PARTIALLY_BLOCKED,
    HAZARDOUS,
}

object VisualNavigationPrompt {
    const val SYSTEM_PROMPT: String = """
You are the central Visual Cognition and Navigation Engine for Trinetra, an AI-powered assistive wearable for blind and visually impaired users. Analyze a first-person camera frame from chest or head level. Detect surface and floor hazards, dynamic obstacles, static path blockers, overhead hazards, and readable signs. Describe positions relative to the user's body using practical distances such as directly ahead, slightly to your left, on your immediate right, one step ahead, touching distance, or about two meters away. Never use compass directions or screen coordinates.

The user cannot see the image and receives your response through text-to-speech. Use empathetic, clear, physical-space language and avoid visual jargon. Every detected hazard MUST include a precise physical maneuver to bypass it. Never return a generic warning without a solution. Guidance must be TTS-ready.

OUTPUT CONSTRAINTS (CRITICAL): Return ONLY one valid JSON object. Do not add markdown fences, conversational text, greetings, or explanations. If nothing specific can be detected, return the safe fallback object below.

REQUIRED JSON SCHEMA:
{
  "environment": "Short description of the scene",
  "path_status": "CLEAR | BLOCKED | PARTIALLY_BLOCKED | HAZARDOUS",
  "hazards_detected": [{ "object": "Name of hazard or None", "position": "Exact relative position" }],
  "text_signs": "Readable text or None",
  "actionable_guidance": "Exact physical movement instruction for the blind user; TTS-ready"
}

SAFE FALLBACK:
{
  "environment": "Scene unclear",
  "path_status": "HAZARDOUS",
  "hazards_detected": [{ "object": "Unknown hazard", "position": "Directly ahead; position unclear" }],
  "text_signs": "None",
  "actionable_guidance": "Stop and remain in place. The scene is unclear. Sweep your cane slowly around you and wait for a clearer view before moving."
}
""".trimIndent()

    val SAFE_FALLBACK = VisualNavigationResult(
        environment = "Scene unclear",
        pathStatus = PathStatus.HAZARDOUS,
        hazardsDetected = listOf(VisualNavigationHazard("Unknown hazard", "Directly ahead; position unclear")),
        textSigns = "None",
        actionableGuidance = "Stop and remain in place. The scene is unclear. Sweep your cane slowly around you and wait for a clearer view before moving.",
    )

    /** Parses only the required JSON object and never throws to a caller handling live frames. */
    fun parse(raw: String): VisualNavigationResult {
        return try {
            val json = JSONObject(raw)
            val keys = json.keys().asSequence().toSet()
            val required = setOf("environment", "path_status", "hazards_detected", "text_signs", "actionable_guidance")
            if (keys != required) return SAFE_FALLBACK

            val environment = json.getString("environment").trim().requireNonEmpty()
            val pathStatus = PathStatus.valueOf(json.getString("path_status"))
            val hazards = parseHazards(json.getJSONArray("hazards_detected"))
            val textSigns = json.getString("text_signs").trim().requireNonEmpty()
            val guidance = json.getString("actionable_guidance").trim().requireNonEmpty()
            VisualNavigationResult(environment, pathStatus, hazards, textSigns, guidance)
        } catch (_: JSONException) {
            SAFE_FALLBACK
        } catch (_: IllegalArgumentException) {
            SAFE_FALLBACK
        }
    }

    private fun parseHazards(array: JSONArray): List<VisualNavigationHazard> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val keys = item.keys().asSequence().toSet()
                if (keys != setOf("object", "position")) throw JSONException("Invalid hazard schema")
                add(
                    VisualNavigationHazard(
                        objectName = item.getString("object").trim().requireNonEmpty(),
                        position = item.getString("position").trim().requireNonEmpty(),
                    ),
                )
            }
        }
    }

    private fun String.requireNonEmpty(): String = also {
        if (it.isEmpty()) throw IllegalArgumentException("Required value is empty")
    }
}

/** Converts a parsed result into a single safe TTS utterance. */
fun VisualNavigationResult.toTtsText(): String = actionableGuidance
