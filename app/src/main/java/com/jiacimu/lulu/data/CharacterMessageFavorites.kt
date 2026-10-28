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

    // A real group message can be witnessed and saved by several characters.
    // Both the lexicon entry and each newly created timeline receipt must belong
    // to the individual, rather than competing for one app-global message ID.
    internal fun entryId(characterId: String, messageId: String): String =
        "message-$messageId:role:$characterId"

    internal fun hasFavorite(characterId: String, messageId: String): Boolean {
        val scopedId = entryId(characterId, messageId)
        val legacyId = "message-$messageId" // Existing entries remain readable after upgrade.
        return LuluRepositories.lexicon.snapshot(characterId).any {
            it.section == LexiconSection.Favorite && it.id in setOf(scopedId, legacyId)
        }
    }

    private fun witnessedUserMessage(characterId: String, message: LuluChatMessage): Boolean =
        characterId.isNotBlank() && message.sender == LuluChatMessage.Sender.User &&
            message.content.isNotBlank() &&
            SharedExperienceTimeline.eventsByIds(characterId,
                listOf(message.id, "${message.id}:group:$characterId")
            ).any { it.evidenceKind == EventEvidenceKind.UserStatement }

    /** A reset revokes in-flight favorite drafts belonging only to this person. */
    internal fun clearPending(characterId: String) = synchronized(lock) {
        pendingEntryIds.removeAll { it.endsWith(":role:$characterId") }
        acceptedAt.remove(characterId)
    }

    private fun receiptId(entryId: String): String =
        "lexicon-favorite-$entryId:${java.util.UUID.randomUUID()}"

    /** A model voluntarily selects the exact user quote and explains why.
     * Persistence and source validation are completed before success is returned.
     * This path reuses the existing lexicon entry; no duplicate personality store.
     */
    suspend fun favoriteExplicit(characterId: String, message: LuluChatMessage,
                                 reason: String): String? {
        if (reason.isBlank() || !witnessedUserMessage(characterId, message)) return null
        val id = entryId(characterId, message.id)
        if (hasFavorite(characterId, message.id)) return null
        val now = Instant.now()
        val character = MigratedDomainStores.characters.get(characterId)
        val title = "收藏 · ${message.createdAt.atZone(ZoneId.systemDefault()).format(titleTime)}"
        val content = message.content.trim().take(2_400) + "\n\n收藏理由：" + reason.take(180)
        LuluRepositories.lexicon.save(LexiconEntry(id = id, characterId = characterId,
            section = LexiconSection.Favorite, title = title, content = content,
            createdAt = now, updatedAt = now))
        // A re-favorite after explicitly deleting an old favorite is a NEW
        // experience, not a resurrection of the old tombstoned evidence ID.
        if (LuluRepositories.lexicon.snapshot(characterId).none {
                it.id == id && it.section == LexiconSection.Favorite }) return null
        val evidenceId = receiptId(id)
        SharedExperienceTimeline.record(eventId = evidenceId,
            characterId = characterId, channel = "收藏", speaker = character.displayName,
            content = "收藏了用户真实发出的消息：${message.content.take(600)}；原因：${reason.take(180)}",
            occurredAt = now, triggerExtraction = false, source = "favorite:own",
            evidenceKind = EventEvidenceKind.CharacterStatement)
        // Explicit autonomous/phone favorites previously never generated chat receipts.
        // The authoritative entry must exist before announcing the actual success.
        appendSavedFavoriteNotice(characterId, id, character.displayName)
        return evidenceId
    }

    fun favorite(
        characterId: String,
        conversationId: String,
        message: LuluChatMessage,
    ) {
        if (!witnessedUserMessage(characterId, message)) return
        val entryId = entryId(characterId, message.id)
        val actionNow = Instant.now()
        val accepted = synchronized(lock) {
            val existing = LuluRepositories.lexicon.snapshot(characterId)
                .filter { it.section == LexiconSection.Favorite }
            if (existing.any { it.id == entryId || it.id == "message-${message.id}" } ||
                entryId in pendingEntryIds) {
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
                // Another character's conversation, a deleted source, or a
                // reset while the model was working must never create a
                // personal archive item after the fact.
                val stillPending = synchronized(lock) { entryId in pendingEntryIds }
                if (!stillPending || !witnessedUserMessage(characterId, message) ||
                    !DigitalLifeProfileStore.allowsTimestamp(characterId, actionNow) ||
                    hasFavorite(characterId, message.id)) return@launch
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
                if (LuluRepositories.lexicon.snapshot(characterId).none {
                        it.id == entryId && it.section == LexiconSection.Favorite }) return@launch
                SharedExperienceTimeline.record(
                    eventId = receiptId(entryId),
                    characterId = characterId,
                    channel = "收藏",
                    speaker = character.displayName,
                    content = "收藏了用户在聊天中的一条消息：$messageText\n收藏理由：$reason",
                    occurredAt = actionNow,
                )
                appendSavedFavoriteNotice(characterId, entryId, character.displayName)
            } finally {
                synchronized(lock) { pendingEntryIds -= entryId }
            }
        }
    }

    /** Success-only, compact private receipt for ALL real character favorite routes. */
    private fun appendSavedFavoriteNotice(characterId: String, entryId: String, characterName: String) {
        if (LuluRepositories.lexicon.snapshot(characterId).none {
                it.id == entryId && it.section == LexiconSection.Favorite }) return
        MigratedDomainStores.chat.appendPrivateActivityNotice(
            characterId,
            "[收藏记录|$entryId] $characterName 收藏了一条消息 · 查看收藏",
        )
    }

    private suspend fun generateFavoriteReason(
        character: CharacterSettings,
        conversationId: String,
        messageText: String,
    ): String {
        val conversation = MigratedDomainStores.chat.conversations.value.firstOrNull { it.id == conversationId }
        val scene = if (conversation?.groupChat == null) {
            "与用户的一对一聊天"
        } else {
            "群聊《${conversation.groupChat?.name.orEmpty().ifBlank { conversation.title }}》"
        }
        val request = UnifiedMemoryRequest(
            currentInput = messageText,
            sceneContext = scene,
            taskIntent = "说明自己为什么真心想把用户这句话收藏下来",
        )
        val generated = LuluAiServices.gateway.generate(
            characterId = character.characterId,
            facts = buildString {
                appendLine("当前角色：${character.displayName}")
                appendLine("发生场景：$scene")
                appendLine("用户原话：$messageText")
            },
            instruction = """
                你就是${character.displayName}。这句话已经让你产生了强烈到足以“收藏”的个人动机。
                只输出一句第一人称收藏理由，不要标题、引号、JSON、解释或系统口吻。
                理由要具体落在这句话为什么对你有意义：可以是喜欢、在意、被触动、想记住某个约定或细节、以后想回来重看。
                不要泛泛写“很重要”“值得收藏”，不要替用户编造未说过的想法，也不要为了煽情夸大关系。
                长度通常 12—45 个中文字符，服从角色自己的说话与思考方式。
            """.trimIndent(),
            source = "角色收藏理由",
            title = "${character.displayName}的收藏理由",
            maxTokens = 120,
            usage = ModelUsage.Chat,
            contextMode = CompanionContextMode.CharacterAndScenario,
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
