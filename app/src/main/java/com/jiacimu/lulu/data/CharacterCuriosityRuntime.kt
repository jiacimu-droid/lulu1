package com.jiacimu.lulu.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * Questions are subjective; experiences are executor receipts. Neither creates a
 * permanent character trait without the existing evidence-driven development system.
 * Per-character state survives restarts, can be revised, and retracts deleted sources.
 */
internal object CharacterCuriosityRuntime {
    private const val PREFS = "lulu_curiosity_threads_v1"
    private const val MAX_THREADS = 8
    private const val MAX_STEPS = 5
    private const val MAX_QUESTIONS = 6
    private var prefs: SharedPreferences? = null

    @Synchronized fun initialize(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    @Synchronized fun clear(characterId: String) {
        if (characterId.isNotBlank()) prefs?.edit()?.remove(characterId)?.commit()
    }

    @Synchronized fun snapshot(characterId: String): JSONObject = runCatching {
        JSONObject(prefs?.getString(characterId, "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    private fun save(characterId: String, state: JSONObject) {
        prefs?.edit()?.putString(characterId, state.toString())?.commit()
    }

    private fun normalized(value: String) = value.lowercase().filter(Char::isLetterOrDigit)

    private fun proposalFields(proposal: JSONObject?): List<String>? {
        val p = proposal ?: return null
        val topic = p.optString("topic").replace(Regex("\\s+"), " ").trim().take(90)
        val question = p.optString("question").replace(Regex("\\s+"), " ").trim().take(180)
        if (normalized(topic).length < 2 || question.length < 3) return null
        return listOf(topic, question, p.optString("why").trim().take(180),
            p.optString("nextStep").trim().take(180))
    }

    /** A genuine observed situation may make the character wonder; it is NOT progress. */
    @Synchronized fun recordInquiry(
        characterId: String, proposal: JSONObject?, observedId: String,
        observedText: String, now: Instant = Instant.now(),
    ) {
        if (prefs == null || characterId.isBlank() || observedId.isBlank() ||
            observedText.isBlank()) return
        val fields = proposalFields(proposal) ?: return
        val root = snapshot(characterId)
        val progress = root.optJSONArray("threads") ?: JSONArray()
        if ((0 until progress.length()).any {
                normalized(progress.optJSONObject(it)?.optString("topic").orEmpty()) == normalized(fields[0]) &&
                    progress.optJSONObject(it)?.optString("status") == "exploring"
            }) return
        val old = root.optJSONArray("questions") ?: JSONArray()
        val retained = (0 until old.length()).mapNotNull(old::optJSONObject).filterNot {
            normalized(it.optString("topic")) == normalized(fields[0])
        }.takeLast(MAX_QUESTIONS - 1)
        val item = JSONObject().put("topic", fields[0]).put("question", fields[1])
            .put("why", fields[2]).put("nextStep", fields[3])
            .put("observedId", observedId).put("observed", observedText.take(220))
            .put("at", now.toString())
        root.put("questions", JSONArray().apply {
            retained.forEach { put(it) }
            put(item)
        })
        save(characterId, root)
    }

    /** Never credit a model's claimed action without a real, time-lined receipt. */
    @Synchronized fun recordOutcome(
        characterId: String, proposal: JSONObject?, action: String, succeeded: Boolean,
        receipt: String, evidenceId: String, now: Instant = Instant.now(),
    ) {
        if (!succeeded || action == "silent" || characterId.isBlank() ||
            receipt.isBlank() || evidenceId.isBlank() || prefs == null) return
        val fields = proposalFields(proposal) ?: return
        val root = snapshot(characterId)
        val old = root.optJSONArray("threads") ?: JSONArray()
        val entries = (0 until old.length()).mapNotNull(old::optJSONObject)
            .map { JSONObject(it.toString()) }.toMutableList()
        val index = entries.indexOfFirst { normalized(it.optString("topic")) == normalized(fields[0]) }
        val current = if (index >= 0) entries.removeAt(index) else JSONObject()
        val steps = current.optJSONArray("steps") ?: JSONArray()
        if ((0 until steps.length()).any { steps.optJSONObject(it)?.optString("evidenceId") == evidenceId }) return
        val kept = JSONArray()
        for (i in maxOf(0, steps.length() - MAX_STEPS + 1) until steps.length()) kept.put(steps.opt(i))
        val status = if (proposal?.optString("status") == "satisfied") "satisfied" else "exploring"
        kept.put(JSONObject().put("evidenceId", evidenceId).put("at", now.toString())
            .put("action", action.take(60)).put("receipt", receipt.take(240))
            .put("question", fields[1]).put("why", fields[2])
            .put("nextStep", fields[3]).put("status", status))
        current.put("topic", fields[0]).put("steps", kept)
            .put("question", fields[1]).put("why", fields[2])
            .put("nextStep", fields[3]).put("status", status)
            .put("updatedAt", now.toString())
        entries.add(current)
        root.put("threads", JSONArray().apply { entries.takeLast(MAX_THREADS).forEach { put(it) } })
        // An actually explored question is no longer an untouched curiosity.
        val questions = root.optJSONArray("questions") ?: JSONArray()
        root.put("questions", JSONArray().apply {
            for (i in 0 until questions.length()) {
                val q = questions.optJSONObject(i) ?: continue
                if (normalized(q.optString("topic")) != normalized(fields[0])) put(q)
            }
        })
        // A successful new way forward clears the transient failed attempt on this topic.
        val failed = root.optJSONArray("failures") ?: JSONArray()
        root.put("failures", JSONArray().apply {
            for (i in 0 until failed.length()) {
                val f = failed.optJSONObject(i) ?: continue
                if (normalized(f.optString("topic")) != normalized(fields[0])) put(f)
            }
        })
        save(characterId, root)
    }

    /** The character may lose interest without inventing a completed exploration. */
    @Synchronized fun releaseInterest(
        characterId: String, proposal: JSONObject?, now: Instant = Instant.now(),
    ) {
        if (prefs == null || characterId.isBlank() || proposal?.optString("status") != "dropped") return
        val fields = proposalFields(proposal) ?: return
        val root = snapshot(characterId)
        val threads = root.optJSONArray("threads") ?: JSONArray()
        val index = (0 until threads.length()).firstOrNull {
            normalized(threads.optJSONObject(it)?.optString("topic").orEmpty()) == normalized(fields[0])
        }
        val questions = root.optJSONArray("questions") ?: JSONArray()
        val hasQuestions = (0 until questions.length()).any {
            normalized(questions.optJSONObject(it)?.optString("topic").orEmpty()) == normalized(fields[0])
        }
        if (index == null && !hasQuestions) return
        if (index != null) {
            threads.optJSONObject(index)?.put("status", "dropped")
                ?.put("releaseReason", fields[2])
                ?.put("releasedAt", now.toString())
            root.put("threads", threads)
        }
        root.put("questions", JSONArray().apply {
            for (i in 0 until questions.length()) {
                val q = questions.optJSONObject(i) ?: continue
                if (normalized(q.optString("topic")) != normalized(fields[0])) put(q)
            }
        })
        save(characterId, root)
    }

    /** Failure is useful feedback for action selection, never evidence of exploration. */
    @Synchronized fun recordFailure(
        characterId: String, proposal: JSONObject?, action: String,
        reason: String, now: Instant = Instant.now(),
    ) {
        if (prefs == null || characterId.isBlank() || action == "silent" || reason.isBlank()) return
        val fields = proposalFields(proposal) ?: return
        val root = snapshot(characterId)
        val previous = root.optJSONArray("failures") ?: JSONArray()
        val same = (0 until previous.length()).mapNotNull(previous::optJSONObject)
            .firstOrNull { normalized(it.optString("topic")) == normalized(fields[0]) &&
                it.optString("action") == action }
        val repeats = same?.optInt("repeats", 0)?.plus(1)?.coerceAtMost(5) ?: 1
        root.put("failures", JSONArray().apply {
            val retained = (0 until previous.length()).mapNotNull(previous::optJSONObject)
                .filterNot { normalized(it.optString("topic")) == normalized(fields[0]) &&
                    it.optString("action") == action }.takeLast(5)
            retained.forEach { put(it) }
            put(JSONObject().put("topic", fields[0]).put("action", action.take(60))
                .put("reason", reason.take(200)).put("repeats", repeats).put("at", now.toString()))
        })
        save(characterId, root)
    }

    /** Retract dependent subjective questions and objectively confirmed steps together. */
    @Synchronized fun invalidateEvidence(eventId: String) {
        if (eventId.isBlank()) return
        val store = prefs ?: return
        store.all.keys.forEach { characterId ->
            val root = snapshot(characterId)
            var changed = false
            val threads = root.optJSONArray("threads") ?: JSONArray()
            val keptThreads = JSONArray()
            for (i in 0 until threads.length()) {
                val t = threads.optJSONObject(i) ?: continue
                val steps = t.optJSONArray("steps") ?: JSONArray()
                val kept = JSONArray()
                for (j in 0 until steps.length()) {
                    val step = steps.optJSONObject(j) ?: continue
                    if (step.optString("evidenceId") == eventId) changed = true
                    else kept.put(step)
                }
                if (kept.length() == 0) continue
                if (kept.length() != steps.length()) {
                    val last = kept.optJSONObject(kept.length() - 1) ?: continue
                    t.put("steps", kept).put("updatedAt", last.optString("at"))
                    listOf("question", "why", "nextStep", "status").forEach { t.put(it, last.optString(it)) }
                }
                keptThreads.put(t)
            }
            val questions = root.optJSONArray("questions") ?: JSONArray()
            val keptQuestions = JSONArray()
            for (i in 0 until questions.length()) {
                val q = questions.optJSONObject(i) ?: continue
                if (q.optString("observedId") == eventId) changed = true else keptQuestions.put(q)
            }
            if (changed) {
                root.put("threads", keptThreads).put("questions", keptQuestions)
                save(characterId, root)
            }
        }
    }

    fun promptSection(
        characterId: String, displayName: String, recentActions: List<String>,
        now: Instant = Instant.now(),
    ): String {
        val profile = CharacterLifeStore.state(characterId).optJSONObject("profile")
        val learned = CharacterDevelopmentStore.active(characterId)
            .filter { it.kind == DevelopmentKind.Interest || it.kind == DevelopmentKind.Preference }
            .takeLast(4)
        val root = snapshot(characterId)
        val threads = root.optJSONArray("threads") ?: JSONArray()
        val active = (0 until threads.length()).mapNotNull(threads::optJSONObject)
            .filter { it.optString("status") != "satisfied" }.takeLast(4)
        val questions = root.optJSONArray("questions") ?: JSONArray()
        val unanswered = (0 until questions.length()).mapNotNull(questions::optJSONObject).takeLast(3)
        val failures = root.optJSONArray("failures") ?: JSONArray()
        val recentFailures = (0 until failures.length()).mapNotNull(failures::optJSONObject).takeLast(3)
        val lived = SharedExperienceTimeline.all(characterId).asReversed().filter {
            it.speaker == displayName && (it.channel.startsWith("独自") ||
                it.channel.startsWith("数字世界") || it.channel.contains("朋友圈") ||
                it.channel.contains("日记") || it.channel.startsWith("现实世界窗口"))
        }.take(4)
        return buildString {
            appendLine("【持续好奇心｜允许新探索、深入、放弃或独处】")
            profile?.optString("interests")?.takeIf(String::isNotBlank)?.let {
                appendLine("用户设定的初始兴趣：" + it.take(230))
            }
            if (learned.isNotEmpty()) appendLine("反复真实经历形成的兴趣：" +
                learned.joinToString("；") { it.content.take(110) })
            if (unanswered.isNotEmpty()) {
                appendLine("之前由真实观察引发、尚未采取行动的疑问（只是好奇，不等于经历）：")
                unanswered.forEach { q ->
                    appendLine("- " + q.optString("topic") + "：" + q.optString("question") +
                        "；当时确实观察到：" + q.optString("observed").take(120) +
                        "；可能下一步：" + q.optString("nextStep"))
                }
            }
            if (active.isNotEmpty()) {
                appendLine("已经真实行动过、仍可能继续探索的主题：")
                active.forEach { t ->
                    val steps = t.optJSONArray("steps") ?: JSONArray()
                    val last = steps.optJSONObject(steps.length() - 1)
                    val hours = runCatching {
                        Duration.between(Instant.parse(t.optString("updatedAt")), now).toHours()
                    }.getOrDefault(0L).coerceAtLeast(0L)
                    appendLine("- " + t.optString("topic") + "；问题=" + t.optString("question") +
                        "；最近真实行动结果=" + last?.optString("receipt").orEmpty().take(150) +
                        "；约" + hours + "小时前；可选下一步=" + t.optString("nextStep"))
                }
            }
            if (recentFailures.isNotEmpty()) {
                appendLine("最近实际尝试未成功的办法（只是失败回执，不代表主题不值得继续）：")
                recentFailures.forEach { f ->
                    appendLine("- " + f.optString("topic") + "：" + f.optString("action") +
                        "；原因=" + f.optString("reason") + "；同类尝试=" + f.optInt("repeats"))
                }
            }
            if (lived.isNotEmpty()) {
                appendLine("最近自己真实经历过的事情：")
                lived.forEach { appendLine("- " + it.channel + "：" +
                    it.evidenceContent.replace(Regex("\\s+"), " ").take(150)) }
            }
            if (recentActions.takeLast(5).count { it == "SILENT" } >= 3)
                appendLine("最近多次安静：区分「不想打扰用户」与「自己不想做事」；若有正在在意的事情，认真比较可执行的小行动，但不强制活跃。")
            appendLine("从人设、兴趣、疑问与眼前真实环境提出自己的选择：继续、接触新知识、体验、表达、休息。行动后才能形成新经历；别把探索当成定时表演。")
        }.trim()
    }
}
