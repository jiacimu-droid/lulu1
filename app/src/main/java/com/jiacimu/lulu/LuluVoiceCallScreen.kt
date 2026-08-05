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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

private val CallInk = Color(0xFF243047)
private val CallMuted = Color(0xFF6F7890)
private val CallDanger = Color(0xFFEE5963)
private val CallBlue = Color(0xFF6C91D8)
private val CallBlueSoft = Color(0xFFDCE8FF)
private val CallLavender = Color(0xFFE9E2FA)
private val CallWarm = Color(0xFFFFEEE2)
private val CallGlass = Color(0xEFFFFFFF)
private val CallLine = Color(0xFFDDE3F0)

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

@Composable
fun LuluVoiceCallScreen(
    conversationId: String,
    characterId: String,
    characterName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state by LuluVoiceCallSession.state.collectAsState()
    val library by LuluAiServices.connectionStore.library.collectAsState()
    val activeConversationId = state.conversationId.ifBlank { conversationId }
    val messagesFlow = remember(activeConversationId) { MigratedDomainStores.chat.messages(activeConversationId) }
    val messages by messagesFlow.collectAsState()
    val character = MigratedDomainStores.characters.get(state.characterId.ifBlank { characterId })
    val listState = rememberLazyListState()
    var modelExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(conversationId, characterId, characterName) {
        LuluVoiceCallSession.prepare(context, conversationId, characterId, characterName)
    }

    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) LuluVoiceCallSession.dial() else LuluVoiceCallSession.reportPermissionDenied()
    }

    val voiceArchiveId = library.archiveIdFor(ModelUsage.VoiceCall)
    val activeArchive = library.archives.firstOrNull { it.id == voiceArchiveId }
    val activeLabel = activeArchive?.let(LuluAiServices.connectionStore::archiveLabel) ?: "未连接电话模型"
    val callMessages = remember(messages, state.callStartMessageCount) {
        messages.drop(state.callStartMessageCount).filter { it.sender != LuluChatMessage.Sender.System }
    }
    val visibleCallMessages = remember(callMessages) { callMessages.takeLast(12) }

    LaunchedEffect(visibleCallMessages.size) {
        if (visibleCallMessages.isNotEmpty()) listState.animateScrollToItem(visibleCallMessages.lastIndex)
    }
    LaunchedEffect(state.phase) {
        if (state.phase == CallPhase.Ended || state.phase == CallPhase.Idle) onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFF8F5FF), Color(0xFFEAF2FF), Color(0xFFFFF8F3)),
                    ),
                ),
        ) {
            CallAmbientBackground(state)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CallTopBar(
                    activeLabel = activeLabel,
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = it },
                    archives = library.archives,
                    activeArchiveId = voiceArchiveId,
                    onSelectArchive = {
                        LuluAiServices.connectionStore.selectArchive(it, ModelUsage.VoiceCall)
                        modelExpanded = false
                    },
                    onMinimize = onDismiss,
                )

                Spacer(Modifier.height(18.dp))
                Box(contentAlignment = Alignment.Center) {
                    Surface(
                        modifier = Modifier.size(138.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = .45f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = .86f)),
                        shadowElevation = 18.dp,
                    ) {}
                    Surface(
                        modifier = Modifier.size(118.dp),
                        shape = RoundedCornerShape(34.dp),
                        color = Color.White,
                        border = BorderStroke(4.dp, Color.White),
                        shadowElevation = 10.dp,
                    ) {
                        LuluProfileAvatar(
                            imageUri = character.avatarUri,
                            fallback = state.characterName.ifBlank { characterName }.take(1).ifBlank { "露" },
                            size = 118,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    state.characterName.ifBlank { characterName },
                    color = CallInk,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    callStatusText(state, activeArchive != null),
                    color = CallMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(11.dp))
                CallActivityIndicator(state)
                Spacer(Modifier.height(16.dp))

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp, max = 255.dp),
                        shape = RoundedCornerShape(28.dp),
                        color = CallGlass,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = .9f)),
                        shadowElevation = 8.dp,
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.Subtitles, null, tint = CallBlue, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("实时字幕", color = CallInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Spacer(Modifier.weight(1f))
                                if (state.connected) Text(formatCallDuration(state.elapsedSeconds), color = CallMuted, fontSize = 12.sp)
                            }
                            HorizontalDivider(color = CallLine.copy(alpha = .7f))
                            if (callMessages.isEmpty()) {
                                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        when (state.phase) {
                                            CallPhase.Ready -> "拨通以后不需要再按住麦克风\n像普通电话一样，直接说话就好"
                                            CallPhase.Dialing -> "正在等待对方接听"
                                            CallPhase.Connected -> if (state.microphoneMuted) "麦克风已静音" else "我会自动听你说完，再让对方回应"
                                            else -> "通话字幕会显示在这里"
                                        },
                                        color = CallMuted,
                                        fontSize = 14.sp,
                                        lineHeight = 21.sp,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    items(visibleCallMessages, key = { it.id }) { message ->
                                        val mine = message.sender == LuluChatMessage.Sender.User
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(
                                                if (mine) "你" else state.characterName.ifBlank { characterName },
                                                color = if (mine) CallBlue else Color(0xFF9A6BB5),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                message.content,
                                                modifier = Modifier.padding(top = 3.dp),
                                                color = CallInk,
                                                fontSize = 15.sp,
                                                lineHeight = 21.sp,
                                            )
                                        }
                                    }
                                    if (state.partialTranscript.isNotBlank()) {
                                        item {
                                            Text("你：${state.partialTranscript}", color = CallMuted, fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                when (state.phase) {
                    CallPhase.Ready -> {
                        FilledIconButton(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    LuluVoiceCallSession.dial()
                                } else {
                                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            enabled = activeArchive != null,
                            modifier = Modifier.size(72.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = CallBlue,
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFFBCC5D7),
                            ),
                        ) {
                            Icon(Icons.Outlined.Call, "拨打电话", modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("拨打电话", color = CallMuted, fontSize = 12.sp)
                    }

                    CallPhase.Dialing -> {
                        CallPrimaryHangup(label = "取消呼叫", onClick = LuluVoiceCallSession::cancelDial)
                    }

                    CallPhase.Connected -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CallControl(
                                icon = if (state.speakerEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
                                label = if (state.speakerEnabled) "扬声器" else "听筒",
                                active = state.speakerEnabled,
                                onClick = LuluVoiceCallSession::toggleSpeaker,
                            )
                            CallControl(
                                icon = if (state.microphoneMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                                label = if (state.microphoneMuted) "取消静音" else "麦克风",
                                active = !state.microphoneMuted,
                                onClick = LuluVoiceCallSession::toggleMicrophone,
                            )
                            CallControl(
                                icon = Icons.Outlined.KeyboardArrowDown,
                                label = "缩小",
                                active = false,
                                onClick = onDismiss,
                            )
                            CallControl(
                                icon = Icons.Outlined.CallEnd,
                                label = "挂断",
                                active = true,
                                danger = true,
                                onClick = LuluVoiceCallSession::endCall,
                            )
                        }
                    }

                    CallPhase.Ended, CallPhase.Idle -> {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = CallBlue)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun CallAmbientBackground(state: LuluVoiceCallState) {
    val pulse by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (state.listening || state.speaking) 1f else .65f,
        animationSpec = androidx.compose.animation.core.tween(700),
        label = "通话氛围",
    )
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            color = CallLavender.copy(alpha = .48f),
            radius = size.minDimension * .48f * pulse,
            center = Offset(size.width * .15f, size.height * .22f),
        )
        drawCircle(
            color = CallBlueSoft.copy(alpha = .58f),
            radius = size.minDimension * .52f,
            center = Offset(size.width * .88f, size.height * .36f),
        )
        drawCircle(
            color = CallWarm.copy(alpha = .68f),
            radius = size.minDimension * .4f,
            center = Offset(size.width * .44f, size.height * .94f),
        )
    }
}

@Composable
private fun CallActivityIndicator(state: LuluVoiceCallState) {
    val active = state.listening || state.thinking || state.speaking
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(Color.White.copy(alpha = .58f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(7) { index ->
            val height = when {
                !active -> 4
                state.thinking -> 5 + (index % 3) * 3
                else -> 6 + ((index * 5) % 4) * 3
            }
            Box(
                Modifier
                    .width(3.dp)
                    .height(height.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            state.speaking -> Color(0xFF9A6BB5)
                            state.listening -> CallBlue
                            else -> CallMuted.copy(alpha = .55f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun CallTopBar(
    activeLabel: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    archives: List<com.jiacimu.lulu.ai.ModelArchive>,
    activeArchiveId: String?,
    onSelectArchive: (String) -> Unit,
    onMinimize: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = onMinimize,
            modifier = Modifier.size(42.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Color.White.copy(alpha = .68f),
                contentColor = CallInk,
            ),
        ) {
            Icon(Icons.Outlined.KeyboardArrowDown, "缩小通话")
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("语音通话", color = CallInk, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text("缩小后仍会继续", color = CallMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.weight(1f))
        Box {
            Surface(
                modifier = Modifier.clickable { onExpandedChange(true) },
                color = Color.White.copy(alpha = .68f),
                shape = RoundedCornerShape(99.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .88f)),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(15.dp), tint = CallBlue)
                    Spacer(Modifier.width(5.dp))
                    Text(activeLabel, maxLines = 1, fontSize = 11.sp, color = CallInk, modifier = Modifier.widthIn(max = 190.dp))
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
                if (archives.isEmpty()) {
                    DropdownMenuItem(text = { Text("还没有模型存档") }, enabled = false, onClick = {})
                } else {
                    archives.forEach { archive ->
                        val selected = archive.id == activeArchiveId
                        DropdownMenuItem(
                            text = { Text(LuluAiServices.connectionStore.archiveLabel(archive)) },
                            leadingIcon = {
                                Icon(
                                    if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                                    null,
                                )
                            },
                            onClick = { onSelectArchive(archive.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(58.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = when {
                    danger -> CallDanger
                    active -> Color.White.copy(alpha = .92f)
                    else -> Color.White.copy(alpha = .55f)
                },
                contentColor = if (danger) Color.White else CallInk,
            ),
        ) {
            Icon(icon, label, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = CallMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CallPrimaryHangup(label: String, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(72.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = CallDanger, contentColor = Color.White),
    ) {
        Icon(Icons.Outlined.CallEnd, label, modifier = Modifier.size(30.dp))
    }
    Spacer(Modifier.height(8.dp))
    Text(label, color = CallMuted, fontSize = 12.sp)
}

private fun callStatusText(state: LuluVoiceCallState, modelConnected: Boolean): String = when {
    !modelConnected -> "请先在右上角选择电话模型"
    state.phase == CallPhase.Ready -> state.statusMessage.ifBlank { "准备好以后拨打" }
    state.phase == CallPhase.Dialing -> "正在呼叫 ${state.characterName}…"
    state.phase == CallPhase.Ended -> "通话已结束"
    state.microphoneMuted -> "麦克风已静音 · ${formatCallDuration(state.elapsedSeconds)}"
    state.thinking -> "${state.characterName} 正在回应"
    state.speaking -> "${state.characterName} 正在说话 · ${formatCallDuration(state.elapsedSeconds)}"
    state.listening -> "正在听你说话 · ${formatCallDuration(state.elapsedSeconds)}"
    state.connected -> "已接通 · ${formatCallDuration(state.elapsedSeconds)}"
    else -> state.statusMessage
}

private fun buildCallHistory(messages: List<LuluChatMessage>, characterName: String): String = messages
    .filter { it.sender != LuluChatMessage.Sender.System }
    .takeLast(24)
    .joinToString("\n") { message ->
        val role = if (message.sender == LuluChatMessage.Sender.User) "用户" else characterName
        "$role：${message.content.trim()}"
    }

private fun formatCallDuration(seconds: Long): String {
    val minutes = seconds / 60
    val remain = seconds % 60
    return "%02d:%02d".format(minutes, remain)
}
