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
    // Keep action markers only until the reply presentation layer converts them into structured effects.
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

/** A cached group speaker still waits a little before the first bubble so the typing state is visible. */
internal fun roleTypingLeadDelayMillis(characterId: String): Long =
    420L + (rolePacingSeed(characterId) % 680L)

private fun roleBubbleDelayMillis(characterId: String, bubble: String, bubbleIndex: Int): Long {
    val personalityBeat = 260L + (rolePacingSeed(characterId) % 360L)
    val contentBeat = (bubble.length * 9L).coerceIn(60L, 330L)
    return (personalityBeat + contentBeat + bubbleIndex * 35L).coerceIn(320L, 1_050L)
}

private fun roleRecallDelayMillis(characterId: String): Long =
    520L + (rolePacingSeed(characterId) % 360L)

/**
 * Reveals already-generated bubbles one by one. This never calls the model; it only controls local
 * message timing, optional recall, role-favorite, and poke receipts.
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
        }
        if (index < bubbles.lastIndex) {
            delay(roleBubbleDelayMillis(characterId, bubble, index))
        }
    }

    favoriteTarget?.let { target ->
        CharacterMessageFavorites.favorite(characterId, conversationId, target)
    }
    if (presentation.pokeUser && currentCoroutineContext().isActive) {
        delay(360L + rolePacingSeed(characterId) % 300L)
        MigratedDomainStores.chat.appendSystemMessage(conversationId, "[戳一戳] $characterLabel 戳了戳你。")
    }
    return bubbles.joinToString("\n")
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
    val lastSpeaker = MigratedDomainStores.chat.messages(conversationId).value
        .lastOrNull { it.sender == LuluChatMessage.Sender.Character }
        ?.authorCharacterId
    val remaining = validMembers
        .filterNot { candidate -> mentioned.any { it.characterId == candidate.characterId } }
        .let { members ->
            if (members.size > 1 && members.firstOrNull()?.characterId == lastSpeaker) members.drop(1) + members.first()
            else members
        }
    val ordered = mentioned + remaining
    val explicitAll = pendingText.contains("@全体成员")
    val replyLimit = when {
        group.allowCharacterConversation -> group.maxAutoReplies.coerceAtLeast(validMembers.size)
        explicitAll -> validMembers.size.coerceAtMost(group.maxAutoReplies.coerceAtLeast(validMembers.size))
        mentioned.isNotEmpty() -> mentioned.size.coerceAtMost(group.maxAutoReplies)
        else -> 1
    }.coerceIn(1, 8)
    val pendingSpeakers = when {
        explicitAll -> ordered.toMutableList()
        mentioned.isNotEmpty() -> mentioned.toMutableList()
        else -> mutableListOf(ordered.first())
    }
    var index = 0

    while (index < replyLimit && pendingSpeakers.isNotEmpty()) {
        val member = pendingSpeakers.removeAt(0)
        if (!currentCoroutineContext().isActive) return
        onSpeakerChange(member.characterId)
        if (index > 0) delay(roleTypingLeadDelayMillis(member.characterId))
        val character = MigratedDomainStores.characters.get(member.characterId)
        val memberLabel = member.groupNickname.ifBlank { character.displayName }
        val latestMessages = MigratedDomainStores.chat.messages(conversationId).value
        val history = if (index == 0) initialHistory else buildBoundedHistory(
            messages = latestMessages,
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
            if (quotableUserMessages.isNotEmpty()) {
                appendLine("[以下是近期真实的用户气泡；消息ID只用于引用或收藏动作，不属于聊天正文：]")
                quotableUserMessages.forEach { item -> appendLine("[消息ID=${item.id} 内容=${qqForwardContextText(item.content).take(300)}]") }
                appendLine("[需要针对用户某一句单独回应时，可在整段回复最前输出 ⟪QUOTE:消息ID⟫；不要为了展示功能而每次引用。]")
                appendLine("[如果你本人真的很想把用户某一句留下来以后再看，可以在整段回复最前额外输出 ⟪FAVORITE:消息ID⟫。收藏是角色自己的选择，不是用户收藏，也不要滥用；只能使用上面真实存在的用户消息ID。引用和收藏可以各自出现，也可以都不出现。]")
            }
            if (index > 0) {
                val previousMessage = latestMessages.lastOrNull { it.sender == LuluChatMessage.Sender.Character }
                val previousName = previousMessage?.authorCharacterId?.let { id ->
                    group.members.firstOrNull { it.characterId == id }?.groupNickname
                        ?.ifBlank { characterNames[id].orEmpty() }
                        ?.ifBlank { "上一位角色" }
                } ?: "上一位角色"
                appendLine("[$previousName 刚刚说：${previousMessage?.content?.takeLast(900).orEmpty()}]")
                appendLine("[你这次主要回应 $previousName，而不是重新回答用户。可以赞同、质疑、追问、开玩笑或补充；如果已经自然说完，也可以让话题停在这里。]")
            }
            appendLine("[这是即时通讯软件里的线上聊天。请按你自己的语气、停顿、情绪变化、补充、转折、追问和聊天习惯决定什么时候按一次发送。现实聊天中会在这里按发送，就在这里结束当前气泡。]")
            appendLine("[需要多个气泡时，只在两个气泡之间输出 $SemanticBubbleSeparator；不按标点、固定字数或固定数量机械切分，也不要为了减少气泡把本来会分开发送的话硬塞成长段。]")
            appendLine("[只有非常少见、很符合当下人设的情况下，例如刚说出口就觉得说漏嘴、说重了或突然后悔，才可以在回复末尾输出 ⟪RECALL:n⟫，n 是本次第 n 个气泡（从1开始）。不要为了显得像真人而频繁撤回。]")
            appendLine("[如果你此刻真的会自然地戳一下用户，可以在回复末尾输出 ⟪POKE_USER⟫；尤其用户刚戳过你时可以考虑戳回来，但不要滥用。]")
            if (group.allowCharacterConversation) {
                appendLine("[根据此刻的内容判断群聊是否还会自然继续：若某位成员会接话，在末尾输出 ⟪NEXT:成员名⟫；全员都至少参与一次以后，若已经自然结束，输出 ⟪END⟫。不要按固定顺序轮流，也不要为了让某个人重复发言而硬续。该标记不会显示给用户。]")
            }
            if (index == 0) append("用户刚在群里说：$pendingText")
            else append("用户最初开启的话题：$pendingText")
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
    val normalized = messages
        .filter { message ->
            message.sender != LuluChatMessage.Sender.System ||
                message.content.startsWith("[群成员变更]") ||
                message.content.startsWith("[戳一戳]") ||
                message.content.startsWith("[撤回]")
        }
        .fold(mutableListOf<LuluChatMessage>()) { result, message ->
            val previous = result.lastOrNull()
            val duplicate = previous != null && previous.sender == message.sender && previous.content.trim() == message.content.trim()
            if (!duplicate) result += message
            result
        }
        .takeLast(maxMessages)
    val lines = normalized.map { message ->
        val role = when (message.sender) {
            LuluChatMessage.Sender.User -> UserProfileContext.displayLabel()
            LuluChatMessage.Sender.System -> "群聊系统"
            LuluChatMessage.Sender.Character -> message.authorCharacterId?.let { characterNames[it] } ?: characterName
        }
        val quoteContext = message.replyToMessageId
            ?.let { replyId -> messages.firstOrNull { it.id == replyId } }
            ?.let { original -> "（引用：${qqForwardContextText(original.content).take(180)}）" }
            .orEmpty()
        "$role$quoteContext：${qqForwardContextText(message.content)}"
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
