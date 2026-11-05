package com.jiacimu.lulu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Read-only projection of the owner's real 自序 → 所行, not an alternate task store. */
internal object SelfTodoRoleRead {
    private const val PAGE_SIZE = 30

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
        val shown = selected.drop(offset).take(PAGE_SIZE)
        val entries = JSONArray()
        shown.forEach { occurrence ->
            val task = occurrence.task
            entries.put(JSONObject().put("taskId", task.id)
                .put("title", task.title).put("note", task.note.take(240))
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
        checkedToday.take(PAGE_SIZE).forEach { (task, log) ->
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
            immediate.take(12).forEach { item ->
                val time = item.task.minutes?.let { "%02d:%02d".format(it / 60, it % 60) }
                    ?: "未设时刻"
                append("\n- ${if (item.date.isBefore(today)) "逾期${item.date} " else ""}${item.task.title.take(68)}（${time}）")
            }
            if (checkedToday.isNotEmpty()) {
                append("\n今天勾选完成：")
                checkedToday.take(5).forEach { (task, _) -> append("\n- ${task.title.take(68)}") }
            }
            if (scope == "all" && future.isNotEmpty()) {
                append("\n以后安排：")
                future.take(4).forEach { item ->
                    append("\n- ${item.date} ${item.task.title.take(68)}")
                }
            }
            append("\n重要：所行是主人自行填写的计划与勾选，不等于已证实的学习时长、专注状态或睡眠；核实要依真实计时记录和主人同意的监督观察。")
        }
        return JSONObject().put("success", true).put("status", "succeeded")
            .put("source", "self_todo_store").put("localDate", today.toString())
            .put("observedAt", now.toString())
            .put("scope", scope).put("offset", offset)
            .put("nextOffset", if (offset + shown.size < selected.size) offset + shown.size else JSONObject.NULL)
            .put("pendingTotalInScope", selected.size)
            .put("pendingTodayCount", dueToday.size).put("overdueCount", overdue.size)
            .put("upcomingCount", future.size).put("unscheduledCount", unscheduled)
            .put("checkedTodayCount", checkedToday.size)
            .put("pending", entries).put("completedToday", checked)
            .put("summary", summary)
    }

    /** Small context cue; the role chooses whether to fetch full detail. */
    fun opportunity(context: Context, now: Instant = Instant.now()): String {
        val tasks = SelfTodoStore.readForRole(context)
            ?: return "【自序·所行】读取失败；不能假定今日清单为空"
        val date = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val due = SelfTodoCalendar.allPending(tasks, date)
        val todayCount = due.count { it.date == date }
        val overdueCount = due.count { it.date.isBefore(date) }
        val checked = tasks.sumOf { task -> task.completions.count { log ->
            log.completedAt > 0 && Instant.ofEpochMilli(log.completedAt)
                .atZone(ZoneId.systemDefault()).toLocalDate() == date
        } }
        return "【主人自序·所行｜本机日期${date}】今天未完成${todayCount}项，逾期${overdueCount}项，今天勾选${checked}次。" +
            "你可自主调用 read_self_todo 查最新任务详情，再结合真实学习记录、限时获授权的学习监督决定是否联系、怎样鼓励或提醒；" +
            "待办未勾选不代表主人没有学习，不能擅自完成、修改清单，也不需要自动派发固定催促。"
    }
}
