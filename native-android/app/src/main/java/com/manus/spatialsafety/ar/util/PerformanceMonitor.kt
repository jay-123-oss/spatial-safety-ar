package com.manus.spatialsafety.ar.util

import android.content.Context
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import kotlin.math.roundToInt

data class PerformanceStats(
    val fps: Int = 0,
    val batteryPercent: Int? = null,
    val cpuPercent: Int? = null,
    val gpuText: String = "N/A",
)

/** Process-time CPU estimate plus rendered FPS. Android has no portable public GPU-load percentage. */
class PerformanceMonitor(private val context: Context) {
    private var frameCount = 0
    private var previousFpsTimeMs = SystemClock.elapsedRealtime()
    private var fps = 0
    private var previousCpuMs = Process.getElapsedCpuTime()
    private var previousWallMs = SystemClock.elapsedRealtime()
    private var cachedBattery: Int? = null
    private var lastBatteryReadMs = 0L
    private var cachedCpu: Int? = null

    fun onFrame(): PerformanceStats {
        frameCount += 1
        val now = SystemClock.elapsedRealtime()
        if (now - previousFpsTimeMs >= 1_000L) {
            fps = (frameCount * 1_000f / (now - previousFpsTimeMs)).roundToInt()
            frameCount = 0
            previousFpsTimeMs = now
        }

        if (cachedCpu == null || now - previousWallMs >= 500L) {
            val cpuNow = Process.getElapsedCpuTime()
            val wallNow = now
            val elapsedCpu = (cpuNow - previousCpuMs).coerceAtLeast(0L)
            val elapsedWall = (wallNow - previousWallMs).coerceAtLeast(1L)
            cachedCpu = ((elapsedCpu * 100f / elapsedWall) / Runtime.getRuntime().availableProcessors())
                .roundToInt()
                .coerceIn(0, 100)
            previousCpuMs = cpuNow
            previousWallMs = now
        }
        if (cachedBattery == null || now - lastBatteryReadMs >= 2_000L) {
            cachedBattery = context.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
            lastBatteryReadMs = now
        }
        return PerformanceStats(fps, cachedBattery, cachedCpu, "N/A")
    }
}
