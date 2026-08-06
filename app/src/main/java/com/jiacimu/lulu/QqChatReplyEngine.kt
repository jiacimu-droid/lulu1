package com.jiacimu.lulu

import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluGroupChat
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.UserProfileContext
import com.jiacimu.lulu.system.LuluDeviceToolBridge
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

internal const val SemanticBubbleSeparator = "⟪BUBBLE⟫"

internal fun normalizeSemanticBubbles(text: String): String {
    val normalized = text.replace("\r\n", "\n").trim()
    if (normalized.isBlank()) return ""
    val semanticBubbles = normalized
        .split(SemanticBubbleSeparator)
        .map { bubble -> bubble.trim().trim('"') }
        .filter(String::isNotBlank)
    return semanticBubbles.joinToString("\n")
}

private data class GroupReplyFlow(
    val content: String,
    val nextSpeakerName: String?,
    val shouldEnd: Boolean,
)

private fun parseGroupReplyFlow(text: String): GroupReplyFlow {
    val nextMatch = Regex("⟪NEXT\\s*:\\s*([^⟫]+)⟫", RegexOption.IGNORE_CASE).find(text)
    val visible = text
        .replace(Regex("⟪NEXT\\s*:\\s*[^⟫]+⟫", RegexOption.IGNORE_CASE), "")
        .replace(Regex("⟪END⟫", RegexOption.IGNORE_CASE), "")
        .trim()
    return GroupReplyFlow(
        content = normalizeSemanticBubbles(visible),
        nextSpeakerName = nextMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank),
        shouldEnd = text.contains("⟪END⟫", ignoreCase = true),
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
        group.allowCharacterConversation -> group.maxAutoReplies
        explicitAll -> validMembers.size.coerceAtMost(group.maxAutoReplies)
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
        val groupInput = buildString {
            appendLine("[这是群聊，不是私聊。群名：${group.name}；群成员：${group.userGroupNickname}、$memberList。]")
            appendLine("[当前由你（$memberLabel）发言。只代表你自己，严格遵循你的人设和关系边界；不要替别人说话，不要输出姓名标签。]")
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
            appendLine("[请按真实聊天的表达节奏决定发几个气泡，不按字数或标点机械切分。一个完整的动作、情绪、观点或紧密相连的句子应留在同一气泡；只有话题转折、独立的反应/追问、或有意停顿时才另开气泡。]")
            appendLine("[需要分气泡时，只在两个气泡之间输出 $SemanticBubbleSeparator；一个气泡时不要输出标记。不要为了凑数量拆句，也不要输出姓名标签或其他格式说明。]")
            if (group.allowCharacterConversation) {
                appendLine("[根据此刻的内容判断群聊是否还会自然继续：若某位成员会接话，在末尾输出 ⟪NEXT:成员名⟫；若已经自然结束，输出 ⟪END⟫。不要按固定顺序轮流，也不要为了让每个人都说话而硬续。该标记不会显示给用户。]")
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
                userText = "$groupInput\n[上一次没有生成有效发言。现在必须直接接住上一位成员的话，输出至少一个自然完整的表达。]",
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
                MigratedDomainStores.chat.appendCharacterMessage(
                    conversationId = conversationId,
                    content = semanticReply,
                    authorCharacterId = member.characterId,
                )
                afterReply(member.characterId, semanticReply)
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
            message.sender != LuluChatMessage.Sender.System || message.content.startsWith("[群成员变更]")
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
        "$role：${message.content.trim()}"
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
