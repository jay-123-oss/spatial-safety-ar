package com.manus.spatialsafety.ar.safety

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Owns Android's fully local TTS and haptic resources; call [close] from the activity lifecycle. */
class AlertController(context: Context) : TextToSpeech.OnInitListener, AutoCloseable {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ttsReady = false

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            val hindiResult = tts.setLanguage(Locale("hi", "IN"))
            if (hindiResult == TextToSpeech.LANG_MISSING_DATA || hindiResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
        }
    }

    fun playFeedback(decision: AlertDecision, voiceEnabled: Boolean) {
        vibrate(decision.zone)
        if (!voiceEnabled || decision.zone.priority < ThreatZone.SAVDHAAN.priority) return
        speak(decision.zone, decision.distanceMeters)
    }

    private fun speak(zone: ThreatZone, distanceMeters: Float) {
        if (!ttsReady) return
        val distance = if (distanceMeters < 1.45f) "एक मीटर" else String.format(Locale.US, "%.1f मीटर", distanceMeters)
        val message = when (zone) {
            ThreatZone.SAVDHAAN -> "सावधान, बाधा $distance दूर।"
            ThreatZone.TURANT_RUKE -> "तुरंत रुकें। बाधा केवल $distance दूर है।"
            else -> "चेतावनी, बाधा $distance दूर।"
        }
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "arcore-${System.nanoTime()}")
    }

    private fun vibrate(zone: ThreatZone) {
        if (vibrator?.hasVibrator() != true || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        when (zone) {
            ThreatZone.CHETAAVNI -> vibrator.vibrate(VibrationEffect.createOneShot(70, 90))
            ThreatZone.SAVDHAAN -> vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), intArrayOf(0, 170, 0, 220), -1),
            )
            ThreatZone.TURANT_RUKE -> vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 110, 45, 110), intArrayOf(0, 255, 0, 255), 1),
            )
            else -> vibrator.cancel()
        }
    }

    fun cancelEmergencyFeedback() {
        vibrator?.cancel()
        tts.stop()
    }

    override fun close() {
        cancelEmergencyFeedback()
        tts.shutdown()
    }
}
