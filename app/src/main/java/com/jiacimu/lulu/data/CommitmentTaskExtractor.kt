package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId

internal suspend fun extractCommitmentTaskDrafts(
    characterId: String,
    userText: String,
    characterText: String,
    activeTasks: List<CommitmentTask>,
): List<CommitmentTaskDraft> {
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    // A clear, accepted wake-up request is an executable obligation, not prose.
    // Resolve its deadline deterministically before asking the model; a model returning
    // [] or malformed JSON must never drop "明天10点叫我" on the floor.
    WakeCommitmentParser.parse(userText, characterText, now, zone)?.let { wake ->
        val matching = activeTasks.filter {
            (it.goal + it.completionCondition).let { content ->
                listOf("叫醒", "起床", "wake").any(content::contains)
            }
        }
        if (matching.size == 1) {
            val previous = matching.single()
            if (previous.dueAt == wake.dueAt && previous.deliveryAction == wake.deliveryAction) {
                return emptyList()
            }
            return listOf(wake.copy(action = "reschedule", targetTaskId = previous.id))
        }
        return listOf(wake)
    }
    TimedContactCommitmentParser.parse(userText, characterText, now, zone)?.let { contact ->
        val matching = activeTasks.filter { task ->
            task.goal.contains("按约定联系用户") || task.goal.contains("按约定给用户打电话")
        }
        if (matching.size == 1) {
            val previous = matching.single()
            if (previous.dueAt == contact.dueAt && previous.deliveryAction == contact.deliveryAction)
                return emptyList()
            return listOf(contact.copy(action = "reschedule", targetTaskId = previous.id))
        }
        return listOf(contact)
    }
    val result = LuluAiServices.gateway.generate(
        characterId = characterId,
        facts = buildString {
            appendLine("当前时间=$now")
            appendLine("当前时区=${zone.id}")
            appendLine("用户本轮：$userText")
            appendLine("角色本轮完整回复：$characterText")
            if (activeTasks.isNotEmpty()) {
                appendLine("现有未完成任务（完整列表，不能因数量多忽略旧任务）：")
                activeTasks.forEach { task ->
                    appendLine("- id=${task.id}; goal=${task.goal}; deliveryAction=${task.deliveryAction}; dueAt=${task.dueAt}; status=${task.status}; revision=${task.revision}")
                }
            }
        },
        instruction = """
            判断本轮双方说话是否产生、修改、取消或完成了角色需要真正负责的事项。
            只返回 JSON 数组，不要代码块。没有任务变化时返回 []。
            每项：{"action":"create|reschedule|cancel|complete","goal":"任务目标","dueAt":"ISO-8601时间或空字符串","timezone":"IANA时区或空字符串","completionCondition":"怎样才算真的完成","steps":["步骤"],"deliveryAction":"start_call|send_private_message","needsClarification":true或false,"targetTaskId":"修改旧任务时填写，否则空"}
            规则：
            1. 必须同时阅读用户和角色完整回复。角色主动说“我来叫你”“我会提醒你”也算承担责任，不能只看用户关键词。
            2. 如果用户只说想睡一会儿，而角色答应叫醒但没有明确多久/几点，create 且 needsClarification=true，dueAt 留空；不要猜时间。
            3. 有明确相对时间时根据当前时间换算；有明确当地时刻时使用当前时区。无法可靠确定具体时间就不要编造。
            4. “创建闹钟”只是执行步骤，不等于目标完成。叫醒类任务应以用户明确反馈醒了/停止叫醒为完成或取消依据。
            5. 用户说“改成九点”“不用叫了”“我醒了”时，优先匹配现有任务并返回 reschedule/cancel/complete；必须填写准确 targetTaskId，不能随便改第一条旧任务。
            6. 没回复不能推断用户仍在睡或任务已完成。
            7. 只创建真正需要未来履行或继续跟进的事项，普通寒暄和随口建议不要建任务。
            8. 角色主动承诺给用户打电话，特别是“等会儿给你打电话催睡”等，必须 create 且 deliveryAction=start_call；不能用一条私聊消息冒充拨号。仅询问要不要打、只是想打、明确拒绝时不能建任务。
            9. 对角色自己承诺的“等会儿/待会儿/一会儿”来电而没有具体分钟数，请角色按此刻的关系、事情紧急程度、用户是否困了和自己的行事习惯，自行选择合理的近期来电时间（通常在未来3至25分钟内），写成明确 dueAt；不要所有角色都固定10分钟，不许说成是用户定的时间。用户说了具体时刻时以用户的时刻优先。
        """.trimIndent(),
        source = "承诺任务",
        title = "承诺任务提取",
        maxTokens = 1_500,
        usage = ModelUsage.Chat,
    )
    val drafts = if (result.isSuccess) parseCommitmentTaskDrafts(result.getOrThrow().text) else emptyList()
    if (!detectSelfPromisedCall(characterText)) return drafts
    val promisedCall = drafts.filter { it.action == "create" &&
        (it.goal + " " + it.steps.joinToString(" ")).let { goal ->
            listOf("电话", "来电", "拨号", "催睡").any(goal::contains)
        }
    }
    if (promisedCall.isNotEmpty()) {
        return drafts.map { task ->
            if (task !in promisedCall) task else task.copy(
                deliveryAction = "start_call",
                dueAt = task.dueAt ?: if (isVagueFutureCall(characterText)) now.plusSeconds(60L * fallbackCallMinutes(characterText)) else null,
                needsClarification = task.dueAt == null && !isVagueFutureCall(characterText),
            )
        }
    }
    if (drafts.isNotEmpty() || !isVagueFutureCall(characterText)) return drafts
    // A precise fallback: the character voluntarily promised an imminent real call.
    return listOf(CommitmentTaskDraft(
        action = "create",
        goal = "履行自己答应用户的主动来电",
        dueAt = now.plusSeconds(60L * fallbackCallMinutes(characterText)),
        timezone = zone.id,
        completionCondition = "发起真实来电，未接听也保留结果",
        steps = listOf("使用真实来电工具拨号"),
        deliveryAction = "start_call",
    ))
}

