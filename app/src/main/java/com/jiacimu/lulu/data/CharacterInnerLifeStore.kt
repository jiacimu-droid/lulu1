package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A character's subjective, revisable inner life. World facts and action results remain
 * with the authoritative stores; model prose alone never means an action succeeded.
 */
object CharacterInnerLifeStore {
    private var prefs: android.content.SharedPreferences? = null

    @Synchronized fun initialize(context: Context) {
        if (prefs == null) prefs = context.applicationContext
            .getSharedPreferences("lulu_inner_life_v1", Context.MODE_PRIVATE)
    }

    @Synchronized fun snapshot(characterId: String): JSONObject =
        runCatching { JSONObject(prefs?.getString(characterId, "{}") ?: "{}") }.getOrDefault(JSONObject())

    @Synchronized fun clear(characterId: String) { prefs?.edit()?.remove(characterId)?.commit() }

    private fun save(id: String, state: JSONObject) {
        check(prefs?.edit()?.putString(id, state.toString())?.commit() == true) {
            "内在生活状态尚未初始化或保存失败"
        }
    }

    /**
     * An observation must have a real event ID and source text.
     * Duplicate evidence does not repeatedly elevate feelings or relationships.
     */
    @Synchronized fun observe(
        characterId: String,
        evidenceId: String,
        evidenceText: String,
        proposal: JSONObject?,
        allowedSocialIds: Set<String> = emptySet(),
        now: Instant = Instant.now(),
    ) {
        if (prefs == null || characterId.isBlank() || evidenceId.isBlank() ||
            evidenceText.isBlank() || proposal == null) return
        val root = snapshot(characterId)
        val evidenceKey = evidenceId + ":" + evidenceText.hashCode()
        val seen = root.optJSONArray("seen") ?: JSONArray()
        if ((0 until seen.length()).any { seen.optString(it) == evidenceKey }) return
        root.put("seen", JSONArray().apply {
            for (i in maxOf(0, seen.length() - 39) until seen.length()) put(seen.optString(i))
            put(evidenceKey)
        })
        proposal.optJSONObject("emotion")?.let { emotion ->
            val feeling = emotion.optString("feeling").trim().take(120)
            val cause = emotion.optString("cause").trim().take(180)
            if (feeling.isNotBlank() && cause.isNotBlank()) {
                root.put("emotion", JSONObject().put("feeling", feeling)
                    .put("cause", cause)
                    .put("otherFeeling", emotion.optString("otherFeeling").trim().take(100))
                    .put("strength", emotion.optInt("strength", 2).coerceIn(1, 4))
                    .put("startedAt", now.toString())
                    .put("halfLifeMinutes", emotion.optInt("halfLifeMinutes", 180).coerceIn(30, 1440))
                    .put("evidenceId", evidenceId))
            }
        }
        val motives = root.optJSONArray("motives") ?: JSONArray()
        val records = (0 until motives.length()).mapNotNull(motives::optJSONObject).toMutableList()
        proposal.optJSONArray("motives")?.let { changes ->
            for (index in 0 until minOf(3, changes.length())) {
                val change = changes.optJSONObject(index) ?: continue
                val op = change.optString("op").lowercase()
                val id = change.optString("id")
                val item = records.firstOrNull { it.optString("id") == id }
                val reason = change.optString("reason").trim().take(160)
                when (op) {
                    "start" -> {
                        val aim = change.optString("aim").trim().take(180)
                        val why = change.optString("why").trim().take(160)
                        if (aim.isBlank() || why.isBlank() || records.size >= 6 ||
                            records.any { it.optString("aim") == aim }) continue
                        records += JSONObject().put("id", UUID.randomUUID().toString())
                            .put("aim", aim).put("why", why)
                            .put("priority", change.optInt("priority", 2).coerceIn(1, 3))
                            .put("status", "active").put("createdAt", now.toString())
                            .put("evidenceId", evidenceId).put("outcomes", JSONArray())
                    }
                    "revise", "pause", "resume", "release" -> {
                        if (item == null || reason.isBlank()) continue
                        if (op == "revise") {
                            val aim = change.optString("aim").trim().take(180)
                            val why = change.optString("why").trim().take(160)
                            if (aim.isBlank() || why.isBlank()) continue
                            item.put("aim", aim).put("why", why)
                        }
                        if (op == "release") records.remove(item)
                        else {
                            if (op != "revise") item.put("status", if (op == "pause") "paused" else "active")
                            item.put("changedBecause", reason).put("updatedAt", now.toString())
                        }
                    }
                }
            }
        }
        root.put("motives", JSONArray().apply { records.forEach(::put) })
        proposal.optJSONObject("social")?.let { observation ->
            val target = observation.optString("targetId")
            val thought = observation.optString("interpretation").trim().take(180)
            if (target in allowedSocialIds && target != characterId && thought.isNotBlank()) {
                val bonds = root.optJSONObject("bonds") ?: JSONObject()
                val old = bonds.optJSONObject(target)
                bonds.put(target, JSONObject().put("interpretation", thought)
                    .put("reason", observation.optString("reason").trim().take(180))
                    .put("priorThought", old?.optString("interpretation").orEmpty().take(120))
                    .put("observations", (old?.optInt("observations", 0) ?: 0).coerceAtMost(999) + 1)
                    .put("evidenceId", evidenceId).put("updatedAt", now.toString()))
                root.put("bonds", bonds)
            }
        }
        proposal.optJSONObject("selfCorrection")?.let { correction ->
            val insight = correction.optString("realization").trim().take(180)
            val next = correction.optString("nextTime").trim().take(180)
            if (insight.isNotBlank() && next.isNotBlank()) {
                val old = root.optJSONArray("corrections") ?: JSONArray()
                root.put("corrections", JSONArray().apply {
                    for (i in maxOf(0, old.length() - 7) until old.length()) put(old.opt(i))
                    put(JSONObject().put("realization", insight).put("nextTime", next)
                        .put("evidenceId", evidenceId).put("at", now.toString()))
                })
            }
        }
        save(characterId, root)
    }

