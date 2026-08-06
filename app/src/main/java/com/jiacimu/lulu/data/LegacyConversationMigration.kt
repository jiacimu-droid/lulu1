package com.jiacimu.lulu.data

import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.util.UUID

/**
 * One-time compatibility cleanup for data created by older builds.
 *
 * Runtime chat now has only two conversation types: ordinary one-to-one chats and group chats.
 * Old branch copies are folded back into their source conversation; old synthetic Pomodoro chats
 * are folded into the character's ordinary private chat. The legacy records are then removed.
 */
object LegacyConversationMigration {
    fun migrateToPrivateAndGroupOnly() {
        val store = MigratedDomainStores.chat as? InMemoryLuluChatStore ?: return
        runCatching {
            val type = InMemoryLuluChatStore::class.java
            val lockField = type.getDeclaredField("lock").apply { isAccessible = true }
            val conversationField = type.getDeclaredField("conversationState").apply { isAccessible = true }
            val messagesField = type.getDeclaredField("messageStates").apply { isAccessible = true }
            val persistMethod = type.getDeclaredMethod("persistLocked").apply { isAccessible = true }
            val lock = lockField.get(store)

            synchronized(lock) {
                @Suppress("UNCHECKED_CAST")
                val conversations = conversationField.get(store) as MutableStateFlow<List<LuluConversation>>
                @Suppress("UNCHECKED_CAST")
                val messageStates = messagesField.get(store) as MutableMap<String, MutableStateFlow<List<LuluChatMessage>>>

                val original = conversations.value
                val legacy = original.filter { conversation ->
                    conversation.parentConversationId != null || conversation.id.endsWith("-study-focus")
                }
                if (legacy.isEmpty()) return@synchronized

                val kept = original.filterNot { it in legacy }.toMutableList()

                fun privateTarget(characterId: String, title: String): LuluConversation {
                    kept.filter { candidate ->
                        candidate.groupChat == null && candidate.characterId == characterId
                    }.maxByOrNull(LuluConversation::updatedAt)?.let { return it }

                    return LuluConversation(
                        id = if (characterId == "lulu" && kept.none { it.id == "lulu-main" }) {
                            "lulu-main"
                        } else {
                            UUID.randomUUID().toString()
                        },
                        characterId = characterId,
                        title = title.ifBlank { MigratedDomainStores.characters.get(characterId).displayName },
                        updatedAt = Instant.now(),
                    ).also { created ->
                        kept += created
                        messageStates.putIfAbsent(created.id, MutableStateFlow(emptyList()))
                    }
                }

                fun resolveBranchTarget(conversation: LuluConversation): LuluConversation? {
                    var parentId = conversation.parentConversationId
                    val visited = mutableSetOf<String>()
                    while (!parentId.isNullOrBlank() && visited.add(parentId)) {
                        val parent = original.firstOrNull { it.id == parentId } ?: break
                        if (parent.parentConversationId == null && !parent.id.endsWith("-study-focus")) return parent
                        parentId = parent.parentConversationId
                    }
                    return null
                }

                val mergedByTarget = linkedMapOf<String, MutableList<LuluChatMessage>>()
                val targetInfo = linkedMapOf<String, LuluConversation>()

                legacy.sortedBy(LuluConversation::updatedAt).forEach { source ->
                    val target = if (source.id.endsWith("-study-focus")) {
                        privateTarget(source.characterId, source.title)
                    } else {
                        resolveBranchTarget(source)
                            ?: privateTarget(source.characterId, source.title.substringBefore(" · 分支"))
                    }
                    targetInfo[target.id] = target
                    val targetMessages = mergedByTarget.getOrPut(target.id) {
                        messageStates[target.id]?.value.orEmpty().toMutableList()
                    }
                    targetMessages += messageStates[source.id]?.value.orEmpty().map { message ->
                        message.copy(conversationId = target.id, branchOriginMessageId = null)
                    }
                }

                mergedByTarget.forEach { (targetId, values) ->
                    val merged = values
                        .distinctBy { message ->
                            listOf(
                                message.sender.name,
                                message.authorCharacterId.orEmpty(),
                                message.content.trim(),
                                message.createdAt.toEpochMilli().toString(),
                                message.replyToMessageId.orEmpty(),
                            ).joinToString("\u0001")
                        }
                        .sortedBy(LuluChatMessage::createdAt)
                    messageStates.getOrPut(targetId) { MutableStateFlow(emptyList()) }.value = merged
                    val target = targetInfo[targetId] ?: kept.firstOrNull { it.id == targetId } ?: return@forEach
                    val updated = target.copy(
                        lastMessage = merged.lastOrNull()?.content.orEmpty(),
                        updatedAt = merged.lastOrNull()?.createdAt ?: target.updatedAt,
                    )
                    kept.removeAll { it.id == targetId }
                    kept += updated
                }

                legacy.forEach { source -> messageStates.remove(source.id) }
                conversations.value = kept.sortedWith(
                    compareByDescending<LuluConversation> { it.groupChat?.pinned == true }
                        .thenByDescending(LuluConversation::updatedAt),
                )
                persistMethod.invoke(store)
            }
        }
    }
}
