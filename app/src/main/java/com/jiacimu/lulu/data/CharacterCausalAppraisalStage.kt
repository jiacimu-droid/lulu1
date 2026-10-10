package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import java.time.Instant

/**
 * A real two-stage bridge for salient interaction events.
 *
 * Stage one interprets witnessed evidence and COMMITS the resulting subjective
 * state before any subsequent autonomous action planner is invoked. Stage two
 * uses that durable state; it cannot retroactively invent the state that
 * supposedly caused its action. Ordinary phone/chat replies are not slowed
 * down by an extra model call.
 */
internal object CharacterCausalAppraisalStage {
    internal data class Outcome(
        val evidenceId: String,
        val evidenceDescription: String,
        val mood: String,
        val innerThought: String,
    )

    /** Only consequential witnessed interactions merit a separate pre-action
     * appraisal. Ambient world ticks and ordinary voice/chat replies stay fast.
     */
    fun eligible(event: SharedTimelineEvent): Boolean =
        event.evidenceKind == EventEvidenceKind.Observation && when {
            event.source == InteractionSignalBridge.SOURCE ->
                event.id.startsWith("interaction-call-end-")
            // Genuine touch and visits also change social context without a new text bubble.
            event.id.startsWith("world-touch-") || event.id.startsWith("world-visit-") ->
                event.source == "meeting" || event.source == "digital-world"
            else -> false
        }

    fun latestPending(events: List<SharedTimelineEvent>): SharedTimelineEvent? =
        events.filter(::eligible).maxByOrNull { it.occurredAt }

    /** A stage-one receipt does not mean a real action decision has completed. */
    fun hasCompletedDecision(characterId: String, evidenceId: String): Boolean {
        if (evidenceId.isBlank()) return false
        val entries = CharacterInnerLifeStore.snapshot(characterId).optJSONArray("decisions") ?: return false
        return (0 until entries.length()).any { index ->
            entries.optJSONObject(index)?.optString("causalEvidenceId") == evidenceId
        }
    }

    /** Resume the saved inner state if the process died between appraisal and action.
     * The event does not need to be reinterpreted or charged for a second time.
     */
    fun resumeCommitted(characterId: String, evidenceId: String): Outcome? {
        if (evidenceId.isBlank() || hasCompletedDecision(characterId, evidenceId)) return null
        val transitions = CharacterInnerLifeStore.snapshot(characterId)
            .optJSONArray("causalTransitions") ?: return null
        val committed = (0 until transitions.length()).any { index ->
            val entry = transitions.optJSONObject(index)
            entry?.optString("evidenceId") == evidenceId &&
                entry.optString("selectedAction") == "appraise"
        }
        if (!committed) return null
        val event = SharedExperienceTimeline.eventsByIds(characterId, listOf(evidenceId))
            .firstOrNull() ?: return null
        val presence = CompanionPresenceStore.current(characterId)
        return Outcome(
            evidenceId = evidenceId,
            evidenceDescription = CharacterPerceptionContext.stimulus(event).description,
            mood = presence?.mood.orEmpty(),
            innerThought = presence?.innerThought.orEmpty(),
        )
    }

