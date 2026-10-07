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
                    appendLine("- id=${task.id}; goal=${task.goal}; dueAt=${task.dueAt}; status=${task.status}; revision=${task.revision}")
                }
            }
        },
        instruction = """
            判断本轮双方说话是否产生、修改、取消或完成了角色需要真正负责的事项。
            只返回 JSON 数组，不要代码块。没有任务变化时返回 []。
            每项：{"action":"create|reschedule|cancel|complete","goal":"任务目标","dueAt":"ISO-8601时间或空字符串","timezone":"IANA时区或空字符串","completionCondition":"怎样才算真的完成","steps":["步骤"],"needsClarification":true或false,"targetTaskId":"修改旧任务时填写，否则空"}
            规则：
            1. 必须同时阅读用户和角色完整回复。角色主动说“我来叫你”“我会提醒你”也算承担责任，不能只看用户关键词。
            2. 如果用户只说想睡一会儿，而角色答应叫醒但没有明确多久/几点，create 且 needsClarification=true，dueAt 留空；不要猜时间。
            3. 有明确相对时间时根据当前时间换算；有明确当地时刻时使用当前时区。无法可靠确定具体时间就不要编造。
            4. “创建闹钟”只是执行步骤，不等于目标完成。叫醒类任务应以用户明确反馈醒了/停止叫醒为完成或取消依据。
            5. 用户说“改成九点”“不用叫了”“我醒了”时，优先匹配现有任务并返回 reschedule/cancel/complete；必须填写准确 targetTaskId，不能随便改第一条旧任务。
            6. 没回复不能推断用户仍在睡或任务已完成。
            7. 只创建真正需要未来履行或继续跟进的事项，普通寒暄和随口建议不要建任务。
        """.trimIndent(),
        source = "承诺任务",
        title = "承诺任务提取",
        maxTokens = 1_500,
        usage = ModelUsage.Chat,
    )
    if (result.isFailure) return emptyList()
    return parseCommitmentTaskDrafts(result.getOrThrow().text)
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
                    needsClarification = item.optBoolean("needsClarification", dueAt == null && action == "create"),
                    targetTaskId = item.optString("targetTaskId").trim().takeIf(String::isNotBlank),
                ),
            )
        }
    }
}.getOrDefault(emptyList())
