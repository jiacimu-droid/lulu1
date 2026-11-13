package com.jiacimu.lulu.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject

/** Every bubble in one typed phone turn keeps its original identity and revision. */
internal object PhoneReadRequestEvidence {
    fun capture(role: String, conversation: String, ids: List<String>): List<SharedTimelineEvent>? {
        if (ids.isEmpty() || ids.any(String::isBlank) || ids.size != ids.distinct().size) return null
        val events = SharedExperienceTimeline.eventsByIds(role, ids).associateBy { it.id }
        return ids.map { id -> events[id]?.takeIf {
            it.evidenceKind == EventEvidenceKind.UserStatement && it.sessionId == conversation
        } ?: return null }
    }

    fun encode(events: List<SharedTimelineEvent>): String = JSONArray().apply {
        events.forEach { put(JSONObject().put("id", it.id).put("revision", it.revision)) }
    }.toString()

    fun restore(role: String, conversation: String, expected: JSONArray): List<SharedTimelineEvent>? {
        val ids = (0 until expected.length()).map { expected.optJSONObject(it)?.optString("id").orEmpty() }
        val sources = capture(role, conversation, ids) ?: return null
        return sources.takeIf { sources.withIndex().all { (i, source) ->
            source.revision == expected.optJSONObject(i)?.optLong("revision", -1L)
        } }
    }
}

/**
 * Kept solely so WorkManager can deserialize and settle previously queued jobs
 * after an upgrade. No new job can be enqueued, and this class never invokes
 * the retired XHS reader or posts a synthetic "failed" chat bubble.
 */
class PhoneXhsReadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}
