package com.manus.spatialsafety.ar.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `cooldown blocks duplicate alert but priority escalation overrides it`() {
        val warning = ObstacleReading(3f, ThreatZone.CHETAAVNI, DistanceSource.DEPTH_IMAGE)
        val emergency = ObstacleReading(0.8f, ThreatZone.TURANT_RUKE, DistanceSource.DEPTH_IMAGE)

        assertNotNull(engine.nextAlert(warning, nowMs = 10_000L))
        assertNull(engine.nextAlert(warning, nowMs = 10_500L))
        assertNotNull(engine.nextAlert(emergency, nowMs = 10_600L))
    }
}
