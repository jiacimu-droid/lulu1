package com.jiacimu.lulu

/** Generated content stays pending until playback is confirmed; interruption returns no full text. */
internal class VoiceDeliveryLedger {
    private val pending = linkedMapOf<Int, String>()
    private val interrupted = mutableSetOf<Int>()
    var epoch: Long = 0
        private set
    fun reset(): Long { epoch++; pending.clear(); interrupted.clear(); return epoch }
    fun generated(token: Long, id: Int, text: String) {
        if (token == epoch && id !in interrupted) pending[id] = text
    }
    fun interrupt(token: Long, id: Int) {
        if (token != epoch) return
        interrupted.addAll(pending.keys); interrupted += id; pending.clear()
    }
    fun played(token: Long): Map<Int, String> {
        if (token != epoch) return emptyMap()
        val result = pending.filterKeys { it !in interrupted }
        pending.clear()
        return result
    }
    fun corrected(token: Long, id: Int, text: String): String? {
        if (token != epoch) return null
        pending.remove(id); interrupted += id
        return text
    }
}
