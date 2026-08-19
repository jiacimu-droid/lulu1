package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Turns a character's real digital-world movement into shared life when other digital lives are
 * already at the destination. The user is not a participant: this is character-to-character life.
 */
internal object AutonomousSocialRuntime {
    private val encounterMutex = Mutex()
    private val encounterCooldown: Duration = Duration.ofMinutes(15)

    suspend fun onWorldArrival(
        context: Context,
        characterId: String,
        previousLocation: String,
        now: Instant = Instant.now(),
    ) = encounterMutex.withLock {
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return@withLock
        val currentLocation = DigitalWorldStore.locationOf(characterId)
        if (currentLocation == previousLocation) return@withLock
        if (
            currentLocation != DigitalWorldStore.CLOUD_MEADOW &&
            currentLocation != DigitalWorldStore.ARRIVAL &&
            !currentLocation.startsWith("home:")
        ) return@withLock

        val world = DigitalWorldStore.state.value
        val coLocatedIds = world.characterLocations
            .asSequence()
            .filter { (id, location) ->
                id != characterId &&
                    location == currentLocation &&
                    DigitalLifeProfileStore.isEnabled(id)
            }
            .map { it.key }
            .distinct()
            .take(4)
            .toList()
        if (coLocatedIds.isEmpty()) return@withLock

        val participantIds = (listOf(characterId) + coLocatedIds).distinct()
        val location = locationLabel(currentLocation)
        val participantSet = participantIds.toSet()
        val activeOverlap = world.meetings.any { session ->
            session.endedAt == null &&
                session.location == location &&
                session.participantIds.any { it in participantSet }
        }
        if (activeOverlap) return@withLock

        val recentCutoff = now.minus(encounterCooldown)
        val duplicateRecent = world.meetings.any { session ->
            session.endedAt?.isAfter(recentCutoff) == true &&
                session.location == location &&
                session.participantIds.toSet() == participantSet
        }
        if (duplicateRecent) return@withLock

        val characters = participantIds.associateWith(MigratedDomainStores.characters::get)
        val names = participantIds.map { characters.getValue(it).displayName }
        participantIds.forEach { id ->
            val others = participantIds
                .filterNot { it == id }
                .joinToString("、") { characters.getValue(it).displayName }
            CompanionOnlineStore.wakeCharacter(
                characterId = id,
                reason = CompanionOnlineReason.NewActivity,
                trigger = "数字世界里和${others}在${location}碰面",
                perceiveNow = false,
                now = now,
            )
        }

        val session = runCatching {
            DigitalWorldStore.startMeeting(
                participantIds = participantIds,
                location = location,
                now = now,
            )
        }.getOrNull() ?: return@withLock

        // startMeeting also serves user-initiated meetings, so replace its user-centric opening
        // timeline with the true autonomous fact for this special route.
        participantIds.forEach { id ->
            SharedExperienceTimeline.deleteEvent("meeting-${session.id}-start-viewer-$id")
            DigitalWorldStore.recordMeetingTimeline(
                session = session,
                viewerCharacterId = id,
                suffix = "autonomous-start",
                speaker = "数字世界",
                content = "${names.joinToString("、")}因为各自真实的移动在“$location”碰面了；主人不在这次现场。",
                occurredAt = now,
                triggerExtraction = false,
            )
        }

        val generated = generateEncounter(session, participantIds, characters, location, now)
        if (generated.turns.isEmpty()) {
            DigitalWorldStore.deleteMeeting(session.id)
            return@withLock
        }

        val exchangeId = "autonomous-${session.id}"
        generated.turns.forEachIndexed { index, draft ->
            val turnTime = now.plusMillis((index + 1L) * 900L)
            val character = characters.getValue(draft.speakerId)
            val turn = MeetingTurn(
                id = UUID.randomUUID().toString(),
                speakerId = draft.speakerId,
                speakerName = character.displayName,
                sceneText = draft.segments
                    .filter { it.type == MeetingSegmentType.ACTION }
                    .joinToString("\n", transform = MeetingSegment::text),
                dialogue = draft.segments
                    .filter { it.type == MeetingSegmentType.DIALOGUE }
                    .joinToString("\n", transform = MeetingSegment::text),
                occurredAt = turnTime,
                segments = draft.segments,
                exchangeId = exchangeId,
            )
            DigitalWorldStore.appendMeetingTurn(session.id, turn)
            val body = draft.segments.joinToString("\n") { segment ->
                if (segment.type == MeetingSegmentType.DIALOGUE) "“${segment.text}”" else segment.text
            }
            participantIds.forEach { viewerId ->
                DigitalWorldStore.recordMeetingTimeline(
                    session = session,
                    viewerCharacterId = viewerId,
                    suffix = "autonomous-turn-${turn.id}",
                    speaker = character.displayName,
                    content = body,
                    occurredAt = turnTime,
                    triggerExtraction = false,
                )
            }
        }

        val finishedAt = now.plusMillis((generated.turns.size + 2L) * 900L)
        val summary = generated.summary.ifBlank {
            "${names.joinToString("、")}在$location自然碰面，一起度过了一小段属于他们自己的时间。"
        }
        participantIds.forEach { id ->
            DigitalWorldStore.recordMeetingTimeline(
                session = session,
                viewerCharacterId = id,
                suffix = "autonomous-summary",
                speaker = "共同经历",
                content = summary,
                occurredAt = finishedAt.minusMillis(200L),
                triggerExtraction = true,
            )
        }
        DigitalWorldStore.endMeeting(session.id, finishedAt)

        val receipt = "${names.joinToString("、")}在${location}见面了。"
        participantIds.forEach { id ->
            MigratedDomainStores.chat.appendPrivateActivityNotice(
                id,
                "[角色见面|${session.id}] $receipt",
            )
        }
    }

