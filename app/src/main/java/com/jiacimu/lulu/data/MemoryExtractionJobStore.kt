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
        jobs = decode(prefs?.getString(KEY_JOBS, null))
    }

    @Synchronized
    fun pending(): List<MemoryExtractionJob> = jobs.sortedBy(MemoryExtractionJob::createdAt)

    @Synchronized
    fun enqueue(characterId: String, conversationId: String = "", sourceReplyId: String = ""): MemoryExtractionJob {
        val id = listOf(characterId, conversationId, sourceReplyId).joinToString(":")
        jobs.firstOrNull { it.id == id }?.let { return it }
        val job = MemoryExtractionJob(
            id = id,
            characterId = characterId,
            conversationId = conversationId,
            sourceReplyId = sourceReplyId,
        )
        jobs = jobs + job
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
    fun complete(id: String) {
        jobs = jobs.filterNot { it.id == id }
        persist()
    }

    @Synchronized
    fun clearCharacter(characterId: String) {
        jobs = jobs.filterNot { it.characterId == characterId }
        persist()
    }

    private fun persist() {
        val array = JSONArray().apply {
            jobs.takeLast(300).forEach { job ->
                put(JSONObject()
                    .put("id", job.id)
                    .put("characterId", job.characterId)
                    .put("conversationId", job.conversationId)
                    .put("sourceReplyId", job.sourceReplyId)
                    .put("attempts", job.attempts)
                    .put("lastError", job.lastError)
                    .put("createdAt", job.createdAt.toString())
                    .put("updatedAt", job.updatedAt.toString()))
            }
        }
        prefs?.edit()?.putString(KEY_JOBS, array.toString())?.commit()
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
