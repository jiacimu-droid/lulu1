package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One view of persistent motives for every surface. Proposed pause/release
 * operations and committed paused entries must never masquerade as current wants.
 * This is a read-only projection: deciding whether to resume is still the role's
 * own choice, not a scheduler side effect.
 */
internal object CharacterMotiveState {
    fun isActive(motive: JSONObject): Boolean =
        motive.optString("status", "active").equals("active", ignoreCase = true) &&
            motive.optString("op").lowercase() !in setOf("pause", "release") &&
            motive.optString("aim").isNotBlank()

    fun active(motives: JSONArray?): List<JSONObject> =
        entries(motives).filter(::isActive)

    fun paused(motives: JSONArray?): List<JSONObject> =
        entries(motives).filter { motive ->
            motive.optString("status").equals("paused", ignoreCase = true) ||
                motive.optString("op").equals("pause", ignoreCase = true)
        }.filter { it.optString("op") != "release" }

    private fun entries(motives: JSONArray?): List<JSONObject> =
        (0 until (motives?.length() ?: 0)).mapNotNull { motives?.optJSONObject(it) }
}
