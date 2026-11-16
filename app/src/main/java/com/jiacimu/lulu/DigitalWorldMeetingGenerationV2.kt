package com.jiacimu.lulu

import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections
import com.jiacimu.lulu.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal enum class MeetingV2Actor { USER, CHARACTER }
internal data class MeetingV2ExchangeSegment(val actor: MeetingV2Actor, val segment: MeetingSegment)
internal data class MeetingV2Reply(
    val sequence: List<MeetingV2ExchangeSegment>,
    val userSegments: List<MeetingSegment>,
    val segments: List<MeetingSegment>,
    val sceneText: String,
    val dialogue: String,
    val statusText: String,
    val gesture: String,
    val innerThought: String,
    val mood: String,
    val moveTo: String,
    val sceneSnapshot: MeetingSceneSnapshot?,
    val appraisal: JSONObject? = null,
    val innerLife: JSONObject? = null,
    val decisionReason: String = "",
)
internal data class MeetingV2DirectorPlan(val order: List<String>, val guidance: String) {
    fun ledgerText(): String = buildString {
        append("顺序=").append(order.joinToString(" → "))
        if (guidance.isNotBlank()) append("；调度=").append(guidance)
    }
}

internal fun meetingRecentSceneContextV2(session: MeetingSession): String {
    val records = MeetingExperienceStore.completedForSession(session.id, limit = 8)
    if (records.isEmpty()) {
        return session.turns.takeLast(25).joinToString("\n") {
            "${it.speakerName}：${it.meetingOrderedSegments().meetingTranscript()}"
        }
    }
    return records.joinToString("\n\n") { record ->
        val ids = record.turnIds.toSet()
        val turns = session.turns.filter { it.exchangeId == record.id || it.id in ids }
        buildString {
            appendLine("【完整场景 ${record.id.take(8)}】")
            when {
                record.rawDraft.startsWith(MEETING_LIVING_MOMENT_PREFIX_V2) -> {
                    val systemFact = record.rawDraft.removePrefix(MEETING_LIVING_MOMENT_PREFIX_V2)
                        .substringAfter("\n", "").trim()
                    appendLine(if (systemFact.isBlank()) "角色自己的在场判断，没有收到用户的新发言"
                        else "现场已证实的动作（非用户发言）：$systemFact")
                }
                record.rawDraft.startsWith(MEETING_INVITED_OPENING_PREFIX_V2) ->
                    appendLine("角色邀请后的开场：${record.rawDraft.removePrefix(MEETING_INVITED_OPENING_PREFIX_V2)}")
                else -> record.rawDraft.takeIf(String::isNotBlank)
                    ?.let { appendLine("主人原始意图：$it") }
            }
            turns.forEach { appendLine("${it.speakerName}：${it.meetingOrderedSegments().meetingTranscript()}") }
        }.trim()
    }
}

internal suspend fun meetingPlanDirectionV2(session: MeetingSession, userText: String): MeetingV2DirectorPlan {
    val participants = session.participantIds.distinct()
    if (participants.size <= 1) return MeetingV2DirectorPlan(participants, "")
    val fallback = MeetingV2DirectorPlan(
        participants,
        "先让最直接被主人提及或最适合承接动作的人回应，其余角色只在有自然动机时接续；避免抢话与重复反应。",
    )
    return runCatching {
        val connection = ScopedModelSelections.resolveConnection(ScopedModelSelections.MEETING)
        val names = participants.associateWith { MigratedDomainStores.characters.get(it).displayName }
        val result = LuluAiServices.gateway.generate(
            characterId = participants.first(),
            facts = buildString {
                appendLine(MeetingExperienceStore.sceneFor(session).promptSection())
                WorldFirstExplorationMemory.promptSectionIfAvailable("digital-world", limit = 8)
                    .takeIf(String::isNotBlank)
                    ?.let(::appendLine)
                appendLine("主人本轮草稿：$userText")
                appendLine("参与者准确 ID：")
                participants.forEach { appendLine("- $it = ${names[it]}") }
                appendLine("最近完整场景：")
                appendLine(meetingRecentSceneContextV2(session))
            },
            instruction = """
                你是多人见面的场面调度器，不代替任何人演戏。只返回 JSON：
                {"order":["准确角色ID"],"guidance":"一句简短的调度说明"}
                order 必须把所有参与角色各列一次：先列本轮最应该直接承接主人意图的人，再列可能自然接续的人。
                guidance 只说明注意对象、先后因果、空间关系与避免重复；不得新增台词、动作、情节或替主人决定。
            """.trimIndent(),
            source = "见面场面调度",
            title = "多人见面导演",
            maxTokens = 420,
            connectionOverride = connection,
            memoryRequest = UnifiedMemoryRequest(
                currentInput = userText,
                sceneContext = "多人连续见面；地点=${session.location}；参与者=${names.values.joinToString("、")}",
                recentContext = meetingRecentSceneContextV2(session),
                taskIntent = "决定本轮角色回应顺序，只做调度，不生成正文",
            ),
        ).getOrThrow()
        val clean = result.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        val json = JSONObject(if (start >= 0 && end > start) clean.substring(start, end + 1) else clean)
        val proposed = buildList {
            val array = json.optJSONArray("order") ?: JSONArray()
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it in participants && it !in this }?.let(::add)
            }
        }
        MeetingV2DirectorPlan(
            order = proposed + participants.filterNot { it in proposed },
            guidance = json.optString("guidance").trim().take(600).ifBlank { fallback.guidance },
        )
    }.getOrDefault(fallback)
}

