package com.jiacimu.lulu.system

import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelReply
import com.jiacimu.lulu.data.CharacterIdentityStore
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
 * Every member participates at least once in each generated group turn, but nobody owns a fixed slot.
 * Order is driven by the live conversation and personalities, and a member may naturally return for
 * another turn before or after the remaining members have spoken.
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
        val recallBubbleNumber: Int?,
        val pokeUser: Boolean,
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

        val memberCount = validMembers.size
        val replyLimit = group.maxAutoReplies.coerceAtLeast(memberCount).coerceIn(memberCount, 8)
        val requiredSpeakerIds = validMembers.map(LuluGroupMember::characterId)
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
                appendLine("本轮界面当前先显示 characterId=$currentSpeakerId（${memberLabels[currentSpeakerId]}）正在输入，因此 turns 第一项必须是这个角色；这个首发角色本身已经由外层按当前群聊状态动态选出，并不是固定 A。")
                appendLine("这一轮共有 $memberCount 个角色成员，每个人都必须至少真正发言一次；这是参与约束，不是发言顺序。")
                appendLine("本轮最多允许 $replyLimit 个角色回合。全员各出现一次以后，仍然可以让任何已经发过言的人再次插话、回应别人或回来补一句，不要求每个人只说一次。")
                appendLine("必须覆盖的成员集合：${requiredSpeakerIds.joinToString(",")}。这个列表只是集合，不代表 A→B→C 的次序，严禁照列表顺序机械输出。")
                if (mentionedIds.isNotEmpty()) appendLine("用户明确点名了：${mentionedIds.joinToString(",")}。被点名角色应自然更早接话，但其他成员这一轮仍然都要至少参与一次。")
                appendLine("用户刚刚在群里说：${latestUserMessage.content}")
                if (actionableUserMessages.isNotEmpty()) {
                    appendLine("\n【近期真实用户气泡；消息ID只供引用或角色收藏使用】")
                    actionableUserMessages.forEach { item -> appendLine("消息ID=${item.id}；内容=${item.content.take(320)}") }
                }
                if (history.isNotBlank()) appendLine("\n【群聊最近记录；这些都已经真实发生】\n${history.takeLast(14_000)}")
                appendLine("\n【群成员身份与设定；每个人必须保持自己的语气、关系和边界】")
                validMembers.forEach { member ->
                    val character = settings.getValue(member.characterId)
                    val label = memberLabels.getValue(member.characterId)
                    val lived = SharedExperienceTimeline.recentContext(member.characterId, limit = 8, characterBudget = 1_100)
                    val presence = CompanionPresenceStore.current(member.characterId)
                    appendLine("---")
                    appendLine("characterId=${member.characterId}")
                    appendLine("显示名=${character.displayName}；群内称呼=$label")
                    CharacterIdentityStore.get(member.characterId)
                        .takeIf(String::isNotBlank)
                        ?.let { identity -> appendLine("角色身份=${identity.take(1_500)}") }
                    appendLine("角色设定=${character.persona.ifBlank { "按该角色已有设定自然表达。" }.take(1_800)}")
                    if (lived.isNotBlank()) appendLine("这个角色亲历的近期原始时间线=${lived.take(1_100)}")
                    presence?.let { appendLine("上一刻状态=${it.statusText}；动作=${it.gesture}；心情=${it.mood}；没说出口=${it.innerThought}") }
                }
                appendLine("\n【调用来源】这是群聊界面的一次整轮生成。全员必须参与，但绝不允许把“全员参与”写成固定 ABC 轮班。合法形态包括 C→B→A、A→B→C→B→A、B→A→C→A 等，具体顺序由当前内容和角色设定决定。")
            },
            instruction = """
                你是多人群聊的整体编排器。把这一轮写成真正会发生的群聊：所有成员都参与，但发言顺序不固定，而且有人完全可以在别人说过以后再次回来接话。不要把“全员都说话”误解成“一人一次、按名单轮班”。

                只返回一个 JSON 对象，不要代码块、分析、旁白或额外说明：
                {"turns":[{"characterId":"真实角色ID","replyTo":"user|group|另一个真实角色ID","intent":"简短意图","bubbles":["气泡"],"quoteMessageId":"真实用户消息ID或空字符串","favoriteMessageId":"角色真心想收藏的真实用户消息ID或空字符串","recallBubbleNumber":0,"pokeUser":false,"statusText":"简短状态","gesture":"该角色此刻的微动作神态","innerThought":"该角色没说出口的一瞬心声，可为空","mood":"简短心情"}]}

                规则：
                1. turns 第一项必须是指定的当前发言者，因为界面已经显示这个人正在输入；这个人不是固定成员，而是每轮动态选出的首发者。
                2. 本轮所有群成员都必须至少出现一次，但“至少一次”绝不等于“只能一次”。只要符合当前话题和角色设定，同一个角色可以在本轮再次出现。
                3. 发言顺序不能跟成员列表绑定，也不能默认 A→B→C。三个人时可以 C→B→A，也可以 A→B→C→B→A、B→A→C→A、C→A→B→C；顺序必须像真实群聊一样由谁最想接这一句话决定。
                4. 在全员尚未全部出现之前，也允许已经说过的人再次插话，例如 A→B→A→C；只要最终安全上限内每个人至少出现一次即可。
                5. turns 数量至少覆盖全部成员，最多是给定安全上限。不要为了填满上限强行续聊；但只要自然，也可以使用多余回合让某些角色再次发言。
                6. 后续角色应真正接住已经发生的内容：赞同、质疑、反驳、追问、补充、插话、玩笑、岔开或改口；不要每个人都从头回答用户同一个问题。
                7. 每个角色必须严格保持自己的身份、语言习惯、关系边界、称呼和性格差异。不要把所有人统一写成温柔助手，也不要让一个角色替另一个角色发言。
                8. bubbles 是这个角色一次次按下“发送”后出现的气泡。一个气泡通常只承载一个当下表达动作；先回应、再补一句、再转折或追问时，可以自然拆成几个短气泡。不要按固定字数、句号或固定数量机械切，也不要把几个不同表达动作硬塞成长段。
                9. quoteMessageId 是正常聊天能力，不必过度克制。用户连续发了几条、角色针对其中某一句单独回应、想捡回稍早的一句、或不引用会让指代不清时，可以填写真实消息ID；只回最新一句且上下文很清楚时留空。
                10. favoriteMessageId 是角色自己的主观动作。如果用户某句话让这个角色很在意、很喜欢、想以后回看或对关系有特殊意义，例如承诺、特殊称呼、重要心意、戳中他的句子，可以收藏。是否收藏必须服从这个角色的设定和感受，不设固定概率，也不能为了展示功能乱收藏。
                11. recallBubbleNumber 默认 0。只有极少数角色刚说出口就后悔、说漏嘴或想装作没说过的时刻才填真实序号。
                12. pokeUser 默认 false。只有这个角色此刻真的会自然戳一下用户时才设为 true。
                13. 不要虚构用户当前身体、环境或正在做的事情。只能依据用户刚说的话、群聊记录、角色身份与设定和真实时间线互动。
                14. 最后一轮不需要总结，不需要“把话题交给主人”，自然停住就可以。
                15. ${if (isCall) "这是实时群聊电话，quoteMessageId、favoriteMessageId 留空，recallBubbleNumber=0，pokeUser=false；语言必须更口语化、适合直接念出。" else "这是文字群聊，可以自然使用连续短气泡、引用、角色主观收藏，以及非常偶发的撤回或戳一戳。"}
                16. statusText、gesture、innerThought、mood 分别属于当前角色本人，不能写成系统分析或推理过程。
            """.trimIndent(),
            source = if (isCall) "群聊电话·全员自然讨论" else "群聊·全员自然讨论",
            title = title,
            temperature = 1.02,
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
            allowMessageActions = !isCall,
        )
        val completed = ensureRequiredSpeakers(
            parsed = parsed,
            requiredSpeakerIds = requiredSpeakerIds,
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
            source = "群聊·全员自然讨论",
        )
        val marker = served.nextLabel?.let { "⟪NEXT:$it⟫" } ?: EndMarker
        val quote = served.turn.quoteMessageId?.let { "⟪QUOTE:$it⟫" }.orEmpty()
        val favorite = served.turn.favoriteMessageId?.let { "⟪FAVORITE:$it⟫" }.orEmpty()
        val recall = served.turn.recallBubbleNumber?.let { "⟪RECALL:$it⟫" }.orEmpty()
        val poke = if (served.turn.pokeUser) "⟪POKE_USER⟫" else ""
        val text = quote + favorite + recall + poke + served.turn.bubbles.joinToString(BubbleSeparator) + marker
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
        allowMessageActions: Boolean,
    ): List<PlannedTurn> {
        val displayNames = MigratedDomainStores.characters.settings.value.mapValues { it.value.displayName }
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
                    val resolvedId = resolveSpeakerId(rawSpeaker, validMembers, memberLabels, displayNames) ?: continue
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
                    val requestedRecall = item.optInt("recallBubbleNumber", 0)
                    add(
                        PlannedTurn(
                            characterId = resolvedId,
                            replyTo = item.optString("replyTo").ifBlank { "group" }.take(100),
                            intent = item.optString("intent").take(80),
                            bubbles = bubbles,
                            quoteMessageId = requestedQuoteId.takeIf { allowMessageActions && it in validUserMessageIds },
                            favoriteMessageId = requestedFavoriteId.takeIf { allowMessageActions && it in validUserMessageIds },
                            recallBubbleNumber = requestedRecall.takeIf { allowMessageActions && it in 1..bubbles.size },
                            pokeUser = allowMessageActions && item.optBoolean("pokeUser", false),
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

    private fun ensureRequiredSpeakers(
        parsed: List<PlannedTurn>,
        requiredSpeakerIds: List<String>,
        currentSpeakerId: String,
        replyLimit: Int,
    ): List<PlannedTurn> {
        val required = requiredSpeakerIds.toSet()
        val result = parsed.take(replyLimit).toMutableList()
        val missing = required
            .filterNot { requiredId -> result.any { it.characterId == requiredId } }
            .shuffled()

        missing.forEach { missingId ->
            if (result.size < replyLimit) {
                val insertAt = if (result.size <= 1) result.size else (1..result.size).random()
                result.add(insertAt, fallbackTurn(missingId))
            } else {
                val counts = result.groupingBy(PlannedTurn::characterId).eachCount()
                val replaceable = result.indices.filter { index ->
                    index != 0 && counts.getOrDefault(result[index].characterId, 0) > 1
                }
                val replaceIndex = replaceable.randomOrNull()
                    ?: result.indices.lastOrNull { index -> index != 0 && result[index].characterId !in required }
                if (replaceIndex != null) result[replaceIndex] = fallbackTurn(missingId)
            }
        }

        if (result.isEmpty()) result += fallbackTurn(currentSpeakerId)
        val firstIndex = result.indexOfFirst { it.characterId == currentSpeakerId }
        if (firstIndex < 0) {
            if (result.size >= replyLimit) {
                val counts = result.groupingBy(PlannedTurn::characterId).eachCount()
                val replaceIndex = result.indices.lastOrNull { index -> counts.getOrDefault(result[index].characterId, 0) > 1 }
                    ?: result.lastIndex
                result[replaceIndex] = fallbackTurn(currentSpeakerId)
            } else {
                result.add(0, fallbackTurn(currentSpeakerId))
            }
        }
        val updatedFirstIndex = result.indexOfFirst { it.characterId == currentSpeakerId }
        if (updatedFirstIndex > 0) {
            val first = result.removeAt(updatedFirstIndex)
            result.add(0, first)
        }
        return result.take(replyLimit)
    }

    private fun fallbackTurn(characterId: String): PlannedTurn = PlannedTurn(
        characterId = characterId,
        replyTo = "group",
        intent = "加入当前话题",
        bubbles = listOf("我也接一句。"),
        quoteMessageId = null,
        favoriteMessageId = null,
        recallBubbleNumber = null,
        pokeUser = false,
        statusText = "正在群里接话",
        gesture = "看着刚刷新的消息回了一句",
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
            .map { part -> part.lines().joinToString(" ") { line -> line.trim() }.trim().trim('"', '“', '”') }
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
