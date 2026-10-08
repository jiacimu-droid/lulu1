package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

internal fun decodeCommitmentTasks(raw: String?): List<CommitmentTask> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val characterId = item.optString("characterId").trim()
                val goal = item.optString("goal").trim()
                if (characterId.isBlank() || goal.isBlank()) continue
                add(
                    CommitmentTask(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        characterId = characterId,
                        lexiconEntryId = item.nullString("lexiconEntryId"),
                        sourceEventIds = item.optJSONArray("sourceEventIds").strings(),
                        sourceTurnId = item.nullString("sourceTurnId"),
                        goal = goal,
                        status = runCatching { CommitmentTaskStatus.valueOf(item.optString("status")) }
                            .getOrDefault(CommitmentTaskStatus.NeedsClarification),
                        dueAt = item.nullInstant("dueAt"),
                        timezone = item.nullString("timezone"),
                        nextCheckAt = item.nullInstant("nextCheckAt"),
                        completionCondition = item.optString("completionCondition"),
                        steps = item.optJSONArray("steps").strings(),
                        deliveryAction = item.optString("deliveryAction", "send_private_message")
                            .takeIf { it == "start_call" } ?: "send_private_message",
                        currentStep = item.optInt("currentStep", 0),
                        attemptCount = item.optInt("attemptCount", 0),
                        lastActionResult = item.optString("lastActionResult"),
                        revision = item.optLong("revision", 1L).coerceAtLeast(1L),
                        linkedAlarmId = item.nullString("linkedAlarmId"),
                        createdAt = item.optString("createdAt").instantOrNow(),
                        updatedAt = item.optString("updatedAt").instantOrNow(),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun JSONObject.nullString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }
}

private fun JSONObject.nullInstant(key: String): Instant? = nullString(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return buildList { for (index in 0 until length()) optString(index).trim().takeIf(String::isNotBlank)?.let(::add) }
}

private fun String.instantOrNow(): Instant = runCatching { Instant.parse(this) }.getOrDefault(Instant.now())