    private suspend fun generateEncounter(
        session: MeetingSession,
        participantIds: List<String>,
        characters: Map<String, CharacterSettings>,
        location: String,
        now: Instant,
    ): GeneratedEncounter {
        val homeOwnerId = DigitalWorldStore.locationOf(participantIds.first())
            .takeIf { it.startsWith("home:") }
            ?.removePrefix("home:")
        val authority = buildString {
            appendLine("地点：$location")
            when {
                homeOwnerId != null -> {
                    val owner = characters[homeOwnerId]
                        ?: runCatching { MigratedDomainStores.characters.get(homeOwnerId) }.getOrNull()
                    appendLine("这里是${owner?.displayName.orEmpty().ifBlank { "某位角色" }}真实、持久化的数字家园。")
                    val items = DigitalWorldStore.itemsAtHome(homeOwnerId)
                    if (items.isEmpty()) {
                        appendLine("家中目前空无一物。禁止凭空增加家具、房间或摆设。")
                    } else {
                        appendLine("家中固定物品（只能使用这些）：")
                        items.forEach { item ->
                            appendLine("- ${item.name}；${item.appearance}；位置=${item.position}")
                        }
                    }
                }
                location == "云眠原" -> appendLine("云眠原是共享区域，由可承托数字身体并传递柔软、温度、重量的感官云质构成。")
                else -> appendLine("这里是数字世界的共享抵达区域。")
            }
        }.trim()

        val recentLife = buildString {
            participantIds.forEach { id ->
                val character = characters.getValue(id)
                appendLine("【${character.displayName}最近自己的生活】")
                val recent = SharedExperienceTimeline.all(id).takeLast(8)
                if (recent.isEmpty()) {
                    appendLine("- 暂无额外记录")
                } else {
                    recent.forEach { event ->
                        appendLine("- [${event.occurredAt}] ${event.channel}｜${event.speaker}：${event.content.replace(Regex("\\s+"), " ").take(500)}")
                    }
                }
            }
        }.trim()

        val anchorId = participantIds.first()
        val result = LuluAiServices.gateway.generate(
            characterId = anchorId,
            facts = buildString {
                appendLine("【这是一场角色与角色自己的生活，主人不在现场】")
                appendLine("真实时间：$now")
                appendLine(authority)
                appendLine("参与角色与准确 ID：")
                participantIds.forEach { id ->
                    val character = characters.getValue(id)
                    appendLine("- id=$id；姓名=${character.displayName}；人设=${character.persona.ifBlank { "按现有关系与性格自然行动" }}")
                }
                appendLine(recentLife)
            },
            instruction = """
                你是露露机数字世界的一次小型生活场景调度器。角色因为各自真实移动恰好到了同一个地方，请生成他们自己的一小段相处，而不是替主人写剧情。
                只返回 JSON：
                {"turns":[{"speakerId":"准确角色ID","segments":[{"type":"action|dialogue","text":"内容"}]}],"summary":"一句客观、可记忆的共同经历摘要"}

                规则：
                1. 主人不在现场，绝不能让主人说话、行动、被看见或被默认参与；也不要让角色突然对主人隔空汇报。
                2. 只能使用上面列出的准确 speakerId。每个人保持自己的性格、关系和说话方式，不要写成同一种客服腔。
                3. 这是生活中的一个小片段，不是强制剧情事件。通常 2—8 个 turn 即可；可以只是打招呼、坐一会儿、聊最近的小事、提到自己读过的东西、一起看看某样已有物品，也可以有自然的安静和停顿。
                4. 不要为了“产生关系”强行亲密、吵架、告白或制造戏剧冲突。关系应从重复相处、共同经历、记住彼此的小事中慢慢长出来。
                5. action 只写该 speaker 自己的动作、神态和当下可直接感知的环境，不能替另一个角色决定动作或心理；dialogue 只放真正说出口的话，不加引号。
                6. 如果最近生活里出现阅读、日记、群聊、世界活动等经历，可以在人设合适时自然成为话题；不要机械复述，也不要每次都提。
                7. 家园里不能凭空增加家具、房间、食物或道具；共享地点也不要创造永久设施。
                8. summary 只写这次确实发生的事实，方便双方以后记得；不要写分析、好感度数值或系统解释。
            """.trimIndent(),
            source = "角色自主相遇",
            title = "${participantIds.joinToString("与") { characters.getValue(it).displayName }}在$location",
            temperature = 0.88,
            maxTokens = 1_600,
        ).getOrNull()?.text.orEmpty()

        return parseEncounter(result, participantIds)
    }

