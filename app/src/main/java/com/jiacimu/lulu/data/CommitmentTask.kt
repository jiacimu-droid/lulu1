package com.jiacimu.lulu.data

import java.time.Instant
import java.util.UUID

data class CommitmentTask(
    val id: String = UUID.randomUUID().toString(),
    val characterId: String,
    val lexiconEntryId: String? = null,
    val sourceEventIds: List<String> = emptyList(),
    val sourceTurnId: String? = null,
    val goal: String,
    val status: CommitmentTaskStatus = CommitmentTaskStatus.NeedsClarification,
    val dueAt: Instant? = null,
    val timezone: String? = null,
    val nextCheckAt: Instant? = null,
    val completionCondition: String = "",
    val steps: List<String> = emptyList(),
    /** Concrete action promised by the role; a call must not silently become a chat message. */
    val deliveryAction: String = "send_private_message",
    val currentStep: Int = 0,
    val attemptCount: Int = 0,
    val lastActionResult: String = "",
    val revision: Long = 1L,
    val linkedAlarmId: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

internal fun CommitmentTaskStatus.isActive(): Boolean = this in setOf(
    CommitmentTaskStatus.NeedsClarification,
    CommitmentTaskStatus.Scheduled,
    CommitmentTaskStatus.Running,
    CommitmentTaskStatus.WaitingForFeedback,
    CommitmentTaskStatus.Blocked,
)
