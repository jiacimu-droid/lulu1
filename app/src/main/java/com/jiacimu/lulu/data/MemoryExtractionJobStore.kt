package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class MemoryExtractionJob(
    val id: String,
    val characterId: String,
    val conversationId: String = "",
    val sourceReplyId: String = "",
    val revision: Long = 1,
    val attempts: Int = 0,
    val lastError: String = "",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

internal object MemoryExtractionJobStore {
    private const val PREFS_NAME = "lulu_memory_extraction_jobs"
    private const val KEY_JOBS = "jobs_v1"
    private var prefs: android.content.SharedPreferences? = null
    private var jobs: List<MemoryExtractionJob> = emptyList()

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        jobs = decode(prefs?.getString(KEY_JOBS, null)).groupBy { it.characterId }.map { (characterId, old) ->
            val latest = old.maxBy { it.updatedAt }
            latest.copy(id = "memory:$characterId", revision = old.sumOf { it.revision },
                createdAt = old.minOf { it.createdAt })
        }
        persist()
    }

    @Synchronized
    fun pending(): List<MemoryExtractionJob> = jobs.sortedBy(MemoryExtractionJob::createdAt)

    @Synchronized
    fun enqueue(characterId: String, conversationId: String = "", sourceReplyId: String = ""): MemoryExtractionJob {
        require(characterId.isNotBlank())
        val old = jobs.firstOrNull { it.characterId == characterId }
        val now = Instant.now()
        val job = MemoryExtractionJob(
            id = "memory:$characterId",
            characterId = characterId,
            conversationId = conversationId,
            sourceReplyId = sourceReplyId,
            revision = (old?.revision ?: 0) + 1,
            attempts = old?.attempts ?: 0,
            lastError = old?.lastError.orEmpty(),
            createdAt = old?.createdAt ?: now,
            // A new event does not erase a failing extractor's backoff.
            updatedAt = old?.updatedAt ?: now,
        )
        jobs = jobs.filterNot { it.characterId == characterId } + job
        persist()
        return job
    }

    @Synchronized
    fun failed(id: String, error: Throwable) {
        jobs = jobs.map { job ->
            if (job.id != id) job else job.copy(
                attempts = job.attempts + 1,
                lastError = error.message.orEmpty().take(500),
                updatedAt = Instant.now(),
            )
        }
        persist()
    }

    @Synchronized
    fun complete(id: String, revision: Long? = null) {
        jobs = jobs.filterNot { it.id == id && (revision == null || it.revision == revision) }
        persist()
    }

    @Synchronized
    fun removeBySourceEvent(eventId: String) {
        if (eventId.isBlank()) return
        // A coalesced job may contain changes from many sources. Removing the latest
        // source must not discard other pending work; source validity is checked at commit.
    }

    @Synchronized
    fun clearCharacter(characterId: String) {
        jobs = jobs.filterNot { it.characterId == characterId }
        persist()
    }

    private fun persist() {
        val array = JSONArray().apply {
            jobs.forEach { job ->
                put(JSONObject()
                    .put("id", job.id)
                    .put("characterId", job.characterId)
                    .put("conversationId", job.conversationId)
                    .put("sourceReplyId", job.sourceReplyId)
                    .put("revision", job.revision)
                    .put("attempts", job.attempts)
                    .put("lastError", job.lastError)
                    .put("createdAt", job.createdAt.toString())
                    .put("updatedAt", job.updatedAt.toString()))
            }
        }
        prefs?.let { check(it.edit().putString(KEY_JOBS, array.toString()).commit()) { "记忆整理队列保存失败" } }
    }

    private fun decode(raw: String?): List<MemoryExtractionJob> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val characterId = item.optString("characterId").trim()
                    if (characterId.isBlank()) continue
                    add(MemoryExtractionJob(
                        id = item.optString("id").ifBlank { "$characterId:${item.optString("sourceReplyId")}" },
                        characterId = characterId,
                        revision = item.optLong("revision", 1),
                        conversationId = item.optString("conversationId"),
                        sourceReplyId = item.optString("sourceReplyId"),
                        attempts = item.optInt("attempts", 0),
                        lastError = item.optString("lastError"),
                        createdAt = item.optString("createdAt").toJobInstant(),
                        updatedAt = item.optString("updatedAt").toJobInstant(),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }
}

private fun String.toJobInstant(): Instant = runCatching { Instant.parse(this) }.getOrDefault(Instant.now())
