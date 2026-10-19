package com.jiacimu.lulu.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlinx.coroutines.flow.asStateFlow

/** Evidence-linked material insights, exercises and actual feedback. */
internal object CharacterLearningLedger {
    private var prefs: SharedPreferences? = null
    private const val CAP = 320
    private val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revisions = changes.asStateFlow()

    @Synchronized fun initialize(context: Context) {
        if (prefs == null) prefs = context.applicationContext
            .getSharedPreferences("lulu_learning_ledger_v1", Context.MODE_PRIVATE)
    }

    @Synchronized fun clearCharacter(characterId: String) {
        if (prefs?.edit()?.remove(characterId)?.commit() == true) changes.value += 1
    }

    data class InsightRow(val sourceEvidenceId: String, val topic: String, val understanding: String)

    /** Material text and real source stay intact; this only erases this role's
     * interpretation and all revisions that depended on it. */
    @Synchronized fun listInsights(characterId: String): List<InsightRow> {
        val all = records(characterId)
        return all.filter { valid(characterId, it, all) }.takeLast(90).asReversed()
            .mapNotNull { entry ->
                val id = entry.optString("sourceEvidenceId")
                if (id.isBlank()) null else InsightRow(
                    id, entry.optString("topic"),
                    entry.optString("understanding").ifBlank { entry.optString("question") })
            }
    }

    @Synchronized fun deleteInsight(characterId: String, sourceEvidenceId: String): Boolean {
        if (prefs == null || sourceEvidenceId.isBlank()) return false
        val all = records(characterId)
        val removed = mutableSetOf(sourceEvidenceId)
        var previousSize: Int
        var remaining = all.toList()
        do {
            previousSize = remaining.size
            remaining = remaining.filterNot { item ->
                val id = item.optString("sourceEvidenceId")
                val direct = id in removed || item.optString("revisesEvidenceId") in removed
                if (direct) removed += id
                direct
            }
        } while (remaining.size < previousSize)
        if (remaining.size == all.size) return false
        save(characterId, remaining)
        // Only the learning stage is reset; original sources remain available as
        // genuinely read material without the rejected model conclusion.
        CharacterLearningAgenda.withdrawInsight(characterId, removed)
        return true
    }

    private fun records(characterId: String): MutableList<JSONObject> {
        val raw = runCatching { JSONArray(prefs?.getString(characterId, "[]")) }.getOrDefault(JSONArray())
        return (0 until raw.length()).mapNotNull(raw::optJSONObject).toMutableList()
    }

    private fun save(characterId: String, items: List<JSONObject>) {
        val value = JSONArray()
        items.takeLast(CAP).forEach(value::put)
        check(prefs!!.edit().putString(characterId, value.toString()).commit()) {
            "学习记录未能保存"
        }
        changes.value += 1
    }

    private fun source(characterId: String, id: String): SharedTimelineEvent? =
        if (id.isBlank()) null else SharedExperienceTimeline.eventsByIds(
            characterId, listOf(id, "$id:group:$characterId"),
        ).firstOrNull()

    private fun materialSource(role: String, event: SharedTimelineEvent): Boolean {
        if (event.source in setOf("xiaohongshu-webview", "xiaohongshu-mcp") &&
            event.evidenceKind == EventEvidenceKind.Observation) {
            val material = runCatching { JSONObject(event.content) }.getOrNull() ?: return false
            return material.optString("text").isNotBlank() &&
                Regex("https://www\\.xiaohongshu\\.com/explore/[a-zA-Z0-9]{12,40}").matches(material.optString("url"))
        }
        if (event.source.startsWith("reading:")) return true // Existing authoritative reading compatibility.
        if (!event.source.startsWith("learning-material:") ||
            event.evidenceKind != EventEvidenceKind.Observation) return false
        val payload = runCatching { JSONObject(event.content) }.getOrNull() ?: return false
        val origin = source(role, payload.optString("originId")) ?: return false
        return origin.revision == payload.optLong("originRevision", -1) &&
            payload.optString("text").length >= 120 && payload.optString("url").startsWith("https://")
    }

    /** Same evidence rules for attaching already-read material and later recall. */
    fun isMaterialEvidence(characterId: String, evidenceId: String): Boolean =
        source(characterId, evidenceId)?.let { materialSource(characterId, it) } == true