    /**
     * produce is the existing model gateway, injected so the stage is
     * testable without an Android or network dependency.
     *
     * A parse/network failure leaves evidence unclaimed, so later perception
     * can retry. A valid "nothing changed" appraisal is allowed and is claimed.
     */
    suspend fun reflect(
        context: Context,
        characterId: String,
        pending: List<SharedTimelineEvent>,
        now: Instant,
        produce: suspend (SharedTimelineEvent) -> String,
    ): Outcome? {
        val event = latestPending(pending) ?: return null
        if (PerceptionStimulusLedger.hasSeen(context, characterId, event.id)) return null
        val parsed = ModelStructuredOutput.objectOrNull(produce(event)) ?: return null
        val appraisal = parsed.optJSONObject("appraisal")
        val innerLife = parsed.optJSONObject("innerLife")?.let { original ->
            JSONObject(original.toString()).apply {
                // If the model names a feeling but omits its cause, ground the
                // cause in the actual event, never in a guessed user motive.
                optJSONObject("emotion")?.let { emotion ->
                    if (emotion.optString("feeling").isNotBlank() &&
                        emotion.optString("cause").isBlank()) {
                        emotion.put("cause", "刚刚实际发生的互动：${event.content.take(150)}")
                    }
                }
            }
        }
        val mood = parsed.optString("mood").trim()
            .ifBlank { innerLife?.optJSONObject("emotion")?.optString("feeling").orEmpty() }
            .take(80)
        val rawThought = parsed.optString("innerThought").trim()
        val basis = parsed.optJSONObject("innerThoughtBasis")
        // Do not mark an invalid/empty provider response as a successful appraisal.
        if (appraisal == null && innerLife == null && mood.isBlank() && rawThought.isBlank()) return null
        // Do NOT claim the evidence before the private state is durably saved.
        // If persistence fails, the still-pending event must be recoverable.
        val verified = CharacterPerceptionContext.stimulus(event)
        val previous = CharacterInnerLifeStore.snapshot(characterId)
        val delta = PrivateStateDeltaEngine.evaluate(
            previous = previous, proposal = innerLife,
            appraisal = appraisal, basis = basis, thought = rawThought,
        )
        val grounded = CharacterHeartVoicePolicy.keepOrBlank(
            thought = rawThought, innerLife = innerLife, basis = basis,
            delta = delta, hasFreshEvidence = true,
        )
        // The emotion and any new motive are committed before the action model runs.
        if (innerLife != null) CharacterInnerLifeStore.observe(
            characterId, verified.evidenceId, verified.description, innerLife,
            verified.socialIds, now,
        )
        CharacterInnerLifeStore.recordCausalTransition(
            characterId = characterId, evidenceId = verified.evidenceId,
            appraisal = appraisal, innerLife = innerLife,
            innerThoughtBasis = basis, selectedAction = "appraise",
            innerThought = grounded,
            reason = appraisal?.optString("meaning").orEmpty().ifBlank { "留意刚发生的互动" },
            now = now,
        )
        CharacterInnerLifeStore.recordInnerVoice(
            characterId, verified.evidenceId, grounded, now, delta.fingerprint,
        )
        if (mood.isNotBlank() || grounded.isNotBlank()) {
            CompanionPresenceStore.update(
                characterId = characterId, statusText = null, gesture = null,
                innerThought = grounded.takeIf(String::isNotBlank),
                mood = mood.takeIf(String::isNotBlank),
                source = "互动感知·私人反应", now = now,
                innerThoughtFingerprint = delta.fingerprint,
            )
        }
        CompanionPresenceStore.recordPerceptionAttempt(characterId,
            "已理解互动事件并保存私人状态 · ${event.id.take(45)}", now)
        // Record that we handled this observation only after its resulting
        // appraisal and subjective continuity have been committed.
        if (!PerceptionStimulusLedger.claim(context, characterId, verified)) return null
        return Outcome(verified.evidenceId, verified.description, mood, grounded)
    }

    fun instruction(): String = """
        你在作出下一步外部行为决定之前，先独立理解一个确实发生的互动事件。
        这一阶段只负责你自己的感受、看法、矛盾和愿望变化，暂时不向用户说话，也不调用工具。
        只依据证据与既有关系、性格和持续状态，区分事实、猜测以及仍不了解的事。
        用户挂断或没有被成功转写的语音，不能断言用户生气、故意沉默或不在乎你。
        感受可以是疑惑、想念、坦然、委屈、关心，也可能没有新的感受；不要为了有反应而造感情。
        只返回 JSON：{"appraisal":{"meaning":"你如何看待事实","uncertainty":"不能确定什么","responseAim":"如想行动，你希望达到什么"},"innerLife":{"emotion":{"feeling":"确实产生的感受","cause":"真实缘由","otherFeeling":"并存感受","impulse":"想做但尚未执行什么","restraint":"有什么顾虑","strength":1},"motives":[{"op":"start","aim":"如有实际持续愿望","why":"现实依据"}]},"innerThought":"确实还没有说出口的念头，可为空","innerThoughtBasis":{"focus":"触发点","change":"和上一刻的变化","unsaidWhy":"为何尚未说出口"},"mood":"可见于此刻的简短心情"}
        其中 emotion、motives、innerThought、innerThoughtBasis、mood 均可省略，不能为了填满字段制造变化。至少保留 appraisal.meaning 或 uncertainty。
        行动必须等待本阶段真实保存完成，下一阶段将用你已经形成的私人状态作出选择。
    """.trimIndent()
}
