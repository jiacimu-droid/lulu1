package com.jiacimu.lulu

import com.jiacimu.lulu.data.CommitmentTask
import com.jiacimu.lulu.data.CommitmentTaskStore
import com.jiacimu.lulu.data.isActive
import java.time.Instant

/** Re-read the original task every turn; never freeze a completed/cancelled goal in call state. */
internal object CallResponsibilityContext {
    fun forCall(characterId: String, taskId: String?, now: Instant): String =
        render(CommitmentTaskStore.snapshot(characterId), characterId, taskId, now)

    internal fun render(tasks: List<CommitmentTask>, characterId: String,
        taskId: String?, now: Instant): String {
        val task = tasks.firstOrNull { it.id == taskId && it.characterId == characterId &&
            it.status.isActive() && it.dueAt?.let { due -> !due.isAfter(now) } == true }
            ?: return ""
        return """
            【本通电话正在履行的未完成责任】
            id=${task.id}；原目标=${task.goal}；完成条件=${task.completionCondition}；状态=${task.status}。
            最近实际执行结果=${task.lastActionResult}；后续检查时间=${task.nextCheckAt}。
            接通、开场说过话、麦克风静音、没有新发言，都不证明目标达成。继续按原目标判断这次互动，不要把履约过程自动改成普通安静陪伴。
            若原目标是叫醒，叫醒效果仍须核实；未经用户更改，不能把原目标改成哄睡或安静陪伴。具体方式由你结合原要求和实际结果决定。用户明确取消、改期或要求停止时，尊重最新要求并更新原任务。
            结合已经尝试的实际结果选择下一步：可自然开口、ring_in_call_wake 播放短铃声，或用 control_music/control_volume 控制已授权的播放器；只在心声说想做不会执行，需在本轮 steps 中提交真实工具与参数。
            本轮决定仍须依据原目标尚缺什么证据以及已有执行结果；后续选择与理由保存在原有结构化决定中，不要求向用户朗读内部计划。需要稍后再核实可在 commitmentChanges 选择 action=review、targetTaskId=原任务ID、dueAt=本人选定未来ISO时刻、verificationReason=复查理由；不是重新约一次叫醒。不重复建同一责任。尊重用户停止与已有尝试上限。
        """.trimIndent()
    }
}
