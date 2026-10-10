package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.*
import io.elevenlabs.*
import io.elevenlabs.models.ConversationMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import java.time.Instant

/** Uses the official WebRTC microphone, echo cancellation and interruption pipeline. */
internal class RealtimeVoiceAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onConnected: () -> Unit,
    private val onState: (listening: Boolean, speaking: Boolean, note: String) -> Unit,
    private val onUserSpeech: () -> Unit,
    private val onCandidate: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var session: ConversationSession? = null
    private var definitionJob: Job? = null
    private var contextVersion = 0L
    private var generation = 0L
    private var characterId = ""
    private var conversationId = ""
    private var providerSessionId = ""
    private val delivery = VoiceDeliveryLedger()
    private var deliveryEpoch = 0L
    private var playedAudio = false
    private var speaking = false
    private var volumeEnabled = true
    private var microphoneMuted = false
    private var openingWaiting = false
    private var openingEventText = ""
    private val controlEvents = mutableSetOf<String>()
    private var lastUserActivity = 0L
    private var suppressDelivery = false
    private var deliveryJob: Job? = null
    private var openingJob: Job? = null

    suspend fun start(characterId: String, conversationId: String, openingPrompt: String = CallOpeningTurn.prompt()) {
        stop()
        microphoneMuted = false
        openingWaiting = true
        openingEventText = openingPrompt
        controlEvents.clear()
        controlEvents += openingPrompt
        this.characterId = characterId
        this.conversationId = conversationId
        val epoch = ++generation
        deliveryEpoch = delivery.reset()
        val core = coreSnapshot(characterId)
        val auth = CloudTaskBridge.request("/v1/voice/session", core)
        if (epoch != generation) return
        val config = ConversationConfig(
            conversationToken = auth.getString("conversationToken"),
            userId = characterId,
            customLlmExtraBody = mapOf("sessionKey" to auth.getString("sessionKey")),
            overrides = CharacterVoicePreferenceStore.realtimeVoiceId(characterId)?.let { Overrides(tts = TtsOverrides(voiceId = it)) },
            onConnect = { id -> if (epoch == generation) { providerSessionId = id; onConnected() } },
            onModeChange = { mode -> if (epoch == generation) {
                speaking = mode == ConversationMode.SPEAKING
                AvatarController.listening(characterId, !speaking)
                if (!speaking && openingWaiting && playedAudio) {
                    openingWaiting = false
                    openingJob?.cancel()
                    scope.launch { session?.setMicMuted(microphoneMuted) }
                }
                onState(!speaking && !openingWaiting, speaking,
                    if (speaking) "正在说话，你可以插话" else if (openingWaiting) "正在准备开场" else "正在听你说话")
                if (speaking) { playedAudio = false; suppressDelivery = !volumeEnabled; deliveryJob?.cancel() }
                else if (playedAudio && volumeEnabled) {
                    // Allow correction/interruption events to arrive before committing completed speech.
                    deliveryJob?.cancel()
                    deliveryJob = scope.launch {
                        delay(600)
                        if (epoch == generation && !speaking) {
                            delivery.played(deliveryEpoch).forEach { (id, text) -> persistAgent(id, text) }
                            onCandidate("")
                            refreshCore(epoch)
                        }
                    }
                }
            } },
            onAudioLevelChanged = { level -> if (epoch == generation) {
                if (level > 0.01f && volumeEnabled) playedAudio = true
                AvatarController.audio(characterId, level)
            } },
            onUserTranscriptEvent = { text, eventId -> if (epoch == generation && text.isNotBlank() && eventId != null && text !in controlEvents) {
                lastUserActivity++
                onUserSpeech()
                MigratedDomainStores.chat.appendVoiceMessage(conversationId, "voice-$providerSessionId-user-$eventId-$characterId", text, false)
                scope.launch { refreshCore(epoch) }
            } },
            onTentativeUserTranscriptEvent = { text, _ -> if (epoch == generation && text.isNotBlank() && text !in controlEvents) {
                lastUserActivity++
                onUserSpeech()
                onState(true, speaking, "听到：$text")
            } },
            onAgentResponseEvent = { text, eventId -> if (epoch == generation && eventId != null) {
                if (volumeEnabled && !suppressDelivery) delivery.generated(deliveryEpoch, eventId, text)
                else delivery.interrupt(deliveryEpoch, eventId)
                onCandidate(text)
                // Generated text is a subtitle candidate; it is not yet a spoken experience.
            } },
            onInterruption = { eventId -> if (epoch == generation) {
                deliveryJob?.cancel()
                delivery.interrupt(deliveryEpoch, eventId)
                onCandidate("")
                onState(true, false, "已停止旧回复，正在听你说话")
            } },
            onAgentResponseCorrectionEvent = { text, eventId -> if (epoch == generation && eventId != null) {
                delivery.corrected(deliveryEpoch, eventId, text)
                // The provider's corrected transcript describes the actually delivered prefix.
                if (volumeEnabled && !suppressDelivery) {
                    if (text.isNotBlank()) persistAgent(eventId, text)
                    else MigratedDomainStores.chat.deleteMessage("voice-$providerSessionId-agent-$eventId-$characterId")
                }
                scope.launch { refreshCore(epoch) }
            } },
            onUnhandledClientToolCall = { call -> if (epoch == generation) scope.launch {
                val result = ToolRouter.execute(context, characterId, call.toolName, JSONObject(call.parameters),
                    requestId = "voice-$providerSessionId-${call.toolCallId}", userRequested = false)
                // Actions already accepted persist even if the user interrupts the spoken response.
                if (epoch == generation) {
                    session?.sendToolResult(call.toolCallId, result, !JSONObject(result).optBoolean("success"))
                    refreshCore(epoch)
                }
            } },
            onError = { _, message -> if (epoch == generation) onError(message ?: "实时语音服务失败，请重试") },
            onDisconnect = { _ -> if (epoch == generation) { definitionJob?.cancel(); deliveryJob?.cancel(); delivery.reset(); onError("实时通话已断开") } },
        )
        val connected = ConversationClient.startSession(config, context)
        if (epoch == generation) VoiceUsageAudit.record(context, "ElevenLabs", "agent_call_connected", 0, "agent_realtime")
        if (epoch != generation) connected.endSession() else {
            session = connected
            connected.setMicMuted(microphoneMuted || openingWaiting)
            connected.setVolume(1f)
            // The configured Agent has no independent first_message. Trigger the shared core once;
            // this event is not persisted as something the user said.
            connected.sendUserMessage(openingPrompt)
            openingJob = scope.launch {
                delay(30_000)
                if (epoch == generation && openingWaiting) {
                    openingWaiting = false
                    connected.setMicMuted(microphoneMuted)
                    onError("开场语音未能播放，可以重新接通电话")
                    onState(!microphoneMuted, false, "开场语音未能播放，可以重新接通电话")
                }
            }
            definitionJob = scope.launch {
                CharacterRuntime.definitionChanges(characterId).collectLatest { refreshCore(epoch) }
            }
        }
    }

    /** This is a system companionship event, never a fabricated user utterance. */
    suspend fun continueFromSilence(text: String) {
        val connected = session ?: return
        val epoch = generation
        val userActivity = lastUserActivity
        if (speaking || openingWaiting || text.isBlank()) return
        refreshCore(epoch)
        if (epoch != generation || userActivity != lastUserActivity || speaking || openingWaiting) return
        val event = "[电话安静陪伴事件；用户没有新增发言] 你刚才自主决定表达：$text。只自然说出这份意思，不朗读事件说明，不假装用户刚问了问题。"
        controlEvents += event
        connected.sendUserMessage(event)
    }

    fun mute(muted: Boolean) { microphoneMuted = muted; scope.launch { session?.setMicMuted(muted || openingWaiting) } }
    fun speaker(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        // Speaker toggle chooses the Android audio route (speaker vs receiver/headset),
        // it is NOT a silence control. Previously "earpiece" used setVolume(0) while
        // ElevenLabs kept generating chargeable speech and silently discarded transcripts.
        volumeEnabled = true
        suppressDelivery = false
        session?.setVolume(1f)
    }
    fun stop() {
        generation++
        definitionJob?.cancel()
        definitionJob = null
        openingJob?.cancel()
        openingJob = null
        deliveryJob?.cancel()
        deliveryEpoch = delivery.reset()
        playedAudio = false
        speaking = false
        suppressDelivery = false
        openingWaiting = false
        if (characterId.isNotBlank()) { AvatarController.audio(characterId, 0f); AvatarController.listening(characterId, false) }
        val old = session
        session = null
        if (old != null) scope.launch { old.endSession() }
    }

    private fun persistAgent(eventId: Int, text: String) {
        MigratedDomainStores.chat.appendVoiceMessage(conversationId, "voice-$providerSessionId-agent-$eventId-$characterId", text, true)
    }

    private suspend fun refreshCore(epoch: Long) {
        if (epoch != generation) return
        runCatching {
            val snapshot = coreSnapshot(characterId)
            if (epoch != generation) return
            CloudTaskBridge.request("/v1/context", snapshot)
        }.onFailure { if (epoch == generation) onError("角色状态同步失败：${it.message}") }
    }

    private suspend fun coreSnapshot(id: String): JSONObject {
        val version = synchronized(this) {
            contextVersion = maxOf(contextVersion + 1, Instant.now().toEpochMilli())
            contextVersion
        }
        val memory = CharacterRuntime.memory(id, UnifiedMemoryRequest(sceneContext = "实时电话", taskIntent = "自然接续同一个角色"))
        val worldBook = LuluRepositories.worldBook.snapshot().filter { (it.globalEnabled && it.characterOverrides[id] != false) || it.characterOverrides[id] == true }
        val lexicon = LuluRepositories.lexicon.snapshot(id)
        val presence = CompanionPresenceStore.current(id)
        val definition = CharacterRuntime.definition(id)
        val context = listOf(
            definition.promptSection(),
            CharacterExpressionGuide.promptSection(),
            CharacterSpeechIdentity.promptSection(id),
            UserProfileContext.promptSection(), UserDevicePerception.context(this.context, id),
            CharacterInnerLifeStore.compactContext(id), "当前状态（主观）：$presence", DigitalWorldStore.contextFor(id),
            DigitalWorldLifeEventStore.contextFor(id), CharacterRuntime.developmentContext(id),
            memory.compactPromptSection(12_000), "世界书：" + worldBook.joinToString("\n") { "${it.title}：${it.content}" },
            "辞海：" + lexicon.take(24).joinToString("\n") { "${it.title}：${it.content}" }, CapabilityRegistry.context(context = this.context, characterId = id),
            "只按实际事件、工具结果和当前世界状态续接。用户陈述、观察事实和推测分开；生成或日记不能证明已经行动。")
            .filter(String::isNotBlank).joinToString("\n\n")
        return JSONObject().put("characterId", id).put("context", context).put("version", version)
    }
}