    private fun valid(
        characterId: String, item: JSONObject, all: List<JSONObject> = records(characterId),
        visited: Set<String> = emptySet(),
    ): Boolean {
        val id = item.optString("sourceEvidenceId")
        if (id.isBlank() || id in visited) return false
        val event = source(characterId, id) ?: return false
        if (!materialSource(characterId, event) ||
            event.revision != item.optLong("sourceRevision", -1L)) return false
        val previousId = item.optString("revisesEvidenceId")
        if (previousId.isBlank()) return true
        val previous = all.firstOrNull {
            it.optString("sourceEvidenceId") == previousId &&
                it.optString("topic").equals(item.optString("topic"), ignoreCase = true)
        } ?: return false
        val previousEvent = source(characterId, previousId) ?: return false
        return previousEvent.occurredAt.isBefore(event.occurredAt) &&
            valid(characterId, previous, all, visited + id)
    }

    @Synchronized fun hasInsight(role: String, id: String): Boolean {
        val all = records(role)
        return all.any { it.optString("sourceEvidenceId") == id && valid(role, it, all) }
    }

    /** Only an actual completed reading and committed timeline source may create insight. */
    @Synchronized fun recordReading(
        characterId: String, evidenceId: String, proposal: JSONObject?,
        now: Instant = Instant.now(),
    ): Boolean {
        if (prefs == null || proposal == null) return false
        val event = source(characterId, evidenceId)
            ?.takeIf { materialSource(characterId, it) } ?: return false
        val topic = proposal.optString("topic").trim().take(90)
        val understanding = proposal.optString("understanding").trim().take(400)
        if (topic.length < 2 || understanding.length < 5) return false
        val items = records(characterId)
        if (items.any { it.optString("sourceEvidenceId") == evidenceId }) return false
        val previousId = proposal.optString("revisesEvidenceId").trim()
        if (previousId.isNotBlank()) {
            val previous = items.find {
                it.optString("sourceEvidenceId") == previousId &&
                    it.optString("topic").equals(topic, true) && valid(characterId, it, items)
            } ?: return false
            if (!source(characterId, previousId)!!.occurredAt.isBefore(event.occurredAt)) return false
            if (previous.optString("sourceEvidenceId") == evidenceId) return false
        }
        items.add(JSONObject()
            .put("sourceEvidenceId", event.id).put("sourceRevision", event.revision)
            .put("originId", if (event.source.startsWith("learning-material:"))
                runCatching { JSONObject(event.content).optString("originId") }.getOrDefault("") else "")
            .put("topic", topic).put("understanding", understanding)
            .put("uncertainty", proposal.optString("uncertainty").trim().take(220))
            .put("question", proposal.optString("question").trim().take(220))
            .put("applicableWhen", proposal.optString("applicableWhen").trim().take(180))
            .put("revisesEvidenceId", previousId)
            .put("at", now.toString()).put("applications", JSONArray())
            .put("challenges", JSONArray()))
        save(characterId, items)
        return true
    }

    /** Private drafts are exercises, never delivered actions or user endorsements. */
    @Synchronized fun recordExercise(role: String, materialId: String, receiptId: String, kind: String): Boolean {
        if (prefs == null || kind !in setOf("practice", "review")) return false
        val event = source(role, receiptId)?.takeIf {
            it.source.startsWith("learning-$kind:") && it.evidenceKind == EventEvidenceKind.CharacterStatement
        } ?: return false
        val payload = runCatching { JSONObject(event.content) }.getOrNull() ?: return false
        if (payload.optString("sourceEvidenceId") != materialId || payload.optString("artifact").length < 5) return false
        val items = records(role)
        val idea = items.firstOrNull { it.optString("sourceEvidenceId") == materialId && valid(role, it, items) }
            ?: return false
        val exercises = idea.optJSONArray("exercises") ?: JSONArray()
        if ((0 until exercises.length()).any { exercises.optJSONObject(it)?.optString("evidenceId") == receiptId }) return false
        exercises.put(JSONObject().put("evidenceId", receiptId).put("revision", event.revision).put("kind", kind))
        idea.put("exercises", JSONArray().apply {
            for (i in maxOf(0, exercises.length() - 6) until exercises.length()) put(exercises.opt(i))
        })
        save(role, items)
        return true
    }