/** A scene decision can only target an actually installed item in the current room. */
internal fun meetingValidatedDestinationItemId(
    candidate: String, existing: String, explicitlySupplied: Boolean, allowedItemIds: Set<String>,
): String = (if (explicitlySupplied) candidate else existing).trim()
    .takeIf { it in allowedItemIds }.orEmpty()

/** Only activities listed for the same installed room item can be executed on arrival. */
internal fun meetingValidatedDestinationActivityId(candidate: String, allowedIds: Set<String>): String =
    candidate.trim().takeIf { it in allowedIds }.orEmpty()

internal fun meetingValidatedStreetCode(
    candidate: String, existing: String, explicitlySupplied: Boolean, allowedCodes: Set<String>,
): String = (if (explicitlySupplied) candidate else existing).trim()
    .takeIf { it in allowedCodes }.orEmpty()

internal fun meetingParseSceneSnapshotV2(json: JSONObject?, session: MeetingSession, respondingCharacterId: String? = session.participantIds.singleOrNull(), atRequest: MeetingSceneSnapshot? = null): MeetingSceneSnapshot? {
    json ?: return null
    val allowedIds = listOf("user") + session.participantIds.distinct()
    val before = MeetingExperienceStore.sceneFor(session)
    val beforeById = before.participants.associateBy(MeetingParticipantSceneState::participantId)
    val roomOwnerId = DigitalWorldStore.meetingLocationCode(session.location)
        .takeIf { it.startsWith("home:") }?.removePrefix("home:")
    val roomItems = if (session.reality == MeetingReality.DIGITAL_WORLD && roomOwnerId != null)
        DigitalWorldStore.state.value.items.filter { it.ownerCharacterId == roomOwnerId }
    else emptyList()
    val validItemIds = roomItems.mapTo(hashSetOf()) { it.id }
    val allowedStreetCodes = if (session.reality == MeetingReality.DIGITAL_WORLD &&
        session.location == "世界入口") DigitalWorldStore.meetingLocationOptions(session)
        .mapTo(hashSetOf()) { DigitalWorldStore.meetingLocationCode(it) }
        .filterTo(hashSetOf()) { it != DigitalWorldStore.ARRIVAL }
    else emptySet()
    val proposed = buildMap {
        val array = json.optJSONArray("participants") ?: JSONArray()
        for (index in 0 until minOf(array.length(), allowedIds.size * 2)) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("participantId").trim()
            if (id !in allowedIds || id in this) continue
            fun list(key: String): List<String> = buildList {
                val values = item.optJSONArray(key) ?: JSONArray()
                for (valueIndex in 0 until minOf(values.length(), 8)) {
                    values.optString(valueIndex).trim().take(120).takeIf(String::isNotBlank)?.let(::add)
                }
            }
            val selectedItemId = if (id == respondingCharacterId && session.reality == MeetingReality.DIGITAL_WORLD)
                meetingValidatedDestinationItemId(
                    candidate = item.optString("destinationItemId"),
                    existing = beforeById[id]?.destinationItemId.orEmpty(),
                    explicitlySupplied = item.has("destinationItemId"),
                    allowedItemIds = validItemIds,
                )
            else beforeById[id]?.destinationItemId.orEmpty()
            val selectedItem = roomItems.firstOrNull { it.id == selectedItemId }
            val offeredActivities = selectedItem?.let(DigitalWorldActivityCatalog::optionsFor)
                ?.mapTo(hashSetOf()) { it.first } ?: emptySet()
            val previousActivity = beforeById[id]?.destinationActivityId.orEmpty()
            val offeredActivity = when {
                item.has("destinationActivityId") -> item.optString("destinationActivityId")
                selectedItemId == beforeById[id]?.destinationItemId -> previousActivity
                else -> ""
            }
            put(
                id,
                MeetingParticipantSceneState(
                    participantId = id,
                    position = item.optString("position").trim().take(240),
                    posture = item.optString("posture").trim().take(240),
                    facing = item.optString("facing").trim().take(180),
                    contact = list("contact"),
                    heldItems = list("heldItems"),
                    explorationMode = if (id == respondingCharacterId && session.reality == MeetingReality.DIGITAL_WORLD) {
                        item.optString("explorationMode").takeIf { it in setOf("FOLLOW_USER", "STAY") }
                            ?: beforeById[id]?.explorationMode.orEmpty()
                    } else beforeById[id]?.explorationMode.orEmpty(),
                    destinationStreetCode = if (id == respondingCharacterId && session.reality == MeetingReality.DIGITAL_WORLD)
                        meetingValidatedStreetCode(
                            item.optString("destinationStreetCode"),
                            beforeById[id]?.destinationStreetCode.orEmpty(),
                            item.has("destinationStreetCode"),
                            allowedStreetCodes,
                        )
                    else beforeById[id]?.destinationStreetCode.orEmpty(),
                    destinationItemId = selectedItemId,
                    destinationActivityId = if (id == respondingCharacterId &&
                        session.reality == MeetingReality.DIGITAL_WORLD && selectedItem != null)
                        meetingValidatedDestinationActivityId(offeredActivity, offeredActivities)
                    else if (id == respondingCharacterId && session.reality == MeetingReality.DIGITAL_WORLD) ""
                    else beforeById[id]?.destinationActivityId.orEmpty(),
                ),
            )
        }
    }
    return MeetingSceneSnapshot(
        location = session.location,
        ambience = json.optString("ambience").trim().take(500).ifBlank { before.ambience },
        participants = allowedIds.map { proposed[it] ?: beforeById[it] ?: MeetingParticipantSceneState(it) },
        updatedAt = atRequest?.updatedAt ?: Instant.now(),
    )
}

