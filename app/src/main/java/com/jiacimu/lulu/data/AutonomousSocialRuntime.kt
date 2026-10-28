package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections
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
        ScopedModelSelections.initialize(context.applicationContext)
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
            // Up to four participants, still only one role decision per person.
            .take(AutonomousEncounterScript.MAX_PARTICIPANTS - 1)
            .toList()
        if (coLocatedIds.isEmpty()) {
            // Routine walking already has its own one-call narrative. A
            // freshly generated ambient footstep or ordinary stay must not
            // buy ANOTHER model call just because the location changed.
            // Substantive persisted incidents still deserve one reaction.
            worldTick?.takeIf { tick ->
                !DigitalWorldLifeEventStore.isAmbientMoment(tick) ||
                    DigitalWorldLifeEventStore.isNoticeableLifeMoment(tick)
            }?.let {
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
        if (generated.turns.isEmpty() && generated.privateReactions.isEmpty()) {
            // The encounter happened even if no valid model decision arrived.
            // Keep its evidence; do not turn a request failure into voluntary silence.
            DigitalWorldStore.endMeeting(session.id, now)
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
            // The speaker independently authored this public turn. Every
            // witness keeps the same spoken facts under an OWN evidence ID,
            // but nobody receives another role's private feelings or motives.
            // The owner's private reaction is committed only after the
            // meeting is actually saved below.
            // This also prevents the shared narrator from cloning one
            // character's emotional state into several personal minds.
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

        // Real shared evidence may trigger different PRIVATE meanings. Store
        // each actor's own model proposal only under their own characterId,
        // after the referenced meeting has actually been committed.
        participantIds.forEach { id ->
            val reaction = generated.privateReactions[id] ?: return@forEach
            val savedSession = DigitalWorldStore.state.value.meetings.firstOrNull { it.id == session.id }
                ?: return@forEach
            val ownEvidence = reactionEvidenceFor(savedSession, id)
            val observed = SharedExperienceTimeline.eventsByIds(id, listOf(ownEvidence)).firstOrNull()
                ?: return@forEach
            runCatching {
                CharacterInnerLifeStore.observe(
                    id, ownEvidence, observed.content, reaction.optJSONObject("innerLife"),
                    participantIds.filterNot { it == id }.toSet(), finishedAt)
                CharacterInnerLifeStore.recordInnerVoice(
                    id, ownEvidence, reaction.optString("innerThought"), finishedAt)
                // Execute this role's OWN social decisions after the shared
                // meeting facts exist. Friend requests, messages and group
                // invitations use the SAME ToolRouter receipts as other scenes.
                val socialSteps = reaction.optJSONArray("steps")
                if (socialSteps != null && socialSteps.length() > 0) {
                    val bounded = JSONArray().apply {
                        for (index in 0 until minOf(3, socialSteps.length()))
                            put(socialSteps.opt(index))
                    }
                    val result = OrderedCharacterActions.execute(
                        context, id, bounded,
                        requestId = "encounter-social:$ownEvidence",
                        autonomousLifeTurn = true,
                        sceneAllowedTools = setOf(
                            "send_friend_request", "send_character_message",
                            "create_group_chat", "send_group_message",
                            "plan_personal_activity",
                        ),
                    )
                    // Failed, malformed or forbidden scene steps used to be
                    // only local report strings. Persist one role-owned decision
                    // summary as well as the executor's per-tool receipts, so
                    // subsequent personal reflection can distinguish intention,
                    // partial delivery and genuine completion.
                    CharacterInnerLifeStore.recordDecision(
                        characterId = id, decisionId = "encounter-social:$ownEvidence",
                        selectedAction = "social_steps",
                        reason = reaction.optString("reason").take(200),
                        chosenMotiveId = "", alternatives = null,
                        outcome = result.notes.joinToString("；").take(230)
                            .ifBlank { "未产生可执行的社交回执" },
                        succeeded = result.completedCount == bounded.length(),
                        now = finishedAt, causalEvidenceId = ownEvidence,
                    )
                }
                // A role's explicit personal activity uses the same existing
                // executor; it never treats a merely spoken invite as consent.
                val decided = reaction.optJSONObject("appraisal")?.optJSONObject("ownActivity")
                if (decided != null && decided.optString("stage") == "decided") {
                    val queued = CharacterSelfActivityRuntime.offer(
                        context, id, reaction.optJSONObject("appraisal"), ownEvidence)
                    if (!queued) CharacterInnerLifeStore.recordDecision(
                        characterId = id, decisionId = "encounter-unqueued:$ownEvidence",
                        selectedAction = decided.optString("action").ifBlank { "digital_world" },
                        reason = decided.optString("whyNow").take(200),
                        chosenMotiveId = "", alternatives = null,
                        outcome = "尚未排程：活动参数或当前执行条件未通过校验",
                        succeeded = false, now = finishedAt, causalEvidenceId = ownEvidence)
                }
            }.onFailure { error ->
                com.jiacimu.lulu.LuluRepositories.performance.recordError(
                    source = "数字世界·相遇心理",
                    title = "个人相遇反应保存失败",
                    message = error.message.orEmpty().take(130))
            }
        }

        // One isolated literary call produces each witnessed POV from the
        // SAME public scene. Never enqueue N independent prose requests.
        // Silence may be meaningful, but buys no automatic chapter.
        if (generated.turns.isEmpty()) return@withLock
        val committedSession = DigitalWorldStore.state.value.meetings
            .firstOrNull { it.id == session.id } ?: return@withLock
        AutonomousEncounterChapters.enqueue(
            context = context, meeting = committedSession,
            locationCode = currentLocation, worldTick = worldTick,
        )
    }

    /** Each choice saw only its own and earlier public turns. Later answers
     * stay unacknowledged for the original personal perception loop to digest. */
    internal fun reactionEvidenceFor(session: MeetingSession, viewerId: String): String {
        val index = session.participantIds.indexOf(viewerId)
        require(index >= 0) { "Reaction owner was not present" }
        val visibleSpeakers = session.participantIds.take(index + 1).toSet()
        val lastSeen = session.turns.lastOrNull { it.speakerId in visibleSpeakers }
        return if (lastSeen == null) "meeting-${session.id}-autonomous-start-viewer-$viewerId"
        else "meeting-${session.id}-autonomous-turn-${lastSeen.id}-viewer-$viewerId"
    }

    /** Only actually recorded, viewer-owned evidence enters that viewer's novel. */
    internal fun witnessedEpisodeSources(
        session: MeetingSession, viewerId: String,
        tick: DigitalWorldLifeTick?, at: Instant,
    ): List<String> = buildList {
        add("meeting-${session.id}-autonomous-start-viewer-$viewerId")
        tick?.let { add("world-fact-${it.incidentId}-$viewerId-${at.toEpochMilli()}") }
        session.turns.forEach { turn ->
            add("meeting-${session.id}-autonomous-turn-${turn.id}-viewer-$viewerId")
        }
        add("meeting-${session.id}-autonomous-summary-viewer-$viewerId")
    }.distinct().takeLast(32)

    /**
     * Shared-scene facts are an intersection of the participants' witness sets,
     * never a union of their separate timelines or subjective records.
     * A meeting is eligible only if every viewer really attended, it ended,
     * and the recorded conversation had at least one committed turn.
     *
     * This pure projection supplies only public facts to each role-owned encounter turn.
     */
    internal fun witnessedSharedEncounterContext(
        participants: List<String>,
        meetings: List<MeetingSession>,
    ): String {
        val everyone = participants.map(String::trim).filter(String::isNotBlank).distinct()
        if (everyone.size < 2) return ""
        val shared = meetings.asSequence()
            .filter { meeting ->
                meeting.reality == MeetingReality.DIGITAL_WORLD &&
                    meeting.endedAt != null &&
                    meeting.turns.isNotEmpty() &&
                    everyone.all(meeting.participantIds::contains)
            }.sortedBy { it.endedAt }.toList().takeLast(3)
        if (shared.isEmpty()) return ""
        return buildString {
            appendLine("【所有在场人物确实共同见证过的会面｜仅已说出口的话与现场动作】")
            shared.forEach { meeting ->
                appendLine("- 共同会面 id=${meeting.id}，地点=${meeting.location}，结束=${meeting.endedAt}")
                meeting.turns.takeLast(6).forEach { turn ->
                    val spoken = turn.dialogue.trim()
                    val visible = turn.sceneText.trim()
                    if (spoken.isNotBlank() || visible.isNotBlank()) {
                        appendLine("  ${turn.speakerName}：${spoken.take(160)}" +
                            if (visible.isBlank()) "" else "；现场=${visible.take(120)}")
                    }
                }
            }
            appendLine("这些只证明所有人当时在场并听到/看到这些内容，不代表他们形成相同理解。")
        }.trim()
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
                        appendLine("这里真实支持的日常活动：${activities.joinToString("、") { "${it.first}=${it.second}" }}。只能围绕这些已存在的设施与用途相处，不得凭空增加店铺、机台、食物、书籍或新区域。")
                    }
                }
                else -> appendLine("这里是数字世界的共享抵达区域。")
            }
        }.trim()

        // A group-scene generator is NOT any participant's private mind.
        // Its previous context contains only words spoken at actual meetings
        // attended by ALL participants. No person's private conversations,
        // diaries, memories, emotions or account relationship assumptions.
        val sharedPast = witnessedSharedEncounterContext(
            participantIds, DigitalWorldStore.state.value.meetings)
        val socialHistory = CharacterSocialRelationship.contextForGroup(participantIds)

        // One optional public scene adviser + one private decision per role.
        // A single final literary pass later supplies the shared chapters.
        val sceneIdea = suggestEncounterPossibilities(
            participantIds, characters, authority, sharedPast, socialHistory, worldTick, now)
        val proposals = linkedMapOf<String, List<AutonomousEncounterScript.Beat>>()
        val privateReactions = mutableMapOf<String, JSONObject>()
        for (speakerId in participantIds) {
            val peers = participantIds.filterNot { it == speakerId }
            val previous = AutonomousEncounterScript.order(participantIds, proposals)
                .joinToString("\n") { beat ->
                    val name = characters.getValue(beat.speakerId).displayName
                    val proposed = beat.turn.optJSONArray("segments") ?: JSONArray()
                    val lines = (0 until proposed.length()).mapNotNull { index ->
                        proposed.optJSONObject(index)?.optString("text")?.trim()
                            ?.takeIf(String::isNotBlank)
                    }
                    "阶段${beat.phase} · $name：${lines.joinToString("；")}"
                }
            val selectedModel = runCatching { ScopedModelSelections.worldSocialConnection(speakerId) }
            if (selectedModel.isFailure) {
                com.jiacimu.lulu.LuluRepositories.performance.recordError(
                    source = "数字世界·人物相遇", title = "角色模型配置不可用",
                    message = selectedModel.exceptionOrNull()?.message.orEmpty().take(160))
                continue // Never silently route an unconfigured resident through an expensive model.
            }
            val roleModel = selectedModel.getOrNull()
            val sceneFacts = buildString {
                    appendLine("【你本人在数字世界的真实相遇；主人不在现场】")
                    appendLine("当前时间=$now；$authority")
                    appendLine(DigitalWorldEnvironment.snapshot(now).context())
                    if (worldTick == null) {
                        appendLine("本轮没有新环境事件；不得创造天气、事故、道具或故障。")
                    } else {
                        appendLine("本轮已发生且亲眼见到的世界事件：${worldTick.summary}")
                        appendLine("incidentId=${worldTick.incidentId}；status=${worldTick.status}；stage=${worldTick.stage}")
                        if (worldTick.status == "active") {
                            appendLine(DigitalWorldLifeEventStore.contextFor(speakerId))
                        }
                    }
                    appendLine("确实在场的其他人（不包含他们的私人档案）：")
                    peers.forEach { id -> appendLine("- $id：${characters.getValue(id).displayName}") }
                    appendLine("已有共同亲历的公开会面：${sharedPast.ifBlank { "暂无" }}")
                    appendLine("双方共同可见的现有关系事实：$socialHistory")
                    appendLine("你本人对对方的主观看法（对方未必知道）：")
                    appendLine(CharacterSocialRelationship.perspectivesFor(speakerId, peers))
                    if (sceneIdea.isNotBlank()) appendLine("【可忽略的创意提议，并非已发生事实】$sceneIdea")
                    appendLine("先前在场人物已自己选择的分阶段公开台词与动作草案（尚未提交）：")
                    appendLine(previous.ifBlank { "当前无人说话" })
                    appendLine("你本人的当下心理与持续目标，仅供你自己选择如何回应：")
                    appendLine(CharacterInnerLifeStore.compactContext(speakerId, now).take(1300))
                }
            val sceneInstruction = """
                    你是${characters.getValue(speakerId).displayName}本人，绝不是替几个人编台词的导演。
                    自己选择是否与眼前的人交流、做个小动作，或者安静；别人也有自己的意愿。
                    只返回 JSON：
                    {"choice":"engage|quiet","reason":"本人为何参与","beats":[{"phase":1,"segments":[{"type":"dialogue|action","text":"开场阶段本人真正愿意说的台词或可见小动作"}]},{"phase":2,"segments":[{"type":"dialogue|action","text":"第二阶段本人想说或做的事"}]}],"steps":[{"tool":"send_friend_request|send_character_message|create_group_chat|send_group_message|plan_personal_activity","args":{"targetCharacterId":"已认识对象ID","text":"本人真实想发送的内容"},"requiresPreviousSuccess":false}],"innerThought":"本人未出口的念头","innerLife":{"emotion":{"feeling":"真实感受","cause":"已有依据"},"social":{"targetId":"在场人物ID","interpretation":"个人理解","reason":"已有证据"},"motives":[{"op":"start","aim":"未来想做的事","why":"缘由","reason":"依据"}]}}
                    这是一场完整的生活戏而不是一句话。你仅调用一次模型，却可以在 beats 中提出阶段1到5的数段自己的公开言语/动作；没有想说的阶段可省略，不能强凑。允许开场、推进、不同看法、转向及余味。
                    phase 只能是1至5整数且每个编号最多一次；每阶段 segments 为1至8项，每项 text 为1至900字符；各段是你自己愿意实施的动作，不是替其他人写剧本。
                    你看得见前面参与者已经提议的公开阶段台词，可在相同阶段对这些真实台词作回应，也可打趣、婉拒或安静。你并不知道后续尚未选择的人会说什么，不能提前声称他们答应或替他们表态。
                    未来阶段只写即使他人没有按预想发言也能独立成立的本人行为，不得演出尚未执行的共同活动和结果。
                    choice=quiet 时 beats 为空；若想开口或做动作，choice=engage，reason 不为空。
                    允许说话、小玩笑、观念不同、邀请、婉拒、主动帮忙、关心、紧张和不喜欢；
                    不要求每轮发生大事，更不强制每个人成为朋友。根据真实关系和个人性格自然选择。
                    如果已经有人说话，优先决定是否要回应这句真正说出口的话，不要机械重新寒暄。
                    仅可记录本人公开发言和微动作；不能替对方表演、宣布对方心意、替对方接受邀请。
                    提出一起玩、下次见、加好友可以是口头愿望，不等于完成游戏、预约或建立好友关系。
                    如果真的决定立即加对方好友、给已认识的人发QQ私信、与已确认好友组群或安排以后主动联系，可在 steps 填真实工具及最少必要 args；没有决定就省略 steps。send_friend_request 的 targetCharacterId 必须是本次真实认识的人；send_character_message 只能给可联系的熟人；建群的 memberIds 只能来自本人已经确认的好友，不能自动拉陌生人或主人。每步需要独立成功回执；申请发出不等于对方接受，邀约说出口不等于对方答应。最多三步，不能替他人执行。
                    任何地点移动、物品出现、咖啡消费、比赛结果和世界事件完成，都必须有真实执行器回执。
                    私人想法只回传到本人心理系统，不能当作公开事实，不能越权知晓其他人私聊、记忆或日记。
                    你可以对其他人的实际行为形成个人评价、挂心或未来意愿，但没有证据就留空。
                    若此次相遇让你确实想马上做件有结果的事，可另选填 appraisal={"ownActivity":{"stage":"decided","action":"digital_world|solo_game","worldAction":"use_location|handle_incident","activityId":"上文列出的当前地点真实活动ID","incidentId":"真实持续事件ID","approach":"该事件支持的操作","gameId":"memory_match","whyNow":"此刻自己决定行动的原因"}}。
                    独立游戏选 solo_game + memory_match；世界动作选 digital_world，按真实参数填写。没有当场行动的意愿就完全省略 appraisal。
                    你只能替自己决定后续行动，不能让对方也执行；必须经已有执行器验证成功后才算做过，邀请别人不等于别人同意。
                    不必每次生成所有 innerLife 字段；本轮的公开事实记录完毕后，才能保存本人的反应。
                    ${com.jiacimu.lulu.VoicePerformance.direction}
                """.trimIndent()
            val raw = LuluAiServices.gateway.generate(
                characterId = speakerId, facts = sceneFacts, instruction = sceneInstruction,
                source = "数字世界·人物独立相遇", title = "${characters.getValue(speakerId).displayName}在$location",
                maxTokens = 2100,
                contextMode = com.jiacimu.lulu.ai.CompanionContextMode.CharacterAndScenario,
                connectionOverride = roleModel,
            ).getOrNull()?.text.orEmpty()
            // No automatic retry of an empty/unknown network response: it may
            // already have been charged. Only actual returned contract violations
            // get one same-role correction with the exact original scene.
            val outcome = if (raw.isBlank()) AutonomousEncounterDecision.Outcome(
                null, raw, null, "模型未返回决定，未视为本人选择安静")
            else AutonomousEncounterDecision.resolve(raw) { error ->
                LuluAiServices.gateway.generate(
                    characterId = speakerId, facts = sceneFacts,
                    instruction = sceneInstruction + "\n【仅补正本次结构错误】$error\n" +
                        "保留你自己的真实意愿；不要因格式错误改成安静。原输出：\n$raw",
                    source = "数字世界·相遇结构补正",
                    title = "${characters.getValue(speakerId).displayName}的相遇决定补正",
                    maxTokens = 2100,
                    contextMode = com.jiacimu.lulu.ai.CompanionContextMode.CharacterAndScenario,
                    connectionOverride = roleModel,
                ).getOrNull()?.text.orEmpty()
            }
            val decision = outcome.decision
            if (decision == null) {
                val evidence = "meeting-${session.id}-autonomous-start-viewer-$speakerId"
                CharacterInnerLifeStore.recordDecision(
                    characterId = speakerId, decisionId = "encounter-invalid:${session.id}:$speakerId",
                    selectedAction = ModelStructuredOutput.objectOrNull(raw)?.optString("choice").orEmpty()
                        .ifBlank { "invalid_output" },
                    reason = ModelStructuredOutput.objectOrNull(raw)?.optString("reason").orEmpty(),
                    chosenMotiveId = "", alternatives = null,
                    outcome = "未执行：${outcome.error}；未替人物选择沉默", succeeded = false,
                    now = now, causalEvidenceId = evidence)
                com.jiacimu.lulu.LuluRepositories.performance.recordError(
                    source = "数字世界·相遇结构补正", title = "本人决定未能执行",
                    message = outcome.error.orEmpty())
                continue
            }
            if (decision.optString("choice") == "quiet") {
                privateReactions[speakerId] = decision
                continue
            }
            val verified = AutonomousEncounterScript.approvedBeats(speakerId, decision)
                .filter { beat ->
                    parseEncounter(JSONObject().put("turns", JSONArray().put(beat.turn)).toString(),
                        listOf(speakerId)).turns.isNotEmpty()
                }
            if (verified.isEmpty()) continue
            proposals[speakerId] = verified
            privateReactions[speakerId] = decision
        }
        // A deterministic stage scheduler can weave a whole scene with no
        // extra role-model calls. It never invents a spoken line or action.
        val turns = AutonomousEncounterScript.order(participantIds, proposals)
            .mapNotNull { beat ->
                parseEncounter(JSONObject().put("turns", JSONArray().put(beat.turn)).toString(),
                    listOf(beat.speakerId)).turns.firstOrNull()
            }
        val summary = buildString {
            append("${participantIds.joinToString("、") { characters.getValue(it).displayName }}在${location}相遇。")
            if (turns.isEmpty() && privateReactions.isNotEmpty())
                append("本次没有公开发言或动作；个人感受由各自保存，不代表彼此知道。")
            turns.forEach { turn ->
                append("\n${characters.getValue(turn.speakerId).displayName}：")
                append(turn.segments.joinToString("；") { it.text })
            }
        }.take(2400)
        return GeneratedEncounter(turns, summary, privateReactions)
    }

    /** An isolated public-world adviser: suggestions, never authored actions. */
    private suspend fun suggestEncounterPossibilities(
        participants: List<String>,
        characters: Map<String, CharacterSettings>,
        locationFacts: String,
        sharedPast: String,
        publicRelations: String,
        tick: DigitalWorldLifeTick?,
        at: Instant,
    ): String {
        val sceneConnection = runCatching { ScopedModelSelections.worldSceneConnection() }
            .getOrNull() ?: return ""
        val suggestion = LuluAiServices.gateway.generate(
            characterId = participants.first(),
            facts = buildString {
                appendLine("【已确认场景】$locationFacts")
                appendLine("时间：$at；${DigitalWorldEnvironment.snapshot(at).context()}")
                if (tick != null) appendLine("现场真实事件：${tick.summary}；状态=${tick.status}")
                else appendLine("没有新环境事件。")
                appendLine("在场人物的公开名称：")
                participants.forEach { id -> appendLine("$id：${characters.getValue(id).displayName}") }
                appendLine("所有人曾共同见到的公开事实：${sharedPast.ifBlank { "暂无" }}")
                appendLine("共同可核验关系：$publicRelations")
            },
            instruction = """
                你是场景创意顾问，不是任何人物，也不是世界执行器。
                根据现有地点、公开关系与已发生的真实事件，提出一到两个可能有趣的交流切入点。
                可以是不同兴趣的碰撞、轻微误会的解释、自然打趣、分享旧话题、活动邀约。
                不强制制造冲突、浪漫或高潮；普通的有趣小事同样值得记录。
                只返回 JSON：{"angle":"一个可选切入角度","options":["一个现有事实支持的机会"],"phases":[{"phase":1,"direction":"开场"},{"phase":2,"direction":"推进"},{"phase":3,"direction":"差异与试探"},{"phase":4,"direction":"转向"},{"phase":5,"direction":"收束"}]}。
                phases 只是分阶段的公开机会，不是发生结果，不能安排人物台词或强迫人物按剧情行事。
                不得新建物件、事故、天气、比赛结果，不得替任何人物写台词、替他们接受邀请、
                设定他们的私人愿望或宣布关系变化。
                这些只供本人各自决策时参考，不算现场事实，不写入时间线。
            """.trimIndent(),
            source = "数字世界·公开场景创意",
            title = "相遇时的互动可能性",
            maxTokens = 360,
            contextMode = com.jiacimu.lulu.ai.CompanionContextMode.Isolated,
            connectionOverride = sceneConnection,
        ).getOrNull()?.text.orEmpty()
        val json = ModelStructuredOutput.objectOrNull(suggestion) ?: return ""
        val angle = json.optString("angle").trim().take(180)
        val options = json.optJSONArray("options")
        val ideas = (0 until minOf(2, options?.length() ?: 0)).mapNotNull { index ->
            options?.optString(index)?.trim()?.take(130)?.takeIf(String::isNotBlank)
        }
        val phases = json.optJSONArray("phases")
        val stageNotes = (0 until minOf(AutonomousEncounterScript.MAX_PHASES,
            phases?.length() ?: 0)).mapNotNull { index ->
            val phase = phases?.optJSONObject(index) ?: return@mapNotNull null
            val number = phase.optInt("phase")
            if (number !in 1..AutonomousEncounterScript.MAX_PHASES) return@mapNotNull null
            "阶段$number：${phase.optString("direction").trim().take(110)}"
        }
        return (listOf(angle) + ideas + stageNotes).filter(String::isNotBlank)
            .joinToString("；").take(1050)
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
                if (tick.status == "active") appendLine(DigitalWorldLifeEventStore.contextFor(characterId))
                appendLine("角色：${character.displayName}")
                val readable = com.jiacimu.lulu.study.ReadingBackgroundBridge
                    .availableBooks(context, characterId).take(5)
                if (readable.isNotEmpty()) {
                    appendLine("本人真正可读的书（供自己决定是否阅读，正文必须执行后才能知道）：")
                    readable.forEach { book ->
                        appendLine("- readingBookId=${book.id}；书名=${book.title}")
                    }
                }
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
                {"statusText":"事件发生后仍持续的处境（若无独立信息留空）","gesture":"仅此刻可见但不冒充世界工具结果的小动作","innerThought":"第一人称未说出口的念头","mood":"简短心情","shareChannel":"none|private|moment|group","shareText":"真正发布或发送的自然内容","groupId":"真实群ID或空字符串","reason":"为什么此刻本人想分享或想保持沉默，允许非常简单的念头"}

                规则：
                1. 主人不在现场，不能让主人说话、行动、被看见或默认参与。
                2. 只能引用给出的准确地点、事件和真实家具。不得添加其他物品、食物、天气、声响、小生物、故障或后续结果。
                3. status=active 时事件尚未解决；角色的文字和动作不能擅自把它抓住、清除、修好或解释清楚。若角色此刻真正决定自己观察、寻找、避让或处理已存在的事件，可以在 appraisal.ownActivity 填 {"stage":"decided","action":"digital_world","worldAction":"handle_incident","incidentId":"上面的真实ID","approach":"当前权威状态允许的真实操作","whyNow":"角色自己选择这一步的原因"}；程序再次验证事件仍在现场，交给同一个执行器处理，失败也保留。也可以先好奇、害怕、分享或暂时不管，不能强制每次立即处理。
                4. 分享仍要来自本人心理反应：先理解所见、产生具体想分享给谁的兴致，再选 shareChannel 并用 reason 留一句当时真正的想法。没有那股意愿不要为了发朋友圈而发，程序也会在无动机时拒绝真正发布。角色的分享欲是自己的生活动机，不是只在重大事故时才允许：想单独告诉用户可选 private，想给大家看自己的趣事、得意、感动、吐槽、意外发现可选 moment（公开朋友圈），想和实际群友聊天可选 group。公开朋友圈可以只是随手记录一次真实的小情绪或发现，绝非只能用于大事；也不要机械每个事件必发。没有分享欲选 none，可以让真实经历保留在后续记忆和好奇心中。
                5. group 必须使用真实 groupId。shareText 可以有角色口吻和情绪，但其中每个事实都必须来自上面的程序记录。
                对这次亲历如果真的产生了新疑问（例如蟑螂明明没食物怎么进来的，或者想研究角色自己喜欢的书），可以选填 curiosity={"topic":"自己关注的主题","question":"真正好奇的问题","why":"本轮真实事件怎样触发","nextStep":"可实际执行的下一步"}。这只是好奇，不等于已查到答案或已采取行动。若暂时很想分享但没立刻发，可填 appraisal.shareWish={"status":"held","why":"为何想告诉别人"}；程序会与这次真实事件ID绑定，不是发布回执。
                6. 可选 appraisal.spontaneousBid={"spark":"这次真实现场里突然想到的小趣事","wantedMove":"想和别人发起的一次自然微互动"}；它只是意图，不能改写世界事实。若角色真的决定另做一件有程序执行能力的生活事，可选 appraisal.ownActivity={"stage":"decided","action":"solo_game|reading|digital_world","whyNow":"自己的具体理由",...真实ID参数}。不确定或只是想到时不填 decided；由统一队列等待真实回执。
                6. 可选 innerLife={"emotion":{"feeling":"感受","cause":"当前实际事件","otherFeeling":"并存感受","impulse":"冲动","restraint":"克制","strength":1到4,"halfLifeMinutes":30到1440},"motives":[{"op":"start|revise|pause|resume|release","id":"已有ID","aim":"个人愿望","why":"原因","reason":"变化依据"}]}。这次独处经历可以改变情绪和兴趣，不只是换一句状态；没有变化则省略，不编造与用户或其他人的互动。
                ${CharacterDecisionProtocol.runtimePrinciples}
            """.trimIndent(),
            source = "数字世界事件反应",
            title = "${character.displayName}在${tick.locationName}",
            maxTokens = 1_100,
        ).getOrNull()?.text.orEmpty()
        val reaction = parseSoloIncidentReaction(raw)
        if (reaction == null) {
            // The event is already saved in the world timeline. Failure to
            // generate a subjective response cannot silently become a chat
            // message written by the program in the companion's name.
            CompanionPresenceStore.recordPerceptionAttempt(characterId,
                "真实世界事件已记录，角色的自主反应生成未完成；未代替角色发送消息", now)
            return
        }

        val sourceId = "world-fact-${tick.incidentId}-$characterId-${now.toEpochMilli()}"
        val selectedShareAction = when (reaction.shareChannel) {
            "private" -> "message"
            "moment" -> "moment"
            "group" -> "group_message"
            else -> "silent"
        }
        val shareBasis = CharacterBehaviorMotivation.resolve(
            selectedShareAction, reaction.decisionReason, reaction.appraisal, reaction.innerLife,
        )
        val effectiveShareChannel = if (selectedShareAction == "silent" || shareBasis != null)
            reaction.shareChannel else "none"
        CharacterCuriosityRuntime.initialize(context.applicationContext)
        CharacterCuriosityRuntime.recordInquiry(
            characterId, reaction.curiosity, sourceId, tick.summary, now,
        )
        reaction.appraisal?.optJSONObject("shareWish")?.let { wish ->
            if (wish.optString("experienceId").isBlank()) wish.put("experienceId", sourceId)
        }
        CharacterInnerLifeStore.observe(characterId, sourceId, tick.summary, reaction.innerLife, emptySet(), now)
        CharacterInnerLifeStore.recordInnerVoice(characterId, sourceId, reaction.innerThought, now)
        // The author's same-model choice MUST enter the existing durable
        // ToolRouter-backed worker. Merely storing appraisal.ownActivity made
        // pests linger while the character seemingly decided to handle them.
        val queuedSelfAction = CharacterSelfActivityRuntime.offer(
            context, characterId, reaction.appraisal, sourceId)
        val ownChoice = reaction.appraisal?.optJSONObject("ownActivity")
        if (!queuedSelfAction && ownChoice?.optString("stage") == "decided") {
            // A model selecting an action is not proof that it was scheduled,
            // much less executed. Keep a trace rather than silently swallowing
            // invalid incident IDs, duplicate queued work or unavailable tools.
            CharacterInnerLifeStore.recordDecision(
                characterId = characterId,
                decisionId = "world-activity-unqueued:$sourceId",
                selectedAction = ownChoice.optString("action").ifBlank { "digital_world" },
                reason = ownChoice.optString("whyNow").trim().take(200),
                chosenMotiveId = ownChoice.optString("motiveId"),
                alternatives = null,
                outcome = "未排程：当前目标、参数或任务状态未通过真实执行器检查；没有执行任何动作",
                succeeded = false, now = now, causalEvidenceId = sourceId,
            )
        }
        CharacterInnerLifeStore.recordCausalTransition(
            characterId = characterId, evidenceId = sourceId,
            appraisal = reaction.appraisal, innerLife = reaction.innerLife,
            innerThoughtBasis = null,
            selectedAction = if (effectiveShareChannel != "none") selectedShareAction
                else if (queuedSelfAction) "own_activity_queued" else "silent",
            innerThought = reaction.innerThought,
            reason = shareBasis?.reason.orEmpty(), now = now,
        )
        val provenanceId = "world-incident-reaction-${tick.incidentId}-$characterId-${now.toEpochMilli()}"
        CompanionPresenceStore.update(
            characterId = characterId,
            statusText = reaction.statusText.ifBlank { "位于${tick.locationName}" },
            // A proposed world action may be queued but is not yet performed.
            // Its actual physical movement belongs to the executor's receipt,
            // never to this speculative pre-action avatar field.
            gesture = if (ownChoice?.optString("stage") == "decided" &&
                ownChoice.optString("action") == "digital_world") "" else
                reaction.gesture.takeIf(String::isNotBlank),
            innerThought = reaction.innerThought,
            mood = reaction.mood,
            source = "数字世界事件反应",
            now = now,
            provenanceId = provenanceId,
            momentReflection = CharacterMomentSnapshot.reflection(
                reaction.innerLife, appraisal = reaction.appraisal,
                outward = reaction.shareText, innerThought = reaction.innerThought,
            ),
        )

        val shareResult = when (effectiveShareChannel) {
            "private" -> CompanionActionRuntime.execute(
                context,
                characterId,
                "send_private_message",
                JSONObject().put("text", reaction.shareText),
                now,
            )
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
        // Immediate impulses use exactly the same receipt-based life queue
        // as deferred background decisions. No generated post is a real post
        // until CompanionActionRuntime confirms publication.
        if (shareResult != null) {
            CharacterInnerLifeStore.recordDecision(
                characterId = characterId,
                decisionId = "world-sharing:$sourceId:$effectiveShareChannel",
                selectedAction = when (effectiveShareChannel) {
                    "private" -> "message"
                    "group" -> "group_message"
                    else -> "moment"
                },
                reason = shareBasis?.reason.orEmpty(),
                chosenMotiveId = "", alternatives = null,
                outcome = shareResult.summary, succeeded = shareResult.success,
                now = now, causalEvidenceId = sourceId,
            )
        }
        if (shareResult?.success == true) {
            CharacterLifeFollowThroughStore.recordAction(
                characterId, sourceId,
                when (reaction.shareChannel) {
                    "moment" -> "moment"
                    "private" -> "message"
                    "group" -> "group_message"
                    else -> "silent"
                }, true, shareResult.summary, shareResult.evidenceId,
            )
        }
        if (shareResult != null && !shareResult.success) {
            CompanionPresenceStore.recordPerceptionAttempt(characterId,
                "角色自选分享未送达：${shareResult.summary.take(130)}；事件事实保留，未伪造聊天消息", now)
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
            decisionReason = json.optString("reason").trim().take(240),
            innerLife = json.optJSONObject("innerLife"),
            appraisal = json.optJSONObject("appraisal"),
            curiosity = json.optJSONObject("curiosity"),
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
                val reason = item.optString("reason").trim().take(240)
                val appraisal = item.optJSONObject("appraisal")
                val innerLife = item.optJSONObject("innerLife")
                if (segments.isNotEmpty() && reason.isNotBlank()) add(GeneratedTurn(speakerId, segments,
                    innerLife, item.optString("innerThought").trim().take(1_200),
                    appraisal, reason))
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
        val decisionReason: String = "",
        val innerLife: JSONObject? = null,
        val appraisal: JSONObject? = null,
        val curiosity: JSONObject? = null,
    )

    private data class GeneratedTurn(
        val speakerId: String,
        val segments: List<MeetingSegment>,
        val innerLife: JSONObject? = null,
        val innerThought: String = "",
        val appraisal: JSONObject? = null,
        val decisionReason: String = "",
    )

    private data class GeneratedEncounter(
        val turns: List<GeneratedTurn>,
        val summary: String,
        val privateReactions: Map<String, JSONObject> = emptyMap(),
    )
}
