package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject

/**
 * Tracks historical fact validity without deleting old memories.
 *
 * A superseded memory remains in the archive for inspection/provenance, but normal recall only uses
 * entries that are not present as keys here. The replacement ID is retained for audit/debugging.
 */
internal object MemoryValidityStore {
    private const val PREFS_NAME = "lulu_memory_validity_v1"
    private const val KEY_SUPERSEDED_BY = "superseded_by"

    private var prefs: android.content.SharedPreferences? = null
    private var supersededBy: Map<String, String> = emptyMap()

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        supersededBy = decode(prefs?.getString(KEY_SUPERSEDED_BY, null))
    }

    @Synchronized
    fun isActive(memoryId: String): Boolean = memoryId !in supersededBy

    @Synchronized
    fun replacementId(memoryId: String): String? = supersededBy[memoryId]

    @Synchronized
    fun supersessionSnapshot(): Map<String, String> = supersededBy.toMap()

    @Synchronized
    fun markSuperseded(oldMemoryId: String, replacementMemoryId: String) {
        if (oldMemoryId.isBlank() || replacementMemoryId.isBlank() || oldMemoryId == replacementMemoryId) return
        supersededBy = supersededBy + (oldMemoryId to replacementMemoryId)
        MemoryEmbeddingIndex.removeMemory(oldMemoryId)
        persist()
    }

    @Synchronized
    fun markSuperseded(oldMemoryIds: Collection<String>, replacementMemoryId: String) {
        val clean = oldMemoryIds.map(String::trim)
            .filter { it.isNotBlank() && it != replacementMemoryId }
            .distinct()
        if (clean.isEmpty() || replacementMemoryId.isBlank()) return
        supersededBy = supersededBy + clean.associateWith { replacementMemoryId }
        clean.forEach(MemoryEmbeddingIndex::removeMemory)
        persist()
    }

    @Synchronized
    fun removeMemory(memoryId: String) {
        if (memoryId.isBlank()) return
        MemoryEmbeddingIndex.removeMemory(memoryId)
        val next = supersededBy
            .filterKeys { it != memoryId }
            // Do not reactivate older false facts merely because the newer correction was deleted.
            // Historical invalidation is evidence about chronology, not ownership of the new entry.
        if (next != supersededBy) {
            supersededBy = next
            persist()
        }
    }

    @Synchronized
    fun debugContext(memoryIds: Collection<String>): String {
        val rows = memoryIds.mapNotNull { oldId -> supersededBy[oldId]?.let { newId -> oldId to newId } }
        if (rows.isEmpty()) return ""
        return buildString {
            appendLine("【历史事实替代关系】")
            rows.forEach { (oldId, newId) -> appendLine("- 旧 memoryId=$oldId 已由 memoryId=$newId 替代；旧条目仅保留历史记录，不代表当前事实。") }
        }.trim()
    }

    private fun persist() {
        val objectValue = JSONObject().apply {
            supersededBy.forEach { (oldId, newId) -> put(oldId, newId) }
        }
        prefs?.edit()?.putString(KEY_SUPERSEDED_BY, objectValue.toString())?.commit()
    }

    private fun decode(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val oldId = keys.next()
                    val newId = root.optString(oldId).trim()
                    if (oldId.isNotBlank() && newId.isNotBlank() && oldId != newId) put(oldId, newId)
                }
            }
        }.getOrDefault(emptyMap())
    }
}