    /** Never acknowledge an attempted action as completed without an executor receipt. */
    @Synchronized fun recordActionResult(
        characterId: String, motiveId: String, receiptId: String,
        action: String, success: Boolean, summary: String, now: Instant = Instant.now(),
    ) {
        if (prefs == null || motiveId.isBlank() || receiptId.isBlank()) return
        val root = snapshot(characterId)
        val motives = root.optJSONArray("motives") ?: return
        val motive = (0 until motives.length()).mapNotNull(motives::optJSONObject)
            .firstOrNull { it.optString("id") == motiveId } ?: return
        val previous = motive.optJSONArray("outcomes") ?: JSONArray()
        if ((0 until previous.length()).any { previous.optJSONObject(it)?.optString("id") == receiptId }) return
        motive.put("outcomes", JSONArray().apply {
            for (i in maxOf(0, previous.length() - 7) until previous.length()) put(previous.opt(i))
            put(JSONObject().put("id", receiptId).put("action", action).put("success", success)
                .put("summary", summary.take(250)).put("at", now.toString()))
        })
        save(characterId, root)
    }

    /** Only call from an observed, persisted character message, never a speculative draft. */
    @Synchronized fun recordSpokenText(characterId: String, eventId: String, text: String) {
        if (prefs == null || eventId.isBlank()) return
        val sample = text.replace(Regex("\\s+"), " ").trim().take(140)
        if (sample.length < 3 || sample.startsWith("{")) return
        val root = snapshot(characterId)
        val old = root.optJSONArray("voice") ?: JSONArray()
        if ((0 until old.length()).any { old.optJSONObject(it)?.optString("id") == eventId }) return
        root.put("voice", JSONArray().apply {
            for (i in maxOf(0, old.length() - 9) until old.length()) put(old.opt(i))
            put(JSONObject().put("id", eventId).put("text", sample))
        })
        save(characterId, root)
    }

    fun context(characterId: String, now: Instant = Instant.now()): String {
        val root = snapshot(characterId)
        val motives = root.optJSONArray("motives") ?: JSONArray()
        val emotion = root.optJSONObject("emotion")
        val bonds = root.optJSONObject("bonds")
        val corrections = root.optJSONArray("corrections") ?: JSONArray()
        val voice = root.optJSONArray("voice") ?: JSONArray()
        return buildString {
            appendLine("【角色持续内在生活｜主观状态而非客观事实】")
            if (motives.length() == 0) appendLine("尚无明确长期小目标，不能为了填满生活编造。")
            for (i in 0 until motives.length()) {
                val m = motives.optJSONObject(i) ?: continue
                appendLine("· 动机 id=${m.optString("id")}；${m.optString("status")}；优先级${m.optInt("priority")}；目标${m.optString("aim")}；缘由${m.optString("why")}")
                val history = m.optJSONArray("outcomes") ?: JSONArray()
                if (history.length() > 0) {
                    val last = history.optJSONObject(history.length() - 1)
                    appendLine("  最近实际执行：${last?.optString("action")}；成功=${last?.optBoolean("success")}；结果=${last?.optString("summary")}")
                }
            }
            if (emotion != null) {
                val from = runCatching { Instant.parse(emotion.optString("startedAt")) }.getOrNull()
                val mins = from?.let { Duration.between(it, now).toMinutes().coerceAtLeast(0) } ?: Long.MAX_VALUE
                if (mins <= emotion.optInt("halfLifeMinutes", 180).coerceAtLeast(30) * 3L) {
                    appendLine("真实刺激之后的主观情绪余波：${emotion.optString("feeling")}；夹杂${emotion.optString("otherFeeling")}；当时原因${emotion.optString("cause")}；已过去${mins}分钟。强度会自然淡化，不要反复表演。")
                }
            }
            bonds?.keys()?.asSequence()?.take(8)?.forEach { id ->
                appendLine("· 对${if (id == "user") "用户" else "角色$id"}的当前看法：${bonds.optJSONObject(id)?.optString("interpretation")}（可被后续经历改变）")
            }
            if (corrections.length() > 0) {
                val last = corrections.optJSONObject(corrections.length() - 1)
                appendLine("自我修正：${last?.optString("realization")}；下次尝试：${last?.optString("nextTime")}。不要反复口头忏悔，以行动表现。")
            }
            if (voice.length() > 0) {
                appendLine("角色近期实际说过的话（仅供个人口气参考，不能逐字复读）：")
                for (i in maxOf(0, voice.length() - 3) until voice.length()) {
                    appendLine("· ${voice.optJSONObject(i)?.optString("text")}")
                }
            }
            appendLine("愿望可以并存、冲突和暂停。选择前考虑性格、时间、用户边界与执行能力；只从工具回执确认成功，失败后可反省并改法。")
        }.trim()
    }
}