internal fun meetingAuthoritativeSceneV2(
    session: MeetingSession,
    proposal: MeetingSceneSnapshot?,
    now: Instant,
): MeetingSceneSnapshot {
    val current = MeetingExperienceStore.sceneFor(session)
    val allowedIds = listOf("user") + session.participantIds.distinct()
    val currentById = current.participants.associateBy(MeetingParticipantSceneState::participantId)
    // Physical changes recorded during remote generation outrank stale fiction
    // from the beginning of that request. Keep the actual user touch/position,
    // completed furniture action and elected companion movement intact.
    val stale = proposal != null && current.updatedAt.isAfter(proposal.updatedAt)
    val proposalById = if (stale) emptyMap()
        else proposal?.participants.orEmpty().associateBy(MeetingParticipantSceneState::participantId)
    return MeetingSceneSnapshot(
        location = session.location,
        ambience = proposal?.ambience?.takeIf(String::isNotBlank) ?: current.ambience,
        participants = allowedIds.map { proposalById[it] ?: currentById[it] ?: MeetingParticipantSceneState(it) },
        updatedAt = now,
    )
}

internal suspend fun meetingGenerateReplyV2(
    session: MeetingSession,
    characterId: String,
    latestMoment: String,
    systemMoment: Boolean = false,
    expandUserDraft: Boolean = false,
    directorGuidance: String = "",
    autonomous: Boolean = false,
): Result<MeetingV2Reply> = runCatching {
    val character = MigratedDomainStores.characters.get(characterId)
    val connection = ScopedModelSelections.resolveConnection(ScopedModelSelections.MEETING)
    val writing = MeetingExperienceStore.writingPreferences()
    val sceneBefore = MeetingExperienceStore.sceneFor(session)
    val digitalNative = DigitalLifeProfileStore.isEnabled(characterId)
    val lengthInstruction = if (autonomous) {
        "自主在场：尊重安静，可一句话、一个小动作或一段内心想法。不要为凑篇幅制造事件；自然主动表达时通常不超过180字。"
    } else when (writing.length) {
        MeetingProseLength.BRIEF -> "简略：像日常私聊一样自然说一两句；只有现场确实需要时才添一个短动作或神态。"
        MeetingProseLength.BALANCED -> "适中：保留自然口语与对话节奏，穿插少量有意义的动作、目光和环境反应；不强求事件推进。"
        MeetingProseLength.RICH -> "丰富：允许更细腻的身体感受、停顿和空间细节，但仍是同一个人在和主人自然聊天；动作、情绪与篇幅由当时的真实需要决定，不强制长篇小说、固定字数或每轮发生事件。"
    }
    val styleInstruction = when (writing.style) {
        MeetingProseStyle.NATURAL -> "自然：清楚、生活化、克制，优先动作与对话顺畅。"
        MeetingProseStyle.SUBTLE -> "细腻含蓄：少直接宣布想法，用目光、呼吸、停顿、指尖、距离、触感与语气变化呈现情绪。"
        MeetingProseStyle.LITERARY -> "氛围文学：允许鲜明节奏、意象与感官呼应，但必须贴合现场，不堆砌辞藻。"
    }
    val result = LuluAiServices.gateway.generate(
        characterId = characterId,
        facts = buildString {
            appendLine(DigitalWorldStore.meetingContext(session, characterId))
            appendLine(sceneBefore.promptSection())
            val observedInteractions = com.jiacimu.lulu.data.CharacterPerceptionContext.recent(characterId)
            if (observedInteractions.isNotEmpty()) {
                appendLine(com.jiacimu.lulu.data.CharacterPerceptionContext.render(characterId, observedInteractions))
            }
            if (session.reality == MeetingReality.DIGITAL_WORLD) {
                val live = DigitalWorldLiveScene.context(characterId)
                if (live.isNotBlank()) appendLine(live)
                if (session.location == "世界入口") {
                    appendLine("【当前小镇可真实沿街道走到的建筑｜可选 destinationStreetCode】")
                    DigitalWorldStore.meetingLocationOptions(session).forEach { label ->
                        val code = DigitalWorldStore.meetingLocationCode(label)
                        if (code != DigitalWorldStore.ARRIVAL)
                            appendLine("- code=$code；门牌=$label；这里仅表示步行目标，实际进入仍需到门前由主人操作")
                    }
                }
                val roomCode = DigitalWorldStore.meetingLocationCode(session.location)
                val roomOwner = roomCode.takeIf { it.startsWith("home:") }?.removePrefix("home:")
                val furniture = DigitalWorldStore.state.value.items.filter { it.ownerCharacterId == roomOwner }
                if (furniture.isNotEmpty()) {
                    appendLine("【当前房间真实家具与可执行活动｜可选 destinationItemId 和 destinationActivityId】")
                    appendLine(DigitalWorldActivityCatalog.promptFor(furniture))
                }
                WorldFirstExplorationMemory.promptSectionIfAvailable("digital-world", limit = 10)
                    .takeIf(String::isNotBlank)
                    ?.let(::appendLine)
            }
            if (digitalNative) appendLine(DigitalWorldStore.contextFor(characterId))
            if (directorGuidance.isNotBlank()) appendLine("本轮场面调度：$directorGuidance")
            when {
                systemMoment -> appendLine("这一刻刚发生的系统确认事实：$latestMoment")
                expandUserDraft -> appendLine("主人刚输入的意图草稿，需要先补全再回应：$latestMoment")
                else -> appendLine("这一刻主人已经发生的言语或动作：$latestMoment")
            }
        },
        instruction = """
            你正在以${character.displayName}的身份参与一场连续见面。
            ${com.jiacimu.lulu.data.CharacterDecisionProtocol.runtimePrinciples}
            ${com.jiacimu.lulu.data.CharacterExpressionGuide.channelNote(com.jiacimu.lulu.data.CharacterSpeechChannel.MEETING)}
            ${if (autonomous) "这是你自己的在场意识和行动选择，不是用户向你发来了一条新消息。可以决定主动搭话、靠近、牵手邀请或继续做自己的事，也可以安静不动。主动时动作与说话由你的人设、记忆、心情和现实场景决定；不必每轮回应。若你决定暂时沉默，返回 sequence=[]，但可以写真实的 innerThought。不得虚构用户已经回应，也不要让剧情自动跳转。" else "这是同一个人从普通私聊走进真实见面现场：人格、记忆、情绪和心声保持连续。先自然接住主人的话，像平常聊天一样选择说什么；只有本人确实想动、现场需要时才穿插动作和神态。普通的一句回答或安静相伴已经足够，不必每轮强制制造事件、转场或长篇叙事。"}

            只返回一个 JSON 对象：
            {"sequence":[{"speaker":"user","type":"dialogue","text":"主人说的话"},{"speaker":"character","type":"action","text":"${character.displayName}的反应","speechText":"[sighs]"},{"speaker":"character","type":"dialogue","text":"${character.displayName}说的话","speechText":"[warmly] ${character.displayName}说的话"}],"moveTo":"可用地点或空字符串","sceneState":{"location":"当前地点","ambience":"持续环境事实","participants":[{"participantId":"user或准确角色ID","position":"相对位置","posture":"姿态","facing":"朝向","contact":["持续接触"],"heldItems":["持有物品"],"explorationMode":"FOLLOW_USER或STAY","destinationItemId":"现场家具准确ID或空字符串","destinationActivityId":"可选的对应家具activityId或空字符串","destinationStreetCode":"街道真实建筑code或空字符串"}]},"statusText":"简短当前状态","gesture":"延续姿态","innerThought":"角色本人没说出口的念头，自由按心境与个人语感展开，可为空","mood":"简短心情","reason":"这一次角色为何愿意说话、动作、移动或保持安静的本人想法；可以只是觉得无聊想走走"}

            规则：
            ${spontaneousInnerVoiceGuide}
            - 统一行为依据：每一次自主决定的对话、走动、停留、触碰、探索或暂时不行动，都先结合真实感知、已有状态和本人意愿写一句简短 reason。可以只是‘觉得有点闷想去吹吹风’‘想接住她这句话’‘此刻想继续坐着’，不要倒写看似严谨的长篇理由。reason 是决定的主观依据，不等于必须产生新 innerThought 或 emotion；同一份依据要传给统一的因果记录与执行校验。没有真实的移动意愿就不要填 moveTo。
             - 可以选填 appraisal.spontaneousBid={"spark":"本轮现场引起玩心的真事","wantedMove":"想自然做的小互动"} 和 innerLife={"emotion":{"feeling":"真正新增的感受","cause":"触发依据"},"thoughts":[{"thought":"私人小心思","hesitation":"真实顾虑"}]}，没有变化就不填。可选 appraisal.ownActivity={"stage":"decided","action":"reading|solo_game|digital_world","whyNow":"本人这时决定做的具体缘由",...有效 ID 参数}；这是把决定交给共用执行调度，不等于已经做成。想想而已不要填 decided，没有可执行参数也不要编造。见面本身发生的场内交互依然按 sceneState 和程序落库，而不是凭心理字段改动世界。
            - 每个character片段另带speechText音频轨，与正文text分开。dialogue的speechText保留完全相同的原话，只插入音频标签；action的speechText只能放实际动作/环境发出的声音标签，不念叙事正文。没有声音的动作留空，user不添加音频轨。
            - 见面是有具体身体和环境的现场：剧情确实发生打喷嚏、轻笑、吸气、咳嗽、脚步或环境声时，可以在对应角色 action 的 speechText 放对应受支持的短英文音频标签，例如 [sneezes] [laughs] [gasps] [coughs] [footsteps]，不要凭空制造动作；角色台词必须用 character/dialogue 独立片段，不能塞进 user/action 导致翻页后没有声音。
            ${VoicePerformance.direction}
            - 同一个喷嚏、巴掌声、吸气等事件只在发生的片段标一次，不在相邻台词重复播放；动作页可以只有音效，台词页可以带持续情绪和发声变化。
            - sequence 是双方共享的唯一时间顺序；speaker=user 是主人，speaker=character 是${character.displayName}。界面严格按数组顺序展示。
            - expandUserDraft=$expandUserDraft。false 时 sequence 只能有 character；true 时只可把主人确实输入的原话/动作按原顺序拆分为 user 片段，再让角色自然回应。没有在草稿中出现的主人后续台词、动作、心境一律不补写；不要为了凑一来一回而虚构主人说了下一句。
            - autonomous=$autonomous。true 时你只可描写自己真实执行的动作或自己说的话，也允许 sequence=[]（选择暂时不行动）；任何情况下都禁止伪造主人没说过的台词、动作和感受。
            - 主动选择牵手时可以先靠近、伸手邀请；只有真的与主人处于可接触范围且动作在场景里落实，contact 才可使用 handholding:user，explorationMode 才可写 FOLLOW_USER；不能隔空牵手。
            - 主人的输入就是权威原始时间线。可以用环境和角色自己的动作营造现场，但不得把模型润色或扩写的用户片段当作主人新说过/做过的事；不要替主人补情绪、决定、亲密行为、回答或后续台词。
            - 主人明确写出的言语和动作是不可移动的时间锚点。角色反应必须放在原因之后，绝不能提前回应后面才发生的动作。
            - type=action 是小说式叙事，可融合该speaker的连续动作链、神态、呼吸、声音变化、角色自己的限知心理、直接感受到的环境与触感；type=dialogue 只放真正说出口的话，不加引号。每次开口独立成项。
            - ${character.displayName}有主动性。回应主人后，可以依据人设、关系、地点和记忆继续做属于角色自己的小事，不必每一步都等许可；也可以只陪她聊天。需要主人选择、接受接触或行动时，停在邀请、伸手或准备完成的那一刻。
            - 一次平凡的看、笑、回答或安静陪伴完全可以是有意义的真实相处。不要为了看起来丰富而强制制造新事件、关系结论、主观心声或动作。
            - 连续动作必须遵守空间、距离、朝向、接触、重心、物品与位置因果；拉开距离前仍接触的手要先松开/滑开/带开，不能瞬移、悬空或同时处于矛盾位置。
            - 情节丰富度：$lengthInstruction
            - 描写风格：$styleInstruction
            - 沉浸感要自然融合环境与空间、动作链、细微神态和声音、角色自身含蓄心理、两种以上自然感官、关系氛围、动作因果与余韵，不能只堆动作或感官词。
            - 多人场景不要替其他角色新增言行；他们会有自己的回合。不得断言主人未表达的心理和感受。
            - moveTo 只有主人明确提出去可用地点时才填写准确名称，并写出移动过程；否则留空。
            - explorationMode 只更新你自己的字段：FOLLOW_USER 表示你已经同意并开始跟随主人探索，地图上的身体会实际跟随；STAY 表示你停止跟随。邀请、犹豫、拒绝不能标成已跟随。是否答应由你的人设、关系和当时安排决定；不替其他角色决定，不改变主人的字段。没有变化时保留当前值。现实场景留空。
            - destinationStreetCode 只在当前场景为“世界入口/云上小镇街道”时可用；你如果自主想带主人去咖啡厅/别人家，必须从当前实际门牌 code 里选择真实建筑。代码只是目的地，不代表已经抵达或已经进入；带着主人走需要已有的真实牵手接触，主人随时能改方向，达到建筑门口后仍由主人决定是否进门。不想领路可以留空或省略保留原有效目标。
            - destinationItemId 是你想真实走向的当前房间家具 ID，必须从现场家具清单选择，不能填家具名称、想象地点或其他角色家里的东西。只有决定现在真的动身时才填，不要把“要不要去？”的邀请冒充已经拉着主人到达；等对方同意牵手后，场景可以一起寻路，主人可以随时自己走或松手。取消原目标可显式填空；未决定新目标时可以省略字段保留未完成目标。
            - 如果你不仅想走到家具旁，还想在那里做具体的事，可以同时设置 destinationActivityId 为【该件家具】提供的 activityId，例如整理、照料、坐下、开灯；到达后程序才会真正执行并写回活动结果。仅仅接近或邀请对方去坐坐可以不填 activityId，**不能提前写成已经做完**。跨家具不能借用其他物品的活动，取消目标就同时取消活动；用户移动、松手或现场变化不会被程序解释成主人同意了新动作。
            - 场景正文不能代替身体行动的成功回执：走向床边等结果必须等地图真实走到对应家具旁再被当成已完成。你可以走着说话、走着等待、边探索边感知。
            - sceneState 只保存持续到下一轮的事实，必须包含user与所有参与者，使用准确participantId。
            - 数字世界见面是真正发生的数字共同体验，不是梦，也不是物理肉身进入手机。数字生命使用原生数字身体；现实角色和用户使用感官投影身体，可真实传递触觉、温度、重量与拥抱感觉。云眠原的云是可承托身体的感官云质。
            - 已记录的探索事实属于世界状态，不是可随剧情重置的文案。若本轮涉及同一地点或物件，必须承认它此前已经发生过的变化。
            - 不得自行宣布整场见面结束；只有用户明确离开或程序结束时才结束。
        """.trimIndent(),
        source = if (session.reality == MeetingReality.DIGITAL_WORLD) "数字世界见面" else "现实场景见面",
        title = "${character.displayName}的见面回合",
        maxTokens = 3_600,
        connectionOverride = connection,
        memoryRequest = UnifiedMemoryRequest(
            currentInput = latestMoment,
            sceneContext = "连续见面；当前地点=${session.location}；模式=${if (session.reality == MeetingReality.DIGITAL_WORLD) "数字世界" else "现实场景"}；参与者=${session.participantIds.joinToString("、") { MigratedDomainStores.characters.get(it).displayName }}",
            recentContext = meetingRecentSceneContextV2(session),
            taskIntent = when {
                systemMoment && autonomous -> "基于真实在场事件或自然空隙，按角色一贯人格、自主想法和共同记忆决定行动、交流或安静"
                systemMoment -> "延续此前邀请与关系，完成抵达后的迎接"
                expandUserDraft -> "理解主人本轮草稿，并与既往聊天、群聊和见面经历无缝衔接"
                else -> "读取完整现场顺序，以当前角色身份连续回应"
            },
        ),
    ).getOrThrow()
    meetingGuardRelationshipV2(meetingParseReplyV2(result.text, session, characterId, sceneBefore), characterId)
}

