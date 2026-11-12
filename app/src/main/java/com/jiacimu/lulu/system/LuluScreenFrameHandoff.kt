package com.jiacimu.lulu.system

/**
 * One-shot, role-scoped in-memory frame delivery. Pixel bytes never enter
 * ToolRouter's durable receipts, personality, or shared history.
 *
 * The former 90s TTL was checked only on the NEXT put(): abandoned frames
 * could occupy heap indefinitely when no further phone action followed.
 * Keep an explicit total bound and release immediately under memory pressure.
 */
internal object LuluScreenFrameHandoff {
    private data class Frame(val imageDataUrl: String, val createdAt: Long, val order: Long)
    private const val TTL_MS = 90_000L
    private const val MAX_PENDING = 3
    private const val MAX_FRAME_CHARS = 1_400_000
    private val pending = LinkedHashMap<String, Frame>()
    private val guard = Any()
    private var sequence = 0L

    private fun key(characterId: String, requestId: String) =
        characterId + ":" + requestId

    private fun prune(now: Long) {
        pending.entries.removeIf { now - it.value.createdAt !in 0..TTL_MS }
        while (pending.size > MAX_PENDING) {
            val oldest = pending.minByOrNull { it.value.order }?.key ?: break
            pending.remove(oldest)
        }
    }

    fun put(characterId: String, requestId: String, imageDataUrl: String) {
        require(characterId.isNotBlank() && requestId.isNotBlank())
        require(imageDataUrl.startsWith("data:image/jpeg;base64,"))
        require(imageDataUrl.length <= MAX_FRAME_CHARS) {
            "Virtual screen frame exceeds the in-memory handoff budget"
        }
        synchronized(guard) {
            val now = System.currentTimeMillis()
            prune(now)
            // The same role cannot carry images from earlier undelivered turns.
            pending.keys.removeIf { it.startsWith(characterId + ":") }
            pending[key(characterId, requestId)] = Frame(imageDataUrl, now, ++sequence)
            prune(now)
        }
    }

    fun take(characterId: String, requestId: String): String? =
        synchronized(guard) {
            val now = System.currentTimeMillis()
            prune(now)
            pending.remove(key(characterId, requestId))?.imageDataUrl
        }

    fun discard(characterId: String, requestId: String) {
        synchronized(guard) { pending.remove(key(characterId, requestId)) }
    }

    /** Memory-pressure eviction intentionally loses an optional frame; the
     * caller must request a new verified image, NEVER reuse an old one. */
    fun clear() {
        synchronized(guard) { pending.clear() }
    }
}
