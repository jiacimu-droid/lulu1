package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * One view of persistent motives for every surface. Proposed pause/release
 * operations and committed paused entries must never masquerade as current wants.
 * This is a read-only projection: deciding whether to resume is still the role's
 * own choice, not a scheduler side effect.
 */
internal object CharacterMotiveState {
    fun isActive(motive: JSONObject): Boolean =
        motive.optString("status", "active").lowercase() in setOf("active", "waiting_feedback") &&
            motive.optString("op").lowercase() !in setOf("pause", "release") &&
            motive.optString("aim").isNotBlank()

    fun active(motives: JSONArray?): List<JSONObject> =
        entries(motives).filter(::isActive)

    fun paused(motives: JSONArray?): List<JSONObject> =
        entries(motives).filter { motive ->
            motive.optString("status").equals("paused", ignoreCase = true) ||
                motive.optString("op").equals("pause", ignoreCase = true)
        }.filter { it.optString("op") != "release" }

    /**
     * Context is a rotating working set, not a six-goal storage quota. All
     * active motives remain saved and can receive independent timed reviews.
     */
    fun workingSet(motives: JSONArray?, now: Instant = Instant.now(), limit: Int = 6): List<JSONObject> {
        val all = active(motives)
        if (all.size <= limit) return all
        if (limit <= 0) return emptyList()
        val day = now.atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        fun rotate(items: List<JSONObject>): List<JSONObject> {
            val sorted = items.sortedBy { it.optString("id") }
            if (sorted.isEmpty()) return emptyList()
            val offset = Math.floorMod(day, sorted.size.toLong()).toInt()
            return sorted.drop(offset) + sorted.take(offset)
        }
        val urgent = all.filter {
            it.optString("reviewAfter").takeIf(String::isNotBlank)?.let { date ->
                runCatching { Instant.parse(date) }.getOrNull()?.let { due -> !due.isAfter(now) }
            } == true
        }
        val ordinary = all.filterNot { it in urgent }
        if (ordinary.isEmpty()) return rotate(urgent).take(limit)
        if (urgent.isEmpty()) return rotate(ordinary).take(limit)
        val dueCount = minOf(urgent.size, maxOf(1, limit / 2))
        val selected = rotate(urgent).take(dueCount) + rotate(ordinary).take(limit - dueCount)
        return if (selected.size == limit) selected else
            selected + (rotate(urgent) + rotate(ordinary)).filterNot { it in selected }
                .take(limit - selected.size)
    }

    private fun entries(motives: JSONArray?): List<JSONObject> =
        (0 until (motives?.length() ?: 0)).mapNotNull { motives?.optJSONObject(it) }
}
