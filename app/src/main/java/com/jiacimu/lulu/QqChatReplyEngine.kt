package com.jiacimu.lulu

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

internal fun characterReplyQuoteId(text: String): String? = QuoteDirectiveRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
internal fun characterFavoriteMessageId(text: String): String? = FavoriteDirectiveRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
internal fun characterRecallBubbleNumber(text: String): Int? = RecallDirectiveRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
internal fun characterPokesUser(text: String): Boolean = PokeUserDirectiveRegex.containsMatchIn(text)

internal fun stripCharacterReplyDirective(text: String): String = stripQqForwardDirective(
    text.replace(QuoteDirectiveRegex, "")
        .replace(FavoriteDirectiveRegex, "")
        .replace(RecallDirectiveRegex, "")
        .replace(PokeUserDirectiveRegex, ""),
).trim()

internal fun normalizeSemanticBubbles(text: String): String {
    val raw = text.replace("\r\n", "\n").trim()
    if (raw.isBlank()) return ""
    val directives = listOf(QuoteDirectiveRegex, FavoriteDirectiveRegex, RecallDirectiveRegex, PokeUserDirectiveRegex)
        .joinToString("") { it.find(raw)?.value.orEmpty() }
    val body = stripCharacterReplyDirective(raw).split(SemanticBubbleSeparator)
        .map { it.trim().trim('"') }.filter(String::isNotBlank).joinToString("\n")
    return if (body.isBlank()) "" else directives + body
}

internal data class CharacterReplyPresentation(
    val content: String,
    val quoteMessageId: String? = null,
    val favoriteMessageId: String? = null,
    val recallBubbleNumber: Int? = null,
    val pokeUser: Boolean = false,
)

internal fun parseCharacterReplyPresentation(text: String): CharacterReplyPresentation = CharacterReplyPresentation(
    content = stripCharacterReplyDirective(normalizeSemanticBubbles(text)),
    quoteMessageId = characterReplyQuoteId(text),
    favoriteMessageId = characterFavoriteMessageId(text),
    recallBubbleNumber = characterRecallBubbleNumber(text),
    pokeUser = characterPokesUser(text),
)

internal fun currentReplyTargetUserMessages(messages: List<LuluChatMessage>): List<LuluChatMessage> {
    val lastCharacterIndex = messages.indexOfLast { it.sender == LuluChatMessage.Sender.Character }
    return messages.drop(lastCharacterIndex + 1).filter { it.sender == LuluChatMessage.Sender.User }
}

private fun rolePacingSeed(characterId: String): Long = abs(characterId.hashCode().toLong())
internal fun roleTypingLeadDelayMillis(characterId: String): Long = 900L + rolePacingSeed(characterId) % 1_100L
private fun roleBubbleDelayMillis(characterId: String, bubble: String, index: Int): Long =
    (620L + rolePacingSeed(characterId) % 520L + (bubble.length * 18L).coerceIn(180L, 1_050L) + index * 90L).coerceIn(850L, 2_500L)
private fun roleRecallDelayMillis(characterId: String): Long = 900L + rolePacingSeed(characterId) % 650L

