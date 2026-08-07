package com.jiacimu.lulu

// Chat response orchestration lives outside the screen so UI changes do not risk reply behavior.
import com.jiacimu.lulu.data.CharacterMessageFavorites
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluGroupChat
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.UserProfileContext
import com.jiacimu.lulu.system.LuluDeviceToolBridge
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.random.Random

internal const val SemanticBubbleSeparator = "⟪BUBBLE⟫"
private val QuoteDirectiveRegex = Regex("⟪QUOTE\\s*:\\s*([^⟫]+)⟫", RegexOption.IGNORE_CASE)
private val FavoriteDirectiveRegex = Regex("⟪FAVORITE\\s*:\\s*([^⟫]+)⟫", RegexOption.IGNORE_CASE)
private val RecallDirectiveRegex = Regex("⟪RECALL\\s*:\\s*(\\d+)⟫", RegexOption.IGNORE_CASE)
private val PokeUserDirectiveRegex = Regex("⟪POKE_USER⟫", RegexOption.IGNORE_CASE)

internal fun characterReplyQuoteId(text: String): String? =
    QuoteDirectiveRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)

internal fun characterFavoriteMessageId(text: String): String? =
    FavoriteDirectiveRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)

internal fun characterRecallBubbleNumber(text: String): Int? =
    RecallDirectiveRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }

internal fun characterPokesUser(text: String): Boolean = PokeUserDirectiveRegex.containsMatchIn(text)

/** Removes invisible role-action markers before anything is displayed or forwarded. */
internal fun stripCharacterReplyDirective(text: String): String =
    stripQqForwardDirective(
        text.replace(QuoteDirectiveRegex, "")
            .replace(FavoriteDirectiveRegex, "")
            .replace(RecallDirectiveRegex, "")
            .replace(PokeUserDirectiveRegex, ""),
    ).trim()

internal fun normalizeSemanticBubbles(text: String): String {
    val raw = text.replace("\r\n", "\n").trim()
    if (raw.isBlank()) return ""
    val quoteMarker = QuoteDirectiveRegex.find(raw)?.value.orEmpty()
    val favoriteMarker = FavoriteDirectiveRegex.find(raw)?.value.orEmpty()
    val recallMarker = RecallDirectiveRegex.find(raw)?.value.orEmpty()
    val pokeMarker = PokeUserDirectiveRegex.find(raw)?.value.orEmpty()
    val body = stripCharacterReplyDirective(raw)
        .split(SemanticBubbleSeparator)
        .map { bubble -> bubble.trim().trim('"') }
        .filter(String::isNotBlank)
        .joinToString("\n")
    if (body.isBlank()) return ""
    return quoteMarker + favoriteMarker + recallMarker + pokeMarker + body
}

internal data class CharacterReplyPresentation(
    val content: String,
    val quoteMessageId: String? = null,
    val favoriteMessageId: String? = null,
    val recallBubbleNumber: Int? = null,
    val pokeUser: Boolean = false,
)

internal fun parseCharacterReplyPresentation(text: String): CharacterReplyPresentation {
    val normalized = normalizeSemanticBubbles(text)
    return CharacterReplyPresentation(
        content = stripCharacterReplyDirective(normalized),
        quoteMessageId = characterReplyQuoteId(text),
        favoriteMessageId = characterFavoriteMessageId(text),
        recallBubbleNumber = characterRecallBubbleNumber(text),
        pokeUser = characterPokesUser(text),
    )
}

private fun rolePacingSeed(characterId: String): Long = abs(characterId.hashCode().toLong())

/** Give the typing indicator enough time to feel like a person is actually composing the next send. */
internal fun roleTypingLeadDelayMillis(characterId: String): Long =
    900L + (rolePacingSeed(characterId) % 1_100L)

private fun roleBubbleDelayMillis(characterId: String, bubble: String, bubbleIndex: Int): Long {
    val personalityBeat = 620L + (rolePacingSeed(characterId) % 520L)
    val contentBeat = (bubble.length * 18L).coerceIn(180L, 1_050L)
    return (personalityBeat + contentBeat + bubbleIndex * 90L).coerceIn(850L, 2_500L)
}

