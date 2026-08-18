package com.manus.spatialsafety.ar.safety

import android.content.Context
import android.speech.tts.TextToSpeech
import com.manus.spatialsafety.ar.pipeline.SmolVlmResult
import java.util.Locale

/** Owns spoken navigation output; spatial reasoning and other fields are never spoken. */
class NavigationTtsController(context: Context) : AutoCloseable, TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
            ready = true
        }
    }

    fun speak(result: SmolVlmResult) {
        if (!ready) return
        tts.speak(result.actionCommand, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun speakFallback() = speak(com.manus.spatialsafety.ar.pipeline.VisualNavigationPrompt.SAFE_FALLBACK)

    override fun close() {
        ready = false
        tts.stop()
        tts.shutdown()
    }

    private companion object {
        const val UTTERANCE_ID = "trinetra_action_command"
    }
}
