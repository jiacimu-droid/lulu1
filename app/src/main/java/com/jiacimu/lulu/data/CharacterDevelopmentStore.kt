package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

enum class DevelopmentKind { Preference, Habit, Judgment, RelationshipRoutine, VerifiedMethod }

data class DevelopmentRecord(
    val id: String,
    val characterId: String,
    val slot: String,
    val kind: DevelopmentKind,
    val content: String,
    val confidence: Double,
    val evidence: Map<String, Long>,
    val counterEvidence: Map<String, Long>,
    val version: Int,
    val createdAt: Instant,
    val active: Boolean = true,
    val personaSnapshot: String = "",
)

/** Additive persistence: no mutation of persona, identity, or existing memories. */
object CharacterDevelopmentStore {
    private var prefs: android.content.SharedPreferences? = null
    private var records = emptyList<DevelopmentRecord>()

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("lulu_character_development", Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs?.getString("records", "[]")) }.getOrDefault(JSONArray())
        records = (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                DevelopmentRecord(item.getString("id"), item.getString("characterId"), item.getString("slot"),
                    DevelopmentKind.valueOf(item.getString("kind")), item.getString("content"), item.getDouble("confidence"),
                    readEvidence(item.getJSONObject("evidence")), readEvidence(item.getJSONObject("counterEvidence")),
                    item.getInt("version"), Instant.parse(item.getString("createdAt")), item.optBoolean("active", true),
                    item.optString("personaSnapshot"))
            }.getOrNull()
        }
    }

    @Synchronized
    fun history(characterId: String): List<DevelopmentRecord> = records.filter { it.characterId == characterId }

    fun active(characterId: String): List<DevelopmentRecord> {
        val persona = MigratedDomainStores.characters.get(characterId).persona
        return history(characterId).filter { record ->
            record.active && record.personaSnapshot == persona &&
                (record.evidence + record.counterEvidence).all { (id, revision) ->
                    SharedExperienceTimeline.eventsByIds(characterId, listOf(id)).firstOrNull()?.revision == revision
                }
        }
    }

    fun applyProposal(characterId: String, slot: String, kind: DevelopmentKind, content: String,
        evidenceIds: List<String>, counterIds: List<String>, personaSnapshot: String): Boolean {
        if (prefs == null || slot.isBlank() || slot.length > 80 || content.isBlank() || content.length > 500) return false
        if (MigratedDomainStores.characters.get(characterId).persona != personaSnapshot) return false
        val events = SharedExperienceTimeline.eventsByIds(characterId, evidenceIds)
        val counters = SharedExperienceTimeline.eventsByIds(characterId, counterIds)
        if (events.size != evidenceIds.distinct().size || counters.size != counterIds.distinct().size) return false
        val factual = events.filter { it.evidenceKind in setOf(EventEvidenceKind.UserStatement, EventEvidenceKind.Observation, EventEvidenceKind.ToolResult) }
        val explicit = factual.any { it.evidenceKind == EventEvidenceKind.UserStatement &&
            Regex("以后|下次|记住|不要再|我喜欢|我不喜欢|我希望").containsMatchIn(it.content) }
        if (!DevelopmentPolicy.accepts(kind, factual.size, explicit, counters.size)) return false
        synchronized(this) {
        val prior = history(characterId).filter { it.slot == slot }
        if (prior.any { it.active && it.content == content && it.evidence.keys == evidenceIds.toSet() }) return false
        val next = DevelopmentRecord(UUID.randomUUID().toString(), characterId, slot, kind, content.trim(),
            (0.35 + factual.size * 0.1).coerceAtMost(0.9), events.associate { it.id to it.revision },
            counters.associate { it.id to it.revision }, (prior.maxOfOrNull { it.version } ?: 0) + 1,
            Instant.now(), personaSnapshot = personaSnapshot)
        save(records.map { if (it.characterId == characterId && it.slot == slot) it.copy(active = false) else it } + next)
        return true
        }
    }

    @Synchronized
    fun retire(characterId: String, id: String) {
        save(records.map { if (it.characterId == characterId && it.id == id) it.copy(active = false) else it })
    }

    @Synchronized
    fun invalidateEvidence(characterId: String, eventId: String) {
        save(records.map { if (it.characterId == characterId && eventId in (it.evidence.keys + it.counterEvidence.keys)) it.copy(active = false) else it })
    }

    @Synchronized
    fun invalidateEvidenceForAll(eventId: String) {
        save(records.map { if (eventId in (it.evidence.keys + it.counterEvidence.keys)) it.copy(active = false) else it })
    }

    @Synchronized
    fun clearCharacter(characterId: String) { save(records.filterNot { it.characterId == characterId }) }

    private fun save(next: List<DevelopmentRecord>) {
        if (prefs == null) return
        val array = JSONArray().apply {
            next.forEach { r -> put(JSONObject().put("id", r.id).put("characterId", r.characterId)
                .put("slot", r.slot).put("kind", r.kind.name).put("content", r.content).put("confidence", r.confidence)
                .put("evidence", JSONObject(r.evidence)).put("counterEvidence", JSONObject(r.counterEvidence))
                .put("version", r.version).put("createdAt", r.createdAt.toString()).put("active", r.active)
                .put("personaSnapshot", r.personaSnapshot)) }
        }
        check(prefs!!.edit().putString("records", array.toString()).commit()) { "成长记录保存失败" }
        records = next
    }

    private fun readEvidence(root: JSONObject): Map<String, Long> = root.keys().asSequence().associateWith { root.getLong(it) }
}
