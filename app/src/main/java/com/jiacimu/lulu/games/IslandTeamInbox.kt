package com.jiacimu.lulu.games

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pending player messages are per-recipient delivery state, not a second character memory.
 * A failed/incomplete decision never acknowledges that actor's unread messages.
 * Acknowledging a snapshot also cannot consume messages submitted while the model thinks.
 */
internal data class IslandTeamInbox(
    val messages: List<String> = emptyList(),
    val receivedUntil: Map<String, Int> = emptyMap(),
) {
    fun enqueue(text: String): IslandTeamInbox {
        val message = text.trim()
        return if (message.isEmpty()) this else copy(messages = messages + message)
    }

    fun forRole(characterId: String): List<String> =
        messages.drop(receivedUntil[characterId].orZero().coerceIn(0, messages.size))

    fun acknowledge(characterId: String, observedMessageCount: Int): IslandTeamInbox {
        if (characterId.isBlank()) return this
        val at = observedMessageCount.coerceIn(0, messages.size)
        return copy(receivedUntil = receivedUntil +
            (characterId to maxOf(receivedUntil[characterId].orZero(), at)))
    }

    fun compact(currentTeam: List<String>): IslandTeamInbox {
        if (messages.isEmpty() || currentTeam.isEmpty()) return this
        val consumed = currentTeam.distinct().minOf {
            receivedUntil[it].orZero().coerceIn(0, messages.size)
        }
        if (consumed <= 0) return this
        return copy(
            messages = messages.drop(consumed),
            receivedUntil = receivedUntil.mapValues { (_, index) -> (index - consumed).coerceAtLeast(0) },
        )
    }

    fun toJson(): String = JSONObject()
        .put("messages", JSONArray(messages))
        .put("receivedUntil", JSONObject().apply {
            receivedUntil.forEach { (id, index) -> put(id, index) }
        }).toString()

    companion object {
        fun fromJson(source: String): IslandTeamInbox = runCatching {
            val root = JSONObject(source)
            val array = root.getJSONArray("messages")
            val messages = (0 until array.length()).map { array.getString(it) }
            val receipt = root.optJSONObject("receivedUntil") ?: JSONObject()
            val acknowledgments = mutableMapOf<String, Int>()
            val keys = receipt.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                acknowledgments[id] = receipt.getInt(id).coerceIn(0, messages.size)
            }
            IslandTeamInbox(messages, acknowledgments)
        }.getOrDefault(IslandTeamInbox())
    }
}

private fun Int?.orZero(): Int = this ?: 0
