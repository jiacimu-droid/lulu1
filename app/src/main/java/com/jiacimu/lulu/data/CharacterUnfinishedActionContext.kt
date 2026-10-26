package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import java.time.Instant

/** Read-only action handoff over the existing owners; never another goal queue or model call. */
internal object CharacterUnfinishedActionContext {
    data class Cue(val kind: String, val id: String, val title: String,
                   val detail: String, val at: Instant, val attentionPriority: Int = 0) {
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
                at(m, "updatedAt", "createdAt"),
                attentionPriority = (if (CharacterMotiveReview.from(m)?.dueAt?.isAfter(now) == false)
                    100 else 0) + (m.optInt("priority", 2) - 2).coerceAtLeast(0))
        }
        val concerns = CharacterOpenConcernRuntime.active(mind.optJSONArray("openConcerns"), now)
        val sources = SharedExperienceTimeline.eventsByIds(role, concerns.map { it.optString("evidenceId") })
            .mapTo(hashSetOf()) { it.id }
        concerns.filter { it.optString("evidenceId") in sources }.forEach { c ->
            val last = c.optJSONObject("lastOutcome")
            cues += Cue("挂心", c.optString("id"), c.optString("focus"),
                "concernId=${c.optString("id")}；在意原因=${c.optString("whyItMatters")}；" +
                    "以前考虑的下一步=${c.optString("possibleNextStep")}；犹豫=${c.optString("hesitation")}；" +
                    "上次实际尝试=${last?.optString("summary").orEmpty()}；" +
                    "旧情绪可以淡化，问题仍未解决；也可用已有threadId更新或放下",
                at(c, "updatedAt", "createdAt"),
                attentionPriority = (c.optInt("priority", 2) - 2).coerceAtLeast(0))
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
                        if (available) "如本人仍想继续，可实际选择learn_topic，args=$args；也可在plan_personal_activity中选择activityAction=learn_topic、activityArgs=$args，保留原goalId稍后继续；失败不等于已经探索完" else
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
        // Use the SAME sources as the sharing-urge gauge, not only 'held'.
        // An open experience that contributes to a saturated urge must have a
        // chance to enter the next real decision. This is a cue, not auto-send.
        val shareIds = CharacterLifeFollowThroughStore.sharingCandidates(role, now)
            .mapTo(hashSetOf()) { it.optString("id") }
        CharacterLifeFollowThroughStore.auditSnapshot(role)
            .filter { it.optString("id") in shareIds }.forEach { share ->
                val held = share.optString("shareStatus") == "held"
                cues += Cue("想分享", share.optString("id"), share.optString("fact"),
                    "experienceId=${share.optString("id")}；" +
                        (if (held) "本人明确想分享：${share.optString("shareWhy")}；尚未有发送回执；"
                        else "这件真实经历形成了累计分享欲，但尚未明确决定分享；") +
                        "若现在本人真想分享，可选择私聊message、好友可见朋友圈moment或有真实groupId的群聊，并附experienceId；不想说可以暂缓或放下",
                    at(share, "occurredAt"),
                    attentionPriority = if (held) 45 else 4)
            }
        return CharacterAttentionWorkingSet.select(cues.filter { !it.at.isAfter(now) }.sortedBy { it.at },
            6, now, ProactivePerceptionPolicyStore.get(role).onlinePulseSeconds.toLong(),
            identity = { "${it.kind}:${it.id}" }, theme = { it.kind },
            priority = { it.attentionPriority })
    }

    fun render(cues: List<Cue>): String = if (cues.isEmpty()) "" else buildString {
        appendLine("【本人已有的未竟事 → 本轮可选行动】")
        appendLine("以下是已保存的私人问题与真实进展，不是命令。最近想到的事与真正到期、本人赋予高优先级的旧目标都拥有注意机会；不等于马上必须执行，也不许忽略当前真实求助。结合此刻兴趣、时机、精力、权限与反馈，选择继续、换方法、等待、暂停或放下。旧问题可回看，不把旧情绪重新当成强烈现状。")
        // The working set already reserves recent and older interests. Give
        // one *older* item a readable comparison slot rather than leaving it
        // buried below the freshest event in a long prompt. No new storage,
        // second model call, tool selection or artificial action obligation.
        // For small sets select() preserves chronological input order,
        // while for large sets it puts newest first. Pick by the actual
        // evidence timestamp, not the presentation order.
        val independent = cues.minWithOrNull(compareBy<Cue> { it.at }.thenBy { it.id })
            ?: cues.first()
        appendLine("【本轮可独立回看的旧主题】${independent.kind}：${independent.title}；引用ID=${independent.id}")
        appendLine("请把它和眼前的新消息、其他愿望独立比较一次：此刻是否仍想推进、是否已有结果、能否做一小步、是否更愿等待或放下？这是思考机会，不是要强行行动；当下真实求助优先，不必把私人比较念给用户听。")
        cues.forEach { appendLine(it.render()) }
        appendLine("若真想推进，选择本轮action/steps或真实未来安排，并关联准确ID；只写想法不会执行。聊天与独立生活动作可并行，不必等到无新消息才处理。")
        appendLine(CharacterThoughtActionHandoff.guidance)
        appendLine("想稍后做时，在本轮steps选择tool=plan_personal_activity，args={title:具体活动,intent:为什么想做,dueAt:本人选择的未来ISO时间,activityAction:真实工具名,activityArgs:该工具参数}；关联持续目标时将motiveId放在args内，来自挂心则传原concernId，探索沿原goalId写入activityArgs。一次决定可以为多件独立心事选不同日期，不限制固定数量。先检查已有日程，同一件事已有时间就改期或沿用，不重复创建。")
        append("挂心是尚未解决的在意，记忆是所依据的事实；安排日程不代表挂心已解决，也不自动把每件挂心升级为核心记忆。探索沿原goalId继续；到点重新核实参数、时机与权限，可执行、改期或取消。无需为排日程单独增加模型调用，也不必强行把每个念头变成待办。")
    }

    fun context(context: Context, role: String, now: Instant = Instant.now()): String =
        render(candidates(context, role, now))
}
