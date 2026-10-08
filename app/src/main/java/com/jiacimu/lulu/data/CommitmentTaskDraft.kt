package com.jiacimu.lulu.data

import java.time.Instant

data class CommitmentTaskDraft(
    val action: String,
    val goal: String,
    val dueAt: Instant? = null,
    val timezone: String? = null,
    val completionCondition: String = "",
    val steps: List<String> = emptyList(),
    val deliveryAction: String = "send_private_message",
    val needsClarification: Boolean = false,
    val targetTaskId: String? = null,
)
