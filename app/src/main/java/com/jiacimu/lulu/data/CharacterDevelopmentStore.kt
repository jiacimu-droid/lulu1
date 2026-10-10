package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

enum class DevelopmentKind(val label: String) {
    Interest("兴趣"), Preference("偏好"), Habit("习惯"), ExpressionHabit("表达习惯"), Judgment("判断"),
    SituationalPattern("情境反应倾向"), NarrativeMeaning("叙事意义"),
    RelationshipRoutine("相处方式"), VerifiedMethod("验证过的方法")
}

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
    val maturity: DevelopmentMaturity = DevelopmentMaturity.Emerging,
)

/** Additive persistence: no mutation of persona, identity, or existing memories. */
object CharacterDevelopmentStore {
    private var prefs: android.content.SharedPreferences? = null
    private var records = emptyList<DevelopmentRecord>()
    private val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revisions: kotlinx.coroutines.flow.StateFlow<Long> = changes

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
                    item.optString("personaSnapshot"),
                    runCatching { DevelopmentMaturity.valueOf(item.optString("maturity")) }.getOrElse {
                        if (item.optDouble("confidence", 0.0) >= 0.75) DevelopmentMaturity.Established
                        else DevelopmentMaturity.Emerging
                    })
            }.getOrNull()
        }
    }

    @Synchronized
    fun history(characterId: String): List<DevelopmentRecord> = records.filter { it.characterId == characterId }

    internal fun authorizedPersonaSnapshots(characterId: String): Set<String> {
        val current = CharacterRuntime.personaConstraintSnapshot(characterId)
        val saved = CharacterLifeStore.state(characterId)
        val from = saved.optString("jiangDuLanguagePreviousConstraints")
        val into = saved.optString("jiangDuLanguageCurrentConstraints")
        return if (from.isNotBlank() && into == current) setOf(current, from) else setOf(current)
    }

    fun active(characterId: String): List<DevelopmentRecord> {
        val authorized = authorizedPersonaSnapshots(characterId)
        return history(characterId).filter { record ->
            record.active && record.personaSnapshot in authorized &&
                (record.evidence + record.counterEvidence).all { (id, revision) ->
                    SharedExperienceTimeline.eventsByIds(characterId, listOf(id)).firstOrNull()?.revision == revision
                }
        }
    }

    fun applyProposal(characterId: String, slot: String, kind: DevelopmentKind, content: String,
        evidenceIds: List<String>, counterIds: List<String>, personaSnapshot: String): Boolean {
        if (prefs == null || slot.isBlank() || slot.length > 80 || content.isBlank() || content.length > 500) return false
        if (CharacterRuntime.personaConstraintSnapshot(characterId) != personaSnapshot) return false
        val events = SharedExperienceTimeline.eventsByIds(characterId, evidenceIds)
        val counters = SharedExperienceTimeline.eventsByIds(characterId, counterIds)
        if (events.size != evidenceIds.distinct().size || counters.size != counterIds.distinct().size) return false
        val factual = events.filter { it.isDevelopmentExposure() }.distinctBy { if (it.evidenceKind == EventEvidenceKind.UserStatement) it.id else it.sessionId.ifBlank { it.id } }
        val explicit = factual.any { it.evidenceKind == EventEvidenceKind.UserStatement &&
            Regex("以后|下次|记住|不要再|我喜欢|我不喜欢|我希望").containsMatchIn(it.content) }
        if (!DevelopmentPolicy.accepts(kind, factual.size, explicit, counters.size)) return false
        if (kind == DevelopmentKind.ExpressionHabit) {
            // A single situational joke is not a durable personal idiom. Require
            // at least two separately authored examples as well as three real
            // exposures, all attached to this role rather than a group bystander.
            val ownExpressions = events.filter { event ->
                event.evidenceKind == EventEvidenceKind.CharacterStatement && when {
                    event.source in setOf("journal:own", "moment:self") -> true
                    event.channel == "私聊" -> true
                    event.source == "message" && event.channel.startsWith("群聊") ->
                        MigratedDomainStores.chat.conversations.value.any { chat ->
                            chat.groupChat != null && chat.groupChat.members.any { it.characterId == characterId } &&
                                MigratedDomainStores.chat.messages(chat.id).value.any { msg ->
                                    msg.id == event.id && msg.authorCharacterId == characterId
                                }
                        }
                    else -> false
                }
            }.distinctBy { it.id }
            if (ownExpressions.size < 2) return false
        }
        if (kind == DevelopmentKind.Interest) {
            // User-specified interests are stable anchors, not a prohibition on developing
            // new nuances, routines or adjacent interests through witnessed experience.
            // Generated growth remains a separate, reversible evidence-backed layer.
            // Interest belongs to the character, not to whichever subject the user likes.
            if (events.none { it.evidenceKind == EventEvidenceKind.CharacterStatement || it.channel.startsWith("独自阅读") }) return false
            if (counters.any { it.occurredAt > factual.maxOf { event -> event.occurredAt } }) return false
        }
        if (kind == DevelopmentKind.VerifiedMethod && factual.count { event ->
            event.evidenceKind == EventEvidenceKind.ToolResult && runCatching {
                val outcome = JSONObject(event.content)
                outcome.optString("status") == "succeeded" &&
                    (outcome.optString("result").let { raw ->
                        if (raw.startsWith("{")) JSONObject(raw).optBoolean("success") else true
                    })
            }.getOrDefault(false)
        } < 3) return false
        synchronized(this) {
        if (CharacterRuntime.personaConstraintSnapshot(characterId) != personaSnapshot ||
            (events + counters).any { event -> SharedExperienceTimeline.eventsByIds(characterId, listOf(event.id))
                .firstOrNull()?.revision != event.revision }) return false
        val prior = history(characterId).filter { it.slot == slot }
        if (prior.any { it.personaSnapshot == personaSnapshot && it.content == content && it.evidence == events.associate { event -> event.id to event.revision } }) return false
        val next = DevelopmentRecord(UUID.randomUUID().toString(), characterId, slot, kind, content.trim(),
            (0.35 + factual.size * 0.1 - counters.size * 0.05).coerceIn(0.35, 0.9), events.associate { it.id to it.revision },
            counters.associate { it.id to it.revision }, (prior.maxOfOrNull { it.version } ?: 0) + 1,
            Instant.now(), personaSnapshot = personaSnapshot,
            maturity = DevelopmentPolicy.maturity(kind, factual.size))
        save(records.map { if (it.characterId == characterId && it.slot == slot) it.copy(active = false) else it } + next)
        return true
        }
    }

    @Synchronized
    fun retire(characterId: String, id: String) {
        save(records.map { if (it.characterId == characterId && it.id == id) it.copy(active = false) else it })
    }

    /**
     * A learned tendency may be withdrawn when repeated later events contradict it.
     * This never edits the user's stable persona; it only retires the derived adaptive layer.
     */
    @Synchronized
    fun retireSlotWithCounterEvidence(
        characterId: String,
        slot: String,
        counterIds: List<String>,
        personaSnapshot: String,
    ): Boolean {
        if (prefs == null || slot.isBlank() || counterIds.isEmpty()) return false
        if (CharacterRuntime.personaConstraintSnapshot(characterId) != personaSnapshot) return false
        val current = records.lastOrNull {
            it.characterId == characterId && it.slot == slot && it.active &&
                it.personaSnapshot == personaSnapshot
        } ?: return false
        val counters = SharedExperienceTimeline.eventsByIds(characterId, counterIds.distinct())
        if (counters.size != counterIds.distinct().size) return false
        val factualCounters = counters.filter { it.isDevelopmentExposure() }
            .distinctBy { event -> event.sessionId.ifBlank { event.id } }
        if (factualCounters.size < DevelopmentPolicy.counterExamplesToRetire(current.maturity)) return false
        val mergedCounters = current.counterEvidence + factualCounters.associate { it.id to it.revision }
        save(records.map { record ->
            if (record.id == current.id) record.copy(
                active = false,
                counterEvidence = mergedCounters,
                version = record.version + 1,
                createdAt = Instant.now(),
                maturity = DevelopmentMaturity.Contested,
            ) else record
        })
        return true
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
                .put("personaSnapshot", r.personaSnapshot).put("maturity", r.maturity.name)) }
        }
        check(prefs!!.edit().putString("records", array.toString()).commit()) { "成长记录保存失败" }
        records = next
        changes.value += 1
    }

    private fun readEvidence(root: JSONObject): Map<String, Long> = root.keys().asSequence().associateWith { root.getLong(it) }
}
