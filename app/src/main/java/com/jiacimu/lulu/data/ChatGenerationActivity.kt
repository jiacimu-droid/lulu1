package com.jiacimu.lulu.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** Actual request/delivery leases shared by manual replies, online workers and social tools. */
object ChatGenerationActivity {
    data class Activity(
        val characterId: String,
        val conversationIds: Set<String>,
        // The lease also covers executing the decision's tools. That is NOT
        // evidence the character is still generating text for the chat header.
        val awaitingModel: Boolean = true,
    )
    private val mutable = MutableStateFlow<Map<String, Activity>>(emptyMap())
    val activities = mutable.asStateFlow()

    fun begin(characterId: String, conversationIds: Set<String>): String {
        val id = UUID.randomUUID().toString()
        mutable.update { it + (id to Activity(characterId, conversationIds)) }
        return id
    }
    fun isRunning(conversationId: String) = mutable.value.values.any { conversationId in it.conversationIds }

    /** A real request/tool lease: while it exists, the character is awake.
     * Unlike a pending plan, this is actual ongoing work with a finally block.
     */
    fun isWorking(characterId: String): Boolean = characterId.isNotBlank() &&
        mutable.value.values.any { it.characterId == characterId }
    fun markModelCompleted(id: String) {
        mutable.update { current ->
            current[id]?.let { current + (id to it.copy(awaitingModel = false)) } ?: current
        }
    }
    fun end(id: String) {
        val owner = mutable.value[id]?.characterId
        mutable.update { it - id }
        // The expiry may have arrived while this character was still working.
        // Trigger the original expiry appraisal AFTER the last real work ends,
        // not while the role is halfway through a user-requested task.
        if (owner != null && !isWorking(owner))
            CompanionOnlineStore.onRoleWorkFinished(owner)
    }
    fun clearConversation(conversationId: String) {
        mutable.update { all -> all.mapValues { (_, a) -> a.copy(conversationIds = a.conversationIds - conversationId) } }
    }
    fun clearCharacter(characterId: String) { mutable.update { all -> all.filterValues { it.characterId != characterId } } }
    suspend fun <T> duringWithLease(
        characterId: String, conversationIds: Set<String>, block: suspend (String) -> T,
    ): T {
        val id = begin(characterId, conversationIds)
        return try { block(id) } finally { end(id) }
    }

    suspend fun <T> during(characterId: String, conversationIds: Set<String>, block: suspend () -> T): T {
        val id = begin(characterId, conversationIds)
        return try { block() } finally { end(id) }
    }
}
