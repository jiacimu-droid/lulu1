package com.jiacimu.lulu.system

import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelReply
import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluConversation
import com.jiacimu.lulu.data.LuluGroupMember
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Plans one natural all-member group exchange with one model request.
 *
 * Every character in the group must actually speak in every round. The model plans the complete
 * exchange once, then the existing chat UI consumes the cached turns one speaker at a time so
 * avatars, bubbles, storage and group-call TTS continue to work without additional API calls.
 */
internal object GroupEnsembleReplyEngine {
    private const val BubbleSeparator = "⟪BUBBLE⟫"
    private const val EndMarker = "⟪END⟫"

    private data class PlannedTurn(
        val characterId: String,
        val bubbles: List<String>,
        val statusText: String,
        val gesture: String,
        val innerThought: String,
        val mood: String,
    )

    private data class CachedPlan(
        val turns: MutableList<PlannedTurn>,
        val memberLabels: Map<String, String>,
    )

    private val lock = Any()
    private val cachedPlans = linkedMapOf<String, CachedPlan>()

    suspend fun respondIfApplicable(
        characterId: String,
        history: String,
        userText: String,
        title: String,
        archiveId: String?,
        sceneContext: String,
    ): Result<ModelReply>? {
        val conversation = resolveGroupConversation(sceneContext) ?: return null
        val group = conversation.groupChat ?: return null
        val messages = MigratedDomainStores.chat.messages(conversation.id).value
        val latestUserMessage = messages.lastOrNull { it.sender == LuluChatMessage.Sender.User } ?: return null
        val channel = if (sceneContext.contains("电话")) "call" else "chat"
        val planKey = "${conversation.id}:${latestUserMessage.id}:$channel"

        takeCachedTurn(planKey, characterId)?.let { return Result.success(it) }

        val settings = MigratedDomainStores.characters.settings.value
        val validMembers = group.members.filter { member -> member.characterId in settings }
        if (validMembers.size < 2) {
            return Result.success(
                ModelReply(text = "群里现在没有足够的成员能接话。$EndMarker"),
            )
        }

        val currentSpeakerId = characterId.takeIf { requested ->
            validMembers.any { it.characterId == requested }
        } ?: validMembers.first().characterId
        val memberLabels = validMembers.associate { member ->
            val character = settings.getValue(member.characterId)
            member.characterId to member.groupNickname.ifBlank { character.displayName }
        }
        val mentionedIds = validMembers.filter { member ->
            val label = memberLabels.getValue(member.characterId)
            val displayName = settings.getValue(member.characterId).displayName
            latestUserMessage.content.contains("@$label", ignoreCase = true) ||
                latestUserMessage.content.contains("@$displayName", ignoreCase = true)
        }.map(LuluGroupMember::characterId)

        // The current rendering loop supports at most eight character turns. Group creation and
        // settings use the same ceiling, so within a valid group every member is included here.
        val replyLimit = validMembers.size.coerceIn(2, 8)
        val requiredSpeakerIds = buildList {
            add(currentSpeakerId)
            mentionedIds.filterNot(::contains).forEach(::add)
            validMembers.map(LuluGroupMember::characterId).filterNot(::contains).forEach(::add)
        }.take(replyLimit)

        // Heal groups created by older builds whose reply cap was lower than their member count.
        if (!group.allowCharacterConversation || group.maxAutoReplies < replyLimit) {
            MigratedDomainStores.chat.updateGroupConversation(
                conversation.id,
                group.copy(
                    allowCharacterConversation = true,
                    maxAutoReplies = replyLimit,
                ),
            )
        }

        val connection = runCatching { LuluAiServices.connectionStore.resolveConnection(archiveId) }
            .getOrElse { error ->
                return Result.success(
                    fallbackReply(currentSpeakerId, memberLabels, error.message),
                )
            }
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val isCall = channel == "call"

        val generated = LuluAiServices.gateway.generate(
            characterId = currentSpeakerId,
            facts = buildString {
                appendLine("当前时间：${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(zone))}")
                appendLine("当前真实场景：$sceneContext")
                appendLine("群聊名称：${group.name}")
                appendLine("用户在群里的称呼：${group.userGroupNickname}")
                appendLine("本轮第一个发言者必须是 characterId=$currentSpeakerId（${memberLabels[currentSpeakerId]}）。")
                appendLine("本轮必须规划恰好 ${requiredSpeakerIds.size} 个角色发言回合；这是一次完整规划，只会调用模型一次。")
                appendLine("本轮必须全部出现且每人至少发言一次的角色 ID：${requiredSpeakerIds.joinToString(",")}")
                appendLine("用户刚刚在群里说：${latestUserMessage.content}")
                if (history.isNotBlank()) appendLine("\n【群聊最近记录】\n${history.takeLast(14_000)}")
                appendLine("\n【群成员设定；每个人必须保持自己的语气、关系和边界】")
                validMembers.forEach { member ->
                    val character = settings.getValue(member.characterId)
                    val label = memberLabels.getValue(member.characterId)
                    val lived = SharedExperienceTimeline.recentContext(
                        member.characterId,
                        limit = 8,
                        characterBudget = 1_100,
                    )
                    val presence = CompanionPresenceStore.current(member.characterId)
                    appendLine("---")
                    appendLine("characterId=${member.characterId}")
                    appendLine("显示名=${character.displayName}；群内称呼=$label")
                    appendLine("人设=${character.persona.ifBlank { "按该角色已有设定自然表达。" }.take(1_800)}")
                    if (lived.isNotBlank()) appendLine("这个角色亲历的近期原始时间线=${lived.take(1_100)}")
                    presence?.let {
                        appendLine("上一刻状态=${it.statusText}；动作=${it.gesture}；心情=${it.mood}；没说出口=${it.innerThought}")
                    }
                }
                appendLine("\n【本轮原始编排提示】\n${userText.takeLast(4_000)}")
            },
            instruction = """
                你是一次多人群聊的整体编排器。你要同时理解所有成员的人设，只用一次生成规划完整的一小段真实群聊，而不是分别替每个人重复回答用户。

                只返回一个 JSON 对象，不要代码块、分析、旁白或额外说明：
                {"turns":[{"characterId":"必须使用上面给出的真实ID","bubbles":["气泡1","气泡2"],"statusText":"简短状态","gesture":"该角色此刻的微动作神态","innerThought":"该角色没说出口的一瞬心声，可为空","mood":"简短心情"}]}

                规则：
                1. 第一项 turns[0].characterId 必须是指定的第一个发言者 ID。
                2. 上面列出的每一个“必须全部出现”的角色都必须在 turns 中实际发言，不能沉默、不能缺席、不能只写状态；每人至少一个 turn。本轮只有所有成员都发过消息才算完成。
                3. 即使某个角色性格冷淡、对话题兴趣不高或不想长篇回答，也必须按其人设给出真实的短反应、追问、吐槽、反驳、补充或对其他成员的回应，不能用“旁听”“不说话”代替发言。
                4. 要把这些发言写成一段彼此有来有回的交流。后面的角色应接住前一位或更早成员的话，可以赞同、质疑、插话、补充、玩笑、追问或短暂打断。禁止每个人都从头重新回答用户，禁止整齐排队式复述。
                5. 每个角色必须严格保持自己的语言习惯、关系边界、称呼和性格差异。不要把所有人统一写成温柔助手，也不要让一个角色替另一个角色说话。
                6. 每个 turn 的 bubbles 是该角色这一回合真实发送的气泡。根据语义和停顿灵活给 1—4 个气泡：一个完整动作、情绪或观点尽量留在同一气泡；话题转折、独立反应、追问或故意停顿时再拆开。
                7. 单个气泡尽量简短自然，通常 8—65 个汉字。禁止把一整段长文塞进一个气泡，也禁止每句话机械拆成一个气泡。
                8. bubbles 内不要写姓名前缀、characterId、引号、项目符号、舞台说明或格式标签。动作描写只有符合人设且像聊天内容时才能自然出现。
                9. 不要虚构用户当前身体、环境或正在做的事。成员之间可以依据群内真实记录互相回应。
                10. ${if (isCall) "这是实时群聊电话，表达要更口语化、适合直接念出来，不要写长段落。" else "这是文字群聊，允许短促停顿、表情和自然的多气泡节奏。"}
                11. statusText、gesture、innerThought、mood 分别属于该角色本人，不得写成系统分析或推理过程。
            """.trimIndent(),
            source = if (isCall) "群聊电话·全员单次编排" else "群聊·全员单次编排",
            title = title,
            temperature = 0.94,
            maxTokens = (760 + replyLimit * 360).coerceIn(1_200, 3_200),
            connectionOverride = connection,
        )

        val baseReply = generated.getOrElse { error ->
            return Result.success(fallbackReply(currentSpeakerId, memberLabels, error.message))
        }
        val parsed = parseTurns(
            raw = baseReply.text,
            validMembers = validMembers,
            memberLabels = memberLabels,
            replyLimit = replyLimit,
        )
        val completed = ensureEveryMemberParticipates(
            parsed = parsed,
            requiredSpeakerIds = requiredSpeakerIds,
            currentSpeakerId = currentSpeakerId,
            userText = latestUserMessage.content,
        )

        synchronized(lock) {
            cachedPlans[planKey] = CachedPlan(completed.toMutableList(), memberLabels)
            while (cachedPlans.size > 24) cachedPlans.remove(cachedPlans.keys.first())
        }
        return Result.success(
            takeCachedTurn(planKey, currentSpeakerId, baseReply)
                ?: fallbackReply(currentSpeakerId, memberLabels, "群聊编排没有返回有效内容"),
        )
    }

