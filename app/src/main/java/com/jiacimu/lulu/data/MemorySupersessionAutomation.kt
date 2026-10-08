package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Detects explicit corrections/updates between stable Fact memories.
 *
 * Old facts are never deleted. When a newer fact clearly makes an older fact no longer current,
 * MemoryValidityStore records oldId -> newId and normal recall ignores the old one.
 */
internal object MemorySupersessionAutomation {
    private const val PREFS_NAME = "lulu_memory_supersession_automation_v1"
    private const val KEY_BASELINED = "baselined_characters"
    private const val KEY_PROCESSED = "processed_fact_ids"
    private const val MAX_PROCESSED = 2_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()
    private var prefs: android.content.SharedPreferences? = null
    private var started = false
    private val inspectionLock = Mutex()

    @Synchronized
    fun initialize(context: Context) {
        if (started) return
        started = true
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        scope.launch {
            while (true) { delay(60_000); runCatching { retryPending() } }
        }
        scope.launch {
            MigratedDomainStores.characters.settings.collect { characters ->
                val ids = characters.keys
                jobs.keys.filterNot(ids::contains).forEach { jobs.remove(it)?.cancel() }
                ids.forEach { characterId ->
                    if (characterId in jobs) return@forEach
                    jobs[characterId] = scope.launch {
                        LuluRepositories.memory.observeMemories(characterId).collect { memories ->
                            inspectionLock.withLock { inspect(characterId, memories) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun retryPending() {
        MigratedDomainStores.characters.settings.value.keys.forEach { characterId ->
            val memories = LuluRepositories.memory.observeMemories(characterId)
            val snapshot = memories.first()
            inspectionLock.withLock { inspect(characterId, snapshot) }
        }
    }

    private suspend fun inspect(characterId: String, memories: List<MemoryEntry>) {
        val facts = memories.filter { it.kind == MemoryKind.Fact }
        if (facts.isEmpty()) return

        val baselined = prefs?.getStringSet(KEY_BASELINED, emptySet()).orEmpty()
        if (characterId !in baselined) {
            // Existing libraries predate validity tracking. Do not reinterpret historical archives in
            // bulk on migration; start tracking from facts created after this feature is installed.
            rememberProcessed(facts.map(MemoryEntry::id))
            prefs?.edit()?.putStringSet(KEY_BASELINED, baselined + characterId)?.apply()
            return
        }

        val processed = prefs?.getStringSet(KEY_PROCESSED, emptySet()).orEmpty()
        val pending = facts
            .filterNot { it.id in processed }
            .sortedBy(MemoryEntry::createdAt)
        for (newFact in pending) {
            val older = facts.filter { old ->
                old.id != newFact.id &&
                    old.createdAt <= newFact.createdAt &&
                    MemoryValidityStore.isActive(old.id)
            }
            val candidates = older
                .map { old -> old to semanticOverlap(old.content, newFact.content) }
                .filter { (_, overlap) -> overlap > 0.0 }
                .sortedByDescending(Pair<MemoryEntry, Double>::second)
                .take(12)
                .map(Pair<MemoryEntry, Double>::first)
            if (candidates.isEmpty() || determineSuperseded(characterId, newFact, candidates)) {
                rememberProcessed(listOf(newFact.id))
            }
        }
    }

    private suspend fun determineSuperseded(
        characterId: String,
        newFact: MemoryEntry,
        candidates: List<MemoryEntry>,
    ): Boolean {
        val facts = buildString {
            appendLine("新事实：")
            appendLine("memoryId=${newFact.id}｜${newFact.content}")
            appendLine("\n可能相关的旧事实：")
            candidates.forEach { old -> appendLine("memoryId=${old.id}｜${old.content}") }
        }
        val result = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = facts,
            instruction = """
                判断“新事实”是否明确纠正、更新或替代某条旧事实，使旧事实不再能作为当前事实使用。
                只返回 JSON：{"supersedes":["旧memoryId"]}。
                严格规则：
                1. 仅仅主题相似、补充更多细节、同时成立、发生在不同时间、情绪变化，都不算替代。
                2. 必须能从文字确认同一个事实槽位发生了纠正/变化，例如旧偏好被明确改口、旧计划被新计划取代、旧身份信息被明确纠正。
                3. 历史上曾经为真的事实后来改变时，可以替代其“当前有效性”，但旧事实仍保留为历史记录。
                4. 不确定就返回空数组。不得猜测。
                5. 只能输出候选中真实存在的旧memoryId。
            """.trimIndent(),
            source = "记忆有效性",
            title = "事实替代判断",
            maxTokens = 260,
            connectionOverride = MemoryModelRuntime.extractionConnection(),
            contextMode = CompanionContextMode.Isolated,
        ).getOrNull() ?: return false
        val allowed = candidates.mapTo(mutableSetOf(), MemoryEntry::id)
        val ids = parseIds(result.text)?.filter(allowed::contains) ?: return false
        val current = LuluRepositories.memory.snapshot(characterId).mapTo(mutableSetOf(), MemoryEntry::id)
        if (newFact.id !in current || !memoryHasLiveSources(newFact, characterId)) return false
        if (ids.isNotEmpty()) MemoryValidityStore.markSuperseded(ids.filter(current::contains), newFact.id)
        return true
    }

    private fun parseIds(raw: String): List<String>? = runCatching {
        val clean = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val array = JSONObject(clean).getJSONArray("supersedes")
        buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct()
    }.getOrNull()

    private fun rememberProcessed(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val current = prefs?.getStringSet(KEY_PROCESSED, emptySet()).orEmpty().toMutableList()
        current += ids
        prefs?.edit()?.putStringSet(KEY_PROCESSED, current.distinct().takeLast(MAX_PROCESSED).toSet())?.apply()
    }

    private fun semanticOverlap(left: String, right: String): Double {
        val a = terms(left)
        val b = terms(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / minOf(a.size, b.size).coerceAtLeast(1).toDouble()
    }

    private fun terms(text: String): Set<String> {
        val normalized = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        if (normalized.isBlank()) return emptySet()
        return buildSet {
            normalized.split(Regex("\\s+")).forEach { token ->
                if (token.length >= 2) add(token)
                if (token.any { it.code > 127 }) {
                    token.windowed(2, 1, false).forEach(::add)
                    token.windowed(3, 1, false).forEach(::add)
                }
            }
        }
    }
}
