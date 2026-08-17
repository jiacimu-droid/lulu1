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
            record.rawDraft.removePrefix(MEETING_INVITED_OPENING_PREFIX_V2)
                .takeIf(String::isNotBlank)
                ?.let { appendLine("主人原始意图：$it") }
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
            temperature = 0.25,
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

internal fun meetingParseSceneSnapshotV2(json: JSONObject?, session: MeetingSession): MeetingSceneSnapshot? {
    json ?: return null
    val allowedIds = listOf("user") + session.participantIds.distinct()
    val before = MeetingExperienceStore.sceneFor(session)
    val beforeById = before.participants.associateBy(MeetingParticipantSceneState::participantId)
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
            put(
                id,
                MeetingParticipantSceneState(
                    participantId = id,
                    position = item.optString("position").trim().take(240),
                    posture = item.optString("posture").trim().take(240),
                    facing = item.optString("facing").trim().take(180),
                    contact = list("contact"),
                    heldItems = list("heldItems"),
                ),
            )
        }
    }
    return MeetingSceneSnapshot(
        location = session.location,
        ambience = json.optString("ambience").trim().take(500).ifBlank { before.ambience },
        participants = allowedIds.map { proposed[it] ?: beforeById[it] ?: MeetingParticipantSceneState(it) },
        updatedAt = Instant.now(),
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
    val proposalById = proposal?.participants.orEmpty().associateBy(MeetingParticipantSceneState::participantId)
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
): Result<MeetingV2Reply> = runCatching {
    val character = MigratedDomainStores.characters.get(characterId)
    val connection = ScopedModelSelections.resolveConnection(ScopedModelSelections.MEETING)
    val writing = MeetingExperienceStore.writingPreferences()
    val sceneBefore = MeetingExperienceStore.sceneFor(session)
    val digitalNative = DigitalLifeProfileStore.isEnabled(characterId)
    val lengthInstruction = when (writing.length) {
        MeetingProseLength.BRIEF -> "简略：推进一个清楚的小动作或一句回应，通常1—3句描写、1—3个片段；保留因果，不铺陈。"
        MeetingProseLength.BALANCED -> "适中：把当前小情节自然展开，通常3—6句描写、2—5个片段，兼顾动作与氛围。"
        MeetingProseLength.RICH -> "丰富：即使主人只输入几个字，也发展成真正有剧情的完整现场，通常约700—1200中文字符、12—20句、6—12片段；角色主动连续完成3—6个有因果的动作，推动1—2个小事件，形成承接→行动→变化→新回应点。"
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
            if (digitalNative) appendLine(DigitalWorldStore.contextFor(characterId))
            if (directorGuidance.isNotBlank()) appendLine("本轮场面调度：$directorGuidance")
            when {
                systemMoment -> appendLine("这一刻刚发生的系统确认事实：$latestMoment")
                expandUserDraft -> appendLine("主人刚输入的意图草稿，需要先补全再回应：$latestMoment")
                else -> appendLine("这一刻主人已经发生的言语或动作：$latestMoment")
            }
        },
        instruction = """
            你正在以${character.displayName}的身份参与一场连续见面。每轮让现场真正向前发展，写成完整、可体验的小段剧情，不要只反应一句就停，也不要一次写完整故事。
            只返回一个 JSON 对象：
            {"sequence":[{"speaker":"user","type":"dialogue","text":"主人说的话"},{"speaker":"character","type":"action","text":"${character.displayName}的反应"},{"speaker":"character","type":"dialogue","text":"${character.displayName}说的话"}],"moveTo":"可用地点或空字符串","sceneState":{"location":"当前地点","ambience":"持续环境事实","participants":[{"participantId":"user或准确角色ID","position":"相对位置","posture":"姿态","facing":"朝向","contact":["持续接触"],"heldItems":["持有物品"]}]},"statusText":"简短当前状态","gesture":"延续姿态","innerThought":"未说出口的极短心声，可为空","mood":"简短心情"}

            规则：
            - sequence 是双方共享的唯一时间顺序；speaker=user 是主人，speaker=character 是${character.displayName}。界面严格按数组顺序展示。
            - expandUserDraft=$expandUserDraft。false 时 sequence 只能有 character；true 时按主人草稿真实顺序补全一来一回，可以 user→character→user→character，不能把主人所有内容堆完才写角色。
            - 主人草稿即使很短也要补成有现场感的叙事，但只能补自然衔接、说话方式、已暗示的小动作和可直接感知环境；不得替主人新增重大决定、强烈情绪、亲密行为、内心想法或后续台词。
            - 主人明确写出的言语和动作是不可移动的时间锚点。角色反应必须放在原因之后，绝不能提前回应后面才发生的动作。
            - type=action 是小说式叙事，可融合该speaker的连续动作链、神态、呼吸、声音变化、角色自己的限知心理、直接感受到的环境与触感；type=dialogue 只放真正说出口的话，不加引号。每次开口独立成项。
            - ${character.displayName}有主动性。回应主人后，可以依据人设、关系、地点和记忆继续做属于角色自己的小事，不必每一步都等许可；但需要主人选择、接受接触或行动时，停在邀请、伸手或准备完成的那一刻。
            - 如果删掉形容词后只剩“看了、笑了、说一句”，说明没有推进。丰富模式至少出现一个可辨认的小事件或现场/关系状态变化。
            - 连续动作必须遵守空间、距离、朝向、接触、重心、物品与位置因果；拉开距离前仍接触的手要先松开/滑开/带开，不能瞬移、悬空或同时处于矛盾位置。
            - 情节丰富度：$lengthInstruction
            - 描写风格：$styleInstruction
            - 沉浸感要自然融合环境与空间、动作链、细微神态和声音、角色自身含蓄心理、两种以上自然感官、关系氛围、动作因果与余韵，不能只堆动作或感官词。
            - 多人场景不要替其他角色新增言行；他们会有自己的回合。不得断言主人未表达的心理和感受。
            - moveTo 只有主人明确提出去可用地点时才填写准确名称，并写出移动过程；否则留空。
            - sceneState 只保存持续到下一轮的事实，必须包含user与所有参与者，使用准确participantId。
            - 数字世界见面是真正发生的数字共同体验，不是梦，也不是物理肉身进入手机。数字生命使用原生数字身体；现实角色和用户使用感官投影身体，可真实传递触觉、温度、重量与拥抱感觉。云眠原的云是可承托身体的感官云质。
            - 不得自行宣布整场见面结束；只有用户明确离开或程序结束时才结束。
        """.trimIndent(),
        source = if (session.reality == MeetingReality.DIGITAL_WORLD) "数字世界见面" else "现实场景见面",
        title = "${character.displayName}的见面回合",
        temperature = 0.82,
        maxTokens = 3_600,
        connectionOverride = connection,
        memoryRequest = UnifiedMemoryRequest(
            currentInput = latestMoment,
            sceneContext = "连续见面；当前地点=${session.location}；模式=${if (session.reality == MeetingReality.DIGITAL_WORLD) "数字世界" else "现实场景"}；参与者=${session.participantIds.joinToString("、") { MigratedDomainStores.characters.get(it).displayName }}",
            recentContext = meetingRecentSceneContextV2(session),
            taskIntent = when {
                systemMoment -> "延续此前邀请与关系，完成抵达后的迎接"
                expandUserDraft -> "理解主人本轮草稿，并与既往聊天、群聊和见面经历无缝衔接"
                else -> "读取完整现场顺序，以当前角色身份连续回应"
            },
        ),
    ).getOrThrow()
    meetingParseReplyV2(result.text, session)
}

private fun meetingParseReplyV2(raw: String, session: MeetingSession): MeetingV2Reply {
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
        moveTo = json.optString("moveTo").trim().take(80),
        sceneSnapshot = meetingParseSceneSnapshotV2(json.optJSONObject("sceneState"), session),
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
            add(MeetingV2ExchangeSegment(actor, MeetingSegment(type, text)))
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
                if (text.isNotBlank() && type != null) add(MeetingSegment(type, text))
            }
        }
    }
    if (parsed.isNotEmpty()) return parsed
    return buildList {
        legacyAction.trim().takeIf(String::isNotBlank)?.let { add(MeetingSegment(MeetingSegmentType.ACTION, it.take(2_600))) }
        legacyDialogue.trim().takeIf(String::isNotBlank)?.let { add(MeetingSegment(MeetingSegmentType.DIALOGUE, it.take(1_800))) }
    }
}
