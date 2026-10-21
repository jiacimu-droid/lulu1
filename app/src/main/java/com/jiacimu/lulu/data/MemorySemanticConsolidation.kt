package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.security.MessageDigest

/** Scheduling metadata only; no parallel store for facts or personality. */
internal object MemorySemanticConsolidation {
    private const val PREFS_NAME = "lulu_memory_maintenance_schedule_v1"
    private val lock = Mutex()
    private var prefs: android.content.SharedPreferences? = null

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Opportunistic and restart-safe: work missed while Android stopped us runs on next launch. */
    suspend fun maintainWhenDue(characterId: String) {
        if (characterId.isBlank()) return
        lock.withLock {
            val storage = prefs ?: return@withLock
            if (!LuluRepositories.memory.observePolicy(characterId).first().autoSummarize) return@withLock
            val now = Instant.now()
            val dailyKey = "daily:$characterId"
            if (due(storage.getString(dailyKey, null), now, Duration.ofDays(1))) {
                LuluRepositories.memory.maintain(characterId)
                check(storage.edit().putString(dailyKey, now.toString()).commit()) { "每日维护进度保存失败" }
            }
            val weeklyKey = "weekly:$characterId"
            val previous = storage.getString(weeklyKey, null)
            if (previous == null) {
                // Upgrade must not unexpectedly start a paid full-library review.
                storage.edit().putString(weeklyKey, now.toString()).commit()
                return@withLock
            }
            if (due(previous, now, Duration.ofDays(7))) {
                if (MemoryModelRuntime.extractionConnection() == null) return@withLock
                val retryKey = "weeklyAttempt:$characterId"
                if (!due(storage.getString(retryKey, null), now, Duration.ofHours(12))) return@withLock
                // Record attempts BEFORE the paid call to avoid hourly retry storms after crashes.
                check(storage.edit().putString(retryKey, now.toString()).commit()) { "每周尝试时间保存失败" }
                consolidateUnderLock(characterId, false)
                check(storage.edit().putString(weeklyKey, now.toString()).remove(retryKey).commit()) { "每周维护进度保存失败" }
            }
        }
    }

    fun pendingBatches(characterId: String): Int = prefs?.getInt("remaining:$characterId", 0) ?: 0

    /** Manual action reviews the existing backlog; old evidence stays inspectable. */
    suspend fun consolidateNow(characterId: String): Int = lock.withLock {
        require(characterId.isNotBlank())
        val archived = consolidateUnderLock(characterId, true)
        prefs?.edit()?.putString("weekly:$characterId", Instant.now().toString())?.commit()
        archived
    }

