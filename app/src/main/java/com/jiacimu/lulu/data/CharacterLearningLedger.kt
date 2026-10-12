package com.jiacimu.lulu.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Evidence-linked reading insights, NOT a second persona, or a claim of proven expertise. */
internal object CharacterLearningLedger {
    private var prefs: SharedPreferences? = null
    private const val CAP = 80

    @Synchronized fun initialize(context: Context) {
        if (prefs == null) prefs = context.applicationContext
            .getSharedPreferences("lulu_learning_ledger_v1", Context.MODE_PRIVATE)
    }

    @Synchronized fun clearCharacter(characterId: String) {
        prefs?.edit()?.remove(characterId)?.commit()
    }

    private fun records(characterId: String): MutableList<JSONObject> {
        val raw = runCatching { JSONArray(prefs?.getString(characterId, "[]")) }.getOrDefault(JSONArray())
        return (0 until raw.length()).mapNotNull(raw::optJSONObject).toMutableList()
    }

    private fun save(characterId: String, items: List<JSONObject>) {
        val value = JSONArray()
        items.takeLast(CAP).forEach(value::put)
        check(prefs!!.edit().putString(characterId, value.toString()).commit()) {
            "阅读学习记录未能保存"
        }
    }

    private fun source(characterId: String, id: String): SharedTimelineEvent? =
        SharedExperienceTimeline.eventsByIds(characterId, listOf(id)).singleOrNull()

    private fun valid(characterId: String, item: JSONObject): Boolean {
        val event = source(characterId, item.optString("sourceEvidenceId")) ?: return false
        if (!event.source.startsWith("reading:") ||
            event.revision != item.optLong("sourceRevision", -1L)) return false
        val previousId = item.optString("revisesEvidenceId")
        return previousId.isBlank() || source(characterId, previousId)?.source?.startsWith("reading:") == true
    }

    /** Only an actual completed reading and committed timeline source may create insight. */
    @Synchronized fun recordReading(
        characterId: String, evidenceId: String, proposal: JSONObject?,
        now: Instant = Instant.now(),
    ): Boolean {
        if (prefs == null || proposal == null) return false
        val event = source(characterId, evidenceId)
            ?.takeIf { it.source.startsWith("reading:") } ?: return false
        val topic = proposal.optString("topic").trim().take(90)
        val understanding = proposal.optString("understanding").trim().take(400)
        if (topic.length < 2 || understanding.length < 5) return false
        val items = records(characterId)
        if (items.any { it.optString("sourceEvidenceId") == evidenceId }) return false
        val previousId = proposal.optString("revisesEvidenceId").trim()
        if (previousId.isNotBlank()) {
            val previous = items.find {
                it.optString("sourceEvidenceId") == previousId &&
                    it.optString("topic").equals(topic, true) && valid(characterId, it)
            } ?: return false
            if (!source(characterId, previousId)!!.occurredAt.isBefore(event.occurredAt)) return false
            if (previous.optString("sourceEvidenceId") == evidenceId) return false
        }
        items.add(JSONObject()
            .put("sourceEvidenceId", event.id).put("sourceRevision", event.revision)
            .put("topic", topic).put("understanding", understanding)
            .put("uncertainty", proposal.optString("uncertainty").trim().take(220))
            .put("question", proposal.optString("question").trim().take(220))
            .put("applicableWhen", proposal.optString("applicableWhen").trim().take(180))
            .put("revisesEvidenceId", previousId)
            .put("at", now.toString()).put("applications", JSONArray()))
        save(characterId, items)
        return true
    }

