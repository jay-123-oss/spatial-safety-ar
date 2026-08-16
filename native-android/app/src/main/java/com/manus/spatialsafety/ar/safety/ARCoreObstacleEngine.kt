package com.manus.spatialsafety.ar.safety

import android.media.Image
import android.os.SystemClock
import com.google.ar.core.Frame
import com.google.ar.core.PointCloud
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A fully offline ARCore safety engine. It inspects the central part of the Depth API image to
 * estimate the closest obstacle in front of the user. If a depth image is not ready, it falls
 * back to center-field ARCore point-cloud samples. No ML model, detector, or network is used.
 */
class ARCoreObstacleEngine(
    private val analysisIntervalMs: Long = 90L,
    private val cooldownMs: Long = 2_000L,
    private val meaningfulApproachMeters: Float = 0.35f,
) {
    private data class AlertRecord(
        val emittedAtMs: Long,
        val zone: ThreatZone,
        val distanceMeters: Float,
    )

    private var lastAnalysisAtMs = 0L
    private var lastReading = ObstacleReading()
    private var lastAlert: AlertRecord? = null

    fun analyze(frame: Frame, nowMs: Long = SystemClock.elapsedRealtime()): ObstacleReading {
        if (nowMs - lastAnalysisAtMs < analysisIntervalMs) return lastReading
        lastAnalysisAtMs = nowMs

        val depthDistance = sampleCenterDepth(frame)
        val pointDistance = depthDistance ?: sampleCenterPointCloud(frame)
        val source = when {
            depthDistance != null -> DistanceSource.DEPTH_IMAGE
            pointDistance != null -> DistanceSource.POINT_CLOUD
            else -> DistanceSource.UNAVAILABLE
        }
        lastReading = ObstacleReading(
            distanceMeters = pointDistance,
            zone = pointDistance?.let(::classify) ?: ThreatZone.UNKNOWN,
            source = source,
        )
        return lastReading
    }

    @Synchronized
    fun nextAlert(reading: ObstacleReading, nowMs: Long = SystemClock.elapsedRealtime()): AlertDecision? {
        val distance = reading.distanceMeters ?: return null
        if (reading.zone.priority < ThreatZone.CHETAAVNI.priority) {
            lastAlert = null
            return null
        }

        val previous = lastAlert
        val escalated = previous == null || reading.zone.priority > previous.zone.priority
        val approaching = previous != null && distance <= previous.distanceMeters - meaningfulApproachMeters
        val cooldownExpired = previous == null || nowMs - previous.emittedAtMs >= cooldownMs
        if (!escalated && !approaching && !cooldownExpired) return null

        lastAlert = AlertRecord(nowMs, reading.zone, distance)
        return AlertDecision(reading.zone, distance)
    }

    fun reset() {
        lastAnalysisAtMs = 0L
        lastReading = ObstacleReading()
        lastAlert = null
    }

    fun classify(distanceMeters: Float): ThreatZone = when {
        distanceMeters > 4f -> ThreatZone.SURAKSHIT
        distanceMeters >= 2.5f -> ThreatZone.CHETAAVNI
        distanceMeters >= 1f -> ThreatZone.SAVDHAAN
        else -> ThreatZone.TURANT_RUKE
    }

    private fun sampleCenterDepth(frame: Frame): Float? = try {
        frame.acquireDepthImage16Bits().use { image -> sampleClosestDepthInCenter(image) }
    } catch (_: NotYetAvailableException) {
        null
    }

    private fun sampleClosestDepthInCenter(image: Image): Float? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val left = (image.width * 0.38f).toInt()
        val right = (image.width * 0.62f).toInt()
        val top = (image.height * 0.38f).toInt()
        val bottom = (image.height * 0.62f).toInt()
        val step = maxOf(1, minOf(image.width, image.height) / 42)
        var closestMillimeters = Int.MAX_VALUE

        for (y in top until bottom step step) {
            for (x in left until right step step) {
                val offset = y * plane.rowStride + x * plane.pixelStride
                if (offset + 1 >= buffer.capacity()) continue
                val millimeters = buffer.getShort(offset).toInt() and DEPTH_VALUE_MASK
                if (millimeters in MIN_VALID_DEPTH_MM until DEPTH_VALUE_MASK) {
                    closestMillimeters = minOf(closestMillimeters, millimeters)
                }
            }
        }
        return closestMillimeters.takeIf { it != Int.MAX_VALUE }?.div(1_000f)
    }

    private fun sampleCenterPointCloud(frame: Frame): Float? = try {
        frame.acquirePointCloud().use { pointCloud -> sampleClosestCenterPoint(frame, pointCloud) }
    } catch (_: NotYetAvailableException) {
        null
    }

    private fun sampleClosestCenterPoint(frame: Frame, pointCloud: PointCloud): Float? {
        val points = pointCloud.points.duplicate()
        val inverseCameraPose = frame.camera.pose.inverse()
        var closest = Float.MAX_VALUE
        while (points.remaining() >= POINT_STRIDE) {
            val worldX = points.get()
            val worldY = points.get()
            val worldZ = points.get()
            val confidence = points.get()
            if (confidence < MIN_POINT_CONFIDENCE) continue
            val local = inverseCameraPose.transformPoint(floatArrayOf(worldX, worldY, worldZ))
            val forwardMeters = -local[2]
            if (forwardMeters <= MIN_POINT_DISTANCE_METERS) continue
            val horizontalRatio = abs(local[0] / forwardMeters)
            val verticalRatio = abs(local[1] / forwardMeters)
            if (horizontalRatio > CENTER_FIELD_RATIO || verticalRatio > CENTER_FIELD_RATIO) continue
            closest = minOf(closest, sqrt(local[0] * local[0] + local[1] * local[1] + local[2] * local[2]))
        }
        return closest.takeIf { it != Float.MAX_VALUE }
    }

    private companion object {
        const val DEPTH_VALUE_MASK = 0x1FFF
        const val MIN_VALID_DEPTH_MM = 80
        const val POINT_STRIDE = 4
        const val MIN_POINT_CONFIDENCE = 0.5f
        const val MIN_POINT_DISTANCE_METERS = 0.08f
        const val CENTER_FIELD_RATIO = 0.32f
    }
}