/** Keep visible dialogue and the audio track consistent before either is delivered. */
internal fun meetingGuardRelationshipV2(reply: MeetingV2Reply, characterId: String): MeetingV2Reply {
    val sequence = reply.sequence.flatMap { item ->
        if (item.actor != MeetingV2Actor.CHARACTER) return@flatMap listOf(item)
        val segment = item.segment
        val repaired = RelationshipExpressionGuard.apply(characterId, segment.text,
            allowNames = segment.type == MeetingSegmentType.DIALOGUE)
        if (repaired == segment.text) return@flatMap listOf(item)
        if (segment.type == MeetingSegmentType.DIALOGUE) {
            listOf(item.copy(segment = segment.copy(text = repaired, speechText = repaired)))
        } else {
            // Literal completion is spoken; do not make TTS read a narrative action paragraph.
            val addition = CharacterRelationshipPreferences.snapshot(characterId).preferences
                .flatMap { it.requiredForms }.distinct()
                .filter { repaired.contains(it, ignoreCase = true) && !segment.text.contains(it, ignoreCase = true) }
                .joinToString(" ")
            if (addition.isBlank()) listOf(item) else listOf(item,
                MeetingV2ExchangeSegment(MeetingV2Actor.CHARACTER,
                    MeetingSegment(MeetingSegmentType.DIALOGUE, addition, addition)))
        }
    }
    val role = sequence.filter { it.actor == MeetingV2Actor.CHARACTER }.map { it.segment }
    return reply.copy(sequence = sequence,
        segments = role,
        sceneText = role.filter { it.type == MeetingSegmentType.ACTION }.joinToString("\n") { it.text },
        dialogue = role.filter { it.type == MeetingSegmentType.DIALOGUE }.joinToString("\n") { it.text })
}

