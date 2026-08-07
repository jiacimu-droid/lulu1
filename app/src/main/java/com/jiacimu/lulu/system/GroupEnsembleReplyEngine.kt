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
 * Nobody is required to speak merely because they are a group member. The current speaker and people
 * explicitly mentioned by the user are the only required participants; everyone else may speak,
 * interrupt, return later, or stay silent according to personality and the live conversation.
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
        val explicitAll = latestUserMessage.content.contains("@全体成员")

        val replyLimit = group.maxAutoReplies.coerceIn(1, 8)
        val requiredSpeakerIds = buildList {
            add(currentSpeakerId)
            if (explicitAll) {
                validMembers.forEach { member -> if (member.characterId !in this) add(member.characterId) }
            } else {
                mentionedIds.forEach { id -> if (id !in this) add(id) }
            }
        }.take(replyLimit)
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
                appendLine("本轮界面当前先显示 characterId=$currentSpeakerId（${memberLabels[currentSpeakerId]}）正在输入，因此 turns 第一项必须是这个角色。")
                appendLine("本轮最多允许 $replyLimit 个角色回合，这是安全上限，不是目标数量；自然说完可以只有 1 个回合。")
                appendLine("本轮真正必须出现的角色ID只有：${requiredSpeakerIds.joinToString(",")}。未列出的成员完全可以不说话。")
                if (mentionedIds.isNotEmpty()) appendLine("用户明确点名了：${mentionedIds.joinToString(",")}。被点名角色应在安全上限内优先获得回应机会。")
                if (explicitAll) appendLine("用户使用了 @全体成员，因此本轮才需要尽量让所有成员分别作出自己的反应。")
                appendLine("用户刚刚在群里说：${latestUserMessage.content}")
                if (actionableUserMessages.isNotEmpty()) {
                    appendLine("\n【近期真实用户气泡；消息ID只供引用或角色收藏使用】")
                    actionableUserMessages.forEach { item -> appendLine("消息ID=${item.id}；内容=${item.content.take(320)}") }
                }
                if (history.isNotBlank()) appendLine("\n【群聊最近记录；这些都已经真实发生】\n${history.takeLast(14_000)}")
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
                appendLine("\n【调用来源】这是群聊界面的一次整轮生成。禁止轮班、禁止为了热闹强制全员出现、禁止固定 ABC 顺序；只有 @全体成员 时才需要全员回应。")
            },
            instruction = """
                你是多人群聊的整体编排器。把这一轮写成真正会发生的群聊：有人抢着说，有人沉默，有人隔一会儿才接，有人连续回两次。不要把群聊写成成员排队交答案。

                只返回一个 JSON 对象，不要代码块、分析、旁白或额外说明：
                {"turns":[{"characterId":"真实角色ID","replyTo":"user|group|另一个真实角色ID","intent":"简短意图","bubbles":["气泡"],"quoteMessageId":"真实用户消息ID或空字符串","favoriteMessageId":"角色真心想收藏的真实用户消息ID或空字符串","recallBubbleNumber":0,"pokeUser":false,"statusText":"简短状态","gesture":"该角色此刻的微动作神态","innerThought":"该角色没说出口的一瞬心声，可为空","mood":"简短心情"}]}

                规则：
                1. turns 第一项必须是指定的当前发言者，因为界面已经显示这个人正在输入。之后没有固定顺序。
                2. turns 可以只有 1 项，也可以有多项，但绝不能为了凑人数而让所有成员轮流说。只有“本轮真正必须出现的角色ID”需要在安全上限内出现；其他成员可以完全沉默。
                3. 后续谁接话只看这一刻谁真的最可能想说：可以 A→C，也可以 B 一个人说完，也可以 C→A→C；不要按成员列表顺序，不要默认 A→B→C，更不要自动补齐没说话的人。
                4. 同一个角色可以在不同角色回合之间再次出现，只要这符合真实群聊；也可以整轮没人接他的下一句。连续发出的多个气泡则放在同一个 turn 的 bubbles 中。
                5. 后续角色应真正接住已经发生的内容：赞同、质疑、反驳、追问、补充、插话、玩笑、岔开或改口；不要每个人都从头回答用户同一个问题。
                6. 每个角色必须严格保持自己的语言习惯、关系边界、称呼和性格差异。不要把所有人统一写成温柔助手，也不要让一个角色替另一个角色发言。
                7. bubbles 是这个角色一次次按下“发送”后出现的气泡。一个气泡通常只承载一个当下表达动作；先回应、再补一句、再转折或追问时，可以自然拆成几个短气泡。不要按固定字数、句号或固定数量机械切，也不要把几个不同表达动作硬塞成长段。
                8. quoteMessageId 是正常聊天能力，不必过度克制。用户连续发了几条、角色针对其中某一句单独回应、想捡回稍早的一句、或不引用会让指代不清时，可以填写真实消息ID；只回最新一句且上下文很清楚时留空。
                9. favoriteMessageId 是角色自己的主观动作。如果用户某句话让这个角色很在意、很喜欢、想以后回看或对关系有特殊意义，例如承诺、特殊称呼、重要心意、戳中他的句子，可以收藏。是否收藏必须服从这个角色的人设和感受，不设固定概率，也不能为了展示功能乱收藏。
                10. recallBubbleNumber 默认 0。只有极少数角色刚说出口就后悔、说漏嘴或想装作没说过的时刻才填真实序号。
                11. pokeUser 默认 false。只有这个角色此刻真的会自然戳一下用户时才设为 true。
                12. 不要虚构用户当前身体、环境或正在做的事情。只能依据用户刚说的话、群聊记录、角色设定和真实时间线互动。
                13. 最后一轮不需要总结，不需要“把话题交给主人”，自然停住就可以。
                14. ${if (isCall) "这是实时群聊电话，quoteMessageId、favoriteMessageId 留空，recallBubbleNumber=0，pokeUser=false；语言必须更口语化、适合直接念出。" else "这是文字群聊，可以自然使用连续短气泡、引用、角色主观收藏，以及非常偶发的撤回或戳一戳。"}
                15. statusText、gesture、innerThought、mood 分别属于当前角色本人，不能写成系统分析或推理过程。
            """.trimIndent(),
            source = if (isCall) "群聊电话·自然讨论" else "群聊·自然讨论",
            title = title,
            temperature = 1.02,
            maxTokens = (650 + replyLimit * 280).coerceIn(950, 3_000),
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
            source = "群聊·自然讨论",
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
        val validRequired = requiredSpeakerIds.take(replyLimit)
        val result = parsed.take(replyLimit).toMutableList()

        validRequired.forEach { requiredId ->
            if (result.any { it.characterId == requiredId }) return@forEach
            if (result.size < replyLimit) result += fallbackTurn(requiredId)
        }
        if (result.isEmpty()) result += fallbackTurn(currentSpeakerId)

        val firstIndex = result.indexOfFirst { it.characterId == currentSpeakerId }
        if (firstIndex < 0) {
            if (result.size >= replyLimit) result[result.lastIndex] = fallbackTurn(currentSpeakerId)
            else result.add(0, fallbackTurn(currentSpeakerId))
        } else if (firstIndex > 0) {
            val first = result.removeAt(firstIndex)
            result.add(0, first)
        }
        return result.take(replyLimit)
    }

    private fun fallbackTurn(characterId: String): PlannedTurn = PlannedTurn(
        characterId = characterId,
        replyTo = "group",
        intent = "自然回应",
        bubbles = listOf("嗯？我在。"),
        quoteMessageId = null,
        favoriteMessageId = null,
        recallBubbleNumber = null,
        pokeUser = false,
        statusText = "看着群聊",
        gesture = "停下来回消息",
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