    /** A REAL successful action is an attempt to apply an idea, not proof it helped. */
    @Synchronized fun recordApplication(
        characterId: String, proposal: JSONObject?, actionEvidenceId: String,
        action: String, success: Boolean, receipt: String,
    ): Boolean {
        if (!success || proposal == null || prefs == null || actionEvidenceId.isBlank()) return false
        val actual = source(characterId, actionEvidenceId)
            ?.takeUnless { it.evidenceKind in setOf(EventEvidenceKind.Inference, EventEvidenceKind.UserStatement) }
            ?: return false
        val learnedFrom = proposal.optString("sourceEvidenceId").trim()
        val how = proposal.optString("howApplied").trim().take(180)
        if (learnedFrom == actionEvidenceId || how.length < 5) return false
        val items = records(characterId)
        val learned = items.find { it.optString("sourceEvidenceId") == learnedFrom && valid(characterId, it) }
            ?: return false
        if (actual.occurredAt.isBefore(source(characterId, learnedFrom)!!.occurredAt)) return false
        val uses = learned.optJSONArray("applications") ?: JSONArray()
        if ((0 until uses.length()).any { uses.optJSONObject(it)?.optString("evidenceId") == actionEvidenceId })
            return false
        uses.put(JSONObject().put("evidenceId", actionEvidenceId).put("revision", actual.revision)
            .put("how", how).put("action", action.take(40)).put("receipt", receipt.take(180)))
        learned.put("applications", uses)
        save(characterId, items)
        return true
    }

    /** Retract a deleted source and all dependent revisions; remove deleted application receipts. */
    @Synchronized fun invalidateEvidence(eventId: String) {
        if (prefs == null || eventId.isBlank()) return
        prefs!!.all.keys.forEach { characterId ->
            val original = records(characterId)
            val removed = mutableSetOf(eventId)
            var current = original.toList()
            var changed: Boolean
            do {
                val next = current.filterNot {
                    val id = it.optString("sourceEvidenceId")
                    val invalid = id in removed || it.optString("revisesEvidenceId") in removed
                    if (invalid) removed.add(id)
                    invalid
                }
                changed = next.size != current.size
                current = next
            } while (changed)
            val remaining = current.map { item ->
                val old = item.optJSONArray("applications") ?: JSONArray()
                val next = JSONArray()
                for (i in 0 until old.length()) {
                    val application = old.optJSONObject(i) ?: continue
                    if (application.optString("evidenceId") != eventId) next.put(application)
                }
                JSONObject(item.toString()).put("applications", next)
            }
            if (remaining.size != original.size || remaining.indices.any { i ->
                    remaining[i].optJSONArray("applications")?.length() !=
                        original.getOrNull(i)?.optJSONArray("applications")?.length()
                }) save(characterId, remaining)
        }
    }

    /** All Full-context routes use this bounded topical recall; old relevant evidence can win. */
    @Synchronized fun context(characterId: String, query: String, compact: Boolean = true): String {
        val validItems = records(characterId).filter { valid(characterId, it) }
        val active = validItems.filter { old ->
            validItems.none { it.optString("revisesEvidenceId") == old.optString("sourceEvidenceId") }
        }
        if (active.isEmpty()) return ""
        val text = query.lowercase().take(2500)
        fun score(item: JSONObject): Int {
            val topic = item.optString("topic").lowercase()
            val chunks = (topic + item.optString("question").lowercase())
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
                appendLine("- 真实阅读证据=" + item.optString("sourceEvidenceId") +
                    "；主题=" + item.optString("topic") +
                    "；当时理解=" + item.optString("understanding"))
                for (field in listOf("uncertainty" to "仍不确定", "question" to "仍想弄清",
                    "applicableWhen" to "可能适用（未验证）")) {
                    item.optString(field.first).takeIf(String::isNotBlank)?.let {
                        appendLine("  " + field.second + "：" + it)
                    }
                }
                val used = item.optJSONArray("applications") ?: JSONArray()
                val last = (0 until used.length()).mapNotNull(used::optJSONObject).lastOrNull {
                    source(characterId, it.optString("evidenceId"))?.revision ==
                        it.optLong("revision", -1L)
                }
                if (last != null) appendLine("  已尝试实践=" + last.optString("how") +
                    "；实际执行回执=" + last.optString("receipt") +
                    "；效果仍须用户反馈或后续证据验证")
            }
            append("只在相关话题自然使用；小说人物经历不是自己的过去，已读不等于掌握，想做不等于做成。")
        }.trim()
    }
}
