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
    private val allowedInteractions = setOf("greet", "chat", "ask_place", "sit_together")
    private val gate = Mutex()

    suspend fun interact(characterId: String, residentId: String, interaction: String,
        now: Instant = Instant.now()): DigitalWorldActionResult = gate.withLock {
        var provisionalSessionId: String? = null
        runCatching {
            require(DigitalLifeProfileStore.isEnabled(characterId)) { "只有数字生命能与居民互动" }
            val location = DigitalWorldStore.locationOf(characterId)
            val resident = DigitalWorldExpansionStore.residentsAt(location).firstOrNull { it.id == residentId }
                ?: error("居民不在当前地点，不能隔空交流")
            val normalized = interaction.trim().lowercase()
            require(normalized in allowedInteractions) { "不支持的居民互动" }
            val character = MigratedDomainStores.characters.get(characterId)
            val history = DigitalWorldStore.state.value.meetings.filter { session ->
                characterId in session.participantIds && session.turns.any { it.speakerId == "resident:$residentId" }
            }.takeLast(3).joinToString("\n") { session ->
                session.turns.takeLast(8).joinToString("\n") { "${it.speakerName}：${it.dialogue}" }
            }
            val reply = LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = "地点ID=$location\n现场居民ID=resident:$residentId，姓名=${resident.name}，身份=${resident.identity}。\n" +
                    "住宅=${resident.homeLocationCode}。本次意愿=$normalized。用户不参与本次台词。\n" +
                    "此前双方真正说过的话：\n${history.take(2400)}",
                instruction = """
                    将角色自主选择的这次数字世界相处展开成真正的双向交谈，输出一个 JSON：
                    {"turns":[{"speakerId":"$characterId 或 resident:$residentId","reason":"此刻本人想说这句话的简短缘由","text":"实际说出口的台词"}],
                    "appraisal":{"meaning":"这次相处的个人理解"},"innerLife":{"emotion":{"feeling":"相处后的感受","cause":"对方实际说的话"}}}
                    greet 可为两三句；chat/ask_place/sit_together 为四至八轮，有具体内容、回应和自然收尾。
                    ${character.displayName}沿用自己的完整人格、经历和当下意愿；${resident.name}按自己的已登记身份说话，保持双方独立，不模仿用户，不替用户讲话。
                    每个 turn 都要有本人当时的 reason，不机械一问一答，不把双方写成客服；可以分享看法、请教、玩笑、不同意见或安静相处。
                    只使用登记地点、现场物品与双方实际记录。NPC不知角色的私人心声与用户私聊；不得让NPC凭空拥有工具执行权限、额外物品、作品原文或权威知识。
                    不以一句“他们聊了一会儿”替代台词；不要编造已读书、已完成任务或用户在场。相处后的感受只属于当前角色，不输出 social，不保证已学会任何技能。
                """.trimIndent(),
                source = "数字世界居民交流", title = "与${resident.name}相处", maxTokens = 2000,
            ).getOrThrow()
            val json = ModelStructuredOutput.objectOrNull(reply.text) ?: error("居民交流没有形成完整台词")
            val turns = parseTurns(json, characterId, resident, now)
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
            val summaryId = "meeting-${session.id}-resident-summary-viewer-$characterId"
            val summary = "${character.displayName}与${resident.name}在${session.location}实际交流了${turns.size}轮。\n" +
                turns.joinToString("\n") { "${it.speakerName}：${it.dialogue}" }
            SharedExperienceTimeline.record(eventId = summaryId, characterId = characterId,
                channel = "数字世界见面·${session.location}", speaker = "数字世界",
                content = summary, occurredAt = now, sessionId = session.id, source = "meeting",
                evidenceKind = EventEvidenceKind.ToolResult, triggerExtraction = true)
            check(SharedExperienceTimeline.eventsByIds(characterId, listOf(summaryId)).isNotEmpty()) { "居民相处记录保存失败" }
            provisionalSessionId = null
            // Optional psychology cannot turn an already saved conversation into a false failure.
            runCatching {
                val lastNative = turns.indexOfLast { it.speakerId == "resident:$residentId" }
                val state = json.optJSONObject("innerLife")?.let { JSONObject(it.toString()).apply { remove("social") } }
                CharacterInnerLifeStore.observe(characterId, evidenceIds[lastNative], turns[lastNative].dialogue,
                    state, emptySet(), now)
                state?.optJSONObject("emotion")?.optString("feeling")?.takeIf(String::isNotBlank)?.let { feeling ->
                    CompanionPresenceStore.update(characterId, statusText = null, gesture = null,
                        innerThought = null, mood = feeling, source = "数字世界·居民相处",
                        now = now, provenanceId = "meeting-${session.id}-presence-resident")
                }
                DigitalWorldExpansionStore.moveResident(resident.id, location, now)
            }
            DigitalWorldActionResult(true,
                "[角色见面|${session.id}] 与${resident.name}交流 · 查看见面记录", summaryId)
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
