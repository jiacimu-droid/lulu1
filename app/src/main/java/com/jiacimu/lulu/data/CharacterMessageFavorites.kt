package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Character-owned message favorites.
 *
 * A favorite is a deliberately rare, character-owned action rather than a lightweight "like".
 * The durable lexicon entry keeps both the exact user message and the character's reason for
 * wanting to keep it. A short per-character quiet window prevents model enthusiasm from turning
 * favorites into a mechanical action while still leaving the decision itself personality-driven.
 */
object CharacterMessageFavorites {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val titleTime = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val lock = Any()
    private val pendingEntryIds = mutableSetOf<String>()
    private val acceptedAt = mutableMapOf<String, Instant>()
    private val favoriteQuietWindow: Duration = Duration.ofMinutes(20)

    fun favorite(
        characterId: String,
        conversationId: String,
        message: LuluChatMessage,
    ) {
        if (characterId.isBlank() || message.sender != LuluChatMessage.Sender.User || message.content.isBlank()) return
        val entryId = "message-${message.id}"
        val actionNow = Instant.now()
        val accepted = synchronized(lock) {
            val existing = LuluRepositories.lexicon.snapshot(characterId)
                .filter { it.section == LexiconSection.Favorite }
            if (existing.any { it.id == entryId } || entryId in pendingEntryIds) {
                false
            } else {
                val latestPersistedAt = existing.maxByOrNull(LexiconEntry::updatedAt)?.updatedAt
                val latestAcceptedAt = acceptedAt[characterId]
                val latestAt = listOfNotNull(latestPersistedAt, latestAcceptedAt).maxOrNull()
                if (latestAt != null && Duration.between(latestAt, actionNow) < favoriteQuietWindow) {
                    false
                } else {
                    pendingEntryIds += entryId
                    acceptedAt[characterId] = actionNow
                    true
                }
            }
        }
        if (!accepted) return

        val character = MigratedDomainStores.characters.get(characterId)
        val sourceTime = message.createdAt
        val title = "收藏 · ${sourceTime.atZone(ZoneId.systemDefault()).format(titleTime)}"
        val messageText = message.content.trim().take(2_400)

        scope.launch {
            try {
                val reason = generateFavoriteReason(character, conversationId, messageText)
                val storedContent = buildString {
                    append(messageText)
                    append("\n\n收藏理由：")
                    append(reason)
                }
                LuluRepositories.lexicon.save(
                    LexiconEntry(
                        id = entryId,
                        characterId = characterId,
                        section = LexiconSection.Favorite,
                        title = title,
                        content = storedContent,
                        createdAt = actionNow,
                        updatedAt = actionNow,
                    ),
                )
                SharedExperienceTimeline.record(
                    eventId = "lexicon-favorite-$entryId",
                    characterId = characterId,
                    channel = "收藏",
                    speaker = character.displayName,
                    content = "收藏了主人在聊天中的一条消息：$messageText\n收藏理由：$reason",
                    occurredAt = actionNow,
                )
                val conversation = MigratedDomainStores.chat.conversations.value.firstOrNull { it.id == conversationId }
                if (conversation?.groupChat == null) {
                    MigratedDomainStores.chat.appendSystemMessage(conversationId, "[共同活动] 收藏了一条消息。")
                } else {
                    MigratedDomainStores.chat.appendPrivateActivityNotice(characterId, "收藏了一条消息。")
                }
            } finally {
                synchronized(lock) { pendingEntryIds -= entryId }
            }
        }
    }

    private suspend fun generateFavoriteReason(
        character: CharacterSettings,
        conversationId: String,
        messageText: String,
    ): String {
        val conversation = MigratedDomainStores.chat.conversations.value.firstOrNull { it.id == conversationId }
        val scene = if (conversation?.groupChat == null) {
            "与主人的一对一聊天"
        } else {
            "群聊《${conversation.groupChat?.name.orEmpty().ifBlank { conversation.title }}》"
        }
        val request = UnifiedMemoryRequest(
            currentInput = messageText,
            sceneContext = scene,
            taskIntent = "说明自己为什么真心想把主人这句话收藏下来",
        )
        val generated = LuluAiServices.gateway.generate(
            characterId = character.characterId,
            facts = buildString {
                appendLine("当前角色：${character.displayName}")
                character.persona.takeIf(String::isNotBlank)?.let { appendLine("人设：${it.take(900)}") }
                appendLine("发生场景：$scene")
                appendLine("主人原话：$messageText")
            },
            instruction = """
                你就是${character.displayName}。这句话已经让你产生了强烈到足以“收藏”的个人动机。
                只输出一句第一人称收藏理由，不要标题、引号、JSON、解释或系统口吻。
                理由要具体落在这句话为什么对你有意义：可以是喜欢、在意、被触动、想记住某个约定或细节、以后想回来重看。
                不要泛泛写“很重要”“值得收藏”，不要替主人编造未说过的想法，也不要为了煽情夸大关系。
                长度通常 12—45 个中文字符，服从角色自己的说话与思考方式。
            """.trimIndent(),
            source = "角色收藏理由",
            title = "${character.displayName}的收藏理由",
            temperature = 0.72,
            maxTokens = 120,
            usage = ModelUsage.Chat,
            contextMode = CompanionContextMode.PersonaAndScenario,
            memoryRequest = request,
        ).getOrNull()?.text.orEmpty()
        return generated
            .trim()
            .removePrefix("收藏理由：")
            .removeSurrounding("\"")
            .removeSurrounding("“", "”")
            .lineSequence()
            .firstOrNull(String::isNotBlank)
            ?.trim()
            ?.take(180)
            .orEmpty()
            .ifBlank { "我是真的想把你这句话留下来，以后还能回来看看。" }
    }
}
