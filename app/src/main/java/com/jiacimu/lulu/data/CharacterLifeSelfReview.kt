package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * A daily opportunity for the SAME character to reconsider their existing life.
 * This is only a context projection. It neither changes a goal nor creates a
 * schedule: the normal decision/executor must explicitly do both.
 */
internal object CharacterLifeSelfReview {
    fun localDay(now: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        now.atZone(zone).toLocalDate().toString()

    fun isDue(lastReviewedDay: String, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        lastReviewedDay != localDay(now, zone)

    fun context(context: Context, characterId: String, now: Instant = Instant.now()): String {
        val current = CharacterLifeVisibility.matters(
            characterId, now, plans = RoleDayScheduleStore.plans(context, characterId),
        )
        val mind = CharacterInnerLifeStore.snapshot(characterId)
        val pausedMotives = CharacterMotiveState.paused(mind.optJSONArray("motives"))
        val pausedConcerns = mind.optJSONArray("openConcerns").let { records ->
            (0 until (records?.length() ?: 0)).mapNotNull { records?.optJSONObject(it) }
                .filter { it.optString("lifecycleState") == "paused" }
        }
        val pausedLearning = CharacterLearningAgenda.goals(characterId, now)
            .filter { it.optString("status") == "paused" }
        // Earlier versions may already have hidden older unanswered curiosity.
        // Reviewing it is optional; this code never reopens it automatically.
        val dormant = CharacterCuriosityRuntime.snapshot(characterId).optJSONArray("dormantQuestions")
        val pastCuriosity = (0 until (dormant?.length() ?: 0)).mapNotNull { dormant?.optJSONObject(it) }

        if (current.isEmpty() && pausedMotives.isEmpty() &&
            pausedConcerns.isEmpty() && pausedLearning.isEmpty() && pastCuriosity.isEmpty()) return ""

        return buildString {
            appendLine("【今天自己的心事整理｜仅这一次自主决策里的回顾，不是自动清理命令】")
            appendLine("此刻本地日期=${localDay(now)}。请真正回顾下列已存档的心事与具体日程，以自己目前的兴趣、责任、情绪、经历、已有真实尝试和时间精力判断：哪些想继续、哪些可以放下、哪些突然想起来值得今天或以后做。可以不安排，也可以在同一轮安排几件不同的事，数量和日期由你选择；不是每日打卡或给用户交作业。")
            appendLine("心事列表是尚未解决的本人想法，并非已做成的事。不要从过时的搜索标题臆造今日活动状态；不能仅靠经过七天或一次搜索就自动认定已解决。")
            current.forEach { item ->
                appendLine(JSONObject().put("category", item.category)
                    .put("id", item.sourceId).put("owner", item.sourceKind)
                    .put("title", item.title).put("state", item.detail.take(330))
                    .put("observedAt", item.at.toString()).toString())
            }
            pausedMotives.forEach { m ->
                appendLine(JSONObject().put("category", "暂缓的持续意愿")
                    .put("motiveId", m.optString("id"))
                    .put("title", m.optString("aim")).put("why", m.optString("why")).toString())
            }
            pausedConcerns.forEach { c ->
                appendLine(JSONObject().put("category", "已暂停的挂心")
                    .put("threadId", c.optString("id")).put("title", c.optString("focus"))
                    .put("why", c.optString("whyItMatters")).toString())
            }
            pausedLearning.forEach { g ->
                appendLine(JSONObject().put("category", "已暂缓的琢磨")
                    .put("goalId", g.optString("id")).put("title", g.optString("topic"))
                    .put("question", g.optString("question")).toString())
            }
            pastCuriosity.forEach { q ->
                appendLine(JSONObject().put("category", "从前搁置的好奇（不代表现在还想做）")
                    .put("threadId", CharacterCuriosityRuntime.identity(q))
                    .put("title", q.optString("topic"))
                    .put("question", q.optString("question")).toString())
            }
            appendLine("【想法和执行如何真正联动】")
            appendLine("① 继续：沿用原ID修订，不要另开一个同义心事。放下已有持续意愿用innerLife.motives的release，恢复用resume；放下挂心用appraisal.pendingConcerns的dismissed，真有证据已解决才用settled，恢复用resume，均指定原threadId。学习事项可以在steps选learn_topic，args={op:pause|resume|drop,goalId:准确ID}；好奇可以用curiosity={threadId,topic,question,status:dropped}放下（topic和question须为原来的准确内容）。")
            appendLine("② 想做：立刻做就在action/steps选择已授权的实际工具；稍后做就在同一次steps中选择一个或多个plan_personal_activity，分别填写title、intent、未来dueAt(含时区)、activityAction、activityArgs。学习要沿用goalId放在activityArgs里，持续意愿可用已有motiveId。不是只写‘明天去做’或nextStep文字就算排好了。")
            appendLine("③ 已有日程：优先认原planId，不重复创建；仍想做就保留，时间不合适用reschedule_personal_activity，确实放下用cancel_personal_activity。若放下的目标已有关联未来安排，考虑是否同轮取消，别留下僵尸闹钟。")
            appendLine("④ 到期仍回到你的同一自主感知，由原执行器逐项核权限与回执；排好日程不等于已行动、查过不等于已读懂、发出消息不等于对方满意。不因清单多就强迫自己做完，不为整理而联系或打扰用户。")
        }.trim()
    }
}
