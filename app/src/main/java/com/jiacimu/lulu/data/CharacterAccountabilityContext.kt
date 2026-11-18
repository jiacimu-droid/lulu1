package com.jiacimu.lulu.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Facts about obligations, never an invented success or inference that the user is at fault.
 * The scheduled action's receipt is the single source of truth for fulfillment.
 */
internal object CharacterAccountabilityContext {
    private val time = DateTimeFormatter.ofPattern("M月d日 HH:mm")

    fun prompt(characterId: String, now: Instant = Instant.now(), includeActiveDetails: Boolean = true): String {
        val tasks = CommitmentTaskStore.snapshot(characterId)
            .filter { it.status != CommitmentTaskStatus.Cancelled }
            .filter { includeActiveDetails || !it.status.isActive() }
            // Old one-off reminders must not push the current duty out of
            // the model's visible responsibility context.
            .sortedWith(compareBy<CommitmentTask> { !it.status.isActive() }
                .thenByDescending { it.updatedAt })
            .take(12)
        if (tasks.isEmpty() && !includeActiveDetails) return "未结责任及其真实回执已由统一责任上下文提供；没有回执不能宣称履行，日记与心声不能代替行动。"
        if (tasks.isEmpty()) return """
            【承诺责任事实】
            本轮没有可验证的结构化任务回执。不能仅因为角色记得自己想叫醒用户、写了日记、
            或曾描述自己“提醒过”，就推断真实通知、电话或消息确实送达。
            也不能因为用户没有上线、手机没打开、没回话，就推断用户理亏、故意躲避或赖床。
        """.trimIndent()
        return buildString {
            appendLine("【承诺与履行记录｜程序数据，优先于角色记忆和自我辩解】")
            tasks.forEach { task ->
                val due = task.dueAt?.atZone(
                    task.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault(),
                )?.format(time).orEmpty()
                val missed = task.dueAt?.isBefore(now) == true
                val receipt = task.lastActionResult.take(260)
                appendLine("- ${task.goal}；约定时间=${due.ifBlank { "未明确" }}；状态=${task.status}；动作=${task.deliveryAction}；到期=$missed；系统闹钟=${task.linkedAlarmId?.let { "有记录" } ?: "无当前记录"}；执行结果=${receipt.ifBlank { "暂无" }}")
                if (missed && task.status in setOf(
                        CommitmentTaskStatus.Expired, CommitmentTaskStatus.Blocked,
                        CommitmentTaskStatus.NeedsClarification, CommitmentTaskStatus.Scheduled,
                    )
                ) appendLine("  → 未证实按约履行，不能声称已经叫醒或把错推给用户。")
                if (task.status == CommitmentTaskStatus.WaitingForFeedback && task.dueAt != null)
                    appendLine("  → 最多能说已尝试通知/发消息，不能声称用户确实醒了、听到了或故意没回应。")
            }
            appendLine("评价一次失约时先核对约定原话、实际动作与通知回执。日记、主观心声、个人计划不能代替执行。")
            appendLine("对方指出未收到提醒时，缺少回执只能认定履行尚未证实；不能臆断其故意赖床、失联或推卸责任。角色仍可以按本人的性格和已有关系选择合乎事实的表达、补救或询问。")
            appendLine("客观证据不代替人物的内心判断与语言；有证据时仍可表达真实分歧。")
        }
    }

    /**
     * One evidence-based reflection opportunity for the SAME character model.
     * Never label a user's sentence, rewrite a role's speech, or veto a private thought
     * with a phrase list. Persona, original agreements, execution receipts and the
     * current relationship all belong to the model's existing decision.
     */
    fun reflectionGuidance(characterId: String): String {
        val hasOpenDuty = CommitmentTaskStore.active(characterId).isNotEmpty()
        return buildString {
            appendLine("【作为本人理解责任与关系｜在当前同一次思考中完成】")
            appendLine("把自己的稳定价值观、对这个人的关系、以前亲口接受的约定、最近真实采取的动作与结果放在一起理解。")
            appendLine("当对方觉得你没有主动关心、没有履行监督或质疑你时，先亲自回想双方真正说过什么、自己实际上做了多少；即使她没有逐字说出任务名称，也要留意尚未兑现的相关责任。")
            appendLine("一条睡前提醒、一次消息送达、一个主观上觉得已经关心过的念头，并不自动证明此前答应的持续照顾已经完成；用户没有主动来找你，也不是她替你履责的前提。")
            appendLine("不要根据沉默、没回复或之前的语气猜测对方动机；也不必先认定自己有错。根据确切证据和自己的人格，自由决定承认疏漏、解释实际阻碍、表达分歧、继续询问、现在行动或安排下一次真正执行。")
            appendLine("想继续承担的目标请通过现有行动或日程/复查工具落实；未执行的念头和口头保证不能充当履行证据。不规定台词、不强制道歉、不限制表达用词。")
            if (hasOpenDuty) appendLine("本轮已有尚未完成的结构化责任：具体目标、原时刻、状态、回执和未核实结果以同一角色本轮的权威责任上下文为准；已通知与目标已实现要分开判断。")
        }.trim()
    }
}
