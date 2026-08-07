package com.jiacimu.lulu.system

import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelReply
import com.jiacimu.lulu.data.CharacterSettings
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
import kotlin.math.ceil

/**
 * Plans one natural multi-turn group discussion with one model request.
 *
 * Every character participates, but participation is not a one-person-one-line roll call. The same
 * character may return several times to answer the user, respond to another member, interrupt,
 * challenge, joke or pull the topic back. The complete exchange is generated once and cached; the
 * existing chat loop then reveals it turn by turn without making another API request.
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
        val quotableUserMessages = messages.filter { it.sender == LuluChatMessage.Sender.User }.takeLast(8)
        val validQuoteIds = quotableUserMessages.mapTo(mutableSetOf(), LuluChatMessage::id)
        val channel = if (sceneContext.contains("电话")) "call" else "chat"
        val planKey = "${conversation.id}:${latestUserMessage.id}:$channel"

        takeCachedTurn(planKey, characterId)?.let { return Result.success(it) }

        val settings = MigratedDomainStores.characters.settings.value
        val validMembers = group.members.filter { member -> member.characterId in settings }
        if (validMembers.size < 2) {
            return Result.success(ModelReply(text = "群里现在没有足够的成员能接话。$EndMarker"))
        }
        if (validMembers.size > 8) {
            return Result.success(ModelReply(text = "这个群目前超过了 8 个角色，暂时无法保证所有人都完整参与这一轮。$EndMarker"))
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
        val replyLimit = group.maxAutoReplies.coerceIn(memberCount, 8)
        val minimumDiscussionTurns = ceil(memberCount * 1.6)
            .toInt()
            .coerceIn(memberCount, replyLimit)
        val requiredSpeakerIds = buildList {
            add(currentSpeakerId)
            mentionedIds.forEach { id -> if (id !in this) add(id) }
            validMembers.forEach { member -> if (member.characterId !in this) add(member.characterId) }
        }

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
                appendLine("本轮第一个发言者必须是 characterId=$currentSpeakerId（${memberLabels[currentSpeakerId]}）。")
                appendLine("本轮应生成 $minimumDiscussionTurns—$replyLimit 个角色发言回合；整段讨论只调用模型一次。")
                appendLine("所有成员都必须实际发言至少一次：${requiredSpeakerIds.joinToString(",")}")
                if (replyLimit > memberCount) appendLine("至少一名角色必须在后面再次回来接话，不能所有人各说一次就结束。")
                if (mentionedIds.isNotEmpty()) appendLine("被用户点名的角色优先回应，但其他成员仍必须加入讨论：${mentionedIds.joinToString(",")}")
                appendLine("用户刚刚在群里说：${latestUserMessage.content}")
                if (quotableUserMessages.isNotEmpty()) {
                    appendLine("\n【可引用的近期用户气泡；只有确实针对某一句单独回应时才引用，不要为了展示功能而引用】")
                    quotableUserMessages.forEach { item -> appendLine("消息ID=${item.id}；内容=${item.content.take(320)}") }
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
                appendLine("\n【调用来源】这是群聊界面的一次整轮生成请求；忽略底层旧调用中关于单人回复、固定气泡数量、固定字数或提前结束的任何文字。")
            },
            instruction = """
                你是多人群聊的整体编排器。请把这一轮写成一段真正发生的交流讨论，而不是让成员轮流交一份答案。你同时理解所有人的人设，只进行这一次生成。

                只返回一个 JSON 对象，不要代码块、分析、旁白或额外说明：
                {"turns":[{"characterId":"真实角色ID","replyTo":"user|group|另一个真实角色ID","intent":"回应、追问、反驳、补充、插话、调侃等简短意图","bubbles":["气泡1","气泡2"],"quoteMessageId":"要引用的真实用户消息ID或空字符串","statusText":"简短状态","gesture":"该角色此刻的微动作神态","innerThought":"该角色没说出口的一瞬心声，可为空","mood":"简短心情"}]}

                硬性规则：
                1. turns 数量必须处于指定范围。第一项必须是指定的第一个发言者。
                2. 群内每个角色都必须真正发送至少一次有内容的消息。不能沉默、旁听、只写状态或用占位话混过去。
                3. 不能每个人各说一句就结束。只要允许的回合数多于成员数，至少一名与话题关系更强的角色必须再次出现；可以形成 A→B→A→C→B 这样的往返。
                4. 至少两次发言应直接回应其他角色，replyTo 填对方真实 characterId；同时也要有人直接回应用户。禁止所有人都只盯着用户从头回答一遍。
                5. 后续角色必须接住已经发生的内容：赞同、质疑、反驳、追问、补充、插话、玩笑、改口或把跑偏的话拉回来。允许隔一两个人后再次回到先前的分歧。
                6. 全员参与不等于平均分配。话题核心人物可以出现两三次；安静或冷淡的人可以说得短，但仍应给出符合其人设的真实反应。
                7. 不要把讨论写得过分整齐。允许短插话、半句反应、打断、改口和轻微跑题，但每一条都必须对交流有作用，禁止连续输出“哈哈”“确实”等空话。
                8. 每个角色必须严格保持自己的语言习惯、关系边界、称呼和性格差异。不要把所有人统一写成温柔助手，也不要让一个角色替另一个角色发言。
                9. 这是即时通讯软件里的日常线上群聊。bubbles 就是这个角色实际一次次按下“发送”后出现的消息气泡。先想清楚这个角色此刻真正想表达什么，再按照他自己的语气、停顿、情绪变化、犹豫、补充、转折、追问、吐槽和聊天习惯，自然决定什么时候结束当前气泡、什么时候再发下一条。
                10. 不按标点、句号、固定字数或固定气泡数量机械切分，也不要为了减少气泡而把现实聊天中本来会分开发送的话强行塞成长段。判断标准是：如果这个角色现实聊天时会在这里按一次发送，就在这里结束一个 bubble。
                11. quoteMessageId 是可选能力，不是固定格式要求。只有某个角色确实是在针对用户此前某一句气泡单独回应，而且使用引用会比普通接话更自然时，才填入上方提供的真实用户消息ID；否则必须留空。不能引用不存在的ID，不能把 replyTo 的角色ID填到 quoteMessageId，也不能每个角色都引用。
                12. 不要虚构用户当前身体、环境或正在做的事情。只能依据用户刚说的话、群聊记录、角色设定和真实时间线互动。
                13. 最后一两轮可以自然把话重新抛给主人，也可以停在一个仍有余味的观点上；禁止写“讨论结束”“大家都发表了意见”等总结式收尾。
                14. ${if (isCall) "这是实时群聊电话，quoteMessageId 留空；语言必须更口语化、适合直接念出。" else "这是文字群聊，可以自然使用短促停顿、连续气泡和偶尔引用用户气泡。"}
                15. statusText、gesture、innerThought、mood 分别属于当前角色本人，不能写成系统分析或推理过程。
            """.trimIndent(),
            source = if (isCall) "群聊电话·单次多轮讨论" else "群聊·单次多轮讨论",
            title = title,
            temperature = 0.96,
            maxTokens = (900 + replyLimit * 390).coerceIn(1_500, 3_800),
            connectionOverride = connection,
        )

        val baseReply = generated.getOrElse { error -> return Result.success(fallbackReply(currentSpeakerId, memberLabels, error.message)) }
        val parsed = parseTurns(
            raw = baseReply.text,
            validMembers = validMembers,
            memberLabels = memberLabels,
            replyLimit = replyLimit,
            validQuoteIds = if (isCall) emptySet() else validQuoteIds,
        )
        val completed = ensureDiscussionShape(
            parsed = parsed,
            requiredSpeakerIds = requiredSpeakerIds,
            currentSpeakerId = currentSpeakerId,
            minimumTurns = minimumDiscussionTurns,
            replyLimit = replyLimit,
            userText = latestUserMessage.content,
            settings = settings,
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
            source = "群聊·单次多轮讨论",
        )
        val marker = served.nextLabel?.let { "⟪NEXT:$it⟫" } ?: EndMarker
        val quote = served.turn.quoteMessageId?.let { "⟪QUOTE:$it⟫" }.orEmpty()
        val text = quote + served.turn.bubbles.joinToString(BubbleSeparator) + marker
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
        validQuoteIds: Set<String>,
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
                    val resolvedId = resolveSpeakerId(rawSpeaker, validMembers, memberLabels, settings) ?: continue
                    val rawBubbles = buildList {
                        val bubblesArray = item.optJSONArray("bubbles")
                        if (bubblesArray != null) {
                            for (bubbleIndex in 0 until bubblesArray.length()) add(bubblesArray.optString(bubbleIndex))
                        } else add(item.optString("text").ifBlank { item.optString("content") })
                    }
                    val bubbles = normalizeBubbles(rawBubbles)
                    if (bubbles.isEmpty()) continue
                    val requestedQuoteId = item.optString("quoteMessageId").trim()
                    add(
                        PlannedTurn(
                            characterId = resolvedId,
                            replyTo = item.optString("replyTo").ifBlank { "group" }.take(100),
                            intent = item.optString("intent").take(80),
                            bubbles = bubbles,
                            quoteMessageId = requestedQuoteId.takeIf { it in validQuoteIds },
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

    private fun ensureDiscussionShape(
        parsed: List<PlannedTurn>,
        requiredSpeakerIds: List<String>,
        currentSpeakerId: String,
        minimumTurns: Int,
        replyLimit: Int,
        userText: String,
        settings: Map<String, CharacterSettings>,
    ): List<PlannedTurn> {
        val required = requiredSpeakerIds.toSet()
        val result = parsed.filter { it.characterId in required }.take(replyLimit).toMutableList()

        requiredSpeakerIds.forEach { missingId ->
            if (result.any { it.characterId == missingId }) return@forEach
            val target = result.lastOrNull()?.characterId ?: "user"
            val replacement = fallbackParticipationTurn(missingId, target, userText, settings[missingId])
            if (result.size < replyLimit) result += replacement
            else {
                val counts = result.groupingBy(PlannedTurn::characterId).eachCount()
                val replaceIndex = result.indexOfLast { turn -> turn.characterId != currentSpeakerId && counts.getOrDefault(turn.characterId, 0) > 1 }
                    .takeIf { it >= 0 }
                    ?: result.indexOfLast { turn -> counts.getOrDefault(turn.characterId, 0) > 1 }
                if (replaceIndex >= 0) result[replaceIndex] = replacement
            }
        }

        var cursor = 0
        while (result.size < minimumTurns && result.size < replyLimit) {
            val lastSpeaker = result.lastOrNull()?.characterId
            val candidates = requiredSpeakerIds.filterNot { it == lastSpeaker }.ifEmpty { requiredSpeakerIds }
            val speakerId = candidates[cursor % candidates.size]
            val target = result.lastOrNull()?.characterId ?: "user"
            result += fallbackContinuationTurn(speakerId, target, userText, settings[speakerId])
            cursor += 1
        }
        return orderForRequestedSpeaker(result, currentSpeakerId)
    }

    private fun fallbackParticipationTurn(characterId: String, replyTo: String, userText: String, character: CharacterSettings?): PlannedTurn {
        val topic = cleanTopic(userText)
        val bubbles = when (personaTone(character)) {
            PersonaTone.Reserved -> listOf("我也听到了。", "关于“$topic”，我不想只给一个敷衍的结论。")
            PersonaTone.Lively -> listOf("等等，我也要加入。", "你们说到“$topic”就想直接翻篇？我还有意见呢。")
            PersonaTone.Direct -> listOf("我补一句。", "“$topic”这件事，刚才那个说法还不够准确。")
            PersonaTone.Neutral -> listOf("我也接一下。", "你刚才提到“$topic”，我更想知道真正卡住你的是什么。")
        }
        return PlannedTurn(characterId, replyTo, "加入并补充", bubbles, null, "加入讨论", "接过话头", "这一轮我也有自己的反应。", "投入")
    }

    private fun fallbackContinuationTurn(characterId: String, replyTo: String, userText: String, character: CharacterSettings?): PlannedTurn {
        val topic = cleanTopic(userText)
        val bubbles = when (personaTone(character)) {
            PersonaTone.Reserved -> listOf("刚才那句我还没完全同意。", "至少在“$topic”这一点上，不能这么快下结论。")
            PersonaTone.Lively -> listOf("不行，我得再接一句。", "你刚才那个角度挺有意思，但“$topic”明明还能继续聊呀。")
            PersonaTone.Direct -> listOf("先别急着收尾。", "你刚刚绕开的那部分，恰好才是“$topic”的重点。")
            PersonaTone.Neutral -> listOf("我想再接着问一句。", "听完你们刚才的话，我反而更在意“$topic”背后的原因。")
        }
        return PlannedTurn(characterId, replyTo, "再次接话", bubbles, null, "继续讨论", "顺着上一句话继续", "这个话题还没有真正说完。", "认真")
    }

    private enum class PersonaTone { Reserved, Lively, Direct, Neutral }

    private fun personaTone(character: CharacterSettings?): PersonaTone {
        val persona = character?.persona.orEmpty()
        return when {
            listOf("冷淡", "寡言", "克制", "沉默", "内敛").any { persona.contains(it) } -> PersonaTone.Reserved
            listOf("活泼", "开朗", "话多", "元气", "爱闹").any { persona.contains(it) } -> PersonaTone.Lively
            listOf("直接", "毒舌", "强势", "锋利", "严肃").any { persona.contains(it) } -> PersonaTone.Direct
            else -> PersonaTone.Neutral
        }
    }

    private fun cleanTopic(userText: String): String = userText
        .replace(Regex("@[^\\s，。！？!?]+"), "")
        .replace("\n", " ")
        .trim()
        .take(30)
        .ifBlank { "刚才这个话题" }

    private fun resolveSpeakerId(
        raw: String,
        validMembers: List<LuluGroupMember>,
        memberLabels: Map<String, String>,
        settings: Map<String, CharacterSettings>,
    ): String? {
        val clean = raw.trim()
        if (clean.isBlank()) return null
        return validMembers.firstOrNull { member ->
            val label = memberLabels[member.characterId].orEmpty()
            val displayName = settings[member.characterId]?.displayName.orEmpty()
            clean == member.characterId || clean.equals(label, ignoreCase = true) || clean.equals(displayName, ignoreCase = true)
        }?.characterId
    }

    private fun orderForRequestedSpeaker(turns: List<PlannedTurn>, requestedCharacterId: String): List<PlannedTurn> {
        val firstIndex = turns.indexOfFirst { it.characterId == requestedCharacterId }
        if (firstIndex <= 0) return turns
        val first = turns[firstIndex]
        return listOf(first) + turns.filterIndexed { index, _ -> index != firstIndex }
    }

    private fun normalizeBubbles(values: List<String>): List<String> {
        return values.flatMap { value ->
            value.replace("\r\n", "\n")
                .split(BubbleSeparator)
                .map { part -> part.replace(Regex("\\s*\\n+\\s*"), " ").trim().trim('"', '“', '”') }
        }.filter(String::isNotBlank)
    }

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
