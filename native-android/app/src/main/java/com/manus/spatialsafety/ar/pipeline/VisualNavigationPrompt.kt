package com.manus.spatialsafety.ar.pipeline

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class VlmObject(
    val name: String,
    val confidence: Float,
    val position: String,
    val relation: String,
)

data class SmolVlmResult(
    val scene: String,
    val importantObjects: List<VlmObject>,
    val unknownObjects: List<String>,
    val pathStatus: String,
    val sceneChange: Boolean,
    val description: String,
    val uncertainty: String,
)

object VisualNavigationPrompt {
    const val MODEL_ID = "SmolVLM2-500M"

    val SYSTEM_PROMPT: String = """
You are the local visual-reasoning component of Trinetra, an offline-first assistive vision system for a blind white-cane user. YOLO11n, ARCore/Depth, tracking, and the Safety Engine remain the primary low-latency safety systems. You are a secondary cognition layer for uncertain objects, scene understanding, context, user queries, and unusual relationships. Never replace or override the Safety Engine, and never make emergency, collision, braking, or dangerous-navigation decisions.

Use the supplied image and structured perception context. Do not invent unsupported objects. Use the human-friendly positions front-left, front, front-right, left, right, far-left, far-right, behind, or unknown. Use ARCore/Depth for geometric distance. If evidence is insufficient, say uncertain or unknown. Keep the description to one or two short sentences.

Return ONLY one valid JSON object with exactly these fields:
{
  "scene":"short scene label",
  "important_objects":[{"name":"object","confidence":0.0,"position":"front|front-left|front-right|left|right|far-left|far-right|behind|unknown","relation":"context"}],
  "unknown_objects":[],
  "path_status":"clear|partially_blocked|blocked|uncertain",
  "scene_change":false,
  "description":"one or two concise contextual sentences",
  "uncertainty":"low|medium|high|unknown"
}

Do not include markdown, commentary, or extra keys. The external Safety Engine owns immediate warnings; your output is contextual information for the ResponsePriorityManager and developer UI.
""".trimIndent()

    val SAFE_FALLBACK = SmolVlmResult(
        scene = "Unclear scene",
        importantObjects = emptyList(),
        unknownObjects = listOf("unknown scene"),
        pathStatus = "uncertain",
        sceneChange = false,
        description = "The visual model is unavailable or uncertain. The safety layer remains in control.",
        uncertainty = "high",
    )

    private val requiredKeys = setOf(
        "scene", "important_objects", "unknown_objects", "path_status", "scene_change", "description", "uncertainty",
    )
    private val positions = setOf("front-left", "front", "front-right", "left", "right", "far-left", "far-right", "behind", "unknown")
    private val pathStatuses = setOf("clear", "partially_blocked", "blocked", "uncertain")
    private val uncertainties = setOf("low", "medium", "high", "unknown")

    fun parse(raw: String): SmolVlmResult {
        return try {
        val json = JSONObject(raw)
        if (json.keys().asSequence().toSet() != requiredKeys) return SAFE_FALLBACK
        val objects = json.getJSONArray("important_objects").parseObjects()
        val unknown = json.getJSONArray("unknown_objects").parseStrings()
        val scene = json.getString("scene").trim().requireNonEmpty()
        val status = json.getString("path_status").trim().also { require(it in pathStatuses) }
        val description = json.getString("description").trim().requireNonEmpty().requireMaxSentences(2)
        val uncertainty = json.getString("uncertainty").trim().also { require(it in uncertainties) }
        SmolVlmResult(scene, objects, unknown, status, json.getBoolean("scene_change"), description, uncertainty)
    } catch (_: JSONException) {
        SAFE_FALLBACK
        } catch (_: IllegalArgumentException) {
            SAFE_FALLBACK
        }
    }

    private fun JSONArray.parseObjects(): List<VlmObject> = (0 until length()).map { index ->
        val obj = getJSONObject(index)
        val keys = obj.keys().asSequence().toSet()
        require(keys == setOf("name", "confidence", "position", "relation"))
        VlmObject(
            name = obj.getString("name").trim().requireNonEmpty(),
            confidence = obj.getDouble("confidence").toFloat().also { require(it in 0f..1f) },
            position = obj.getString("position").trim().also { require(it in positions) },
            relation = obj.getString("relation").trim().requireNonEmpty(),
        )
    }

    private fun JSONArray.parseStrings(): List<String> = (0 until length()).map {
        getString(it).trim().requireNonEmpty()
    }

    private fun String.requireNonEmpty(): String = also { require(it.isNotEmpty()) }
    private fun String.requireMaxSentences(max: Int): String = also {
        require(split(Regex("[.!?]+"), limit = 0).count { it.trim().isNotEmpty() } <= max)
    }
}

fun SmolVlmResult.toTtsText(): String = description
