package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.time.Instant

/** Applies an explicit model decision, never inferred from private thoughts or reply keywords. */
internal object CommitmentDecisionRuntime {
    private val mutex = Mutex()
    private const val PREFS = "lulu_semantic_commitment_turns"

    fun handled(context: Context, characterId: String, evidenceIds: List<String>): Boolean {
        if (evidenceIds.isEmpty()) return false
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("handled", emptySet()).orEmpty()
        return evidenceIds.all { "$characterId:${it.substringBefore(":group:")}" in saved }
    }

    suspend fun apply(context: Context, characterId: String, sourceTurnId: String,
        evidenceIds: List<String>, changes: JSONArray): String = mutex.withLock {
        if (handled(context, characterId, evidenceIds)) return@withLock "本轮约定变更已有保存回执，未重复执行。"
        CommitmentTaskStore.initialize(context)
        val active = CommitmentTaskStore.active(characterId)
        val drafts = CommitmentDecisionProtocol.drafts(changes, active)
        val completed = drafts.filter { it.action == "complete" }.mapNotNull { it.targetTaskId }.toSet()
        if (completed.isNotEmpty()) {
            val userText = SharedExperienceTimeline.eventsByIds(characterId, evidenceIds)
                .filter { it.evidenceKind == EventEvidenceKind.UserStatement }.joinToString("\n") { it.content }
            // Preserve the existing music handoff, only after a model-owned completion
            // decision and an actual user acknowledgement, never merely an answered call.
            WakeMusicCoordinator.onExplicitWakeConfirmation(context, characterId, userText,
                active.filter { it.id in completed })
        }
        val before = CommitmentTaskStore.snapshot(characterId).associate { it.id to it.revision }
        applyCommitmentTaskDrafts(characterId, sourceTurnId, evidenceIds, drafts)
        val changed = CommitmentTaskStore.snapshot(characterId).filter { before[it.id] != it.revision }
        if (evidenceIds.isNotEmpty()) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getStringSet("handled", emptySet()).orEmpty().toMutableList()
            saved += evidenceIds.map { "$characterId:${it.substringBefore(":group:")}" }
            check(prefs.edit().putStringSet("handled", saved.takeLast(2000).toSet()).commit())
        }
        val receipt = if (drafts.isEmpty()) "本轮模型判断没有约定变化。" else changed.joinToString("\n") {
            "id=${it.id}; 辞海id=${it.lexiconEntryId}; goal=${it.goal}; dueAt=${it.dueAt}; status=${it.status}; 实际结果=${it.lastActionResult}"
        }.ifBlank { "未新增或更改任务，已有记录保持原状。" }
        if (drafts.isNotEmpty()) SharedExperienceTimeline.record(
            eventId = "commitment:$sourceTurnId", characterId = characterId, channel = "约定保存与执行回执",
            speaker = "执行器", content = receipt, occurredAt = Instant.now(), source = "commitment-decision",
            evidenceKind = EventEvidenceKind.ToolResult, triggerExtraction = false,
        )
        receipt
    }
}
