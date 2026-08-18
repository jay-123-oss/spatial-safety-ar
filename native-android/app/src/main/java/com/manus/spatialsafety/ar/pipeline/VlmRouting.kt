package com.manus.spatialsafety.ar.pipeline

/** Structured context passed from YOLO, tracking, ARCore/depth, and the Safety Engine. */
data class PerceptionContext(
    val yoloConfidence: Float? = null,
    val unknownObject: Boolean = false,
    val detectionsUnstable: Boolean = false,
    val sceneChanged: Boolean = false,
    val overlappingObjects: Boolean = false,
    val safetyCritical: Boolean = false,
    val depthDistanceMeters: Float? = null,
    val userQuery: String? = null,
)

data class VlmTriggerConfig(
    val confidenceThreshold: Float = 0.55f,
    val cooldownMs: Long = 1_200L,
    val repeatedUnknownFrames: Int = 2,
)

enum class VlmTriggerReason {
    LOW_CONFIDENCE,
    UNKNOWN_OBJECT,
    SCENE_CHANGE,
    COMPLEX_SCENE,
    USER_QUERY,
}

data class VlmTriggerDecision(
    val enabled: Boolean,
    val reason: VlmTriggerReason? = null,
)

/** Event-driven policy. Safety-critical warnings never wait for VLM. */
class VlmTriggerManager(
    private val config: VlmTriggerConfig = VlmTriggerConfig(),
) {
    private var unknownFrameCount = 0

    fun evaluate(context: PerceptionContext): VlmTriggerDecision {
        if (context.safetyCritical) return VlmTriggerDecision(false)
        val query = context.userQuery?.trim().orEmpty()
        if (query.isNotEmpty()) return VlmTriggerDecision(true, VlmTriggerReason.USER_QUERY)
        if (context.yoloConfidence != null && context.yoloConfidence < config.confidenceThreshold) {
            return VlmTriggerDecision(true, VlmTriggerReason.LOW_CONFIDENCE)
        }
        if (context.unknownObject || context.detectionsUnstable) {
            unknownFrameCount++
            if (unknownFrameCount >= config.repeatedUnknownFrames) {
                return VlmTriggerDecision(true, VlmTriggerReason.UNKNOWN_OBJECT)
            }
        } else {
            unknownFrameCount = 0
        }
        if (context.sceneChanged) return VlmTriggerDecision(true, VlmTriggerReason.SCENE_CHANGE)
        if (context.overlappingObjects) return VlmTriggerDecision(true, VlmTriggerReason.COMPLEX_SCENE)
        return VlmTriggerDecision(false)
    }
}

/** Cooldown and single-flight gate; never creates an inference queue. */
class VlmRouter(
    private val triggerManager: VlmTriggerManager = VlmTriggerManager(),
    private val config: VlmTriggerConfig = VlmTriggerConfig(),
) {
    private var lastInvocationMs = Long.MIN_VALUE
    private var busy = false

    fun request(context: PerceptionContext, nowMs: Long): VlmTriggerDecision {
        val decision = triggerManager.evaluate(context)
        val inCooldown = lastInvocationMs != Long.MIN_VALUE && nowMs - lastInvocationMs < config.cooldownMs
        if (!decision.enabled || busy || inCooldown) {
            return VlmTriggerDecision(false)
        }
        busy = true
        lastInvocationMs = nowMs
        return decision
    }

    fun complete() {
        busy = false
    }

    fun isBusy(): Boolean = busy
}
