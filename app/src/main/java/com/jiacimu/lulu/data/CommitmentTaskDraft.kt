package com.jiacimu.lulu.data

import java.time.Instant

data class CommitmentTaskDraft(
    val action: String,
    val goal: String,
    val dueAt: Instant? = null,
    val timezone: String? = null,
    val completionCondition: String = "",
    val verificationMode: String? = null,
    val completionEvidenceIds: List<String> = emptyList(),
    val verificationReason: String = "",
    val steps: List<String> = emptyList(),
    val deliveryAction: String = "send_private_message",
    val needsClarification: Boolean = false,
    /** null means unchanged for rescheduling an existing recurring commitment. */
    val repeat: String? = null,
    /**
     * Only for rescheduling a daily task. True only when the user explicitly
     * wants every day changed; a plain "postpone it a bit" is one-time and
     * leaves the daily anchor untouched. Defaults to false.
     */
    val permanent: Boolean = false,
    val targetTaskId: String? = null,
    val promiseKind: String? = null,
)