    private suspend fun consolidateUnderLock(characterId: String, force: Boolean): Int {
        val connection = MemoryModelRuntime.extractionConnection()
            ?: error("请先配置后台「记忆提取」模型，防止误用前台聊天模型付费")
        val storage = prefs ?: error("记忆维护尚未初始化")
        val facts = candidates(characterId)
        if (facts.size < 2) return 0
        val fingerprint = fingerprint(facts)
        val windows = facts.windowed(size = 70, step = 50, partialWindows = true)
        val cursorKey = "cursor:$characterId"
        val start = storage.getInt(cursorKey, 0).coerceIn(0, windows.lastIndex)
        if (start == 0 && storage.getString("digest:$characterId", null) == fingerprint) return 0

        // Bound total paid work per maintenance run. Large libraries progress across checkpoints.
        val end = (start + if (force) 3 else 2).coerceAtMost(windows.size)
        var archived = 0
        for (window in windows.subList(start, end)) {
            if (window.size < 2) continue
            val evidence = window.joinToString("\n") { memory ->
                "id=${memory.id} | 对象=${memory.subject} | 槽位=${memory.slot} | 时间=${memory.occurredAt ?: memory.createdAt} | ${memory.content.replace('\n', ' ')}"
            }
            val reply = LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = evidence,
                instruction = """
                    仅对当前列表的长期事实做严格语义去重，不修改、不推测任何事实。
                    输出完整 JSON 对象 {"groups":[["idA","idB"]]}，没有可确证的重复返回 {"groups":[]}。
                    每组至少两个真实 id，同一 id 不得出现在两组。
                    只把人物、含义、条件和适用时间完全一致的事实归为一组。
                    相似主题、不同时间的重复经历、相反偏好、纠正和事实过期、主观印象、互相补充的细节都不是重复。
                    不确定就不合并。无需输出说明。
                """.trimIndent(),
                source = "记忆维护", title = "长期事实语义去重", maxTokens = 2_000,
                contextMode = CompanionContextMode.Isolated, connectionOverride = connection,
            ).getOrThrow()
            // A malformed provider response is a retryable failure, not "no duplicates".
            val cleanReply = reply.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            check(runCatching { JSONObject(cleanReply).getJSONArray("groups") }.isSuccess) {
                "记忆语义审核结果格式错误，将在后续重试"
            }
            val groups = parseGroups(reply.text, window.mapTo(mutableSetOf(), MemoryEntry::id))
            for (group in groups) {
                synchronized(SharedExperienceTimeline) {
                    // Do not commit stale model judgments after an edit, deletion or extraction.
                    val current = candidates(characterId).filter { it.id in group }
                    val originallyReviewed = window.filter { it.id in group }
                    if (current.size != group.size || current.toSet() != originallyReviewed.toSet() ||
                        current.size < 2 || !equivalentScope(current)) return@synchronized
                    val keeper = current.sortedWith(compareByDescending<MemoryEntry> { it.isCoreMemory() }
                        .thenByDescending { it.strength }.thenByDescending { it.createdAt }).first()
                    val redundant = current.filter { it.id != keeper.id }
                    if (redundant.isNotEmpty()) {
                        MemoryValidityStore.markEquivalent(redundant.map { it.id }, keeper.id)
                        archived += redundant.size
                    }
                }
            }
        }
        val editor = storage.edit().putInt("remaining:$characterId", windows.size - end)
            .putInt(cursorKey, if (end == windows.size) 0 else end)
        if (end == windows.size) editor.putString("digest:$characterId", fingerprint(candidates(characterId)))
        else editor.remove("digest:$characterId")
        check(editor.commit()) { "语义整理检查点保存失败" }
        return archived
    }

    private fun candidates(characterId: String): List<MemoryEntry> =
        LuluRepositories.memory.snapshot(characterId).filter {
            it.kind == MemoryKind.Fact && it.canRecallProactively &&
                it.source.startsWith("timeline-events:") && MemoryValidityStore.isActive(it.id) &&
                memoryHasLiveSources(it, characterId)
        }.sortedWith(compareBy<MemoryEntry> { it.subject }.thenBy { it.slot }.thenBy { it.content })

    private fun fingerprint(memories: List<MemoryEntry>): String {
        val bytes = memories.joinToString("|") { "${it.id}:${it.content}:${it.sourceRevisions}" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun equivalentScope(memories: List<MemoryEntry>): Boolean =
        memories.map { it.characterId }.distinct().size == 1 &&
            memories.map { it.subject.trim() }.filter(String::isNotBlank).distinct().size <= 1 &&
            memories.map { it.slot.trim() }.filter(String::isNotBlank).distinct().size <= 1

    internal fun parseGroups(raw: String, allowed: Set<String>): List<List<String>> = runCatching {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val groups = JSONObject(clean).getJSONArray("groups")
        val seen = mutableSetOf<String>()
        buildList {
            for (index in 0 until groups.length()) {
                val array = groups.optJSONArray(index) ?: continue
                val ids = (0 until array.length()).map { array.optString(it).trim() }.distinct()
                if (ids.size >= 2 && ids.all { it in allowed && it !in seen }) {
                    add(ids)
                    seen.addAll(ids)
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun due(previous: String?, now: Instant, interval: Duration): Boolean =
        previous == null || runCatching { Duration.between(Instant.parse(previous), now) >= interval }.getOrDefault(true)
}
