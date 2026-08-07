package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Character-owned message favorites.
 *
 * A favorite is not just a visual star on the chat message. It is a durable character lexicon
 * entry plus a raw timeline event. The chat receipt is deliberately a system notice so it can be
 * filtered from later memory extraction without losing the underlying favorite event.
 */
object CharacterMessageFavorites {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val titleTime = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    fun favorite(
        characterId: String,
        conversationId: String,
        message: LuluChatMessage,
    ) {
        if (characterId.isBlank() || message.sender != LuluChatMessage.Sender.User || message.content.isBlank()) return
        val entryId = "message-${message.id}"
        if (LuluRepositories.lexicon.snapshot(characterId).any { it.id == entryId && it.section == LexiconSection.Favorite }) return
        val character = MigratedDomainStores.characters.get(characterId)
        val occurredAt = message.createdAt
        val title = "收藏 · ${occurredAt.atZone(ZoneId.systemDefault()).format(titleTime)}"
        val content = message.content.trim().take(2_400)

        scope.launch {
            LuluRepositories.lexicon.save(
                LexiconEntry(
                    id = entryId,
                    characterId = characterId,
                    section = LexiconSection.Favorite,
                    title = title,
                    content = content,
                    createdAt = occurredAt,
                    updatedAt = occurredAt,
                ),
            )
            SharedExperienceTimeline.record(
                eventId = "lexicon-favorite-$entryId",
                characterId = characterId,
                channel = "收藏",
                speaker = character.displayName,
                content = "收藏了主人在聊天中的一条消息：$content",
                occurredAt = java.time.Instant.now(),
            )
            val conversation = MigratedDomainStores.chat.conversations.value.firstOrNull { it.id == conversationId }
            if (conversation?.groupChat == null) {
                MigratedDomainStores.chat.appendSystemMessage(conversationId, "[共同活动] 刚刚收藏了一条消息。")
            } else {
                MigratedDomainStores.chat.appendPrivateActivityNotice(characterId, "刚刚收藏了一条消息。")
            }
        }
    }
}
