package com.jiacimu.lulu.data

import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId

/** The model owns meaning; this contract only defines typed changes and concrete execution evidence. */
internal object CommitmentDecisionProtocol {
    val instruction = """
        【理解与落地】理解整段对话的意思和指代，不靠某几个词决定是否记录。
        一闪而过的想法写 innerLife.thoughts；本人持续想做的愿望写 innerLife.motives；需要留意的未解心事写 appraisal.pendingConcerns；已经向对方承担的责任写 commitmentChanges。愿望、条件句、玩笑、建议或拒绝不能冒充已接受的约定。
        每轮额外返回 commitmentChanges 数组；没有约定变化就 []。有变化时每项为：
        {"action":"create|reschedule|cancel|complete","targetTaskId":"修改/取消/完成旧任务必须用真实id","goal":"完整的约定内容","dueAt":"带时区ISO时间，或空字符串","timezone":"IANA时区","promiseKind":"promise|responsibility|reminder|long_term_supervision","needsClarification":false,"completionCondition":"真正完成的依据","steps":[],"deliveryAction":"send_private_message|start_call|alarm_only"}。
        先结合当地时间、前后文和生活语境判断具体日期。凌晨未睡时“明天叫我起床”可能指这次睡醒的早晨，不机械把日历加一天；用户明确的日期优先，不确定就确认，不能编造时刻。用户纠正日期或条件时修改原id，并给出修正后的完整goal/dueAt，保留没改的条件，不能只口头认错或另建一条。
        持续关心、学习督促等尚无具体行动时，可用 long_term_supervision 或 responsibility 且 dueAt 为空保留长期责任；但这不是今晚已安排好监督。若角色自己真心决定在今天、今晚或稍后主动履行某次监督，应自己参考当地时间、双方真实习惯选具体时间，用同一轮 plan_personal_activity 真正存入个人日程，已有持续约定可带 commitmentTaskId。用户不必给时刻。不要把临时念头、玩笑或单纯关心变成强制日程。
        start_call代表真实来电，alarm_only代表本机带声音/震动的闹钟，send_private_message只是普通私聊而不是可靠的叫醒手段。仅想打电话不等于承诺。本人答应稍后来电时自行给出合理时刻，不能由程序按词固定若干分钟。已过去的当天计划不能在次日伪装为新任务；必须由本人显式选择改期并留下原本未履行的事实。
        【定时叫醒约定】如果你已经明确答应到点叫醒对方，同一轮必须用commitmentChanges创建或调整真实任务；不可只在台词或心声里说“我会叫你”却提交[]。你作为角色自主决定用真实主动来电start_call还是本机闹钟alarm_only，先参考用户明确提出的方式、你们的关系偏好、你的性格和手机实际权限。偏好不是命令；但若用户明确要求电话，不要私自改成普通通知。不要选send_private_message来履行叫醒睡着的人。没有权限或无法安排时，留失败回执或询问，不假装已设置成功。
        约定通过commitmentChanges落地，同一件事不要再选create_alarm重复建闹钟。记录/执行是否成功以接下来的程序回执为准，当前说话只能表达接受、意愿或必要确认，不能提前宣称已经保存或已经改好。
    """.trimIndent()

    fun context(tasks: List<CommitmentTask>, now: Instant, zone: ZoneId): String = buildString {
        appendLine("【约定的客观依据】当前当地时间=${now.atZone(zone)}；时区=${zone.id}")
        if (tasks.isEmpty()) appendLine("暂无现有约定；不能虚构旧任务id。")
        tasks.forEach { task ->
            appendLine("id=${task.id}; goal=${task.goal}; dueAt=${task.dueAt}; timezone=${task.timezone}; action=${task.deliveryAction}; status=${task.status}; revision=${task.revision}; condition=${task.completionCondition}")
        }
    }

    fun drafts(changes: JSONArray, active: List<CommitmentTask>): List<CommitmentTaskDraft> {
        val drafts = parseCommitmentTaskDrafts(changes.toString())
        require(drafts.size == changes.length()) { "约定变更格式不完整，未执行" }
        drafts.forEach { draft ->
            if (draft.action != "create") require(active.any { it.id == draft.targetTaskId }) {
                "没有找到准确的原约定id，未修改其他约定"
            }
        }
        return drafts
    }
}