private fun roleRecallDelayMillis(characterId: String): Long =
    900L + (rolePacingSeed(characterId) % 650L)

/**
 * Reveals already-generated bubbles one by one. This never calls the model; it only controls local
 * message timing, optional recall, role-favorite, poke receipts, and optional voice playback.
 */
internal suspend fun appendRoleReplyWithPacing(
    conversationId: String,
    characterId: String,
    characterLabel: String,
    presentation: CharacterReplyPresentation,
): String {
    val bubbles = presentation.content
        .replace("\r\n", "\n")
        .split(Regex("\n+"))
        .map(String::trim)
        .filter(String::isNotBlank)
    if (bubbles.isEmpty()) return ""

    val before = MigratedDomainStores.chat.messages(conversationId).value
    val validQuoteId = presentation.quoteMessageId?.takeIf { id ->
        before.any { it.id == id && it.sender == LuluChatMessage.Sender.User }
    }
    val favoriteTarget = presentation.favoriteMessageId?.let { id ->
        before.firstOrNull { it.id == id && it.sender == LuluChatMessage.Sender.User }
    }
    val spokenMessages = mutableListOf<LuluChatMessage>()

    delay(roleTypingLeadDelayMillis(characterId))
    bubbles.forEachIndexed { index, bubble ->
        if (!currentCoroutineContext().isActive) return@forEachIndexed
        val created = MigratedDomainStores.chat.appendCharacterMessage(
            conversationId = conversationId,
            content = bubble,
            authorCharacterId = characterId,
            replyToMessageId = validQuoteId.takeIf { index == 0 },
        )
        if (presentation.recallBubbleNumber == index + 1) {
            delay(roleRecallDelayMillis(characterId))
            if (currentCoroutineContext().isActive) {
                MigratedDomainStores.chat.retractCharacterMessage(created.id, characterLabel)
            }
        } else {
            spokenMessages += created
        }
        if (index < bubbles.lastIndex) {
            delay(roleBubbleDelayMillis(characterId, bubble, index))
        }
    }

    favoriteTarget?.let { target ->
        CharacterMessageFavorites.favorite(characterId, conversationId, target)
    }
    if (presentation.pokeUser && currentCoroutineContext().isActive) {
        delay(700L + rolePacingSeed(characterId) % 500L)
        MigratedDomainStores.chat.appendSystemMessage(conversationId, "[戳一戳] $characterLabel 戳了戳你。")
    }
    spokenMessages.forEach { message ->
        ChatAutoVoicePlayback.enqueue(characterId, message.id, message.content)
    }
    return spokenMessages.joinToString("\n", transform = LuluChatMessage::content)
}

private data class GroupReplyFlow(
    val content: String,
    val nextSpeakerName: String?,
    val shouldEnd: Boolean,
    val quoteMessageId: String?,
    val favoriteMessageId: String?,
    val recallBubbleNumber: Int?,
    val pokeUser: Boolean,
)

private fun parseGroupReplyFlow(text: String): GroupReplyFlow {
    val nextMatch = Regex("⟪NEXT\\s*:\\s*([^⟫]+)⟫", RegexOption.IGNORE_CASE).find(text)
    val actionText = text
        .replace(Regex("⟪NEXT\\s*:\\s*[^⟫]+⟫", RegexOption.IGNORE_CASE), "")
        .replace(Regex("⟪END⟫", RegexOption.IGNORE_CASE), "")
        .trim()
    val presentation = parseCharacterReplyPresentation(actionText)
    return GroupReplyFlow(
        content = presentation.content,
        nextSpeakerName = nextMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank),
        shouldEnd = text.contains("⟪END⟫", ignoreCase = true),
        quoteMessageId = presentation.quoteMessageId,
        favoriteMessageId = presentation.favoriteMessageId,
        recallBubbleNumber = presentation.recallBubbleNumber,
        pokeUser = presentation.pokeUser,
    )
}

