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
    fun markModelCompleted(id: String) {
        mutable.update { current ->
            current[id]?.let { current + (id to it.copy(awaitingModel = false)) } ?: current
        }
    }
    fun end(id: String) { mutable.update { it - id } }
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