    /** A REAL successful action is an attempt to apply an idea, not proof it helped. */
    @Synchronized fun recordApplication(
        characterId: String, proposal: JSONObject?, actionEvidenceId: String,
        action: String, success: Boolean, receipt: String,
    ): Boolean {
        if (!success || proposal == null || prefs == null || actionEvidenceId.isBlank()) return false
        val actual = CharacterActionEvidence.resolve(characterId, actionEvidenceId)
            ?.takeIf { CharacterActionEvidence.supports(it, action) } ?: return false
        val learnedFrom = proposal.optString("sourceEvidenceId").trim()
        val how = proposal.optString("howApplied").trim().take(180)
        if (learnedFrom == actionEvidenceId || how.length < 5) return false
        val items = records(characterId)
        val learned = items.find { it.optString("sourceEvidenceId") == learnedFrom && valid(characterId, it, items) }
            ?: return false
        if (actual.occurredAt.isBefore(source(characterId, learnedFrom)!!.occurredAt)) return false
        val uses = learned.optJSONArray("applications") ?: JSONArray()
        if ((0 until uses.length()).any { uses.optJSONObject(it)?.optString("evidenceId") == actionEvidenceId })
            return false
        uses.put(JSONObject().put("evidenceId", actionEvidenceId).put("revision", actual.revision)
            .put("how", how).put("action", action.take(40))
            .put("receipt", receipt.take(180)).put("feedback", JSONArray()))
        learned.put("applications", uses)
        save(characterId, items)
        return true
    }


    /**
     * A user statement is feedback only when the same turn's model identifies
     * an actual preceding application. The quote stays attributable to the
     * user; positive/negative is an interpretation and never a mastery score.
     */
    @Synchronized fun recordFeedback(
        characterId: String, proposal: JSONObject?, userEvidenceId: String,
    ): Boolean {
        if (prefs == null || proposal == null || userEvidenceId.isBlank()) return false
        val feedback = source(characterId, userEvidenceId)
            ?.takeIf {
                it.evidenceKind == EventEvidenceKind.UserStatement &&
                    (it.channel == "私聊" || it.channel.contains("电话") || it.channel.contains("见面"))
            } ?: return false
        val evaluation = proposal.optString("evaluation").trim().lowercase()
        if (evaluation !in setOf("helped", "unhelpful", "mixed")) return false
        val negative = Regex("不喜欢|没用|没有用|无效|不行|不好|不舒服|别|不要|讨厌|反感|更烦|不对|别扭|尴尬|不希望|不合适")
            .containsMatchIn(feedback.content)
        val positive = Regex("喜欢|有用|有效|不错|挺好|很好|更好|好多了|舒服|开心|满意|合适|可以|很棒|这次对了")
            .containsMatchIn(feedback.content)
        // Reject a model's unsupported claim of endorsement or objection.
        if (evaluation == "helped" && (!positive || negative) ||
            evaluation == "unhelpful" && !negative ||
            evaluation == "mixed" && !(positive && negative)) return false
        val from = proposal.optString("sourceEvidenceId").trim()
        val applied = proposal.optString("actionEvidenceId").trim()
        val items = records(characterId)
        val idea = items.firstOrNull { it.optString("sourceEvidenceId") == from && valid(characterId, it, items) }
            ?: return false
        val applications = idea.optJSONArray("applications") ?: return false
        val attempt = (0 until applications.length()).mapNotNull(applications::optJSONObject)
            .firstOrNull { it.optString("evidenceId") == applied } ?: return false
        val realAction = source(characterId, applied) ?: return false
        if (realAction.revision != attempt.optLong("revision", -1L) ||
            !realAction.occurredAt.isBefore(feedback.occurredAt)) return false
        val before = attempt.optJSONArray("feedback") ?: JSONArray()
        if ((0 until before.length()).any {
            before.optJSONObject(it)?.optString("evidenceId") == userEvidenceId
        }) return false
        val after = JSONArray()
        for (i in maxOf(0, before.length() - 5) until before.length()) after.put(before.opt(i))
        after.put(JSONObject().put("evidenceId", feedback.id)
            .put("revision", feedback.revision).put("evaluation", evaluation)
            .put("userQuote", feedback.content.take(240))
            .put("interpretation", proposal.optString("why").trim().take(180)))
        attempt.put("feedback", after)
        idea.put("applications", applications)
        save(characterId, items)
        return true
    }


