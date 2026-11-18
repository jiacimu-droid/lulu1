package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject

/** The role owns the goal and the first action. The executor checks their structure. */
internal object ImmediateCommitmentDecision {
    val instruction = """
        【答应当下做的事】如果你刚答应现在去找、检查、发送等，同轮在 commitmentChanges 写
        {"action":"create","startNow":true,"promiseKind":"responsibility","goal":"你实际答应的目标",
        "completionCondition":"什么真实结果才算兑现","verificationMode":"delivery|link_delivery|outcome","firstStep":0}。
        firstStep 是实际工具 steps 的从零开始序号；若主动作就是对应工具则填 -1。此时直接选第一步，
        不填 dueAt，不把「我这就去」变成另一条未来计划。按上下文理解「换一个」「再试一次」，
        用新操作和新回执推进原目标；在原 App 内找内容时选 background_phone，不能拿旧截图或样例链接交差。
        若目标是把某个真实链接作为私聊卡片发给用户，verificationMode 用 link_delivery，须有本次 linkSent 回执。
        没有可行步骤时说清楚真实缺项或阻碍。一次行动结束仍须核实目标；启动应用或发了气泡不算兑现。
        已接受的长期责任用原长期约定；当天的一次子行动完成后，原约定仍应继续跟进。
    """.trimIndent()

    private val imminentPhoneAction = Regex(
        "(?:我这就|我马上|我现在|我接着|我继续).{0,24}(?:往下滑|往上滑|下滑|刷一|退回|点开|打开|给你找|给你搜|给你挖|给你换)")
    private val phoneContext = Regex("后台虚拟屏|小红书|xhslink\\.|拼多多|抖音|微信|应用里|帖子|复制链接")

    /** Compatibility cue to ask the role for its meaning; prose never authorizes a tool. */
    fun mayHaveOmittedPhoneAction(speech: String, recent: String): Boolean =
        imminentPhoneAction.containsMatchIn(speech) && phoneContext.containsMatchIn(recent)

    fun repairStep(raw: JSONObject): JSONObject? =
        AutonomousActionWireProtocol.normalizeStep(raw).takeIf { step ->
            val tool = step.optString("tool")
            // An omission inferred from legacy prose may only repair the existing
            // lookup routes. Typed decisions already use all registered tools.
            (tool in setOf("web_search", "image_search", "background_phone")) &&
                isExecutable(tool, step.optJSONObject("args"))
        }

    fun repairedChange(response: JSONObject, stepIndex: Int): JSONObject? {
        val goal = response.optString("goal").trim()
        val condition = response.optString("completionCondition").trim()
        if (goal.isBlank() || condition.isBlank()) return null
        return JSONObject().put("action", "create").put("startNow", true)
            .put("promiseKind", "responsibility").put("goal", goal)
            .put("completionCondition", condition)
            .put("verificationMode", response.optString("verificationMode")
                .takeIf { it in setOf("link_delivery", "outcome") } ?: "delivery")
            .put("firstStep", stepIndex)
    }

    fun promisedChanges(changes: JSONArray?): List<JSONObject> =
        (0 until (changes?.length() ?: 0)).mapNotNull { index ->
            changes?.optJSONObject(index)?.takeIf {
                it.optString("action").trim().equals("create", ignoreCase = true) &&
                    it.optBoolean("startNow")
            }
        }

    fun hasFirstAction(change: JSONObject, primaryTool: String, primaryArgs: JSONObject,
                       steps: JSONArray?): Boolean {
        val index = change.optInt("firstStep", -2)
        if (index == -1) return isExecutable(primaryTool, primaryArgs)
        return steps?.optJSONObject(index)?.let(AutonomousActionWireProtocol::normalizeStep)?.let { step ->
            isExecutable(step.optString("tool"), step.optJSONObject("args"))
        } == true
    }

    fun isExecutable(tool: String, args: JSONObject?): Boolean {
        if (args == null || !CapabilityRegistry.canChoose(tool)) return false
        return when (tool) {
            // The initial action must do real work, not create another reminder
            // or claim the original result has already been verified.
            "create_alarm", "plan_personal_activity", "schedule_commitment_review",
            "verify_commitment", "reschedule_personal_activity", "go_offline" -> false
            "web_search", "image_search" ->
                SearchActionIntegrity.validatedAction(JSONObject().put("tool", tool)
                    .put("args", args).toString()) != null
            "background_phone" -> {
                val action = args.optString("action").trim().lowercase()
                val policy = com.jiacimu.lulu.system.LuluBackgroundPhoneArgumentPolicy
                action != "stop" && policy.isSupportedAction(action) &&
                    policy.invalidReason(action, args) == null
            }
            else -> true
        }
    }

    fun readyToReview(task: CommitmentTask, now: java.time.Instant): Boolean =
        task.startNow && task.dueAt == null &&
            task.status == CommitmentTaskStatus.WaitingForFeedback &&
            task.nextCheckAt?.isAfter(now) != true
}
