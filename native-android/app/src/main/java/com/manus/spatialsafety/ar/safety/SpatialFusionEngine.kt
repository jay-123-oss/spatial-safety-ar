package com.manus.spatialsafety.ar.safety

import android.graphics.RectF
import android.media.Image
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Converts detector rectangles in view pixels to ARCore depth estimates and applies the safety
 * policy. Depth values are estimates; invalid or unavailable samples stay UNKNOWN rather than
 * being converted into a misleading distance.
 */
class SpatialFusionEngine(
    private val cooldownMs: Long = 2_000L,
    private val meaningfulApproachMeters: Float = 0.35f,
) {
    private data class TriggerRecord(
        val emittedAtMs: Long,
        val zone: ThreatZone,
        val distanceMeters: Float,
    )

    private val triggerHistory = mutableMapOf<String, TriggerRecord>()

    fun fuse(
        frame: Frame,
        viewWidth: Int,
        viewHeight: Int,
        detections: List<Detection>,
    ): List<FusedObstacle> {
        if (viewWidth <= 0 || viewHeight <= 0 || detections.isEmpty()) return emptyList()

        return try {
            frame.acquireDepthImage16Bits().use { depthImage ->
                detections.map { detection ->
                    val center = viewToDepthPixel(frame, detection.box, depthImage, viewWidth, viewHeight)
                    val distance = center?.let { (x, y) -> sampleDepthMeters(depthImage, x, y) }
                    val zone = distance?.let(::classify) ?: ThreatZone.UNKNOWN
                    FusedObstacle(
                        detection = detection,
                        distanceMeters = distance,
                        zone = zone,
                        obstacleKey = obstacleKey(detection, viewWidth, viewHeight),
                    )
                }
            }
        } catch (_: NotYetAvailableException) {
            detections.map { detection ->
                FusedObstacle(detection, null, ThreatZone.UNKNOWN, obstacleKey(detection, viewWidth, viewHeight))
            }
        }
    }

    @Synchronized
    fun nextAlert(obstacles: List<FusedObstacle>, nowMs: Long = System.currentTimeMillis()): AlertDecision? {
        val candidate = obstacles
            .filter { it.distanceMeters != null && it.zone.priority >= ThreatZone.CHETAAVNI.priority }
            .maxWithOrNull(compareBy<FusedObstacle> { it.zone.priority }.thenByDescending { -(it.distanceMeters ?: Float.MAX_VALUE) })
            ?: return null

        val distance = candidate.distanceMeters ?: return null
        val previous = triggerHistory[candidate.obstacleKey]
        val escalated = previous == null || candidate.zone.priority > previous.zone.priority
        val approaching = previous != null && distance <= previous.distanceMeters - meaningfulApproachMeters
        val cooldownExpired = previous == null || nowMs - previous.emittedAtMs >= cooldownMs

        if (!escalated && !approaching && !cooldownExpired) return null

        triggerHistory[candidate.obstacleKey] = TriggerRecord(nowMs, candidate.zone, distance)
        triggerHistory.entries.removeIf { nowMs - it.value.emittedAtMs > cooldownMs * 4 }
        return AlertDecision(
            priorityLevel = candidate.zone.priority,
            hazardName = candidate.detection.label,
            distanceMeters = distance,
            zone = candidate.zone,
        )
    }

    fun classify(distanceMeters: Float): ThreatZone = when {
        distanceMeters > 4f -> ThreatZone.SURAKSHIT
        distanceMeters >= 2.5f -> ThreatZone.CHETAAVNI
        distanceMeters >= 1f -> ThreatZone.SAVDHAAN
        else -> ThreatZone.TURANT_RUKE
    }

    private fun viewToDepthPixel(
        frame: Frame,
        box: RectF,
        depthImage: Image,
        viewWidth: Int,
        viewHeight: Int,
    ): Pair<Int, Int>? {
        val source = floatArrayOf(box.centerX(), box.centerY())
        val target = FloatArray(2)
        frame.transformCoordinates2d(Coordinates2d.VIEW, source, Coordinates2d.DEPTH_IMAGE_PIXELS, target)
        val x = target[0].roundToInt()
        val y = target[1].roundToInt()
        return if (x in 0 until depthImage.width && y in 0 until depthImage.height) x to y else null
    }

    private fun sampleDepthMeters(depthImage: Image, x: Int, y: Int): Float? {
        val plane = depthImage.planes.firstOrNull() ?: return null
        val offset = y * plane.rowStride + x * plane.pixelStride
        if (offset + 1 >= plane.buffer.capacity()) return null
        val millimeters = plane.buffer.duplicate()
            .order(ByteOrder.nativeOrder())
            .getShort(offset)
            .toInt() and 0x1FFF
        return if (millimeters in 1 until 0x1FFF) millimeters / 1000f else null
    }

    private fun obstacleKey(detection: Detection, width: Int, height: Int): String {
        val column = (detection.box.centerX() / (width / 6f)).toInt().coerceIn(0, 5)
        val row = (detection.box.centerY() / (height / 8f)).toInt().coerceIn(0, 7)
        return "${detection.label}:$column:$row"
    }
}
