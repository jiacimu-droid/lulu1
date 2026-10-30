package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.system.LuluMediaPlayback
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.Instant

/** Character-led wake strategy with a reliable Android fallback. */
internal object WakeActionPlanner {
    enum class Cue { RING, SPEAK, MUSIC, WAIT }
    /** A follow-up contact is a new character decision, not an attempt-count rule. */
    enum class Contact { CALL, MESSAGE, WAIT }
    data class Choice(
        val cue: Cue,
        val reason: String = "",
        val volumePercent: Int? = null,
        val openingLine: String = "",
        // This is ONE cue in a larger wake → observe → re-decide cycle,
        // not a fixed batch of ring repetitions before speech.
        val ringSeconds: Int = 6,
        val contact: Contact? = null,
        val reviewAfterSeconds: Long? = null,
        /** A system fallback is never misrepresented as the character's choice. */
        val systemFallback: Boolean = false,
        val executionIssue: String = "",
    )

    fun musicAllowed(context: Context, characterId: String): Boolean {
        val prefs = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        return prefs.getBoolean("$characterId:media", false) &&
            prefs.getBoolean("$characterId:wake_music_enabled", false)
    }

    suspend fun choose(
        context: Context,
        task: CommitmentTask,
        inCall: Boolean,
        now: Instant = Instant.now(),
    ): Choice {
        // The model must never be a single point of failure for a wake alarm.
        val fallback = Choice(Cue.RING, systemFallback = true,
            executionIssue = "模型未能作出有效叫醒决定，由本机铃声应急保底")
        val canMusic = musicAllowed(context, task.characterId)
        val raw = try {
            withTimeoutOrNull(20_000L) {
                // Cold-process AlarmManager deliveries are allowed to use the
                // same configured character model as foreground conversations.
                LuluAiServices.initialize(context)
                LuluAiServices.gateway.generate(
                characterId = task.characterId,
                facts = buildString {
                    appendLine("真实到点时刻：$now")
                    appendLine("已经接受的叫醒责任：${task.goal}")
                    appendLine("叫醒形式约定：${task.deliveryAction}；此前已执行/安排次数：${task.attemptCount}")
                    appendLine("是否还在同一通电话中：$inCall")
                    appendLine("是否为到点首次行动：${task.attemptCount == 0}")
                    appendLine("当前来电权限：${MigratedDomainStores.characters.get(task.characterId).contactPolicy.proactiveCallsEnabled}")
                    appendLine("当前目标状态：${task.status}；尚无可验证的醒来完成结果")
                    if (task.wakeAttemptHistory.isNotEmpty())
                        appendLine("这次叫醒已经经历的真实阶段（按先后次序）：\n" +
                            task.wakeAttemptHistory.takeLast(5).joinToString("\n") { "· " + it.take(300) })
                    if (task.lastActionResult.isNotBlank() && task.lastActionResult != "到期步骤已领取，正在执行")
                        appendLine("最近一条执行/播放回执：${task.lastActionResult.take(250)}")
                    appendLine("手机音乐控制是否已授权：$canMusic")
                    if (canMusic) appendLine("已查实的播放器状态：" +
                        LuluMediaPlayback.observation(context).take(350))
                    appendLine("不能假装她已经醒了。")
                },
                instruction = """
                    你是正在履行这项真实责任的原角色。结合自己的性格、关系、
                    已知的习惯与当前约定，自由决定这一刻怎么叫醒她。
                    只返回 JSON：{"cue":"ring|speak|music|wait","contact":"call|message|wait","reviewAfterSeconds":300,"reason":"结合已经发生的叫醒过程，本轮为什么这样做","ringSeconds":6,"volumePercent":35,"openingLine":"如需在电话里开口，你自然想说的第一句"}
                    这是同一个叫醒目标的下一步，不是每次从零开始的新任务。
                    第一次叫醒（此前尝试次数为0），主人已经明确选择「先铃声，后自然说话」：
                    同一通话内本轮应选 cue=ring；如果尚未接通，就用真正的来电铃声作为第一次响铃，接听后说话。
                    从第二轮开始不固定顺序，你根据上一阶段的实际回执和自己的想法选择。
                    后续每次都看上一阶段的真实结果，重新自主选择下一步：
                    ring=本轮短铃声（2-10秒）再自然开口；
                    speak=通话中直接再开口；未接通时仍必须通过可听见的来电/闹钟；
                    music=在授权且存在可控播放器时真实播放音乐，不凭空指定歌曲；
                    wait=这轮暂不发出叫醒声音，保留目标并稍后观察。
                    如果没有正在连接的电话，contact 必须是你本轮独立选择的联系渠道：
                    call=本轮确实再次主动来电（须有已授予的来电权限）；
                    message=本轮发出自己想说的私信；
                    wait=不立即联系，等下次结果复查。
                    第一次仍须遵守原约定的联系渠道；明确约定来电不能换成文字。
                    后续不应因为尝试次数达到2就被系统自动改成来电；你要自己选。
                    reviewAfterSeconds=你希望经过多少秒再复查真实结果，可在90到1200之间明确选择；程序只管理时钟与资源保障。
                    有新外发文本时 openingLine 要填写你本人真正想说的短句，不交给系统代写固定叫醒台词。
                    例如你可以先响铃、再说几句话；若仍无确认，你可能想再说、换铃声、放音乐或稍候复查。
                    **不要预先把几次铃声连续播完，再只说一次话**。每次都要基于上次的尝试和回执重新作出自己的判断。
                    没有回应只能说明尚未得到确认，不代表她睡死、也不代表已经醒了；不要编造证据。
                    不用每一步都重复相同的话，也不要为了形式固定“铃声、说话、音乐”的顺序。
                    答应打电话的任务仍必须实际来电，不能擅自换成纯文字或音乐。
                    music 只在已授权时可选；音量只控制音乐且受安全限幅限制。
                    如果你仍选择了权限不足的动作，程序只记录不能执行并提供独立的保底回执，不会谎称你改了主意。
                    可以温柔，可以觉得需要更响一点；无需遵循固定顺序。
                    模型超时、网络不通时，已有安卓叫醒响铃负责兜底。
                """.trimIndent(),
                source = "到点叫醒决策",
                title = "角色自主叫醒",
                maxTokens = 320,
                usage = ModelUsage.Chat,
            ).getOrNull()?.text
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null // Never sacrifice the audible Android fallback for AI failure.
        } ?: return fallback
        return honorFirstWakePromise(parseChoice(raw, canMusic, inCall), task.attemptCount)
    }

    /** The owner explicitly promised the first audible ring. A valid but
     * conflicting model response is preserved as an execution failure, not
     * silently re-labelled as a new character preference. Subsequent rounds
     * are fully character-selected and may intentionally stay quiet. */
    internal fun honorFirstWakePromise(choice: Choice, previousAttempts: Int): Choice {
        if (previousAttempts != 0 || choice.cue == Cue.RING) return choice
        return Choice(
            cue = Cue.RING,
            systemFallback = true,
            executionIssue = "首次约定先响铃，角色本轮原选择=${choice.cue}；" +
                "未按既有叫醒约定形成可执行决定，本机仅做首次铃声保底",
            reason = choice.reason,
        )
    }

    /** Central validation used for live decisions and regression tests. */
    internal fun parseChoice(raw: String, canMusic: Boolean, inCall: Boolean): Choice {
        val fallback = Choice(Cue.RING, systemFallback = true,
            executionIssue = "模型输出格式无效，本机铃声应急保底")
        val json = runCatching {
            JSONObject(raw.trim().removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim())
        }.getOrNull() ?: return fallback
        val cue = when (json.optString("cue").trim().lowercase()) {
            "ring" -> Cue.RING
            "speak" -> Cue.SPEAK
            "music" -> Cue.MUSIC
            "wait" -> Cue.WAIT
            else -> return fallback
        }
        val contact = when (json.optString("contact").trim().lowercase()) {
            "call" -> Contact.CALL
            "message" -> Contact.MESSAGE
            "wait" -> Contact.WAIT
            else -> null
        }
        val issue = when {
            cue == Cue.MUSIC && !canMusic -> "角色选择音乐，但目前未获音乐控制权限"
            cue == Cue.SPEAK && !inCall -> "角色选择说话，但尚未连接通话，先须有实际可听的联系渠道"
            else -> ""
        }
        return Choice(
            cue = cue,
            reason = json.optString("reason").trim().take(180),
            volumePercent = json.optInt("volumePercent", -1).takeIf { it in 0..65 },
            openingLine = json.optString("openingLine").trim().take(180),
            ringSeconds = json.optInt("ringSeconds", 6).coerceIn(2, 10),
            contact = contact,
            reviewAfterSeconds = json.optLong("reviewAfterSeconds", -1L)
                .takeIf { it in 90L..1_200L },
            executionIssue = issue,
        )
    }
}
