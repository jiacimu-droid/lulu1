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
        {"action":"create|reschedule|cancel|complete|review","targetTaskId":"修改/取消/完成旧任务必须用真实id","goal":"完整的约定内容","dueAt":"带时区ISO时间，或空字符串","timezone":"IANA时区","promiseKind":"promise|responsibility|reminder|long_term_supervision","needsClarification":false,"completionCondition":"真正完成的依据","verificationMode":"outcome|delivery|subjective_report","completionEvidenceIds":[],"verificationReason":"证据如何证明目标达成","steps":[],"deliveryAction":"send_private_message|start_call|alarm_only","repeat":"once|daily","permanent":false}。
        先结合当地时间、前后文和生活语境判断具体日期。凌晨未睡时“明天叫我起床”可能指这次睡醒的早晨，不机械把日历加一天；用户明确的日期优先。若不确定用户究竟要求什么，应确认，不可编造用户指定的时间；但角色已经自主决定承担的一次行动，可以明确选择自己的未来执行时刻，无须向用户追问几点。用户纠正日期或条件时修改原id，并给出修正后的完整goal/dueAt，保留没改的条件，不能只口头认错或另建一条。最终在 text/speechIntent 口头确认的具体日期、距现在多久与 commitmentChanges.dueAt 必须一致：例如「再过几小时是今天早上」不能同时登记次日。时间语义只以本轮最新更正为准；在同一轮结构化输出中不能继续沿用被否定的旧日期。你就是此轮时间含义的唯一解释者：同一决策必须先确定唯一的实际执行时刻，再用该结果同时编写口头确认与 commitmentChanges，不要让两个字段各自重新解释；如果发现现有任务日期不对，必须返回针对真实任务ID的 reschedule。程序不会按“明天”“几小时后”等关键词再修正你的时间判断。
        【单一定时任务来源】用户要求“10:30喊我学习”“每晚23:30监督我睡觉”“明早9点叫醒我”等明确的定时提醒或监督：同一意图只通过 commitmentChanges 建立或修改一条真实执行任务（一次性 once、每日 daily），日程页面直接展示这一个任务。不要再用 plan_personal_activity、create_alarm 或第二条不同表述的责任重复登记。个人日程工具仅用于本人自发选择的读书、社交、世界活动，以及从没有固定时间的持续约定发展出的具体行动；同一个已定时约定的后续更新必须复用原任务ID，不是另外建个人日程。
        持续关心、学习督促等尚无具体行动时，可用 long_term_supervision 或 responsibility 且 dueAt 为空、needsClarification=false 保留长期责任；比如同时关注学习、休息、起床和饮食，这是长期责任而不是一条尚未登记的叫醒闹钟，不要仅因为其中有“起床”就标记缺少叫醒方式、要求用户去开手机权限。仅在具体执行动作确实缺少用户必须确认的条件时才用 needsClarification=true；不能把接受长期责任等同于“今晚已安排好监督”。若角色自己真心决定在今天、今晚或稍后主动履行某次监督，应自己参考当地时间、双方真实习惯选具体时间，用同一轮 plan_personal_activity 真正存入个人日程，已有持续约定可带 commitmentTaskId。用户不必给时刻。不要把临时念头、玩笑或单纯关心变成强制日程。
        start_call代表真实来电，alarm_only代表本机带声音/震动的闹钟，send_private_message只是普通私聊而不是可靠的叫醒手段。仅想打电话不等于承诺。本人答应稍后来电时自行给出合理时刻，不能由程序按词固定若干分钟。已过去的当天计划不能在次日伪装为新任务；必须由本人显式选择改期并留下原本未履行的事实。
        【所有约定：完成条件与证据核验】先区别最终目标和履行步骤。verificationMode=outcome（默认）表示目标是外部真实结果；delivery 表示目标仅要求实际完成某个工具或送达动作；subjective_report 仅适用于需要本人评价的主观体验，禁止用来替代可以观察的客观结果。打了电话、发了消息、调用工具、哄过睡、用户说准备做、用户沉默或离开页面都不等于目标结果达成。
        不论什么约定，到期行动之后还要有一次结果核实；“监督她睡觉/哄睡到入睡”是需要核实实际效果的持续目标，不是弹窗送达就算完成。她没回复、故事发出、电话结束都不证明她睡着。获准读取的近期设备交互只是线索；需要真实画面时可自主选择既有 capture_screen，遵守已有授权。无法确认时保留目标，自己安排下一次复查或有理由暂缓，而不是留下口头承诺就休眠。屏幕截图需明确授权，不能偷看或盲猜。你可以考虑是否进一步查证、等待或调整方式。只有判断完成条件确实满足才能在 commitmentChanges 返回 complete；必须传同一角色近期真实的 completionEvidenceIds 与解释它们如何证明本目标的 verificationReason，不能凭你自己的承诺、想象或单条用户话语直接结案。若暂时拿不到证据，保持等待核实，选择适当下一步或诚实说明未知；不许重复打扰到获得回应为止。
        【未完成目标的下一次结果复查】已经行动但效果未确认时，可在同轮 commitmentChanges 选择 {"action":"review","targetTaskId":"原任务ID","dueAt":"你决定的未来ISO时刻","verificationReason":"还缺什么证据以及这次复查的理由"}。这是静默唤醒原角色重新决策，不会自动重复打电话、响铃或发送消息；不改变原始约定日期、不重置已尝试次数，也不创建第二条责任。下次到时仍需依据原目标、已执行结果和新证据决定行动或继续安排复查；用户取消或已获可靠证据完成后不再跟进。不要用 reschedule 偷换原约定来表示一次结果检查，也不要只在心声或台词里说稍后查看。
        【每日定时约定】用户明确要求“每天23:30打电话监督睡觉”等固定重复任务时，commitmentChanges 只创建或修改一条 repeat=daily、含真实日期时区的 dueAt 与 start_call，使用同一个任务 ID 循环下一天的闹钟，不要同时另外创建 plan_personal_activity；仅当天或一次性的任务设 repeat=once。已建立相同每日责任时修改同一 ID，不再新建一条。每天任务改时间要分清一次与永久：只说今天、明天、这次推迟一下时 permanent=false，只改当次，次日滚回本来时刻；只有用户明确说以后每天都改时 permanent=true，才改每天本来时刻。普通不定时的白天学习监督仍可持续跟进，但它没有每天固定闹钟。\n        【定时叫醒约定】如果你已经明确答应到点叫醒对方，同一轮必须用commitmentChanges创建或调整真实任务；不可只在台词或心声里说“我会叫你”却提交[]。你作为角色自主决定用真实主动来电start_call还是本机闹钟alarm_only，先参考用户明确提出的方式、你们的关系偏好、你的性格和手机实际权限。偏好不是命令；但若用户明确要求电话，不要私自改成普通通知。不要选send_private_message来履行叫醒睡着的人。没有权限或无法安排时，留失败回执或询问，不假装已设置成功。
        【通话定时与日程分离】当前电话“半小时后挂断”“02:35结束这通电话”属于本次通话控制，必须由电话的 hangupAfterSeconds 计时字段处理，绝不是下一次 start_call 来电，也不应作为普通 commitmentChanges 约定重复登记。每天00:00督促休息等持续约定是另一件事，各自独立选择执行时点与执行方式，不能把两个意图拼成一项 goal 并套用每日零点。已经过去的本地日期不自动改成明天；跨午夜仅在实际时间计算越过午夜时发生。\n        约定通过commitmentChanges落地，同一件事不要再选create_alarm重复建闹钟。记录/执行是否成功以接下来的程序回执为准，当前说话只能表达接受、意愿或必要确认，不能提前宣称已经保存或已经改好。
    """.trimIndent()

    // Model-only consistency pass; the executor never decodes Chinese dates.
    val deliveredReplyReconciliation = """
        【已发送回复与真实日程核对】用户已经看到这轮角色回复。独立阅读本轮用户完整原话、相邻上下文、
        角色实际说出的最终时间，以及现有未完成任务的真实 id/dueAt。
        由你判断是否接受了约定、是否更正了前面的日期，以及最终指向哪一条原任务。
        若口头确认与已保存的任务日期不一致，返回 reschedule + 真实 targetTaskId +
        你理解的完整 dueAt（带时区）与原有执行方式；旧任务由执行器原位更新。
        如本轮已经正确保存，或没承诺/没要求修改，返回 []。
        不要为了对齐而重复 create；不能凭词表、程序日历偏移或机械的关键词匹配覆盖本人最终理解。
        模型是时间含义的唯一解释者。程序只检查格式、未来时间、真实 ID 和执行权限。
    """.trimIndent()

    fun context(tasks: List<CommitmentTask>, now: Instant, zone: ZoneId): String = buildString {
        appendLine("【约定的客观依据】当前当地时间=${now.atZone(zone)}；时区=${zone.id}")
        if (tasks.isEmpty()) appendLine("暂无现有约定；不能虚构旧任务id。")
        tasks.forEach { task ->
            appendLine("id=${task.id}; goal=${task.goal}; dueAt=${task.dueAt}; timezone=${task.timezone}; action=${task.deliveryAction}; repeat=${task.repeat}; dailyBase=${task.dailyBaseDueAt}; status=${task.status}; revision=${task.revision}; condition=${task.completionCondition}; verificationMode=${task.verificationMode}; nextReview=${task.nextCheckAt}; lastReceipt=${task.readableLastResult().take(170)}")
        }
        if (tasks.isNotEmpty()) {
            appendLine("【结案证据候选（仅来源、时间和类型初筛，不等于目标完成）】")
            // This extracted/reconciliation path must offer the same evidence
            // contract as the live role planner rather than a random last-N list.
            val byRole = tasks.map(CommitmentTask::characterId).distinct()
                .associateWith { role -> SharedExperienceTimeline.recentEvents(role, 160) }
            tasks.forEach { task ->
                appendLine(CommitmentOutcomeVerification.candidateContext(
                    task, byRole[task.characterId].orEmpty(), maxItems = 4))
            }
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

