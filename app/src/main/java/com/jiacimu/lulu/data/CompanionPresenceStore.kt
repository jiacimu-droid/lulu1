package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

data class CompanionPresenceState(
    val characterId: String,
    val statusText: String = "",
    val gesture: String = "",
    val innerThought: String = "",
    val mood: String = "",
    val updatedAt: Instant = Instant.EPOCH,
    val source: String = "",
    val lastPerceptionAt: Instant? = null,
    val lastPerceptionNote: String = "",
    val provenanceId: String = "",
)

data class CompanionPresenceMessageAnchor(
    val characterId: String,
    val messageAt: Instant,
    val state: CompanionPresenceState?,
)

/** Current private/visible role presence shared by chat and background perception. */
object CompanionPresenceStore {
    private const val PREFS_NAME = "lulu_companion_presence"
    private const val KEY_STATES = "states_v1"
    private const val KEY_HISTORY = "history_v1"
    private val mutableStates = MutableStateFlow<Map<String, CompanionPresenceState>>(emptyMap())
    val states: StateFlow<Map<String, CompanionPresenceState>> = mutableStates.asStateFlow()
    private val mutableHistories = MutableStateFlow<Map<String, List<CompanionPresenceState>>>(emptyMap())
    val histories: StateFlow<Map<String, List<CompanionPresenceState>>> = mutableHistories.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null

    @Volatile
    private var selectedMessageAnchor: CompanionPresenceMessageAnchor? = null

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutableStates.value = decode(prefs?.getString(KEY_STATES, null))
        mutableHistories.value = decodeHistory(prefs?.getString(KEY_HISTORY, null))
    }

    fun current(characterId: String): CompanionPresenceState? = states.value[characterId]

    /**
     * Anchors the next presence dialog to the state that existed when this concrete chat message
     * was sent. New chat turns are recorded one-by-one in history, so different message avatars no
     * longer all open the role's newest state. Older messages fall back to the closest saved state
     * at or before their timestamp instead of incorrectly showing a future state.
     */
    fun selectMessageAnchor(characterId: String, messageAt: Instant) {
        if (characterId.isBlank()) return
        val candidates = buildList {
            addAll(mutableHistories.value[characterId].orEmpty())
            mutableStates.value[characterId]?.let(::add)
        }.distinctBy(CompanionPresenceState::updatedAt)
        val snapshot = candidates
            .asSequence()
            .filter { it.updatedAt <= messageAt }
            .maxByOrNull(CompanionPresenceState::updatedAt)
        selectedMessageAnchor = CompanionPresenceMessageAnchor(
            characterId = characterId,
            messageAt = messageAt,
            state = snapshot,
        )
    }

    fun selectedMessageAnchor(characterId: String): CompanionPresenceMessageAnchor? =
        selectedMessageAnchor?.takeIf { it.characterId == characterId }

    fun clearMessageAnchor() {
        selectedMessageAnchor = null
    }

    /** Records that the perception pipeline actually ran, including skips and failures. */
    @Synchronized
    fun recordPerceptionAttempt(characterId: String, note: String, now: Instant = Instant.now()) {
        if (characterId.isBlank()) return
        val previous = mutableStates.value[characterId]
        val next = (previous ?: CompanionPresenceState(characterId = characterId)).copy(
            lastPerceptionAt = now,
            lastPerceptionNote = note.trim().take(180),
        )
        mutableStates.value = mutableStates.value + (characterId to next)
        persist()
    }

    @Synchronized
    fun update(
        characterId: String,
        statusText: String?,
        gesture: String?,
        innerThought: String?,
        mood: String?,
        source: String,
        now: Instant = Instant.now(),
        provenanceId: String = "",
    ) {
        if (characterId.isBlank()) return
        val previous = mutableStates.value[characterId]
        val next = CompanionPresenceState(
            characterId = characterId,
            statusText = statusText.cleanPresence(120) ?: previous?.statusText.orEmpty(),
            gesture = gesture.cleanPresence(500) ?: previous?.gesture.orEmpty(),
            innerThought = if (innerThought == null) previous?.innerThought.orEmpty() else innerThought.cleanPresence(500).orEmpty(),
            mood = mood.cleanPresence(80) ?: previous?.mood.orEmpty(),
            updatedAt = now,
            source = source.take(40),
            lastPerceptionAt = if (source.contains("感知")) now else previous?.lastPerceptionAt,
            lastPerceptionNote = if (source.contains("感知")) "感知成功，已形成新的此刻状态" else previous?.lastPerceptionNote.orEmpty(),
            provenanceId = provenanceId,
        )
        if (next.statusText.isBlank() && next.gesture.isBlank() && next.innerThought.isBlank() && next.mood.isBlank()) {
            recordPerceptionAttempt(characterId, "模型返回了空状态", now)
            return
        }
        mutableStates.value = mutableStates.value + (characterId to next)
        val contentChanged = previous == null || previous.copy(
            updatedAt = next.updatedAt,
            source = next.source,
            lastPerceptionAt = next.lastPerceptionAt,
            lastPerceptionNote = next.lastPerceptionNote,
        ) != next
        val heartbeatDue = previous == null || Duration.between(previous.updatedAt, now).toMinutes() >= 30
        val isChatTurn = source.contains("聊天") || source.contains("群聊")

        // Every actual chat turn owns a distinct 'moment', even if two consecutive states happen to
        // have the same wording. Non-chat background states may still dedupe to avoid noisy history.
        if (isChatTurn || contentChanged || heartbeatDue) {
            mutableHistories.value = mutableHistories.value +
                (characterId to (listOf(next) + mutableHistories.value[characterId].orEmpty())
                    .distinctBy { it.updatedAt }
                    .take(100))
            recordPresenceTimeline(next)
        }
        persist()
    }

    @Synchronized
    fun rollbackMeetingProvenance(
        provenanceIds: Set<String>,
        snapshots: Map<String, CompanionPresenceState?>,
    ) {
        if (provenanceIds.isEmpty()) return
        val affectedCharacters = snapshots.keys + mutableStates.value.values
            .filter { it.provenanceId in provenanceIds }
            .map(CompanionPresenceState::characterId)
        val nextHistories = mutableHistories.value.toMutableMap()
        val nextStates = mutableStates.value.toMutableMap()
        affectedCharacters.distinct().forEach { characterId ->
            val remaining = nextHistories[characterId].orEmpty()
                .filterNot { it.provenanceId in provenanceIds }
            nextHistories[characterId] = remaining
            val current = nextStates[characterId]
            if (current?.provenanceId in provenanceIds) {
                val restored = snapshots[characterId] ?: remaining.maxByOrNull(CompanionPresenceState::updatedAt)
                if (restored == null) nextStates.remove(characterId) else nextStates[characterId] = restored
            }
            provenanceIds.forEach { provenanceId ->
                SharedExperienceTimeline.deleteEvent("presence-$provenanceId-$characterId")
            }
        }
        mutableHistories.value = nextHistories
        mutableStates.value = nextStates
        persist()
    }

    @Synchronized
    fun clearCharacter(characterId: String) {
        if (characterId.isBlank()) return
        mutableStates.value = mutableStates.value - characterId
        mutableHistories.value = mutableHistories.value - characterId
        if (selectedMessageAnchor?.characterId == characterId) selectedMessageAnchor = null
        persist()
    }

    private fun recordPresenceTimeline(state: CompanionPresenceState) {
        val characterName = runCatching { MigratedDomainStores.characters.get(state.characterId).displayName }
            .getOrDefault("角色")
        val detail = buildList {
            state.statusText.takeIf(String::isNotBlank)?.let { add("状态：$it") }
            state.gesture.takeIf(String::isNotBlank)?.let { add("动作：$it") }
            state.mood.takeIf(String::isNotBlank)?.let { add("心情：$it") }
            state.innerThought.takeIf(String::isNotBlank)?.let { add("心声：$it") }
        }.joinToString("；")
        if (detail.isBlank()) return
        SharedExperienceTimeline.record(
            eventId = state.provenanceId.takeIf(String::isNotBlank)
                ?.let { "presence-$it-${state.characterId}" }
                ?: "presence-${state.characterId}-${state.updatedAt.toEpochMilli()}",
            characterId = state.characterId,
            channel = "此刻",
            speaker = characterName,
            content = detail,
            occurredAt = state.updatedAt,
        )
    }

    private fun persist() {
        val array = JSONArray()
        mutableStates.value.values.forEach { array.put(it.toJson()) }
        prefs?.edit()?.putString(KEY_STATES, array.toString())?.apply()
        val historyRoot = JSONObject()
        mutableHistories.value.forEach { (characterId, history) ->
            historyRoot.put(characterId, JSONArray().apply { history.forEach { put(it.toJson()) } })
        }
        prefs?.edit()?.putString(KEY_HISTORY, historyRoot.toString())?.apply()
    }

    private fun decode(raw: String?): Map<String, CompanionPresenceState> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val characterId = item.optString("characterId").trim()
                if (characterId.isBlank()) continue
                item.toPresenceState(characterId)?.let { put(characterId, it) }
            }
        }
    }.getOrDefault(emptyMap())

    private fun decodeHistory(raw: String?): Map<String, List<CompanionPresenceState>> = runCatching {
        val root = JSONObject(raw ?: "{}")
        buildMap {
            root.keys().forEach { characterId ->
                val array = root.optJSONArray(characterId) ?: return@forEach
                put(characterId, buildList {
                    for (index in 0 until array.length()) array.optJSONObject(index)?.toPresenceState(characterId)?.let(::add)
                })
            }
        }
    }.getOrDefault(emptyMap())
}

