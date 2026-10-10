package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Turns a character's real digital-world movement into shared life when other digital lives are
 * already at the destination. The user is not a participant: this is character-to-character life.
 */
internal object AutonomousSocialRuntime {
    private val encounterMutex = Mutex()

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
            !currentLocation.startsWith("home:") &&
            !DigitalWorldPublicPlaces.contains(currentLocation)
        ) return@withLock

        val worldTick = DigitalWorldLifeEventStore.tick(
            context = context,
            characterId = characterId,
            now = now,
            arrivalBoost = true,
        )
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
        if (coLocatedIds.isEmpty()) {
            worldTick?.let {
                generateSoloIncidentReaction(
                    context = context,
                    characterId = characterId,
                    previousLocation = previousLocation,
                    tick = it,
                    now = now,
                )
            }
            return@withLock
        }

        val participantIds = (listOf(characterId) + coLocatedIds).distinct()
        worldTick?.let { tick ->
            coLocatedIds.forEach { id -> DigitalWorldLifeEventStore.recordWitness(id, tick, now) }
        }
        val location = locationLabel(currentLocation)
        val participantSet = participantIds.toSet()
        val activeOverlap = world.meetings.any { session ->
            session.endedAt == null &&
                session.location == location &&
                session.participantIds.any { it in participantSet }
        }
        // Only reject a genuinely overlapping live session. Once a meeting has ended, the same
        // characters may meet again immediately if their own later movement brings them together.
        // Repetition is a character choice, not a system cooldown policy.
        if (activeOverlap) return@withLock

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

        val generated = generateEncounter(session, participantIds, characters, location, worldTick, now)
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
            "${names.joinToString("、")}在${location}自然碰面，一起度过了一小段属于他们自己的时间。"
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
        worldTick: DigitalWorldLifeTick?,
        now: Instant,
    ): GeneratedEncounter {
        val currentLocationCode = DigitalWorldStore.locationOf(participantIds.first())
        val homeOwnerId = currentLocationCode
            .takeIf { it.startsWith("home:") }
            ?.removePrefix("home:")
        val publicPlace = DigitalWorldPublicPlaces.all.firstOrNull { it.code == currentLocationCode }
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
                publicPlace != null -> {
                    appendLine("这里是持久存在的公共地点“${publicPlace.label}”：${publicPlace.purpose}")
                    val activities = DigitalWorldActivityCatalog.locationOptions(publicPlace.code)
                    if (activities.isNotEmpty()) {
                        appendLine("这里真实支持的日常活动：${activities.joinToString("、") { it.second }}。只能围绕这些已存在的设施与用途相处，不得凭空增加店铺、机台、食物、书籍或新区域。")
                    }
                }
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
                        appendLine("- [${event.occurredAt}] ${event.channel}｜${event.speaker}：${event.evidenceContent.replace(Regex("\\s+"), " ").take(500)}")
                    }
                }
            }
        }.trim()
        val socialHistory = CharacterSocialRelationship.contextForGroup(participantIds)

        val anchorId = participantIds.first()
        val result = LuluAiServices.gateway.generate(
            characterId = anchorId,
            facts = buildString {
                appendLine("【这是一场角色与角色自己的生活，主人不在现场】")
                appendLine("真实时间：$now")
                appendLine(authority)
                if (worldTick != null) {
                    appendLine("【本轮程序权威环境事件｜所有参与者亲眼经历】")
                    appendLine(worldTick.summary)
                    appendLine("incidentId=${worldTick.incidentId}；status=${worldTick.status}；stage=${worldTick.stage}；anchorItemId=${worldTick.anchorItemId}")
                } else {
                    appendLine("【本轮程序环境事件】无；不得自行添加蟑螂、声响、光影、天气、小生物、故障或其他插曲。")
                }
                appendLine("参与角色与准确 ID：")
                participantIds.forEach { id ->
                    val character = characters.getValue(id)
                    appendLine("- id=$id")
                    appendLine(CharacterRuntime.definition(id).promptSection())
                }
                appendLine(socialHistory)
                appendLine(recentLife)
            },
            instruction = """
                你是露露机数字世界的一次小型生活场景调度器。角色因为各自真实移动恰好到了同一个地方，请生成他们自己的一小段相处，而不是替主人写剧情。
                只返回 JSON：
                {"turns":[{"speakerId":"准确角色ID","segments":[{"type":"action|dialogue","text":"内容","speechText":"音频轨，可为空"}]}],"summary":"一句客观、可记忆的共同经历摘要"}

                规则：
                1. 主人不在现场，绝不能让主人说话、行动、被看见或被默认参与；也不要让角色突然对主人隔空汇报。
                2. 只能使用上面列出的准确 speakerId。每个人保持自己的性格、关系和说话方式，不要写成同一种客服腔。
                3. 这是生活中的一个小片段，不是强制剧情事件。通常 2—8 个 turn 即可；可以打招呼、坐一会儿、聊最近的真实记录、一起看看某样已有物品，也可以有自然的安静和停顿。只有上面明确给出【本轮程序权威环境事件】时才能让角色现场反应，不能自行触发任何环境插曲。
                4. 必须尊重“角色间已有社会关系”里的真实历史：共同群聊已经意味着认识；见面次数越多可以越自然熟悉，但不能仅凭次数强行升级成喜欢、恋爱、亲密或敌意。第一次见面与经常来往的相处方式应有自然区别。
                5. 不要为了“产生关系”强行亲密、吵架、告白或制造戏剧冲突。关系应从重复相处、共同经历、记住彼此的小事中慢慢长出来。
                发声单独放speechText：dialogue保留text完全相同的原话，只加入英文音频标签；action只放实际事件的音效标签，不念正文。不重复同一动作音效。
                ${com.jiacimu.lulu.VoicePerformance.direction}
                6. action 只写该 speaker 自己的动作、神态和当下可直接感知的环境，不能替另一个角色决定动作或心理；dialogue 只放真正说出口的话，不加引号。
                7. 如果最近生活里出现阅读、日记、群聊、世界活动等经历，可以在人设合适时自然成为话题；不要机械复述，也不要每次都提。
                8. 家园里不能凭空增加家具、房间、食物或道具；共享地点也不能创造设施或短暂环境现象。蟑螂、小生物、声音、光影、云质变化与故障只有程序事件明确提供时才存在，且不得改写其阶段或解决状态。
                9. summary 只写这次确实发生的事实，方便双方以后记得；不要写分析、好感度数值或系统解释。
            """.trimIndent(),
            source = "角色自主相遇",
            title = "${participantIds.joinToString("与") { characters.getValue(it).displayName }}在$location",
            maxTokens = 1_600,
        ).getOrNull()?.text.orEmpty()

        return parseEncounter(result, participantIds)
    }

    private suspend fun generateSoloIncidentReaction(
        context: Context,
        characterId: String,
        previousLocation: String,
        tick: DigitalWorldLifeTick,
        now: Instant,
    ) {
        val character = MigratedDomainStores.characters.get(characterId)
        val previous = locationLabel(previousLocation)
        val availableGroups = MigratedDomainStores.chat.conversations.value.filter { conversation ->
            conversation.groupChat?.members?.any { it.characterId == characterId } == true
        }
        val raw = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                appendLine("【程序已经写入的唯一现场事实】")
                appendLine("角色从“$previous”真实移动到了“${tick.locationName}”。")
                appendLine(tick.summary)
                appendLine("incidentId=${tick.incidentId}；status=${tick.status}；stage=${tick.stage}；anchorItemId=${tick.anchorItemId}；anchorItemName=${tick.anchorItemName}")
                appendLine("角色：${character.displayName}")
                if (availableGroups.isNotEmpty()) {
                    appendLine("角色所在真实群聊：")
                    availableGroups.forEach { conversation ->
                        appendLine("- groupId=${conversation.id}；群名=${conversation.groupChat?.name}")
                    }
                }
            },
            instruction = """
                你只负责让角色对程序已经确认并保存的现场事实作出反应，不能创造、移动、解决或改写事件。
                ${spontaneousInnerVoiceGuide}
                只返回 JSON：
                {"statusText":"事件后正在做什么","gesture":"动作神态","innerThought":"第一人称未说出口的念头","mood":"简短心情","shareChannel":"none|moment|group","shareText":"真正发布或发送的自然内容","groupId":"真实群ID或空字符串"}

                规则：
                1. 主人不在现场，不能让主人说话、行动、被看见或默认参与。
                2. 只能引用给出的准确地点、事件和真实家具。不得添加其他物品、食物、天气、声响、小生物、故障或后续结果。
                3. status=active 时事件尚未解决；角色的文字和动作不能擅自把它抓住、清除、修好或解释清楚。
                4. 朋友圈和群聊不是稀有渠道。若真实事件好笑、惊讶、烦人或适合熟人接话，可自然选择 moment 或 group；没有分享冲动就选 none。
                5. group 必须使用真实 groupId。shareText 可以有角色口吻和情绪，但其中每个事实都必须来自上面的程序记录。
            """.trimIndent(),
            source = "数字世界事件反应",
            title = "${character.displayName}在${tick.locationName}",
            maxTokens = 700,
        ).getOrNull()?.text.orEmpty()
        val reaction = parseSoloIncidentReaction(raw)
        if (reaction == null) {
            if (!DigitalWorldLifeEventStore.isAmbientMoment(tick) ||
                DigitalWorldLifeEventStore.isNoticeableLifeMoment(tick)) {
                MigratedDomainStores.chat.appendPrivateActivityNotice(
                    characterId, tick.summary, tick.incidentId,
                )
            }
            return
        }

        val provenanceId = "world-incident-reaction-${tick.incidentId}-$characterId-${now.toEpochMilli()}"
        CompanionPresenceStore.update(
            characterId = characterId,
            statusText = "在${tick.locationName}，${tick.summary}",
            gesture = null,
            innerThought = reaction.innerThought,
            mood = reaction.mood,
            source = "数字世界事件反应",
            now = now,
            provenanceId = provenanceId,
        )

        val shareResult = when (reaction.shareChannel) {
            "moment" -> CompanionActionRuntime.execute(
                context,
                characterId,
                "publish_moment",
                JSONObject().put("text", reaction.shareText),
                now,
            )
            "group" -> CompanionActionRuntime.execute(
                context,
                characterId,
                "send_group_message",
                JSONObject().put("groupId", reaction.groupId).put("text", reaction.shareText),
                now,
            )
            else -> null
        }
        if ((shareResult == null || !shareResult.success) &&
            (!DigitalWorldLifeEventStore.isAmbientMoment(tick) ||
                DigitalWorldLifeEventStore.isNoticeableLifeMoment(tick))) {
            MigratedDomainStores.chat.appendPrivateActivityNotice(
                characterId, tick.summary, tick.incidentId,
            )
        }
    }

    private fun parseSoloIncidentReaction(raw: String): SoloIncidentReaction? = runCatching {
        val clean = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .let { value ->
                val start = value.indexOf('{')
                val end = value.lastIndexOf('}')
                if (start >= 0 && end > start) value.substring(start, end + 1) else value
            }
        val json = JSONObject(clean)
        SoloIncidentReaction(
            statusText = json.optString("statusText").trim().take(240),
            gesture = json.optString("gesture").trim().take(500),
            innerThought = json.optString("innerThought").trim().take(1_200),
            mood = json.optString("mood").trim().take(80),
            shareChannel = json.optString("shareChannel").trim().lowercase(),
            shareText = json.optString("shareText").trim().take(2_000),
            groupId = json.optString("groupId").trim(),
        )
    }.getOrNull()

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
                        add(com.jiacimu.lulu.VoicePerformance.meetingSegment(type, text, segment.optString("speechText")))
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
        else -> DigitalWorldPublicPlaces.label(code) ?: if (code.startsWith("home:")) {
            val ownerId = code.removePrefix("home:")
            DigitalWorldStore.state.value.homes[ownerId]?.name
                ?: "${MigratedDomainStores.characters.get(ownerId).displayName}的家"
        } else {
            code
        }
    }

    private data class SoloIncidentReaction(
        val statusText: String,
        val gesture: String,
        val innerThought: String,
        val mood: String,
        val shareChannel: String,
        val shareText: String,
        val groupId: String,
    )

    private data class GeneratedTurn(
        val speakerId: String,
        val segments: List<MeetingSegment>,
    )

    private data class GeneratedEncounter(
        val turns: List<GeneratedTurn>,
        val summary: String,
    )
}
