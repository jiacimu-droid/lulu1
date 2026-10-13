package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.MemoryEntry
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Role-world permanent memory is a separate, evidence-checked prompt channel. */
internal object CoreMemoryDelivery {
    fun active(characterId: String): List<MemoryEntry> =
        if (characterId.isBlank()) emptyList() else LuluRepositories.memory.snapshot(characterId)
            .filter { it.isCoreMemory() && MemoryValidityStore.isActive(it.id) &&
                memoryHasLiveSources(it, characterId) }

    fun render(memories: List<MemoryEntry>): String =
        if (memories.isEmpty()) "" else buildString {
            appendLine("【持续生效的核心记忆｜独立于普通召回名额】")
            appendLine("仅在有关时自然使用，不逐条复述；当前明确纠正优先，临时状态不覆盖长期事实。")
            memories.forEach { appendLine("- ${it.content.trim().replace("\n", " ")}") }
        }.trim()
}

/** A record of SUCCESSFUL character-model requests, not a guess based on recall.
 * Stores only IDs/metadata, never the private prompt, response or API key.
 * History survives app restart. Expired/deleted sources remain *historical* IDs,
 * and are not reintroduced into the next prompt. */
internal data class CoreMemoryInjectionReceipt(
    val at: Instant,
    val source: String,
    val contextMode: String,
    val coreIds: List<String>,
)

internal object CoreMemoryInjectionAudit {
    private const val PREFS = "lulu_core_memory_injection_audit_v1"
    private const val MAX_HISTORY = 12
    @Volatile private var preferences: android.content.SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun recent(characterId: String): List<CoreMemoryInjectionReceipt> {
        if (characterId.isBlank()) return emptyList()
        val raw = preferences?.getString("role:$characterId", null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val at = runCatching { Instant.parse(item.optString("at")) }.getOrNull()
                    ?: return@mapNotNull null
                val ids = item.optJSONArray("ids") ?: JSONArray()
                CoreMemoryInjectionReceipt(at, item.optString("source"),
                    item.optString("mode"), (0 until ids.length()).map { ids.optString(it) }
                        .filter(String::isNotBlank).distinct())
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun recordSuccess(
        characterId: String,
        source: String,
        contextMode: String,
        coreIds: List<String>,
        at: Instant = Instant.now(),
    ) {
        if (characterId.isBlank()) return
        if (preferences == null) return
        // A successful request with zero valid Core entries is still useful.
        val records = (listOf(CoreMemoryInjectionReceipt(at, source.take(100),
            contextMode.take(60), coreIds.distinct())) + recent(characterId)).take(MAX_HISTORY)
        persist(characterId, records)
    }

    /** Privacy invariant: deleting a memory also removes its IDs from audit history. */
    @Synchronized fun removeMemory(characterId: String, memoryId: String) {
        if (characterId.isBlank() || memoryId.isBlank()) return
        val old = recent(characterId)
        if (old.none { memoryId in it.coreIds }) return
        persist(characterId, old.map { it.copy(coreIds = it.coreIds - memoryId) })
    }

    /** A full character reset must forget the audit too. */
    @Synchronized fun clearCharacter(characterId: String) {
        if (characterId.isNotBlank()) preferences?.edit()?.remove("role:$characterId")?.apply()
    }

    private fun persist(characterId: String, records: List<CoreMemoryInjectionReceipt>) {
        val prefs = preferences ?: return
        val array = JSONArray()
        records.take(MAX_HISTORY).forEach { record ->
            val ids = JSONArray()
            record.coreIds.forEach { ids.put(it) }
            array.put(JSONObject().put("at", record.at.toString())
                .put("source", record.source).put("mode", record.contextMode)
                .put("ids", ids))
        }
        prefs.edit().putString("role:$characterId", array.toString()).apply()
    }
}