internal suspend fun appendRoleReplyWithPacing(
    conversationId: String,
    characterId: String,
    characterLabel: String,
    presentation: CharacterReplyPresentation,
    actionableUserMessageIds: Set<String>? = null,
): String {
    val bubbles = presentation.content.replace("\r\n", "\n").split(Regex("\n+"))
        .map(String::trim).filter(String::isNotBlank)
    if (bubbles.isEmpty()) return ""
    val before = MigratedDomainStores.chat.messages(conversationId).value
    val allowed = actionableUserMessageIds ?: currentReplyTargetUserMessages(before).mapTo(mutableSetOf(), LuluChatMessage::id)
    val quoteId = presentation.quoteMessageId?.takeIf { id -> id in allowed && before.any { it.id == id && it.sender == LuluChatMessage.Sender.User } }
    val favoriteTarget = presentation.favoriteMessageId?.takeIf { it in allowed }?.let { id -> before.firstOrNull { it.id == id && it.sender == LuluChatMessage.Sender.User } }
    val spoken = mutableListOf<LuluChatMessage>()
    delay(roleTypingLeadDelayMillis(characterId))
    bubbles.forEachIndexed { index, bubble ->
        if (!currentCoroutineContext().isActive) return@forEachIndexed
        val created = MigratedDomainStores.chat.appendCharacterMessage(conversationId, bubble, characterId, quoteId.takeIf { index == 0 })
        if (presentation.recallBubbleNumber == index + 1) {
            delay(roleRecallDelayMillis(characterId))
            if (currentCoroutineContext().isActive) MigratedDomainStores.chat.retractCharacterMessage(created.id, characterLabel)
        } else spoken += created
        if (index < bubbles.lastIndex) delay(roleBubbleDelayMillis(characterId, bubble, index))
    }
    favoriteTarget?.let { CharacterMessageFavorites.favorite(characterId, conversationId, it) }
    if (presentation.pokeUser && currentCoroutineContext().isActive) {
        delay(700L + rolePacingSeed(characterId) % 500L)
        MigratedDomainStores.chat.appendSystemMessage(conversationId, "[戳一戳] $characterLabel 戳了戳你。")
    }
    spoken.forEach { ChatAutoVoicePlayback.enqueue(characterId, it.id, it.content) }
    return spoken.joinToString("\n", transform = LuluChatMessage::content)
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
    val next = Regex("⟪NEXT\\s*:\\s*([^⟫]+)⟫", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
    val actionText = text.replace(Regex("⟪NEXT\\s*:\\s*[^⟫]+⟫", RegexOption.IGNORE_CASE), "")
        .replace(Regex("⟪END⟫", RegexOption.IGNORE_CASE), "").trim()
    val p = parseCharacterReplyPresentation(actionText)
    return GroupReplyFlow(p.content, next, text.contains("⟪END⟫", true), p.quoteMessageId, p.favoriteMessageId, p.recallBubbleNumber, p.pokeUser)
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
    val members = group.members.filter { it.characterId in characterNames }
    if (members.size < 2) return onError("群聊至少需要两个仍然存在的角色")
    val mentioned = members.filter { m -> m.groupNickname.ifBlank { characterNames[m.characterId].orEmpty() }.let { it.isNotBlank() && pendingText.contains("@$it", true) } }
    val before = MigratedDomainStores.chat.messages(conversationId).value
    val recalled = recalledMessageIds(before)
    val lastSpeaker = before.asSequence().filterNot { it.id in recalled }.lastOrNull { it.sender == LuluChatMessage.Sender.Character }?.authorCharacterId
    val lastCharacter = before.indexOfLast { it.sender == LuluChatMessage.Sender.Character }
    val pendingMessages = before.drop(lastCharacter + 1).filter { it.sender == LuluChatMessage.Sender.User || (it.sender == LuluChatMessage.Sender.System && it.content.startsWith("[戳一戳]")) }
    val pendingIds = pendingMessages.mapTo(mutableSetOf(), LuluChatMessage::id)
    val pendingUserIds = pendingMessages.filter { it.sender == LuluChatMessage.Sender.User }.mapTo(mutableSetOf(), LuluChatMessage::id)
    val random = Random(System.nanoTime())
    val remaining = members.filterNot { c -> mentioned.any { it.characterId == c.characterId } }.shuffled(random).sortedBy { it.characterId == lastSpeaker }
    val first = (mentioned.shuffled(random) + remaining).first()
    val queue = mutableListOf(first)
    val replyLimit = group.maxAutoReplies.coerceIn(1, 8)
    var index = 0

    while (index < replyLimit && queue.isNotEmpty()) {
        val member = queue.removeAt(0)
        if (!currentCoroutineContext().isActive) return
        onSpeakerChange(member.characterId)
        val character = MigratedDomainStores.characters.get(member.characterId)
        val label = member.groupNickname.ifBlank { character.displayName }
        val visibleAll = MigratedDomainStores.chat.messages(conversationId).value.filter { DigitalLifeProfileStore.allowsTimestamp(member.characterId, it.createdAt) }
        val recalledNow = recalledMessageIds(visibleAll)
        val latest = visibleAll.filterNot { it.id in recalledNow }
        val historySource = if (index == 0) visibleAll.filterNot { it.id in pendingIds } else visibleAll
        val history = buildBoundedHistory(historySource, label, characterNames)
            .ifBlank { initialHistory.takeIf { !DigitalLifeProfileStore.isEnabled(member.characterId) }.orEmpty() }
        val memberList = members.joinToString("、") { it.groupNickname.ifBlank { characterNames[it.characterId] ?: it.characterId } }
        val quotable = latest.filter { it.sender == LuluChatMessage.Sender.User && it.id in pendingUserIds }
        val prompt = buildString {
            appendLine("[这是QQ式群聊《${group.name}》，成员：${group.userGroupNickname}、$memberList。当前由你（$label）看到共享消息，只代表你自己。]")
            appendLine("[在线/看见消息≠必须说话。被@、话题与你有关、你真的有话想接时才参与；别人已说清、你没兴趣或没必要时可以继续潜水。绝不为了凑全员而轮班。]")
            appendLine("[群聊与私聊不同：这是所有成员共享的上下文，不要把别人的私聊内容带进来，也不要把对用户的一对一亲密话当成群内默认公开信息。]")
            if (quotable.isNotEmpty()) {
                appendLine("[本轮可引用/收藏的用户消息：]")
                quotable.forEach { appendLine("[消息ID=${it.id} 内容=${qqForwardContextText(it.content).take(300)}]") }
                appendLine("[收藏是低频强意图，不是点赞；只有真的想长期留住并以后重看时才 ⟪FAVORITE:消息ID⟫。引用只用真实ID。]")
            }
            if (index > 0) latest.lastOrNull { it.sender == LuluChatMessage.Sender.Character }?.let {
                val prev = it.authorCharacterId?.let(characterNames::get).orEmpty().ifBlank { "上一位角色" }
                appendLine("[$prev 刚说：${it.content.takeLast(900)}。从这一刻接，不要重启话题。]")
            }
            appendLine("[像真人即时通讯：一个表达动作一个气泡；需要分开发送时用 $SemanticBubbleSeparator。撤回 ⟪RECALL:n⟫ 和戳用户 ⟪POKE_USER⟫ 都只能偶尔自然发生。]")
            if (group.allowCharacterConversation) appendLine("[说完后只有当某位具体成员真的会自然接话才输出 ⟪NEXT:成员名⟫；否则输出 ⟪END⟫。不要为了让更多人出现而NEXT。]")
            append(if (index == 0) "用户刚在群里说：$pendingText" else "这轮最初由用户说：$pendingText")
        }
        var result = LuluDeviceToolBridge.respond(member.characterId, history, prompt, activeLabel, archiveId, sceneContext)
        if (result.getOrNull()?.text.isNullOrBlank() && currentCoroutineContext().isActive) {
            result = LuluDeviceToolBridge.respond(member.characterId, history, "$prompt\n[如果你确实该接话，直接自然发言；不要替别人说话。]", activeLabel, archiveId, sceneContext)
        }
        if (!currentCoroutineContext().isActive) return
        val reply = result.getOrNull()
        if (reply == null) {
            onError("${character.displayName}回复失败：${result.exceptionOrNull()?.message ?: "未知错误"}")
        } else {
            val flow = parseGroupReplyFlow(reply.text)
            if (flow.content.isNotBlank()) {
                val shown = appendRoleReplyWithPacing(
                    conversationId, member.characterId, label,
                    CharacterReplyPresentation(flow.content, flow.quoteMessageId?.takeIf { it in pendingUserIds }, flow.favoriteMessageId, flow.recallBubbleNumber, flow.pokeUser),
                    pendingUserIds,
                )
                if (shown.isNotBlank()) afterReply(member.characterId, shown)
            }
            if (group.allowCharacterConversation && !flow.shouldEnd && index + 1 < replyLimit) {
                flow.nextSpeakerName?.let { requested ->
                    members.firstOrNull { candidate ->
                        candidate.characterId != member.characterId && listOf(candidate.groupNickname, characterNames[candidate.characterId].orEmpty())
                            .filter(String::isNotBlank).any { requested.equals(it, true) }
                    }
                }?.let { next -> queue.removeAll { it.characterId == next.characterId }; queue.add(0, next) }
            }
        }
        index++
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
    val inferredId = MigratedDomainStores.characters.settings.value.values.singleOrNull { it.displayName == characterName }?.characterId
    val eligible = inferredId?.let { id -> messages.filter { DigitalLifeProfileStore.allowsTimestamp(id, it.createdAt) } } ?: messages
    val recalled = recalledMessageIds(eligible)
    val lines = eligible.filterNot { it.id in recalled }
        .filter { it.sender != LuluChatMessage.Sender.System || it.content.startsWith("[群成员变更]") || it.content.startsWith("[戳一戳]") || it.content.startsWith("[撤回|") }
        .takeLast(maxMessages).map { message ->
            val role = when {
                message.sender == LuluChatMessage.Sender.System -> "聊天系统"
                message.sender == LuluChatMessage.Sender.User -> UserProfileContext.displayLabel()
                else -> message.authorCharacterId?.let(characterNames::get) ?: characterName
            }
            val quote = message.replyToMessageId?.let { id -> eligible.firstOrNull { it.id == id } }
                ?.let { "（引用：${qqForwardContextText(it.content).take(180)}）" }.orEmpty()
            val content = if (message.sender == LuluChatMessage.Sender.System) stripRecallReceiptDirective(message.content) else qqForwardContextText(message.content)
            "$role$quote：$content"
        }
    val selected = ArrayDeque<String>()
    var chars = 0
    for (line in lines.asReversed()) {
        if (selected.isNotEmpty() && chars + line.length > maxChars) break
        selected.addFirst(line); chars += line.length
    }
    return selected.joinToString("\n")
}
