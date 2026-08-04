package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class CompanionPresenceState(
    val characterId: String,
    val statusText: String = "",
    val gesture: String = "",
    val innerThought: String = "",
    val mood: String = "",
    val updatedAt: Instant = Instant.EPOCH,
    val source: String = "",
)

/** Current private/visible role presence shared by chat and background perception. */
object CompanionPresenceStore {
    private const val PREFS_NAME = "lulu_companion_presence"
    private const val KEY_STATES = "states_v1"
    private val mutableStates = MutableStateFlow<Map<String, CompanionPresenceState>>(emptyMap())
    val states: StateFlow<Map<String, CompanionPresenceState>> = mutableStates.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutableStates.value = decode(prefs?.getString(KEY_STATES, null))
    }

    fun current(characterId: String): CompanionPresenceState? = states.value[characterId]

    @Synchronized
    fun update(
        characterId: String,
        statusText: String?,
        gesture: String?,
        innerThought: String?,
        mood: String?,
        source: String,
        now: Instant = Instant.now(),
    ) {
        if (characterId.isBlank()) return
        val previous = mutableStates.value[characterId]
        val next = CompanionPresenceState(
            characterId = characterId,
            statusText = statusText.cleanPresence(120) ?: previous?.statusText.orEmpty(),
            gesture = gesture.cleanPresence(500) ?: previous?.gesture.orEmpty(),
            // An explicitly returned empty thought means there is nothing worth exposing now.
            innerThought = if (innerThought == null) previous?.innerThought.orEmpty() else innerThought.cleanPresence(500).orEmpty(),
            mood = mood.cleanPresence(80) ?: previous?.mood.orEmpty(),
            updatedAt = now,
            source = source.take(40),
        )
        if (next.statusText.isBlank() && next.gesture.isBlank() && next.innerThought.isBlank() && next.mood.isBlank()) return
        mutableStates.value = mutableStates.value + (characterId to next)
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        mutableStates.value.values.forEach { state ->
            array.put(JSONObject().apply {
                put("characterId", state.characterId)
                put("statusText", state.statusText)
                put("gesture", state.gesture)
                put("innerThought", state.innerThought)
                put("mood", state.mood)
                put("updatedAt", state.updatedAt.toString())
                put("source", state.source)
            })
        }
        prefs?.edit()?.putString(KEY_STATES, array.toString())?.apply()
    }

    private fun decode(raw: String?): Map<String, CompanionPresenceState> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val characterId = item.optString("characterId").trim()
                if (characterId.isBlank()) continue
                put(
                    characterId,
                    CompanionPresenceState(
                        characterId = characterId,
                        statusText = item.optString("statusText"),
                        gesture = item.optString("gesture"),
                        innerThought = item.optString("innerThought"),
                        mood = item.optString("mood"),
                        updatedAt = runCatching { Instant.parse(item.optString("updatedAt")) }.getOrDefault(Instant.EPOCH),
                        source = item.optString("source"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())
}

private fun String?.cleanPresence(limit: Int): String? = this
    ?.trim()
    ?.replace(Regex("\\s+"), " ")
    ?.take(limit)
    ?.takeIf(String::isNotBlank)
