package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import java.time.Instant

/** Read-only action handoff over the existing owners; never another goal queue or model call. */
internal object CharacterUnfinishedActionContext {
    data class Cue(val kind: String, val id: String, val title: String,
                   val detail: String, val at: Instant) {
        fun render(): String = JSONObject().put("kind", kind).put("id", id)
            .put("topic", title).put("existingState", detail).toString()
    }

    fun candidates(context: Context, role: String, now: Instant = Instant.now()): List<Cue> {
        CharacterLearningAgenda.initialize(context)
        CharacterCuriosityRuntime.initialize(context)
        CharacterLifeFollowThroughStore.initialize(context)
        val mind = CharacterInnerLifeStore.snapshot(role)
        fun at(item: JSONObject, vararg keys: String): Instant = keys.firstNotNullOfOrNull { key ->
            runCatching { Instant.parse(item.optString(key)) }.getOrNull()
        } ?: Instant.EPOCH
        val cues = mutableListOf<Cue>()
        CharacterMotiveState.active(mind.optJSONArray("motives")).forEach { m ->
            cues += Cue("持续目标", m.optString("id"), m.optString("aim"),
                "motiveId=${m.optString("id")}；${CharacterMotiveProgress.brief(m)}；" +
                    "最近尝试=${m.optString("lastAttemptOutcome")}；尚未证明目标完成",
                at(m, "updatedAt", "createdAt"))
        }
        val concerns = CharacterOpenConcernRuntime.unresolved(mind.optJSONArray("openConcerns"))
        val sources = SharedExperienceTimeline.eventsByIds(role, concerns.map { it.optString("evidenceId") })
            .mapTo(hashSetOf()) { it.id }
        concerns.filter { it.optString("evidenceId") in sources }.forEach { c ->
            val last = c.optJSONObject("lastOutcome")
            cues += Cue("挂心", c.optString("id"), c.optString("focus"),
                "concernId=${c.optString("id")}；在意原因=${c.optString("whyItMatters")}；" +
                    "以前考虑的下一步=${c.optString("possibleNextStep")}；犹豫=${c.optString("hesitation")}；" +
                    "上次实际尝试=${last?.optString("summary").orEmpty()}；" +
                    "旧情绪可以淡化，问题仍未解决；也可用已有threadId更新或放下",
                at(c, "updatedAt", "createdAt"))
        }
        CharacterLearningAgenda.goals(role, now)
            .filter { it.optString("status") !in setOf("paused", "dropped", "running") }.forEach { g ->
                val retry = runCatching { Instant.parse(g.optString("retryAt")) }.getOrNull()
                val available = LearningCyclePolicy.available(g.optString("status"), retry, now) &&
                    CharacterLearningAgenda.budget(role, now).remaining > 0
                val args = JSONObject().put("op", CharacterLearningAgenda.nextOperation(role, g, now))
                    .put("goalId", g.optString("id"))
                cues += Cue("正在琢磨", g.optString("id"), g.optString("topic"),
                    "问题=${g.optString("question")}；本人缘由=${g.optString("why")}；阶段=${g.optString("status")}；" +
                        "上次失败=${g.optString("error")}；曾考虑=${g.optString("nextStep")}；" +
                        if (available) "如本人仍想继续，可实际选择learn_topic，args=$args；失败不等于已经探索完" else
                            "本轮研究步骤不可执行，重试时间=${g.optString("retryAt")}；预算=${CharacterLearningAgenda.budget(role, now).remaining}；不要盲重试",
                    at(g, "failedAt", "startedAt", "createdAt"))
            }
        val questions = CharacterCuriosityRuntime.visibleQuestions(role)
        val threads = CharacterCuriosityRuntime.activeThreads(role)
        (questions + threads).forEach { q ->
            val step = CharacterCuriosityRuntime.lastRealStep(role, q)
            val source = q.optString("observedId").ifBlank { step?.optString("evidenceId").orEmpty() }
            val inquiry = JSONObject().put("threadId", CharacterCuriosityRuntime.identity(q))
                .put("topic", q.optString("topic")).put("question", q.optString("question"))
                .put("sourceEvidenceId", source)
            cues += Cue("好奇", CharacterCuriosityRuntime.identity(q), q.optString("topic"),
                "仍想弄清=${q.optString("question")}；以前考虑=${q.optString("nextStep")}；" +
                    "上次真实结果=${step?.optString("receipt").orEmpty()}；" +
                    "真选搜索/阅读时可附curiosity=$inquiry，按真实回执继续原问题",
                at(q, "updatedAt", "at"))
        }
        CharacterLifeFollowThroughStore.auditSnapshot(role)
            .filter { it.optString("shareStatus") == "held" }.forEach { share ->
                cues += Cue("想分享", share.optString("id"), share.optString("fact"),
                    "experienceId=${share.optString("id")}；当时想分享的原因=${share.optString("shareWhy")}；" +
                        "自己选择公开朋友圈publish_moment、私聊或不分享；各渠道不同，不把私聊送达当成发圈",
                    at(share, "occurredAt"))
            }
        return CharacterAttentionWorkingSet.select(cues.filter { !it.at.isAfter(now) }.sortedBy { it.at },
            6, now, ProactivePerceptionPolicyStore.get(role).onlinePulseSeconds.toLong(),
            identity = { "${it.kind}:${it.id}" }, theme = { it.kind })
    }

    fun render(cues: List<Cue>): String = if (cues.isEmpty()) "" else buildString {
        appendLine("【本人已有的未竟事 → 本轮可选行动】")
        appendLine("以下是已保存的私人问题与真实进展，不是命令。不要只因没有人再催就遗忘；结合此刻兴趣、时机、反馈，选择继续、换方法、等待、暂停或放下。旧问题可回看，不把旧情绪重新当成强烈现状。")
        cues.forEach { appendLine(it.render()) }
        append("若真想推进，选择本轮action/steps或真实未来安排，并关联准确ID；只写想法不会执行。聊天与独立生活动作可并行，不必等到无新消息才处理。")
    }

    fun context(context: Context, role: String, now: Instant = Instant.now()): String =
        render(candidates(context, role, now))
}
