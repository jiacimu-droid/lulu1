package com.jiacimu.lulu

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.InputStream
import java.io.File
import java.io.FileOutputStream
import android.util.Log

/** Personal TTS credentials stay in the phone configuration, independent of the Agents service. */
internal class ElevenLabsSpeech(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
    @Volatile private var epoch = 0L
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var dialogueStream: ElevenLabsDialogueStream? = null

    fun stop() {
        epoch++
        connection?.disconnect(); connection = null
        dialogueStream?.close(); dialogueStream = null
        val old = track; track = null
        runCatching { old?.pause(); old?.flush(); old?.release() }
    }

    private fun request(text: String, voiceOverride: String?, format: String): HttpURLConnection {
        val key = prefs.getString("eleven_api_key", "").orEmpty().trim()
        val voice = voiceOverride?.takeIf(String::isNotBlank) ?: prefs.getString("eleven_voice_id", "").orEmpty().trim()
        require(key.isNotBlank() && voice.isNotBlank()) { "请填写 ElevenLabs API Key 和 Voice ID" }
        val model = selectedModel()
        val url = "https://api.elevenlabs.io${ElevenLabsModels.httpPath(model, voice)}?output_format=$format"
        val result = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 60_000; doOutput = true
            setRequestProperty("xi-api-key", key)
            setRequestProperty("Content-Type", "application/json")
        }
        connection = result
        val body = ElevenLabsModels.body(model, text, voice, prefs.getFloat("eleven_stability", .5f), prefs.getFloat("eleven_similarity", .75f))
        try {
            result.outputStream.use { it.write(body.toString().toByteArray()) }
            if (result.responseCode !in 200..299) {
                error("ElevenLabs HTTP ${result.responseCode}：请检查模型权限、声线和余额")
            }
            return result
        } catch (error: Throwable) {
            result.disconnect()
            if (connection === result) connection = null
            throw error
        }
    }

    private fun selectedModel() = prefs.getString("eleven_tts_model", ElevenLabsModels.DEFAULT).orEmpty().trim().ifBlank { ElevenLabsModels.DEFAULT }

    private fun createCallTrack(rate: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(rate, AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
        .setTransferMode(AudioTrack.MODE_STREAM).build()

    private class AudioSource(val input: InputStream, val cleanup: () -> Unit) {
        fun close() { runCatching { input.close() }; cleanup() }
    }
    private fun openAudio(text: String, voiceOverride: String?, format: String, source: String): AudioSource {
        VoiceUsageAudit.record(appContext, "ElevenLabs", source, text.length, selectedModel())
        if (ElevenLabsModels.websocket(selectedModel())) {
            val key = prefs.getString("eleven_api_key", "").orEmpty().trim()
            val voice = voiceOverride?.takeIf(String::isNotBlank) ?: prefs.getString("eleven_voice_id", "").orEmpty().trim()
            require(key.isNotBlank() && voice.isNotBlank()) { "请填写 ElevenLabs API Key 和 Voice ID" }
            val stream = ElevenLabsDialogueStream(key, selectedModel(), voice, text, format)
            dialogueStream = stream
            return AudioSource(stream) { stream.close(); if (dialogueStream === stream) dialogueStream = null }
        }
        val call = request(text, voiceOverride, format)
        return try { AudioSource(call.inputStream) { call.disconnect(); if (connection === call) connection = null } }
        catch (error: Throwable) { call.disconnect(); if (connection === call) connection = null; throw error }
    }

    suspend fun synthesize(text: String, voiceId: String?, source: String = "unknown"): ByteArray = withContext(Dispatchers.IO) {
        val token = epoch
        val call = openAudio(text, voiceId, "mp3_44100_128", source)
        try {
            val bytes = call.input.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (token == epoch) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 20 * 1024 * 1024) { "语音超过单段大小限制" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(token == epoch) { "试听或合成已取消" }
            check(bytes.isNotEmpty() && bytes.size <= 20 * 1024 * 1024) { "语音为空或超过单段大小限制" }
            bytes
        } finally { call.close() }
    }

    suspend fun speak(text: String, voiceId: String?, onStarted: () -> Unit, recordingTarget: File? = null, source: String = "phone_direct",
        onPcmFrame: (ByteArray, Int, Int) -> Unit = { _, _, _ -> },
        onPosition: (Long) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        val token = epoch
        // A long bedtime turn remains one visible message and one recording,
        // but each WebSocket synthesis segment has its own bounded PCM stream.
        val speechParts = if (ElevenLabsModels.websocket(selectedModel()))
            ElevenLabsSpeechSegments.split(text) else listOf(text)
        var audio: AudioTrack? = null
        var playbackClock: CallAudioPlaybackClock? = null
        val recording = recordingTarget?.let { runCatching { CallSpeechRecording(it) }.getOrNull() }
        var playedToEnd = false
        // Keep a temporary copy of the already PAID PCM, so a silent Android
        // output can be restarted without asking ElevenLabs to synthesize it again.
        // Cache failure must not break otherwise healthy streaming playback.
        val replayFile = runCatching { File.createTempFile("eleven-call-", ".pcm", appContext.cacheDir) }.getOrNull()
        var replayOutput = replayFile?.let { runCatching { FileOutputStream(it) }.getOrNull() }
        var replayAvailable = replayOutput != null
        try {
            val rate = 24_000
            var output = createCallTrack(rate)
            audio = output; track = output
            playbackClock = CallAudioPlaybackClock(output, rate, { token == epoch }, onStarted, onPosition)
            CallAudioRoute.preferredOutput?.let { check(output.setPreferredDevice(it)) { "系统未接受电话声音输出设备" } }
            output.play()
            var written = 0L
            var carry = 0
            var silentPrimaryStalled = false
            var firstWriteAttemptAt = 0L
            var lastHeadAt = 0L
            var primaryHead = 0L
            val buffer = ByteArray(8192)
            for (part in speechParts) {
                if (token != epoch) break
                val call = openAudio(part, voiceId, "pcm_24000", source)
                try {
                    call.input.use { input ->
                        while (token == epoch) {
                            val count = input.read(buffer, carry, buffer.size - carry)
                            if (count < 0) break
                            val total = count + carry
                            val even = total - total % 2
                            // Save received PCM once, regardless of playback status;
                            // a stalled Android writer must not force paid TTS again.
                            recording?.write(buffer, 0, even)
                            if (replayAvailable) {
                                if (written + even > 20L * 1024 * 1024) {
                                    replayAvailable = false
                                    runCatching { replayOutput?.close() }
                                    replayOutput = null
                                } else {
                                    runCatching { replayOutput?.write(buffer, 0, even) }
                                        .onFailure {
                                            replayAvailable = false
                                            runCatching { replayOutput?.close() }
                                            replayOutput = null
                                        }
                                }
                            }
                            written += even
                            if (even > 0 && firstWriteAttemptAt == 0L)
                                firstWriteAttemptAt = android.os.SystemClock.elapsedRealtime()
                            var offset = 0
                            while (offset < even && token == epoch && !silentPrimaryStalled) {
                                CallAudioRoute.preferredOutput?.let {
                                    if (output.preferredDevice?.id != it.id)
                                        check(output.setPreferredDevice(it)) { "系统未接受电话声音输出设备" }
                                }
                                // Never let WRITE_BLOCKING hang forever when the
                                // selected Android communication device is silent.
                                val sent = output.write(buffer, offset, even - offset, AudioTrack.WRITE_NON_BLOCKING)
                                check(sent >= 0) { "ElevenLabs AudioTrack 写入失败：$sent" }
                                playbackClock?.sample()
                                val now = android.os.SystemClock.elapsedRealtime()
                                val head = output.playbackHeadPosition.toLong() and 0xffffffffL
                                if (head > primaryHead) { primaryHead = head; lastHeadAt = now }
                                if (sent == 0) {
                                    if (head == 0L && now - firstWriteAttemptAt >=
                                        CallAudioRecoveryPolicy.STARTUP_TIMEOUT_MS) {
                                        silentPrimaryStalled = true
                                        Log.w("ElevenLabsSpeech", "Silent output: caching remaining already-generated PCM for local replay")
                                        break
                                    }
                                    check(head == 0L || now - lastHeadAt <
                                        CallAudioRecoveryPolicy.DRAIN_STALL_TIMEOUT_MS) {
                                        "手机音频输出停滞，已停止向播放设备写入"
                                    }
                                    delay(20)
                                } else {
                                    runCatching { onPcmFrame(buffer, offset, sent) }
                                    offset += sent
                                }
                            }
                            carry = total % 2
                            if (carry == 1) buffer[0] = buffer[even]
                        }
                    }
                } finally { call.close() }
            }
            if (token != epoch) return@withContext false
            check(written > 0 && carry == 0) { "ElevenLabs PCM 音频不完整" }
            // The primary device can accept all PCM writes while never moving
            // its playback head (e.g. during communication-route initialization).
            // Detect that separately from a slow but genuinely audible speech.
            runCatching { replayOutput?.flush(); replayOutput?.close() }
                .onFailure { replayAvailable = false }
            replayOutput = null
            val firstAudioDeadline = android.os.SystemClock.elapsedRealtime() +
                CallAudioRecoveryPolicy.STARTUP_TIMEOUT_MS
            while (token == epoch && (output.playbackHeadPosition.toLong() and 0xffffffffL) == 0L &&
                android.os.SystemClock.elapsedRealtime() < firstAudioDeadline) {
                delay(25)
            }
            if (token != epoch) return@withContext false
            val playedOnPrimary = output.playbackHeadPosition.toLong() and 0xffffffffL
            // After a silent stall, the primary may have received only part of the PCM.
            if (silentPrimaryStalled && playedOnPrimary > 0L)
                error("手机音频输出初始化过慢，原播放器只接收了部分语音")
            if (CallAudioRecoveryPolicy.canReplay(
                    playedOnPrimary, written, replayFile?.length() ?: 0L, replayAvailable)) {
                Log.w("ElevenLabsSpeech", "PCM received but AudioTrack did not start; retrying local playback without TTS request")
                playbackClock?.close()
                runCatching { output.pause(); output.flush(); output.release() }
                // Do not pin the second player to a possibly stale preferred
                // device; Android's current communication route decides instead.
                output = createCallTrack(rate)
                audio = output; track = output
                playbackClock = CallAudioPlaybackClock(output, rate, { token == epoch }, onStarted, onPosition)
                check(output.state == AudioTrack.STATE_INITIALIZED) { "手机音频输出未初始化" }
                output.play()
                val replayBuffer = ByteArray(8192)
                var replayed = 0L
                var lastProgress = android.os.SystemClock.elapsedRealtime()
                var lastHead = 0L
                replayFile!!.inputStream().use { input ->
                    while (token == epoch) {
                        val count = input.read(replayBuffer)
                        if (count < 0) break
                        var offset = 0
                        while (offset < count && token == epoch) {
                            val sent = output.write(replayBuffer, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
                            check(sent >= 0) { "本地音频重播失败：AudioTrack 写入错误 $sent" }
                            offset += sent
                            replayed += sent
                            playbackClock?.sample()
                            val head = output.playbackHeadPosition.toLong() and 0xffffffffL
                            if (head > lastHead) {
                                lastHead = head
                                lastProgress = android.os.SystemClock.elapsedRealtime()
                            }
                            check(android.os.SystemClock.elapsedRealtime() - lastProgress <
                                CallAudioRecoveryPolicy.STARTUP_TIMEOUT_MS) {
                                "手机音频输出未启动：本地重播也没有播放进度"
                            }
                            if (sent == 0) delay(20)
                        }
                        if (token != epoch) break
                    }
                }
                if (token != epoch) return@withContext false
                check(replayed == written) { "本地音频重播不完整" }
            } else if (playedOnPrimary == 0L) {
                error("手机音频输出未启动，已收到语音但没有播放进度（请检查通话输出设备）")
            }
            val drainDeadline = android.os.SystemClock.elapsedRealtime() +
                written * 1000 / (rate * 2) + 5_000
            var lastHead = output.playbackHeadPosition.toLong() and 0xffffffffL
            var lastAdvanced = android.os.SystemClock.elapsedRealtime()
            while (token == epoch && lastHead < written / 2) {
                val now = android.os.SystemClock.elapsedRealtime()
                val head = output.playbackHeadPosition.toLong() and 0xffffffffL
                if (head > lastHead) { lastHead = head; lastAdvanced = now }
                check(now < drainDeadline && now - lastAdvanced <
                    CallAudioRecoveryPolicy.DRAIN_STALL_TIMEOUT_MS) {
                    "手机音频播放停滞（已生成语音，播放头=$head，目标=${written / 2}）"
                }
                playbackClock?.sample()
                delay(20)
            }
            playedToEnd = token == epoch
            playbackClock?.sample()
            playedToEnd
        } finally {
            playbackClock?.close()
            runCatching { replayOutput?.close() }
            replayFile?.delete()
            // The recording contains exactly what was sent to AudioTrack, not a
            // second TTS generation. Interrupted or failed speech is discarded.
            recording?.finish(playedToEnd)
            if (track === audio) { track = null; runCatching { audio?.release() } }
        }
    }
}