    private fun resolveGroupConversation(sceneContext: String): LuluConversation? {
        val groupName = Regex("群聊《([^》]+)》").find(sceneContext)?.groupValues?.getOrNull(1)?.trim()
            ?: return null
        return MigratedDomainStores.chat.conversations.value
            .asSequence()
            .filter { conversation -> conversation.groupChat?.name == groupName }
            .maxByOrNull(LuluConversation::updatedAt)
    }

    private fun takeCachedTurn(
        planKey: String,
        requestedCharacterId: String,
        tokenSource: ModelReply? = null,
    ): ModelReply? {
        val served = synchronized(lock) {
            val cached = cachedPlans[planKey] ?: return@synchronized null
            val requestedIndex = cached.turns.indexOfFirst { it.characterId == requestedCharacterId }
            val index = if (requestedIndex >= 0) requestedIndex else 0
            val turn = cached.turns.removeAt(index)
            val next = cached.turns.firstOrNull()
            val nextLabel = next?.let { cached.memberLabels[it.characterId] }
            if (cached.turns.isEmpty()) cachedPlans.remove(planKey)
            ServedTurn(turn, nextLabel)
        } ?: return null

        CompanionPresenceStore.update(
            characterId = served.turn.characterId,
            statusText = served.turn.statusText,
            gesture = served.turn.gesture,
            innerThought = served.turn.innerThought,
            mood = served.turn.mood,
            source = "群聊·全员单次编排",
        )
        val marker = served.nextLabel?.let { "⟪NEXT:$it⟫" } ?: EndMarker
        val text = served.turn.bubbles.joinToString(BubbleSeparator) + marker
        return ModelReply(
            text = text,
            inputTokens = tokenSource?.inputTokens ?: 0,
            outputTokens = tokenSource?.outputTokens ?: 0,
            cachedTokens = tokenSource?.cachedTokens ?: 0,
        )
    }

