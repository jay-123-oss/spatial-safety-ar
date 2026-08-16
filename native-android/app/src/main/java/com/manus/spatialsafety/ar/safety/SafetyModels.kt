package com.manus.spatialsafety.ar.safety

import android.graphics.RectF
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

data class Detection(
    val label: String,
    val confidence: Float,
    val box: RectF,
)

data class FusedObstacle(
    val detection: Detection,
    val distanceMeters: Float?,
    val zone: ThreatZone,
    val obstacleKey: String,
)

data class AlertDecision(
    val priorityLevel: Int,
    val hazardName: String,
    val distanceMeters: Float,
    val zone: ThreatZone,
)
