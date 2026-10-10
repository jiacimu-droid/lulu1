package com.jiacimu.lulu.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * Role-owned curiosity. Proposals are intentions; only real executor receipts advance a thread.
 * It neither imposes action quotas nor duplicates the character's stable persona.
 */
internal object CharacterCuriosityRuntime {
    private const val PREFS = "lulu_curiosity_threads_v1"
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

    private fun key(value: String) = value.lowercase().filter(Char::isLetterOrDigit)

    @Synchronized fun recordOutcome(
        characterId: String, proposal: JSONObject?, action: String, succeeded: Boolean,
        receipt: String, evidenceId: String, now: Instant = Instant.now(),
    ) {
        if (!succeeded || action == "silent" || characterId.isBlank() ||
            receipt.isBlank() || evidenceId.isBlank() || proposal == null || prefs == null) return
        val topic = proposal.optString("topic").trim().replace(Regex("\\s+"), " ").take(90)
        val question = proposal.optString("question").trim().replace(Regex("\\s+"), " ").take(180)
        val why = proposal.optString("why").trim().take(180)
        val next = proposal.optString("nextStep").trim().take(180)
        if (key(topic).length < 2 || question.length < 3) return
        val status = if (proposal.optString("status") == "satisfied") "satisfied" else "exploring"
        val root = snapshot(characterId)
        val past = root.optJSONArray("threads") ?: JSONArray()
        val entries = (0 until past.length()).mapNotNull(past::optJSONObject)
            .map { JSONObject(it.toString()) }.toMutableList()
        val existing = entries.indexOfFirst { key(it.optString("topic")) == key(topic) }
        val thread = if (existing >= 0) entries.removeAt(existing) else JSONObject()
        val steps = thread.optJSONArray("steps") ?: JSONArray()
        if ((0 until steps.length()).any { steps.optJSONObject(it)?.optString("evidenceId") == evidenceId }) return
        val kept = JSONArray()
        for (i in maxOf(0, steps.length() - 4) until steps.length()) kept.put(steps.opt(i))
        kept.put(JSONObject().put("evidenceId", evidenceId).put("at", now.toString())
            .put("action", action.take(60)).put("receipt", receipt.take(240))
            .put("question", question).put("why", why).put("nextStep", next).put("status", status))
        thread.put("topic", topic).put("steps", kept)
            .put("question", question).put("why", why).put("nextStep", next)
            .put("status", status).put("updatedAt", now.toString())
        entries.add(thread)
        root.put("threads", JSONArray().apply { entries.takeLast(8).forEach { put(it) } })
        prefs?.edit()?.putString(characterId, root.toString())?.commit()
    }

    /** Remove only the deleted raw evidence; independently witnessed progress survives. */
    @Synchronized fun invalidateEvidence(eventId: String) {
        val store = prefs ?: return
        if (eventId.isBlank()) return
        store.all.keys.forEach { characterId ->
            val root = snapshot(characterId)
            val threads = root.optJSONArray("threads") ?: return@forEach
            val keptThreads = JSONArray()
            var changed = false
            for (i in 0 until threads.length()) {
                val thread = threads.optJSONObject(i) ?: continue
                val steps = thread.optJSONArray("steps") ?: JSONArray()
                val kept = JSONArray()
                for (j in 0 until steps.length()) {
                    val step = steps.optJSONObject(j) ?: continue
                    if (step.optString("evidenceId") == eventId) changed = true else kept.put(step)
                }
                if (kept.length() == 0) continue
                if (kept.length() != steps.length()) {
                    val latest = kept.optJSONObject(kept.length() - 1) ?: continue
                    thread.put("steps", kept).put("updatedAt", latest.optString("at"))
                    listOf("question", "why", "nextStep", "status").forEach {
                        thread.put(it, latest.optString(it))
                    }
                }
                keptThreads.put(thread)
            }
            if (changed) {
                root.put("threads", keptThreads)
                store.edit().putString(characterId, root.toString()).commit()
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
        val threads = snapshot(characterId).optJSONArray("threads") ?: JSONArray()
        val active = (0 until threads.length()).mapNotNull(threads::optJSONObject)
            .filter { it.optString("status") != "satisfied" }.takeLast(4)
        val lived = SharedExperienceTimeline.all(characterId).asReversed().filter {
            it.speaker == displayName && (it.channel.startsWith("独自") ||
                it.channel.startsWith("数字世界") || it.channel.contains("朋友圈") ||
                it.channel.contains("日记") || it.channel.startsWith("现实世界窗口"))
        }.take(4)
        return buildString {
            appendLine("【好奇心与生活选择｜不是行为配额】")
            profile?.optString("interests")?.takeIf(String::isNotBlank)?.let {
                appendLine("已有兴趣：" + it.take(230))
            }
            if (learned.isNotEmpty()) appendLine("经真实经历逐渐形成的兴趣：" +
                learned.joinToString("；") { it.content.take(110) })
            if (active.isNotEmpty()) {
                appendLine("已经实际尝试过、仍可能想继续了解的问题：")
                active.forEach { t ->
                    val steps = t.optJSONArray("steps") ?: JSONArray()
                    val last = steps.optJSONObject(steps.length() - 1)
                    val hours = runCatching {
                        Duration.between(Instant.parse(t.optString("updatedAt")), now).toHours()
                    }.getOrDefault(0L).coerceAtLeast(0L)
                    appendLine("- " + t.optString("topic") + "；疑问=" + t.optString("question") +
                        "；最近执行结果=" + last?.optString("receipt").orEmpty().take(150) +
                        "；约" + hours + "小时前；可能下一步=" + t.optString("nextStep"))
                }
            }
            if (lived.isNotEmpty()) {
                appendLine("最近自己真正经历、可能值得进一步留意的事情：")
                lived.forEach { appendLine("- " + it.channel + "：" +
                    it.evidenceContent.replace(Regex("\\s+"), " ").take(150)) }
            }
            if (recentActions.takeLast(5).count { it == "SILENT" } >= 3)
                appendLine("多次独处后，区分「不想打扰用户」和「自己也不想做任何事」；若确实想休息仍可以不行动。")
            appendLine("选择可来自自己的问题、愿望和环境：继续探索、真实阅读或游戏、观察场景、表达发现、独处。先找一个具体未知点，再找权威能力清单中真正能执行的下一步。")
            appendLine("不用制造惊奇或强制换主题；一次真实体验可能加深兴趣，也可能让兴趣自然结束。")
        }.trim()
    }
}
