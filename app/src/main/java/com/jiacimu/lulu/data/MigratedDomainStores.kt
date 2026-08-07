package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class LuluChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val sender: Sender,
    val content: String,
    val createdAt: Instant = Instant.now(),
    val status: Status = Status.Sent,
    val favorite: Boolean = false,
    val authorCharacterId: String? = null,
    val replyToMessageId: String? = null,
) {
    enum class Sender { User, Character, System }
    enum class Status { Sending, Sent, Failed }
}

enum class LuluGroupRole { Owner, Admin, Member }

data class LuluGroupMember(
    val characterId: String,
    val groupNickname: String = "",
    val role: LuluGroupRole = LuluGroupRole.Member,
)

data class LuluGroupChat(
    val name: String,
    val avatarUri: String? = null,
    val announcement: String = "",
    val userGroupNickname: String = "我",
    val members: List<LuluGroupMember>,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val showMemberNames: Boolean = true,
    val allowCharacterConversation: Boolean = true,
    val maxAutoReplies: Int = 6,
) {
    fun normalized(): LuluGroupChat = copy(
        name = name.trim().ifBlank { "新群聊" },
        announcement = announcement.trim(),
        userGroupNickname = userGroupNickname.trim().ifBlank { "我" },
        members = members.distinctBy(LuluGroupMember::characterId).map { member ->
            member.copy(groupNickname = member.groupNickname.trim())
        },
        maxAutoReplies = maxAutoReplies.coerceIn(1, 8),
    )
}

data class LuluConversation(
    val id: String,
    val characterId: String,
    val title: String,
    val lastMessage: String = "",
    val updatedAt: Instant = Instant.now(),
    val unreadCount: Int = 0,
    val groupChat: LuluGroupChat? = null,
)

interface LuluChatStore {
    val conversations: StateFlow<List<LuluConversation>>
    fun messages(conversationId: String): StateFlow<List<LuluChatMessage>>
    fun ensureConversation(characterId: String, title: String): LuluConversation
    fun createGroupConversation(name: String, memberIds: List<String>): LuluConversation
    fun updateGroupConversation(conversationId: String, groupChat: LuluGroupChat): Boolean
    fun deleteConversation(conversationId: String): Boolean
    fun clearConversationMessages(conversationId: String): Boolean
    fun sendUserMessage(conversationId: String, content: String, replyToMessageId: String? = null): LuluChatMessage
    fun appendCharacterMessage(
        conversationId: String,
        content: String,
        authorCharacterId: String? = null,
        replyToMessageId: String? = null,
    ): LuluChatMessage
    fun appendSystemMessage(conversationId: String, content: String): LuluChatMessage
    fun appendPrivateActivityNotice(characterId: String, content: String): LuluChatMessage
    fun markFailed(messageId: String)
    fun markConversationRead(conversationId: String)
    fun editMessage(messageId: String, content: String): Boolean
    fun deleteMessage(messageId: String): Boolean
    fun toggleFavorite(messageId: String): Boolean
}

/** Retains the old class name while using persistent local storage. */
class InMemoryLuluChatStore : LuluChatStore {
    private val conversationState = MutableStateFlow(defaultConversations())
    private val messageStates = mutableMapOf<String, MutableStateFlow<List<LuluChatMessage>>>()
    private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()

