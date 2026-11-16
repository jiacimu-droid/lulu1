package com.jiacimu.lulu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Read-only projection of the owner's real 自序 → 所行, not an alternate task store. */
internal object SelfTodoRoleRead {

    fun execute(context: Context, args: JSONObject, now: Instant = Instant.now()): JSONObject {
        val tasks = SelfTodoStore.readForRole(context)
            ?: return JSONObject().put("success", false).put("status", "failed")
                .put("summary", "无法可靠读取自序·所行；原始笔记或待办数据不完整，不能假定清单为空")
        return snapshot(tasks, now.atZone(ZoneId.systemDefault()).toLocalDate(), now, args)
    }

    internal fun snapshot(
        tasks: List<TodoTask>, today: LocalDate, now: Instant,
        args: JSONObject = JSONObject(),
    ): JSONObject {
        val scope = args.optString("scope", "today").ifBlank { "today" }
        require(scope == "today" || scope == "all") { "scope 只能是 today 或 all" }
        val offset = args.optInt("offset", 0)
        require(offset in 0..100_000) { "offset 必须在 0 到 100000 之间" }
        val zone = ZoneId.systemDefault()
        val pending = SelfTodoCalendar.allPending(tasks, today)
        val overdue = pending.filter { it.date.isBefore(today) }
        val dueToday = pending.filter { it.date == today }
        val future = pending.filter { it.date.isAfter(today) }
        val unscheduled = pending.count {
            it.task.repeat == TodoRepeat.ONCE && !it.task.dateSpecified
        }
        val checkedToday = tasks.flatMap { task ->
            task.completions.filter { log -> log.completedAt > 0 &&
                Instant.ofEpochMilli(log.completedAt).atZone(zone).toLocalDate() == today
            }.map { task to it }
        }.sortedByDescending { it.second.completedAt }
        val selected = if (scope == "all") pending
            else pending.filter { !it.date.isAfter(today) }
        // Return every matching task from the requested offset. This local checklist is
        // small; silently slicing it to 30 hid the owner's real completion history.
        val shown = selected.drop(offset)
        val entries = JSONArray()
        shown.forEach { occurrence ->
            val task = occurrence.task
            entries.put(JSONObject().put("taskId", task.id)
                .put("title", task.title).put("note", task.note)
                .put("dueDate", if (task.repeat == TodoRepeat.ONCE && !task.dateSpecified)
                    JSONObject.NULL else occurrence.date.toString())
                .put("time", task.minutes?.let { "%02d:%02d".format(it / 60, it % 60) }
                    ?: JSONObject.NULL)
                .put("repeat", task.repeat.name.lowercase())
                .put("dateSpecified", task.dateSpecified)
                .put("state", when {
                    task.repeat == TodoRepeat.ONCE && !task.dateSpecified -> "unscheduled"
                    occurrence.date.isBefore(today) -> "overdue"
                    occurrence.date == today -> "pending_today"
                    else -> "upcoming"
                }))
        }
        val checked = JSONArray()
        checkedToday.forEach { (task, log) ->
            checked.put(JSONObject().put("taskId", task.id).put("title", task.title)
                .put("scheduledDate", log.date.toString())
                .put("checkedAt", Instant.ofEpochMilli(log.completedAt).toString())
                .put("state", "checked_by_owner"))
        }
        val summary = buildString {
            append("自序·所行（设备本地日期${today}）：今天未完成${dueToday.size}项，")
            append("逾期未完成${overdue.size}项，今天勾选完成${checkedToday.size}次，")
            append("未来待办${future.size}项，未指定日期${unscheduled}项。")
            val immediate = (dueToday + overdue)
            if (immediate.isNotEmpty()) append("\n目前要做：")
            immediate.forEach { item ->
                val time = item.task.minutes?.let { "%02d:%02d".format(it / 60, it % 60) }
                    ?: "未设时刻"
                append("\n- ${if (item.date.isBefore(today)) "逾期${item.date} " else ""}${item.task.title}（${time}）")
            }
            if (checkedToday.isNotEmpty()) {
                append("\n今天勾选完成：")
                checkedToday.forEach { (task, _) -> append("\n- ${task.title}") }
            }
            if (scope == "all" && future.isNotEmpty()) {
                append("\n以后安排：")
                future.forEach { item ->
                    append("\n- ${item.date} ${item.task.title}")
                }
            }
            append("\n重要：所行是主人自行填写的计划与勾选，不等于已证实的学习时长、专注状态或睡眠；核实要依真实计时记录和主人同意的监督观察。")
        }
        return JSONObject().put("success", true).put("status", "succeeded")
            .put("source", "self_todo_store").put("localDate", today.toString())
            .put("observedAt", now.toString())
            .put("scope", scope).put("offset", offset)
            .put("nextOffset", JSONObject.NULL)
            .put("pendingTotalInScope", selected.size)
            .put("pendingTodayCount", dueToday.size).put("overdueCount", overdue.size)
            .put("upcomingCount", future.size).put("unscheduledCount", unscheduled)
            .put("checkedTodayCount", checkedToday.size)
            .put("pending", entries).put("completedToday", checked)
            .put("summary", summary)
    }

