package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject

internal fun encodeCommitmentTasks(values: List<CommitmentTask>): String = JSONArray().apply {
    values.forEach { task ->
        put(JSONObject().apply {
            put("id", task.id)
            put("characterId", task.characterId)
            put("lexiconEntryId", task.lexiconEntryId ?: JSONObject.NULL)
            put("sourceEventIds", JSONArray(task.sourceEventIds))
            put("sourceTurnId", task.sourceTurnId ?: JSONObject.NULL)
            put("goal", task.goal)
            put("status", task.status.name)
            put("dueAt", task.dueAt?.toString() ?: JSONObject.NULL)
            put("timezone", task.timezone ?: JSONObject.NULL)
            put("nextCheckAt", task.nextCheckAt?.toString() ?: JSONObject.NULL)
            put("completionCondition", task.completionCondition)
            put("steps", JSONArray(task.steps))
            put("deliveryAction", task.deliveryAction)
            put("currentStep", task.currentStep)
            put("attemptCount", task.attemptCount)
            put("lastActionResult", task.lastActionResult)
            put("revision", task.revision)
            put("linkedAlarmId", task.linkedAlarmId ?: JSONObject.NULL)
            put("createdAt", task.createdAt.toString())
            put("updatedAt", task.updatedAt.toString())
        })
    }
}.toString()
