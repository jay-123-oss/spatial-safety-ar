package com.manus.spatialsafety.ar.safety

import androidx.annotation.ColorInt

enum class ThreatZone(
    val hindiLabel: String,
    @ColorInt val color: Int,
    val priority: Int,
) {
    SURAKSHIT("Surakshit", 0xFF36D399.toInt(), 0),
    CHETAAVNI("Chetaavni", 0xFFFACC15.toInt(), 1),
    SAVDHAAN("Savdhaan", 0xFFFB923C.toInt(), 2),
    TURANT_RUKE("Turant Ruke", 0xFFF43F5E.toInt(), 3),
    UNKNOWN("Scanning", 0xFF94A3B8.toInt(), -1),
}

/** Robust closest-obstacle estimate for the current tracked AR frame. */
data class ObstacleReading(
    val distanceMeters: Float? = null,
    val zone: ThreatZone = ThreatZone.UNKNOWN,
    val source: DistanceSource = DistanceSource.UNAVAILABLE,
    val confidence: Float = 0f,
    val sampleCount: Int = 0,
    val isStable: Boolean = false,
)

enum class DistanceSource {
    DEPTH_IMAGE,
    POINT_CLOUD,
    UNAVAILABLE,
}

data class AlertDecision(
    val zone: ThreatZone,
    val distanceMeters: Float,
)