internal suspend fun runGroupReplies(
    conversationId: String,
    group: LuluGroupChat,
    pendingText: String,
    initialHistory: String,
    activeLabel: String,
    archiveId: String?,
    characterNames: Map<String, String>,
    onError: suspend (String) -> Unit,
    sceneContext: String = "你正在群聊《${group.name}》中。群里的所有消息对在场成员可见。",
    onSpeakerChange: (String?) -> Unit = {},
    afterReply: suspend (String, String) -> Unit = { _, _ -> },
) {
    val validMembers = group.members.filter { it.characterId in characterNames }
    if (validMembers.size < 2) {
        onError("群聊至少需要两个仍然存在的角色")
        return
    }
    val mentioned = validMembers.filter { member ->
        val name = member.groupNickname.ifBlank { characterNames[member.characterId].orEmpty() }
        name.isNotBlank() && pendingText.contains("@$name", ignoreCase = true)
    }
    val beforeLoop = MigratedDomainStores.chat.messages(conversationId).value
    val recalledBeforeLoop = recalledMessageIds(beforeLoop)
    val lastSpeaker = beforeLoop
        .asSequence()
        .filterNot { it.id in recalledBeforeLoop }
        .lastOrNull { it.sender == LuluChatMessage.Sender.Character }
        ?.authorCharacterId
    val random = Random(System.nanoTime())
    val remaining = validMembers
        .filterNot { candidate -> mentioned.any { it.characterId == candidate.characterId } }
        .shuffled(random)
        .sortedBy { candidate -> candidate.characterId == lastSpeaker }
    val ordered = mentioned + remaining
    val explicitAll = pendingText.contains("@全体成员")
    val replyLimit = when {
        explicitAll -> validMembers.size.coerceAtMost(group.maxAutoReplies.coerceAtLeast(validMembers.size))
        mentioned.isNotEmpty() -> group.maxAutoReplies.coerceAtLeast(mentioned.size)
        group.allowCharacterConversation -> group.maxAutoReplies
        else -> 1
    }.coerceIn(1, 8)
    val pendingSpeakers = when {
        explicitAll -> validMembers.shuffled(random).toMutableList()
        mentioned.isNotEmpty() -> mentioned.toMutableList()
        else -> mutableListOf(ordered.first())
    }
    var index = 0

    while (index < replyLimit && pendingSpeakers.isNotEmpty()) {
        val member = pendingSpeakers.removeAt(0)
        if (!currentCoroutineContext().isActive) return
        onSpeakerChange(member.characterId)
        val character = MigratedDomainStores.characters.get(member.characterId)
        val memberLabel = member.groupNickname.ifBlank { character.displayName }
        val allLatestMessages = MigratedDomainStores.chat.messages(conversationId).value
        val recalledLatest = recalledMessageIds(allLatestMessages)
        val latestMessages = allLatestMessages.filterNot { it.id in recalledLatest }
        val history = if (index == 0) initialHistory else buildBoundedHistory(
            messages = allLatestMessages,
            characterName = memberLabel,
            characterNames = characterNames,
        )
        val memberList = group.members.joinToString("、") { candidate ->
            candidate.groupNickname.ifBlank { characterNames[candidate.characterId] ?: candidate.characterId }
        }
        val quotableUserMessages = latestMessages
            .filter { it.sender == LuluChatMessage.Sender.User }
            .takeLast(6)
        val groupInput = buildString {
            appendLine("[这是群聊，不是私聊。群名：${group.name}；群成员：${group.userGroupNickname}、$memberList。]")
            appendLine("[当前由你（$memberLabel）发言。只代表你自己，严格遵循你的人设和关系边界；不要替别人说话，不要输出姓名标签。]")
            appendLine("[群聊是连续发生的生活过程，不是排队答题。成员不需要轮流发言，也不需要每个人都回应；谁想接话、谁沉默、谁连续多说几句，都应由当下内容、人设和关系决定。]")
            appendLine("[不要按照成员列表顺序安排下一位发言人。若这一刻自然会有人接话，只根据谁最可能真的想说话来选择 ⟪NEXT:成员名⟫；没人需要接就结束。]")
            if (quotableUserMessages.isNotEmpty()) {
                appendLine("[以下是近期真实的用户气泡；消息ID只用于引用或收藏动作，不属于聊天正文：]")
                quotableUserMessages.forEach { item -> appendLine("[消息ID=${item.id} 内容=${qqForwardContextText(item.content).take(300)}]") }
                appendLine("[引用是正常聊天动作，不必过度克制：当用户连续发了几条、你要针对其中某一句单独回答、话题已经往后走但你想捡回某句、或不引用会让指代不清时，可以在整段回复最前输出 ⟪QUOTE:消息ID⟫。只回应最新一句且上下文很清楚时不必硬引用。]")
                appendLine("[收藏是你这个角色自己的主观行为。如果用户说了让你很在意、很喜欢、想以后记住或回看的话，例如承诺、特殊称呼、重要心意、戳中你的句子、对关系有意义的瞬间，可以输出 ⟪FAVORITE:消息ID⟫。是否收藏必须服从你的人设和当下感受，不要固定概率，也不要为了展示功能乱收藏。]")
                appendLine("[⟪QUOTE:...⟫ 与 ⟪FAVORITE:...⟫ 可以同时出现，也可以都不出现；只能使用上面真实存在的消息ID。]")
            }
            if (index > 0) {
                val previousMessage = latestMessages.lastOrNull { it.sender == LuluChatMessage.Sender.Character }
                val previousName = previousMessage?.authorCharacterId?.let { id ->
                    group.members.firstOrNull { it.characterId == id }?.groupNickname
                        ?.ifBlank { characterNames[id].orEmpty() }
                        ?.ifBlank { "上一位角色" }
                } ?: "上一位角色"
                appendLine("[$previousName 刚刚说：${previousMessage?.content?.takeLast(900).orEmpty()}]")
                appendLine("[你现在站在 $previousName 说完后的下一刻，主要对刚发生的内容作出你自己的反应。可以赞同、质疑、追问、开玩笑、插嘴、岔开或不接用户原题；不要重新回到话题起点。]")
            }
            appendLine("[这是即时通讯软件里的线上聊天。请按你自己的语气、停顿、情绪变化、补充、转折、追问和聊天习惯决定什么时候按一次发送。现实聊天中会在这里按发送，就在这里结束当前气泡。]")
            appendLine("[一个气泡通常只承载一个当下表达动作。若先回应、再补一句、再转折或追问，现实聊天会分别按发送，就用 $SemanticBubbleSeparator 分成多个短气泡。不要按固定字数机械切分，也不要把多个表达动作硬塞进一个大气泡。]")
            appendLine("[只有非常少见、很符合当下人设的情况下，例如刚说出口就觉得说漏嘴、说重了或突然后悔，才可以在回复末尾输出 ⟪RECALL:n⟫，n 是本次第 n 个气泡（从1开始）。不要为了显得像真人而频繁撤回。]")
            appendLine("[如果你此刻真的会自然地戳一下用户，可以在回复末尾输出 ⟪POKE_USER⟫；尤其用户刚戳过你时可以考虑戳回来，但不要滥用。]")
            if (group.allowCharacterConversation) {
                appendLine("[若另一位成员此刻真的会自然接话，在末尾输出 ⟪NEXT:成员名⟫；若这轮自然停下，输出 ⟪END⟫。不要为了凑人数、轮班或让每个人都露脸而硬续。该标记不会显示给用户。]")
            }
            if (index == 0) append("用户刚在群里说：$pendingText")
            else append("这轮话题最初由用户说：$pendingText")
        }
        var result = LuluDeviceToolBridge.respond(
            characterId = member.characterId,
            history = history,
            userText = groupInput,
            title = activeLabel,
            archiveId = archiveId,
            sceneContext = sceneContext,
        )
        if (result.getOrNull()?.text.isNullOrBlank() && currentCoroutineContext().isActive) {
            result = LuluDeviceToolBridge.respond(
                characterId = member.characterId,
                history = history,
                userText = "$groupInput\n[上一次没有生成有效发言。现在直接给出至少一个自然完整的表达。]",
                title = activeLabel,
                archiveId = archiveId,
                sceneContext = sceneContext,
            )
        }
        if (!currentCoroutineContext().isActive) return
        val reply = result.getOrNull()
        if (reply != null) {
            val flow = parseGroupReplyFlow(reply.text)
            val semanticReply = flow.content
            if (semanticReply.isNotBlank()) {
                val validQuoteId = flow.quoteMessageId?.takeIf { quoteId ->
                    latestMessages.any { it.id == quoteId && it.sender == LuluChatMessage.Sender.User }
                }
                val shown = appendRoleReplyWithPacing(
                    conversationId = conversationId,
                    characterId = member.characterId,
                    characterLabel = memberLabel,
                    presentation = CharacterReplyPresentation(
                        content = semanticReply,
                        quoteMessageId = validQuoteId,
                        favoriteMessageId = flow.favoriteMessageId,
                        recallBubbleNumber = flow.recallBubbleNumber,
                        pokeUser = flow.pokeUser,
                    ),
                )
                if (shown.isNotBlank()) afterReply(member.characterId, shown)
            }
            if (group.allowCharacterConversation && !flow.shouldEnd && index + 1 < replyLimit) {
                val requestedNext = flow.nextSpeakerName?.let { requested ->
                    validMembers.firstOrNull { candidate ->
                        if (candidate.characterId == member.characterId) return@firstOrNull false
                        val nickname = candidate.groupNickname.ifBlank { characterNames[candidate.characterId].orEmpty() }
                        val displayName = characterNames[candidate.characterId].orEmpty()
                        requested.equals(nickname, ignoreCase = true) || requested.equals(displayName, ignoreCase = true)
                    }
                }
                if (requestedNext != null) {
                    pendingSpeakers.removeAll { it.characterId == requestedNext.characterId }
                    pendingSpeakers.add(0, requestedNext)
                }
            }
        } else {
            val error = result.exceptionOrNull()
            onError("${character.displayName}回复失败：${error?.message ?: "未知错误"}")
        }
        index += 1
    }
    onSpeakerChange(null)
}