private fun parseCommitmentTaskDrafts(raw: String): List<CommitmentTaskDraft> = runCatching {
    val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val array = JSONArray(clean)
    buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val action = item.optString("action").trim().lowercase()
            if (action !in setOf("create", "reschedule", "cancel", "complete")) continue
            val goal = item.optString("goal").trim()
            if (goal.isBlank() && action == "create") continue
            val dueAt = item.optString("dueAt").trim().takeIf(String::isNotBlank)
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val steps = item.optJSONArray("steps")?.let { values ->
                buildList { for (i in 0 until values.length()) values.optString(i).trim().takeIf(String::isNotBlank)?.let(::add) }
            }.orEmpty()
            add(
                CommitmentTaskDraft(
                    action = action,
                    goal = goal,
                    dueAt = dueAt,
                    timezone = item.optString("timezone").trim().takeIf(String::isNotBlank),
                    completionCondition = item.optString("completionCondition").trim(),
                    steps = steps,
                    deliveryAction = item.optString("deliveryAction").takeIf { it == "start_call" } ?: "send_private_message",
                    needsClarification = item.optBoolean("needsClarification", dueAt == null && action == "create"),
                    targetTaskId = item.optString("targetTaskId").trim().takeIf(String::isNotBlank),
                ),
            )
        }
    }
}.getOrDefault(emptyList())

/** Only an affirmative, character-owned future call can create a scheduled action. */
internal fun detectSelfPromisedCall(text: String): Boolean {
    val clean = text.replace(Regex("\\s+"), "").take(700)
    if (clean.isBlank()) return false
    if (listOf("不打电话", "不会打电话", "不想打电话", "不要打电话", "别打电话").any(clean::contains)) return false
    if (listOf("要不要我打", "要我打电话吗", "想不想我打", "可以给你打电话吗").any(clean::contains)) return false
    val future = listOf("我等会", "我待会", "我一会", "我过会", "我稍后",
        "我晚点", "我会", "我来", "我给你", "等会我", "待会我", "一会我", "过会我").any(clean::contains)
    val call = listOf("打电话", "打个电话", "来电话", "打给你", "给你打", "拨电话", "电话催", "电话叫").any(clean::contains)
    return future && call
}

private fun isVagueFutureCall(text: String): Boolean =
    listOf("等会", "待会", "一会", "过会", "稍后", "晚点").any(text::contains)

/** Used only if the model fails to choose an actual time for its own vague promise. */
internal fun fallbackCallMinutes(text: String): Long = when {
    listOf("马上", "现在就", "立刻").any(text::contains) -> 2L
    listOf("催睡", "早点睡", "去睡觉", "还没睡", "不睡觉").any(text::contains) -> 5L
    listOf("晚点", "晚些", "过一阵").any(text::contains) -> 20L
    text.contains("稍后") -> 15L
    else -> 10L
}
