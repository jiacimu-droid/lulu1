package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.ModelReply
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/** A role-owned reaction from the same response that chose what to do. */
internal object CharacterObservedMoment {
    private const val FIELD = "privateMoment"

    const val instruction = """
        【同一人物的此刻】用眼前真实的新情况理解这一轮，再决定说话、行动或等待。如果这一刻确有没说出口的念头或新心情，在原 JSON 最后可附：
        "privateMoment":{"innerThought":"本人没说出口的一句短念头","innerThoughtBasis":{"focus":"本人刚观察到的具体触发","change":"自己的反应新在哪里","unsaidWhy":"若适用，为什么没说"},"mood":"若有新的心情"}
        真的产生跨轮变化时，可在 privateMoment 附内在状态字段 innerLife（emotion/thoughts/motives）和 appraisal（privateIdeas/pendingConcerns/concernAssessment）。使用统一角色协议，同主题沿用原ID。已经明确选择的对外行动仍填写在外层原场景协议。
        沉默、等待和失败后的观察也可以有心声。若没有新反应可留空。只写当前人物自己的感受；不复述台词，不把猜测、作品内容、其他人在想什么或尚未执行的事写成事实。privateMoment 是独立字段，不得写进对外台词、字幕或工具参数。
    """

    /** Defer all writes until the scene verifies its own active turn and evidence. */
    fun detach(role: String, reply: ModelReply): ModelReply {
        val json = ModelStructuredOutput.objectOrNull(reply.text)
        if (json == null) {
            return if (FIELD in reply.text) reply.copy(text = "") else reply
        }
        if (!json.has(FIELD)) return reply
        val packet = json.remove(FIELD) as? JSONObject
        return reply.copy(
            text = json.toString(),
            privateMoment = packet.takeUnless { reply.finishReason in setOf("length", "max_tokens", "MAX_TOKENS") },
            privateMomentOwner = role,
        )
    }

    /** Existing source IDs are used whenever the scene has already saved the stimulus. */
    fun record(
        role: String,
        reply: ModelReply,
        evidenceId: String,
        scene: String,
        outward: String = "",
        now: Instant = Instant.now(),
    ) {
        val packet = reply.privateMoment?.takeIf { reply.privateMomentOwner == role } ?: return
        recordAuthored(role, packet, evidenceId, scene, outward, now)
    }

    /** A durable scene adapter may already own its decoded, non-public decision. */
    @Synchronized
    fun recordAuthored(
        role: String,
        packet: JSONObject,
        evidenceId: String,
        scene: String,
        outward: String = "",
        now: Instant = Instant.now(),
    ) {
        // Projection errors cannot turn a valid game move or tool decision into an action failure.
        runCatching {
            val event = SharedExperienceTimeline.eventsByIds(role, listOf(evidenceId)).singleOrNull()
                ?: return@runCatching
            if (evidenceId !in CharacterPerceptionContext.liveEvidenceIds(role, listOf(evidenceId)))
                return@runCatching
            val previous = CharacterInnerLifeStore.snapshot(role)
            val transitions = previous.optJSONArray("causalTransitions")
            if ((0 until (transitions?.length() ?: 0)).any {
                    transitions?.optJSONObject(it)?.optString("id") == "$evidenceId:scene_perception"
                } || CompanionPresenceStore.histories.value[role].orEmpty().any {
                    it.provenanceId == evidenceId && it.source == "$scene·感知"
                }) return@runCatching
            val basis = packet.optJSONObject("innerThoughtBasis")
            val thought = (packet.opt("innerThought") as? String).orEmpty().trim().take(1_200)
            val appraisal = privateFields(packet, "appraisal", "privateIdeas", "pendingConcerns",
                "pendingConcern", "concernAssessment")
            val innerLife = privateFields(packet, "innerLife", "emotion", "thoughts", "motives")
            val mood = ((packet.opt("mood") as? String).orEmpty()
                .ifBlank { (innerLife?.optJSONObject("emotion")?.opt("feeling") as? String).orEmpty() })
                .trim().take(45).takeIf(String::isNotBlank)
            val delta = PrivateStateDeltaEngine.evaluate(
                previous, proposal = innerLife, appraisal = appraisal, basis = basis, thought = thought,
                hasFreshDirectObservation = true,
            )
            val voice = if (CharacterHeartVoicePolicy.hasCausalBasis(basis)) {
                CharacterHeartVoicePolicy.keepOrBlank(
                    thought, outward, innerLife = innerLife, basis = basis, delta = delta, hasFreshEvidence = true,
                )
            } else ""
            if (voice.isBlank() && mood == null && appraisal == null && innerLife == null) return@runCatching
            CharacterInnerLifeStore.observe(
                role, event.id, CharacterPerceptionContext.stimulus(event).description, innerLife, now = now,
            )
            CharacterTurnStateCommit.recordDurableDecision(
                role, event.id, appraisal = appraisal, innerLife = innerLife, innerThoughtBasis = basis,
                selectedAction = "scene_perception", innerThought = voice, reason = "", now = now,
                voiceFingerprint = delta.fingerprint,
            )
            val reflection = if (delta.meaningful)
                CharacterMomentSnapshot.reflection(innerLife, basis, appraisal, outward, voice)
            else ""
            if (voice.isNotBlank() || mood != null || reflection.isNotBlank()) {
                CompanionPresenceStore.update(
                    role, statusText = null, gesture = null, innerThought = voice.takeIf(String::isNotBlank),
                    mood = mood, source = "$scene·感知", now = now, provenanceId = event.id,
                    innerThoughtFingerprint = delta.fingerprint, momentReflection = reflection,
                )
            }
        }
    }

    private fun privateFields(packet: JSONObject, name: String, vararg keys: String): JSONObject? {
        val authored = packet.optJSONObject(name) ?: return null
        return JSONObject().apply {
            keys.forEach { key -> if (authored.has(key) && !authored.isNull(key)) put(key, authored.get(key)) }
        }.takeIf { it.length() > 0 }
    }

    /** Persist only the factual snapshot already verified by the scene, never the model's account of it. */
    fun recordScene(
        role: String,
        reply: ModelReply,
        scene: String,
        sessionId: String,
        turnKey: String,
        observation: String,
        outward: String = "",
    ) {
        if (role.isBlank() || reply.privateMomentOwner != role || sessionId.isBlank() ||
            turnKey.isBlank() || observation.isBlank()) return
        val packet = reply.privateMoment ?: return
        if ((packet.opt("innerThought") as? String).isNullOrBlank() &&
            (packet.opt("mood") as? String).isNullOrBlank() &&
            packet.optJSONObject("appraisal") == null && packet.optJSONObject("innerLife") == null) return
        val time = Instant.now()
        val id = "role-observed-" + UUID.nameUUIDFromBytes(
            "$role\n$scene\n$sessionId\n$turnKey".toByteArray(StandardCharsets.UTF_8),
        )
        runCatching {
            if (SharedExperienceTimeline.eventsByIds(role, listOf(id)).isEmpty()) {
                SharedExperienceTimeline.record(
                    eventId = id, characterId = role, channel = scene, speaker = "现场观察",
                    content = observation.take(550), sessionId = sessionId, occurredAt = time,
                    triggerExtraction = false, source = "role-observed-scene",
                    evidenceKind = EventEvidenceKind.Observation, expectedRevision = 0L,
                )
            }
            record(role, reply, id, scene, outward, time)
        }
    }
}
