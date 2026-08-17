package com.manus.spatialsafety.ar.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ARCoreObstacleEngineTest {
    private val engine = ARCoreObstacleEngine(cooldownMs = 2_000L, meaningfulApproachMeters = 0.35f)

    @Test
    fun `classify implements all four requested zones`() {
        assertEquals(ThreatZone.SURAKSHIT, engine.classify(4.01f))
        assertEquals(ThreatZone.CHETAAVNI, engine.classify(4f))
        assertEquals(ThreatZone.CHETAAVNI, engine.classify(2.5f))
        assertEquals(ThreatZone.SAVDHAAN, engine.classify(2.49f))
        assertEquals(ThreatZone.SAVDHAAN, engine.classify(1f))
        assertEquals(ThreatZone.TURANT_RUKE, engine.classify(0.99f))
    }

    @Test
    fun `unstable readings do not emit an obstacle alert`() {
        val unstable = ObstacleReading(0.6f, ThreatZone.TURANT_RUKE, DistanceSource.DEPTH_IMAGE, 0.7f, 81, false)
        assertNull(engine.nextAlert(unstable, nowMs = 10_000L))
    }

    @Test
    fun `stable cooldown blocks duplicate alert but escalation overrides it`() {
        val warning = stableReading(3f, ThreatZone.CHETAAVNI)
        val emergency = stableReading(0.8f, ThreatZone.TURANT_RUKE)

        assertNotNull(engine.nextAlert(warning, nowMs = 10_000L))
        assertNull(engine.nextAlert(warning, nowMs = 10_500L))
        assertNotNull(engine.nextAlert(emergency, nowMs = 10_600L))
    }

    @Test
    fun `safe transition emits one clear event after a hazard`() {
        val warning = stableReading(3f, ThreatZone.CHETAAVNI)
        val safe = stableReading(4.5f, ThreatZone.SURAKSHIT)

        assertNotNull(engine.nextAlert(warning, nowMs = 10_000L))
        assertNotNull(engine.nextAlert(safe, nowMs = 10_100L))
        assertNull(engine.nextAlert(safe, nowMs = 10_200L))
    }

    @Test
    fun `deescalation emits immediate feedback to stop an emergency pattern`() {
        val emergency = stableReading(0.8f, ThreatZone.TURANT_RUKE)
        val caution = stableReading(1.5f, ThreatZone.SAVDHAAN)

        assertNotNull(engine.nextAlert(emergency, nowMs = 10_000L))
        assertNotNull(engine.nextAlert(caution, nowMs = 10_100L))
    }

    @Test
    fun `temporal filter responds faster when approaching and settles receding noise`() {
        val filter = TemporalDepthFilter(windowSize = 5)
        val first = filter.add(3f)
        filter.add(3f)
        val stable = filter.add(3f)
        assertFalse(first.isStable)
        assertTrue(stable.isStable)

        filter.add(1f)
        filter.add(1f)
        val closer = filter.add(1f)
        assertTrue(closer.distanceMeters < 2.2f)
        val receding = filter.add(3.8f)
        assertTrue(receding.distanceMeters < 3.8f)
    }

    @Test
    fun `temporal filter rejects a single noisy spike through the median`() {
        val filter = TemporalDepthFilter(windowSize = 5)
        listOf(2.4f, 2.4f, 2.4f, 2.4f).forEach(filter::add)
        val noisy = filter.add(0.2f)
        assertTrue(noisy.distanceMeters > 1.5f)
    }

    private fun stableReading(distance: Float, zone: ThreatZone) = ObstacleReading(
        distanceMeters = distance,
        zone = zone,
        source = DistanceSource.DEPTH_IMAGE,
        confidence = 0.9f,
        sampleCount = 81,
        isStable = true,
    )
}
