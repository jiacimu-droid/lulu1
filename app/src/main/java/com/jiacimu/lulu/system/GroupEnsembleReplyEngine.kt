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
 * Plans one natural group-chat continuation with one model request.
 *
 * The configured auto-reply count is only a safety ceiling. It is never a target or a minimum:
 * one character may answer once and stop, several characters may join, or someone may naturally
 * come back later if the conversation really calls for it.
 */
internal object GroupEnsembleReplyEngine {
    private const val BubbleSeparator = "⟪BUBBLE⟫"
    private const val EndMarker = "⟪END⟫"

    private data class PlannedTurn(
        val characterId: String,
        val replyTo: String,
        val intent: String,
        val bubbles: List<String>,
        val quoteMessageId: String?,
        val favoriteMessageId: String?,
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
        val actionableUserMessages = messages.filter { it.sender == LuluChatMessage.Sender.User }.takeLast(8)
        val validUserMessageIds = actionableUserMessages.mapTo(mutableSetOf(), LuluChatMessage::id)
        val channel = if (sceneContext.contains("电话")) "call" else "chat"
        val planKey = "${conversation.id}:${latestUserMessage.id}:$channel"

        takeCachedTurn(planKey, characterId)?.let { return Result.success(it) }

        val settings = MigratedDomainStores.characters.settings.value
        val validMembers = group.members.filter { member -> member.characterId in settings }
        if (validMembers.size < 2) {
            return Result.success(ModelReply(text = "群里现在没有足够的成员能接话。$EndMarker"))
        }
        if (validMembers.size > 8) {
            return Result.success(ModelReply(text = "这个群目前超过了 8 个角色，暂时无法稳定编排这一轮。$EndMarker"))
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

        val replyLimit = group.maxAutoReplies.coerceIn(1, 8)
        val connection = runCatching { LuluAiServices.connectionStore.resolveConnection(archiveId) }
            .getOrElse { error ->
                return Result.success(fallbackReply(currentSpeakerId, memberLabels, error.message))
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
                appendLine("本轮第一个发言者是 characterId=$currentSpeakerId（${memberLabels[currentSpeakerId]}）。")
                appendLine("本轮最多允许 $replyLimit 个角色发言回合。这个数字只是安全上限，不是目标，也不是最低数量；自然说完时 1 个回合就可以结束。")
                appendLine("不要求所有群成员都发言，也不要求任何角色回来补第二轮。谁想说、说几次、什么时候停，都按当前话题与各自人设自然决定。")
                if (mentionedIds.isNotEmpty()) appendLine("用户明确点名了：${mentionedIds.joinToString(",")}。点名应被自然理解，但不要因此强迫其他成员凑数。")
                appendLine("用户刚刚在群里说：${latestUserMessage.content}")
                if (actionableUserMessages.isNotEmpty()) {
                    appendLine("\n【近期真实用户气泡；消息ID只供引用或角色收藏使用】")
                    actionableUserMessages.forEach { item -> appendLine("消息ID=${item.id}；内容=${item.content.take(320)}") }
                }
                if (history.isNotBlank()) appendLine("\n【群聊最近记录】\n${history.takeLast(14_000)}")
                appendLine("\n【群成员设定；每个人必须保持自己的语气、关系和边界】")
                validMembers.forEach { member ->
                    val character = settings.getValue(member.characterId)
                    val label = memberLabels.getValue(member.characterId)
                    val lived = SharedExperienceTimeline.recentContext(member.characterId, limit = 8, characterBudget = 1_100)
                    val presence = CompanionPresenceStore.current(member.characterId)
                    appendLine("---")
                    appendLine("characterId=${member.characterId}")
                    appendLine("显示名=${character.displayName}；群内称呼=$label")
                    appendLine("人设=${character.persona.ifBlank { "按该角色已有设定自然表达。" }.take(1_800)}")
                    if (lived.isNotBlank()) appendLine("这个角色亲历的近期原始时间线=${lived.take(1_100)}")
                    presence?.let { appendLine("上一刻状态=${it.statusText}；动作=${it.gesture}；心情=${it.mood}；没说出口=${it.innerThought}") }
                }
                appendLine("\n【调用来源】这是群聊界面的一次整轮生成请求。任何旧的固定回合数、全员发言、固定气泡数量或固定字数要求都无效，以本次规则为准。")
            },
            instruction = """
                你是多人群聊的整体编排器。把这一轮写成真正会发生的群聊，而不是让成员轮流交答案。你同时理解所有人的人设，只进行这一次生成。

                只返回一个 JSON 对象，不要代码块、分析、旁白或额外说明：
                {"turns":[{"characterId":"真实角色ID","replyTo":"user|group|另一个真实角色ID","intent":"简短意图","bubbles":["气泡"],"quoteMessageId":"真实用户消息ID或空字符串","favoriteMessageId":"角色真心想收藏的真实用户消息ID或空字符串","statusText":"简短状态","gesture":"该角色此刻的微动作神态","innerThought":"该角色没说出口的一瞬心声，可为空","mood":"简短心情"}]}

                规则：
                1. turns 只能在 1 到给定安全上限之间。第一项必须是指定的第一个发言者。安全上限绝不是要填满的目标；一名角色回一句就自然结束，是完全正常的结果。
                2. 不要求全员参与，不要求每个角色至少说一次，也不要求有人重复出现。只有真的想接话的人才出现；安静、冷淡或觉得没必要说的人可以这一轮完全不发言。
                3. 如果话题自然形成往返，可以 A→B→A、A→C→B，也可以只有 A。不要固定 ABAB，不要按成员列表轮流，更不要为了“像群聊”强行延长讨论。
                4. 后续角色如果出现，应真正接住已经发生的内容：赞同、质疑、反驳、追问、补充、插话、玩笑或改口；不要每个人都从头重新回答用户同一个问题。
                5. 每个角色必须严格保持自己的语言习惯、关系边界、称呼和性格差异。不要把所有人统一写成温柔助手，也不要让一个角色替另一个角色发言。
                6. bubbles 是这个角色实际一次次按下“发送”后出现的气泡。气泡数量没有最低值、没有推荐值，也没有“至少三条”之类的要求。一个气泡非常正常；只有现实聊天里这个角色真的会停一下再补一句、转折、追问、吐槽或改口时，才继续拆出第二、第三个或更多气泡。
                7. 不按标点、句号、固定字数或固定数量机械切分，也不要为了显得活泼、丰富或有层次而强行拆成三四条。反过来也不要把本来会分开发送的话硬塞成长段。唯一标准是这个角色现实聊天时会不会在这里按一次发送。
                8. quoteMessageId 是可选能力。只有确实针对用户此前某一句气泡单独回应且引用更自然时才填写真实消息ID，否则留空；不能编造ID。
                9. favoriteMessageId 也是可选能力，而且应更少见。只有角色本人真的很想把用户某一句留下来以后再看时才收藏；只能填写提供过的真实用户消息ID，否则留空。
                10. 不要虚构用户当前身体、环境或正在做的事情。只能依据用户刚说的话、群聊记录、角色设定和真实时间线互动。
                11. 最后一轮不需要总结，也不需要“把话题交给主人”。自然停住就可以。
                12. ${if (isCall) "这是实时群聊电话，quoteMessageId 和 favoriteMessageId 都留空；语言必须更口语化、适合直接念出。" else "这是文字群聊，可以自然使用短促停顿、连续气泡、偶尔引用，以及极少量符合角色意愿的收藏。"}
                13. statusText、gesture、innerThought、mood 分别属于当前角色本人，不能写成系统分析或推理过程。
            """.trimIndent(),
            source = if (isCall) "群聊电话·自然多人讨论" else "群聊·自然多人讨论",
            title = title,
            temperature = 0.96,
            maxTokens = (700 + replyLimit * 300).coerceIn(1_100, 3_200),
            connectionOverride = connection,
        )

        val baseReply = generated.getOrElse { error -> return Result.success(fallbackReply(currentSpeakerId, memberLabels, error.message)) }
        val parsed = parseTurns(
            raw = baseReply.text,
            validMembers = validMembers,
            memberLabels = memberLabels,
            replyLimit = replyLimit,
            validUserMessageIds = if (isCall) emptySet() else validUserMessageIds,
        )
        val completed = normalizePlan(
            parsed = parsed,
            currentSpeakerId = currentSpeakerId,
            replyLimit = replyLimit,
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
        val groupName = Regex("群聊《([^》]+)》").find(sceneContext)?.groupValues?.getOrNull(1)?.trim() ?: return null
        return MigratedDomainStores.chat.conversations.value
            .asSequence()
            .filter { conversation -> conversation.groupChat?.name == groupName }
            .maxByOrNull(LuluConversation::updatedAt)
    }

    private fun takeCachedTurn(planKey: String, requestedCharacterId: String, tokenSource: ModelReply? = null): ModelReply? {
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
            source = "群聊·自然多人讨论",
        )
        val marker = served.nextLabel?.let { "⟪NEXT:$it⟫" } ?: EndMarker
        val quote = served.turn.quoteMessageId?.let { "⟪QUOTE:$it⟫" }.orEmpty()
        val favorite = served.turn.favoriteMessageId?.let { "⟪FAVORITE:$it⟫" }.orEmpty()
        val text = quote + favorite + served.turn.bubbles.joinToString(BubbleSeparator) + marker
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
        validUserMessageIds: Set<String>,
    ): List<PlannedTurn> {
        val settings = MigratedDomainStores.characters.settings.value
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
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
                    val rawSpeaker = item.optString("characterId").ifBlank { item.optString("speaker") }.ifBlank { item.optString("name") }
                    val resolvedId = resolveSpeakerId(rawSpeaker, validMembers, memberLabels, settings.mapValues { it.value.displayName }) ?: continue
                    val rawBubbles = buildList {
                        val bubblesArray = item.optJSONArray("bubbles")
                        if (bubblesArray != null) {
                            for (bubbleIndex in 0 until bubblesArray.length()) add(bubblesArray.optString(bubbleIndex))
                        } else add(item.optString("text").ifBlank { item.optString("content") })
                    }
                    val bubbles = normalizeBubbles(rawBubbles)
                    if (bubbles.isEmpty()) continue
                    val requestedQuoteId = item.optString("quoteMessageId").trim()
                    val requestedFavoriteId = item.optString("favoriteMessageId").trim()
                    add(
                        PlannedTurn(
                            characterId = resolvedId,
                            replyTo = item.optString("replyTo").ifBlank { "group" }.take(100),
                            intent = item.optString("intent").take(80),
                            bubbles = bubbles,
                            quoteMessageId = requestedQuoteId.takeIf { it in validUserMessageIds },
                            favoriteMessageId = requestedFavoriteId.takeIf { it in validUserMessageIds },
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

    private fun normalizePlan(
        parsed: List<PlannedTurn>,
        currentSpeakerId: String,
        replyLimit: Int,
    ): List<PlannedTurn> {
        val trimmed = parsed.take(replyLimit)
        if (trimmed.isEmpty()) return listOf(fallbackTurn(currentSpeakerId))
        val currentIndex = trimmed.indexOfFirst { it.characterId == currentSpeakerId }
        if (currentIndex == 0) return trimmed
        if (currentIndex > 0) {
            val first = trimmed[currentIndex]
            return listOf(first) + trimmed.filterIndexed { index, _ -> index != currentIndex }
        }
        if (replyLimit <= 1) return listOf(fallbackTurn(currentSpeakerId))
        return listOf(fallbackTurn(currentSpeakerId)) + trimmed.take(replyLimit - 1)
    }

    private fun fallbackTurn(characterId: String): PlannedTurn = PlannedTurn(
        characterId = characterId,
        replyTo = "user",
        intent = "回应",
        bubbles = listOf("我先接一下你刚刚这句。"),
        quoteMessageId = null,
        favoriteMessageId = null,
        statusText = "正在群里回应",
        gesture = "顺着消息接话",
        innerThought = "",
        mood = "平静",
    )

    private fun resolveSpeakerId(
        raw: String,
        validMembers: List<LuluGroupMember>,
        memberLabels: Map<String, String>,
        displayNames: Map<String, String>,
    ): String? {
        val clean = raw.trim()
        if (clean.isBlank()) return null
        return validMembers.firstOrNull { member ->
            val label = memberLabels[member.characterId].orEmpty()
            val displayName = displayNames[member.characterId].orEmpty()
            clean == member.characterId || clean.equals(label, ignoreCase = true) || clean.equals(displayName, ignoreCase = true)
        }?.characterId
    }

    private fun normalizeBubbles(values: List<String>): List<String> = values.flatMap { value ->
        value.replace("\r\n", "\n")
            .split(BubbleSeparator)
            .map { part -> part.replace(Regex("\s*\n+\s*"), " ").trim().trim('"', '“', '”') }
    }.filter(String::isNotBlank)

    private fun fallbackReply(characterId: String, labels: Map<String, String>, reason: String?): ModelReply {
        val label = labels[characterId].orEmpty()
        val text = when {
            reason.isNullOrBlank() -> "我刚刚一下没接住……你再说一次？"
            label.isBlank() -> "我这边刚刚卡了一下，你再说一次？"
            else -> "$label 刚刚卡了一下……你再说一次？"
        }
        return ModelReply(text = text + EndMarker)
    }
}
