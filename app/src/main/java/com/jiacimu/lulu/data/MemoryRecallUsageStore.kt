package com.jiacimu.lulu.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.time.Instant

/** Successful model-input use, not candidate hits or claims that a character remembered. */
internal data class MemoryRecallUsage(
    val successfulInjections: Int = 0,
    val lastInjectedAt: Instant? = null,
)

/**
 * Per-character, source-safe usage metadata. The original memories and timeline
 * remain authoritative; usage can only bias future priority, never erase facts.
 */
internal object MemoryRecallUsageStore {
    private const val PREFS = "lulu_memory_recall_usage_v1"
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun initialize(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Read once for ranking/UI; never parse the full JSON per candidate memory. */
    @Synchronized
    fun snapshot(characterId: String): Map<String, MemoryRecallUsage> {
        if (characterId.isBlank()) return emptyMap()
        val records = runCatching { JSONObject(prefs?.getString("usage:$characterId", "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        return buildMap {
            val keys = records.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val item = records.optJSONObject(id) ?: continue
                put(id, MemoryRecallUsage(
                    successfulInjections = item.optInt("injections", 0).coerceAtLeast(0),
                    lastInjectedAt = item.optString("lastAt").takeIf(String::isNotBlank)
                        ?.let { runCatching { Instant.parse(it) }.getOrNull() },
                ))
            }
        }
    }

    @Synchronized
    fun stats(characterId: String, memoryId: String): MemoryRecallUsage {
        if (characterId.isBlank() || memoryId.isBlank()) return MemoryRecallUsage()
        val records = runCatching { JSONObject(prefs?.getString("usage:$characterId", "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        val record = records.optJSONObject(memoryId) ?: return MemoryRecallUsage()
        return MemoryRecallUsage(
            successfulInjections = record.optInt("injections", 0).coerceAtLeast(0),
            lastInjectedAt = record.optString("lastAt").takeIf(String::isNotBlank)
                ?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
    }

    /**
     * Call only after a real provider reply and final transport projection.
     * A candidate, truncated summary, failed API call or all-time core fact is NOT counted.
     */
    @Synchronized
    fun recordSuccessfulInjection(
        characterId: String, injectedIds: Collection<String>, at: Instant = Instant.now(),
    ) {
        val storage = prefs ?: return
        if (characterId.isBlank()) return
        val ids = injectedIds.asSequence().filter { it.startsWith("memory:") }
            .map { it.removePrefix("memory:") }.filter(String::isNotBlank).distinct().toList()
        if (ids.isEmpty()) return
        val key = "usage:$characterId"
        val records = runCatching { JSONObject(storage.getString(key, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        ids.forEach { memoryId ->
            val prior = records.optJSONObject(memoryId)
            val count = prior?.optInt("injections", 0)?.coerceAtLeast(0) ?: 0
            records.put(memoryId, JSONObject()
                .put("injections", if (count == Int.MAX_VALUE) count else count + 1)
                .put("lastAt", at.toString()))
        }
        check(storage.edit().putString(key, records.toString()).commit()) {
            "记忆召回统计写入失败"
        }
    }

    /** A reset/new life must not inherit private recall history from the old one. */
    @Synchronized
    fun clearCharacter(characterId: String) {
        if (characterId.isNotBlank()) prefs?.edit()?.remove("usage:$characterId")?.commit()
    }

    /** Remove private usage metadata with a user-deleted memory. */
    @Synchronized
    fun removeMemory(characterId: String, memoryId: String) {
        val storage = prefs ?: return
        val key = "usage:$characterId"
        val records = runCatching { JSONObject(storage.getString(key, "{}") ?: "{}") }.getOrNull() ?: return
        if (!records.has(memoryId)) return
        records.remove(memoryId)
        storage.edit().putString(key, records.toString()).commit()
    }
}
