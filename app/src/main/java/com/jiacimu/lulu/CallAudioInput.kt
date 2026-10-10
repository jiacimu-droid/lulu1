package com.jiacimu.lulu

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Captures real PCM. Meter is measured input, never a simulated listening animation. */
internal class CallAudioInput(private val scope: CoroutineScope) {
    private var job: Job? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var generation = 0L

    @SuppressLint("MissingPermission")
    fun start(accept: () -> Boolean, onReady: () -> Unit, onLevel: (Float) -> Unit,
        onSpeech: () -> Unit, onFrame: (ByteArray) -> Unit = {}, onSegment: (ByteArray, Boolean) -> Unit,
        onError: (String) -> Unit, threshold: Float = 350f, endSilenceMs: Int = 500) {
        stop()
        val epoch = generation
        job = scope.launch(Dispatchers.IO) {
            var audio: AudioRecord? = null
            var echo: AcousticEchoCanceler? = null
            var noise: NoiseSuppressor? = null
            try {
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "手机不支持 16kHz 单声道录音" }
                audio = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败，可能被其他应用占用" }
                recorder = audio
                val activeAudio = checkNotNull(audio)
                // The call output may switch from speaker to headset mid-call.
                // Route microphone capture independently; choosing an earpiece
                // must never strand capture on an unavailable Bluetooth input.
                var boundInputId: Int? = null
                fun bindInput() {
                    val preferred = CallAudioRoute.preferredInput
                    if (preferred?.id != boundInputId) {
                        val selected = activeAudio.setPreferredDevice(preferred)
                        if (selected) boundInputId = preferred?.id
                    }
                }
                bindInput()
                if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(audio.audioSessionId)?.apply { enabled = true }
                if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(audio.audioSessionId)?.apply { enabled = true }
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "系统未允许录音" }
                withContext(Dispatchers.Main) { if (epoch == generation) onReady() }
                val samples = ShortArray(1600)
                var buffer = ByteArrayOutputStream()
                val preRoll = ArrayDeque<ByteArray>()
                var active = false
                var silentFrames = 0
                var loudFrames = 0
                var utteranceFrames = 0
                // The mic's real noise floor can be higher than the user-configured
                // threshold (headset hiss, fan, Android VOICE_COMMUNICATION AGC).
                // Do not treat that steady level as somebody speaking forever.
                var ambientRms = threshold.toDouble() * 0.40
                var peakRms = 0.0
                var rollingRms = 0.0
                var steadyFrames = 0
                // Split uploads into continuous PCM chunks; a chunk boundary is NOT
                // a silence or a conversational turn boundary.
                while (isActive && epoch == generation) {
                    bindInput()
                    val count = audio.read(samples, 0, samples.size)
                    check(count > 0) { "麦克风读取失败（$count）" }
                    val rms = sqrt((0 until count).sumOf { samples[it].toDouble() * samples[it] } / count)
                    val allowed = accept()
                    withContext(Dispatchers.Main) { if (epoch == generation) onLevel(if (allowed) (rms / 4000).toFloat().coerceIn(0f, 1f) else 0f) }
                    val frame = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                        for (i in 0 until count) putShort(if (allowed) samples[i] else 0)
                    }.array()
                    onFrame(frame)
                    if (!allowed) {
                        // Caller is temporarily playing audio. Do not lose an
                        // already captured user's phrase without signalling its end.
                        if (active && buffer.size() > 0) {
                            val completed = buffer.toByteArray()
                            withContext(Dispatchers.Main) {
                                if (epoch == generation) onSegment(completed, true)
                            }
                        }
                        active = false; silentFrames = 0; loudFrames = 0; utteranceFrames = 0
                        peakRms = 0.0; rollingRms = 0.0; steadyFrames = 0
                        buffer.reset(); preRoll.clear()
                        continue
                    }
                    if (!active) {
                        preRoll.addLast(frame)
                        if (preRoll.size > 4) preRoll.removeFirst()
                        // While idle, learn the room's quiet background without
                        // letting an abrupt real voice raise the start threshold.
                        val startGate = PhoneMicSegmentPolicy.startThreshold(threshold, ambientRms)
                        if (rms < startGate) ambientRms = ambientRms * 0.86 + rms * 0.14
                        loudFrames = if (rms >= startGate) loudFrames + 1 else 0
                        if (loudFrames < 2) continue
                        active = true
                        utteranceFrames = 0
                        peakRms = rms
                        rollingRms = rms
                        steadyFrames = 0
                        preRoll.forEach { buffer.write(it) }; preRoll.clear()
                        withContext(Dispatchers.Main) { if (epoch == generation) onSpeech() }
                    } else buffer.write(frame)
                    utteranceFrames++
                    peakRms = maxOf(rms, peakRms * 0.997)
                    // Comparing only against the static settings threshold makes
                    // elevated background noise look like unending speech.
                    val quietGate = PhoneMicSegmentPolicy.quietThreshold(threshold, ambientRms, peakRms)
                    silentFrames = if (rms < quietGate) silentFrames + 1 else 0
                    // A constant noise plateau (including gain-controlled mic
                    // noise) is also silence, even if its RMS sits above the
                    // fixed threshold. Natural voiced speech is variable.
                    val nearlyConstant = abs(rms - rollingRms) <= maxOf(24.0, rollingRms * 0.075)
                    steadyFrames = if (nearlyConstant) steadyFrames + 1 else 0
                    rollingRms = rollingRms * 0.72 + rms * 0.28
                    val finishedBySilence = PhoneMicSegmentPolicy.finishedBySilence(
                        silentFrames, utteranceFrames, endSilenceMs
                    ) || PhoneMicSegmentPolicy.stationaryNoiseEnded(steadyFrames, utteranceFrames)
                    val chunkFull = PhoneMicSegmentPolicy.uploadChunkFull(buffer.size())
                    if (finishedBySilence || chunkFull) {
                        val segment = buffer.toByteArray()
                        buffer = ByteArrayOutputStream()
                        if (finishedBySilence) {
                            active = false
                            loudFrames = 0
                            silentFrames = 0
                            utteranceFrames = 0
                            peakRms = 0.0
                            rollingRms = 0.0
                            steadyFrames = 0
                        }
                        // At max duration preserve VAD state; the next frame
                        // belongs to the same utterance, without a lost pre-roll.
                        withContext(Dispatchers.Main) {
                            if (epoch == generation) onSegment(segment, finishedBySilence)
                        }
                    }
                }
            } catch (error: Exception) {
                if (error !is CancellationException) withContext(Dispatchers.Main) {
                    if (epoch == generation) onError(error.message.orEmpty())
                }
            } finally {
                echo?.release(); noise?.release()
                runCatching { audio?.stop() }; audio?.release()
                if (recorder === audio) recorder = null
            }
        }
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        // Relinquish the privacy-sensitive AudioRecord immediately on mute.
        val old = recorder
        recorder = null
        runCatching { old?.stop() }
        runCatching { old?.release() }
    }
}

internal fun pcmWav(pcm: ByteArray): ByteArray {
    val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
        .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(pcm.size).put(pcm)
    return b.array()
}
