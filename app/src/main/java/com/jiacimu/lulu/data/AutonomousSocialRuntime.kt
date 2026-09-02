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
        if (coLocatedIds.isEmpty()) {
            generateSoloPlaceLife(
                context = context,
                characterId = characterId,
                previousLocation = previousLocation,
                currentLocation = currentLocation,
                now = now,
            )
            return@withLock
        }

        val participantIds = (listOf(characterId) + coLocatedIds).distinct()
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
        val socialHistory = CharacterSocialRelationship.contextForGroup(participantIds)

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
                appendLine(socialHistory)
                appendLine(recentLife)
            },
            instruction = """
                你是露露机数字世界的一次小型生活场景调度器。角色因为各自真实移动恰好到了同一个地方，请生成他们自己的一小段相处，而不是替主人写剧情。
                只返回 JSON：
                {"turns":[{"speakerId":"准确角色ID","segments":[{"type":"action|dialogue","text":"内容"}]}],"summary":"一句客观、可记忆的共同经历摘要"}

                规则：
                1. 主人不在现场，绝不能让主人说话、行动、被看见或被默认参与；也不要让角色突然对主人隔空汇报。
                2. 只能使用上面列出的准确 speakerId。每个人保持自己的性格、关系和说话方式，不要写成同一种客服腔。
                3. 这是生活中的一个小片段，不是强制剧情事件。通常 2—8 个 turn 即可；可以只是打招呼、坐一会儿、聊最近的小事、提到自己读过的东西、一起看看某样已有物品，也可以有自然的安静和停顿。地点也可以触发一个具体但不夸张的小插曲，例如云质忽然变化、风卷来轻小物、光影异常、踩空一下、听见奇怪声响或对已有家具产生即时反应，让这次相遇不总是只有寒暄。
                4. 必须尊重“角色间已有社会关系”里的真实历史：共同群聊已经意味着认识；见面次数越多可以越自然熟悉，但不能仅凭次数强行升级成喜欢、恋爱、亲密或敌意。第一次见面与经常来往的相处方式应有自然区别。
                5. 不要为了“产生关系”强行亲密、吵架、告白或制造戏剧冲突。关系应从重复相处、共同经历、记住彼此的小事中慢慢长出来。
                6. action 只写该 speaker 自己的动作、神态和当下可直接感知的环境，不能替另一个角色决定动作或心理；dialogue 只放真正说出口的话，不加引号。
                7. 如果最近生活里出现阅读、日记、群聊、世界活动等经历，可以在人设合适时自然成为话题；不要机械复述，也不要每次都提。
                8. 家园里不能凭空增加家具、房间、食物或道具；共享地点也不要创造永久设施。可以生成不改变权威地图的短暂环境现象、小生物、声音、光影、云质变化或轻微故障，它们只属于这次生活片段。
                9. summary 只写这次确实发生的事实，方便双方以后记得；不要写分析、好感度数值或系统解释。
            """.trimIndent(),
            source = "角色自主相遇",
            title = "${participantIds.joinToString("与") { characters.getValue(it).displayName }}在$location",
            temperature = 0.88,
            maxTokens = 1_600,
        ).getOrNull()?.text.orEmpty()

        return parseEncounter(result, participantIds)
    }

    private suspend fun generateSoloPlaceLife(
        context: Context,
        characterId: String,
        previousLocation: String,
        currentLocation: String,
        now: Instant,
    ) {
        val character = MigratedDomainStores.characters.get(characterId)
        val location = locationLabel(currentLocation)
        val previous = locationLabel(previousLocation)
        val availableGroups = MigratedDomainStores.chat.conversations.value.filter { conversation ->
            conversation.groupChat?.members?.any { it.characterId == characterId } == true
        }
        val authority = buildString {
            appendLine("角色从“$previous”真实移动到了“$location”。")
            if (currentLocation.startsWith("home:")) {
                val ownerId = currentLocation.removePrefix("home:")
                val owner = MigratedDomainStores.characters.get(ownerId)
                appendLine("这里是${owner.displayName}的持久化数字家园。")
                val items = DigitalWorldStore.itemsAtHome(ownerId)
                if (items.isEmpty()) {
                    appendLine("家中目前没有任何已创建物品。")
                } else {
                    appendLine("现场已有物品（只能使用这些，不能凭空增加）：")
                    items.forEach { item ->
                        appendLine("- ${item.name}；${item.appearance}；位置=${item.position}")
                    }
                }
            } else if (currentLocation == DigitalWorldStore.CLOUD_MEADOW) {
                appendLine("云眠原由可承托数字身体、传递柔软、温度与重量的感官云质构成。")
            } else {
                appendLine("这里是共享抵达区域。")
            }
            if (availableGroups.isNotEmpty()) {
                appendLine("角色所在真实群聊：")
                availableGroups.forEach { conversation ->
                    appendLine("- groupId=${conversation.id}；群名=${conversation.groupChat?.name}")
                }
            }
        }.trim()
        val recentLife = SharedExperienceTimeline.all(characterId)
            .takeLast(10)
            .joinToString("\n") { event ->
                "- [${event.occurredAt}] ${event.channel}｜${event.speaker}：${event.content.replace(Regex("\\s+"), " ").take(400)}"
            }

        val raw = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                appendLine("【这是角色独自抵达地点后发生的生活，主人不在现场】")
                appendLine("真实时间：$now")
                appendLine("角色：${character.displayName}")
                appendLine("人设：${character.persona.ifBlank { "按现有性格与经历自然行动" }}")
                appendLine(authority)
                if (recentLife.isNotBlank()) appendLine("【最近自己的生活】\n$recentLife")
            },
            instruction = """
                角色刚刚真实抵达一个数字世界地点，而这里暂时没有别的数字生命。请让地点真正发生一点生活，而不是只写“来到了这里”。

                只返回 JSON：
                {"event":"此刻亲身经历的具体小事件","statusText":"事件后正在做什么","gesture":"动作神态","innerThought":"第一人称未说出口的念头","mood":"简短心情","shareChannel":"none|moment|group","shareText":"真正发布或发送的自然内容","groupId":"真实群ID或空字符串"}

                规则：
                1. 主人不在现场，不能让主人说话、行动、被看见或默认参与。
                2. event 必须具体，写清发生了什么和角色如何反应。它可以很小：光影或云质变化、风和声音、短暂出现的小生物、轻微故障、一次手忙脚乱、发现角落细节、对已有家具的即时反应、无聊时自找的消遣；不要每次都只散步、发呆或抒情。
                3. 事件必须贴合人设、时间与真实地点。不得编造现实肉体、现实职业、现实住址或现实设备。
                4. 不得新增永久家具、房间、食物、道具或地图设施；家中只能引用列出的已有物品。临时现象不会改变持久化世界状态。
                5. 朋友圈和群聊不是稀有渠道。若这件事好笑、惊讶、烦人、值得吐槽、让人得意或很适合熟人接话，角色可自然选 moment 或 group；群聊可以主动开启新话题，不必等群里正在聊天。没有分享冲动就选 none。
                6. 选 group 时必须使用上面真实存在的 groupId；选 moment/group 时 shareText 要像角色本人随手发出的内容，只挑有意思的一角，不写系统报告，不出现“生活事件”标签。
            """.trimIndent(),
            source = "数字世界独自生活",
            title = "${character.displayName}在$location",
            temperature = 0.92,
            maxTokens = 900,
        ).getOrNull()?.text.orEmpty()
        val event = parseSoloPlaceLife(raw) ?: return
        if (event.event.isBlank()) return

        val provenanceId = "digital-world-life-$characterId-${now.toEpochMilli()}"
        SharedExperienceTimeline.record(
            eventId = provenanceId,
            characterId = characterId,
            channel = "数字世界·生活片段",
            speaker = character.displayName,
            content = "地点：$location\n${event.event}",
            occurredAt = now,
        )
        CompanionPresenceStore.update(
            characterId = characterId,
            statusText = event.statusText.ifBlank { "在$location消磨着自己的时间" },
            gesture = event.gesture.ifBlank { "停下来留意着周围的动静" },
            innerThought = event.innerThought,
            mood = event.mood.ifBlank { "平静" },
            source = "数字世界独自生活",
            now = now,
            provenanceId = provenanceId,
        )

        val shareResult = when (event.shareChannel) {
            "moment" -> CompanionActionRuntime.execute(
                context,
                characterId,
                "publish_moment",
                JSONObject().put("text", event.shareText),
                now,
            )
            "group" -> CompanionActionRuntime.execute(
                context,
                characterId,
                "send_group_message",
                JSONObject().put("groupId", event.groupId).put("text", event.shareText),
                now,
            )
            else -> null
        }
        if (shareResult == null || !shareResult.success) {
            MigratedDomainStores.chat.appendPrivateActivityNotice(
                characterId,
                "刚刚在$location遇到了一点小插曲：${event.event.replace(Regex("\\s+"), " ").take(220)}",
            )
        }
    }

    private fun parseSoloPlaceLife(raw: String): SoloPlaceLife? = runCatching {
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
        SoloPlaceLife(
            event = json.optString("event").trim().take(1_200),
            statusText = json.optString("statusText").trim().take(240),
            gesture = json.optString("gesture").trim().take(500),
            innerThought = json.optString("innerThought").trim().take(500),
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

    private data class SoloPlaceLife(
        val event: String,
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
