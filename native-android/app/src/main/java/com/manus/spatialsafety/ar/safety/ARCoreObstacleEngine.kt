package com.manus.spatialsafety.ar.safety

import android.media.Image
import android.os.SystemClock
import com.google.ar.core.Frame
import com.google.ar.core.PointCloud
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Offline ARCore-only obstacle engine.
 *
 * Depth is sampled from a center-weighted 9x9 grid, not a single pixel. Invalid samples are
 * rejected, the spatial result is a weighted median, and a short asymmetric temporal filter makes
 * the reading stable without making a closer obstacle slow to trigger. Alerts are gated until a
 * reading has enough spatial and temporal agreement.
 */
class ARCoreObstacleEngine(
    private val analysisIntervalMs: Long = 0L,
    private val cooldownMs: Long = 2_000L,
    private val meaningfulApproachMeters: Float = 0.35f,
    private val temporalFilter: TemporalDepthFilter = TemporalDepthFilter(),
) {
    private data class AlertRecord(
        val emittedAtMs: Long,
        val zone: ThreatZone,
        val distanceMeters: Float,
    )

    private data class DepthCandidate(
        val distanceMeters: Float,
        val confidence: Float,
        val sampleCount: Int,
    )

    private var lastAnalysisAtMs = 0L
    private var lastReading = ObstacleReading()
    private var lastAlert: AlertRecord? = null

    fun analyze(frame: Frame, nowMs: Long = SystemClock.elapsedRealtime()): ObstacleReading {
        if (nowMs - lastAnalysisAtMs < analysisIntervalMs) return lastReading
        lastAnalysisAtMs = nowMs

        val candidate = sampleCenterDepth(frame) ?: sampleCenterPointCloud(frame)
        if (candidate == null) {
            temporalFilter.reset()
            lastReading = ObstacleReading()
            return lastReading
        }

        val filtered = temporalFilter.add(candidate.distanceMeters)
        val zone = classify(filtered.distanceMeters)
        lastReading = ObstacleReading(
            distanceMeters = filtered.distanceMeters,
            zone = zone,
            source = if (sampleCenterDepthWasAvailable) DistanceSource.DEPTH_IMAGE else DistanceSource.POINT_CLOUD,
            confidence = candidate.confidence,
            sampleCount = candidate.sampleCount,
            isStable = filtered.isStable && candidate.confidence >= MIN_ACCEPTABLE_CONFIDENCE,
        )
        sampleCenterDepthWasAvailable = false
        return lastReading
    }

    @Synchronized
    fun nextAlert(reading: ObstacleReading, nowMs: Long = SystemClock.elapsedRealtime()): AlertDecision? {
        val distance = reading.distanceMeters ?: return null
        if (!reading.isStable || reading.zone == ThreatZone.UNKNOWN) return null
        if (reading.zone == ThreatZone.SURAKSHIT) {
            val hadHazard = lastAlert != null
            lastAlert = null
            return if (hadHazard) AlertDecision(reading.zone, distance) else null
        }

        val previous = lastAlert
        val zoneChanged = previous == null || reading.zone != previous.zone
        val approaching = previous != null && distance <= previous.distanceMeters - meaningfulApproachMeters
        val cooldownExpired = previous == null || nowMs - previous.emittedAtMs >= cooldownMs
        if (!zoneChanged && !approaching && !cooldownExpired) return null

        lastAlert = AlertRecord(nowMs, reading.zone, distance)
        return AlertDecision(reading.zone, distance)
    }

    fun reset() {
        lastAnalysisAtMs = 0L
        lastReading = ObstacleReading()
        lastAlert = null
        temporalFilter.reset()
        sampleCenterDepthWasAvailable = false
    }

    fun classify(distanceMeters: Float): ThreatZone = when {
        distanceMeters > 4f -> ThreatZone.SURAKSHIT
        distanceMeters >= 2.5f -> ThreatZone.CHETAAVNI
        distanceMeters >= 1f -> ThreatZone.SAVDHAAN
        else -> ThreatZone.TURANT_RUKE
    }

    private var sampleCenterDepthWasAvailable = false

    private fun sampleCenterDepth(frame: Frame): DepthCandidate? = try {
        frame.acquireDepthImage16Bits().use { image ->
            sampleDepthGrid(image)?.also { sampleCenterDepthWasAvailable = true }
        }
    } catch (_: NotYetAvailableException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun sampleDepthGrid(image: Image): DepthCandidate? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate().order(ByteOrder.nativeOrder())
        val values = ArrayList<Pair<Float, Float>>(GRID_SIZE * GRID_SIZE)
        val radiusX = image.width * CENTER_RADIUS_RATIO
        val radiusY = image.height * CENTER_RADIUS_RATIO
        var attempted = 0

        for (row in -GRID_RADIUS..GRID_RADIUS) {
            for (column in -GRID_RADIUS..GRID_RADIUS) {
                val x = (image.width * 0.5f + column * radiusX / GRID_RADIUS).roundToInt()
                val y = (image.height * 0.5f + row * radiusY / GRID_RADIUS).roundToInt()
                val safeX = x.coerceIn(0, image.width - 1)
                val safeY = y.coerceIn(0, image.height - 1)
                val offset = safeY * plane.rowStride + safeX * plane.pixelStride
                attempted++
                if (offset < 0 || offset + 1 >= buffer.capacity()) continue
                val millimeters = buffer.getShort(offset).toInt() and DEPTH_VALUE_MASK
                if (millimeters !in MIN_VALID_DEPTH_MM..MAX_VALID_DEPTH_MM) continue
                val normalizedX = column.toFloat() / GRID_RADIUS
                val normalizedY = row.toFloat() / GRID_RADIUS
                val radialDistance = sqrt(normalizedX * normalizedX + normalizedY * normalizedY)
                val weight = 1f / (1f + radialDistance)
                values += (millimeters / 1_000f) to weight
            }
        }

        val validFraction = values.size.toFloat() / attempted.coerceAtLeast(1)
        if (values.size < MIN_VALID_SAMPLES || validFraction < MIN_VALID_FRACTION) return null
        val weightedMedian = weightedMedian(values)
        return DepthCandidate(
            distanceMeters = weightedMedian,
            confidence = (validFraction * 1.35f).coerceIn(0f, 1f),
            sampleCount = values.size,
        )
    }

    private fun weightedMedian(values: List<Pair<Float, Float>>): Float {
        val sorted = values.sortedBy { it.first }
        val totalWeight = sorted.sumOf { it.second.toDouble() }.toFloat()
        var accumulated = 0f
        for ((distance, weight) in sorted) {
            accumulated += weight
            if (accumulated >= totalWeight * 0.5f) return distance
        }
        return sorted.last().first
    }

    private fun sampleCenterPointCloud(frame: Frame): DepthCandidate? = try {
        frame.acquirePointCloud().use { pointCloud -> sampleCenterPointCloud(frame, pointCloud) }
    } catch (_: NotYetAvailableException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun sampleCenterPointCloud(frame: Frame, pointCloud: PointCloud): DepthCandidate? {
        val points = pointCloud.points.duplicate()
        val inverseCameraPose = frame.camera.pose.inverse()
        val distances = ArrayList<Float>()
        while (points.remaining() >= POINT_STRIDE) {
            val worldX = points.get()
            val worldY = points.get()
            val worldZ = points.get()
            val confidence = points.get()
            if (confidence < MIN_POINT_CONFIDENCE) continue
            val local = inverseCameraPose.transformPoint(floatArrayOf(worldX, worldY, worldZ))
            val forwardMeters = -local[2]
            if (forwardMeters <= MIN_POINT_DISTANCE_METERS) continue
            if (abs(local[0] / forwardMeters) > CENTER_FIELD_RATIO || abs(local[1] / forwardMeters) > CENTER_FIELD_RATIO) continue
            distances += sqrt(local[0] * local[0] + local[1] * local[1] + local[2] * local[2])
        }
        if (distances.size < MIN_POINT_SAMPLES) return null
        distances.sort()
        val robustIndex = ((distances.size - 1) * POINT_ROBUST_QUANTILE).roundToInt()
        return DepthCandidate(
            distanceMeters = distances[robustIndex],
            confidence = (distances.size / POINT_CONFIDENCE_SCALE).coerceIn(0f, 1f),
            sampleCount = distances.size,
        )
    }

    private companion object {
        const val GRID_RADIUS = 4
        const val GRID_SIZE = GRID_RADIUS * 2 + 1
        const val CENTER_RADIUS_RATIO = 0.22f
        const val DEPTH_VALUE_MASK = 0x1FFF
        const val MIN_VALID_DEPTH_MM = 80
        const val MAX_VALID_DEPTH_MM = 8_000
        const val MIN_VALID_SAMPLES = 10
        const val MIN_VALID_FRACTION = 0.18f
        const val MIN_ACCEPTABLE_CONFIDENCE = 0.28f
        const val POINT_STRIDE = 4
        const val MIN_POINT_CONFIDENCE = 0.5f
        const val MIN_POINT_DISTANCE_METERS = 0.08f
        const val CENTER_FIELD_RATIO = 0.32f
        const val MIN_POINT_SAMPLES = 6
        const val POINT_ROBUST_QUANTILE = 0.25f
        const val POINT_CONFIDENCE_SCALE = 32f
    }
}

/** Robust asymmetric temporal filter: closer readings respond quickly; receding readings settle. */
class TemporalDepthFilter(
    private val windowSize: Int = 7,
    private val closeAlpha: Float = 0.65f,
    private val farAlpha: Float = 0.35f,
) {
    private val window = ArrayDeque<Float>()
    private var smoothedDistance: Float? = null

    fun add(distanceMeters: Float): FilteredDepth {
        val value = distanceMeters.coerceIn(0.08f, 8f)
        window.addLast(value)
        while (window.size > windowSize) window.removeFirst()

        val sorted = window.toList().sorted()
        val median = sorted[sorted.size / 2]
        val previous = smoothedDistance
        val alpha = when {
            previous == null -> 1f
            median < previous -> closeAlpha
            else -> farAlpha
        }
        smoothedDistance = previous?.let { it + (median - it) * alpha } ?: median
        val medianAbsoluteDeviation = sorted.map { abs(it - median) }.sorted()[sorted.size / 2]
        val spread = sorted.last() - sorted.first()
        val stable = window.size >= 3 &&
            spread <= max(0.18f, median * 0.14f) &&
            medianAbsoluteDeviation <= max(0.10f, median * 0.07f)
        return FilteredDepth(smoothedDistance!!, stable)
    }

    fun reset() {
        window.clear()
        smoothedDistance = null
    }
}

data class FilteredDepth(
    val distanceMeters: Float,
    val isStable: Boolean,
)
