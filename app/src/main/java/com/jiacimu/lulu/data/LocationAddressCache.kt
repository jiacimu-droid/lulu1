package com.jiacimu.lulu.data

import java.util.concurrent.Executors

/** Address lookup must never sit on a chat/phone's path to the model.
 * One in-flight lookup and one exact-coordinate result; no location history. */
internal class LocationAddressCache(
    private val execute: (() -> Unit) -> Unit = { work -> worker.execute(work) },
) {
    data class Key(val latitude: Double, val longitude: Double, val locale: String)
    private data class Entry(val key: Key, val label: String)
    private var cached: Entry? = null
    private var running = false

    @Synchronized fun peekOrRefresh(key: Key, resolve: () -> String): String {
        cached?.takeIf { it.key == key }?.let { return it.label }
        if (!running) {
            running = true
            try {
                execute {
                    // A failed lookup stays unknown and can retry on a later
                    // perception. It never blocks or fails a paid model request.
                    val label = runCatching(resolve).getOrDefault("")
                    synchronized(this) {
                        if (label.isNotBlank()) cached = Entry(key, label)
                        running = false
                    }
                }
            } catch (_: Exception) { running = false }
        }
        return ""
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "lulu-address-observation").apply { isDaemon = true }
        }
        val shared = LocationAddressCache()
    }
}
