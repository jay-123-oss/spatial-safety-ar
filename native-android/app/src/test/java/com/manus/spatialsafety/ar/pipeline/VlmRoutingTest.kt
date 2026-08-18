package com.manus.spatialsafety.ar.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmRoutingTest {
    @Test
    fun normalSceneKeepsVlmOff() {
        val router = VlmRouter()
        assertFalse(router.request(PerceptionContext(yoloConfidence = 0.9f), 1_000).enabled)
    }

    @Test
    fun lowConfidenceTurnsVlmOn() {
        val router = VlmRouter()
        val decision = router.request(PerceptionContext(yoloConfidence = 0.4f), 1_000)
        assertTrue(decision.enabled)
        assertEquals(VlmTriggerReason.LOW_CONFIDENCE, decision.reason)
    }

    @Test
    fun userQueryTurnsVlmOn() {
        val router = VlmRouter()
        val decision = router.request(PerceptionContext(userQuery = "What is in front of me?"), 1_000)
        assertEquals(VlmTriggerReason.USER_QUERY, decision.reason)
    }

    @Test
    fun safetyCriticalSituationDoesNotWaitForVlm() {
        val router = VlmRouter()
        assertFalse(router.request(PerceptionContext(safetyCritical = true, yoloConfidence = 0.1f), 1_000).enabled)
    }

    @Test
    fun duplicateRequestsAreBlockedUntilCompletionAndCooldown() {
        val router = VlmRouter(config = VlmTriggerConfig(cooldownMs = 1_000))
        val context = PerceptionContext(yoloConfidence = 0.2f)
        assertTrue(router.request(context, 1_000).enabled)
        assertFalse(router.request(context, 1_100).enabled)
        router.complete()
        assertFalse(router.request(context, 1_500).enabled)
        assertTrue(router.request(context, 2_100).enabled)
    }
}
