package com.jiacimu.lulu.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

data class LuluChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val sender: Sender,
    val content: String,
    val createdAt: Instant = Instant.now(),
    val status: Status = Status.Sent,
) {
    enum class Sender { User, Character, System }
    enum class Status { Sending, Sent, Failed }
}

data class LuluConversation(
    val id: String,
    val characterId: String,
    val title: String,
    val lastMessage: String = "",
    val updatedAt: Instant = Instant.now(),
    val unreadCount: Int = 0,
)

interface LuluChatStore {
    val conversations: StateFlow<List<LuluConversation>>
    fun messages(conversationId: String): StateFlow<List<LuluChatMessage>>
    fun sendUserMessage(conversationId: String, content: String): LuluChatMessage
    fun appendCharacterMessage(conversationId: String, content: String): LuluChatMessage
    fun markFailed(messageId: String)
    fun markConversationRead(conversationId: String)
}

class InMemoryLuluChatStore : LuluChatStore {
    private val conversationState = MutableStateFlow(
        listOf(
            LuluConversation(
                id = "lulu-main",
                characterId = "lulu",
                title = "露露",
                lastMessage = "今天也会一直陪着主人呀～",
            ),
        ),
    )
    private val messageStates = mutableMapOf<String, MutableStateFlow<List<LuluChatMessage>>>()

    override val conversations: StateFlow<List<LuluConversation>> = conversationState.asStateFlow()

    override fun messages(conversationId: String): StateFlow<List<LuluChatMessage>> =
        messageStates.getOrPut(conversationId) {
            MutableStateFlow(
                listOf(
                    LuluChatMessage(
                        conversationId = conversationId,
                        sender = LuluChatMessage.Sender.Character,
                        content = "主人，今天学习辛苦啦。",
                    ),
                ),
            )
        }.asStateFlow()

    override fun sendUserMessage(conversationId: String, content: String): LuluChatMessage {
        val clean = content.trim()
        require(clean.isNotEmpty()) { "Message content cannot be blank" }
        val message = LuluChatMessage(
            conversationId = conversationId,
            sender = LuluChatMessage.Sender.User,
            content = clean,
        )
        append(conversationId, message)
        return message
    }

    override fun appendCharacterMessage(conversationId: String, content: String): LuluChatMessage {
        val clean = content.trim()
        require(clean.isNotEmpty()) { "Message content cannot be blank" }
        val message = LuluChatMessage(
            conversationId = conversationId,
            sender = LuluChatMessage.Sender.Character,
            content = clean,
        )
        append(conversationId, message)
        return message
    }

    override fun markFailed(messageId: String) {
        messageStates.values.forEach { state ->
            state.update { messages ->
                messages.map { message ->
                    if (message.id == messageId) message.copy(status = LuluChatMessage.Status.Failed) else message
                }
            }
        }
    }

    override fun markConversationRead(conversationId: String) {
        conversationState.update { conversations ->
            conversations.map { conversation ->
                if (conversation.id == conversationId) conversation.copy(unreadCount = 0) else conversation
            }
        }
    }

    private fun append(conversationId: String, message: LuluChatMessage) {
        val state = messageStates.getOrPut(conversationId) { MutableStateFlow(emptyList()) }
        state.update { it + message }
        conversationState.update { conversations ->
            val current = conversations.firstOrNull { it.id == conversationId }
            val updated = (current ?: LuluConversation(conversationId, "lulu", "露露")).copy(
                lastMessage = message.content,
                updatedAt = message.createdAt,
            )
            listOf(updated) + conversations.filterNot { it.id == conversationId }
        }
    }
}

data class CharacterContactPolicy(
    val enabled: Boolean = true,
    val adaptiveFrequency: Boolean = true,
    val quietHoursEnabled: Boolean = true,
    val quietStartHour: Int = 23,
    val quietEndHour: Int = 7,
    val proactiveCallsEnabled: Boolean = false,
    val callWindowStartHour: Int = 9,
    val callWindowEndHour: Int = 22,
) {
    init {
        require(quietStartHour in 0..23)
        require(quietEndHour in 0..23)
        require(callWindowStartHour in 0..23)
        require(callWindowEndHour in 0..23)
    }
}

data class CharacterSettings(
    val characterId: String,
    val displayName: String,
    val persona: String = "",
    val contactPolicy: CharacterContactPolicy = CharacterContactPolicy(),
    val defaultWorldBookIds: Set<String> = emptySet(),
)

class CharacterSettingsStore {
    private val state = MutableStateFlow(
        mapOf(
            "lulu" to CharacterSettings(
                characterId = "lulu",
                displayName = "露露",
                persona = "长期陪伴主人、保持连续感与角色一致性。",
            ),
        ),
    )

    val settings: StateFlow<Map<String, CharacterSettings>> = state.asStateFlow()

    fun update(settings: CharacterSettings) {
        state.update { it + (settings.characterId to settings) }
    }

    fun get(characterId: String): CharacterSettings =
        state.value[characterId] ?: CharacterSettings(characterId, characterId)
}

data class CharacterWorldBookRule(
    val worldBookId: String,
    val characterId: String,
    val enabled: Boolean,
)

class CharacterWorldBookRuleStore {
    private val state = MutableStateFlow<List<CharacterWorldBookRule>>(emptyList())
    val rules: StateFlow<List<CharacterWorldBookRule>> = state.asStateFlow()

    fun setEnabled(worldBookId: String, characterId: String, enabled: Boolean) {
        state.update { rules ->
            rules.filterNot { it.worldBookId == worldBookId && it.characterId == characterId } +
                CharacterWorldBookRule(worldBookId, characterId, enabled)
        }
    }

    fun isEnabled(worldBookId: String, characterId: String, globalEnabled: Boolean): Boolean {
        val override = state.value.lastOrNull {
            it.worldBookId == worldBookId && it.characterId == characterId
        }
        return override?.enabled ?: globalEnabled
    }
}

object MigratedDomainStores {
    val chat: LuluChatStore = InMemoryLuluChatStore()
    val characters = CharacterSettingsStore()
    val worldBookRules = CharacterWorldBookRuleStore()
}
