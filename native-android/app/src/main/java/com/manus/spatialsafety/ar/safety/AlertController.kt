package com.manus.spatialsafety.ar.safety

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Owns Android's local feedback resources and must be released from the activity lifecycle. */
class AlertController(context: Context) : TextToSpeech.OnInitListener, AutoCloseable {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ttsReady = false

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts.language = Locale("hi", "IN")
    }

    fun speakAlert(priorityLevel: Int, hazardName: String, distance: Float) {
        val metres = if (distance < 1.5f) "एक मीटर" else String.format(Locale.US, "%.1f मीटर", distance)
        val prefix = when (priorityLevel) {
            ThreatZone.TURANT_RUKE.priority -> "तुरंत रुकें"
            ThreatZone.SAVDHAAN.priority -> "सावधान"
            else -> "चेतावनी"
        }
        val text = "$prefix, $hazardName, $metres दूर।"
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hazard-${System.nanoTime()}")
        vibrate(priorityLevel)
    }

    fun cancelEmergencyFeedback() {
        vibrator?.cancel()
    }

    private fun vibrate(priorityLevel: Int) {
        if (vibrator?.hasVibrator() != true || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        when (priorityLevel) {
            ThreatZone.TURANT_RUKE.priority -> {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 140, 70, 140),
                        intArrayOf(0, 255, 0, 255),
                        1,
                    ),
                )
            }
            ThreatZone.SAVDHAAN.priority -> {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 110, 90, 110), -1))
            }
            else -> vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun close() {
        vibrator?.cancel()
        tts.stop()
        tts.shutdown()
    }
}
