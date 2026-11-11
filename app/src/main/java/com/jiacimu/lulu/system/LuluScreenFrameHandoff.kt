package com.jiacimu.lulu.system

import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived in-memory delivery only. No screen pixels, image URLs or model
 * secrets enter ToolRouter's durable JSON receipt, persona, or timeline.
 * The ongoing character reply consumes the frame once; no image model is run.
 */
internal object LuluScreenFrameHandoff {
    private data class Frame(val imageDataUrl: String, val createdAt: Long)
    private const val TTL_MS = 90_000L
    private val pending = ConcurrentHashMap<String, Frame>()
    private fun key(characterId: String, requestId: String) =
        characterId + ":" + requestId

    fun put(characterId: String, requestId: String, imageDataUrl: String) {
        require(characterId.isNotBlank() && requestId.isNotBlank())
        require(imageDataUrl.startsWith("data:image/jpeg;base64,"))
        val now = System.currentTimeMillis()
        pending.entries.removeIf { now - it.value.createdAt > TTL_MS }
        // A role's earlier undelivered frame must not migrate to a later turn.
        pending.keys.removeIf { it.startsWith(characterId + ":") }
        pending[key(characterId, requestId)] = Frame(imageDataUrl, now)
    }

    fun take(characterId: String, requestId: String): String? {
        val frame = pending.remove(key(characterId, requestId)) ?: return null
        return frame.imageDataUrl.takeIf {
            System.currentTimeMillis() - frame.createdAt in 0..TTL_MS
        }
    }

    fun discard(characterId: String, requestId: String) {
        pending.remove(key(characterId, requestId))
    }
}