internal fun buildBoundedHistory(
    messages: List<LuluChatMessage>,
    characterName: String,
    characterNames: Map<String, String> = emptyMap(),
    maxMessages: Int = 30,
    maxChars: Int = 12_000,
): String {
    val recalledIds = recalledMessageIds(messages)
    val normalized = messages
        .filterNot { it.id in recalledIds }
        .filter { message ->
            message.sender != LuluChatMessage.Sender.System ||
                message.content.startsWith("[群成员变更]") ||
                message.content.startsWith("[戳一戳]") ||
                message.content.startsWith("[撤回|")
        }
        .takeLast(maxMessages)
    val lines = normalized.map { message ->
        val role = when {
            message.sender == LuluChatMessage.Sender.System -> "聊天系统"
            message.sender == LuluChatMessage.Sender.User -> UserProfileContext.displayLabel()
            else -> message.authorCharacterId?.let { characterNames[it] } ?: characterName
        }
        val quoteContext = message.replyToMessageId
            ?.let { replyId -> messages.firstOrNull { it.id == replyId } }
            ?.let { original -> "（引用：${qqForwardContextText(original.content).take(180)}）" }
            .orEmpty()
        val content = if (message.sender == LuluChatMessage.Sender.System) {
            stripRecallReceiptDirective(message.content)
        } else {
            qqForwardContextText(message.content)
        }
        "$role$quoteContext：$content"
    }
    val selected = ArrayDeque<String>()
    var chars = 0
    for (line in lines.asReversed()) {
        if (selected.isNotEmpty() && chars + line.length > maxChars) break
        selected.addFirst(line)
        chars += line.length
    }
    return selected.joinToString("\n")
}