    private data class ServedTurn(val turn: PlannedTurn, val nextLabel: String?)

    private fun parseTurns(
        raw: String,
        validMembers: List<LuluGroupMember>,
        memberLabels: Map<String, String>,
        replyLimit: Int,
    ): List<PlannedTurn> {
        val settings = MigratedDomainStores.characters.settings.value
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            val json = JSONObject(cleaned.substring(start, end + 1))
            val array = json.optJSONArray("turns") ?: json.optJSONArray("messages") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    if (size >= replyLimit) break
                    val item = array.optJSONObject(index) ?: continue
                    val rawSpeaker = item.optString("characterId")
                        .ifBlank { item.optString("speaker") }
                        .ifBlank { item.optString("name") }
                    val resolvedId = resolveSpeakerId(rawSpeaker, validMembers, memberLabels, settings)
                        ?: continue
                    val rawBubbles = buildList {
                        val bubblesArray = item.optJSONArray("bubbles")
                        if (bubblesArray != null) {
                            for (bubbleIndex in 0 until bubblesArray.length()) {
                                add(bubblesArray.optString(bubbleIndex))
                            }
                        } else {
                            add(item.optString("text").ifBlank { item.optString("content") })
                        }
                    }
                    val bubbles = normalizeBubbles(rawBubbles)
                    if (bubbles.isEmpty()) continue
                    add(
                        PlannedTurn(
                            characterId = resolvedId,
                            bubbles = bubbles,
                            statusText = item.optString("statusText").ifBlank { item.optString("status") }.take(80),
                            gesture = item.optString("gesture").ifBlank { item.optString("actionDescription") }.take(160),
                            innerThought = item.optString("innerThought").ifBlank { item.optString("inner_voice") }.take(220),
                            mood = item.optString("mood").take(60),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun ensureEveryMemberParticipates(
        parsed: List<PlannedTurn>,
        requiredSpeakerIds: List<String>,
        currentSpeakerId: String,
        userText: String,
    ): List<PlannedTurn> {
        val required = requiredSpeakerIds.toSet()
        val unique = parsed
            .filter { it.characterId in required }
            .distinctBy(PlannedTurn::characterId)
            .toMutableList()
        val present = unique.mapTo(mutableSetOf(), PlannedTurn::characterId)
        requiredSpeakerIds.filterNot { it in present }.forEach { missingId ->
            unique += fallbackParticipationTurn(missingId, userText)
        }
        return orderForRequestedSpeaker(unique, currentSpeakerId)
    }

    private fun fallbackParticipationTurn(characterId: String, userText: String): PlannedTurn {
        val topic = userText
            .replace(Regex("@[^\\s，。！？!?]+"), "")
            .trim()
            .take(34)
            .ifBlank { "刚才这个话题" }
        val bubbles = when ((characterId.hashCode() and Int.MAX_VALUE) % 3) {
            0 -> listOf(
                "我也接一下。",
                "你刚才说的“$topic”，我更想知道你最在意的是哪一部分？",
            )
            1 -> listOf(
                "等下，我也有话说。",
                "关于“$topic”，别只听他们的——你自己现在怎么想？",
            )
            else -> listOf(
                "我还没说呢。",
                "你提到“$topic”时我有认真听，这件事不能一句带过。",
            )
        }
        return PlannedTurn(
            characterId = characterId,
            bubbles = bubbles,
            statusText = "加入群聊回应",
            gesture = "接过话头",
            innerThought = "这一轮我也要把自己的反应说出来。",
            mood = "投入",
        )
    }

    private fun resolveSpeakerId(
        raw: String,
        validMembers: List<LuluGroupMember>,
        memberLabels: Map<String, String>,
        settings: Map<String, com.jiacimu.lulu.data.CharacterSettings>,
    ): String? {
        val clean = raw.trim()
        if (clean.isBlank()) return null
        return validMembers.firstOrNull { member ->
            val label = memberLabels[member.characterId].orEmpty()
            val displayName = settings[member.characterId]?.displayName.orEmpty()
            clean == member.characterId ||
                clean.equals(label, ignoreCase = true) ||
                clean.equals(displayName, ignoreCase = true)
        }?.characterId
    }

    private fun orderForRequestedSpeaker(
        turns: List<PlannedTurn>,
        requestedCharacterId: String,
    ): List<PlannedTurn> {
        val firstIndex = turns.indexOfFirst { it.characterId == requestedCharacterId }
        if (firstIndex <= 0) return turns
        val first = turns[firstIndex]
        return listOf(first) + turns.filterIndexed { index, _ -> index != firstIndex }
    }

    private fun normalizeBubbles(values: List<String>): List<String> {
        val explicit = values.flatMap { value ->
            value.replace("\r\n", "\n")
                .split(BubbleSeparator)
                .flatMap { part -> part.split(Regex("\\n+")) }
        }.map { it.trim().trim('"', '“', '”') }.filter(String::isNotBlank)

        val flexible = explicit.flatMap(::splitLongBubble)
        if (flexible.size <= 4) return flexible
        return flexible.take(3) + flexible.drop(3).joinToString("")
    }

    private fun splitLongBubble(raw: String): List<String> {
        val text = raw.trim()
        if (text.length <= 68) return listOf(text)
        val sentences = text.split(Regex("(?<=[。！？!?…])"))
            .map(String::trim)
            .filter(String::isNotBlank)
        if (sentences.size <= 1) return splitAtNaturalPauses(text)

        val result = mutableListOf<String>()
        var current = ""
        sentences.forEach { sentence ->
            val candidate = current + sentence
            if (current.isNotBlank() && candidate.length > 68 && current.length >= 18) {
                result += current
                current = sentence
            } else {
                current = candidate
            }
        }
        if (current.isNotBlank()) result += current
        return result.flatMap { part -> if (part.length > 86) splitAtNaturalPauses(part) else listOf(part) }
    }

    private fun splitAtNaturalPauses(text: String): List<String> {
        val result = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.length > 76) {
            val window = remaining.take(72)
            val boundary = window.indexOfLast { it in "，；：、 " }
            val cut = if (boundary >= 30) boundary + 1 else 58
            result += remaining.take(cut).trim()
            remaining = remaining.drop(cut).trim()
        }
        if (remaining.isNotBlank()) result += remaining
        return result
    }

    private fun fallbackReply(
        characterId: String,
        labels: Map<String, String>,
        reason: String?,
    ): ModelReply {
        val label = labels[characterId].orEmpty()
        val text = when {
            reason.isNullOrBlank() -> "我刚刚一下没接住……你再说一次？"
            label.isBlank() -> "我这边刚刚卡了一下，你再说一次？"
            else -> "$label 刚刚卡了一下……你再说一次？"
        }
        return ModelReply(text = text + EndMarker)
    }
}