    override val conversations: StateFlow<List<LuluConversation>> = conversationState.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
            val loaded = decode(prefs?.getString(CHAT_STATE, null))
            conversationState.value = loaded.first.ifEmpty { defaultConversations() }.sortedWith(conversationOrdering())
            messageStates.clear()
            loaded.second.forEach { (conversationId, messages) ->
                messageStates[conversationId] = MutableStateFlow(messages)
            }
        }
    }

    override fun messages(conversationId: String): StateFlow<List<LuluChatMessage>> = synchronized(lock) {
        messageStates.getOrPut(conversationId) {
            MutableStateFlow(defaultMessages(conversationId))
        }.asStateFlow()
    }

    override fun ensureConversation(characterId: String, title: String): LuluConversation {
        val cleanCharacterId = characterId.trim()
        require(cleanCharacterId.isNotBlank()) { "角色 ID 不能为空" }
        val cleanTitle = title.trim().ifBlank { "未命名角色" }
        synchronized(lock) {
            conversationState.value
                .filter { it.characterId == cleanCharacterId && it.groupChat == null }
                .maxByOrNull(LuluConversation::updatedAt)
                ?.let { return it }

            val conversation = LuluConversation(
                id = UUID.randomUUID().toString(),
                characterId = cleanCharacterId,
                title = cleanTitle,
                lastMessage = "",
                updatedAt = Instant.now(),
            )
            messageStates[conversation.id] = MutableStateFlow(emptyList())
            conversationState.value = (listOf(conversation) + conversationState.value)
                .sortedByDescending(LuluConversation::updatedAt)
            persistLocked()
            return conversation
        }
    }

    override fun createGroupConversation(name: String, memberIds: List<String>): LuluConversation {
        val members = memberIds.map(String::trim).filter(String::isNotBlank).distinct()
        require(members.size >= 2) { "群聊至少需要两个角色" }
        val conversation = synchronized(lock) {
            val conversation = LuluConversation(
                id = UUID.randomUUID().toString(),
                characterId = members.first(),
                title = name.trim().ifBlank { "新群聊" },
                updatedAt = Instant.now(),
                groupChat = LuluGroupChat(
                    name = name,
                    members = members.map { characterId -> LuluGroupMember(characterId = characterId) },
                ).normalized(),
            )
            messageStates[conversation.id] = MutableStateFlow(emptyList())
            conversationState.value = (listOf(conversation) + conversationState.value)
                .sortedWith(conversationOrdering())
            persistLocked()
            conversation
        }
        recordGroupMembershipAwareness(conversation)
        return conversation
    }

    override fun updateGroupConversation(conversationId: String, groupChat: LuluGroupChat): Boolean {
        val normalized = groupChat.normalized()
        if (normalized.members.size < 2) return false
        val membershipChanges = synchronized(lock) {
            val previous = conversationState.value.firstOrNull { it.id == conversationId }?.groupChat
                ?: return false
            val previousIds = previous.members.map(LuluGroupMember::characterId).toSet()
            val normalizedIds = normalized.members.map(LuluGroupMember::characterId).toSet()
            val added = normalizedIds - previousIds
            val removed = previousIds - normalizedIds
            var changed = false
            conversationState.value = conversationState.value.map { conversation ->
                if (conversation.id == conversationId && conversation.groupChat != null) {
                    changed = true
                    conversation.copy(
                        characterId = normalized.members.first().characterId,
                        title = normalized.name,
                        groupChat = normalized,
                        updatedAt = Instant.now(),
                    )
                } else {
                    conversation
                }
            }.sortedWith(conversationOrdering())
            if (changed) persistLocked()
            if (changed) added to removed else emptySet<String>() to emptySet()
        }
        val (addedMemberIds, removedMemberIds) = membershipChanges
        if (addedMemberIds.isNotEmpty() || removedMemberIds.isNotEmpty()) {
            conversationState.value.firstOrNull { it.id == conversationId }?.let { conversation ->
                val eventParts = buildList {
                    if (addedMemberIds.isNotEmpty()) {
                        val names = addedMemberIds.joinToString("、") { MigratedDomainStores.characters.get(it).displayName }
                        add("用户邀请 $names 加入了群聊")
                    }
                    if (removedMemberIds.isNotEmpty()) {
                        val names = removedMemberIds.joinToString("、") { MigratedDomainStores.characters.get(it).displayName }
                        add("用户将 $names 移出了群聊")
                    }
                }
                appendSystemMessage(conversationId, "[群成员变更] ${eventParts.joinToString("；")}。当前群成员：${normalized.members.size + 1} 人。")
                recordGroupMembershipAwareness(conversation, addedMemberIds)
                removedMemberIds.forEach { characterId ->
                    SharedExperienceTimeline.record(
                        eventId = "group-removed-${conversation.id}-$characterId-${Instant.now().toEpochMilli()}",
                        characterId = characterId,
                        channel = "群聊·${normalized.name}",
                        speaker = "系统事件",
                        content = "用户将你移出了群聊《${normalized.name}》。你保留此前真实经历，但不再是群成员，也不会读取或参与此后的群聊消息。",
                        occurredAt = Instant.now(),
                    )
                }
            }
        }
        return true
    }

    private fun recordGroupMembershipAwareness(
        conversation: LuluConversation,
        onlyCharacterIds: Set<String>? = null,
    ) {
        val group = conversation.groupChat ?: return
        val characterNames = group.members.map { member ->
            val character = MigratedDomainStores.characters.get(member.characterId)
            member.groupNickname.ifBlank { character.displayName }
        }
        val memberSummary = (listOf(group.userGroupNickname) + characterNames).joinToString("、")
        group.members
            .filter { onlyCharacterIds == null || it.characterId in onlyCharacterIds }
            .forEach { member ->
                SharedExperienceTimeline.record(
                    eventId = "group-joined-${conversation.id}-${member.characterId}-${conversation.updatedAt.toEpochMilli()}",
                    characterId = member.characterId,
                    channel = "群聊·${group.name}",
                    speaker = "系统事件",
                    content = "用户把你拉进了群聊《${group.name}》。群成员：$memberSummary。你知道自己现在是这个群的成员，可以阅读并参与群内对话。",
                    occurredAt = conversation.updatedAt,
                )
            }
    }

    override fun deleteConversation(conversationId: String): Boolean {
        val deleted = synchronized(lock) {
            val conversation = conversationState.value.firstOrNull { it.id == conversationId } ?: return false
            val messages = messageStates[conversationId]?.value.orEmpty()
            if (conversationState.value.none { it.id == conversationId }) return false
            conversationState.value = conversationState.value.filterNot { it.id == conversationId }
            messageStates.remove(conversationId)
            persistLocked()
            conversation to messages
        }
        SharedExperienceTimeline.deleteConversationData(deleted.first, deleted.second)
        return true
    }

    override fun clearConversationMessages(conversationId: String): Boolean {
        val cleared = synchronized(lock) {
            val conversation = conversationState.value.firstOrNull { it.id == conversationId } ?: return false
            val state = messageStates[conversationId] ?: return false
            val messages = state.value
            state.value = emptyList()
            conversationState.value = conversationState.value.map { current ->
                if (current.id == conversationId) current.copy(lastMessage = "", unreadCount = 0, updatedAt = Instant.now()) else current
            }.sortedWith(conversationOrdering())
            persistLocked()
            conversation to messages
        }
        SharedExperienceTimeline.deleteConversationData(cleared.first, cleared.second)
        return true
    }

    override fun sendUserMessage(
        conversationId: String,
        content: String,
        replyToMessageId: String?,
    ): LuluChatMessage {
        val clean = content.trim()
        require(clean.isNotEmpty()) { "Message content cannot be blank" }
        return LuluChatMessage(
            conversationId = conversationId,
            sender = LuluChatMessage.Sender.User,
            content = clean,
            replyToMessageId = replyToMessageId,
        ).also { message -> append(conversationId, message, incrementUnread = false) }
    }

    override fun appendCharacterMessage(
        conversationId: String,
        content: String,
        authorCharacterId: String?,
        replyToMessageId: String?,
    ): LuluChatMessage {
        val clean = content.trim()
        require(clean.isNotEmpty()) { "Message content cannot be blank" }
        return LuluChatMessage(
            conversationId = conversationId,
            sender = LuluChatMessage.Sender.Character,
            content = clean,
            authorCharacterId = authorCharacterId,
            replyToMessageId = replyToMessageId,
        ).also { message -> append(conversationId, message, incrementUnread = false) }
    }

    override fun appendSystemMessage(conversationId: String, content: String): LuluChatMessage {
        val clean = content.trim()
        require(clean.isNotEmpty()) { "Message content cannot be blank" }
        val requested = conversationState.value.firstOrNull { it.id == conversationId }
        val effectiveConversationId = if (clean.startsWith("[共同活动]") && requested?.groupChat != null) {
            val character = MigratedDomainStores.characters.get(requested.characterId)
            ensureConversation(requested.characterId, character.displayName).id
        } else {
            conversationId
        }
        return LuluChatMessage(
            conversationId = effectiveConversationId,
            sender = LuluChatMessage.Sender.System,
            content = clean,
        ).also { message -> append(effectiveConversationId, message, incrementUnread = false) }
    }

    override fun appendPrivateActivityNotice(characterId: String, content: String): LuluChatMessage {
        val cleanCharacterId = characterId.trim().ifBlank { "lulu" }
        val character = MigratedDomainStores.characters.get(cleanCharacterId)
        val conversation = ensureConversation(cleanCharacterId, character.displayName)
        val notice = content.trim().let { text ->
            if (text.startsWith("[共同活动]")) text else "[共同活动] $text"
        }
        return appendSystemMessage(conversation.id, notice)
    }

    override fun markFailed(messageId: String) {
        mutateMessagesContaining(messageId) { messages ->
            messages.map { message ->
                if (message.id == messageId) message.copy(status = LuluChatMessage.Status.Failed) else message
            }
        }
    }

    override fun markConversationRead(conversationId: String) {
        synchronized(lock) {
            conversationState.update { conversations ->
                conversations.map { conversation ->
                    if (conversation.id == conversationId) conversation.copy(unreadCount = 0) else conversation
                }
            }
            persistLocked()
        }
    }

    override fun editMessage(messageId: String, content: String): Boolean {
        val clean = content.trim()
        if (clean.isBlank()) return false
        val changed = mutateMessagesContaining(messageId) { messages ->
            messages.map { message ->
                if (message.id == messageId) message.copy(content = clean, status = LuluChatMessage.Status.Sent) else message
            }
        }
        if (changed) {
            conversationState.value.firstOrNull { conversation ->
                messageStates[conversation.id]?.value?.any { it.id == messageId } == true
            }?.let { conversation ->
                messageStates[conversation.id]?.value?.firstOrNull { it.id == messageId }?.let { message ->
                    SharedExperienceTimeline.recordConversationMessage(conversation, message)
                }
            }
        }
        return changed
    }

    override fun deleteMessage(messageId: String): Boolean {
        val conversation = conversationState.value.firstOrNull { current ->
            messageStates[current.id]?.value?.any { it.id == messageId } == true
        }
        val changed = mutateMessagesContaining(messageId) { messages ->
            messages.filterNot { message -> message.id == messageId }
        }
        if (changed && conversation != null) SharedExperienceTimeline.deleteConversationMessage(conversation, messageId)
        return changed
    }

    override fun toggleFavorite(messageId: String): Boolean = mutateMessagesContaining(messageId) { messages ->
        messages.map { message ->
            if (message.id == messageId) message.copy(favorite = !message.favorite) else message
        }
    }

    private fun append(conversationId: String, message: LuluChatMessage, incrementUnread: Boolean) {
        synchronized(lock) {
            val current = conversationState.value.firstOrNull { conversation -> conversation.id == conversationId }
                ?: error("会话不存在：$conversationId。请先通过 ensureConversation 建立角色会话。")
            val state = messageStates.getOrPut(conversationId) { MutableStateFlow(emptyList()) }
            state.value = state.value + message
            val updated = current.copy(
                lastMessage = message.content,
                updatedAt = message.createdAt,
                unreadCount = if (incrementUnread) current.unreadCount + 1 else current.unreadCount,
            )
            conversationState.value = (listOf(updated) + conversationState.value.filterNot { it.id == conversationId })
                .sortedWith(conversationOrdering())
            persistLocked()
            SharedExperienceTimeline.recordConversationMessage(current, message)
        }
    }

    private fun mutateMessagesContaining(
        messageId: String,
        transform: (List<LuluChatMessage>) -> List<LuluChatMessage>,
    ): Boolean {
        synchronized(lock) {
            val entry = messageStates.entries.firstOrNull { (_, state) ->
                state.value.any { message -> message.id == messageId }
            } ?: return false
            entry.value.value = transform(entry.value.value)
            refreshConversationLocked(entry.key)
            persistLocked()
            return true
        }
    }

    private fun refreshConversationLocked(conversationId: String) {
        val messages = messageStates[conversationId]?.value.orEmpty()
        conversationState.value = conversationState.value.map { conversation ->
            if (conversation.id == conversationId) {
                conversation.copy(
                    lastMessage = messages.lastOrNull()?.content.orEmpty(),
                    updatedAt = messages.lastOrNull()?.createdAt ?: conversation.updatedAt,
                )
            } else {
                conversation
            }
        }.sortedWith(conversationOrdering())
    }

    private fun persistLocked() {
        prefs?.edit()?.putString(
            CHAT_STATE,
            encode(conversationState.value, messageStates.mapValues { (_, state) -> state.value }).toString(),
        )?.apply()
    }

    private fun encode(
        conversations: List<LuluConversation>,
        messages: Map<String, List<LuluChatMessage>>,
    ): JSONObject = JSONObject()
        .put(
            "conversations",
            JSONArray().apply {
                conversations.forEach { conversation -> put(encodeConversation(conversation)) }
            },
        )
        .put(
            "messages",
            JSONObject().apply {
                messages.forEach { (conversationId, values) ->
                    put(conversationId, JSONArray().apply { values.forEach { message -> put(encodeMessage(message)) } })
                }
            },
        )

    private fun decode(raw: String?): Pair<List<LuluConversation>, Map<String, List<LuluChatMessage>>> {
        if (raw.isNullOrBlank()) return emptyList<LuluConversation>() to emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val conversations = root.optJSONArray("conversations").decodeObjects(::decodeConversation)
            val messagesObject = root.optJSONObject("messages") ?: JSONObject()
            val messages = buildMap {
                val keys = messagesObject.keys()
                while (keys.hasNext()) {
                    val conversationId = keys.next()
                    put(conversationId, messagesObject.optJSONArray(conversationId).decodeObjects(::decodeMessage))
                }
            }
            conversations.sortedByDescending(LuluConversation::updatedAt) to messages
        }.getOrDefault(emptyList<LuluConversation>() to emptyMap())
    }

    private fun encodeConversation(value: LuluConversation): JSONObject = JSONObject()
        .put("id", value.id)
        .put("characterId", value.characterId)
        .put("title", value.title)
        .put("lastMessage", value.lastMessage)
        .put("updatedAt", value.updatedAt.toString())
        .put("unreadCount", value.unreadCount)
        .put("groupChat", value.groupChat?.let(::encodeGroupChat) ?: JSONObject.NULL)

    private fun decodeConversation(item: JSONObject): LuluConversation = LuluConversation(
        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
        characterId = item.optString("characterId").ifBlank { "lulu" },
        title = item.optString("title").ifBlank { "露露" },
        lastMessage = item.optString("lastMessage"),
        updatedAt = item.optString("updatedAt").toInstantOrNow(),
        unreadCount = item.optInt("unreadCount").coerceAtLeast(0),
        groupChat = item.optJSONObject("groupChat")?.let(::decodeGroupChat),
    )

    private fun encodeGroupChat(value: LuluGroupChat): JSONObject = JSONObject()
        .put("name", value.name)
        .put("avatarUri", value.avatarUri.orEmpty())
        .put("announcement", value.announcement)
        .put("userGroupNickname", value.userGroupNickname)
        .put("pinned", value.pinned)
        .put("muted", value.muted)
        .put("showMemberNames", value.showMemberNames)
        .put("allowCharacterConversation", value.allowCharacterConversation)
        .put("maxAutoReplies", value.maxAutoReplies)
        .put(
            "members",
            JSONArray().apply {
                value.members.forEach { member ->
                    put(
                        JSONObject()
                            .put("characterId", member.characterId)
                            .put("groupNickname", member.groupNickname)
                            .put("role", member.role.name),
                    )
                }
            },
        )

    private fun decodeGroupChat(item: JSONObject): LuluGroupChat? = runCatching {
        val members = item.optJSONArray("members").decodeObjects { member ->
            LuluGroupMember(
                characterId = member.optString("characterId"),
                groupNickname = member.optString("groupNickname"),
                role = runCatching { LuluGroupRole.valueOf(member.optString("role")) }.getOrDefault(LuluGroupRole.Member),
            )
        }.filter { it.characterId.isNotBlank() }
        if (members.size < 2) return@runCatching null
        LuluGroupChat(
            name = item.optString("name").ifBlank { "群聊" },
            avatarUri = item.optString("avatarUri").takeIf(String::isNotBlank),
            announcement = item.optString("announcement"),
            userGroupNickname = item.optString("userGroupNickname").ifBlank { "我" },
            members = members,
            pinned = item.optBoolean("pinned"),
            muted = item.optBoolean("muted"),
            showMemberNames = item.optBoolean("showMemberNames", true),
            allowCharacterConversation = item.optBoolean("allowCharacterConversation", true),
            maxAutoReplies = item.optInt("maxAutoReplies", 6).coerceIn(1, 8),
        ).normalized()
    }.getOrNull()

    private fun encodeMessage(value: LuluChatMessage): JSONObject = JSONObject()
        .put("id", value.id)
        .put("conversationId", value.conversationId)
        .put("sender", value.sender.name)
        .put("content", value.content)
        .put("createdAt", value.createdAt.toString())
        .put("status", value.status.name)
        .put("favorite", value.favorite)
        .put("authorCharacterId", value.authorCharacterId ?: JSONObject.NULL)
        .put("replyToMessageId", value.replyToMessageId ?: JSONObject.NULL)

    private fun decodeMessage(item: JSONObject): LuluChatMessage = LuluChatMessage(
        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
        conversationId = item.optString("conversationId").ifBlank { "lulu-main" },
        sender = runCatching { LuluChatMessage.Sender.valueOf(item.optString("sender")) }.getOrDefault(LuluChatMessage.Sender.System),
        content = item.optString("content"),
        createdAt = item.optString("createdAt").toInstantOrNow(),
        status = runCatching { LuluChatMessage.Status.valueOf(item.optString("status")) }.getOrDefault(LuluChatMessage.Status.Sent),
        favorite = item.optBoolean("favorite"),
        authorCharacterId = item.nullableString("authorCharacterId"),
        replyToMessageId = item.nullableString("replyToMessageId"),
    )

    private fun conversationOrdering(): Comparator<LuluConversation> =
        compareByDescending<LuluConversation> { it.groupChat?.pinned == true }
            .thenByDescending(LuluConversation::updatedAt)

    private companion object {
        const val CHAT_PREFS = "lulu_chat_store"
        const val CHAT_STATE = "state_v1"

        fun defaultConversations(): List<LuluConversation> = listOf(
            LuluConversation(
                id = "lulu-main",
                characterId = "lulu",
                title = "露露",
                lastMessage = "今天也会继续陪着你。",
            ),
        )

        fun defaultMessages(conversationId: String): List<LuluChatMessage> = if (conversationId == "lulu-main") {
            listOf(
                LuluChatMessage(
                    conversationId = conversationId,
                    sender = LuluChatMessage.Sender.Character,
                    content = "今天学习辛苦啦。",
                ),
            )
        } else emptyList()
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
    val avatarUri: String? = null,
    val persona: String = "",
    val contactPolicy: CharacterContactPolicy = CharacterContactPolicy(),
    val defaultWorldBookIds: Set<String> = emptySet(),
)