private fun CompanionPresenceState.toJson(): JSONObject = JSONObject().apply {
    put("characterId", characterId)
    put("statusText", statusText)
    put("gesture", gesture)
    put("innerThought", innerThought)
    put("mood", mood)
    put("updatedAt", updatedAt.toString())
    put("source", source)
    put("lastPerceptionAt", lastPerceptionAt?.toString().orEmpty())
    put("lastPerceptionNote", lastPerceptionNote)
    put("provenanceId", provenanceId)
}

private fun JSONObject.toPresenceState(fallbackCharacterId: String): CompanionPresenceState? {
    val id = optString("characterId").ifBlank { fallbackCharacterId }
    if (id.isBlank()) return null
    return CompanionPresenceState(
        characterId = id,
        statusText = optString("statusText"),
        gesture = optString("gesture"),
        innerThought = optString("innerThought"),
        mood = optString("mood"),
        updatedAt = runCatching { Instant.parse(optString("updatedAt")) }.getOrDefault(Instant.EPOCH),
        source = optString("source"),
        lastPerceptionAt = optString("lastPerceptionAt").takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() },
        lastPerceptionNote = optString("lastPerceptionNote"),
        provenanceId = optString("provenanceId"),
    )
}

private fun String?.cleanPresence(limit: Int): String? = this
    ?.trim()
    ?.replace(Regex("\\s+"), " ")
    ?.take(limit)
    ?.takeIf(String::isNotBlank)
