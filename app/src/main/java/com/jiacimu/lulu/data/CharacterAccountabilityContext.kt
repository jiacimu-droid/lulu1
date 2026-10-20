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
            appendLine("对方指出没收到约定提醒时，不许凭空判定对方故意赖床、明知理亏、失联有错或倒打一耙；应承认自己没证据证明完成，对真实过失作出相称的歉意并说明可补救的行动。")
            appendLine("角色可以保留自己的幽默、独立意见和脾气；承认自己没有完成的事，不意味着必须对无关要求照单全收。")
        }
    }

    fun isWakeExplanation(text: String): Boolean {
        val compact = text.replace(Regex("\\s+"), "").take(600)
        val voice = listOf("声音", "语气", "温柔", "太轻", "太柔", "说话").any(compact::contains)
        val wake = listOf("叫醒", "叫不醒", "唤醒", "喊醒", "起不来", "醒不过来", "没醒", "睡得").any(compact::contains)
        return voice && wake
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
     * Fact verifier, NOT a speech author. We may detect unearned blame when
     * discussing an unfulfilled obligation, but do not put a canned apology
     * into another character's mouth.
     *
     * The delivery gateway retries the same character's own expression; if
     * that fails, it rejects the reply without logging a fabricated apology.
     */
    fun containsUnfoundedBlame(userText: String, roleReply: String): Boolean {
        val factualWake = isWakeExplanation(userText)
        if (!isUnmetPromiseChallenge(userText) && !factualWake) return false
        val condensed = roleReply.replace(Regex("\\s+"), "")
        val unfair = listOf("倒打一耙", "明知理亏", "故意转移话题", "失联了还",
            "怎么跟我交代", "还敢质问", "还敢怪我", "甩锅", "找借口", "嘴硬", "还敢狡辩")
            .any(condensed::contains)
        if (!unfair) return false
        val ownsCorrection = listOf("不是你倒打一耙", "不能说你倒打一耙",
            "不该说你倒打一耙", "你没有倒打一耙", "我不该怪你",
            "不是你甩锅", "你不是甩锅", "不是你找借口",
            "我误会你了", "我不该说你嘴硬", "不是你嘴硬",
            "不该说你找借口", "不能说你找借口").any(condensed::contains)
        return !ownsCorrection
    }

    /** Compatibility verifier: blank means invalid, never a program-written reply. */
    fun guardUnfairBlame(userText: String, roleReply: String): String =
        if (containsUnfoundedBlame(userText, roleReply)) "" else roleReply

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
        不必固定照念道歉模板，不必抹去角色原有个性。核实由内部判断完成，不需要把任务状态、调度步骤、通知回执逐项念给用户。
        若用户只是表达失望，先用自己的自然语气接住；一句简短认错、懊恼的反应或合适的表情包也可以，不必每次生成“道歉→说明故障→承诺整改”的报告。只有对方真在问原因时才解释已核实的必要事实；未知的原因就保持未知，别编造技术借口。
    """.trimIndent()
}
