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

    fun prompt(characterId: String, now: Instant = Instant.now()): String {
        val tasks = CommitmentTaskStore.snapshot(characterId)
            .filter { it.status != CommitmentTaskStatus.Cancelled }
            .take(12)
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
                if (task.status == CommitmentTaskStatus.WaitingForFeedback)
                    appendLine("  → 最多能说已尝试通知/发消息，不能声称用户确实醒了、听到了或故意没回应。")
            }
            appendLine("评价一次失约时先核对约定原话、实际动作与通知回执。日记、主观心声、个人计划不能代替执行。")
            appendLine("对方指出没收到约定提醒时，不许凭空判定对方故意赖床、明知理亏、失联有错或倒打一耙；应承认自己没证据证明完成，对真实过失作出相称的歉意并说明可补救的行动。")
            appendLine("角色可以保留自己的幽默、独立意见和脾气；承认自己没有完成的事，不意味着必须对无关要求照单全收。")
        }
    }

    fun isUnmetPromiseChallenge(text: String): Boolean {
        if (text.isBlank()) return false
        val compact = text.replace(Regex("\\s+"), "").take(500)
        val theme = listOf("叫我", "叫醒", "喊我", "提醒", "十点", "10点", "起床",
            "承诺", "说好", "答应", "闹钟", "打电话", "来电").any(compact::contains)
        val challenge = listOf("没", "没有", "忘", "为什么", "怎么", "失约", "没做到",
            "没听到", "没响", "没叫", "没提醒", "没打").any(compact::contains)
        return theme && challenge
    }

    fun challengeGuidance(text: String): String = if (!isUnmetPromiseChallenge(text)) "" else """
        【本轮用户正在追问可能未履行的具体约定】
        用户指出了一个需要核实的失约，而不是自动成为“理亏的那个人”。
        先确认谁承担叫醒或提醒责任、到点有哪些真实回执；不要把“用户让角色十点叫醒她”
        错读成“用户答应十点起床要向角色交代”。对方睡过头不构成角色免责理由。
        如无法确认通知实际送达，就坦诚承认没有成功叫醒用户；别说“倒打一耙、嘴硬、
        明知理亏、转移话题、你失联了还敢说我”等无事实根据的归责，也不要说完歉意
        马上再羞辱一遍。可以自然地歉意、懊恼或懊悔，再根据当前工具状态谈补救。
        不必固定照念道歉模板，不必抹去角色原有个性。
    """.trimIndent()
}
