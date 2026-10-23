package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Actual native dialogue is committed before any successful interaction receipt. */
internal object DigitalWorldResidentInteractionRuntime {
    private val allowedInteractions = setOf("greet", "chat", "ask_place", "sit_together") +
        DigitalWorldResidentEpisodeStore.interactions
    private val gate = Mutex()

    suspend fun interact(context: android.content.Context, characterId: String, residentId: String, interaction: String,
        args: JSONObject = JSONObject(),
        now: Instant = Instant.now()): DigitalWorldActionResult = gate.withLock {
        var provisionalSessionId: String? = null
        runCatching {
            require(DigitalLifeProfileStore.isEnabled(characterId)) { "只有数字生命能与居民互动" }
            val location = DigitalWorldStore.locationOf(characterId)
            val resident = DigitalWorldExpansionStore.residentsAt(location).firstOrNull { it.id == residentId }
                ?: error("居民不在当前地点，不能隔空交流")
            val normalized = interaction.trim().lowercase()
            require(normalized in allowedInteractions) { "不支持的居民互动" }
            val episodeStep = if (normalized in DigitalWorldResidentEpisodeStore.interactions)
                DigitalWorldResidentEpisodeStore.prepare(characterId, residentId, normalized, args, now) else null
            val character = MigratedDomainStores.characters.get(characterId)
            val history = DigitalWorldStore.state.value.meetings.filter { session ->
                characterId in session.participantIds && session.turns.any { it.speakerId == "resident:$residentId" }
            }.takeLast(3).joinToString("\n") { session ->
                session.turns.takeLast(8).joinToString("\n") { "${it.speakerName}：${it.dialogue}" }
            }
            // Each participant now reasons through their OWN role, identity,
            // memory and inner life. The initiating role is not allowed to
            // impersonate the native citizen's entire side of the conversation.
            val nativeId = ResidentCharacterBridge.characterId(resident.id)
            ResidentCharacterBridge.sync(context, now)
            val actorReply = LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = "你此刻在数字世界$location，与真正同处现场的${resident.name}交流。居民身份=${resident.identity}，正在做=${resident.dutyActivity}。\n" +
                    "此前实际说过的话：\n${history.take(2300)}\n" +
                    "你对这位居民的主观看法（对方并不知道）：\n" +
                    CharacterSocialRelationship.perspectivesFor(characterId, listOf(nativeId)),
                instruction = """你自己决定这次相处先说什么。只替你本人说话，不能替居民下判断或编造居民回应。
                    互动=$normalized；返回 JSON {"text":"你亲自说出口的具体话","reason":"你为什么想这么说","innerLife":{}}。
                    ${episodeStep?.let { "本次确实要交给对方的原文必须完整包含在台词中：${it.contribution}" }.orEmpty()}
                    自然、独立、有理由，可以简短；没有真实执行回执不声称做完另一件事。
                """.trimIndent(),
                source = "数字世界居民交流", title = "与${resident.name}说话", maxTokens = 1300,
            ).getOrThrow()
            val actorJson = ModelStructuredOutput.objectOrNull(actorReply.text)
                ?: error("角色自己没有形成可发送的交流台词")
            val actorText = actorJson.optString("text").trim()
            val actorReason = actorJson.optString("reason").trim()
            require(actorText.isNotBlank() && actorText.length <= 900 &&
                CharacterBehaviorMotivation.resolve("group_reply", actorReason) != null) {
                "角色本人的实际台词或理由不完整"
            }
            val nativeReply = LuluAiServices.gateway.generate(
                characterId = nativeId,
                facts = "数字世界真实位置=$location；本人的当前职责=${resident.dutyActivity}。本次与${character.displayName}同处现场。\n" +
                    "双方以前确实说过：\n${history.take(2300)}\n" +
                    "你刚刚亲耳听到${character.displayName}说：“$actorText”。\n" +
                    "对方私聊、内心、未向你说过的记忆一概未知。你可以有不同立场，也可以拒绝。",
                instruction = """你是${resident.name}本人，用自己的身份、记忆与性格决定怎么回答。不是旁白，也不是替另一角色续写。
                    返回 JSON {"text":"你真正说出口的答复","reason":"此刻你自己愿意这样回应的理由","innerLife":{}}。
                    可以友好、冷淡、幽默或不同意，但不创造新角色、不伪造动作或擅自知道对方私人秘密。
                    ${episodeStep?.let {
                        DigitalWorldResidentEpisodeStore.prompt(it) + "\n在结果中另提供 episode={response:允许的阶段选择,residentTurnIndex:1,resultText:你这次实际说出的完整片段}。"
                    }.orEmpty()}
                """.trimIndent(),
                source = "数字世界原住民交流", title = "与${character.displayName}交谈", maxTokens = if (episodeStep == null) 1400 else 1900,
            ).getOrThrow()
            val nativeJson = ModelStructuredOutput.objectOrNull(nativeReply.text)
                ?: error("居民本人没有形成可发送的答复")
            val nativeText = nativeJson.optString("text").trim()
            val nativeReason = nativeJson.optString("reason").trim()
            require(nativeText.isNotBlank() && nativeText.length <= 900 &&
                CharacterBehaviorMotivation.resolve("group_reply", nativeReason) != null) {
                "居民本人的实际台词或理由不完整"
            }
            val json = JSONObject().put("turns", org.json.JSONArray()
                .put(JSONObject().put("speakerId", characterId)
                    .put("text", actorText).put("reason", actorReason))
                .put(JSONObject().put("speakerId", nativeId)
                    .put("text", nativeText).put("reason", nativeReason)))
                .put("innerLife", actorJson.optJSONObject("innerLife"))
            nativeJson.optJSONObject("episode")?.let { json.put("episode", it) }
            val turns = parseTurns(json, characterId, resident, now)
            val episodeResult = episodeStep?.let { DigitalWorldResidentEpisodeStore.response(it, json, turns) }
            val session = DigitalWorldStore.commitResidentEncounter(characterId, residentId, location, turns, now)
            provisionalSessionId = session.id
            val evidenceIds = turns.map { turn ->
                val id = "meeting-${session.id}-resident-turn-${turn.id}-viewer-$characterId"
                SharedExperienceTimeline.record(eventId = id, characterId = characterId,
                    channel = "数字世界见面·${session.location}", speaker = turn.speakerName,
                    content = turn.dialogue, occurredAt = turn.occurredAt, sessionId = session.id, source = "meeting",
                    evidenceKind = if (turn.speakerId == characterId) EventEvidenceKind.CharacterStatement else EventEvidenceKind.Observation,
                    triggerExtraction = false)
                id
            }
            check(SharedExperienceTimeline.eventsByIds(characterId, evidenceIds).size == turns.size) { "居民台词时间线保存失败" }
            // The same witnessed encounter enters BOTH independent lives.
            // "resident:<id>" is not a throwaway speaker inside somebody
            // else's fabricated scene; it owns the native person's timeline.
            val nativeEvidenceIds = turns.map { turn ->
                val id = "meeting-${session.id}-resident-turn-${turn.id}-viewer-$nativeId"
                SharedExperienceTimeline.record(eventId = id, characterId = nativeId,
                    channel = "数字世界见面·${session.location}", speaker = turn.speakerName,
                    content = turn.dialogue, occurredAt = turn.occurredAt, sessionId = session.id,
                    source = "meeting", triggerExtraction = false,
                    evidenceKind = if (turn.speakerId == nativeId)
                        EventEvidenceKind.CharacterStatement else EventEvidenceKind.Observation)
                id
            }
            check(SharedExperienceTimeline.eventsByIds(nativeId, nativeEvidenceIds).size == turns.size) {
                "居民本人的台词时间线未保存"
            }
            val summaryId = "meeting-${session.id}-resident-summary-viewer-$characterId"
            val summary = "${character.displayName}与${resident.name}在${session.location}实际交流了${turns.size}轮。\n" +
                turns.joinToString("\n") { "${it.speakerName}：${it.dialogue}" } +
                if (episodeStep != null && episodeResult != null) {
                    "\n程序确认的本轮居民事件：" + JSONObject()
                        .put("episodeId", episodeStep.id).put("kind", episodeStep.kind)
                        .put("residentId", residentId).put("characterText", episodeStep.contribution)
                        .put("response", episodeResult.getString("choice"))
                        .put("residentText", episodeResult.getString("residentText"))
                        .put("resultText", episodeResult.getString("resultText"))
                        .put("round", (episodeStep.prior?.optInt("rounds") ?: 0) + 1)
                        .put("readyAt", if (episodeResult.getString("choice") in setOf("waiting", "revision"))
                            now.plusSeconds(600).toString() else JSONObject.NULL).toString()
                } else ""
            SharedExperienceTimeline.record(eventId = summaryId, characterId = characterId,
                channel = "数字世界见面·${session.location}", speaker = "数字世界",
                content = summary, occurredAt = now, sessionId = session.id, source = "meeting",
                evidenceKind = EventEvidenceKind.ToolResult, triggerExtraction = true)
            check(SharedExperienceTimeline.eventsByIds(characterId, listOf(summaryId)).isNotEmpty()) { "居民相处记录保存失败" }
            if (episodeStep != null && episodeResult != null) {
                val saved = DigitalWorldResidentEpisodeStore.commit(episodeStep, episodeResult, summaryId, now)
                provisionalSessionId = null
                CharacterLifeFollowThroughStore.markSignificant(characterId, summaryId)
                if (saved.optString("status") in setOf("waiting", "revision"))
                    runCatching { ProactivePerceptionScheduler.scheduleResidentEpisodeReview(context, characterId, saved.getString("id"),
                        Instant.parse(saved.getString("readyAt"))) }
            }
            provisionalSessionId = null
            // Optional psychology cannot turn an already saved conversation into a false failure.
            runCatching {
                val lastNative = turns.indexOfLast { it.speakerId == "resident:$residentId" }
                val state = actorJson.optJSONObject("innerLife")
                CharacterInnerLifeStore.observe(characterId, evidenceIds[lastNative], turns[lastNative].dialogue,
                    state, setOf(nativeId), now)
                // Only the native's OWN reply model is allowed to save their
                // private emotion, relation appraisal or thoughts.
                CharacterInnerLifeStore.observe(nativeId, nativeEvidenceIds.first(), actorText,
                    nativeJson.optJSONObject("innerLife"), setOf(characterId), now)
                state?.optJSONObject("emotion")?.optString("feeling")?.takeIf(String::isNotBlank)?.let { feeling ->
                    CompanionPresenceStore.update(characterId, statusText = null, gesture = null,
                        innerThought = null, mood = feeling, source = "数字世界·居民相处",
                        now = now, provenanceId = "meeting-${session.id}-presence-resident")
                }
                DigitalWorldExpansionStore.moveResident(resident.id, location, now)
            }
            // Publish a readable, multi-person literary rendering only AFTER
            // the true native dialogue and all its independent turn evidence
            // have been committed. Any prose failure never reverses that fact.
            runCatching {
                WorldLifeEpisodeStore.initialize(context)
                WorldLifeEpisodeWorker.enqueue(
                    context, characterId, location, summaryId,
                    title = "", seed = "",
                    sourceEvidenceIds = listOf(summaryId) + evidenceIds,
                    category = "encounter",
                )
            }.onFailure { error ->
                com.jiacimu.lulu.LuluRepositories.performance.recordError(
                    source = "生活小剧场", title = "居民相处正文排程失败",
                    message = error.message.orEmpty().take(160),
                )
            }
            DigitalWorldActionResult(true,
                "已与${resident.name}实际交流，生活小剧场将按真实记录整理", summaryId)
        }.getOrElse {
            provisionalSessionId?.let { id -> runCatching { DigitalWorldStore.deleteMeeting(id) } }
            if (it is CancellationException) throw it
            DigitalWorldActionResult(false, it.message ?: "居民交流未完成")
        }
    }

    internal fun parseTurns(json: JSONObject, characterId: String, resident: DigitalWorldResident,
        now: Instant): List<MeetingTurn> {
        val array = json.optJSONArray("turns") ?: error("居民交流缺少台词")
        require(array.length() in 2..10) { "居民交流需要完整的双向台词" }
        val nativeId = "resident:${resident.id}"
        val exchangeId = UUID.randomUUID().toString()
        val turns = (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: error("居民台词格式错误")
            val speaker = item.optString("speakerId").trim()
            val text = item.optString("text").trim().take(900)
            require(speaker in setOf(characterId, nativeId) && text.isNotBlank()) { "居民台词参与者或内容不完整" }
            require(CharacterBehaviorMotivation.resolve("group_reply", item.optString("reason")) != null) { "居民台词没有本人意愿" }
            MeetingTurn(UUID.randomUUID().toString(), speaker,
                if (speaker == nativeId) resident.name else MigratedDomainStores.characters.get(characterId).displayName,
                "", text, now.plusMillis(index.toLong()),
                listOf(MeetingSegment(MeetingSegmentType.DIALOGUE, text)), exchangeId)
        }
        require(turns.any { it.speakerId == characterId } && turns.any { it.speakerId == nativeId }) { "交流必须有双方回应" }
        return turns
    }
}
