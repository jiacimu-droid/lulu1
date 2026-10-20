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
     * Repair an accepted, explicit wake-up promise which the main model omitted
     * from commitmentChanges (including a model decision already marked handled).
     *
     * Runs ONLY after actual user and role messages are delivered. The character
     * (original planner or focused model recovery) must SELECT the wake method.
     * This verifier ONLY checks time, responsibility, existing tasks and delivery
     * evidence; it must never silently turn "wake me" into a chat message.
     */
    suspend fun ensureAcceptedWake(
        context: Context,
        characterId: String,
        sourceTurnId: String,
        evidenceIds: List<String>,
        userText: String,
        roleText: String,
        userAt: Instant,
        now: Instant = Instant.now(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        selectedDeliveryAction: String? = null,
    ): CommitmentTask? = mutex.withLock {
        if (characterId.isBlank() || evidenceIds.isEmpty() || sourceTurnId.isBlank()) return@withLock null
        val normalizedUser = userText.trim()
        val proposed = RelativeWakeCommitmentParser.parse(normalizedUser, roleText, userAt, zone)
            ?: WakeCommitmentParser.parse(normalizedUser, roleText, userAt, zone)
            ?: return@withLock null
        val due = proposed.dueAt ?: return@withLock null
        val requestedPhone = proposed.deliveryAction == "start_call"

        CommitmentTaskStore.initialize(context)
        val all = CommitmentTaskStore.snapshot(characterId)
        val sourceIds = evidenceIds.map { it.substringBefore(":group:") }.toSet()
        // Treat a model-created task with this original message as the same
        // responsibility. A blocked task should remain visibly blocked; never
        // hide it by creating a second alarm with the same deadline.
        val matchingSource = all.firstOrNull { task ->
            task.isWakeResponsibility() && task.sourceEventIds.any {
                it.substringBefore(":group:") in sourceIds
            }
        }
        val sameDeadline = all.firstOrNull { task ->
            task.isWakeResponsibility() && task.dueAt != null &&
                kotlin.math.abs(java.time.Duration.between(task.dueAt, due).seconds) <= 30L &&
                task.deliveryAction in setOf("start_call", "alarm_only") &&
                (!requestedPhone || task.deliveryAction == "start_call") &&
                task.status != CommitmentTaskStatus.Cancelled
        }
        fun hasValidAlarm(task: CommitmentTask): Boolean =
            task.deliveryAction in setOf("start_call", "alarm_only") &&
                (!requestedPhone || task.deliveryAction == "start_call") &&
                task.status != CommitmentTaskStatus.NeedsClarification &&
                !(task.status == CommitmentTaskStatus.Scheduled &&
                    task.linkedAlarmId.isNullOrBlank())
        // If the model guessed a different deadline for the SAME user turn,
        // the explicit accepted relative time wins. Do not keep the wrong alarm.
        val sourceDeadlineAccurate = matchingSource?.dueAt?.let {
            kotlin.math.abs(java.time.Duration.between(it, due).seconds) <= 30L
        } == true
        if (matchingSource != null && hasValidAlarm(matchingSource) &&
            (matchingSource.status != CommitmentTaskStatus.Scheduled ||
                sourceDeadlineAccurate))
            return@withLock matchingSource
        if (sameDeadline != null && hasValidAlarm(sameDeadline))
            return@withLock sameDeadline

        // Never register an old alarm retrospectively when opening an old chat.
        if (!due.isAfter(now.plusSeconds(5))) return@withLock null
        // Only model-selected audible methods qualify as waking someone up.
        // A private text bubble is not proof of an audible wake. No program-side
        // preference, keyword default or quiet automatic downgrade.
        val chosen = selectedDeliveryAction?.takeIf { it in setOf("start_call", "alarm_only") }
            ?: return@withLock null
        if (requestedPhone && chosen != "start_call") return@withLock null

        val wantsReschedule = listOf("再叫我", "改成", "改到", "改为", "推迟", "延后")
            .any(normalizedUser::contains)
        val prior = when {
            matchingSource != null -> matchingSource
            sameDeadline != null -> sameDeadline
            wantsReschedule -> all.filter { it.status.isActive() && it.isWakeResponsibility() }
                .singleOrNull()
            else -> null
        }
        val change = if (prior != null) proposed.copy(
            action = "reschedule", targetTaskId = prior.id,
            deliveryAction = chosen,
        ) else proposed.copy(deliveryAction = chosen)
        applyCommitmentTaskDrafts(characterId, sourceTurnId, evidenceIds, listOf(change))
        val created = CommitmentTaskStore.snapshot(characterId).firstOrNull { task ->
            if (prior != null) task.id == prior.id
            else task.isWakeResponsibility() &&
                task.sourceTurnId == sourceTurnId &&
                task.sourceEventIds.any { it.substringBefore(":group:") in sourceIds }
        } ?: throw IllegalStateException("接受了明确叫醒约定，但承诺任务没有保存")
        val receipt = "目标=${created.goal}；时间=${created.dueAt}；" +
            "状态=${created.status}；闹钟=${created.linkedAlarmId ?: "未建立"}；" +
            "执行结果=${created.lastActionResult}"
        SharedExperienceTimeline.record(
            eventId = "commitment:wake-confirm:$sourceTurnId",
            characterId = characterId,
            channel = "约定保存与执行回执",
            speaker = "执行器",
            content = receipt,
            occurredAt = now,
            source = "commitment-decision",
            evidenceKind = EventEvidenceKind.ToolResult,
            triggerExtraction = false,
        )
        created
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
