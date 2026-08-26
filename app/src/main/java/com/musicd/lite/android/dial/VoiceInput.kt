package com.musicd.lite.android.dial

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Push-to-talk: tap, speak, get a string.
 *
 * This is deliberately the app's own recogniser rather than the system
 * assistant. Nothing here depends on which assistant is installed, on the
 * media button session, or on anything Google is retiring — a tap starts
 * listening, Android turns speech into text, and the text goes to Roon's
 * browse API as a search. The one hard requirement is a recognition service
 * on the device, which Android provides.
 */
class VoiceInput(private val context: Context) {

    companion object {
        private const val TAG = "VoiceInput"
    }

    interface Listener {
        fun onListening()
        /** Words so far, while the user is still speaking. */
        fun onPartial(text: String)
        fun onHeard(text: String)
        fun onFailed(reason: String)
    }

    private var recognizer: SpeechRecognizer? = null
    var isListening: Boolean = false
        private set

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(listener: Listener) {
        if (isListening) return
        if (!isAvailable()) {
            listener.onFailed("No speech recognition on this device")
            return
        }

        val speech = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speech
        isListening = true

        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onListening()
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let { listener.onPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                stop()
                val heard = firstResult(results)
                if (heard.isNullOrBlank()) {
                    listener.onFailed("Didn't catch that")
                } else {
                    listener.onHeard(heard)
                }
            }

            override fun onError(error: Int) {
                stop()
                listener.onFailed(describe(error))
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        try {
            speech.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "could not start listening: ${e.message}")
            stop()
            listener.onFailed("Could not start listening")
        }
    }

    fun stop() {
        isListening = false
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "could not release the recogniser: ${e.message}")
        }
        recognizer = null
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone problem"
        SpeechRecognizer.ERROR_CLIENT -> "Speech recogniser stopped"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition needs a network"
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser busy"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Heard nothing"
        else -> "Speech recognition failed"
    }
}

/**
 * Turns what was said into something to search for.
 *
 * People say "play Iron Maiden", not "Iron Maiden" — Roon would search for the
 * word "play" along with the rest and rank the results worse for it.
 */
object SpokenQuery {

    private val LEADING = listOf(
        "play some music by ",
        "play some music from ",
        "play some ",
        "play me some ",
        "play me ",
        "play the album ",
        "play album ",
        "play music by ",
        "play track ",
        "play song ",
        "play ",
        "put on some ",
        "put on ",
        "listen to ",
        "search for ",
        "find "
    )

    fun clean(spoken: String): String {
        var text = spoken.trim()
        val lower = text.lowercase()
        for (prefix in LEADING) {
            if (lower.startsWith(prefix)) {
                text = text.substring(prefix.length)
                break
            }
        }
        return text.trim().trimEnd('.', '!', '?')
    }
}
