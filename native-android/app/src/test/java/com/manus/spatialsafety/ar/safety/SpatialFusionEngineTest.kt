package com.manus.spatialsafety.ar.safety

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpatialFusionEngineTest {
    private val engine = SpatialFusionEngine(cooldownMs = 2_000L, meaningfulApproachMeters = 0.35f)

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
    fun `cooldown blocks duplicate but escalation overrides it`() {
        val detection = Detection("car", 0.9f, RectF(10f, 10f, 60f, 80f))
        val warning = FusedObstacle(detection, 3f, ThreatZone.CHETAAVNI, "car:1:1")
        val emergency = FusedObstacle(detection, 0.8f, ThreatZone.TURANT_RUKE, "car:1:1")

        assertNotNull(engine.nextAlert(listOf(warning), nowMs = 10_000L))
        assertNull(engine.nextAlert(listOf(warning), nowMs = 10_500L))
        assertNotNull(engine.nextAlert(listOf(emergency), nowMs = 10_600L))
    }
}
