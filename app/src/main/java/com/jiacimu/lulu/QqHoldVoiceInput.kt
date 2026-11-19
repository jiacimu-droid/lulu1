package com.jiacimu.lulu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardVoice
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/** One press captures original PCM; only verified STT is sent to the chat timeline. */
internal class QqHoldVoiceRecorder(private val context: Context, private val scope: CoroutineScope) {
    private val microphone = CallAudioInput(scope)
    private val lock = Any()
    private var raw = ByteArrayOutputStream()
    private var pending: ByteArray? = null
    private var transcript: String? = null
    private var activeAsr: ProviderCallInput? = null
    @Volatile private var generation = 0
    @Volatile private var accepting = false
    @Volatile private var full = false
    private var startedAt = 0L
    var onSend: (String, ByteArray) -> Boolean = { _, _ -> false }
    var recording by mutableStateOf(false)
        private set
    var processing by mutableStateOf(false)
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var elapsedMs by mutableLongStateOf(0L)
        private set
    var notice by mutableStateOf("")
        private set
    val retryable: Boolean get() = !processing && !recording && pending != null

    fun inform(value: String) { notice = value }

    fun begin(): Boolean {
        if (recording || processing) return false
        CallVoiceConfiguration.sttPreflight(context)?.let { notice = it; return false }
        pending = null
        transcript = null
        synchronized(lock) { raw = ByteArrayOutputStream() }
        val epoch = ++generation
        accepting = true
        full = false
        recording = true
        startedAt = SystemClock.elapsedRealtime()
        elapsedMs = 0
        level = 0f
        notice = ""
        microphone.start(
            accept = { generation == epoch && accepting },
            onReady = { if (generation == epoch && recording) notice = "正在录音 · 松开发送，上滑取消" },
            onLevel = { if (generation == epoch && recording) level = it.coerceIn(0f, 1f) },
            onSpeech = {},
            onFrame = { frame ->
                if (generation == epoch && accepting) synchronized(lock) {
                    if (raw.size() + frame.size <= MAX_BYTES) raw.write(frame)
                    else if (!full) {
                        full = true
                        scope.launch { if (generation == epoch && recording) finish(true) }
                    }
                }
            },
            onSegment = { _, _ -> },
            continuousFrames = true,
            onError = {
                if (generation == epoch) {
                    inform("录音失败：$it")
                    finish(false, keepNotice = true)
                }
            },
        )
        return true
    }

    fun tick() {
        if (recording) elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceIn(0, 60_000L)
    }

    fun finish(send: Boolean, keepNotice: Boolean = false) {
        if (!recording) return
        accepting = false
        generation++
        microphone.stop()
        recording = false
        level = 0f
        val audio = synchronized(lock) { raw.toByteArray().also { raw.reset() } }
        if (!send) {
            if (!keepNotice) notice = "已取消录音"
            pending = null
            return
        }
        if (audio.size < MIN_BYTES) {
            notice = "录音太短，请按住再说一次"
            return
        }
        pending = audio
        transcript = null
        processPending()
    }

    fun retry() { if (retryable) processPending() }

    private fun processPending() {
        val audio = pending ?: return
        if (processing) return
        val epoch = ++generation
        processing = true
        notice = "正在转写 · 完成后自动发送语音条"
        scope.launch {
            try {
                val words = transcript ?: withContext(Dispatchers.IO) {
                    val engine = CallVoiceConfiguration.sttEngine(context)
                    val chosen = CallVoiceConfiguration.elevenSttModel(
                        context.getSharedPreferences("lulu_advanced_settings", 0).getString("eleven_asr_model", null))
                    val model = when (chosen) {
                        CallVoiceConfiguration.SCRIBE_V3_LIVE -> CallVoiceConfiguration.SCRIBE_V3
                        CallVoiceConfiguration.SCRIBE_V2_REALTIME -> CallVoiceConfiguration.SCRIBE_V2
                        else -> chosen
                    }
                    val input = ProviderCallInput(
                        context = context, scope = scope, sttEngine = engine,
                        elevenPath = if (engine == "elevenlabs")
                            CallVoiceConfiguration.ElevenInputPath.SEGMENTED_UPLOAD
                        else CallVoiceConfiguration.ElevenInputPath.OTHER,
                        elevenModel = model, accept = { false }, onReady = {},
                        onLevel = {}, onSpeech = {}, onPartial = {},
                        onText = { _, _ -> }, onStatus = {}, onError = {},
                    )
                    activeAsr = input
                    try { input.transcribeRecordedPcm(audio) }
                    finally { input.stop(); if (activeAsr === input) activeAsr = null }
                }
                if (generation != epoch) return@launch
                if (words.isBlank()) {
                    notice = "没识别出文字，录音已保留，可点重试"
                    return@launch
                }
                transcript = words
                val sent = runCatching { onSend(words, audio) }.getOrDefault(false)
                if (generation != epoch) return@launch
                if (sent) {
                    pending = null
                    transcript = null
                    notice = ""
                } else notice = "发送未成功，录音保留，点重试"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (generation == epoch)
                    notice = "转写失败：${error.message.orEmpty().take(90)}。录音已保留，可重试"
            } finally {
                if (generation == epoch) processing = false
            }
        }
    }