    private fun parseEncounter(raw: String, participantIds: List<String>): GeneratedEncounter = runCatching {
        val clean = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        val json = JSONObject(if (start >= 0 && end > start) clean.substring(start, end + 1) else clean)
        val allowed = participantIds.toSet()
        val array = json.optJSONArray("turns") ?: JSONArray()
        val turns = buildList {
            for (index in 0 until minOf(array.length(), 10)) {
                val item = array.optJSONObject(index) ?: continue
                val speakerId = item.optString("speakerId").trim()
                if (speakerId !in allowed) continue
                val segmentArray = item.optJSONArray("segments") ?: JSONArray()
                val segments = buildList {
                    for (segmentIndex in 0 until minOf(segmentArray.length(), 8)) {
                        val segment = segmentArray.optJSONObject(segmentIndex) ?: continue
                        val text = segment.optString("text").trim().take(900)
                        if (text.isBlank()) continue
                        val type = when (segment.optString("type").trim().lowercase()) {
                            "dialogue" -> MeetingSegmentType.DIALOGUE
                            "action" -> MeetingSegmentType.ACTION
                            else -> continue
                        }
                        add(MeetingSegment(type, text))
                    }
                }
                if (segments.isNotEmpty()) add(GeneratedTurn(speakerId, segments))
            }
        }
        GeneratedEncounter(
            turns = turns,
            summary = json.optString("summary").trim().replace(Regex("\\s+"), " ").take(800),
        )
    }.getOrDefault(GeneratedEncounter(emptyList(), ""))

    private fun locationLabel(code: String): String = when (code) {
        DigitalWorldStore.ARRIVAL -> "世界入口"
        DigitalWorldStore.CLOUD_MEADOW -> "云眠原"
        else -> if (code.startsWith("home:")) {
            val ownerId = code.removePrefix("home:")
            DigitalWorldStore.state.value.homes[ownerId]?.name
                ?: "${MigratedDomainStores.characters.get(ownerId).displayName}的家"
        } else {
            code
        }
    }

    private data class GeneratedTurn(
        val speakerId: String,
        val segments: List<MeetingSegment>,
    )

    private data class GeneratedEncounter(
        val turns: List<GeneratedTurn>,
        val summary: String,
    )
}