    /** Fresh, complete task titles and checkmarks from the SAME owner store.
     * Included in existing role decisions without another paid tool turn.
     * Private notes stay behind the explicit read_self_todo tool. */
    internal fun briefContext(tasks: List<TodoTask>, now: Instant): String {
        val date = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val pending = SelfTodoCalendar.allPending(tasks, date)
        val dueToday = pending.filter { it.date == date }
        val overdue = pending.filter { it.date.isBefore(date) }
        val futureCount = pending.count { it.date.isAfter(date) }
        val completedToday = tasks.flatMap { task ->
            task.completions.filter { log -> log.completedAt > 0 &&
                Instant.ofEpochMilli(log.completedAt)
                    .atZone(ZoneId.systemDefault()).toLocalDate() == date
            }.map { task.title to it.completedAt }
        }.sortedByDescending { it.second }
        val future = pending.filter { it.date.isAfter(date) }
        return buildString {
            appendLine("【主人自序·所行｜本机当前已保存清单，读取日期${date}】今天未完成${dueToday.size}项，逾期${overdue.size}项，今天勾选${completedToday.size}次，未来待办${futureCount}项。")
            if (dueToday.isNotEmpty()) {
                appendLine("今天的待办：")
                dueToday.forEach { item ->
                    appendLine("- ${item.task.title}${item.task.minutes?.let { "（%02d:%02d）".format(it / 60, it % 60) }.orEmpty()}")
                }
            }
            if (overdue.isNotEmpty()) {
                appendLine("逾期的待办：")
                overdue.forEach { item ->
                    appendLine("- ${item.date} ${item.task.title}")
                }
            }
            if (future.isNotEmpty()) {
                appendLine("未来的待办：")
                future.forEach { item ->
                    appendLine("- ${item.date} ${item.task.title}")
                }
            }
            if (completedToday.isNotEmpty()) {
                appendLine("今天确实在所行中勾选的事项：")
                completedToday.forEach { (title, _) -> appendLine("- ${title}") }
            }
            append("这是本次从本机权威待办存储即时读取的完整标题与勾选摘要，不是昨天聊天中的旧记忆；")
            append("如需任务备注、详细日期及结构化记录，可自行调用 read_self_todo(scope=today|all)，无需主人截图。")
            append("清单未勾选不能证明主人没学习，须结合真实学习计时或获许可的监督证据。")
        }.trim()
    }

    /** If current storage is unreadable, never represent old memories as live data. */
    fun opportunity(context: Context, now: Instant = Instant.now()): String {
        val tasks = SelfTodoStore.readForRole(context)
            ?: return "【主人自序·所行】本机当前保存的待办读取失败；不能凭旧聊天推测今天清单或声称数据停在昨天。read_self_todo 也须以真实回执为准。"
        return briefContext(tasks, now)
    }
}
