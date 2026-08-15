package com.jiacimu.lulu

// Chat response orchestration lives outside the screen so UI changes do not risk reply behavior.
import com.jiacimu.lulu.data.CharacterMessageFavorites
import com.jiacimu.lulu.data.DigitalLifeProfileStore
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

/**
 * User messages that belong to the current unanswered turn.
 *
 * This is intentionally turn-based rather than count-based: one new user message means one action
 * candidate; five consecutive user messages before the role replies means five candidates. Older
 * user messages that already received a character reply stay in history/memory but are no longer
 * eligible for automatic quote/favorite actions in a later turn.
 */
internal fun currentReplyTargetUserMessages(messages: List<LuluChatMessage>): List<LuluChatMessage> {
    val lastCharacterIndex = messages.indexOfLast { it.sender == LuluChatMessage.Sender.Character }
    return messages.drop(lastCharacterIndex + 1)
        .filter { it.sender == LuluChatMessage.Sender.User }
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
    actionableUserMessageIds: Set<String>? = null,
): String {
    val bubbles = presentation.content
        .replace("\r\n", "\n")
        .split(Regex("\n+"))
        .map(String::trim)
        .filter(String::isNotBlank)
    if (bubbles.isEmpty()) return ""

    val before = MigratedDomainStores.chat.messages(conversationId).value
    val allowedActionIds = actionableUserMessageIds
        ?: currentReplyTargetUserMessages(before).mapTo(mutableSetOf(), LuluChatMessage::id)
    val validQuoteId = presentation.quoteMessageId?.takeIf { id ->
        id in allowedActionIds && before.any { it.id == id && it.sender == LuluChatMessage.Sender.User }
    }
    val favoriteTarget = presentation.favoriteMessageId?.takeIf { it in allowedActionIds }?.let { id ->
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
    val lastCharacterBeforePending = beforeLoop.indexOfLast { it.sender == LuluChatMessage.Sender.Character }
    val initialPendingMessages = beforeLoop
        .drop(lastCharacterBeforePending + 1)
        .filter { message ->
            message.sender == LuluChatMessage.Sender.User ||
                (message.sender == LuluChatMessage.Sender.System && message.content.startsWith("[戳一戳]"))
        }
    val initialPendingIds = initialPendingMessages.mapTo(mutableSetOf(), LuluChatMessage::id)
    val initialPendingUserIds = initialPendingMessages
        .filter { it.sender == LuluChatMessage.Sender.User }
        .mapTo(mutableSetOf(), LuluChatMessage::id)
    val random = Random(System.nanoTime())
    val remaining = validMembers
        .filterNot { candidate -> mentioned.any { it.characterId == candidate.characterId } }
        .shuffled(random)
        .sortedBy { candidate -> candidate.characterId == lastSpeaker }
    val ordered = mentioned.shuffled(random) + remaining
    val replyLimit = group.maxAutoReplies
        .coerceAtLeast(validMembers.size)
        .coerceIn(validMembers.size, 8)
    // Only choose the first speaker here. The ensemble plan owns every later turn so outer queues
    // cannot accidentally recreate a fixed A→B→C order.
    val pendingSpeakers = mutableListOf(ordered.first())
    var index = 0

    while (index < replyLimit && pendingSpeakers.isNotEmpty()) {
        val member = pendingSpeakers.removeAt(0)
        if (!currentCoroutineContext().isActive) return
        onSpeakerChange(member.characterId)
        val character = MigratedDomainStores.characters.get(member.characterId)
        val memberLabel = member.groupNickname.ifBlank { character.displayName }
        val allLatestMessages = MigratedDomainStores.chat.messages(conversationId).value
        val memberVisibleMessages = allLatestMessages.filter { message ->
            DigitalLifeProfileStore.allowsTimestamp(member.characterId, message.createdAt)
        }
        val recalledLatest = recalledMessageIds(memberVisibleMessages)
        val latestMessages = memberVisibleMessages.filterNot { it.id in recalledLatest }
        val historySource = if (index == 0) {
            memberVisibleMessages.filterNot { it.id in initialPendingIds }
        } else {
            memberVisibleMessages
        }
        val history = buildBoundedHistory(
            messages = historySource,
            characterName = memberLabel,
            characterNames = characterNames,
        ).ifBlank { initialHistory.takeIf { !DigitalLifeProfileStore.isEnabled(member.characterId) }.orEmpty() }
        val memberList = group.members.joinToString("、") { candidate ->
            candidate.groupNickname.ifBlank { characterNames[candidate.characterId] ?: candidate.characterId }
        }
        val quotableUserMessages = latestMessages.filter { message ->
            message.sender == LuluChatMessage.Sender.User && message.id in initialPendingUserIds
        }
        val groupInput = buildString {
            appendLine("[这是群聊，不是私聊。群名：${group.name}；群成员：${group.userGroupNickname}、$memberList。]")
            appendLine("[当前由你（$memberLabel）发言。只代表你自己，严格遵循你的人设和关系边界；不要替别人说话，不要输出姓名标签。]")
            appendLine("[这一轮群聊所有角色成员都必须至少真正说一次，但绝不是固定轮班：全员参与是覆盖要求，不是顺序要求。可以 C→B→A，也可以 A→B→C→B→A；已经说过的人可以在别人之后自然回来继续说。]")
            appendLine("[不要按照成员列表顺序安排下一位。谁接话只看这一刻谁最自然想说；同时要保证这一整轮结束前所有成员至少出现一次。同一角色需要连续补充时可以用多个气泡，隔着别人再次发言时则可以再次成为后续发言者。]")
            if (quotableUserMessages.isNotEmpty()) {
                appendLine("[以下只列本轮用户在角色回复前连续发来的真实消息；数量跟随用户实际发送，不按固定条数截取。消息ID只用于这一轮引用或收藏：]")
                quotableUserMessages.forEach { item -> appendLine("[消息ID=${item.id} 内容=${qqForwardContextText(item.content).take(300)}]") }
                appendLine("[引用只能针对上面这一轮尚未被回复的用户消息。用户连续发多句时，可针对其中某一句单独回应；只有一条时就只能引用这一条。不要回头引用更早轮次已经回答完的旧消息。]")
                appendLine("[收藏也只能在这一轮回复发生时针对上面的用户消息做决定。如果其中某句话让你很在意、很喜欢、想以后记住或回看，可以收藏；一旦这一轮已经结束，后续新话题里不要再突然补收藏旧消息。]")
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
                appendLine("[下一位不要按固定名单轮转。根据真实群聊状态选择最自然的接话者并输出 ⟪NEXT:成员名⟫；可以选择之前已经发过言的人，只要整轮最终保证所有成员至少参与一次。全员已经覆盖且话题自然结束时才输出 ⟪END⟫。]")
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
                val validQuoteId = flow.quoteMessageId?.takeIf { quoteId -> quoteId in initialPendingUserIds }
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
                    actionableUserMessageIds = initialPendingUserIds,
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
    // Private chat callers only pass a display name, so infer the character when unambiguous. This
    // keeps old private messages from being reintroduced after a digital-life birth/reset.
    val inferredCharacterId = MigratedDomainStores.characters.settings.value.values
        .singleOrNull { it.displayName == characterName }
        ?.characterId
    val eligibleMessages = if (inferredCharacterId == null) {
        messages
    } else {
        messages.filter { message -> DigitalLifeProfileStore.allowsTimestamp(inferredCharacterId, message.createdAt) }
    }
    val recalledIds = recalledMessageIds(eligibleMessages)
    val normalized = eligibleMessages
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
            ?.let { replyId -> eligibleMessages.firstOrNull { it.id == replyId } }
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