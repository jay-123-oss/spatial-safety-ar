package com.manus.spatialsafety.ar.safety

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.manus.spatialsafety.ar.pipeline.SmolVlmResult
import com.manus.spatialsafety.ar.pipeline.toTtsText
import java.util.Locale

/** Owns spoken navigation output; spatial reasoning and every other field remain silent. */
class NavigationTtsController(context: Context) : AutoCloseable, TextToSpeech.OnInitListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        mainHandler.post {
            val languageStatus = tts.setLanguage(Locale.getDefault())
            ready = languageStatus != TextToSpeech.LANG_MISSING_DATA &&
                languageStatus != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    /** Speaks only the validated concise contextual description; safety warnings remain authoritative elsewhere. */
    fun speak(result: SmolVlmResult) {
        mainHandler.post {
            if (!ready) return@post
            tts.speak(result.toTtsText(), TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }
    }

    fun speakFallback() = speak(com.manus.spatialsafety.ar.pipeline.VisualNavigationPrompt.SAFE_FALLBACK)

    override fun close() {
        ready = false
        mainHandler.post {
            tts.stop()
            tts.shutdown()
        }
        mainHandler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val UTTERANCE_ID = "trinetra_action_command"
    }
}