    /**
     * A user can contest a character's provisional understanding without
     * magically changing the factual source or proving either speaker correct.
     * The read note is annotated until later evidence supports revision.
     */
    @Synchronized fun recordChallenge(
        characterId: String, proposal: JSONObject?, userEvidenceId: String,
    ): Boolean {
        if (prefs == null || proposal == null || userEvidenceId.isBlank()) return false
        val userEvent = source(characterId, userEvidenceId)
            ?.takeIf {
                it.evidenceKind == EventEvidenceKind.UserStatement &&
                    (it.channel == "私聊" || it.channel.contains("电话") || it.channel.contains("见面"))
            } ?: return false
        // A model-invented contradiction without any correction in the user's
        // own current utterance cannot create a durable disagreement.
        if (!Regex("不是|不对|不准确|弄错|错了|其实|纠正|不同意|反例|误解|应该|但不是|并非")
                .containsMatchIn(userEvent.content)) return false
        val readingId = proposal.optString("sourceEvidenceId").trim()
        val issue = proposal.optString("issue").trim().take(220)
        if (issue.length < 5) return false
        val items = records(characterId)
        val record = items.firstOrNull {
            it.optString("sourceEvidenceId") == readingId && valid(characterId, it, items)
        } ?: return false
        if (!source(characterId, readingId)!!.occurredAt.isBefore(userEvent.occurredAt))
            return false
        val challenges = record.optJSONArray("challenges") ?: JSONArray()
        if ((0 until challenges.length()).any {
            challenges.optJSONObject(it)?.optString("evidenceId") == userEvidenceId
        }) return false
        val next = JSONArray()
        for (i in maxOf(0, challenges.length() - 5) until challenges.length()) next.put(challenges.opt(i))
        next.put(JSONObject().put("evidenceId", userEvent.id)
            .put("revision", userEvent.revision).put("userQuote", userEvent.content.take(240))
            .put("issue", issue))
        record.put("challenges", next)
        save(characterId, items)
        return true
    }

    /** Read-only audit projection. Never award mastery for an unverified idea. */
    @Synchronized fun growthTrace(characterId: String, now: Instant = Instant.now()): List<CharacterGrowthTrace> {
        val items = records(characterId)
        return buildList {
            items.filter { valid(characterId, it, items) }.takeLast(28).forEach { record ->
                val read = source(characterId, record.optString("sourceEvidenceId")) ?: return@forEach
                if (read.occurredAt.isAfter(now)) return@forEach
                add(CharacterGrowthTrace(read.occurredAt, "读后形成暂定理解",
                    "材料主题：" + record.optString("topic") + "；当时理解：" +
                        record.optString("understanding").take(180) +
                        "。尚未证明方法有效，也不是本人亲历小说情节。",
                    read.id, "学习"))
                val exercises = record.optJSONArray("exercises") ?: JSONArray()
                for (i in 0 until exercises.length()) {
                    val exercise = exercises.optJSONObject(i) ?: continue
                    val event = source(characterId, exercise.optString("evidenceId")) ?: continue
                    if (event.revision != exercise.optLong("revision", -1) || event.occurredAt.isAfter(now)) continue
                    val payload = runCatching { JSONObject(event.content) }.getOrNull() ?: continue
                    add(CharacterGrowthTrace(event.occurredAt,
                        if (exercise.optString("kind") == "review") "实际回看与复习" else "私人练习",
                        payload.optString("artifact").take(240) + "；本人自评：" +
                            payload.optString("selfAssessment").take(180) + "。没有用户评价，不算已验证。", event.id, "学习"))
                }
                val challenges = record.optJSONArray("challenges") ?: JSONArray()
                for (i in 0 until challenges.length()) {
                    val item = challenges.optJSONObject(i) ?: continue
                    val event = source(characterId, item.optString("evidenceId")) ?: continue
                    if (event.revision != item.optLong("revision", -1L) ||
                        event.occurredAt.isAfter(now)) continue
                    add(CharacterGrowthTrace(event.occurredAt, "理解受到新的质疑",
                        "用户原话：" + item.optString("userQuote").take(170) +
                            "；争议：" + item.optString("issue").take(160) +
                            "。尚未判定哪种理解正确。",
                        event.id, "学习"))
                }
                val applications = record.optJSONArray("applications") ?: JSONArray()
                for (i in 0 until applications.length()) {
                    val application = applications.optJSONObject(i) ?: continue
                    val action = source(characterId, application.optString("evidenceId")) ?: continue
                    if (action.revision != application.optLong("revision", -1L) ||
                        action.occurredAt.isAfter(now)) continue
                    add(CharacterGrowthTrace(action.occurredAt, "实际尝试运用所学",
                        application.optString("how").take(180) +
                            "；执行回执：" + application.optString("receipt").take(150) +
                            "。是否有效仍待检验。", action.id, "学习"))
                    val feedback = application.optJSONArray("feedback") ?: JSONArray()
                    for (j in 0 until feedback.length()) {
                        val f = feedback.optJSONObject(j) ?: continue
                        val user = source(characterId, f.optString("evidenceId")) ?: continue
                        if (user.revision != f.optLong("revision", -1L) || user.occurredAt.isAfter(now)) continue
                        add(CharacterGrowthTrace(user.occurredAt, "收到真实应用反馈",
                            "用户原话：" + f.optString("userQuote").take(210) +
                                "。这是对一次做法的反馈，并非稳定习惯已经形成。",
                            user.id, "学习"))
                    }
                }
            }
        }
    }

