package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class DevelopmentKind(val label: String) {
    Interest("兴趣"), Preference("偏好"), Habit("习惯"),
    UserPreference("主人的偏好"), UserHabit("主人的习惯"),
    ExpressionHabit("表达习惯"), Judgment("判断"),
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
        val migrations = listOf(
            saved.optString("jiangDuLanguagePreviousConstraints") to
                saved.optString("jiangDuLanguageCurrentConstraints"),
            saved.optString("jiangDuUnifiedPreviousConstraints") to
                saved.optString("jiangDuUnifiedCurrentConstraints"),
            saved.optString("jiangDuV8PreviousConstraints") to
                saved.optString("jiangDuV8CurrentConstraints"),
        )
        val authorized = linkedSetOf(current)
        var changed: Boolean
        do {
            changed = false
            migrations.forEach { (from, into) ->
                if (from.isNotBlank() && into in authorized && authorized.add(from)) changed = true
            }
        } while (changed)
        return authorized
    }

    fun active(characterId: String): List<DevelopmentRecord> {
        val authorized = authorizedPersonaSnapshots(characterId)
        return history(characterId).filter { record ->
            record.active && (record.kind in setOf(DevelopmentKind.UserPreference, DevelopmentKind.UserHabit) ||
                record.personaSnapshot in authorized) &&
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
        val factual = independentDevelopmentExposures(events)
        val explicit = factual.any { it.evidenceKind == EventEvidenceKind.UserStatement &&
            Regex("以后|下次|记住|不要再|我喜欢|我不喜欢|我希望").containsMatchIn(it.content) }
        if (!DevelopmentPolicy.accepts(kind, factual.size, explicit, counters.size)) return false
        if (kind in setOf(DevelopmentKind.UserPreference, DevelopmentKind.UserHabit)) {
            // Keep what the user actually said distinct from the companion's own
            // traits. A clear first-person preference/self-report is useful now;
            // a pattern inferred from behaviour still needs independent days.
            if (factual.isEmpty() || factual.any {
                    it.evidenceKind != EventEvidenceKind.UserStatement ||
                        (it.channel != "私聊" && !it.channel.contains("电话") &&
                            !it.channel.contains("见面"))
                }) return false
            val direct = factual.any { event ->
                val text = event.content
                if (kind == DevelopmentKind.UserPreference)
                    Regex("我.{0,8}(喜欢|不喜欢|更喜欢|偏爱|讨厌|希望|不希望|想要|不想)|别总|别再|不要再|下次.{0,14}(请|别|不要)|就这样")
                        .containsMatchIn(text)
                else Regex("我(一般|通常|经常|习惯|总是|平时|每次|基本)")
                    .containsMatchIn(text)
            }
            val distinctDays = factual.map {
                it.occurredAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            }.toSet()
            if (!direct && (factual.size < 3 || distinctDays.size < 3)) return false
            // Later contrary USER evidence must not be silently ignored.
            if (counters.any { counter ->
                    factual.any { it.occurredAt < counter.occurredAt }
                }) return false
        }
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
        if (kind == DevelopmentKind.VerifiedMethod) {
            // A real sent message/gesture can be a tried method; tool execution
            // alone cannot establish interpersonal success. Validate the named
            // exchange against the original timeline, not the model's verdict.
            val claimedMessages = events.filter {
                it.evidenceKind == EventEvidenceKind.CharacterStatement &&
                    it.source == "message" &&
                    (it.channel == "私聊" || it.channel.contains("电话") || it.channel.contains("见面"))
            }
            val positiveFeedback = factual.filter { event ->
                event.evidenceKind == EventEvidenceKind.UserStatement &&
                    (event.channel == "私聊" || event.channel.contains("电话") || event.channel.contains("见面")) &&
                    Regex("我喜欢|喜欢你|好喜欢|被哄好|哄好了|好多了|舒服多了|有用|有效|这招可以|下次还|这样就好|就这样|开心了|笑出来了|你哄得|这次对了")
                        .containsMatchIn(event.content) &&
                    !Regex("不喜欢|不太喜欢|没用|没有用|没效果|没好|不开心|更烦|别这样|不要这样|反话").containsMatchIn(event.content)
            }
            if (claimedMessages.isEmpty() || positiveFeedback.isEmpty()) return false
            val timeline = SharedExperienceTimeline.all(characterId)
            val matched = positiveFeedback.any { feedback ->
                claimedMessages.any { action ->
                    val minutes = Duration.between(action.occurredAt, feedback.occurredAt).toMinutes()
                    minutes in 0..30 && action.channel == feedback.channel &&
                        // One conversational scene: do not credit a distant message
                        // when another character reply came in between.
                        timeline.none { later ->
                            later.id != action.id && later.evidenceKind == EventEvidenceKind.CharacterStatement &&
                                later.source == "message" && later.channel == action.channel &&
                                later.occurredAt.isAfter(action.occurredAt) &&
                                later.occurredAt.isBefore(feedback.occurredAt)
                        }
                }
            }
            if (!matched) return false
        }
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
        val factualCounters = independentDevelopmentExposures(counters)
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
