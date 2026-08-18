package com.jiacimu.lulu

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Small hands-free SpeechRecognizer loop for phone-call surfaces.
 *
 * Android's recognizer naturally finishes after a phrase. A real call should not require the user
 * to tap the microphone again, so this class automatically opens a new recognition window after a
 * result/error. Callers can temporarily block it while remote speech is playing to avoid feeding
 * the phone speaker back into speech recognition.
 */
internal class LuluContinuousSpeechRecognizer(
    context: Context,
    private val scope: CoroutineScope,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onPartialText: (String) -> Unit = {},
    private val onSpeech: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var restartJob: Job? = null
    private var enabled = false
    private var blocked = false
    private var recognitionActive = false
    private var destroyed = false

    fun setEnabled(value: Boolean) {
        if (destroyed) return
        enabled = value
        if (value) scheduleRestart(100) else cancelRecognition()
    }

    fun setBlocked(value: Boolean) {
        if (destroyed || blocked == value) return
        blocked = value
        if (value) cancelRecognition() else if (enabled) scheduleRestart(160)
    }

    fun retrySoon() {
        if (enabled && !blocked && !destroyed) scheduleRestart(120)
    }

    fun destroy() {
        destroyed = true
        enabled = false
        blocked = true
        restartJob?.cancel()
        restartJob = null
        recognitionActive = false
        onListeningChanged(false)
        onPartialText("")
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (destroyed) return null
        recognizer?.let { return it }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return null
        return SpeechRecognizer.createSpeechRecognizer(appContext).also { speechRecognizer ->
            recognizer = speechRecognizer
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    recognitionActive = true
                    onListeningChanged(true)
                    onPartialText("")
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    onListeningChanged(false)
                }

                override fun onError(error: Int) {
                    recognitionActive = false
                    onListeningChanged(false)
                    onPartialText("")
                    if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 620 else 360)
                    }
                }

                override fun onResults(results: Bundle?) {
                    recognitionActive = false
                    onListeningChanged(false)
                    onPartialText("")
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (spoken.isBlank()) {
                        scheduleRestart(180)
                    } else {
                        onSpeech(spoken)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    onPartialText(partial)
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun scheduleRestart(delayMillis: Long) {
        restartJob?.cancel()
        if (!canListen()) return
        restartJob = scope.launch {
            delay(delayMillis)
            startListeningNow()
        }
    }

    private fun startListeningNow() {
        if (!canListen() || recognitionActive) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onListeningChanged(false)
            return
        }
        val speechRecognizer = ensureRecognizer() ?: return
        runCatching {
            speechRecognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
            })
            recognitionActive = true
        }.onFailure {
            recognitionActive = false
            scheduleRestart(600)
        }
    }

    private fun cancelRecognition() {
        restartJob?.cancel()
        restartJob = null
        recognitionActive = false
        onListeningChanged(false)
        onPartialText("")
        runCatching { recognizer?.cancel() }
    }

    private fun canListen(): Boolean = enabled && !blocked && !destroyed
}
