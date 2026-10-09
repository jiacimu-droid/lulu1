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
import kotlin.math.sqrt

/** Captures real PCM. Meter is measured input, never a simulated listening animation. */
internal class CallAudioInput(private val scope: CoroutineScope) {
    private var job: Job? = null
    @Volatile private var recorder: AudioRecord? = null
    private var generation = 0L

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
                // Split uploads into continuous PCM chunks; a chunk boundary is NOT
                // a silence or a conversational turn boundary.
                val maxChunkBytes = 16000 * 2 * 12
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
                        active = false; silentFrames = 0; loudFrames = 0
                        buffer.reset(); preRoll.clear()
                        continue
                    }
                    if (!active) {
                        preRoll.addLast(frame)
                        if (preRoll.size > 4) preRoll.removeFirst()
                        loudFrames = if (rms >= threshold) loudFrames + 1 else 0
                        if (loudFrames < 2) continue
                        active = true
                        preRoll.forEach { buffer.write(it) }; preRoll.clear()
                        withContext(Dispatchers.Main) { if (epoch == generation) onSpeech() }
                    } else buffer.write(frame)
                    silentFrames = if (rms < threshold * 0.8) silentFrames + 1 else 0
                    val finishedBySilence = silentFrames >= (endSilenceMs.coerceIn(500, 3200) + 99) / 100
                    val chunkFull = buffer.size() >= maxChunkBytes
                    if (finishedBySilence || chunkFull) {
                        val segment = buffer.toByteArray()
                        buffer = ByteArrayOutputStream()
                        if (finishedBySilence) {
                            active = false
                            loudFrames = 0
                            silentFrames = 0
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

    fun stop() { generation++; job?.cancel(); job = null; runCatching { recorder?.stop() } }
}

internal fun pcmWav(pcm: ByteArray): ByteArray {
    val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
        .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(pcm.size).put(pcm)
    return b.array()
}
