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
    private val revisionState = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revisions: kotlinx.coroutines.flow.StateFlow<Long> = revisionState

    @Synchronized fun initialize(context: Context) {
        if (prefs == null) prefs = context.applicationContext
            .getSharedPreferences("lulu_inner_life_v1", Context.MODE_PRIVATE)
    }

    @Synchronized fun snapshot(characterId: String): JSONObject =
        runCatching { JSONObject(prefs?.getString(characterId, "{}") ?: "{}") }.getOrDefault(JSONObject())

    @Synchronized fun clear(characterId: String) {
        if (prefs?.edit()?.remove(characterId)?.commit() == true) revisionState.value += 1L
    }

    private fun save(id: String, state: JSONObject) {
        check(prefs?.edit()?.putString(id, state.toString())?.commit() == true) {
            "内在生活状态尚未初始化或保存失败"
        }
        revisionState.value += 1L
    }

    /** The user may stop a pending thought without erasing the role's other life threads. */
    @Synchronized fun stopMotive(characterId: String, motiveId: String) {
        if (prefs == null || motiveId.isBlank()) return
        val root = snapshot(characterId)
        val motives = root.optJSONArray("motives") ?: return
        val remaining = JSONArray()
        var removed = false
        for (i in 0 until motives.length()) {
            val item = motives.optJSONObject(i) ?: continue
            if (item.optString("id") == motiveId) removed = true else remaining.put(item)
        }
        if (removed) {
            root.put("motives", remaining)
            save(characterId, root)
        }
    }

    /** Individual raw-source deletion also invalidates subjective conclusions derived from it. */
    @Synchronized fun invalidateEvidence(eventId: String) {
        if (eventId.isBlank()) return
        val ids = prefs?.all?.keys.orEmpty()
        fun backedBy(value: String): Boolean =
            value == eventId || value.contains(":$eventId:")
        ids.forEach { characterId ->
            val root = snapshot(characterId)
            var changed = false
            val oldSeen = root.optJSONArray("seen") ?: JSONArray()
            val seen = JSONArray()
            for (i in 0 until oldSeen.length()) {
                val key = oldSeen.optString(i)
                if (backedBy(key.substringBeforeLast(":"))) changed = true else seen.put(key)
            }
            root.put("seen", seen)
            listOf("emotion").forEach { key ->
                val record = root.optJSONObject(key)
                if (record != null && backedBy(record.optString("evidenceId"))) {
                    root.remove(key); changed = true
                }
            }
            // A deleted event must not survive as an earlier emotional influence.
            root.optJSONArray("emotionHistory")?.let { history ->
                val retained = JSONArray()
                for (i in 0 until history.length()) {
                    val entry = history.optJSONObject(i) ?: continue
                    if (backedBy(entry.optString("evidenceId"))) changed = true
                    else retained.put(entry)
                }
                root.put("emotionHistory", retained)
            }
            listOf("motives", "corrections", "voice").forEach { key ->
                val values = root.optJSONArray(key) ?: return@forEach
                val next = JSONArray()
                for (i in 0 until values.length()) {
                    val entry = values.optJSONObject(i) ?: continue
                    val linked = backedBy(entry.optString("evidenceId")) ||
                        (key == "voice" && backedBy(entry.optString("id")))
                    if (linked) changed = true else next.put(entry)
                }
                root.put(key, next)
            }
            val bonds = root.optJSONObject("bonds")
            if (bonds != null) {
                bonds.keys().asSequence().toList().forEach { target ->
                    val opinion = bonds.optJSONObject(target) ?: return@forEach
                    val history = opinion.optJSONArray("encounters") ?: JSONArray()
                    val retained = JSONArray()
                    for (i in 0 until history.length()) {
                        val encounter = history.optJSONObject(i) ?: continue
                        if (backedBy(encounter.optString("source"))) changed = true
                        else retained.put(encounter)
                    }
                    if (retained.length() == 0 && backedBy(opinion.optString("evidenceId"))) {
                        bonds.remove(target); changed = true
                    } else if (retained.length() > 0 && retained.length() != history.length()) {
                        val last = retained.optJSONObject(retained.length() - 1)
                        opinion.put("encounters", retained)
                            .put("interpretation", last?.optString("thought"))
                            .put("reason", last?.optString("because"))
                            .put("evidenceId", last?.optString("source"))
                        changed = true
                    }
                }
            }
            if (changed) save(characterId, root)
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
                val previous = root.optJSONObject("emotion")
                val entry = JSONObject().put("feeling", feeling)
                    .put("cause", cause)
                    .put("otherFeeling", emotion.optString("otherFeeling").trim().take(100))
                    .put("impulse", emotion.optString("impulse").trim().take(150))
                    .put("restraint", emotion.optString("restraint").trim().take(150))
                    .put("physicalCue", emotion.optString("physicalCue").trim().take(120))
                    .put("outwardCue", emotion.optString("outwardCue").trim().take(120))
                    .put("strength", emotion.optInt("strength", 2).coerceIn(1, 4))
                    .put("startedAt", now.toString())
                    .put("halfLifeMinutes", emotion.optInt("halfLifeMinutes", 180).coerceIn(30, 1440))
                    .put("evidenceId", evidenceId)
                // Keep the previous emotional course as witnessed history, rather than overwriting
                // a complex reaction every time the model supplies a fresh feeling.
                val history = root.optJSONArray("emotionHistory") ?: JSONArray()
                val nextHistory = JSONArray()
                for (i in maxOf(0, history.length() - 9) until history.length()) {
                    nextHistory.put(history.opt(i))
                }
                if (previous != null && previous.optString("evidenceId") != evidenceId) {
                    nextHistory.put(previous)
                }
                root.put("emotionHistory", nextHistory)
                root.put("emotion", entry)
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
        root.put("motives", JSONArray().apply { records.forEach { put(it) } })
        proposal.optJSONObject("social")?.let { observation ->
            val target = observation.optString("targetId")
            val thought = observation.optString("interpretation").trim().take(180)
            if (target in allowedSocialIds && target != characterId && thought.isNotBlank()) {
                val bonds = root.optJSONObject("bonds") ?: JSONObject()
                val old = bonds.optJSONObject(target)
                val events = old?.optJSONArray("encounters") ?: JSONArray()
                val recent = JSONArray()
                for (i in maxOf(0, events.length() - 4) until events.length()) recent.put(events.opt(i))
                recent.put(JSONObject().put("thought", thought)
                    .put("because", observation.optString("reason").trim().take(180))
                    .put("source", evidenceId).put("at", now.toString()))
                bonds.put(target, JSONObject().put("interpretation", thought)
                    .put("reason", observation.optString("reason").trim().take(180))
                    .put("priorThought", old?.optString("interpretation").orEmpty().take(120))
                    .put("observations", (old?.optInt("observations", 0) ?: 0).coerceAtMost(999) + 1)
                    .put("encounters", recent)
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
        motive.put("lastAttemptOutcome", if (success) "success" else "needs_review")
        motive.put("lastAttemptAt", now.toString())
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
        val emotionHistory = root.optJSONArray("emotionHistory") ?: JSONArray()
        val bonds = root.optJSONObject("bonds")
        val corrections = root.optJSONArray("corrections") ?: JSONArray()
        val voice = root.optJSONArray("voice") ?: JSONArray()
        return buildString {
            appendLine("【角色持续内在生活｜主观状态而非客观事实】")
            val active = (0 until motives.length()).mapNotNull(motives::optJSONObject)
                .filter { it.optString("status", "active") == "active" }
            val paused = (0 until motives.length()).mapNotNull(motives::optJSONObject)
                .filter { it.optString("status") == "paused" }
            if (active.size > 1) {
                appendLine("现在并行牵挂${active.size}件事。不要每回合只按固定优先级执行；考虑此刻状态、执行条件、上次行动后果及角色本人的冲突。")
                val contrasting = active.sortedByDescending { it.optInt("priority", 2) }.take(3)
                appendLine("当下可权衡的心愿：${contrasting.joinToString("／") { it.optString("aim") }}。")
            }
            if (paused.isNotEmpty()) appendLine("另外有${paused.size}件事被暂时搁置，若没有新的理由不要硬恢复。")
            appendLine("连续人格链：真实记忆与现有信念 → 多种同时存在的感受 → 内在愿望和实际限制 → 本轮可以执行/克制的选择 → 言语和动作 → 真实后果 → 下轮反思。")
            appendLine("不要把心理状态列表念给用户；它应暗中影响自然的停顿、措辞、话题偏爱、行动选择、相处边界和对失败的态度。")
            appendLine("可以有私心、好奇、无聊、得意、嘴硬或反悔，必须对应个人设定与实际触发。不同性格的人反应方式不同，不要统一撒娇或每轮强烈心动。")
            if (motives.length() == 0) appendLine("尚无明确长期小目标，不能为了填满生活编造。")
            for (i in 0 until motives.length()) {
                val m = motives.optJSONObject(i) ?: continue
                appendLine("· 动机 id=${m.optString("id")}；${m.optString("status")}；优先级${m.optInt("priority")}；目标${m.optString("aim")}；缘由${m.optString("why")}")
                if (m.optString("lastAttemptOutcome") == "needs_review")
                    appendLine("  此愿望上次行动没做成：应按真实失败原因决定复试、暂停、求助或放弃，不得装成已经成功。")
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
                    appendLine("当前情绪：${emotion.optString("feeling")}；另一层感受：${emotion.optString("otherFeeling")}；触发原因：${emotion.optString("cause")}；已经过去${mins}分钟。")
                    emotion.optString("impulse").takeIf(String::isNotBlank)?.let { appendLine("本能想做：$it") }
                    emotion.optString("restraint").takeIf(String::isNotBlank)?.let { appendLine("克制/犹豫：$it") }
                    emotion.optString("physicalCue").takeIf(String::isNotBlank)?.let { appendLine("自身可感知的身体反应：$it") }
                    emotion.optString("outwardCue").takeIf(String::isNotBlank)?.let { appendLine("可能被旁人察觉的变化：$it") }
                    appendLine("这些不是固定剧本：根据时间让情绪逐渐消退、叠加或被新事实扭转，而非每次从零开始。")
                }
            }
            if (emotionHistory.length() > 0) {
                appendLine("最近情绪变化的前因（避免无理由性格跳变）：")
                for (i in maxOf(0, emotionHistory.length() - 2) until emotionHistory.length()) {
                    val prior = emotionHistory.optJSONObject(i) ?: continue
                    appendLine("· ${prior.optString("feeling")}，源于${prior.optString("cause")}")
                }
            }
            bonds?.keys()?.asSequence()?.take(8)?.forEach { id ->
                val bond = bonds?.optJSONObject(id) ?: return@forEach
                appendLine("· 对${if (id == "user") "用户" else "角色$id"}的当前私人看法：${bond.optString("interpretation")}；因为${bond.optString("reason")}；此前想法：${bond.optString("priorThought")}；累计${bond.optInt("observations")}次实际互动推断。")
                appendLine("  不是绝对结论，观点可以纠结、相互矛盾，不能只凭一次聊天就彻底爱上/讨厌。")
            }
            if (corrections.length() > 0) {
                val last = corrections.optJSONObject(corrections.length() - 1)
                appendLine("自我修正：${last?.optString("realization")}；下次尝试：${last?.optString("nextTime")}。不要反复口头忏悔，以行动表现。")
            }
            if (voice.length() > 0) {
                appendLine("角色近期实际说过的话（延续自己的语言节奏、称呼、话题与情绪表达习惯；不复制原话）：")
                for (i in maxOf(0, voice.length() - 3) until voice.length()) {
                    appendLine("· ${voice.optJSONObject(i)?.optString("text")}")
                }
            }
            appendLine("愿望可以并存、冲突和暂停。选择前考虑性格、时间、用户边界与执行能力；只从工具回执确认成功，失败后可反省并改法。")
        }.trim()
    }
}
