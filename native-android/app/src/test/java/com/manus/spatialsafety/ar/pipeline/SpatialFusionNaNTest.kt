package com.manus.spatialsafety.ar.pipeline

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpatialFusionNaNTest {
    @Test
    fun `non finite vector has no distance`() {
        assertNull(Vec3(Float.NaN, 0f, 1f).norm())
        assertNull(Vec3(0f, Float.POSITIVE_INFINITY, 1f).norm())
        assertNull(Vec3(1f, 2f, 3f).div(0f).norm())
    }

    @Test
    fun `non finite depth is rejected and does not create fused object`() {
        val fusion = SpatialFusion()
        val track = Track(1, "person", 0.9f, RectF(10f, 10f, 30f, 30f), 1L)
        val frame = ArFrame(
            timestampNs = 1L,
            cameraImageWidth = 640,
            cameraImageHeight = 480,
            depth = object : DepthSampler {
                override fun sampleMeters(xPx: Float, yPx: Float): Float = Float.NaN
            },
            intrinsics = CameraIntrinsics(500f, 500f, 320f, 240f),
        )
        assertEquals(emptyList<FusedObject>(), fusion.fuse(listOf(track), frame))
    }

    @Test
    fun `invalid intrinsics are rejected before depth sampling`() {
        val fusion = SpatialFusion()
        val track = Track(1, "person", 0.9f, RectF(10f, 10f, 30f, 30f), 1L)
        val frame = ArFrame(
            timestampNs = 1L,
            cameraImageWidth = 640,
            cameraImageHeight = 480,
            depth = object : DepthSampler {
                override fun sampleMeters(xPx: Float, yPx: Float): Float = error("depth must not be sampled")
            },
            intrinsics = CameraIntrinsics(Float.NaN, 500f, 320f, 240f),
        )
        assertEquals(emptyList<FusedObject>(), fusion.fuse(listOf(track), frame))
    }
}
