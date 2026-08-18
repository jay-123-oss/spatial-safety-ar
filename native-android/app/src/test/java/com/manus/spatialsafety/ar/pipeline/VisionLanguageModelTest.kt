package com.manus.spatialsafety.ar.pipeline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionLanguageModelTest {
    @Test
    fun missingLocalArtifactDoesNotCrashAndReportsDisabled() = runBlocking {
        val engine = SmolVlmEngine(MissingLocalModelRuntime())
        assertFalse(engine.initialize())
        assertEquals(VlmState.DISABLED, engine.state)
        val result = engine.analyze(byteArrayOf(1), VlmInputContext(PerceptionContext()))
        assertEquals(VisualNavigationPrompt.SAFE_FALLBACK, result)
        engine.release()
        assertEquals(VlmState.IDLE, engine.state)
    }

    @Test
    fun injectedRuntimeLoadsOnceAndReleases() = runBlocking {
        val runtime = FakeRuntime()
        val engine = SmolVlmEngine(runtime)
        assertTrue(engine.initialize())
        assertTrue(engine.initialize())
        assertEquals(1, runtime.initializeCount)
        engine.release()
        assertEquals(1, runtime.releaseCount)
    }

    private class FakeRuntime : LocalVlmRuntime {
        var initializeCount = 0
        var releaseCount = 0
        override fun initialize(): Boolean { initializeCount++; return true }
        override suspend fun analyze(imageBytes: ByteArray, context: VlmInputContext) = VisualNavigationPrompt.SAFE_FALLBACK
        override fun release() { releaseCount++ }
    }
}
