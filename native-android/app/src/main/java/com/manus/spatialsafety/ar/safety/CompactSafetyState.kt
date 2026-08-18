package com.manus.spatialsafety.ar.safety

/** Compact user-facing path state; avoids exposing every peripheral detection. */
enum class PathStatus { CLEAR, PARTIALLY_BLOCKED, BLOCKED, UNCERTAIN }
enum class HazardPosition { LEFT, CENTER, RIGHT, UNKNOWN }
enum class RiskLevel { LOW, MEDIUM, HIGH, UNKNOWN }
enum class SafetyAction { CONTINUE, CAUTION, STOP, WAIT }

data class DetectorEvidence(
    val hazard: String? = null,
    val confidence: Float = 0f,
    val position: HazardPosition = HazardPosition.UNKNOWN,
)

data class VlmEvidence(
    val hazard: String? = null,
    val confidence: Float = 0f,
    val pathStatus: PathStatus = PathStatus.UNCERTAIN,
    val uncertainty: String = "unknown",
)

data class CompactSafetyState(
    val pathStatus: PathStatus = PathStatus.UNCERTAIN,
    val hazard: String? = null,
    val distanceMeters: Float? = null,
    val position: HazardPosition = HazardPosition.UNKNOWN,
    val confidence: Float = 0f,
    val risk: RiskLevel = RiskLevel.UNKNOWN,
    val action: SafetyAction = SafetyAction.WAIT,
)

/**
 * Fuses evidence according to responsibility instead of averaging unrelated confidences.
 * Depth owns metric distance and immediate risk; detector owns category when confident; VLM
 * contributes context only when the fast path is ambiguous.
 */
object SafetyFusion {
    fun fromDepth(reading: ObstacleReading): CompactSafetyState {
        val distance = reading.distanceMeters
        val path = when (reading.zone) {
            ThreatZone.SURAKSHIT -> PathStatus.CLEAR
            ThreatZone.CHETAAVNI -> PathStatus.PARTIALLY_BLOCKED
            ThreatZone.SAVDHAAN, ThreatZone.TURANT_RUKE -> PathStatus.BLOCKED
            ThreatZone.UNKNOWN -> PathStatus.UNCERTAIN
        }
        val risk = when (reading.zone) {
            ThreatZone.SURAKSHIT -> RiskLevel.LOW
            ThreatZone.CHETAAVNI -> RiskLevel.MEDIUM
            ThreatZone.SAVDHAAN, ThreatZone.TURANT_RUKE -> RiskLevel.HIGH
            ThreatZone.UNKNOWN -> RiskLevel.UNKNOWN
        }
        val action = when (reading.zone) {
            ThreatZone.SURAKSHIT -> SafetyAction.CONTINUE
            ThreatZone.CHETAAVNI -> SafetyAction.CAUTION
            ThreatZone.SAVDHAAN, ThreatZone.TURANT_RUKE -> SafetyAction.STOP
            ThreatZone.UNKNOWN -> SafetyAction.WAIT
        }
        return CompactSafetyState(
            pathStatus = path,
            hazard = distance?.let { if (it <= 1.5f) "obstacle" else null },
            distanceMeters = distance,
            position = if (distance != null) HazardPosition.CENTER else HazardPosition.UNKNOWN,
            confidence = reading.confidence.coerceIn(0f, 1f),
            risk = risk,
            action = action,
        )
    }

    fun fuse(
        depth: ObstacleReading,
        detector: DetectorEvidence? = null,
        vlm: VlmEvidence? = null,
    ): CompactSafetyState {
        val depthState = fromDepth(depth)
        if (depthState.risk == RiskLevel.HIGH) return depthState
        val detectorUsable = detector?.confidence?.let { it.isFinite() && it >= 0.55f } == true
        val hazard = when {
            detectorUsable -> detector?.hazard
            depthState.hazard != null -> depthState.hazard
            else -> vlm?.hazard
        }
        val position = when {
            detectorUsable -> detector?.position ?: HazardPosition.UNKNOWN
            else -> depthState.position
        }
        val confidence = when {
            depthState.distanceMeters != null -> depthState.confidence
            detectorUsable -> detector?.confidence ?: 0f
            else -> (vlm?.confidence ?: 0f).coerceIn(0f, 1f)
        }
        val path = when {
            depthState.pathStatus != PathStatus.UNCERTAIN -> depthState.pathStatus
            vlm != null && vlm.uncertainty == "low" -> vlm.pathStatus
            else -> PathStatus.UNCERTAIN
        }
        return depthState.copy(
            pathStatus = path,
            hazard = hazard,
            position = position,
            confidence = confidence,
            risk = if (path == PathStatus.BLOCKED) RiskLevel.HIGH else depthState.risk,
            action = if (path == PathStatus.BLOCKED) SafetyAction.STOP else depthState.action,
        )
    }
}
