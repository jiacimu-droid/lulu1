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

    /**
     * The model has the sole authority to interpret the date, time and which
     * prior promise to amend. This recovery runs only when the normal role
     * decision already produced a reply but lost or contradicted its structured
     * scheduling action. No parser can manufacture or override a deadline.
     */
    suspend fun applyModelRecovery(
        context: Context,
        characterId: String,
        sourceTurnId: String,
        evidenceIds: List<String>,
        drafts: List<CommitmentTaskDraft>,
    ): List<CommitmentTask> = mutex.withLock {
        if (evidenceIds.isEmpty() || characterId.isBlank()) return@withLock emptyList()
        CommitmentTaskStore.initialize(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val recoveryKey = "$characterId:$sourceTurnId"
        if (recoveryKey in prefs.getStringSet("recovery_handled", emptySet()).orEmpty())
            return@withLock emptyList()

        val before = CommitmentTaskStore.snapshot(characterId)
        val active = before.filter { it.status.isActive() }
        val sourceIds = evidenceIds.map { it.substringBefore(":group:") }.toSet()
        val proposed = drafts.filter { draft ->
            // Replaying a role's original reply must not create a second task.
            // To CHANGE a previous task the model must name its precise ID;
            // a program side date parser never supplies that missing decision.
            draft.action != "create" || active.none { existing ->
                existing.sourceEventIds.any { it.substringBefore(":group:") in sourceIds }
            }
        }
        proposed.forEach { draft ->
            if (draft.action != "create") require(active.any { it.id == draft.targetTaskId }) {
                "恢复决策没有选中有效约定ID，未更改其他日程"
            }
        }
        applyCommitmentTaskDrafts(characterId, sourceTurnId, evidenceIds, proposed)
        val changed = CommitmentTaskStore.snapshot(characterId).filter { after ->
            before.none { it.id == after.id && it.revision == after.revision }
        }
        val recovered = prefs.getStringSet("recovery_handled", emptySet())
            .orEmpty().toMutableList()
        recovered += recoveryKey
        check(prefs.edit().putStringSet("recovery_handled", recovered.takeLast(2000).toSet())
            .commit()) { "无法保存补救执行回执" }
        // The recovery path must trigger the same owner-selected follow-up as
        // the primary planner. Otherwise promises recovered after a delivered
        // bubble are saved but never re-enter the character's action loop.
        scheduleUndatedFollowThroughIfNeeded(context, characterId, changed)
        if (changed.isNotEmpty()) SharedExperienceTimeline.record(
            eventId = "commitment:model-recovery:$sourceTurnId",
            characterId = characterId, channel = "约定保存与执行回执",
            speaker = "执行器",
            content = changed.joinToString("\n") { task ->
                "model-selected id=${task.id}; dueAt=${task.dueAt}; goal=${task.goal}; status=${task.status}; actual=${task.readableLastResult()}"
            },
            source = "commitment-decision", evidenceKind = EventEvidenceKind.ToolResult,
            triggerExtraction = false,
        )
        changed
    }

    private fun scheduleUndatedFollowThroughIfNeeded(
        context: Context, characterId: String, changed: List<CommitmentTask>,
    ) {
        if (changed.any { it.needsUndatedActionReview() } &&
            RoleDayScheduleStore.undatedCommitmentsToPlan(context, characterId).isNotEmpty()) {
            // Save the accepted responsibility first; ask its owner to choose
            // an actual next action through the existing unified decision loop.
            // This never manufactures a clock time or promises an already-run tool.
            ProactivePerceptionScheduler.scheduleOwnIdeaFollowThrough(context, characterId)
        }
    }

    suspend fun apply(context: Context, characterId: String, sourceTurnId: String,
        evidenceIds: List<String>, changes: JSONArray): String = mutex.withLock {
        if (handled(context, characterId, evidenceIds)) return@withLock "本轮约定变更已有保存回执，未重复执行。"
        CommitmentTaskStore.initialize(context)
        val active = CommitmentTaskStore.active(characterId)
        val drafts = CommitmentDecisionProtocol.drafts(changes, active)
        val completed = drafts.filter { it.action == "complete" }.mapNotNull { it.targetTaskId }.toSet()
        val before = CommitmentTaskStore.snapshot(characterId).associateBy(CommitmentTask::id)
        val outcomes = applyCommitmentTaskDrafts(characterId, sourceTurnId, evidenceIds, drafts)
        // A rejected completion suggestion cannot change music/alarms.
        if (evidenceIds.isNotEmpty() && CommitmentTaskStore.snapshot(characterId).any {
                it.id in completed && it.status == CommitmentTaskStatus.Completed &&
                    before[it.id]?.status != CommitmentTaskStatus.Completed && it.isWakeResponsibility()
            }) WakeMusicCoordinator.onModelConfirmedWake(context, characterId)
        // Once the model verifies a nightly occurrence, keep the original
        // recurring promise alive without adding another independent task.
        CommitmentTaskStore.snapshot(characterId).filter { task ->
            task.id in completed && task.repeat == "daily" &&
                task.status == CommitmentTaskStatus.Completed
        }.forEach { task ->
            CommitmentTaskStore.advanceDailyAfterAttempt(context, task.id, task.executionToken)
        }
        val changed = CommitmentTaskStore.snapshot(characterId).filter { before[it.id]?.revision != it.revision }
        scheduleUndatedFollowThroughIfNeeded(context, characterId, changed)
        if (evidenceIds.isNotEmpty()) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getStringSet("handled", emptySet()).orEmpty().toMutableList()
            saved += evidenceIds.map { "$characterId:${it.substringBefore(":group:")}" }
            check(prefs.edit().putStringSet("handled", saved.takeLast(2000).toSet()).commit())
        }
        // A failed draft used to collapse into the generic "nothing
        // changed" line, hiding which change failed and why. Name each
        // failure so the next turn can correct the claim instead of
        // repeating that the change was made.
        val failedLines = outcomes.filter { it.appliedTaskId == null }.map {
            "未落成：${it.action}「${it.goal}」——${it.note}"
        }
        val changedLines = changed.map {
            "id=${it.id}; 关联记录id=${it.lexiconEntryId}; goal=${it.goal}; dueAt=${it.dueAt}; status=${it.status}; 实际结果=${it.readableLastResult()}"
        }
        val receipt = if (drafts.isEmpty()) "本轮模型判断没有约定变化。"
            else (changedLines + failedLines).joinToString("\n")
                .ifBlank { "未新增或更改任务，已有记录保持原状。" }
        if (drafts.isNotEmpty()) SharedExperienceTimeline.record(
            eventId = "commitment:$sourceTurnId", characterId = characterId, channel = "约定保存与执行回执",
            speaker = "执行器", content = receipt, occurredAt = Instant.now(), source = "commitment-decision",
            evidenceKind = EventEvidenceKind.ToolResult, triggerExtraction = false,
        )
        receipt
    }
}

