package com.manus.spatialsafety.ar.safety

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Local, tone-only feedback controller.
 *
 * There is deliberately no spoken output or speech dependency. The engine emits only
 * meaningful state transitions; this class owns short notification tones and the emergency
 * vibration lifecycle.
 */
class AlertController(context: Context) : AutoCloseable {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
    private var emergencyVibrationActive = false
    private var lastToneAtMs = 0L
    private var lastToneZone = ThreatZone.UNKNOWN

    /** Plays a single local tone or starts/stops the emergency pattern for one engine event. */
    fun playFeedback(decision: AlertDecision) {
        when (decision.zone) {
            ThreatZone.SURAKSHIT -> {
                stopEmergencyVibration()
                if (lastToneZone != ThreatZone.SURAKSHIT) {
                    playRateLimitedTone(ToneGenerator.TONE_PROP_ACK, CLEAR_TONE_MS, decision.zone)
                }
            }
            ThreatZone.CHETAAVNI -> {
                stopEmergencyVibration()
                playRateLimitedTone(ToneGenerator.TONE_PROP_BEEP, WARNING_TONE_MS, decision.zone)
            }
            ThreatZone.SAVDHAAN -> {
                stopEmergencyVibration()
                playRateLimitedTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, CAUTION_TONE_MS, decision.zone)
            }
            ThreatZone.TURANT_RUKE -> startContinuousEmergencyVibration()
            ThreatZone.UNKNOWN -> cancelAllFeedback()
        }
    }

    private fun playRateLimitedTone(type: Int, durationMs: Int, zone: ThreatZone) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (zone == lastToneZone && now - lastToneAtMs < TONE_COOLDOWN_MS) return
        toneGenerator.startTone(type, durationMs)
        lastToneAtMs = now
        lastToneZone = zone
    }

    private fun startContinuousEmergencyVibration() {
        if (emergencyVibrationActive) return
        if (vibrator?.hasVibrator() != true || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        vibrator.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0L, 100L, 30L, 100L),
                intArrayOf(0, 255, 0, 255),
                1,
            ),
        )
        emergencyVibrationActive = true
    }

    private fun stopEmergencyVibration() {
        if (!emergencyVibrationActive) return
        vibrator?.cancel()
        emergencyVibrationActive = false
    }

    /** Must be called on unknown/tracking-loss, safe transition, pause and activity close. */
    fun cancelAllFeedback() {
        stopEmergencyVibration()
        toneGenerator.stopTone()
        lastToneZone = ThreatZone.UNKNOWN
        lastToneAtMs = 0L
    }

    override fun close() {
        cancelAllFeedback()
        toneGenerator.release()
    }

    private companion object {
        const val TONE_VOLUME = 80
        const val CLEAR_TONE_MS = 110
        const val WARNING_TONE_MS = 75
        const val CAUTION_TONE_MS = 120
        const val TONE_COOLDOWN_MS = 1_000L
    }
}
