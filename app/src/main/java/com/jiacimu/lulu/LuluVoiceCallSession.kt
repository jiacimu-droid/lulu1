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
    /** VAD saw the user's voice, not merely an open microphone. */
    val userSpeaking: Boolean = false,
    val thinking: Boolean = false,
    val speaking: Boolean = false,
    val speakerEnabled: Boolean = true,
    val audioRouteLabel: String = "扬声器",
    val microphoneMuted: Boolean = false,
    /** User may listen silently; the character owns the initiative until stopped. */
    val sleepMode: Boolean = false,
    val sleepFocus: SleepGuidanceFocus = SleepGuidanceFocus.Natural,
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
    private var subtitleRevealJob: Job? = null
    private var realtime: RealtimeVoiceAdapter? = null
    private var replyJob: Job? = null
    private var replyGeneration = 0L
    private var timerJob: Job? = null
    private var dialJob: Job? = null
    private var restartListeningJob: Job? = null
    private var systemCommitJob: Job? = null
    private var sleepContinuationJob: Job? = null
    private var systemUtteranceBuffer = ""
    private var systemPartialCandidate = ""
    private var userSpeechInProgress = false
    private var sleepFailures = 0
    private var lastUserActivityMillis = 0L
    private var lastCallAudioMillis = 0L
    private var lastSilenceReflectionMillis = 0L
    private var silenceFailures = 0
    private var replyIsSilence = false
    private var silenceAudioPending = false

    private fun noteCallUserActivity() {
        lastUserActivityMillis = SystemClock.elapsedRealtime()
        silenceFailures = 0
        if (replyIsSilence || silenceAudioPending) {
            replyGeneration++
            replyJob?.cancel()
            replyIsSilence = false
            silenceAudioPending = false
            autonomousHangup.cancel()
            subtitleRevealJob?.cancel()
            speechQueue?.stop()
            mutableState.update { it.copy(thinking = false, generatedTranscript = "", playingTranscript = "") }
        }
        noteSleepUserReply()
    }

    /** Monotonic time: the listener's silence is measured even if the call UI is minimized. */
    private var lastSleepUserReplyAtMillis: Long? = null

    private fun sleepSilenceMillis(): Long {
        val latest = lastSleepUserReplyAtMillis ?: return 0L
        return (SystemClock.elapsedRealtime() - latest).coerceAtLeast(0L)
    }

    private fun noteSleepUserReply() {
        if (mutableState.value.sleepMode) lastSleepUserReplyAtMillis = SystemClock.elapsedRealtime()
    }

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
                            if (speaking || mutableState.value.speaking) lastCallAudioMillis = SystemClock.elapsedRealtime()
                            if (speaking) {
                                if (replyIsSilence) {
                                    replyGeneration++; replyJob?.cancel(); replyIsSilence = false
                                    mutableState.update { it.copy(thinking = false) }
                                }
                            }
                            mutableState.update { it.copy(listening = listening, speaking = speaking, opening = it.opening && !listening, statusMessage = note) }
                            AvatarController.listening(current.characterId, listening)
                        }
                    },
                    onUserSpeech = { if (mutableState.value.callExperienceId == sessionId) noteCallUserActivity() },
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
        if (CallVoiceConfiguration.sttEngine(context) != "system") {
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
        mutableState.update {
            it.copy(
                microphoneMuted = nextMuted,
                userSpeaking = if (nextMuted) false else it.userSpeaking,
                listening = if (nextMuted) false else it.listening,
                inputLevel = if (nextMuted) 0f else it.inputLevel,
                partialTranscript = if (nextMuted) "" else it.partialTranscript,
                statusMessage = when {
                    nextMuted && realtime != null -> "实时线路已静音；麦克风是否释放取决于 ElevenLabs SDK"
                    nextMuted -> "麦克风已释放，可在其他应用语音输入"
                    else -> "麦克风已打开，直接说话就好"
                },
            )
        }
        if (realtime != null) {
            // An Agent SDK owns its own microphone; keep explicit mute, but
            // do not set system-wide AudioManager.isMicrophoneMute.
            realtime?.mute(nextMuted)
            if (nextMuted) audioRoute?.stop() else audioRoute?.start()
            return
        }
        if (nextMuted) {
            userSpeechInProgress = false
            // "Muted" is a real capture shutdown, not zeroing input frames
            // while retaining AudioRecord and globally silencing other apps.
            restartListeningJob?.cancel()
            resetSystemRecognitionTurn()
            providerInput?.pauseCapture()
            pauseRecognition()
            recognizer?.destroy()
            recognizer = null
            audioRoute?.stop()
        } else {
            audioRoute?.start()
            audioRoute?.refresh()
            if (appContext?.let(CallVoiceConfiguration::sttEngine) == "system") {
                scheduleListening(160)
            } else {
                providerInput?.resumeCapture() ?: startProviderInput()
            }
        }
    }

    /**
     * Sleep mode is an opt-in continuous character monologue. The listener may
     * remain completely silent, mute the mic, or answer whenever they want.
     * The normal direct call model is used, preserving the role and its voice.
     */
    fun toggleSleepMode() {
        val current = mutableState.value
        if (!current.connected) return
        if (realtime != null) {
            mutableState.update { it.copy(statusMessage = "实时 Agent 线路暂不支持无人发言时自动续讲，请选普通电话模式") }
            return
        }
        val enabled = !current.sleepMode
        sleepContinuationJob?.cancel()
        sleepFailures = 0
        lastSleepUserReplyAtMillis = if (enabled) SystemClock.elapsedRealtime() else null
        mutableState.update {
            it.copy(sleepMode = enabled, sleepFocus = SleepGuidanceFocus.Natural,
                statusMessage = if (enabled) "哄睡陪伴已开启 · 你可以只听，也可以随时说话"
                else "哄睡模式已关闭 · 恢复普通通话")
        }
        if (enabled) scheduleSleepContinuation()
    }

    fun selectSleepFocus(focus: SleepGuidanceFocus) {
        val call = mutableState.value
        if (!call.connected || !call.sleepMode) return
        mutableState.update { it.copy(sleepFocus = focus) }
        // Current spoken audio is not interrupted; the next natural reply
        // will adopt this focus, or an explicit user request can supersede it.
    }

    private fun scheduleSleepContinuation() {
        sleepContinuationJob?.cancel()
        val call = mutableState.value
        if (!call.sleepMode || realtime != null) return
        val sessionId = call.callExperienceId
        sleepContinuationJob = scope.launch {
            // A small real pause avoids robotic back-to-back speech and gives
            // the user a chance to begin their own reply.
            delay(SleepCallContinuationPolicy.nextPauseMillis(sleepSilenceMillis()))
            val now = mutableState.value
            if (now.callExperienceId != sessionId) return@launch
            // The call timer also enforces the end; do not generate another
            // monologue after the unattended-call threshold.
            if (SleepCallContinuationPolicy.shouldQuietlyEnd(
                    sinceUserReplyMillis = sleepSilenceMillis(),
                    connected = now.connected,
                    sleepMode = now.sleepMode,
                    userSpeaking = userSpeechInProgress,
                    audioInProgress = now.speaking || speechQueue?.hasPendingAudio == true,
                )) {
                endSleepCallOnSilence()
                return@launch
            }
            if (!SleepCallContinuationPolicy.canContinue(
                    connected = now.connected,
                    sleepMode = now.sleepMode,
                    thinking = now.thinking,
                    speaking = now.speaking || speechQueue?.hasPendingAudio == true,
                    opening = now.opening,
                    userSpeaking = userSpeechInProgress,
                    realtime = realtime != null,
                )) return@launch
            // Empty userText means no user utterance occurred. The next subject
            // comes from the role's own thoughts and ACTUALLY delivered history.
            handleUserSpeech("", autonomousSleep = true)
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

    /** Never claim the user has fallen asleep; simply stop after sustained silence. */
    private fun endSleepCallOnSilence() = finishCall(endedByCharacter = true, sleepAutoEnded = true)

    private fun finishCall(endedByCharacter: Boolean, sleepAutoEnded: Boolean = false) {
        val current = mutableState.value
        if (current.phase == CallPhase.Idle || current.phase == CallPhase.Ended) return
        LuluCallRingtone.stopAll()
        sleepContinuationJob?.cancel()
        sleepContinuationJob = null
        resetSystemRecognitionTurn()
        sleepFailures = 0
        replyIsSilence = false
        silenceAudioPending = false
        subtitleRevealJob?.cancel()
        subtitleRevealJob = null
        lastSleepUserReplyAtMillis = null
        userSpeechInProgress = false
        autonomousHangup.cancel()
        val endStatus = if (sleepAutoEnded) "哄睡陪伴已安静结束" else
            if (endedByCharacter) "${current.characterName}结束了通话" else "通话已结束"
        // Mark ended before stopping audio: callbacks cannot reopen the microphone or revive state.
        mutableState.update { it.copy(phase = CallPhase.Ended, opening = false, listening = false,
            userSpeaking = false, thinking = false, speaking = false, sleepMode = false,
            statusMessage = endStatus) }
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
                userSpeaking = false,
                thinking = false,
                speaking = false,
                partialTranscript = "",
                statusMessage = endStatus,
            )
        }
        restoreCallAudio()
        stopForegroundService()
        scope.launch {
            delay(if (endedByCharacter) 2_500L else 850L)
            if (mutableState.value.phase == CallPhase.Ended &&
                mutableState.value.callExperienceId == current.callExperienceId) {
                LuluCallWindowController.minimize()
                mutableState.value = LuluVoiceCallState()
            }
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
                lastCallAudioMillis = SystemClock.elapsedRealtime()
                if (!busy && speechQueue?.hasPendingAudio != true) silenceAudioPending = false
                if (busy) {
                    // Never let either system/cloud ASR hear the character's own TTS and
                    // persist it back as a fake user utterance.
                    resetSystemRecognitionTurn()
                    providerInput?.pauseCapture()
                    pauseRecognition()
                    audioRoute?.refresh()
                }
                mutableState.update {
                    it.copy(
                        speaking = busy,
                        userSpeaking = if (busy) false else it.userSpeaking,
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
                        if (appContext?.let(CallVoiceConfiguration::sttEngine) == "system") {
                            scheduleListening(220)
                        } else {
                            providerInput?.resumeCapture()
                        }
                    }
                    scheduleSleepContinuation()
                }
            },
            onError = { error -> if (mutableState.value.connected) {
                mutableState.update { it.copy(speaking = false, errorMessage = "发声失败：$error", statusMessage = "回复已生成，但声音播放失败") }
                if (mutableState.value.sleepMode && ++sleepFailures >= 3) {
                    sleepContinuationJob?.cancel()
                    mutableState.update { it.copy(sleepMode = false, statusMessage = "哄睡发声连续失败，已停止自动续讲以免反复请求") }
                }
            } },
        )
        audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun ensureRecognizer() {
        val context = appContext ?: return
        if (recognizer != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speechRecognizer.also { speechRecognizer ->
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (!mutableState.value.connected || mutableState.value.opening) return
                    recognitionActive = true
                    mutableState.update {
                        it.copy(
                            listening = true,
                            partialTranscript = systemUtteranceBuffer,
                            statusMessage = if (systemUtteranceBuffer.isBlank()) "正在听你说话" else "听到了，你可以继续说",
                        )
                    }
                }

                override fun onBeginningOfSpeech() {
                    val call = mutableState.value
                    if (!call.connected || call.opening || call.thinking || call.speaking ||
                        speechQueue?.hasPendingAudio == true) return
                    systemCommitJob?.cancel()
                    systemCommitJob = null
                    systemPartialCandidate = ""
                    userSpeechInProgress = true
                    noteCallUserActivity()
                    sleepContinuationJob?.cancel()
                    mutableState.update { it.copy(userSpeaking = true, statusMessage = "听到了，你继续说") }
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (!mutableState.value.connected || mutableState.value.opening) return
                    userSpeechInProgress = false
                    mutableState.update { it.copy(userSpeaking = false, listening = false, statusMessage = "正在识别你的语音…") }
                }

                override fun onError(error: Int) {
                    if (!mutableState.value.connected || mutableState.value.opening) return
                    userSpeechInProgress = false
                    recognitionActive = false
                    mutableState.update { it.copy(userSpeaking = false) }
                    val hasBufferedSpeech = systemUtteranceBuffer.isNotBlank()
                    if (hasBufferedSpeech &&
                        error in setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                        mutableState.update {
                            it.copy(
                                listening = false,
                                partialTranscript = systemUtteranceBuffer,
                                statusMessage = "听到了，稍等一下…",
                            )
                        }
                        scheduleSystemUtteranceCommit(420)
                        scheduleListening(160)
                        return
                    }
                    scheduleSleepContinuation()
                    mutableState.update { current ->
                        current.copy(
                            listening = false,
                            partialTranscript = systemUtteranceBuffer,
                            statusMessage = when (error) {
                                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                    "语音识别网络暂时不可用"
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
                    val call = mutableState.value
                    if (!call.connected || call.opening) return
                    if (call.thinking || call.speaking || speechQueue?.hasPendingAudio == true) {
                        userSpeechInProgress = false
                        recognitionActive = false
                        resetSystemRecognitionTurn()
                        return
                    }
                    userSpeechInProgress = false
                    recognitionActive = false
                    mutableState.update { it.copy(userSpeaking = false) }
                    val finals = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()
                        .map(String::trim)
                        .filter(String::isNotBlank)
                    val primary = finals.firstOrNull().orEmpty()
                    val partial = systemPartialCandidate.trim()
                    val spoken = when {
                        primary.isBlank() -> partial
                        partial.isBlank() -> primary
                        normalizeSpeechForComparison(partial).contains(normalizeSpeechForComparison(primary)) &&
                            partial.length > primary.length -> partial
                        else -> primary
                    }
                    systemPartialCandidate = ""
                    if (spoken.isBlank()) {
                        mutableState.update { it.copy(listening = false, partialTranscript = systemUtteranceBuffer) }
                        if (systemUtteranceBuffer.isBlank()) scheduleSleepContinuation()
                        else scheduleSystemUtteranceCommit(520)
                        scheduleListening(180)
                    } else {
                        systemUtteranceBuffer = PhoneTranscriptAssembler.combine(systemUtteranceBuffer, spoken).trim()
                        mutableState.update {
                            it.copy(
                                listening = false,
                                partialTranscript = systemUtteranceBuffer,
                                statusMessage = "听到了，你还可以继续说",
                            )
                        }
                        scheduleSystemUtteranceCommit()
                        scheduleListening(120)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val call = mutableState.value
                    if (!call.connected || call.opening || call.thinking || call.speaking ||
                        speechQueue?.hasPendingAudio == true) return
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    systemPartialCandidate = partial
                    mutableState.update {
                        it.copy(partialTranscript = PhoneTranscriptAssembler.combine(systemUtteranceBuffer, partial))
                    }
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
        // Never start a new recognition window while a finalized sentence is
        // awaiting its turn. A noisy recognizer may immediately call onBeginning,
        // canceling the commit and trapping a valid reply in the speech buffer.
        if (!current.connected || current.microphoneMuted || current.thinking || current.speaking ||
            current.opening || recognitionActive || systemUtteranceBuffer.isNotBlank() ||
            realtime != null || providerInput != null) return
        if (!hasMicrophonePermission()) {
            mutableState.update { it.copy(statusMessage = "需要麦克风权限才能继续通话") }
            return
        }
        ensureRecognizer()
        val speechRecognizer = recognizer
        if (speechRecognizer == null) {
            val error = "手机没有可用的系统语音识别，请在语音设置选择 Groq Whisper"
            mutableState.update { it.copy(statusMessage = error, errorMessage = error) }
            return
        }
        runCatching {
            speechRecognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                 putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_050L)
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
        userSpeechInProgress = false
        mutableState.update { it.copy(listening = false, userSpeaking = false, partialTranscript = "") }
    }

    private fun normalizeSpeechForComparison(value: String): String =
        value.lowercase().replace(Regex("""[\s，。！？!?、；;：:“”‘’…~～—_"'（）()]+"""), "")

    private fun scheduleSystemUtteranceCommit(delayMillis: Long = 700L) {
        systemCommitJob?.cancel()
        val sessionId = mutableState.value.callExperienceId
        systemCommitJob = scope.launch {
            delay(delayMillis)
            val current = mutableState.value
            if (!current.connected || current.callExperienceId != sessionId ||
                current.thinking || current.speaking || current.opening || userSpeechInProgress) return@launch
            val spoken = systemUtteranceBuffer.trim()
            if (spoken.isBlank()) return@launch
            systemUtteranceBuffer = ""
            systemPartialCandidate = ""
            systemCommitJob = null
            handleUserSpeech(spoken)
        }
    }

    private fun resetSystemRecognitionTurn() {
        systemCommitJob?.cancel()
        systemCommitJob = null
        systemUtteranceBuffer = ""
        systemPartialCandidate = ""
    }

    private fun startOpening() {
        val current = mutableState.value
        if (!current.connected || !openingTurn.claim(current.callExperienceId)) return
        mutableState.update { it.copy(opening = true, listening = false, statusMessage = "${it.characterName} 正要开口") }
        handleUserSpeech(CallOpeningTurn.prompt(current.incomingReason), opening = true)
    }

    private fun handleUserSpeech(
        spoken: String,
        opening: Boolean = false,
        allowWhileMuted: Boolean = false,
        autonomousSleep: Boolean = false,
        autonomousSilence: Boolean = false,
    ) {
        val current = mutableState.value
        if (!current.connected || (realtime != null && !autonomousSilence) ||
            (!opening && !autonomousSleep && !autonomousSilence && ((current.microphoneMuted && !allowWhileMuted) || current.opening)) ||
            (autonomousSleep && (!current.sleepMode || userSpeechInProgress || current.thinking ||
                current.speaking || speechQueue?.hasPendingAudio == true || current.opening))) return
        if (autonomousSilence && (current.sleepMode || userSpeechInProgress || current.thinking ||
            current.speaking || speechQueue?.hasPendingAudio == true || current.opening)) return
        sleepContinuationJob?.cancel()
        if (!autonomousSleep && !autonomousSilence) {
            userSpeechInProgress = false
            if (spoken.isNotBlank() && !opening) noteCallUserActivity()
        }
        replyGeneration++
        autonomousHangup.cancel()
        subtitleRevealJob?.cancel()
        speechQueue?.stop()
        val generation = replyGeneration
        replyJob?.cancel()
        replyIsSilence = autonomousSilence
        silenceAudioPending = false
        if (!autonomousSilence) pauseRecognition()
        if (!opening && !autonomousSleep && !autonomousSilence) {
            resetSystemRecognitionTurn()
            MigratedDomainStores.chat.appendVoiceMessage(current.conversationId,
                "voice-${current.callExperienceId}-user-$generation", spoken, false)
        }
        mutableState.update { it.copy(thinking = true, userSpeaking = false, opening = opening, partialTranscript = "", errorMessage = "", statusMessage = if (autonomousSilence) "${current.characterName} 正陪着你" else "${current.characterName} 正在想怎么回答") }
        replyJob = scope.launch {
            val latest = mutableState.value
            val library = LuluAiServices.connectionStore.library.value
            val archiveId = library.archiveIdFor(ModelUsage.VoiceCall)
                ?: if (autonomousSilence) library.archiveIdFor(ModelUsage.Chat) else null
            val activeArchive = library.archives.firstOrNull { it.id == archiveId }
            if (activeArchive == null) {
                replyIsSilence = false
                if (autonomousSilence) silenceFailures++
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
                    val renderedSpeech = appContext?.let {
                        VoicePerformance.sleepAudio(it, speech, latest.sleepMode)
                    } ?: speech
                    // Reserve a stable ID BEFORE playback. The exact streamed
                    // performance and the chat transcript must share this ID.
                    val voiceMessageId = "voice-${if (latest.sleepMode) "sleep-" else ""}${latest.callExperienceId}-agent-${UUID.randomUUID()}"
                    speechQueue?.enqueue(
                        text = renderedSpeech,
                        speakerId = latest.characterId,
                        voiceId = CharacterVoicePreferenceStore.callVoiceId(latest.characterId, latest.sleepMode),
                        messageId = voiceMessageId,
                        onStarted = {
                            if (sameReply()) {
                                silenceAudioPending = false
                                subtitleRevealJob?.cancel()
                                val lines = PhoneSubtitleLayout.lines(plainSpeech)
                                if (lines.isNotEmpty()) {
                                    mutableState.update { it.copy(playingTranscript = lines.first()) }
                                    subtitleRevealJob = scope.launch {
                                        for (index in 1 until lines.size) {
                                            delay(PhoneSubtitleProgress.pauseBeforeNextLine(lines[index - 1]))
                                            if (!sameReply() || !mutableState.value.speaking) break
                                            val released = lines.take(index + 1).joinToString("\n")
                                            mutableState.update { it.copy(playingTranscript = released) }
                                        }
                                    }
                                }
                            }
                        },
                        onDelivered = {
                            if (!sameReply()) return@enqueue
                            subtitleRevealJob?.cancel()
                            heard.append(VoicePerformance.plain(part))
                            if (plainSpeech.isNotBlank()) {
                                sleepFailures = 0
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
            val bedtimeGuide = if (latest.sleepMode) SleepGuidanceGuide.instruction(
                latest.sleepFocus, continuing = autonomousSleep,
                silenceMillis = sleepSilenceMillis(),
            ) else ""
            val silenceContext = if (autonomousSilence) CallSilencePolicy.context(
                SystemClock.elapsedRealtime() - lastUserActivityMillis, latest.microphoneMuted) else ""
            val observationId = if (autonomousSilence) "call-silence-${latest.callExperienceId}-$generation" else ""
            if (autonomousSilence) com.jiacimu.lulu.data.SharedExperienceTimeline.record(
                eventId = observationId, characterId = latest.characterId, channel = "电话陪伴",
                speaker = "通话观察", content = "电话仍接通；本轮没有新增用户发言；距最近用户语音活动约 ${(SystemClock.elapsedRealtime() - lastUserActivityMillis) / 1000} 秒；麦克风${if (latest.microphoneMuted) "静音" else "未静音"}", triggerExtraction = false,
                sessionId = latest.callExperienceId, source = "call-observation",
                evidenceKind = com.jiacimu.lulu.data.EventEvidenceKind.Observation)
            LuluDeviceToolBridge.respond(
                characterId = latest.characterId,
                history = recentHistory,
                userText = if (autonomousSleep || autonomousSilence) "" else spoken,
                title = activeLabel,
                archiveId = archiveId,
                silenceObservationId = observationId,
                sceneContext = when {
                    autonomousSilence -> silenceContext
                    autonomousSleep -> """
                        这是同一通电话的哄睡后续。用户没有新增发言，这只是系统续讲事件，不能当作用户说话或催促。
                        $bedtimeGuide
                        自然接着上次真正说过的内容继续，不断切换技巧或从头开场都没有必要。说完这一段让电话程序安排后续；不要反复索要回应或制造新的世界事件。
                    """.trimIndent()
                    opening && latest.sleepMode -> "哄睡电话刚刚接通，用户尚未开口。你先轻声自然开场，不催促她说话，不假装她回答。\n$bedtimeGuide"
                    opening -> "你正在和用户进行一对一实时电话，刚刚接通，用户尚未开口。现在由你按自己的关系、人设和最近上下文先说一两句自然开场。不要把通话事件当作用户说过的话，不要朗读事件说明。"
                    latest.sleepMode -> "这是哄睡陪伴电话。用户刚刚说了话，要先认真回应她，再自然选择引导或陪伴方式。用户可以安静只听，别催促答话。\n$bedtimeGuide"
                    else -> "你正在和用户进行一对一实时电话。你能意识到电话已经接通，听见的是用户刚刚在电话里说的话；具体关系与称呼必须服从你的人设。回复要像真实通话，口语自然。先理解用户这段话的重点、情绪和说话是否已经结束，再依角色自己的个性、关系和兴趣自然回应。简单的事可以轻快接话，值得深谈的事可以认真讲清；不要固定每轮几句话，不要机械复述、每句都追问或习惯性附和。角色可以有自己的判断、幽默、沉默与不同意见，但不得编造已经发生的事情。不要朗读说明文字。"
                },
                onCharacterHangup = if (realtime != null) null else ({
                    if (sameReply() && !autonomousSleep) autonomousHangup.request(latest.callExperienceId, generation)
                }),
                onReplyStream = if (autonomousSilence) null else ({ envelope: String -> scope.launch {
                    if (!sameReply() || stream.isFinished) return@launch
                    val parts = stream.updateForSpeech(envelope, wholeTurnSpeech)
                    CallReplyStream.replyTextPrefix(envelope)?.let { candidate ->
                        mutableState.update { it.copy(generatedTranscript = VoicePerformance.plain(candidate)) }
                    }
                    enqueueSpoken(parts)
                }; Unit }),
            ).onSuccess { reply ->
                if (generation != replyGeneration || !mutableState.value.connected || mutableState.value.callExperienceId != latest.callExperienceId) return@onSuccess
                replyIsSilence = false
                if (autonomousSilence) silenceFailures = 0
                if (reply.disposition == com.jiacimu.lulu.data.CharacterDecisionProtocol.SILENT) {
                    stream.cancel()
                    mutableState.update { it.copy(thinking = false, opening = false,
                        statusMessage = "${latest.characterName} 正安静陪着你") }
                    return@onSuccess
                }
                val text = reply.text
                if (autonomousSilence && realtime != null && text.isNotBlank()) {
                    mutableState.update { it.copy(thinking = false) }
                    runCatching { realtime?.continueFromSilence(text) }.onFailure { error ->
                        silenceFailures++
                        mutableState.update { it.copy(errorMessage = "陪伴发声失败：${error.message.orEmpty().take(100)}") }
                    }
                    return@onSuccess
                }
                if (text.isBlank()) {
                    stream.cancel()
                    speechQueue?.stop()
                    mutableState.update { it.copy(thinking = false, opening = false, statusMessage = "刚才没有听清回复，再说一句吧") }
                    scheduleListening(300)
                    if (autonomousSleep && ++sleepFailures >= 3) {
                        mutableState.update { it.copy(sleepMode = false, statusMessage = "哄睡自动续讲连续失败，已暂停") }
                    } else scheduleSleepContinuation()
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
                    if (autonomousSleep && ++sleepFailures >= 3) {
                        mutableState.update { it.copy(sleepMode = false, statusMessage = "哄睡续讲解析连续失败，已暂停") }
                    } else scheduleSleepContinuation()
                    return@onSuccess
                }
                mutableState.update { it.copy(thinking = false, generatedTranscript = VoicePerformance.plain(text),
                    statusMessage = if (it.speaking) "${latest.characterName} 正在说话" else "回复正在准备发声") }
                silenceAudioPending = autonomousSilence && remaining.isNotEmpty()
                enqueueSpoken(remaining)
                // During a new call's opening, do not reopen the microphone while
                // the first sentence is still synthesizing. Speaking becomes true
                // only when real PCM is audible, not when TTS request begins.
                if (speechQueue?.hasPendingAudio != true) {
                    mutableState.update { it.copy(opening = false) }
                    scheduleListening(220)
                }
                clearWhenHeard()
                finishAutonomousHangupIfReady()
            }.onFailure { error ->
                if (generation != replyGeneration || !mutableState.value.connected) return@onFailure
                replyIsSilence = false
                if (autonomousSilence) {
                    silenceFailures++
                    com.jiacimu.lulu.data.CompanionPresenceStore.recordPerceptionAttempt(
                        latest.characterId, "通话沉默感知失败 · ${error.message.orEmpty().take(100)}")
                    mutableState.update { it.copy(thinking = false,
                        statusMessage = "通话仍在继续", errorMessage = "陪伴状态更新失败：${error.message.orEmpty().take(100)}") }
                    return@onFailure
                }
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
                if (autonomousSleep && ++sleepFailures >= 3) {
                    mutableState.update { it.copy(sleepMode = false, statusMessage = "哄睡服务连续请求失败，已停止自动重试") }
                } else scheduleSleepContinuation()
            }
        }
    }

    private fun startProviderInput() {
        val context = appContext ?: return
        providerInput?.stop()
        val id = mutableState.value.callExperienceId
        fun sameSession() = mutableState.value.callExperienceId == id && mutableState.value.phase in setOf(CallPhase.Dialing, CallPhase.Connected)
        providerInput = ProviderCallInput(context, scope,
            sttEngine = CallVoiceConfiguration.sttEngine(context),
            accept = { val s = mutableState.value; sameSession() && !s.microphoneMuted && !s.speaking && !s.opening },
            onReady = {
                if (sameSession()) {
                    if (!mutableState.value.microphoneMuted) audioRoute?.refresh()
                    val wasConnected = mutableState.value.connected
                    mutableState.update { it.copy(phase = CallPhase.Connected, callStartedAt = it.callStartedAt ?: Instant.now(), everConnected = true,
                        listening = !it.thinking && !it.speaking && !it.opening, inputMeterAvailable = true,
                        errorMessage = "", statusMessage = if (it.thinking) it.statusMessage else "麦克风收音已启动，直接说话；停顿后识别并回复") }
                    if (!wasConnected) { startTimer(); startOpening() }
                }
            },
            onLevel = { level -> if (sameSession()) mutableState.update { it.copy(inputLevel = level) } },
            onSpeech = {
                if (sameSession()) {
                    userSpeechInProgress = true
                    noteCallUserActivity()
                    sleepContinuationJob?.cancel()
                    // Do not cancel a real in-flight model reply merely because
                    // room noise tripped VAD. Confirm a new user utterance from
                    // STT before replacing the previous reply.
                    mutableState.update { it.copy(userSpeaking = true, listening = true, errorMessage = "", partialTranscript = "",
                        statusMessage = "检测到语音，正在收音…") }
                }
            },
            onPartial = { text -> if (sameSession()) mutableState.update { it.copy(partialTranscript = text) } },
            onText = { text ->
                if (sameSession()) {
                    userSpeechInProgress = false
                    mutableState.update { it.copy(userSpeaking = false) }
                    if (!mutableState.value.opening) handleUserSpeech(text, allowWhileMuted = true)
                }
            },
            onStatus = { note -> if (sameSession()) mutableState.update { it.copy(
                userSpeaking = if (note.contains("识别完整语句")) false else it.userSpeaking,
                statusMessage = if (it.thinking) it.statusMessage else note,
            ) } },
            onError = { error -> if (sameSession()) {
                userSpeechInProgress = false
                scheduleSleepContinuation()
                val dialing = mutableState.value.phase == CallPhase.Dialing
                mutableState.update { it.copy(phase = if (dialing) CallPhase.Ready else it.phase, listening = false, userSpeaking = false, inputLevel = 0f,
                    errorMessage = "收音／识别失败：$error", statusMessage = "语音识别未完成，可重新收音") }
                if (dialing) { providerInput?.stop(); providerInput = null; restoreCallAudio(); stopForegroundService() }
            } },
        ).also { it.start() }
    }

    fun retryListening() {
        if (!mutableState.value.connected || realtime != null) return
        sleepContinuationJob?.cancel()
        userSpeechInProgress = false
        replyGeneration++; replyJob?.cancel()
        subtitleRevealJob?.cancel(); speechQueue?.stop()
        mutableState.update { it.copy(speaking = false, thinking = false, userSpeaking = false, opening = false, microphoneMuted = false, errorMessage = "", generatedTranscript = "", playingTranscript = "") }
        audioRoute?.microphone(false)
        audioRoute?.refresh()
        if (appContext?.let(CallVoiceConfiguration::sttEngine) != "system") startProviderInput()
        else { pauseRecognition(); scheduleListening(100) }
    }

    private fun startTimer() {
        timerJob?.cancel()
        com.jiacimu.lulu.data.CompanionPresenceStore.beginCall(mutableState.value.characterId)
        com.jiacimu.lulu.data.CompanionOnlineStore.recordActivity(mutableState.value.characterId)
        val startedAt = SystemClock.elapsedRealtime()
        lastUserActivityMillis = startedAt
        lastCallAudioMillis = startedAt
        // First ordinary-call reflection after one quiet minute, then bounded pulses.
        lastSilenceReflectionMillis = startedAt - 60_000L
        silenceFailures = 0
        timerJob = scope.launch {
            while (mutableState.value.connected) {
                mutableState.update { it.copy(elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1_000L) }
                if (mutableState.value.elapsedSeconds % 60L == 0L) {
                    com.jiacimu.lulu.data.CompanionOnlineStore.recordActivity(mutableState.value.characterId)
                }
                val current = mutableState.value
                if (SleepCallContinuationPolicy.shouldQuietlyEnd(
                        sinceUserReplyMillis = sleepSilenceMillis(),
                        connected = current.connected,
                        sleepMode = current.sleepMode,
                        userSpeaking = userSpeechInProgress,
                        audioInProgress = current.speaking || speechQueue?.hasPendingAudio == true,
                    )) {
                    endSleepCallOnSilence()
                    break
                }
                val clock = SystemClock.elapsedRealtime()
                if (CallSilencePolicy.shouldReflect(current.connected, current.sleepMode,
                        current.thinking || current.speaking || current.opening || speechQueue?.hasPendingAudio == true,
                        userSpeechInProgress, clock - lastUserActivityMillis,
                        clock - lastCallAudioMillis, clock - lastSilenceReflectionMillis, silenceFailures)) {
                    lastSilenceReflectionMillis = clock
                    handleUserSpeech("", autonomousSilence = true)
                }
                delay(1_000L)
            }
        }
    }

    private fun saveCallExperience(current: LuluVoiceCallState, endedByCharacter: Boolean = false) {
        if (!current.everConnected || current.experienceSaved) return
        com.jiacimu.lulu.data.CompanionOnlineStore.recordActivity(current.characterId)
        // Phone audio/transcripts already live in the conversation's raw history.
        // Do NOT duplicate an entire call (especially hours of bedtime narration)
        // as a permanent high-strength shared memory. Normal evidence-backed
        // extraction may still retain genuinely important facts or relationship
        // milestones from actual user/character utterances.
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
