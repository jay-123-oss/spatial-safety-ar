package com.manus.spatialsafety.ar.safety

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactSafetyStateTest {
    @Test
    fun closeDepthObstacleAlwaysWinsWithStop() {
        val reading = ObstacleReading(
            distanceMeters = 0.7f,
            zone = ThreatZone.TURANT_RUKE,
            source = DistanceSource.POINT_CLOUD,
            confidence = 0.8f,
            sampleCount = 20,
            isStable = true,
        )
        val state = SafetyFusion.fuse(
            reading,
            detector = DetectorEvidence("person", 0.99f, HazardPosition.LEFT),
            vlm = VlmEvidence("person", 0.99f, PathStatus.CLEAR, "low"),
        )
        assertEquals(PathStatus.BLOCKED, state.pathStatus)
        assertEquals(SafetyAction.STOP, state.action)
        assertEquals(0.7f, state.distanceMeters)
    }

    @Test
    fun confidentDetectorAddsCategoryButDepthOwnsDistance() {
        val reading = ObstacleReading(
            distanceMeters = 3.0f,
            zone = ThreatZone.CHETAAVNI,
            source = DistanceSource.POINT_CLOUD,
            confidence = 0.7f,
            sampleCount = 15,
            isStable = true,
        )
        val state = SafetyFusion.fuse(reading, DetectorEvidence("bicycle", 0.9f, HazardPosition.RIGHT))
        assertEquals("bicycle", state.hazard)
        assertEquals(HazardPosition.RIGHT, state.position)
        assertEquals(3.0f, state.distanceMeters)
    }

    @Test
    fun uncertainDepthCanUseLowUncertaintyVlmPathContext() {
        val state = SafetyFusion.fuse(
            ObstacleReading(),
            vlm = VlmEvidence("pothole", 0.82f, PathStatus.BLOCKED, "low"),
        )
        assertEquals(PathStatus.BLOCKED, state.pathStatus)
        assertEquals("pothole", state.hazard)
        assertEquals(SafetyAction.STOP, state.action)
    }
}