class CharacterSettingsStore {
    private val state = MutableStateFlow(defaultSettings())
    private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()
    val settings: StateFlow<Map<String, CharacterSettings>> = state.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(CHARACTER_PREFS, Context.MODE_PRIVATE)
            state.value = decodeSettings(prefs?.getString(CHARACTER_STATE, null)).ifEmpty { defaultSettings() }
        }
    }

    fun update(settings: CharacterSettings) {
        require(settings.characterId.isNotBlank()) { "角色 ID 不能为空" }
        synchronized(lock) {
            state.value = state.value + (settings.characterId to settings)
            persistLocked()
        }
    }

    fun create(displayName: String, persona: String = ""): CharacterSettings {
        val character = CharacterSettings(
            characterId = UUID.randomUUID().toString(),
            displayName = displayName.trim().ifBlank { "未命名角色" },
            persona = persona.trim(),
        )
        update(character)
        return character
    }

    fun delete(characterId: String): Boolean {
        if (characterId == "lulu") return false
        synchronized(lock) {
            if (characterId !in state.value) return false
            state.value = state.value - characterId
            persistLocked()
            return true
        }
    }

    fun get(characterId: String): CharacterSettings = state.value[characterId] ?: CharacterSettings(characterId, characterId)

    private fun persistLocked() {
        prefs?.edit()?.putString(
            CHARACTER_STATE,
            JSONArray().apply { state.value.values.forEach { character -> put(encodeCharacter(character)) } }.toString(),
        )?.apply()
    }

    private fun encodeCharacter(value: CharacterSettings): JSONObject = JSONObject()
        .put("characterId", value.characterId)
        .put("displayName", value.displayName)
        .put("avatarUri", value.avatarUri.orEmpty())
        .put("persona", value.persona)
        .put(
            "contactPolicy",
            JSONObject()
                .put("enabled", value.contactPolicy.enabled)
                .put("adaptiveFrequency", value.contactPolicy.adaptiveFrequency)
                .put("quietHoursEnabled", value.contactPolicy.quietHoursEnabled)
                .put("quietStartHour", value.contactPolicy.quietStartHour)
                .put("quietEndHour", value.contactPolicy.quietEndHour)
                .put("proactiveCallsEnabled", value.contactPolicy.proactiveCallsEnabled)
                .put("callWindowStartHour", value.contactPolicy.callWindowStartHour)
                .put("callWindowEndHour", value.contactPolicy.callWindowEndHour),
        )
        .put("defaultWorldBookIds", JSONArray(value.defaultWorldBookIds.toList()))

    private fun decodeSettings(raw: String?): Map<String, CharacterSettings> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val characterId = item.optString("characterId")
                    if (characterId.isBlank()) continue
                    val policy = item.optJSONObject("contactPolicy") ?: JSONObject()
                    val worldBooks = buildSet {
                        val ids = item.optJSONArray("defaultWorldBookIds") ?: JSONArray()
                        for (idIndex in 0 until ids.length()) ids.optString(idIndex).takeIf(String::isNotBlank)?.let(::add)
                    }
                    put(
                        characterId,
                        CharacterSettings(
                            characterId = characterId,
                            displayName = item.optString("displayName").ifBlank { characterId },
                            avatarUri = item.optString("avatarUri").takeIf(String::isNotBlank),
                            persona = item.optString("persona"),
                            contactPolicy = CharacterContactPolicy(
                                enabled = policy.optBoolean("enabled", true),
                                adaptiveFrequency = policy.optBoolean("adaptiveFrequency", true),
                                quietHoursEnabled = policy.optBoolean("quietHoursEnabled", true),
                                quietStartHour = policy.optInt("quietStartHour", 23).coerceIn(0, 23),
                                quietEndHour = policy.optInt("quietEndHour", 7).coerceIn(0, 23),
                                proactiveCallsEnabled = policy.optBoolean("proactiveCallsEnabled", false),
                                callWindowStartHour = policy.optInt("callWindowStartHour", 9).coerceIn(0, 23),
                                callWindowEndHour = policy.optInt("callWindowEndHour", 22).coerceIn(0, 23),
                            ),
                            defaultWorldBookIds = worldBooks,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val CHARACTER_PREFS = "lulu_character_store"
        const val CHARACTER_STATE = "state_v1"

        fun defaultSettings(): Map<String, CharacterSettings> = mapOf(
            "lulu" to CharacterSettings(
                characterId = "lulu",
                displayName = "露露",
                persona = "长期陪伴用户，保持连续感与角色一致性；具体关系和称呼以用户填写的人设为准。",
            ),
        )
    }
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
            rules.filterNot { rule -> rule.worldBookId == worldBookId && rule.characterId == characterId } +
                CharacterWorldBookRule(worldBookId, characterId, enabled)
        }
    }

    fun isEnabled(worldBookId: String, characterId: String, globalEnabled: Boolean): Boolean {
        val override = state.value.lastOrNull { rule -> rule.worldBookId == worldBookId && rule.characterId == characterId }
        return override?.enabled ?: globalEnabled
    }
}

object MigratedDomainStores {
    val chat = InMemoryLuluChatStore()
    val characters = CharacterSettingsStore()
    val worldBookRules = CharacterWorldBookRuleStore()

    fun initialize(context: Context) {
        chat.initialize(context)
        characters.initialize(context)
    }
}

private fun <T> JSONArray?.decodeObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            runCatching { transform(item) }.getOrNull()?.let(::add)
        }
    }
}

private fun JSONObject.nullableString(key: String): String? =
    takeUnless { json -> json.isNull(key) }?.optString(key)?.takeIf(String::isNotBlank)

private fun String.toInstantOrNow(): Instant = runCatching { Instant.parse(this) }.getOrDefault(Instant.now())