    fun dispose() {
        generation++
        accepting = false
        microphone.stop()
        activeAsr?.stop()
        activeAsr = null
        recording = false
        processing = false
        pending = null
    }

    companion object {
        const val MAX_BYTES = 60 * 32_000
        const val MIN_BYTES = 12_800
    }
}

@Composable
internal fun rememberQqHoldVoiceRecorder(onSend: (String, ByteArray) -> Boolean): QqHoldVoiceRecorder {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val recorder = remember(context, scope) { QqHoldVoiceRecorder(context, scope) }
    SideEffect { recorder.onSend = onSend }
    DisposableEffect(recorder) { onDispose { recorder.dispose() } }
    LaunchedEffect(recorder.recording) {
        while (recorder.recording) {
            recorder.tick()
            delay(100)
        }
    }
    return recorder
}

@Composable
internal fun QqHoldToTalkButton(recorder: QqHoldVoiceRecorder, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cancelDistance = with(LocalDensity.current) { 68.dp.toPx() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        recorder.inform(if (it) "麦克风已授权，长按即可说话" else "需要麦克风权限才能录语音")
    }
    val active = recorder.recording
    val teal = Color(0xFF477E72)
    Surface(
        modifier = modifier.pointerInput(recorder, permission) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED) {
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                    waitForUpOrCancellation()
                    return@awaitEachGesture
                }
                if (!recorder.begin()) {
                    waitForUpOrCancellation()
                    return@awaitEachGesture
                }
                var cancelled = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        recorder.finish(false)
                        break
                    }
                    if (change.position.y < -cancelDistance) cancelled = true
                    if (!change.pressed) {
                        recorder.finish(!cancelled)
                        change.consume()
                        break
                    }
                    change.consume()
                }
            }
        },
        color = if (active) Color(0xFFE3F3EC) else Color(0xFFF2F5F0),
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, if (active) Color(0xFF81B6A6) else Color(0xFFDCE6DD)),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.KeyboardVoice, null, tint = teal, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(7.dp))
            Text(when {
                recorder.processing -> "正在转写…"
                active -> "松开发送 · 上滑取消"
                else -> "按住说话"
            }, color = Color(0xFF33594F), fontSize = 14.sp,
                fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
internal fun QqVoiceRecordingNotice(recorder: QqHoldVoiceRecorder) {
    if (recorder.notice.isBlank() && !recorder.recording) return
    val accent = Color(0xFF477E72)
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        color = Color(0xFFF1F6F0), shape = RoundedCornerShape(13.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (recorder.recording) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(7.dp).background(Color(0xFFD46B67), RoundedCornerShape(50)))
                Text("录音中 ${recorder.elapsedMs / 1_000}s / 60s", color = accent, fontSize = 11.sp)
                Spacer(Modifier.width(4.dp))
                repeat(12) { i ->
                    val factor = 0.35f + ((i * 7) % 8) / 12f
                    Box(Modifier.width(3.dp)
                        .height((6f + 19f * recorder.level.coerceIn(0f, 1f) * factor).dp)
                        .background(accent, RoundedCornerShape(3.dp)))
                }
            }
            if (recorder.notice.isNotBlank())
                Text(recorder.notice, color = Color(0xFF44695B), fontSize = 12.sp, lineHeight = 17.sp)
            if (recorder.retryable) TextButton(onClick = recorder::retry, contentPadding = PaddingValues(0.dp)) {
                Text("重试转写并发送", color = accent, fontSize = 12.sp)
            }
        }
    }
}
