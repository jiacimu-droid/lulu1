package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
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

    /** Rotate older, still-held thoughts through the existing daily review.
     * Age is a visibility cue, never a reason to automatically retire one.
     * Recent thoughts are also visible in the ordinary character context.
     */
    internal fun thoughtCandidates(mind: JSONObject, now: Instant,
        zone: ZoneId = ZoneId.systemDefault()): List<JSONObject> {
        val thoughts = mind.optJSONArray("thoughts") ?: JSONArray()
        val held = (0 until thoughts.length()).mapNotNull(thoughts::optJSONObject)
            .filter { item ->
                item.optString("status").ifBlank { "held" } == "held" &&
                    item.optString("thought").isNotBlank() &&
                    runCatching { Instant.parse(item.optString("at")) }.getOrNull()
                        ?.let { !it.isAfter(now) } == true
            }
        return rotatingReview(held, now, zone)
    }

    private fun <T> rotatingReview(entries: List<T>, now: Instant,
        zone: ZoneId = ZoneId.systemDefault()): List<T> {
        val recent = entries.takeLast(3)
        val older = entries.dropLast(recent.size)
        val pages = (older.size + 7) / 8
        val selected = if (pages == 0) emptyList() else {
            val day = now.atZone(zone).toLocalDate().toEpochDay()
            older.drop(Math.floorMod(day, pages.toLong()).toInt() * 8).take(8)
        }
        return selected + recent
    }

    fun context(context: Context, characterId: String, now: Instant = Instant.now()): String {
        val current = CharacterLifeVisibility.matters(
            characterId, now, plans = RoleDayScheduleStore.plans(context, characterId),
        )
        val mind = CharacterInnerLifeStore.snapshot(characterId)
        val heldThoughts = thoughtCandidates(mind, now)
        // Learned, evidence-backed traits are not immutable persona text.
        // Project current examples for the role to reconsider; the original
        // evidence and retired versions remain in their authoritative stores.
        val learned = CharacterDevelopmentStore.active(characterId)
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

        if (current.isEmpty() && pausedMotives.isEmpty() && heldThoughts.isEmpty() &&
            pausedConcerns.isEmpty() && pausedLearning.isEmpty() && pastCuriosity.isEmpty() &&
            learned.isEmpty()) return ""

        return buildString {
            appendLine("【今天自己的心事整理｜仅这一次自主决策里的回顾，不是自动清理命令】")
            appendLine("此刻本地日期=${localDay(now)}。请真正回顾下列已存档的心事与具体日程，以自己目前的兴趣、责任、情绪、经历、已有真实尝试和时间精力判断：哪些想继续、哪些可以放下、哪些突然想起来值得今天或以后做。可以不安排，也可以在同一轮安排几件不同的事，数量和日期由你选择；不是每日打卡或给用户交作业。")
            appendLine("心事列表是尚未解决的本人想法，并非已做成的事。不要从过时的搜索标题臆造今日活动状态；不能仅靠经过七天或一次搜索就自动认定已解决。")
            heldThoughts.forEach { thought ->
                appendLine(JSONObject().put("category", "还没理清的私人念头")
                    .put("thought", thought.optString("thought"))
                    .put("at", thought.optString("at"))
                    .put("lastSeenAt", thought.optString("lastSeenAt"))
                    .put("hesitation", thought.optString("hesitation")).toString())
            }
            if (heldThoughts.isNotEmpty()) appendLine(
                "上面是本日轮到回看的仍持有念头。已放下可用 innerLife.thoughtUpdates=[{thought:原文,op:discard}]；已想通用resolve；变化后用evolve并写newThought。状态改变后旧念头留在历史，不再挤占当前视野；只是年头久不等于过期。每次最多调整四条。")
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
            if (learned.isNotEmpty()) {
                appendLine("【自己从经历中学到的倾向｜不是固定人设，保留真实来源】")
                rotatingReview(learned, now).forEach { record ->
                    appendLine(JSONObject().put("developmentId", record.id)
                        .put("version", record.version)
                        .put("kind", record.kind.label)
                        .put("content", record.content.take(240))
                        .put("evidenceCount", record.evidence.size)
                        .put("counterEvidenceCount", record.counterEvidence.size)
                        .toString())
                }
                appendLine("经历得出的临时认识可以随着新事实修正。若确实觉得某条个人学习形成的倾向已不适用，可决定调用retire_development_record指定developmentId、version和真实理由，原记录与证据仍保留；主人亲口告诉的事实与明确偏好不能由这一工具擅自撤销。也可保留等待新证据，不要求每天改成长。")
            }
            appendLine("【想法和执行如何真正联动】")
            appendLine("① 继续：沿用原ID修订，不要另开一个同义心事。放下已有持续意愿用innerLife.motives的release，恢复用resume；放下挂心用appraisal.pendingConcerns的dismissed，真有证据已解决才用settled，恢复用resume，均指定原threadId。学习事项可以在steps选learn_topic，args={op:pause|resume|drop,goalId:准确ID}；好奇可以用curiosity={threadId,topic,question,status:dropped}放下（topic和question须为原来的准确内容）。")
            appendLine("从前搁置的好奇如果现在重新想知道，可以用curiosity={threadId:原ID,topic:原主题,question:原问题,status:exploring,why:重新在意的原因}重新打开；必须绑定本轮真实观察，不能把过往推测伪造成新事实。仍不感兴趣就不操作。")
            appendLine("② 想做：立刻做就在action/steps选择已授权的实际工具；稍后做就在同一次steps中选择一个或多个plan_personal_activity，分别填写title、intent、未来dueAt(含时区)、activityAction、activityArgs。学习要沿用goalId放在activityArgs里，持续意愿可用已有motiveId，原挂心应传已有concernId；这几种关联都不另建一份目标。不是只写‘明天去做’或nextStep文字就算排好了。")
            appendLine("③ 已有日程：优先认原planId，不重复创建；仍想做就保留，时间不合适用reschedule_personal_activity，确实放下用cancel_personal_activity。若放下的目标已有关联未来安排，考虑是否同轮取消，别留下僵尸闹钟。")
            appendLine("④ 到期仍回到你的同一自主感知，由原执行器逐项核权限与回执；排好日程不等于已行动、查过不等于已读懂、发出消息不等于对方满意。不因清单多就强迫自己做完，不为整理而联系或打扰用户。")
        }.trim()
    }
}