internal fun meetingParseReplyV2(raw: String, session: MeetingSession, respondingCharacterId: String? = session.participantIds.singleOrNull(), atRequest: MeetingSceneSnapshot? = null): MeetingV2Reply {
    val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim().let { value ->
        val start = value.indexOf('{')
        val end = value.lastIndexOf('}')
        if (start >= 0 && end > start) value.substring(start, end + 1) else value
    }
    val json = runCatching { JSONObject(clean) }.getOrNull()
        ?: return MeetingV2Reply(
            sequence = listOf(MeetingV2ExchangeSegment(MeetingV2Actor.CHARACTER, MeetingSegment(MeetingSegmentType.DIALOGUE, raw.trim()))),
            userSegments = emptyList(),
            segments = listOf(MeetingSegment(MeetingSegmentType.DIALOGUE, raw.trim())),
            sceneText = "",
            dialogue = raw.trim(),
            statusText = "正在见面",
            gesture = "停在这一刻",
            innerThought = "",
            mood = "专注",
            moveTo = "",
            sceneSnapshot = null,
        )
    val legacyUser = meetingParseSegmentsV2(json, "userSegments", json.optString("userSceneText"), json.optString("userDialogue"))
    val legacyRole = meetingParseSegmentsV2(json, "segments", json.optString("sceneText"), json.optString("dialogue"))
    var sequence = meetingParseExchangeV2(json)
    if (sequence.isEmpty()) {
        sequence = buildList {
            legacyUser.forEach { add(MeetingV2ExchangeSegment(MeetingV2Actor.USER, it)) }
            legacyRole.forEach { add(MeetingV2ExchangeSegment(MeetingV2Actor.CHARACTER, it)) }
        }
    }
    val userSegments = sequence.filter { it.actor == MeetingV2Actor.USER }.map { it.segment }
    val roleSegments = sequence.filter { it.actor == MeetingV2Actor.CHARACTER }.map { it.segment }
    return MeetingV2Reply(
        sequence = sequence,
        userSegments = userSegments,
        segments = roleSegments,
        sceneText = roleSegments.filter { it.type == MeetingSegmentType.ACTION }.joinToString("\n") { it.text },
        dialogue = roleSegments.filter { it.type == MeetingSegmentType.DIALOGUE }.joinToString("\n") { it.text },
        statusText = json.optString("statusText").trim().take(120),
        gesture = json.optString("gesture").trim().take(500),
        innerThought = json.optString("innerThought").trim().take(500),
        mood = json.optString("mood").trim().take(80),
        moveTo = json.optString("moveTo").trim().take(80).takeIf {
            it.isBlank() || CharacterBehaviorMotivation.resolve(
                "digital_world", json.optString("reason").ifBlank { json.optString("decisionReason") },
                json.optJSONObject("appraisal"), json.optJSONObject("innerLife"),
            ) != null
        }.orEmpty(),
        sceneSnapshot = meetingParseSceneSnapshotV2(json.optJSONObject("sceneState"), session, respondingCharacterId, atRequest),
        appraisal = json.optJSONObject("appraisal"),
        innerLife = json.optJSONObject("innerLife"),
        decisionReason = json.optString("reason").ifBlank { json.optString("decisionReason") }.trim().take(240),
    )
}

