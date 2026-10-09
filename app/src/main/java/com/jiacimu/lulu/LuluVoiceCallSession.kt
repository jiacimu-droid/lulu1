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
import android.os.Build
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
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
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
import kotlinx.coroutines.flow.collect
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
    val audioRouteLabel: String = "扬声器",
    val microphoneMuted: Boolean = false,
    val partialTranscript: String = "",
    val inputLevel: Float = 0f,
    val inputMeterAvailable: Boolean = false,
    val errorMessage: String = "",
    val provider: String = "",
    val generatedTranscript: String = "",
    val playingTranscript: String = "",
    val statusMessage: String = "",
    val elapsedSeconds: Long = 0L,
    val callStartMessageCount: Int = 0,
    val callStartedAt: Instant? = null,
    val callExperienceId: String = "",
    val everConnected: Boolean = false,
    val experienceSaved: Boolean = false,
    val opening: Boolean = false,
    val incomingReason: String = "",
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
    private var providerInput: ProviderCallInput? = null
    private var speechQueue: LuluCallSpeechQueue? = null
    private var realtime: RealtimeVoiceAdapter? = null
    private var replyJob: Job? = null
    private var replyGeneration = 0L
    private var timerJob: Job? = null
    private var dialJob: Job? = null
    private var restartListeningJob: Job? = null
    private var recognitionActive = false
    private var audioManager: AudioManager? = null
    private var audioRoute: CallAudioRoute? = null
    private val openingTurn = CallOpeningTurn()
    private val autonomousHangup = CallAutonomousHangupGate()

    private fun finishAutonomousHangupIfReady() {
        val call = mutableState.value
        if (autonomousHangup.consumeWhenReady(call.callExperienceId, replyGeneration,
                call.connected, call.thinking, call.speaking)) {
            endCallByCharacter()
        }
    }

    fun prepare(context: Context, conversationId: String, characterId: String, characterName: String, incomingReason: String = "") {
        initialize(context)
        val current = mutableState.value
        if (current.phase == CallPhase.Idle || current.phase == CallPhase.Ended) {
            autonomousHangup.cancel()
            mutableState.value = LuluVoiceCallState(
                conversationId = conversationId,
                characterId = characterId,
                characterName = characterName,
                phase = CallPhase.Ready,
                incomingReason = incomingReason,
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
        val context = appContext ?: return
        val prefs = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
        val provider = CallVoiceConfiguration.provider(context)
        CallVoiceConfiguration.preflight(context, current.characterId)?.let { error ->
            mutableState.update { it.copy(statusMessage = error, errorMessage = error) }; return
        }
        mutableState.update { it.copy(provider = provider, errorMessage = "") }
        val useRealtime = CallVoiceConfiguration.usesAgent(provider, prefs.getString("voice_call_mode", "direct"))
        if (useRealtime) {
            if (!com.jiacimu.lulu.data.CloudTaskBridge.isConfigured()) {
                mutableState.update { it.copy(statusMessage = "请先配置云端语音服务") }
                return
            }
            configureCallAudio()
            startForegroundService()
            mutableState.update { it.copy(phase = CallPhase.Dialing, microphoneMuted = false, statusMessage = "正在连接实时语音…") }
            dialJob?.cancel()
            dialJob = scope.launch {
                val sessionId = current.callExperienceId
                val adapter = RealtimeVoiceAdapter(checkNotNull(appContext), scope,
                    onConnected = {
                        if (mutableState.value.callExperienceId == sessionId && mutableState.value.phase == CallPhase.Dialing) {
                            mutableState.update { it.copy(phase = CallPhase.Connected, callStartedAt = Instant.now(), everConnected = true, listening = false, opening = true, statusMessage = "已接通，正在准备开场") }
                            audioRoute?.refresh()
                            startTimer()
                        }
                    },
                    onState = { listening, speaking, note ->
                        if (mutableState.value.callExperienceId == sessionId && mutableState.value.connected) {
                            mutableState.update { it.copy(listening = listening, speaking = speaking, opening = it.opening && !listening, statusMessage = note) }
                            AvatarController.listening(current.characterId, listening)
                        }
                    },
                    onCandidate = { text -> if (mutableState.value.callExperienceId == sessionId) mutableState.update { it.copy(generatedTranscript = text) } },
                    onError = { error -> if (mutableState.value.callExperienceId == sessionId) {
                        mutableState.update { it.copy(statusMessage = error) }
                        if (error == "实时通话已断开") endCall()
                    } },
                )
                realtime = adapter
                runCatching { adapter.start(current.characterId, current.conversationId, CallOpeningTurn.prompt(current.incomingReason)) }.onFailure { error ->
                    adapter.stop()
                    if (mutableState.value.callExperienceId == sessionId && mutableState.value.phase == CallPhase.Dialing) {
                        mutableState.update { it.copy(phase = CallPhase.Ready, statusMessage = "连接失败：${error.message}") }
                        restoreCallAudio()
                        stopForegroundService()
                    }
                }
            }
            return
        }
        val library = LuluAiServices.connectionStore.library.value
        val archiveId = library.archiveIdFor(ModelUsage.VoiceCall)
        if (library.archives.none { it.id == archiveId }) {
            mutableState.update { it.copy(statusMessage = "请先选择电话模型") }
            return
        }
        configureCallAudio()
        startForegroundService()
        mutableState.update {
            it.copy(
                phase = CallPhase.Dialing,
                statusMessage = "正在呼叫…",
                microphoneMuted = false,
            )
        }
        dialJob?.cancel()
        if (provider == "minimax") {
            startProviderInput()
            return
        }
        dialJob = scope.launch {
            delay(350)
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
            startOpening()
        }
    }

    fun cancelDial() {
        if (mutableState.value.phase == CallPhase.Dialing) endCall()
    }

    fun toggleMicrophone() {
        val nextMuted = !mutableState.value.microphoneMuted
        audioRoute?.microphone(nextMuted)
        mutableState.update {
            it.copy(
                microphoneMuted = nextMuted,
                statusMessage = if (nextMuted) "麦克风已静音" else "麦克风已打开，直接说话就好",
            )
        }
        realtime?.let { it.mute(nextMuted); return }
        if (providerInput != null) return
        if (nextMuted) {
            pauseRecognition()
        } else {
            scheduleListening(160)
        }
    }

    fun toggleSpeaker() {
        audioRoute?.speaker(!mutableState.value.speakerEnabled)
    }

    fun reportPermissionDenied() {
        mutableState.update { it.copy(statusMessage = "没有麦克风权限，暂时无法开始通话") }
    }

    fun endCall() = finishCall(endedByCharacter = false)

    private fun endCallByCharacter() = finishCall(endedByCharacter = true)

    private fun finishCall(endedByCharacter: Boolean) {
        val current = mutableState.value
        if (current.phase == CallPhase.Idle || current.phase == CallPhase.Ended) return
        LuluCallRingtone.stopAll()
        autonomousHangup.cancel()
        // Mark ended before stopping audio: callbacks cannot reopen the microphone or revive state.
        mutableState.update { it.copy(phase = CallPhase.Ended, opening = false, listening = false,
            thinking = false, speaking = false,
            statusMessage = if (endedByCharacter) "${current.characterName}结束了通话" else "通话已结束") }
        com.jiacimu.lulu.data.CompanionPresenceStore.finishCall(current.characterId)
        dialJob?.cancel()
        replyGeneration++
        replyJob?.cancel()
        realtime?.stop()
        realtime = null
        providerInput?.stop()
        providerInput = null
        AvatarController.audio(current.characterId, 0f)
        AvatarController.listening(current.characterId, false)
        timerJob?.cancel()
        restartListeningJob?.cancel()
        pauseRecognition()
        // A recognizer created for a previous SYSTEM call must never be reused for
        // an ElevenLabs direct call, where only the device-bound service is allowed.
        recognizer?.destroy()
        recognizer = null
        recognitionActive = false
        speechQueue?.stop()
        saveCallExperience(current, endedByCharacter)
        mutableState.update {
            it.copy(
                phase = CallPhase.Ended,
                listening = false,
                thinking = false,
                speaking = false,
                partialTranscript = "",
                statusMessage = if (endedByCharacter) "${current.characterName}结束了通话" else "通话已结束",
            )
        }
        restoreCallAudio()
        stopForegroundService()
        scope.launch {
            delay(850)
            if (mutableState.value.phase == CallPhase.Ended && mutableState.value.callExperienceId == current.callExperienceId) mutableState.value = LuluVoiceCallState()
        }
    }

    private fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        CharacterVoicePreferenceStore.initialize(context.applicationContext)
        // Observe the actual call phase, not just the call page (it may be minimized).
        scope.launch {
            var previousPhase: CallPhase? = null
            mutableState.collect { call ->
                if (call.phase == previousPhase) return@collect
                previousPhase = call.phase
                when (call.phase) {
                    CallPhase.Dialing -> LuluCallRingtone.startOutgoing(context.applicationContext)
                    CallPhase.Connected, CallPhase.Ready, CallPhase.Ended, CallPhase.Idle ->
                        LuluCallRingtone.stopOutgoing()
                }
            }
        }
        speechQueue = LuluCallSpeechQueue(
            context = context.applicationContext,
            scope = scope,
            onBusyChanged = busyChanged@ { busy ->
                val current = mutableState.value
                if (!current.connected) return@busyChanged
                if (busy) audioRoute?.refresh()
                mutableState.update {
                    it.copy(
                        speaking = busy,
                        playingTranscript = if (busy) it.playingTranscript else "",
                        opening = it.opening && (busy || it.thinking),
                        statusMessage = when {
                            busy -> "${it.characterName} 正在说话"
                            it.connected && it.thinking -> "正在生成后续回复"
                            it.connected && !it.microphoneMuted -> "正在听你说话"
                            else -> it.statusMessage
                        },
                    )
                }
                if (!busy && current.connected) {
                    finishAutonomousHangupIfReady()
                    if (mutableState.value.connected && !mutableState.value.microphoneMuted) {
                        scheduleListening(220)
                    }
                }
            },
            onError = { error -> if (mutableState.value.connected) mutableState.update { it.copy(speaking = false, errorMessage = "发声失败：$error", statusMessage = "回复已生成，但声音播放失败") } },
        )
        audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun ensureRecognizer() {
        val context = appContext ?: return
        if (recognizer != null) return
        val directElevenLabs = CallVoiceConfiguration.requiresOnDeviceStt(
            mutableState.value.provider, "direct")
        val speechRecognizer = if (directElevenLabs) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) return
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = speechRecognizer.also { speechRecognizer ->
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (!mutableState.value.connected || mutableState.value.opening) return
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
                    if (!mutableState.value.connected || mutableState.value.opening) return
                    mutableState.update { it.copy(statusMessage = "听到了，你继续说") }
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (!mutableState.value.connected || mutableState.value.opening) return
                    mutableState.update { it.copy(listening = false, statusMessage = "正在识别…") }
                }

                override fun onError(error: Int) {
                    if (!mutableState.value.connected || mutableState.value.opening) return
                    recognitionActive = false
                    mutableState.update { current ->
                        current.copy(
                            listening = false,
                            partialTranscript = "",
                            statusMessage = when (error) {
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                    if (CallVoiceConfiguration.requiresOnDeviceStt(current.provider, "direct"))
                                        "本机语音识别服务暂时不可用，请检查离线语音包"
                                    else "语音识别网络暂时不可用"
                                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                                    "本地中文识别不可用，请安装或下载中文语音识别包"
                                SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_CLIENT ->
                                    "系统语音识别暂时不可用"
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麦克风权限不可用"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "正在重新连接麦克风…"
                                else -> if (current.microphoneMuted) "麦克风已静音" else "我在听，直接说话就好"
                            },
                        )
                    }
                    if (error !in setOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) scheduleListening(420)
                }

                override fun onResults(results: Bundle?) {
                    if (!mutableState.value.connected || mutableState.value.opening) return
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
                    if (!mutableState.value.connected || mutableState.value.opening) return
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
        if (!current.connected || current.microphoneMuted || current.speaking || current.opening || recognitionActive || realtime != null || providerInput != null) return
        if (!hasMicrophonePermission()) {
            mutableState.update { it.copy(statusMessage = "需要麦克风权限才能继续通话") }
            return
        }
        ensureRecognizer()
        val speechRecognizer = recognizer
        if (speechRecognizer == null) {
            val error = if (CallVoiceConfiguration.requiresOnDeviceStt(current.provider, "direct"))
                "手机无法启动离线语音识别。请安装系统本地中文语音识别服务，不会使用 ElevenLabs 付费转写。"
                else "当前手机没有可用的语音识别服务"
            mutableState.update { it.copy(statusMessage = error, errorMessage = error) }
            return
        }
        runCatching {
            speechRecognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                if (CallVoiceConfiguration.requiresOnDeviceStt(current.provider, "direct"))
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 850L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
            })
            recognitionActive = true
        }.onFailure {
            recognitionActive = false
            mutableState.update { state -> state.copy(statusMessage = "语音识别无法启动", errorMessage = "系统识别启动失败：${it.message}") }
            scheduleListening(600)
        }
    }

    private fun pauseRecognition() {
        restartListeningJob?.cancel()
        recognitionActive = false
        runCatching { recognizer?.cancel() }
        mutableState.update { it.copy(listening = false, partialTranscript = "") }
    }

    private fun startOpening() {
        val current = mutableState.value
        if (!current.connected || !openingTurn.claim(current.callExperienceId)) return
        mutableState.update { it.copy(opening = true, listening = false, statusMessage = "${it.characterName} 正要开口") }
        handleUserSpeech(CallOpeningTurn.prompt(current.incomingReason), opening = true)
    }

    private fun handleUserSpeech(spoken: String, opening: Boolean = false) {
        val current = mutableState.value
        if (!current.connected || (!opening && (current.microphoneMuted || current.opening)) || realtime != null) return
        replyGeneration++
        autonomousHangup.cancel()
        speechQueue?.stop()
        val generation = replyGeneration
        replyJob?.cancel()
        pauseRecognition()
        if (!opening) MigratedDomainStores.chat.appendVoiceMessage(current.conversationId,
            "voice-${current.callExperienceId}-user-$generation", spoken, false)
        mutableState.update { it.copy(thinking = true, opening = opening, partialTranscript = "", errorMessage = "", statusMessage = "${current.characterName} 正在想怎么回答") }
        if (!opening) scheduleListening(200)
        replyJob = scope.launch {
            val latest = mutableState.value
            val library = LuluAiServices.connectionStore.library.value
            val archiveId = library.archiveIdFor(ModelUsage.VoiceCall)
            val activeArchive = library.archives.firstOrNull { it.id == archiveId }
            if (activeArchive == null) {
                mutableState.update { it.copy(thinking = false, opening = false, statusMessage = "电话模型已断开") }
                scheduleListening(300)
                return@launch
            }
            val activeLabel = LuluAiServices.connectionStore.archiveLabel(activeArchive)
            val recentHistory = buildCallHistory(
                MigratedDomainStores.chat.messages(latest.conversationId).value,
                latest.characterName,
            )
            val stream = CallReplyStream()
            // Expressive ElevenLabs voices interpret emotion over surrounding sentences.
            // Keep the live subtitle preview, but synthesize the completed turn once.
            val wholeTurnSpeech = appContext?.let { VoicePerformance.phoneNeedsWholeTurn(it) } == true
            val heard = StringBuilder()
            var finalText: String? = null
            fun sameReply() = generation == replyGeneration && mutableState.value.connected &&
                mutableState.value.callExperienceId == latest.callExperienceId
            fun clearWhenHeard() {
                val complete = finalText ?: return
                if (heard.toString().filterNot(Char::isWhitespace) == complete.filterNot(Char::isWhitespace))
                    mutableState.update { it.copy(generatedTranscript = "", playingTranscript = "") }
            }
            fun enqueueSpoken(parts: List<String>) {
                parts.forEach { part ->
                    val speech = part.replace(Regex("⟪[^⟫]*⟫"), "").trim()
                    if (speech.isBlank()) return@forEach
                    val plainSpeech = VoicePerformance.plain(speech)
                    // Reserve a stable ID BEFORE playback. The exact streamed
                    // performance and the chat transcript must share this ID.
                    val voiceMessageId = "voice-${latest.callExperienceId}-agent-${UUID.randomUUID()}"
                    speechQueue?.enqueue(
                        text = speech,
                        speakerId = latest.characterId,
                        voiceId = CharacterVoicePreferenceStore.playbackVoiceId(latest.characterId),
                        messageId = voiceMessageId,
                        onStarted = {
                            if (sameReply()) mutableState.update { it.copy(playingTranscript = plainSpeech) }
                        },
                        onDelivered = {
                            if (!sameReply()) return@enqueue
                            heard.append(VoicePerformance.plain(part))
                            if (plainSpeech.isNotBlank()) {
                                MigratedDomainStores.chat.appendVoiceMessage(latest.conversationId,
                                    voiceMessageId, plainSpeech, true)
                                autonomousHangup.markDelivered(latest.callExperienceId, generation)
                            }
                            clearWhenHeard()
                            mutableState.update { it.copy(playingTranscript = "") }
                        },
                    )
                }
            }
            LuluDeviceToolBridge.respond(
                characterId = latest.characterId,
                history = recentHistory,
                userText = spoken,
                title = activeLabel,
                archiveId = archiveId,
                sceneContext = if (opening) "你正在和用户进行一对一实时电话，刚刚接通，用户尚未开口。现在由你按自己的关系、人设和最近上下文先说一两句自然开场。不要把通话事件当作用户说过的话，不要朗读事件说明。"
                    else "你正在和用户进行一对一实时电话。你能意识到电话已经接通，听见的是用户刚刚在电话里说的话；具体关系与称呼必须服从你的人设。回复要像真实通话，口语自然。普通接话优先一到两句有内容的话，不每次长篇解释；用户要求详细内容时再展开。不要朗读说明文字。",
                onCharacterHangup = {
                    if (sameReply()) autonomousHangup.request(latest.callExperienceId, generation)
                },
                onReplyStream = { envelope -> scope.launch {
                    if (!sameReply() || stream.isFinished) return@launch
                    val parts = stream.updateForSpeech(envelope, wholeTurnSpeech)
                    CallReplyStream.replyTextPrefix(envelope)?.let { candidate ->
                        mutableState.update { it.copy(generatedTranscript = VoicePerformance.plain(candidate)) }
                    }
                    enqueueSpoken(parts)
                } },
            ).onSuccess { reply ->
                if (generation != replyGeneration || !mutableState.value.connected || mutableState.value.callExperienceId != latest.callExperienceId) return@onSuccess
                val text = reply.text
                if (text.isBlank()) {
                    stream.cancel()
                    speechQueue?.stop()
                    mutableState.update { it.copy(thinking = false, opening = false, statusMessage = "刚才没有听清回复，再说一句吧") }
                    scheduleListening(300)
                    return@onSuccess
                }
                finalText = VoicePerformance.plain(text)
                val remaining = runCatching {
                    // Validate the final speakable text without sending internal JSON to TTS.
                    // Dialogue-capable voices get one request for the entire emotional arc.
                    stream.finishForSpeech(text, wholeTurnSpeech)
                }.getOrElse { error ->
                    stream.cancel()
                    speechQueue?.stop()
                    mutableState.update { it.copy(thinking = false, opening = false, speaking = false, generatedTranscript = "", playingTranscript = "",
                        statusMessage = "回复未完整生成，可以继续说话", errorMessage = error.message.orEmpty()) }
                    scheduleListening(300)
                    return@onSuccess
                }
                mutableState.update { it.copy(thinking = false, generatedTranscript = VoicePerformance.plain(text),
                    statusMessage = if (it.speaking) "${latest.characterName} 正在说话" else "回复正在准备发声") }
                enqueueSpoken(remaining)
                if (!mutableState.value.speaking) {
                    mutableState.update { it.copy(opening = false) }
                    scheduleListening(220)
                }
                clearWhenHeard()
                finishAutonomousHangupIfReady()
            }.onFailure { error ->
                if (generation != replyGeneration || !mutableState.value.connected) return@onFailure
                stream.cancel()
                autonomousHangup.cancel()
                speechQueue?.stop()
                mutableState.update {
                    it.copy(
                        thinking = false,
                        opening = false,
                        speaking = false,
                        errorMessage = "模型回复失败：${error.message?.take(160).orEmpty()}",
                        statusMessage = "已识别你的话，但回复生成失败",
                        generatedTranscript = "", playingTranscript = "",
                    )
                }
                scheduleListening(500)
            }
        }
    }

    private fun startProviderInput() {
        val context = appContext ?: return
        providerInput?.stop()
        val id = mutableState.value.callExperienceId
        fun sameSession() = mutableState.value.callExperienceId == id && mutableState.value.phase in setOf(CallPhase.Dialing, CallPhase.Connected)
        providerInput = ProviderCallInput(context, scope,
            accept = { val s = mutableState.value; sameSession() && !s.microphoneMuted && !s.speaking && !s.opening },
            onReady = {
                if (sameSession()) {
                    audioRoute?.microphone(mutableState.value.microphoneMuted)
                    audioRoute?.refresh()
                    val wasConnected = mutableState.value.connected
                    mutableState.update { it.copy(phase = CallPhase.Connected, callStartedAt = it.callStartedAt ?: Instant.now(), everConnected = true,
                        listening = true, inputMeterAvailable = true, errorMessage = "", statusMessage = "麦克风收音已启动，直接说话；停顿后识别并回复") }
                    if (!wasConnected) { startTimer(); startOpening() }
                }
            },
            onLevel = { level -> if (sameSession()) mutableState.update { it.copy(inputLevel = level) } },
            onSpeech = {
                if (sameSession()) {
                    if (mutableState.value.thinking) { replyGeneration++; replyJob?.cancel() }
                    mutableState.update { it.copy(thinking = false, listening = true, errorMessage = "", partialTranscript = "", statusMessage = "检测到语音，正在收音…") }
                }
            },
            onPartial = { text -> if (sameSession()) mutableState.update { it.copy(partialTranscript = text) } },
            onText = { text -> if (sameSession() && !mutableState.value.microphoneMuted && !mutableState.value.opening) handleUserSpeech(text) },
            onStatus = { note -> if (sameSession()) mutableState.update { it.copy(statusMessage = note) } },
            onError = { error -> if (sameSession()) {
                val dialing = mutableState.value.phase == CallPhase.Dialing
                mutableState.update { it.copy(phase = if (dialing) CallPhase.Ready else it.phase, listening = false, inputLevel = 0f,
                    errorMessage = "收音／识别失败：$error", statusMessage = "语音识别未完成，可重新收音") }
                if (dialing) { providerInput?.stop(); providerInput = null; restoreCallAudio(); stopForegroundService() }
            } },
        ).also { it.start() }
    }

    fun retryListening() {
        if (!mutableState.value.connected || realtime != null) return
        replyGeneration++; replyJob?.cancel(); speechQueue?.stop()
        mutableState.update { it.copy(speaking = false, thinking = false, opening = false, microphoneMuted = false, errorMessage = "", generatedTranscript = "", playingTranscript = "") }
        audioRoute?.microphone(false)
        audioRoute?.refresh()
        if (mutableState.value.provider == "minimax") startProviderInput()
        else { pauseRecognition(); scheduleListening(100) }
    }

    private fun startTimer() {
        timerJob?.cancel()
        com.jiacimu.lulu.data.CompanionPresenceStore.beginCall(mutableState.value.characterId)
        com.jiacimu.lulu.data.CompanionOnlineStore.recordActivity(mutableState.value.characterId)
        val startedAt = SystemClock.elapsedRealtime()
        timerJob = scope.launch {
            while (mutableState.value.connected) {
                mutableState.update { it.copy(elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1_000L) }
                if (mutableState.value.elapsedSeconds % 60L == 0L) {
                    com.jiacimu.lulu.data.CompanionOnlineStore.recordActivity(mutableState.value.characterId)
                }
                delay(1_000L)
            }
        }
    }

    private fun saveCallExperience(current: LuluVoiceCallState, endedByCharacter: Boolean = false) {
        if (!current.everConnected || current.experienceSaved) return
        com.jiacimu.lulu.data.CompanionOnlineStore.recordActivity(current.characterId)
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
                append(if (endedByCharacter) "这次由${current.characterName}主动结束通话。" else "这次由用户结束通话。")
                if (transcript.isNotBlank()) append("通话内容：\n$transcript")
            },
            occurredAt = current.callStartedAt ?: Instant.now(),
            strength = 7,
            source = "voice-call",
        )
        MigratedDomainStores.chat.appendSystemMessage(current.conversationId,
            if (endedByCharacter) "[共同活动] ${current.characterName}主动结束了电话" else "[共同活动] 刚刚打了个电话")
        mutableState.update { it.copy(experienceSaved = true) }
    }

    private fun hasMicrophonePermission(): Boolean {
        val context = appContext ?: return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun configureCallAudio() {
        val manager = audioManager ?: return
        if (audioRoute == null) audioRoute = CallAudioRoute(manager,
            onRoute = { speaker, label -> mutableState.update { it.copy(speakerEnabled = speaker, audioRouteLabel = label) } },
            onError = { error -> mutableState.update { it.copy(errorMessage = error) } })
        audioRoute?.start()
    }

    private fun restoreCallAudio() { audioRoute?.stop() }

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
