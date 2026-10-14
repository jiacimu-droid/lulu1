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
import com.jiacimu.lulu.data.InteractionSignalBridge
import com.jiacimu.lulu.data.ProactivePerceptionScheduler
import com.jiacimu.lulu.data.ProactivePerceptionRuntime
import com.jiacimu.lulu.data.initializeBackgroundRuntime
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
    private var lastPhoneXhsRequest = ""
    private var lastPhoneXhsRequestAt = 0L
    private var speechQueue: LuluCallSpeechQueue? = null
    // A cloud transcript may arrive just AFTER TTS finishes. Keep a short
    // factual playback reference so its delayed echo does not become "user speech".
    private var recentlyPlayedAgentText = ""
    private var recentlyPlayedAgentAtMillis = 0L

    private fun isPlaybackEchoFromCloud(candidate: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val active = mutableState.value.speaking
        val recentlyPlayed = recentlyPlayedAgentText.isNotBlank() &&
            now - recentlyPlayedAgentAtMillis in 0L..12_000L
        return PhoneBargeInPolicy.isLikelyPlaybackEcho(
            candidate = candidate,
            generated = "", // Unplayed model text cannot be acoustic echo.
            playing = listOf(mutableState.value.playingTranscript, recentlyPlayedAgentText)
                .filter(String::isNotBlank).joinToString(" "),
            playbackActive = active || recentlyPlayed,
        )
    }
    private var subtitleRevealJob: Job? = null
    private var realtime: RealtimeVoiceAdapter? = null
    private var replyJob: Job? = null
    private var replyGeneration = 0L
    // Text typed in the private chat during a call is the same conversation,
    // not a second online-chat request or an invented microphone transcript.
    private val incomingChatDrafts = mutableListOf<String>()
    private var incomingChatJob: Job? = null

    /**
     * Scheduled reminders reuse the live call instead of creating call-in-call.
     * A bedtime session switches to the usual speaking voice for an audible wake.
     * This is an agent-owned reminder, never synthesized as a user utterance.
     */
    fun announceScheduledReminder(characterId: String, text: String, taskId: String): Boolean {
        val call = mutableState.value
        if (!call.connected || call.characterId != characterId || text.isBlank()) return false
        val queue = speechQueue ?: return false
        // Cancel any unfinished bedtime narration before delivering the wake cue.
        sleepContinuationJob?.cancel()
        replyJob?.cancel()
        replyGeneration++
        subtitleRevealJob?.cancel()
        mutableState.update { current ->
            current.copy(sleepMode = false, sleepFocus = SleepGuidanceFocus.Natural,
                thinking = false, opening = false, playingTranscript = "",
                generatedTranscript = "", statusMessage = "到点提醒 · 正常通话声线")
        }
        queue.stop()
        val callId = call.callExperienceId
        val speech = VoicePerformance.plain(text).take(600)
        val messageId = "voice-$callId-scheduled-$taskId"
        queue.enqueue(
            text = speech,
            speakerId = characterId,
            voiceId = CharacterVoicePreferenceStore.callVoiceId(characterId, false),
            messageId = messageId,
            onStarted = {
                if (mutableState.value.connected && mutableState.value.callExperienceId == callId) {
                    recentlyPlayedAgentText = speech
                    recentlyPlayedAgentAtMillis = SystemClock.elapsedRealtime()
                    mutableState.update { it.copy(playingTranscript = PhoneSubtitleLayout.lines(speech).joinToString("\n")) }
                }
            },
            onDelivered = {
                if (mutableState.value.connected && mutableState.value.callExperienceId == callId) {
                    MigratedDomainStores.chat.appendVoiceMessage(call.conversationId, messageId, speech, true)
                    mutableState.update { it.copy(playingTranscript = "") }
                    if (com.jiacimu.lulu.data.WakeMusicCoordinator.shouldSwitchAfterSpeech(
                            appContext, characterId, taskId)) {
                        // Finish the audible wake phrase, then release the phone audio focus.
                        scope.launch {
                            delay(300L)
                            if (mutableState.value.connected &&
                                mutableState.value.callExperienceId == callId) {
                                finishCall(endedByCharacter = true)
                            }
                        }
                    }
                }
            },
        )
        return true
    }

    fun isActiveForChat(conversationId: String): Boolean {
        val call = mutableState.value
        return call.connected && call.conversationId == conversationId
    }

    fun onTextChatMessage(conversationId: String, content: String): Boolean {
        if (!isActiveForChat(conversationId) || content.isBlank()) return false
        val callId = mutableState.value.callExperienceId
        incomingChatDrafts.add(content.trim())
        incomingChatJob?.cancel()
        incomingChatJob = scope.launch {
            // A cluster of QQ chat bubbles is one human turn, not N rapid phone replies.
            delay(1_600L)
            while (isActiveForChat(conversationId) && mutableState.value.callExperienceId == callId &&
                mutableState.value.opening) delay(250L)
            if (!isActiveForChat(conversationId) || mutableState.value.callExperienceId != callId) return@launch
            val typed = incomingChatDrafts.joinToString("\n").trim()
            incomingChatDrafts.clear()
            if (typed.isBlank()) return@launch
            val agent = realtime
            if (agent != null) {
                noteCallUserActivity()
                lastConfirmedUserSpeechMillis = SystemClock.elapsedRealtime()
                runCatching { agent.sendTypedMessage(typed) }.onFailure { error ->
                    mutableState.update { it.copy(errorMessage = "电话文字未能送达：${error.message.orEmpty()}") }
                }
            } else handleUserSpeech(typed, allowWhileMuted = true, alreadyPersisted = true)
        }
        return true
    }
    private var timerJob: Job? = null
    private var dialJob: Job? = null
    private var restartListeningJob: Job? = null
    private var systemCommitJob: Job? = null
    private var sleepContinuationJob: Job? = null
    private var systemUtteranceBuffer = ""
    private var systemPartialCandidate = ""
    // VAD's onset is untrusted; text makes a user utterance real.
    private var systemSpeechConfirmed = false
    private var providerSpeechConfirmed = false
    // Only STT utterances whose VAD actually started in an eligible mic
    // window may reach the user-message pipeline. Cloud callbacks can arrive
    // well after the character began loudspeaker playback.
    private var cloudCaptureTurnAllowed = false
    private var userSpeechInProgress = false
    private fun confirmedSpeechInProgress() = userSpeechInProgress &&
        (systemSpeechConfirmed || providerSpeechConfirmed)
    private var providerSpeechStartedMillis = 0L
    private var providerRecognitionPendingUntilMillis = 0L
    private var sleepFailures = 0
    private var lastUserActivityMillis = 0L
    private var lastConfirmedUserSpeechMillis = 0L
    private var confirmedUserSpeechCount = 0
    private var lastCallAudioMillis = 0L
    private var lastSilenceReflectionMillis = 0L
    private var silenceFailures = 0
    private var replyIsSilence = false
    private var silenceAudioPending = false

    private fun noteCallUserActivity() {
        if (pendingCharacterHangupJob?.isActive == true) {
            // Confirmed new user words or typed input supersede goodbye.
            cancelPendingCharacterHangup()
            mutableState.update { it.copy(statusMessage = "听到你还想说话，电话继续") }
        }
        lastUserActivityMillis = SystemClock.elapsedRealtime()
        silenceFailures = 0
        if (replyIsSilence || silenceAudioPending) {
            replyGeneration++
            replyJob?.cancel()
            replyIsSilence = false
            silenceAudioPending = false
            cancelPendingCharacterHangup()
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
    private var pendingCharacterHangupJob: Job? = null

    /** A real user response can change the farewell decision at any time
     * before the disconnect, including while speech recognition is pending.
     */
    private fun cancelPendingCharacterHangup() {
        pendingCharacterHangupJob?.cancel()
        pendingCharacterHangupJob = null
        autonomousHangup.cancel()
    }

    private fun startCharacterHangupGrace(callId: String, generation: Long, agent: Boolean) {
        if (pendingCharacterHangupJob?.isActive == true) return
        val initial = mutableState.value
        if (!initial.connected || initial.callExperienceId != callId) return
        val began = SystemClock.elapsedRealtime()
        pendingCharacterHangupJob = scope.launch {
            while (true) {
                val call = mutableState.value
                if (!call.connected || call.callExperienceId != callId ||
                    (agent && realtime == null) || (!agent && generation != replyGeneration)) return@launch
                // A newer utterance by the character means the old farewell is
                // no longer the final spoken decision.
                if (call.thinking || call.speaking || speechQueue?.hasPendingAudio == true) return@launch
                val clock = SystemClock.elapsedRealtime()
                val elapsed = clock - began
                val inputStillResolving = !call.microphoneMuted &&
                    (confirmedSpeechInProgress() || userSpeechInProgress ||
                        systemPartialCandidate.isNotBlank() ||
                        providerRecognitionPendingUntilMillis > clock)
                if (!CallHangupGracePolicy.shouldWait(elapsed, inputStillResolving)) {
                    endCallByCharacter()
                    return@launch
                }
                val remaining = ((CallHangupGracePolicy.RESPONSE_WINDOW_MS - elapsed)
                    .coerceAtLeast(0L) + 999L) / 1_000L
                val status = if (remaining > 0)
                    "准备挂断 · 还可以叫住对方（${remaining}秒）"
                else "听你是不是还想说话，暂不挂断"
                if (call.statusMessage != status) {
                    mutableState.update { it.copy(statusMessage = status) }
                }
                delay(250L)
            }
        }
    }

    private fun finishAutonomousHangupIfReady() {
        val call = mutableState.value
        if (autonomousHangup.consumeWhenReady(call.callExperienceId, replyGeneration,
                call.connected, call.thinking, call.speaking || speechQueue?.hasPendingAudio == true)) {
            startCharacterHangupGrace(call.callExperienceId, replyGeneration, agent = false)
        }
    }

    fun prepare(context: Context, conversationId: String, characterId: String, characterName: String, incomingReason: String = "") {
        initialize(context)
        val current = mutableState.value
        if (current.phase == CallPhase.Idle || current.phase == CallPhase.Ended) {
            cancelPendingCharacterHangup()
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
                                if (pendingCharacterHangupJob?.isActive == true) cancelPendingCharacterHangup()
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
                    onFarewellDelivered = {
                        if (mutableState.value.callExperienceId == sessionId &&
                            mutableState.value.connected)
                            startCharacterHangupGrace(sessionId, replyGeneration, agent = true)
                    },
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
        // A mute click means "I'm done"—freeze the recognizer's last words.
        val pendingSystemSpeech = if (nextMuted && realtime == null && providerInput == null)
            SystemSpeechTurnPolicy.wordsToSubmitOnMute(systemUtteranceBuffer, systemPartialCandidate)
        else ""
        mutableState.update {
            it.copy(
                microphoneMuted = nextMuted,
                userSpeaking = if (nextMuted) false else it.userSpeaking,
                listening = if (nextMuted) false else it.listening,
                inputLevel = if (nextMuted) 0f else it.inputLevel,
                partialTranscript = if (nextMuted && providerInput == null &&
                    pendingSystemSpeech.isBlank()) "" else it.partialTranscript,
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
            // Muting microphone must not tear down speaker/headset output.
            // WebRTC owns the realtime mic, not the Android audio route.
            if (!nextMuted) audioRoute?.refresh()
            return
        }
        if (nextMuted) {
            userSpeechInProgress = false
            systemSpeechConfirmed = false
            providerSpeechConfirmed = false
            // "Muted" is a real capture shutdown, not zeroing input frames
            // while retaining AudioRecord and globally silencing other apps.
            restartListeningJob?.cancel()
            resetSystemRecognitionTurn()
            providerInput?.pauseCapture()
            pauseRecognition()
            recognizer?.destroy()
            recognizer = null
            // Preserve the current headphones / earpiece during a text-only call.
            // Input capture is already released above.
            if (pendingSystemSpeech.isNotBlank()) {
                handleUserSpeech(pendingSystemSpeech, allowWhileMuted = true)
            } else if (mutableState.value.sleepMode) scheduleSleepContinuation()
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
        appContext?.let(SleepNarrativeStore::initialize)
        val enabled = !current.sleepMode
        sleepContinuationJob?.cancel()
        sleepFailures = 0
        // One hour starts at the LAST confirmed user sentence, not the
        // moment the listener opened the bedtime surface. If nothing has
        // ever been said during this call, start from enabling bedtime.
        lastSleepUserReplyAtMillis = if (enabled)
            lastConfirmedUserSpeechMillis.takeIf { confirmedUserSpeechCount > 0 }
                ?: SystemClock.elapsedRealtime()
        else null
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
        // Repeated false VAD starts/stops must not keep resetting this timer.
        if (sleepContinuationJob?.isActive == true) return
        val call = mutableState.value
        if (!call.sleepMode || realtime != null) return
        val sessionId = call.callExperienceId
        sleepContinuationJob = scope.launch {
            delay(SleepCallContinuationPolicy.nextPauseMillis(sleepSilenceMillis(), mutableState.value.sleepFocus == SleepGuidanceFocus.Story))
            while (mutableState.value.connected &&
                mutableState.value.callExperienceId == sessionId &&
                mutableState.value.sleepMode && realtime == null) {
                val now = mutableState.value
                val busy = now.speaking || speechQueue?.hasPendingAudio == true
                if (SleepCallContinuationPolicy.shouldQuietlyEnd(
                        sleepSilenceMillis(), now.connected, now.sleepMode,
                        confirmedSpeechInProgress(), busy,
                    )) {
                    endSleepCallOnSilence()
                    return@launch
                }
                // Hardware detection without recognized words is unreliable.
                // Wait a SHORT grace period for a real live utterance, then
                // let the bedtime monologue continue rather than hanging.
                val recentlyDetected = userSpeechInProgress &&
                    !confirmedSpeechInProgress() &&
                    SystemClock.elapsedRealtime() - providerSpeechStartedMillis in 0L..3_500L
                if (recentlyDetected) {
                    delay(600)
                    continue
                }
                if (SleepCallContinuationPolicy.canContinue(
                        now.connected, now.sleepMode, now.thinking, busy, now.opening,
                        confirmedSpeechInProgress(), realtime != null,
                    )) {
                    handleUserSpeech("", autonomousSleep = true)
                    return@launch
                }
                // Crucial: a busy/thinking/mic transition is temporary, not a
                // reason to discard the only pending bedtime continuation.
                delay(700)
            }
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
        lastPhoneXhsRequest = ""
        lastPhoneXhsRequestAt = 0L
        sleepContinuationJob?.cancel()
        sleepContinuationJob = null
        resetSystemRecognitionTurn()
        incomingChatJob?.cancel()
        incomingChatJob = null
        incomingChatDrafts.clear()
        sleepFailures = 0
        replyIsSilence = false
        silenceAudioPending = false
        subtitleRevealJob?.cancel()
        subtitleRevealJob = null
        lastSleepUserReplyAtMillis = null
        userSpeechInProgress = false
        systemSpeechConfirmed = false
        providerSpeechConfirmed = false
        providerSpeechStartedMillis = 0L
        providerRecognitionPendingUntilMillis = 0L
        cancelPendingCharacterHangup()
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
        // The user ending a connected call is a new *observed event*, not a
        // command to make the character worried or to send a canned message.
        // Preserve the cue before requesting a separate, deduplicated appraisal.
        if (current.everConnected && current.callExperienceId.isNotBlank()) {
            val cueId = InteractionSignalBridge.recordCallEnd(
                characterId = current.characterId,
                callId = current.callExperienceId,
                endedByCharacter = endedByCharacter,
                elapsedSeconds = current.elapsedSeconds,
                confirmedUserSpeechCount = confirmedUserSpeechCount,
                secondsSinceConfirmedSpeech = if (lastConfirmedUserSpeechMillis > 0L)
                    (SystemClock.elapsedRealtime() - lastConfirmedUserSpeechMillis).coerceAtLeast(0L) / 1_000L
                    else current.elapsedSeconds,
            )
            appContext?.let { context ->
                ProactivePerceptionScheduler.scheduleInteractionReflection(context, current.characterId, cueId)
                // The app is still alive immediately after the user hangs up.
                // Run the same authoritative pipeline now so that opening the
                // avatar can reveal a real reaction. The queued WorkManager
                // item is only a durable fallback if Android kills this process.
                val id = current.characterId
                // This is an immediate response to an actual user interaction,
                // not the optional periodic autonomous-background heartbeat.
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        initializeBackgroundRuntime(context)
                        ProactivePerceptionRuntime.runDueCycle(
                            context = context, trigger = "刚结束的电话互动；证据ID=$cueId",
                            targetCharacterId = id, force = true,
                            requiredInteractionEvidenceId = cueId,
                        )
                    }.onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        com.jiacimu.lulu.data.CompanionPresenceStore.recordPerceptionAttempt(
                            id, "通话结束后的感知尚未完成：${error.message.orEmpty().take(80)}",
                        )
                    }
                }
            }
        }
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
            // Never mix music with the last phone TTS frame or call audio route.
            delay(500L)
            com.jiacimu.lulu.data.WakeMusicCoordinator.onCallEnded(
                appContext, current.characterId,
            )
        }
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
                if (!busy && speechQueue?.hasPendingAudio != true) {
                    silenceAudioPending = false
                    if (current.speaking && recentlyPlayedAgentText.isNotBlank()) {
                        // Cancelling unplayed synthesis is not a new acoustic echo.
                        recentlyPlayedAgentAtMillis = SystemClock.elapsedRealtime()
                    }
                }
                if (busy) {
                    // Keep a previously captured V2 utterance eligible while its
                    // upload is pending; model/TTS playback must not erase user
                    // words already recorded. New loudspeaker audio remains gated.
                    if (!privateCallOutput() && !userSpeechInProgress &&
                        providerRecognitionPendingUntilMillis <= SystemClock.elapsedRealtime())
                        cloudCaptureTurnAllowed = false
                    // Cloud STT has its own PCM recorder and echo processing.
                    // Do not shut it down whenever TTS starts: that made
                    // ordinary calls effectively half-duplex (no barge-in).
                    // Android's provider-owned SpeechRecognizer remains gated
                    // on loudspeaker, where it cannot reliably isolate the user.
                    if (providerInput == null && !privateCallOutput()) {
                        // Pause capture so loudspeaker TTS isn't mistaken for
                        // the user, but DO NOT throw away verified words still
                        // waiting for a natural continuation.
                        pauseRecognition()
                    }
                    audioRoute?.refresh()
                }
                mutableState.update {
                    it.copy(
                        speaking = busy,
                        listening = if (providerInput != null) !busy && !it.microphoneMuted else it.listening,
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
                    // The gate must observe speaking=false AFTER the state update above.
                    // Previously it ran while call.speaking still held true, and
                    // nothing checked again after the last speech segment finished.
                    finishAutonomousHangupIfReady()
                    if (mutableState.value.connected && !mutableState.value.microphoneMuted) {
                        if (appContext?.let(CallVoiceConfiguration::sttEngine) == "system") {
                            if (systemUtteranceBuffer.isNotBlank()) {
                                scheduleSystemUtteranceCommit(SystemSpeechTurnPolicy.continuationMillis(systemUtteranceBuffer))
                            }
                            if (!recognitionActive) scheduleListening(220)
                        } else {
                            providerInput?.resumeCapture()
                        }
                    }
                    scheduleSleepContinuation()
                }
            },
            onError = { error -> if (mutableState.value.connected) {
                // The character's farewell is not complete when TTS fails.
                // Never execute a structured or spoken hangup after failed audio.
                cancelPendingCharacterHangup()
                mutableState.update { it.copy(speaking = false, errorMessage = "发声失败：$error", statusMessage = "回复已生成，但声音播放失败") }
                if (mutableState.value.sleepMode && ++sleepFailures >= 3) {
                    sleepContinuationJob?.cancel()
                    mutableState.update { it.copy(sleepMode = false, statusMessage = "哄睡发声连续失败，已停止自动续讲以免反复请求") }
                }
            } },
        )
        audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun privateCallOutput(): Boolean = when (CallAudioRoute.preferredOutput?.type) {
        android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        android.media.AudioDeviceInfo.TYPE_HEARING_AID -> true
        else -> false
    }

    /** User speech can supersede an unfinished reply; only confirmed mic
     * callbacks reach here. Already delivered audio stays in the timeline.
     */
    private fun interruptSystemReplyForSpeech(confirmedCloudSpeech: Boolean = false) {
        val state = mutableState.value
        if (confirmedCloudSpeech) {
            if (!PhoneBargeInPolicy.shouldSupersedeUnplayedReply(
                    state.thinking, state.speaking, speechQueue?.hasPendingAudio == true)) return
        } else if (!SystemSpeechTurnPolicy.shouldInterrupt(
                detectedUserSpeech = true,
                thinking = state.thinking,
                speakingOrPending = state.speaking || speechQueue?.hasPendingAudio == true,
                privateAudioOutput = privateCallOutput(),
            )) return
        replyGeneration++
        replyJob?.cancel()
        replyJob = null
        subtitleRevealJob?.cancel()
        cancelPendingCharacterHangup()
        speechQueue?.stop()
        replyIsSilence = false
        silenceAudioPending = false
        mutableState.update {
            it.copy(thinking = false, speaking = false, opening = false,
                generatedTranscript = "", playingTranscript = "",
                statusMessage = "听到你继续说，正在听你说完")
        }
    }

    private fun ensureRecognizer() {
        val context = appContext ?: return
        if (recognizer != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speechRecognizer.also { speechRecognizer ->
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (!mutableState.value.connected || mutableState.value.opening ||
                        mutableState.value.microphoneMuted) return
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
                    if (!call.connected || call.opening || call.microphoneMuted) return
                    if ((call.speaking || speechQueue?.hasPendingAudio == true) && !privateCallOutput()) return
                    // Keep the microphone on the new phrase; defer playback
                    // interruption until an actual partial/final transcript is
                    // received. A noise-only beginning must not cancel the reply.
                    // Noise can trigger onBeginningOfSpeech. Do not discard
                    // recognized text or its pending commit until real words
                    // appear in a partial/final transcript.
                    systemPartialCandidate = ""
                    systemSpeechConfirmed = false
                    userSpeechInProgress = true
                    // A fan can trip Android's onBeginningOfSpeech. Do not
                    // block sleep-mode or interrupt TTS before actual words.
                    mutableState.update { it.copy(userSpeaking = false, statusMessage = "收音中，等待确认语音…") }
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (!mutableState.value.connected || mutableState.value.opening ||
                        mutableState.value.microphoneMuted) return
                    userSpeechInProgress = false
                    // Keep confirmation until onResults/onError: Android emits
                    // onEndOfSpeech BEFORE its final result and sometimes errors
                    // instead. Clearing it here discarded valid partial words.
                    mutableState.update { it.copy(userSpeaking = false, listening = false, statusMessage = "正在识别你的语音…") }
                    if (systemUtteranceBuffer.isBlank() && systemPartialCandidate.isBlank())
                        scheduleSleepContinuation()
                }

                override fun onError(error: Int) {
                    if (!mutableState.value.connected || mutableState.value.opening ||
                        mutableState.value.microphoneMuted) return
                    val confirmedBeforeError = systemSpeechConfirmed
                    userSpeechInProgress = false
                    systemSpeechConfirmed = false
                    recognitionActive = false
                    mutableState.update { it.copy(userSpeaking = false) }
                    // An Android recognizer can error at a short pause even
                    // after it has already provided words. Retain those words
                    // and reopen the mic instead of silently throwing them away.
                    val partialCandidate = systemPartialCandidate.trim()
                    if (systemUtteranceBuffer.isBlank() &&
                        SystemSpeechTurnPolicy.canKeepPartialOnError(confirmedBeforeError, partialCandidate) &&
                        error !in setOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                            SpeechRecognizer.ERROR_CLIENT)) {
                        systemUtteranceBuffer = partialCandidate
                    }
                    val hasBufferedSpeech = systemUtteranceBuffer.isNotBlank()
                    if (hasBufferedSpeech && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        systemPartialCandidate = ""
                        mutableState.update {
                            it.copy(
                                listening = false,
                                partialTranscript = systemUtteranceBuffer,
                                statusMessage = "已经记下前面的话，你可以接着说…",
                            )
                        }
                        scheduleSystemUtteranceCommit(SystemSpeechTurnPolicy.continuationMillis(systemUtteranceBuffer))
                        scheduleListening(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 650 else 240)
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
                    if (!call.connected || call.opening || call.microphoneMuted) return
                    // The user may have continued talking while the character
                    // was preparing audio. Do not throw those recognized words away.
                    if ((call.speaking || speechQueue?.hasPendingAudio == true) && !privateCallOutput()) {
                        userSpeechInProgress = false
                        recognitionActive = false
                        scheduleListening(250)
                        return
                    }
                    userSpeechInProgress = false
                    systemSpeechConfirmed = false
                    recognitionActive = false
                    mutableState.update { it.copy(userSpeaking = false) }
                    val finals = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()
                        .map(String::trim)
                        .filter(String::isNotBlank)
                    val primary = finals.firstOrNull().orEmpty()
                    val partial = systemPartialCandidate.trim()
                    val spoken = SystemSpeechTurnPolicy.bestFinal(partial, primary)
                    systemPartialCandidate = ""
                    if (spoken.isNotBlank()) interruptSystemReplyForSpeech()
                    if (spoken.isBlank()) {
                        mutableState.update { it.copy(listening = false, partialTranscript = systemUtteranceBuffer) }
                        if (systemUtteranceBuffer.isBlank()) scheduleSleepContinuation()
                        else scheduleSystemUtteranceCommit(SystemSpeechTurnPolicy.continuationMillis(systemUtteranceBuffer))
                        scheduleListening(240)
                    } else {
                        systemUtteranceBuffer = PhoneTranscriptAssembler.combine(systemUtteranceBuffer, spoken).trim()
                        mutableState.update {
                            it.copy(
                                listening = false,
                                partialTranscript = systemUtteranceBuffer,
                                statusMessage = "听到了，你还可以继续说",
                            )
                        }
                        scheduleSystemUtteranceCommit(SystemSpeechTurnPolicy.continuationMillis(systemUtteranceBuffer))
                        // A new recognizer window must open while the previous
                        // recognized text remains buffered. Otherwise the user
                        // cannot keep talking after the device's early endpoint.
                        scheduleListening(240)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val call = mutableState.value
                    if (!call.connected || call.opening || call.microphoneMuted ||
                        ((call.speaking || speechQueue?.hasPendingAudio == true) && !privateCallOutput())) return
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    systemPartialCandidate = SystemSpeechTurnPolicy.bestPartial(systemPartialCandidate, partial)
                    if (systemPartialCandidate.length >= 2) {
                        // Keep earlier recognized bubbles available until an
                        // actual new phrase is confirmed, not merely room noise.
                        systemCommitJob?.cancel()
                        systemCommitJob = null
                        if (!systemSpeechConfirmed) {
                            systemSpeechConfirmed = true
                            noteCallUserActivity()
                            sleepContinuationJob?.cancel()
                        }
                        interruptSystemReplyForSpeech()
                    }
                    mutableState.update {
                        it.copy(partialTranscript = PhoneTranscriptAssembler.combine(
                            systemUtteranceBuffer, systemPartialCandidate))
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
        // Android often ends recognition after a short breath. Reopen the mic
        // even when a previous final transcript is waiting for more words.
        // Only a new *actual speech onset* cancels the pending commit.
        if (!SystemSpeechTurnPolicy.canListen(
                connected = current.connected,
                muted = current.microphoneMuted,
                opening = current.opening,
                thinking = current.thinking,
                speakingOrPending = current.speaking || speechQueue?.hasPendingAudio == true,
                privateAudioOutput = privateCallOutput(),
            ) || recognitionActive || realtime != null || providerInput != null) return
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
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_400L)
                // These are hints to the installed Android recognizer service,
                // not guarantees. Longer windows reduce cuts at natural breaths.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_600L)
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

    private fun scheduleSystemUtteranceCommit(
        delayMillis: Long = SystemSpeechTurnPolicy.continuationMillis(systemUtteranceBuffer),
    ) {
        systemCommitJob?.cancel()
        val sessionId = mutableState.value.callExperienceId
        systemCommitJob = scope.launch {
            delay(delayMillis)
            val current = mutableState.value
            if (!current.connected || current.callExperienceId != sessionId ||
                current.thinking || current.speaking || current.opening || confirmedSpeechInProgress()) {
                // Retain the draft rather than silently losing an already
                // recognized utterance when another state briefly won the race.
                return@launch
            }
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
        systemSpeechConfirmed = false
    }

    private fun startOpening() {
        val current = mutableState.value
        if (!current.connected || !openingTurn.claim(current.callExperienceId)) return
        mutableState.update { it.copy(opening = true, listening = false, statusMessage = "${it.characterName} 正要开口") }
        InteractionSignalBridge.recordCallStart(current.characterId, current.callExperienceId, current.incomingReason)
        handleUserSpeech("", opening = true)
    }

    private fun handleUserSpeech(
        spoken: String,
        opening: Boolean = false,
        allowWhileMuted: Boolean = false,
        alreadyPersisted: Boolean = false,
        autonomousSleep: Boolean = false,
        autonomousSilence: Boolean = false,
    ) {
        val current = mutableState.value
        if (!current.connected || (realtime != null && !autonomousSilence) ||
            (!opening && !autonomousSleep && !autonomousSilence && ((current.microphoneMuted && !allowWhileMuted) || current.opening)) ||
            (autonomousSleep && (!current.sleepMode || confirmedSpeechInProgress() || current.thinking ||
                current.speaking || speechQueue?.hasPendingAudio == true || current.opening))) return
        if (autonomousSilence && (current.sleepMode || userSpeechInProgress || current.thinking ||
            current.speaking || speechQueue?.hasPendingAudio == true || current.opening)) return
        sleepContinuationJob?.cancel()
        if (!autonomousSleep && !autonomousSilence) {
            userSpeechInProgress = false
            if (spoken.isNotBlank() && !opening) {
                noteCallUserActivity()
                lastConfirmedUserSpeechMillis = SystemClock.elapsedRealtime()
                if (!alreadyPersisted) confirmedUserSpeechCount++
            }
        }
        if (current.sleepMode && !opening && !autonomousSleep && !autonomousSilence) {
            SleepGuidanceGuide.requestedFocus(spoken)?.let { focus ->
                mutableState.update { it.copy(sleepFocus = focus) }
            }
        }
        replyGeneration++
        cancelPendingCharacterHangup()
        subtitleRevealJob?.cancel()
        speechQueue?.stop()
        val generation = replyGeneration
        val phoneXhsRequest = if (!opening && !autonomousSleep && !autonomousSilence &&
            spoken.isNotBlank() && realtime == null)
            com.jiacimu.lulu.data.XhsExplicitReadIntent.parse(spoken) else null
        val xhsDispatched = if (phoneXhsRequest != null) {
            val key = phoneXhsRequest.url.ifBlank { "keyword:" + phoneXhsRequest.keyword }
            val now = SystemClock.elapsedRealtime()
            if (lastPhoneXhsRequest == key && now - lastPhoneXhsRequestAt in 0L..18_000L) false
            else {
                lastPhoneXhsRequest = key
                lastPhoneXhsRequestAt = now
                val app = appContext
                if (app != null) scope.launch(Dispatchers.IO) {
                    // Never hold the microphone, voice generation or interruption
                    // loop while a real WebView search waits on website navigation.
                    val args = org.json.JSONObject()
                    if (phoneXhsRequest.url.isNotBlank()) args.put("url", phoneXhsRequest.url)
                    else args.put("keyword", phoneXhsRequest.keyword)
                    val raw = com.jiacimu.lulu.data.ToolRouter.execute(
                        app, current.characterId, "read_xiaohongshu", args,
                        requestId = "call-xhs-" + current.callExperienceId + "-" + generation,
                        userRequested = true,
                    )
                    val result = runCatching { org.json.JSONObject(raw) }.getOrNull()
                    val success = result?.optBoolean("success") == true
                    val detail = result?.optString("summary").orEmpty()
                        .ifBlank { result?.optString("error").orEmpty() }
                        .replace(Regex("""\s+"""), " ").trim().take(160)
                        .ifBlank { if (success) "已取得真实阅读结果" else "本次网页没有返回可验证的结果" }
                    // Leave ONE compact factual note, not a fake character
                    // performance or a verbose raw JSON receipt.
                    MigratedDomainStores.chat.appendSystemMessage(
                        current.conversationId,
                        if (success) "小红书 · 实际已读：$detail"
                        else "小红书 · 本次未读到：$detail",
                    )
                }
                app != null
            }
        } else false
        replyJob?.cancel()
        replyIsSilence = autonomousSilence
        silenceAudioPending = false
        if (!autonomousSilence && providerInput == null) pauseRecognition()
        if (!opening && !autonomousSleep && !autonomousSilence) {
            resetSystemRecognitionTurn()
            if (!alreadyPersisted) MigratedDomainStores.chat.appendVoiceMessage(current.conversationId,
                "voice-${current.callExperienceId}-user-$generation", spoken, false)
        }
        mutableState.update { it.copy(thinking = true, userSpeaking = false, opening = opening, partialTranscript = "", errorMessage = "", statusMessage = if (autonomousSilence) "${current.characterName} 正陪着你" else "${current.characterName} 正在想怎么回答") }
        // Keep a second recognition window alive while the model thinks.
        // The user may continue or correct what they just said without
        // waiting for the agent to generate the next line.
        if (!opening && !autonomousSleep && !autonomousSilence &&
            !current.microphoneMuted && providerInput == null && realtime == null) {
            scheduleListening(220L)
        }
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
            val wholeTurnSpeech = latest.sleepMode ||
                appContext?.let { VoicePerformance.phoneNeedsWholeTurn(it) } == true ||
                com.jiacimu.lulu.data.RelationshipExpressionGuard.requiresBufferedSpeech(latest.characterId)
            val heard = StringBuilder()
            var finalText: String? = null
            var interactionReceipt: String? = null
            var bedtimeContinuation: String? = null
            var lastDeliveredMessageId = ""
            fun sameReply() = generation == replyGeneration && mutableState.value.connected &&
                mutableState.value.callExperienceId == latest.callExperienceId
            fun clearWhenHeard() {
                val complete = finalText ?: return
                if (heard.toString().filterNot(Char::isWhitespace) == complete.filterNot(Char::isWhitespace)) {
                    com.jiacimu.lulu.data.CharacterConversationAgency.delivered(latest.characterId,
                        interactionReceipt, lastDeliveredMessageId, heard.toString(), phone = true)
                    if (latest.sleepMode) {
                        SleepNarrativeStore.accept(latest.callExperienceId, lastDeliveredMessageId, bedtimeContinuation)
                        val kind = bedtimeContinuation?.let { runCatching { org.json.JSONObject(it).optString("kind") }.getOrNull() }
                        if (mutableState.value.sleepFocus == SleepGuidanceFocus.Natural) {
                            if (kind == "story") mutableState.update { it.copy(sleepFocus = SleepGuidanceFocus.Story) }
                            else if (kind == "body") mutableState.update { it.copy(sleepFocus = SleepGuidanceFocus.Body) }
                        }
                    }
                    mutableState.update { it.copy(generatedTranscript = "", playingTranscript = "") }
                }
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
                                // Retain all audible segments, not only the last bubble.
                                // STT echo for an earlier sentence can arrive during
                                // later TTS (or seconds after that TTS ends).
                                recentlyPlayedAgentText =
                                    (recentlyPlayedAgentText + " " + plainSpeech).takeLast(700).trim()
                                recentlyPlayedAgentAtMillis = SystemClock.elapsedRealtime()
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
                                autonomousHangup.markDelivered(latest.callExperienceId, generation, plainSpeech)
                                lastDeliveredMessageId = voiceMessageId
                                if (latest.sleepMode) SleepNarrativeStore.recordPlayed(latest.callExperienceId,
                                    voiceMessageId, plainSpeech)
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
            ) + "\n" + SleepNarrativeStore.context(latest.callExperienceId, latest.sleepFocus) else ""
            val silenceContext = if (autonomousSilence) CallSilencePolicy.context(
                SystemClock.elapsedRealtime() - lastConfirmedUserSpeechMillis, latest.microphoneMuted) else ""
            // Stable milestones: silence can be reconsidered without inventing
            // a brand-new external event on every timer pulse.
            val observationId = if (autonomousSilence)
                "call-silence-${latest.callExperienceId}-${confirmedUserSpeechCount}-${CallSilencePolicy.observationMilestone(
                    (SystemClock.elapsedRealtime() - lastConfirmedUserSpeechMillis).coerceAtLeast(0L)
                )}" else ""
            if (autonomousSilence) InteractionSignalBridge.recordCallQuiet(
                characterId = latest.characterId,
                callId = latest.callExperienceId,
                observationId = observationId,
                secondsSinceUserActivity = (SystemClock.elapsedRealtime() - lastConfirmedUserSpeechMillis).coerceAtLeast(0L) / 1000L,
                microphoneMuted = latest.microphoneMuted,
            )
            LuluDeviceToolBridge.respond(
                characterId = latest.characterId,
                history = recentHistory,
                userText = if (opening || autonomousSleep || autonomousSilence) "" else spoken,
                title = activeLabel,
                archiveId = archiveId,
                silenceObservationId = observationId,
                turnContext = if (opening) "真实事件：本通电话刚接通；来电缘由=${latest.incomingReason}。这不是用户说的话。" else "",
                sceneContext = (when {
                    autonomousSilence -> silenceContext
                    autonomousSleep -> """
                        【哄睡长段续讲】一次输出完整连续的一段，不要分多次重复开头或总结。
                        这是同一通电话的哄睡后续。用户没有新增发言，这只是系统续讲事件，不能当作用户说话或催促。
                        $bedtimeGuide
                        自然接着上次真正说过的内容继续，不断切换技巧或从头开场都没有必要。说完这一段让电话程序安排后续；不要反复索要回应或制造新的世界事件。
                    """.trimIndent()
                    opening && latest.sleepMode -> "哄睡电话刚刚接通，用户尚未开口。你先轻声自然开场，不催促她说话，不假装她回答。\n$bedtimeGuide"
                    opening -> "你正在和用户进行一对一实时电话，刚刚接通，用户尚未开口。现在由你按自己的关系、人设和最近上下文先说一两句自然开场。不要把通话事件当作用户说过的话，不要朗读事件说明。"
                    latest.sleepMode -> "这是哄睡陪伴电话。用户刚刚说了话，要先认真回应她，再自然选择引导或陪伴方式。用户可以安静只听，别催促答话。\n$bedtimeGuide"
                    alreadyPersisted -> "你正在与用户通话，用户此刻没有通过麦克风说这段话，而是在同一个人的私聊窗口打字发给你。你确实能看见这些文字，并且可以在电话里自然开口回应；不要假装从麦克风听见了打字声。始终沿用这个角色的稳定人格、真实经历、关系称呼与连续状态，只根据电话这个媒介调整表达节奏。不要重复发一条独立的私聊回复。"
                    else -> "你正在和用户进行一对一实时电话。你能意识到电话已经接通，听见的是用户刚刚在电话里说的话；具体关系与称呼必须服从你的人设。回复要像真实通话，口语自然，别像客服确认工单。先理解用户这段话的重点、情绪和说话是否已经结束，再依角色自己的个性、关系和兴趣自然回应。简单的事可以轻快接话，甚至只哼一声、笑一下、接个梗；值得深谈的事可以认真讲清。可以自然主动提起自己真实经历的一件小事或先前没说完的念头，但它必须与此刻有关系，不把每次通话做成主动生活报告。不要固定每轮几句话，不要机械复述、每句都追问或习惯性附和。角色可以有自己的判断、幽默、沉默与不同意见，但不得编造已经发生的事情。不要朗读说明文字。"
                }) + if (xhsDispatched)
                    "\n【真实异步动作】已经向本机的小红书只读执行器派发本轮检索；无需暂停电话或等网页。现在可以自然说‘我去看看’，真正结果稍后写入私聊。尚未拿到执行回执，绝不能说已搜到，更不能说没有注册这个工具。"
                else "",
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
                val text = com.jiacimu.lulu.data.RelationshipExpressionGuard.apply(latest.characterId, reply.text)
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
                interactionReceipt = reply.interactionReceipt
                bedtimeContinuation = reply.bedtimeContinuation
                finalText = VoicePerformance.plain(text)
                clearWhenHeard()
                val remaining = runCatching {
                    // Validate the final speakable text without sending internal JSON to TTS.
                    // Dialogue-capable voices get one request for the entire emotional arc.
                    stream.finishForSpeech(text, wholeTurnSpeech)
                }.getOrElse { error ->
                    stream.cancel()
                    speechQueue?.stop()
                    mutableState.update { it.copy(thinking = false, opening = false, speaking = false, generatedTranscript = "", playingTranscript = "",
                        statusMessage = "回复未完整生成，可以继续说话",
                        errorMessage = "回复没生成完整，继续说话或重试即可") }
                    scheduleListening(300)
                    if (autonomousSleep && ++sleepFailures >= 3) {
                        mutableState.update { it.copy(sleepMode = false, statusMessage = "哄睡续讲解析连续失败，已暂停") }
                    } else scheduleSleepContinuation()
                    return@onSuccess
                }
                // A character who explicitly says "那我挂了" has committed to
                // an action, even if the planner omitted the JSON endCall flag.
                // The gate still requires successfully delivered TTS and idle
                // audio; farewell jokes, quotes and ordinary goodbyes do nothing.
                if (!autonomousSleep && CallSpokenHangupPolicy.explicitlyEndsOwnCall(finalText.orEmpty())) {
                    autonomousHangup.request(latest.callExperienceId, generation)
                }
                autonomousHangup.expectSpoken(latest.callExperienceId, generation, finalText.orEmpty())
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
                    // Silent reflection can fail without the actual call failing.
                    // Keep diagnostics in presence history, not a red error banner.
                    mutableState.update { it.copy(thinking = false,
                        statusMessage = "仍在通话中", errorMessage = "") }
                    return@onFailure
                }
                stream.cancel()
                cancelPendingCharacterHangup()
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
        cloudCaptureTurnAllowed = false
        val id = mutableState.value.callExperienceId
        fun sameSession() = mutableState.value.callExperienceId == id && mutableState.value.phase in setOf(CallPhase.Dialing, CallPhase.Connected)
        val selectedSttEngine = CallVoiceConfiguration.sttEngine(context)
        val selectedElevenPath = CallVoiceConfiguration.elevenInputPath(
            selectedSttEngine,
            context.getSharedPreferences("lulu_advanced_settings", 0)
                .getString("eleven_asr_model", null),
        )
        val scribeRealtime =
            selectedElevenPath == CallVoiceConfiguration.ElevenInputPath.CONTINUOUS_REALTIME
        providerInput = ProviderCallInput(context, scope,
            sttEngine = selectedSttEngine,
            elevenPath = selectedElevenPath,
            // Listen throughout model/TTS preparation. Audible playback owns
            // its turn on all output routes, as requested by the user.
            accept = {
                val call = mutableState.value
                val characterAudio = call.speaking
                sameSession() && PhoneBargeInPolicy.shouldCapture(
                    microphoneMuted = call.microphoneMuted,
                    playbackBusy = characterAudio,
                    privateOutput = privateCallOutput(),
                )
            },
            onReady = {
                if (sameSession()) {
                    if (!mutableState.value.microphoneMuted) audioRoute?.refresh()
                    val wasConnected = mutableState.value.connected
                    mutableState.update { it.copy(phase = CallPhase.Connected, callStartedAt = it.callStartedAt ?: Instant.now(), everConnected = true,
                        listening = !it.speaking && !it.microphoneMuted,
                        inputMeterAvailable = true,
                        errorMessage = "", statusMessage = if (it.thinking) it.statusMessage
                            else if (scribeRealtime) "Scribe 实时转写已就绪，可以直接说话"
                            else "麦克风已就绪，可以直接说话，停顿后自动转写") }
                    if (!wasConnected) { startTimer(); startOpening() }
                }
            },
            onLevel = { level -> if (sameSession()) mutableState.update { it.copy(inputLevel = level) } },
            onSpeech = {
                if (sameSession()) {
                    userSpeechInProgress = true
                    cloudCaptureTurnAllowed = PhoneBargeInPolicy.shouldCapture(
                        mutableState.value.microphoneMuted,
                        mutableState.value.speaking,
                        privateCallOutput(),
                    )
                    if (!providerSpeechConfirmed) providerSpeechStartedMillis = SystemClock.elapsedRealtime()
                    // VAD alone may be static/noise; wait for real STT text.
                    // Do not cancel a real in-flight model reply merely because
                    // room noise tripped VAD. Confirm a new user utterance from
                    // STT before replacing the previous reply.
                    mutableState.update { it.copy(listening = !it.speaking, errorMessage = "",
                        statusMessage = if (it.thinking) it.statusMessage else "正在听你说话") }
                }
            },
            onCaptureFinished = {
                if (sameSession()) {
                    // VAD's final PCM chunk is a finished user turn even if cloud
                    // recognition fails to return usable words afterward.
                    userSpeechInProgress = false
                    providerSpeechStartedMillis = 0L
                    if (scribeRealtime) providerRecognitionPendingUntilMillis = SystemClock.elapsedRealtime() + 5_000L
                    mutableState.update { it.copy(userSpeaking = false) }
                }
            },
            onPartial = { text -> if (sameSession()) {
                val candidate = text.trim()
                val isEcho = isPlaybackEchoFromCloud(candidate)
                val call = mutableState.value
                val characterAudio = call.speaking
                val previouslyCapturedBatch = !scribeRealtime &&
                    !userSpeechInProgress &&
                    providerRecognitionPendingUntilMillis > SystemClock.elapsedRealtime()
                val safeCapture = cloudCaptureTurnAllowed && !call.microphoneMuted &&
                    (PhoneBargeInPolicy.shouldCapture(false, characterAudio, privateCallOutput()) ||
                        previouslyCapturedBatch)
                // A tentative STT partial is not a human speaker identifier.
                // Never interrupt loudspeaker output or publish its tentative
                // words as the user's speech. Pending synthesis is interruptible;
                // already audible speech is not. Final text is gated again.
                if (candidate.isNotBlank() && !isEcho && safeCapture &&
                    PhoneBargeInPolicy.safePartialBargeIn(
                        candidate, privateCallOutput(), characterAudio)) {
                    if (!providerSpeechConfirmed) {
                        providerSpeechConfirmed = true
                        noteCallUserActivity()
                        sleepContinuationJob?.cancel()
                    }
                    interruptSystemReplyForSpeech(confirmedCloudSpeech = true)
                }
                if (!isEcho && safeCapture) mutableState.update {
                    it.copy(partialTranscript = text, userSpeaking = candidate.isNotBlank())
                }
            } },
            onText = { text ->
                if (sameSession()) {
                    val isEcho = isPlaybackEchoFromCloud(text)
                    val call = mutableState.value
                    val characterAudio = call.speaking
                    val previouslyCapturedBatch = !scribeRealtime &&
                        !userSpeechInProgress &&
                        providerRecognitionPendingUntilMillis > SystemClock.elapsedRealtime()
                    val safeCapture = cloudCaptureTurnAllowed && !call.microphoneMuted &&
                        (PhoneBargeInPolicy.shouldCapture(false, characterAudio, privateCallOutput()) ||
                            previouslyCapturedBatch)
                    cloudCaptureTurnAllowed = false
                    userSpeechInProgress = false
                    providerSpeechConfirmed = false
                    providerSpeechStartedMillis = 0L
                    providerRecognitionPendingUntilMillis = 0L
                    mutableState.update { it.copy(userSpeaking = false) }
                    // Check BEFORE calling handleUserSpeech: it writes sender=User
                    // and begins another model reply. Speaker TTS must never
                    // manufacture one of those turns.
                    if (text.isNotBlank() && !isEcho && safeCapture) {
                        interruptSystemReplyForSpeech(confirmedCloudSpeech = true)
                        handleUserSpeech(text, allowWhileMuted = true)
                    }
                    if (isEcho || !safeCapture) mutableState.update {
                        it.copy(partialTranscript = "", userSpeaking = false)
                    }
                }
            },
            onStatus = { note -> if (sameSession()) {
                val captureFinished = note.contains("识别完整语句")
                if (captureFinished) {
                    // Capture has stopped, but cloud recognition is still in flight.
                    // These are separate states; never leave the mic shown as
                    // "still speaking" while waiting for an STT network response.
                    userSpeechInProgress = false
                    providerSpeechStartedMillis = 0L
                    providerRecognitionPendingUntilMillis = SystemClock.elapsedRealtime() + 90_000L
                }
                mutableState.update { it.copy(
                    userSpeaking = if (captureFinished) false else it.userSpeaking,
                    statusMessage = if (it.thinking) it.statusMessage else note,
                ) }
            } },
            onError = { error -> if (sameSession()) {
                userSpeechInProgress = false
                cloudCaptureTurnAllowed = false
                providerSpeechConfirmed = false
                providerSpeechStartedMillis = 0L
                providerRecognitionPendingUntilMillis = 0L
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
        cancelPendingCharacterHangup()
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
        systemSpeechConfirmed = false
        providerSpeechConfirmed = false
        lastConfirmedUserSpeechMillis = startedAt
        confirmedUserSpeechCount = 0
        providerSpeechStartedMillis = 0L
        providerRecognitionPendingUntilMillis = 0L
        lastCallAudioMillis = startedAt
        recentlyPlayedAgentText = ""
        recentlyPlayedAgentAtMillis = 0L
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
                        userSpeaking = confirmedSpeechInProgress(),
                        audioInProgress = current.speaking || speechQueue?.hasPendingAudio == true,
                    )) {
                    endSleepCallOnSilence()
                    break
                }
                val clock = SystemClock.elapsedRealtime()
                if (CallSilencePolicy.shouldRecoverStuckCapture(
                        providerActive = providerInput?.usesServerVad == false &&
                            !current.microphoneMuted,
                        userSpeechInProgress = userSpeechInProgress,
                        sinceSpeechStartedMillis = clock - providerSpeechStartedMillis,
                    )) {
                    // If noise prevents the mic from ever reporting "speech end",
                    // submit the already captured PCM and reopen a clean microphone.
                    // This also gives a long uninterrupted monologue a recoverable
                    // chunk boundary rather than losing every word.
                    providerSpeechStartedMillis = clock
                    providerInput?.pauseCapture()
                    scope.launch {
                        delay(500L)
                        if (mutableState.value.connected && !mutableState.value.microphoneMuted &&
                            !mutableState.value.thinking && !mutableState.value.speaking) {
                            providerInput?.resumeCapture()
                        }
                    }
                    mutableState.update { it.copy(statusMessage = "连续收音较长，正在提交语音识别…") }
                }
                if (providerRecognitionPendingUntilMillis != 0L &&
                    clock >= providerRecognitionPendingUntilMillis) {
                    // A cloud STT timeout must not suppress every later life pulse.
                    providerRecognitionPendingUntilMillis = 0L
                    userSpeechInProgress = false
                    mutableState.update { it.copy(userSpeaking = false,
                        statusMessage = "语音识别尚未返回，可继续讲话或重试收音") }
                }
                if (CallSilencePolicy.shouldReflect(current.connected, current.sleepMode,
                        current.thinking || current.speaking || current.opening ||
                            speechQueue?.hasPendingAudio == true ||
                            providerRecognitionPendingUntilMillis > clock,
                        confirmedSpeechInProgress(),
                        clock - lastConfirmedUserSpeechMillis,
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