    /** Retract a deleted source and all dependent revisions; remove deleted application receipts. */
    fun invalidateEvidence(eventId: String) {
        val retractedMaterials = mutableSetOf<String>()
        synchronized(this) {
            if (prefs == null || eventId.isBlank()) return
            prefs!!.all.keys.forEach { characterId ->
                val original = records(characterId)
                val removed = mutableSetOf(eventId)
                var current = original.toList()
                var changed: Boolean
                do {
                    val next = current.filterNot {
                        val id = it.optString("sourceEvidenceId")
                        val invalid = id in removed || it.optString("revisesEvidenceId") in removed ||
                            it.optString("originId") in removed
                        if (invalid) removed.add(id)
                        invalid
                    }
                    changed = next.size != current.size
                    current = next
                } while (changed)
                // A withdrawn origin also withdraws opportunities derived from its
                // learning material; raw external text remaining on disk is not a
                // license to keep sharing a retracted learning projection.
                retractedMaterials += removed - eventId
                val remaining = current.map { item ->
                    val old = item.optJSONArray("applications") ?: JSONArray()
                    val next = JSONArray()
                    for (i in 0 until old.length()) {
                        val application = old.optJSONObject(i) ?: continue
                        if (application.optString("evidenceId") != eventId) {
                            val feedback = application.optJSONArray("feedback") ?: JSONArray()
                            val kept = JSONArray()
                            for (index in 0 until feedback.length()) {
                                val item = feedback.optJSONObject(index) ?: continue
                                if (item.optString("evidenceId") != eventId) kept.put(item)
                            }
                            next.put(JSONObject(application.toString()).put("feedback", kept))
                        }
                    }
                    val challenges = item.optJSONArray("challenges") ?: JSONArray()
                    val keptChallenges = JSONArray()
                    for (j in 0 until challenges.length()) {
                        val issue = challenges.optJSONObject(j) ?: continue
                        if (issue.optString("evidenceId") != eventId) keptChallenges.put(issue)
                    }
                    val exercises = item.optJSONArray("exercises") ?: JSONArray()
                    val keptExercises = JSONArray().apply {
                        for (i in 0 until exercises.length()) {
                            val exercise = exercises.optJSONObject(i) ?: continue
                            if (exercise.optString("evidenceId") != eventId) put(exercise)
                        }
                    }
                    JSONObject(item.toString()).put("applications", next).put("exercises", keptExercises)
                        .put("challenges", keptChallenges)
                }
                if (remaining.map(JSONObject::toString) != original.map(JSONObject::toString))
                    save(characterId, remaining)
            }
        }
        // Do not hold the learning-store monitor while entering another Store.
        retractedMaterials.forEach(CharacterLifeFollowThroughStore::invalidateEvidence)
    }