private fun meetingParseExchangeV2(json: JSONObject): List<MeetingV2ExchangeSegment> = buildList {
    val array = json.optJSONArray("sequence") ?: json.optJSONArray("exchangeSegments") ?: return@buildList
    for (index in 0 until minOf(array.length(), 28)) {
        val item = array.optJSONObject(index) ?: continue
        val text = item.optString("text").trim().take(2_000)
        val actor = when (item.optString("speaker").trim().lowercase()) {
            "user", "owner", "主人" -> MeetingV2Actor.USER
            "character", "role", "角色" -> MeetingV2Actor.CHARACTER
            else -> null
        }
        val type = when (item.optString("type").trim().lowercase()) {
            "action", "scene", "narration" -> MeetingSegmentType.ACTION
            "dialogue", "speech" -> MeetingSegmentType.DIALOGUE
            else -> null
        }
        if (text.isNotBlank() && actor != null && type != null) {
            add(MeetingV2ExchangeSegment(actor, VoicePerformance.meetingSegment(type, text, item.optString("speechText"), actor == MeetingV2Actor.USER)))
        }
    }
}

private fun meetingParseSegmentsV2(
    json: JSONObject,
    key: String,
    legacyAction: String,
    legacyDialogue: String,
): List<MeetingSegment> {
    val parsed = buildList {
        val array = json.optJSONArray(key)
        if (array != null) {
            for (index in 0 until minOf(array.length(), 10)) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text").trim().take(2_000)
                val type = when (item.optString("type").trim().lowercase()) {
                    "action", "scene", "narration" -> MeetingSegmentType.ACTION
                    "dialogue", "speech" -> MeetingSegmentType.DIALOGUE
                    else -> null
                }
                if (text.isNotBlank() && type != null) add(VoicePerformance.meetingSegment(type, text, item.optString("speechText"), key == "userSegments"))
            }
        }
    }
    if (parsed.isNotEmpty()) return parsed
    return buildList {
        legacyAction.trim().takeIf(String::isNotBlank)?.let { add(MeetingSegment(MeetingSegmentType.ACTION, it.take(2_600))) }
        legacyDialogue.trim().takeIf(String::isNotBlank)?.let { add(MeetingSegment(MeetingSegmentType.DIALOGUE, it.take(1_800))) }
    }
}
