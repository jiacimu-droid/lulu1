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

    /**
     * Last-resort integrity guard, only for a direct complaint about an unmet promise.
     * This cannot substitute for good modeling; it blocks an especially harmful misfire when
     * a model nevertheless accuses the user of wrongdoing without any action receipt.
     */
    fun guardUnfairBlame(userText: String, roleReply: String): String {
        if (!isUnmetPromiseChallenge(userText)) return roleReply
        val condensed = roleReply.replace(Regex("\\s+"), "")
        val blatantlyUnfair = listOf("倒打一耙", "明知理亏", "故意转移话题", "失联了还",
            "怎么跟我交代", "还敢质问", "还敢怪我").any(condensed::contains)
        if (!blatantlyUnfair) return roleReply
        // The model may itself explicitly reject that accusation; don't censor a correction.
        if (listOf("不是你倒打一耙", "不能说你倒打一耙", "不该说你倒打一耙",
            "你没有倒打一耙", "我不该怪你").any(condensed::contains)) return roleReply
        return "等等，你说得对。是我答应按时叫醒你，不是让你醒来以后向我交代。" +
            "我没有证据证明当时真的叫醒了你，却反过来怪你，这话说得不对。" +
            "对不起。我得先把自己答应的事做好，而不是拿玩笑把责任带过去。"
    }

    /**
     * Responsibility cannot be turned into a claim that the user is acting
     * maliciously, not even in model-authored private thoughts.
     * Other honest unpleasant thoughts and evidence-based disagreements remain.
     */
    fun guardUnfoundedInnerBlame(userText: String, innerThought: String): String {
        val thought = innerThought.trim()
        if (thought.isBlank()) return thought
        val subject = userText + "\n" + thought
        val hasOwnDuty = listOf("答应", "约定", "承诺", "准时", "打电话", "来电",
            "提醒", "闹钟", "叫醒", "到点", "失约", "拨过去", "没做到").any(subject::contains)
        if (!hasOwnDuty) return thought
        val unearnedAccusation = Regex("抓.{0,6}把柄|挑.{0,3}刺|找.{0,4}茬|无理取闹|故意刁难|恶意找错|小题大做")
            .containsMatchIn(thought)
        if (!unearnedAccusation) return thought
        val admittingMistake = listOf("我不该觉得她", "我不该说她", "不是她在挑刺",
            "她不是在挑刺", "不能怪她", "不应把她", "我误会了她").any(thought::contains)
        // Omitting an unsupported attribution is safer than forging a nicer
        // "real thought". The model can simply return an empty innerThought.
        return if (admittingMistake) thought else ""
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
