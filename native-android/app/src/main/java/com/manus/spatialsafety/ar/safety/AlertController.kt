package com.manus.spatialsafety.ar.safety

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Owns all local alert resources. Zone transitions are already rate-limited by
 * [ARCoreObstacleEngine]; this class adds an independent TTS cooldown so a frame-rate change
 * cannot cause speech spam. No network audio or external sound asset is used.
 */
class AlertController(context: Context) : TextToSpeech.OnInitListener, AutoCloseable {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ttsReady = false
    private var lastSpokenAtMs = Long.MIN_VALUE
    private var lastSpokenZone = ThreatZone.UNKNOWN

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            val hindiResult = tts.setLanguage(Locale("hi", "IN"))
            if (hindiResult == TextToSpeech.LANG_MISSING_DATA || hindiResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
        }
    }

    /** Delivers one feedback event for a zone change, meaningful approach, or cooldown expiry. */
    fun playFeedback(decision: AlertDecision) {
        when (decision.zone) {
            ThreatZone.SURAKSHIT -> {
                stopEmergencyVibration()
                playTone(ToneGenerator.TONE_PROP_ACK, CLEAR_TONE_MS)
            }
            ThreatZone.CHETAAVNI -> {
                stopEmergencyVibration()
                playTone(ToneGenerator.TONE_PROP_BEEP, WARNING_TONE_MS)
            }
            ThreatZone.SAVDHAAN -> {
                stopEmergencyVibration()
                playTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, CAUTION_TONE_MS)
                speakIfAllowed(decision.zone, decision.distanceMeters)
            }
            ThreatZone.TURANT_RUKE -> {
                startContinuousEmergencyVibration()
                speakIfAllowed(decision.zone, decision.distanceMeters)
            }
            ThreatZone.UNKNOWN -> cancelAllFeedback()
        }
    }

    private fun playTone(type: Int, durationMs: Int) {
        toneGenerator.startTone(type, durationMs)
    }

    private fun startContinuousEmergencyVibration() {
        if (vibrator?.hasVibrator() != true || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Repeat from index 1: a short uninterrupted rapid-pulse emergency pattern until stopped.
        vibrator.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 95, 35, 95),
                intArrayOf(0, 255, 0, 255),
                1,
            ),
        )
    }

    private fun stopEmergencyVibration() {
        vibrator?.cancel()
    }

    private fun speakIfAllowed(zone: ThreatZone, distanceMeters: Float) {
        if (!ttsReady) return
        val now = SystemClock.elapsedRealtime()
        val zoneChanged = zone != lastSpokenZone
        if (!zoneChanged && now - lastSpokenAtMs < TTS_COOLDOWN_MS) return

        val distance = if (distanceMeters < 1.45f) "एक मीटर" else String.format(Locale.US, "%.1f मीटर", distanceMeters)
        val message = when (zone) {
            ThreatZone.SAVDHAAN -> "सावधान, बाधा $distance दूर।"
            ThreatZone.TURANT_RUKE -> "तुरंत रुकें। बाधा केवल $distance दूर है।"
            else -> return
        }
        lastSpokenAtMs = now
        lastSpokenZone = zone
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "arcore-${now}")
    }

    fun cancelAllFeedback() {
        stopEmergencyVibration()
        toneGenerator.stopTone()
        tts.stop()
        lastSpokenZone = ThreatZone.UNKNOWN
    }

    override fun close() {
        cancelAllFeedback()
        toneGenerator.release()
        tts.shutdown()
    }

    private companion object {
        const val TONE_VOLUME = 70
        const val CLEAR_TONE_MS = 90
        const val WARNING_TONE_MS = 70
        const val CAUTION_TONE_MS = 110
        const val TTS_COOLDOWN_MS = 4_000L
    }
}
