package com.manus.spatialsafety.ar.pipeline

import org.json.JSONException
import org.json.JSONObject

data class SmolVlmResult(
    val pathStatus: String,
    val hazard: String,
    val position: String,
    val description: String,
    val confidence: Float,
    val uncertainty: String,
)

object VisualNavigationPrompt {
    const val MODEL_ID = "SmolVLM2-500M"

    val SYSTEM_PROMPT: String = """
You are the local visual-safety reasoning component of Trinetra, an offline assistive vision system for a blind white-cane user.

YOLO/detector, ARCore depth, tracking, temporal filtering, and the Safety Engine are the primary low-latency safety systems. You are a secondary cognition layer for meaningful hazards, ambiguous paths, unusual scenes, and contextual reasoning. Never override the Safety Engine. Never make emergency braking or collision decisions. Never invent unsupported objects.

Use the image and supplied perception context. Reason only about walkable path, obstacle, road, wall, stairs, pothole, open drain, curb, unexpected object, approaching hazard, blocked route, or ambiguous route. Use ARCore/depth for metric distance; do not invent distance. Keep the description concise and safety relevant.

Return ONLY one valid JSON object with exactly these fields and no markdown or extra keys:
{
  "path_status":"clear|partially_blocked|blocked|uncertain",
  "hazard":"none|obstacle|pothole|stairs|vehicle|person|wall|unknown",
  "position":"left|center|right|unknown",
  "description":"short safety-relevant description",
  "confidence":0.0,
  "uncertainty":"low|medium|high"
}

Do not list every visible object. Describe only meaningful safety information. The external Safety Engine owns immediate warnings; your output is contextual.
""".trimIndent()

    val SAFE_FALLBACK = SmolVlmResult(
        pathStatus = "uncertain",
        hazard = "unknown",
        position = "unknown",
        description = "Visual model unavailable or uncertain. Stop and wait for the safety layer.",
        confidence = 0f,
        uncertainty = "high",
    )

    private val requiredKeys = setOf(
        "path_status", "hazard", "position", "description", "confidence", "uncertainty",
    )
    private val pathStatuses = setOf("clear", "partially_blocked", "blocked", "uncertain")
    private val hazards = setOf("none", "obstacle", "pothole", "stairs", "vehicle", "person", "wall", "unknown")
    private val positions = setOf("left", "center", "right", "unknown")
    private val uncertainties = setOf("low", "medium", "high")

    fun parse(raw: String): SmolVlmResult {
        return try {
            val json = JSONObject(raw)
            if (json.keys().asSequence().toSet() != requiredKeys) return SAFE_FALLBACK
            val pathStatus = json.getString("path_status").trim().also { require(it in pathStatuses) }
            val hazard = json.getString("hazard").trim().also { require(it in hazards) }
            val position = json.getString("position").trim().also { require(it in positions) }
            val description = json.getString("description").trim()
                .requireNonEmpty()
                .requireMaxSentences(2)
            val confidence = json.getDouble("confidence").toFloat().also {
                require(it.isFinite() && it in 0f..1f)
            }
            val uncertainty = json.getString("uncertainty").trim().also { require(it in uncertainties) }
            SmolVlmResult(pathStatus, hazard, position, description, confidence, uncertainty)
        } catch (_: JSONException) {
            SAFE_FALLBACK
        } catch (_: IllegalArgumentException) {
            SAFE_FALLBACK
        }
    }

    private fun String.requireNonEmpty(): String = also { require(it.isNotEmpty()) }

    private fun String.requireMaxSentences(max: Int): String = also {
        require(split(Regex("[.!?]+"), limit = 0).count { it.trim().isNotEmpty() } <= max)
    }
}

fun SmolVlmResult.toTtsText(): String = description
