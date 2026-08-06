package com.jiacimu.lulu

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.archiveIdFor
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.system.LuluDeviceToolBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

internal enum class CallPhase { Idle, Ready, Dialing, Connected, Ended }

internal data class LuluVoiceCallState(
    val conversationId: String = "",
    val characterId: String = "",
    val characterName: String = "",
    val phase: CallPhase = CallPhase.Idle,
    val listening: Boolean = false,
    val thinking: Boolean = false,
    val speaking: Boolean = false,
    val speakerEnabled: Boolean = true,
    val microphoneMuted: Boolean = false,
    val partialTranscript: String = "",
    val statusMessage: String = "",
    val elapsedSeconds: Long = 0L,
    val callStartMessageCount: Int = 0,
    val callStartedAt: Instant? = null,
    val callExperienceId: String = "",
    val everConnected: Boolean = false,
    val experienceSaved: Boolean = false,
) {
    val hasSession: Boolean get() = phase != CallPhase.Idle
    val connected: Boolean get() = phase == CallPhase.Connected
}

internal object LuluVoiceCallSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(LuluVoiceCallState())
    val state: StateFlow<LuluVoiceCallState> = mutableState.asStateFlow()

    private var appContext: Context? = null
    private var recognizer: SpeechRecognizer? = null
    private var speechEngine: LuluSpeechEngine? = null
    private var timerJob: Job? = null
    private var dialJob: Job? = null
    private var restartListeningJob: Job? = null
    private var recognitionActive = false
    private var audioManager: AudioManager? = null
    private var previousAudioMode: Int? = null
    private var previousSpeakerphone: Boolean? = null

    fun prepare(context: Context, conversationId: String, characterId: String, characterName: String) {
        initialize(context)
        val current = mutableState.value
        if (current.phase == CallPhase.Idle || current.phase == CallPhase.Ended) {
            mutableState.value = LuluVoiceCallState(
                conversationId = conversationId,
                characterId = characterId,
                characterName = characterName,
                phase = CallPhase.Ready,
                callStartMessageCount = MigratedDomainStores.chat.messages(conversationId).value.size,
                callExperienceId = UUID.randomUUID().toString(),
            )
        }
    }

    fun dial() {
        val current = mutableState.value
        if (current.phase != CallPhase.Ready) return
        if (!hasMicrophonePermission()) {
            mutableState.update { it.copy(statusMessage = "需要麦克风权限才能开始通话") }
            return
        }
        val library = LuluAiServices.connectionStore.library.value
        val archiveId = library.archiveIdFor(ModelUsage.VoiceCall)
        if (library.archives.none { it.id == archiveId }) {
            mutableState.update { it.copy(statusMessage = "请先选择电话模型") }
            return
        }
        configureCallAudio(true)
        startForegroundService()
        mutableState.update {
            it.copy(
                phase = CallPhase.Dialing,
                statusMessage = "正在呼叫…",
                microphoneMuted = false,
                speakerEnabled = true,
            )
        }
        dialJob?.cancel()
        dialJob = scope.launch {
            delay(1_650)
            if (mutableState.value.phase != CallPhase.Dialing) return@launch
            mutableState.update {
                it.copy(
                    phase = CallPhase.Connected,
                    callStartedAt = Instant.now(),
                    everConnected = true,
                    statusMessage = "已接通，直接说话就好",
                )
            }
            startTimer()
            scheduleListening(220)
        }
    }

    fun cancelDial() {
        if (mutableState.value.phase == CallPhase.Dialing) endCall()
    }

    fun toggleMicrophone() {
        val nextMuted = !mutableState.value.microphoneMuted
        mutableState.update {
            it.copy(
                microphoneMuted = nextMuted,
                statusMessage = if (nextMuted) "麦克风已静音" else "麦克风已打开，直接说话就好",
            )
        }
        if (nextMuted) {
            pauseRecognition()
        } else {
            scheduleListening(160)
        }
    }

    fun toggleSpeaker() {
        val enabled = !mutableState.value.speakerEnabled
        mutableState.update { it.copy(speakerEnabled = enabled) }
        audioManager?.isSpeakerphoneOn = enabled
        if (!enabled) {
            speechEngine?.stop()
            mutableState.update { it.copy(speaking = false) }
            scheduleListening(120)
        }
    }

    fun reportPermissionDenied() {
        mutableState.update { it.copy(statusMessage = "没有麦克风权限，暂时无法开始通话") }
    }

    fun endCall() {
        val current = mutableState.value
        if (current.phase == CallPhase.Idle || current.phase == CallPhase.Ended) return
        dialJob?.cancel()
        timerJob?.cancel()
        restartListeningJob?.cancel()
        pauseRecognition()
        speechEngine?.stop()
        saveCallExperience(current)
        mutableState.update {
            it.copy(
                phase = CallPhase.Ended,
                listening = false,
                thinking = false,
                speaking = false,
                partialTranscript = "",
                statusMessage = "通话已结束",
            )
        }
        restoreCallAudio()
        stopForegroundService()
        scope.launch {
            delay(850)
            if (mutableState.value.phase == CallPhase.Ended) mutableState.value = LuluVoiceCallState()
        }
    }

    private fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        speechEngine = LuluSpeechEngine(context.applicationContext)
        audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        ensureRecognizer()
    }

    private fun ensureRecognizer() {
        val context = appContext ?: return
        if (recognizer != null || !SpeechRecognizer.isRecognitionAvailable(context)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { speechRecognizer ->
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    recognitionActive = true
                    mutableState.update {
                        it.copy(
                            listening = true,
                            partialTranscript = "",
                            statusMessage = "正在听你说话",
                        )
                    }
                }

                override fun onBeginningOfSpeech() {
                    mutableState.update { it.copy(statusMessage = "听到了，你继续说") }
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    mutableState.update { it.copy(listening = false, statusMessage = "正在识别…") }
                }

                override fun onError(error: Int) {
                    recognitionActive = false
                    mutableState.update { current ->
                        current.copy(
                            listening = false,
                            partialTranscript = "",
                            statusMessage = when (error) {
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络暂时不可用"
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麦克风权限不可用"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "正在重新连接麦克风…"
                                else -> if (current.microphoneMuted) "麦克风已静音" else "我在听，直接说话就好"
                            },
                        )
                    }
                    if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) scheduleListening(420)
                }

                override fun onResults(results: Bundle?) {
                    recognitionActive = false
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    mutableState.update { it.copy(listening = false, partialTranscript = "") }
                    if (spoken.isBlank()) {
                        scheduleListening(220)
                    } else {
                        handleUserSpeech(spoken)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    mutableState.update { it.copy(partialTranscript = partial) }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun scheduleListening(delayMillis: Long) {
        restartListeningJob?.cancel()
        restartListeningJob = scope.launch {
            delay(delayMillis)
            startListeningIfPossible()
        }
    }

    private fun startListeningIfPossible() {
        val current = mutableState.value
        if (!current.connected || current.microphoneMuted || current.thinking || current.speaking || recognitionActive) return
        if (!hasMicrophonePermission()) {
            mutableState.update { it.copy(statusMessage = "需要麦克风权限才能继续通话") }
            return
        }
        ensureRecognizer()
        val speechRecognizer = recognizer
        if (speechRecognizer == null) {
            mutableState.update { it.copy(statusMessage = "当前手机没有可用的语音识别服务") }
            return
        }
        runCatching {
            speechRecognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 850L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
            })
            recognitionActive = true
        }.onFailure {
            recognitionActive = false
            mutableState.update { state -> state.copy(statusMessage = "麦克风正在重新连接…") }
            scheduleListening(600)
        }
    }

    private fun pauseRecognition() {
        restartListeningJob?.cancel()
        recognitionActive = false
        runCatching { recognizer?.cancel() }
        mutableState.update { it.copy(listening = false, partialTranscript = "") }
    }

    private fun handleUserSpeech(spoken: String) {
        val current = mutableState.value
        if (!current.connected || current.thinking) return
        pauseRecognition()
        val userMessage = MigratedDomainStores.chat.sendUserMessage(current.conversationId, spoken)
        SharedExperienceTimeline.recordChatMessage(
            current.characterId,
            current.conversationId,
            userMessage,
            channelOverride = "电话",
        )
        mutableState.update { it.copy(thinking = true, statusMessage = "${current.characterName} 正在想怎么回答") }
        scope.launch {
            val latest = mutableState.value
            val library = LuluAiServices.connectionStore.library.value
            val archiveId = library.archiveIdFor(ModelUsage.VoiceCall)
            val activeArchive = library.archives.firstOrNull { it.id == archiveId }
            if (activeArchive == null) {
                mutableState.update { it.copy(thinking = false, statusMessage = "电话模型已断开") }
                scheduleListening(300)
                return@launch
            }
            val activeLabel = LuluAiServices.connectionStore.archiveLabel(activeArchive)
            val recentHistory = buildCallHistory(
                MigratedDomainStores.chat.messages(latest.conversationId).value,
                latest.characterName,
            )
            LuluDeviceToolBridge.respond(
                characterId = latest.characterId,
                history = recentHistory,
                userText = spoken,
                title = activeLabel,
                archiveId = archiveId,
                sceneContext = "你正在和用户进行一对一实时电话。你能意识到电话已经接通，听见的是用户刚刚在电话里说的话；具体关系与称呼必须服从你的人设。回复要像真实通话，口语自然、长度适中，不要朗读说明文字。",
            ).onSuccess { reply ->
                val text = reply.text.trim()
                if (text.isBlank()) {
                    mutableState.update { it.copy(thinking = false, statusMessage = "刚才没有听清回复，再说一句吧") }
                    scheduleListening(300)
                    return@onSuccess
                }
                val characterMessage = MigratedDomainStores.chat.appendCharacterMessage(latest.conversationId, text)
                SharedExperienceTimeline.recordChatMessage(
                    latest.characterId,
                    latest.conversationId,
                    characterMessage,
                    channelOverride = "电话",
                )
                val shouldSpeak = mutableState.value.speakerEnabled
                mutableState.update {
                    it.copy(
                        thinking = false,
                        speaking = shouldSpeak,
                        statusMessage = if (shouldSpeak) "${latest.characterName} 正在说话" else "回复已显示在字幕里",
                    )
                }
                if (shouldSpeak) {
                    speechEngine?.speak(text, scope) {
                        scope.launch {
                            mutableState.update { it.copy(speaking = false, statusMessage = "正在听你说话") }
                            scheduleListening(220)
                        }
                    }
                } else {
                    scheduleListening(220)
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        thinking = false,
                        speaking = false,
                        statusMessage = error.message?.take(80) ?: "回复失败，再说一次吧",
                    )
                }
                scheduleListening(500)
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        val startedAt = SystemClock.elapsedRealtime()
        timerJob = scope.launch {
            while (mutableState.value.connected) {
                mutableState.update { it.copy(elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1_000L) }
                delay(1_000L)
            }
        }
    }

    private fun saveCallExperience(current: LuluVoiceCallState) {
        if (!current.everConnected || current.experienceSaved) return
        val transcript = MigratedDomainStores.chat.messages(current.conversationId).value
            .drop(current.callStartMessageCount)
            .joinToString("\n") { message ->
                val speaker = if (message.sender == LuluChatMessage.Sender.User) "你" else current.characterName
                "$speaker：${message.content.trim()}"
            }
        SharedExperienceTimeline.remember(
            memoryId = "call-${current.callExperienceId}",
            characterId = current.characterId,
            label = "共同通话",
            detail = buildString {
                append("进行了一次持续约 ${current.elapsedSeconds.coerceAtLeast(1)} 秒的电话。")
                if (transcript.isNotBlank()) append("通话内容：\n$transcript")
            },
            occurredAt = current.callStartedAt ?: Instant.now(),
            strength = 7,
            source = "voice-call",
        )
        MigratedDomainStores.chat.appendSystemMessage(current.conversationId, "[共同活动] 刚刚打了个电话")
        mutableState.update { it.copy(experienceSaved = true) }
    }

    private fun hasMicrophonePermission(): Boolean {
        val context = appContext ?: return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun configureCallAudio(speaker: Boolean) {
        val manager = audioManager ?: return
        if (previousAudioMode == null) previousAudioMode = manager.mode
        if (previousSpeakerphone == null) previousSpeakerphone = manager.isSpeakerphoneOn
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.isSpeakerphoneOn = speaker
    }

    private fun restoreCallAudio() {
        val manager = audioManager ?: return
        previousSpeakerphone?.let { manager.isSpeakerphoneOn = it }
        previousAudioMode?.let { manager.mode = it }
        previousSpeakerphone = null
        previousAudioMode = null
    }

    private fun startForegroundService() {
        val context = appContext ?: return
        ContextCompat.startForegroundService(
            context,
            Intent(context, LuluVoiceCallService::class.java).setAction(LuluVoiceCallService.ACTION_START),
        )
    }

    private fun stopForegroundService() {
        val context = appContext ?: return
        context.stopService(Intent(context, LuluVoiceCallService::class.java))
    }
}

class LuluVoiceCallService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_END -> {
                LuluVoiceCallSession.endCall()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> startForeground(NOTIFICATION_ID, buildNotification())
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "正在通话", NotificationManager.IMPORTANCE_LOW).apply {
                description = "让露露的语音通话在后台继续"
                setSound(null, null)
            },
        )
    }

    private fun buildNotification(): android.app.Notification {
        val state = LuluVoiceCallSession.state.value
        val openIntent = PendingIntent.getActivity(
            this,
            9101,
            Intent(this, MigrationActivity::class.java).apply {
                putExtra("open_conversation_id", state.conversationId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val endIntent = PendingIntent.getService(
            this,
            9102,
            Intent(this, LuluVoiceCallService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle("正在与 ${state.characterName.ifBlank { "露露" }} 通话")
            .setContentText("切到后台也会继续听你说话和播放回复")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "挂断", endIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.jiacimu.lulu.voicecall.START"
        const val ACTION_STOP = "com.jiacimu.lulu.voicecall.STOP"
        const val ACTION_END = "com.jiacimu.lulu.voicecall.END"
        private const val CHANNEL_ID = "lulu_voice_call"
        private const val NOTIFICATION_ID = 7124
    }
}

private fun buildCallHistory(messages: List<LuluChatMessage>, characterName: String): String = messages
    .filter { it.sender != LuluChatMessage.Sender.System }
    .takeLast(24)
    .joinToString("\n") { message ->
        val role = if (message.sender == LuluChatMessage.Sender.User) "用户" else characterName
        "$role：${message.content.trim()}"
    }