    /** All Full-context routes use this bounded topical recall; old relevant evidence can win. */
    @Synchronized fun context(characterId: String, query: String, compact: Boolean = true): String {
        val stored = records(characterId)
        val validItems = stored.filter { valid(characterId, it, stored) }
        val active = validItems.filter { old ->
            validItems.none { it.optString("revisesEvidenceId") == old.optString("sourceEvidenceId") }
        }
        if (active.isEmpty()) return ""
        val text = query.lowercase().take(2500)
        fun score(item: JSONObject): Int {
            val topic = item.optString("topic").lowercase()
            val chunks = (topic + item.optString("question").lowercase() + item.optString("applicableWhen").lowercase())
                .windowed(2).distinct().take(70)
            return (if (topic.length > 2 && text.contains(topic)) 30 else 0) +
                chunks.count(text::contains).coerceAtMost(12)
        }
        val sorted = active.withIndex().sortedWith(
            compareByDescending<IndexedValue<JSONObject>> { score(it.value) }
                .thenByDescending { it.index })
        val selected = sorted.filterIndexed { i, item -> i == 0 || score(item.value) > 0 }
            .take(if (compact) 2 else 4)
        return buildString {
            appendLine("【本人读过材料后形成的理解｜不代表材料必然正确或方法已验证】")
            selected.forEach { indexed ->
                val item = indexed.value
                appendLine("- 真实材料证据=" + item.optString("sourceEvidenceId") +
                    "；主题=" + item.optString("topic") +
                    "；当时理解=" + item.optString("understanding"))
                val material = source(characterId, item.optString("sourceEvidenceId"))
                if (material?.source?.startsWith("learning-material:") == true) {
                    val payload = runCatching { JSONObject(material.content) }.getOrNull()
                    appendLine("  实际来源=${payload?.optString("url")}；来源类型=${payload?.optString("kind")}；只读了节选")
                }
                val exercises = item.optJSONArray("exercises") ?: JSONArray()
                val exercise = (0 until exercises.length()).mapNotNull(exercises::optJSONObject).lastOrNull {
                    source(characterId, it.optString("evidenceId"))?.revision == it.optLong("revision", -1)
                }
                exercise?.let {
                    val event = source(characterId, it.optString("evidenceId"))
                    val payload = event?.let { e -> runCatching { JSONObject(e.content) }.getOrNull() }
                    appendLine("  私人练习/复习证据=${event?.id}；本人练习稿=${payload?.optString("artifact")?.take(260)}；" +
                        "自评=${payload?.optString("selfAssessment")?.take(160)}。不是已发给用户或用户好评")
                }
                val challenges = item.optJSONArray("challenges") ?: JSONArray()
                val open = (0 until challenges.length()).mapNotNull(challenges::optJSONObject)
                    .filter { issue ->
                        val event = source(characterId, issue.optString("evidenceId"))
                        event?.revision == issue.optLong("revision", -1L) &&
                            event.evidenceKind == EventEvidenceKind.UserStatement
                    }.takeLast(2)
                open.forEach { issue ->
                    appendLine("  用户提出的反证ID=" + issue.optString("evidenceId") +
                        "；原话=" + issue.optString("userQuote") +
                        "；待核对的争议=" + issue.optString("issue") +
                        "。并非一方的说法已经被证明。")
                }
                for (field in listOf("uncertainty" to "仍不确定", "question" to "仍想弄清",
                    "applicableWhen" to "可能适用（未验证）")) {
                    item.optString(field.first).takeIf(String::isNotBlank)?.let {
                        appendLine("  " + field.second + "：" + it)
                    }
                }
                val used = item.optJSONArray("applications") ?: JSONArray()
                val evaluated = (0 until used.length()).mapNotNull(used::optJSONObject).flatMap { attempt ->
                    val action = source(characterId, attempt.optString("evidenceId"))
                    if (action?.revision != attempt.optLong("revision", -1)) return@flatMap emptyList()
                    val feedback = attempt.optJSONArray("feedback") ?: JSONArray()
                    (0 until feedback.length()).mapNotNull(feedback::optJSONObject).filter { f ->
                        source(characterId, f.optString("evidenceId"))?.revision == f.optLong("revision", -1)
                    }
                }.distinctBy { it.optString("evidenceId") }
                val helped = evaluated.count { it.optString("evaluation") == "helped" }
                val unhelpful = evaluated.count { it.optString("evaluation") == "unhelpful" }
                if (evaluated.isNotEmpty()) appendLine("  真实不同反馈：认可=$helped，不适合=$unhelpful；" +
                    "可以据情境保留或调整做法，负面和混合反馈不能被旧好评覆盖，不自动改变核心人格")
                val verifiedActions = (0 until used.length()).mapNotNull(used::optJSONObject)
                    .filter { attempt ->
                        source(characterId, attempt.optString("evidenceId"))?.revision ==
                            attempt.optLong("revision", -1L)
                    }.distinctBy { it.optString("evidenceId") }
                fun matchingActionFeedback(action: JSONObject, label: String): Boolean {
                    val entries = action.optJSONArray("feedback") ?: JSONArray()
                    return (0 until entries.length()).mapNotNull(entries::optJSONObject).any { f ->
                        f.optString("evaluation") == label &&
                            source(characterId, f.optString("evidenceId"))?.revision ==
                                f.optLong("revision", -1L)
                    }
                }
                val practiceCount = (0 until exercises.length()).mapNotNull(exercises::optJSONObject)
                    .count { source(characterId, it.optString("evidenceId"))?.revision ==
                        it.optLong("revision", -1L) }
                val learningStage = LearningCompetencyPolicy.stage(
                    practiceCount, verifiedActions.size,
                    verifiedActions.count { matchingActionFeedback(it, "helped") },
                    verifiedActions.count { matchingActionFeedback(it, "unhelpful") ||
                        matchingActionFeedback(it, "mixed") })
                appendLine("  能力证据阶段=" + when (learningStage) {
                    LearningEvidenceStage.READING_ONLY -> "仅形成理解"
                    LearningEvidenceStage.PRACTICED -> "已私人练习，未验证"
                    LearningEvidenceStage.APPLIED_UNVERIFIED -> "已尝试实际应用，效果未知"
                    LearningEvidenceStage.POSITIVE_FEEDBACK -> "有真实正面反馈，仍需看情境"
                    LearningEvidenceStage.REPEATEDLY_SUPPORTED -> "多个独立行动获正面反馈，非永久掌握"
                })
                val last = (0 until used.length()).mapNotNull(used::optJSONObject).lastOrNull {
                    source(characterId, it.optString("evidenceId"))?.revision ==
                        it.optLong("revision", -1L)
                }
                if (last != null) {
                    appendLine("  应用证据ID=" + last.optString("evidenceId") +
                        "；已尝试实践=" + last.optString("how") +
                        "；实际执行回执=" + last.optString("receipt"))
                    val feedbacks = last.optJSONArray("feedback") ?: JSONArray()
                    val confirmed = (0 until feedbacks.length()).mapNotNull(feedbacks::optJSONObject)
                        .filter { candidate ->
                            val statement = source(characterId, candidate.optString("evidenceId"))
                            statement?.revision == candidate.optLong("revision", -1L) &&
                                statement.evidenceKind == EventEvidenceKind.UserStatement
                        }.takeLast(2)
                    confirmed.forEach { feedback ->
                        val meaning = when (feedback.optString("evaluation")) {
                            "helped" -> "角色理解为认可（不能单凭一次反馈认定习惯已成熟）"
                            "unhelpful" -> "角色理解为不适合/不喜欢（下次需调整或放下）"
                            else -> "好坏并存，仍需重新理解"
                        }
                        appendLine("  用户真实反馈ID=" + feedback.optString("evidenceId") +
                            "；原话=" + feedback.optString("userQuote") + "；评价=" + meaning)
                    }
                    if (confirmed.isEmpty()) appendLine("  是否有效仍未知；执行成功不是对方满意")
                }
            }
            append("只在相关话题自然使用；小说人物经历不是自己的过去，已读不等于掌握，想做不等于做成。")
        }.trim()
    }
}
