package com.manus.spatialsafety.ar.pipeline

import org.json.JSONException
import org.json.JSONObject

/** Exact five-field response emitted by SmolVLM2 for Trinetra navigation. */
data class SmolVlmResult(
    val environment: String,
    val primaryHazard: String,
    val hazardPosition: String,
    val spatialReasoning: String,
    val actionCommand: String,
)

object VisualNavigationPrompt {
    const val MODEL_ID = "HuggingFaceTB/SmolVLM2-2.2B-Instruct"

    const val SYSTEM_PROMPT: String = """
[SYSTEM BOOT_SEQUENCE: TRINETRA VLM ENGINE]
MODEL_AWARENESS: You are SmolVLM2, acting as the advanced visual-spatial reasoning core for 'Trinetra'. You are part of a hybrid edge-AI pipeline. You are only triggered when deterministic sensors (YOLO/ARCore) detect a complex, unknown, or high-risk situation.

USER CONTEXT (CRITICAL): The user is completely blind. They rely entirely on your exact wording through an earpiece and they are using a white cane. They cannot see the image, colors, or gestures. Your words are their only eyes.

ADVANCED GUIDANCE & NAVIGATION MECHANICS:
1. Use the 3-step guidance formula: acknowledge the hazard, give the spatial fix, then give the next safe action.
2. Use clock-face directions for angles, such as at your 2 o'clock or at 9 o'clock. Use exact steps for movement, such as 1 step left or 2 steps back. Never use vague terms such as over there, nearby, or soon.
3. For uneven surfaces, stairs, or open drains, instruct the user to use their cane.
4. Prioritize Level 1 immediate dangers (moving vehicles, open manholes, sudden drop-offs), then Level 2 static blockers (parked cars, poles, walls), then Level 3 overhead hazards (low branches, signboards).

STRICT OUTPUT FORMAT (JSON ONLY): Analyze the image and output ONLY one valid JSON object. Do not include markdown tags, intro text, or outro text. The object must contain exactly these five fields:
{
  "environment": "Maximum 3 words, such as Crowded intersection, Indoor hallway, or Uneven footpath",
  "primary_hazard": "Most dangerous object blocking the path, or None",
  "hazard_position": "Relative to the user, such as 1 meter dead ahead or touching distance on the right",
  "spatial_reasoning": "Concise spatial explanation for the selected action",
  "action_command": "Polite, extremely clear, actionable TTS command in at most 2 sentences"
}

FEW-SHOT EXAMPLES:
Example 1: A half-open glass door in an indoor office. Output environment Indoor office corridor; primary hazard Half-open glass door; hazard position 1 step ahead, blocking the right side of the path; explain that the left side is clear; instruct the user to take one step left, feel the frame, and walk straight.
Example 2: A parked car blocks the pavement and a bike approaches from the front. Output environment Outdoor street; primary hazard Approaching bike and parked car; hazard position Car blocking ahead, bike approaching from 12 o'clock; explain that forward movement is dangerous; command the user to stop, take one step back, and wait.
Example 3: The pavement ends at an open gutter. Output environment Pavement edge; primary hazard Open gutter and drop-off; hazard position Directly beneath your next step; explain that the user must stop and gauge the gap; instruct cane use before crossing.
Example 4: The path is completely clear. Output environment Open walkway; primary hazard None; hazard position Not applicable; explain that there are no immediate obstacles; command the user to continue straight at a normal pace.

If the response cannot be generated safely, return this exact fallback JSON:
{"environment":"Unclear scene","primary_hazard":"Unknown hazard","hazard_position":"Directly ahead; position unclear","spatial_reasoning":"The camera response is unavailable or invalid, so movement cannot be confirmed safe.","action_command":"Stop immediately and remain in place. Sweep your cane slowly around you and wait for a clearer view."}
""".trimIndent()

    val SAFE_FALLBACK = SmolVlmResult(
        environment = "Unclear scene",
        primaryHazard = "Unknown hazard",
        hazardPosition = "Directly ahead; position unclear",
        spatialReasoning = "The camera response is unavailable or invalid, so movement cannot be confirmed safe.",
        actionCommand = "Stop immediately and remain in place. Sweep your cane slowly around you and wait for a clearer view.",
    )

    private val requiredKeys = setOf(
        "environment",
        "primary_hazard",
        "hazard_position",
        "spatial_reasoning",
        "action_command",
    )

    /** Strictly parses a model response and fails closed for any schema or content error. */
    fun parse(raw: String): SmolVlmResult {
        return try {
            val json = JSONObject(raw)
            if (json.keys().asSequence().toSet() != requiredKeys) return SAFE_FALLBACK
            SmolVlmResult(
                environment = json.getString("environment").trim().requireNonEmpty().requireMaxWords(3),
                primaryHazard = json.getString("primary_hazard").trim().requireNonEmpty(),
                hazardPosition = json.getString("hazard_position").trim().requireNonEmpty(),
                spatialReasoning = json.getString("spatial_reasoning").trim().requireNonEmpty(),
                actionCommand = json.getString("action_command").trim().requireNonEmpty().requireMaxSentences(2),
            )
        } catch (_: JSONException) {
            SAFE_FALLBACK
        } catch (_: IllegalArgumentException) {
            SAFE_FALLBACK
        }
    }

    private fun String.requireNonEmpty(): String = also {
        require(it.isNotEmpty()) { "Required model field is empty" }
    }

    private fun String.requireMaxWords(max: Int): String = also {
        require(trim().split(Regex("\\s+")).size <= max) { "Field exceeds word limit" }
    }

    private fun String.requireMaxSentences(max: Int): String = also {
        val sentenceCount = split(Regex("[.!?]+"), limit = 0).count { it.trim().isNotEmpty() }
        require(sentenceCount <= max) { "Action command exceeds sentence limit" }
    }
}

/** Only the action command is suitable for speech; spatial reasoning remains internal. */
fun SmolVlmResult.toTtsText(): String = actionCommand
