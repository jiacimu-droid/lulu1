package com.jiacimu.lulu

import com.jiacimu.lulu.data.CapabilityRegistry
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.RoleDayScheduleStore
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.MemoryTestEnvironment
import com.jiacimu.lulu.data.ToolRouter
import com.jiacimu.lulu.data.SearchReceiptFeedback
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SelfTodoRoleReadTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        MemoryTestEnvironment.release()
        LuluRepositories.initialize(app)
        SharedExperienceTimeline.initialize(app)
        app.getSharedPreferences("lulu_self_todo_v2", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        app.getSharedPreferences("lulu_self_preface_v1", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After fun tearDown() { MemoryTestEnvironment.release() }

    @Test fun todayOverdueFutureAndCheckedTodayUseTheSameOccurrenceCalendar() {
        val today = LocalDate.of(2026, 11, 5)
        val now = today.atTime(15, 0).atZone(ZoneId.systemDefault()).toInstant()
        val finishedAt = today.atTime(11, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun task(id: String, date: LocalDate, repeat: TodoRepeat = TodoRepeat.ONCE,
                 completed: List<TodoCompletion> = emptyList()) =
            TodoTask(id, "事项-$id", "备注-$id", date, 8 * 60,
                repeat, now.toEpochMilli(), completed)
        val tasks = listOf(
            task("today", today),
            task("old", today.minusDays(1)),
            task("weekly", today.minusDays(7), TodoRepeat.WEEKLY),
            task("daily", today.minusDays(2), TodoRepeat.DAILY,
                listOf(TodoCompletion(today, finishedAt))),
            task("future", today.plusDays(1)),
            task("finished_old", today.minusDays(1), completed =
                listOf(TodoCompletion(today.minusDays(1), finishedAt))),
        )
        val todayView = SelfTodoRoleRead.snapshot(tasks, today, now)
        assertTrue(todayView.toString(), todayView.getBoolean("success"))
        assertEquals(2, todayView.getInt("pendingTodayCount"))
        assertEquals(1, todayView.getInt("overdueCount"))
        assertEquals(1, todayView.getInt("upcomingCount"))
        assertEquals(2, todayView.getInt("checkedTodayCount"))
        assertEquals(3, todayView.getInt("scheduledTodayCount"))
        assertEquals(1, todayView.getInt("scheduledTodayCompletedCount"))
        assertEquals(2, todayView.getInt("scheduledTodayRemainingCount"))
        assertTrue(SelfTodoRoleRead.briefContext(tasks, now).contains("今天明确安排的事项已完成1/3项"))
        assertEquals(2, todayView.getJSONArray("pending").length())
        assertTrue(todayView.getString("summary").contains("学习时长"))
        assertFalse(todayView.getJSONArray("pending").toString().contains("future"))
        assertFalse(todayView.getJSONArray("pending").toString().contains("finished_old"))
        val all = SelfTodoRoleRead.snapshot(tasks, today, now, JSONObject().put("scope", "all"))
        assertEquals(4, all.getJSONArray("pending").length())
        assertTrue(all.getJSONArray("pending").toString().contains("future"))
        val allPending = all.getJSONArray("pending")
        // The owner's deliberate dated plans are prominent: today's
        // one-off/weekly items first, then upcoming before overdue. The
        // previous assertion expected overdue at index 2 and contradicted
        // the shared SelfTodoCalendar ordering introduced for 所行.
        assertEquals("today", allPending.getJSONObject(0).getString("taskId"))
        assertEquals("pending_today", allPending.getJSONObject(0).getString("state"))
        assertEquals("weekly", allPending.getJSONObject(1).getString("taskId"))
        assertEquals("future", allPending.getJSONObject(2).getString("taskId"))
        assertEquals("upcoming", allPending.getJSONObject(2).getString("state"))
        assertEquals("old", allPending.getJSONObject(3).getString("taskId"))
        assertEquals("overdue", allPending.getJSONObject(3).getString("state"))
        // Today's explicit read must not grow with historical overdue tasks.
        assertFalse(todayView.getJSONArray("pending").toString().contains("old"))
        assertEquals(1, todayView.getInt("overdueCount"))
    }

    @Test fun readOnlyRoleSeesUnmigratedOldTodoButNeverOtherPrivateNotebookPages() {
        val raw = JSONArray().put(JSONObject().put("id", "legacy-todo")
            .put("area", "所行").put("title", "复习今天的刑法")
            .put("body", "记一页错题").put("done", false)
            .put("updated", System.currentTimeMillis()))
            .put(JSONObject().put("id", "private-note")
                .put("area", "所思").put("title", "私密日记")
                .put("body", "绝不应出现在工具结果")
                .put("updated", System.currentTimeMillis()))
            .toString()
        val old = app.getSharedPreferences("lulu_self_preface_v1",
            android.content.Context.MODE_PRIVATE)
        assertTrue(old.edit().putString("entries", raw).commit())
        val result = SelfTodoRoleRead.execute(app, JSONObject())
        assertTrue(result.toString(), result.getBoolean("success"))
        assertEquals(1, result.getInt("pendingTodayCount"))
        assertTrue(result.toString().contains("复习今天的刑法"))
        assertFalse(result.toString().contains("私密日记"))
        assertEquals(raw, old.getString("entries", ""))
        // A read must not mark migration complete or rewrite owner tasks.
        val taskPrefs = app.getSharedPreferences("lulu_self_todo_v2",
            android.content.Context.MODE_PRIVATE)
        assertFalse(taskPrefs.contains("legacy_imported"))
        assertFalse(taskPrefs.contains("tasks"))
        // Old data is still migrated by the same UI path if user opens 所行.
        val legacyTasks = SelfTodoStore.loadWithLegacy(app, legacySelfTodoRecords(app)!!)
        assertEquals(1, legacyTasks.count { it.id == "legacy-todo" })
        assertFalse(legacyTasks.single().dateSpecified)
        val later = LocalDate.now().plusDays(5)
        assertEquals(TodoSection.REGULAR, SelfTodoCalendar.section(
            SelfTodoCalendar.pending(legacyTasks.single(), later).single(), later))
    }

    @Test fun toolRouterUsesTheOnlyAuthoritativeReadAndRejectsResidentAccess() = runBlocking {
        val characterId = MigratedDomainStores.characters.create("学习陪伴角色").characterId
        val date = LocalDate.now()
        val task = TodoTask("review", "整理错题本", "", date, 17 * 60,
            TodoRepeat.ONCE, System.currentTimeMillis())
        assertTrue(SelfTodoStore.save(app, listOf(task)))
        val cap = CapabilityRegistry.find("read_self_todo")
        assertNotNull(cap)
        assertTrue(cap!!.retrySafe)
        assertFalse(CapabilityRegistry.allows(app, "resident:cannot-read", cap, false))
        val requestId = "owner-todo-read-" + UUID.randomUUID()
        val result = JSONObject(ToolRouter.execute(app, characterId, "read_self_todo",
            JSONObject().put("scope", "today"), requestId = requestId))
        assertTrue(result.toString(), result.getBoolean("success"))
        assertEquals(1, result.getInt("pendingTodayCount"))
        assertEquals("review", result.getJSONArray("pending").getJSONObject(0).getString("taskId"))
        assertTrue(result.getString("summary").contains("整理错题本"))
        assertEquals(listOf(task), SelfTodoStore.read(app))
    }

    @Test fun realScheduledReadKeepsItsReceiptAndRollsTheSameDailyPlan() = runBlocking {
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        val role = MigratedDomainStores.characters.create("每日看所行").characterId
        val now = Instant.now()
        val due = now.minusSeconds(10)
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val task = TodoTask("finish-practice", "完成今天的练习", "", today, null,
            TodoRepeat.ONCE, now.toEpochMilli())
        assertTrue(SelfTodoStore.save(app, listOf(task)))
        val quietRead = JSONObject().put("scope", "today").put("reportToUser", false)
        val plan = RoleDayScheduleStore.arrange(
            app, role, "看今日所行", "继续关心今天的学习计划", due, now.minusSeconds(90),
            activityAction = "read_self_todo", repeat = "daily", activityArgs = quietRead,
            selfInitiated = true,
        )
        val args = JSONObject(quietRead.toString()).put("planId", plan.id)
        val requestId = "daily-todo-$role"
        val result = JSONObject(ToolRouter.execute(app, role, "read_self_todo", args, requestId = requestId))
        assertTrue(result.toString(), result.getBoolean("success"))
        assertEquals(1, result.getInt("scheduledTodayRemainingCount"))
        val next = RoleDayScheduleStore.plans(app, role).single()
        assertEquals(plan.id, next.id)
        assertEquals(due.plusSeconds(86_400), next.dueAt)
        assertEquals(result.getString("evidenceId"), next.resultEvidenceId)
        assertEquals("planned", next.status)
        ToolRouter.execute(app, role, "read_self_todo", args, requestId = requestId)
        assertEquals(next, RoleDayScheduleStore.plans(app, role).single())
        assertEquals(listOf(task), SelfTodoStore.read(app))
    }

    @Test fun undatedTaskSurvivesPersistenceAndRoleReadDoesNotInventDeadline() {
        val today = LocalDate.of(2026, 11, 5)
        val task = TodoTask("undated", "随时整理书桌", "", today.minusDays(12),
            null, TodoRepeat.ONCE, 100L, dateSpecified = false)
        assertTrue(SelfTodoStore.save(app, listOf(task)))
        assertEquals(task, SelfTodoStore.read(app).single())
        val now = today.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val output = SelfTodoRoleRead.snapshot(SelfTodoStore.read(app), today, now)
        assertEquals(0, output.getInt("overdueCount"))
        assertEquals(1, output.getInt("unscheduledCount"))
        val row = output.getJSONArray("pending").getJSONObject(0)
        assertEquals("unscheduled", row.getString("state"))
        assertTrue(row.isNull("dueDate"))
    }

    @Test fun existingChatContextSeesLatestSavedTitlesWithoutAnotherModelToolCall() {
        val date = LocalDate.now()
        val now = date.atTime(20, 23).atZone(ZoneId.systemDefault()).toInstant()
        val task = TodoTask("today-live", "今天先复习刑法", "私密备注不可默认外露",
            date, 20 * 60, TodoRepeat.ONCE, now.toEpochMilli())
        assertTrue(SelfTodoStore.save(app, listOf(task)))
        val initial = SelfTodoRoleRead.opportunity(app, now)
        assertTrue(initial, initial.contains("今天先复习刑法"))
        assertTrue(initial, initial.contains("读取日期${date}"))
        assertFalse(initial.contains("私密备注不可默认外露"))
        // The phone can edit 所行 between two ordinary chat decisions. The
        // second decision must use the same durable store, not yesterday\'s prompt.
        assertTrue(SelfTodoStore.save(app, listOf(task.copy(title = "今天先复习民法"))))
        val refreshed = SelfTodoRoleRead.opportunity(app, now)
        assertTrue(refreshed, refreshed.contains("今天先复习民法"))
        assertFalse(refreshed, refreshed.contains("今天先复习刑法"))
        val checked = SelfTodoCalendar.complete(task.copy(title = "今天先复习民法"),
            date, now.toEpochMilli())
        assertTrue(SelfTodoStore.save(app, listOf(checked)))
        val afterChecking = SelfTodoRoleRead.opportunity(app, now)
        assertTrue(afterChecking, afterChecking.contains("今天未完成0项"))
        assertTrue(afterChecking, afterChecking.contains("今天勾选1次"))
        assertTrue(afterChecking, afterChecking.contains("今天先复习民法"))
    }

    @Test fun briefContextIncludesEverySavedTitleWithoutExposingPrivateNotes() {
        val date = LocalDate.of(2026, 11, 15)
        val now = date.atTime(20, 0).atZone(ZoneId.systemDefault()).toInstant()
        fun item(id: Int, due: LocalDate): TodoTask =
            TodoTask("todo-$id", "计划$id", "备注$id", due, null, TodoRepeat.ONCE, id.toLong())
        val tasks = (1..7).map { item(it, date) } +
            (8..10).map { item(it, date.minusDays(1)) } + item(11, date.plusDays(1))
        val brief = SelfTodoRoleRead.briefContext(tasks, now)
        for (id in 1..7) assertTrue(brief, brief.contains("计划$id"))
        for (id in 8..11) assertFalse(brief, brief.contains("计划$id"))
        assertTrue(brief.contains("今天未完成7项"))
        assertTrue(brief.contains("逾期3项"))
        assertTrue(brief.contains("未来待办1项"))
        assertFalse(brief.contains("另有4项"))
        assertTrue(brief.contains("read_self_todo"))
        // A capability hint mentioning note fields is fine; the owner's actual
        // private note *values* must never leak into the ambient context.
        for (id in 1..11) assertFalse(brief, brief.contains("备注$id"))
        assertTrue(brief.contains("计划7"))
        val full = SelfTodoRoleRead.snapshot(tasks, date, now,
            JSONObject().put("scope", "all"))
        assertEquals(11, full.getJSONArray("pending").length())
        assertTrue(full.toString().contains("计划7"))
        assertTrue(full.toString().contains("备注7"))
    }
    @Test fun todaysEntireOwnerListIsReturnedWithoutPageOrReceiptTruncation() {
        val day = LocalDate.of(2026, 11, 16)
        val now = day.atTime(21, 0).atZone(ZoneId.systemDefault()).toInstant()
        val checkedMillis = day.atTime(20, 0).atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        fun task(index: Int, done: Boolean): TodoTask = TodoTask(
            "item-$index", if (done) "完成$index" else "待办$index",
            "完整私人备注$index：" + "字".repeat(280),
            day, null, TodoRepeat.ONCE, index.toLong(),
            if (done) listOf(TodoCompletion(day, checkedMillis)) else emptyList(),
        )
        val tasks = (1..42).map { task(it, false) } +
            (43..77).map { task(it, true) }
        val output = SelfTodoRoleRead.snapshot(tasks, day, now)
        assertEquals(42, output.getJSONArray("pending").length())
        assertEquals(35, output.getJSONArray("completedToday").length())
        assertEquals(35, output.getInt("checkedTodayCount"))
        assertTrue(output.isNull("nextOffset"))
        assertEquals(42, output.getInt("pendingTotalInScope"))
        assertTrue(output.getString("summary").contains("待办42"))
        assertTrue(output.getString("summary").contains("完成77"))
        assertEquals(("完整私人备注42：" + "字".repeat(280)).take(240),
            output.getJSONArray("pending").getJSONObject(41).getString("note"))
        val fromLaterOffset = SelfTodoRoleRead.snapshot(tasks, day, now,
            JSONObject().put("offset", 30))
        assertEquals(12, fromLaterOffset.getJSONArray("pending").length())
        assertTrue(fromLaterOffset.isNull("nextOffset"))

        val ambient = SelfTodoRoleRead.briefContext(tasks, now)
        assertTrue(ambient.contains("待办42"))
        assertTrue(ambient.contains("完成77"))
        assertFalse(ambient.contains("完整私人备注"))
        val verified = SearchReceiptFeedback.from("read_self_todo",
            JSONObject().put("scope", "today"), output)
        assertNotNull(verified)
        assertTrue(verified!!.details.contains("待办42"))
        assertTrue(verified.details.contains("完成77"))
        assertTrue(verified.details.contains("完整私人备注42"))
    }

    @Test fun accumulatedHistoricalAndFutureTasksArePagedButNeverPushedIntoTodaysContext() {
        val day = LocalDate.of(2026, 11, 16)
        val now = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
        val tasks = listOf(TodoTask("today", "今日完整标题", "", day, null,
            TodoRepeat.ONCE, 1L)) +
            (1..80).map { n -> TodoTask("old-$n", "旧计划$n", "",
                day.minusDays(n.toLong()), null, TodoRepeat.ONCE, n.toLong()) } +
            (1..60).map { n -> TodoTask("future-$n", "未来计划$n", "",
                day.plusDays(n.toLong()), null, TodoRepeat.ONCE, n.toLong()) }
        val ambient = SelfTodoRoleRead.briefContext(tasks, now)
        assertTrue(ambient.contains("今日完整标题"))
        assertTrue(ambient.contains("逾期80项"))
        assertTrue(ambient.contains("未来待办60项"))
        assertFalse(ambient.contains("旧计划"))
        assertFalse(ambient.contains("未来计划"))
        val today = SelfTodoRoleRead.snapshot(tasks, day, now)
        assertEquals(1, today.getJSONArray("pending").length())
        assertTrue(today.isNull("nextOffset"))
        val all = SelfTodoRoleRead.snapshot(tasks, day, now, JSONObject().put("scope", "all"))
        assertEquals(30, all.getJSONArray("pending").length())
        assertEquals(141, all.getInt("pendingTotalInScope"))
        assertEquals(30, all.getInt("nextOffset"))
        assertFalse(all.getString("summary").contains("旧计划"))
        val second = SelfTodoRoleRead.snapshot(tasks, day, now,
            JSONObject().put("scope", "all").put("offset", all.getInt("nextOffset")))
        assertEquals(30, second.getJSONArray("pending").length())
        assertEquals(60, second.getInt("nextOffset"))
    }

    @Test fun unreadableOwnerDataIsAnErrorNotAFalseEmptyPlan() {
        val prefs = app.getSharedPreferences("lulu_self_todo_v2",
            android.content.Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("tasks", "this is not json").commit())
        val result = SelfTodoRoleRead.execute(app, JSONObject())
        assertFalse(result.getBoolean("success"))
        assertTrue(result.getString("summary").contains("不能假定清单为空"))
        // Syntactically valid JSON can still conceal a malformed/partial task.
        // The character must not confidently announce an empty plan then.
        val malformed = """[{"title":"缺少ID和日期的待办"}]"""
        assertTrue(prefs.edit().putString("tasks", malformed).commit())
        val partiallyBroken = SelfTodoRoleRead.execute(app, JSONObject())
        assertFalse(partiallyBroken.getBoolean("success"))
        assertEquals(malformed, prefs.getString("tasks", ""))
    }

    @Test fun roleReadsDiaryByChosenDateAndKeepsAllSameDayPages() {
        val now = LocalDate.of(2026, 11, 19).atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant()
        val yesterday = LocalDate.of(2026, 11, 18)
        val legacyMillis = LocalDate.of(2026, 11, 17).atTime(9, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val original = JSONArray()
            .put(JSONObject().put("id", "diary-b").put("area", "所思")
                .put("entryDate", yesterday.toString()).put("title", "晚上的事")
                .put("body", "这是昨天第二篇完整日记").put("createdAt", 200L)
                .put("updated", now.toEpochMilli()))
            .put(JSONObject().put("id", "diary-a").put("area", "所思")
                .put("entryDate", yesterday.toString()).put("title", "早上的事")
                .put("body", "这是昨天第一篇完整日记").put("mood", "开心")
                .put("createdAt", 100L).put("updated", now.toEpochMilli()))
            .put(JSONObject().put("id", "legacy-journal").put("area", "日记")
                .put("title", "以前的记录").put("body", "老版日记也要能找回来")
                .put("updated", legacyMillis))
            .put(JSONObject().put("id", "private-value").put("area", "所重")
                .put("title", "想珍惜的事").put("body", "这是另一栏，不属于日记")
                .put("updated", now.toEpochMilli()))
            .toString()
        val prefs = app.getSharedPreferences("lulu_self_preface_v1", 0)
        assertTrue(prefs.edit().putString("entries", original).commit())

        val dates = SelfPrefaceRoleRead.execute(app,
            JSONObject().put("area", "thoughts").put("op", "dates"), now)
        assertTrue(dates.getBoolean("success"))
        assertEquals(yesterday.toString(), dates.getJSONArray("dates").getString(0))
        val selected = SelfPrefaceRoleRead.execute(app,
            JSONObject().put("area", "thoughts").put("date", "yesterday"), now)
        assertEquals(2, selected.getInt("count"))
        assertEquals("diary-a", selected.getJSONArray("entries").getJSONObject(0).getString("id"))
        assertEquals("开心", selected.getJSONArray("entries").getJSONObject(0).getString("mood"))
        assertEquals("这是昨天第二篇完整日记",
            selected.getJSONArray("entries").getJSONObject(1).getString("body"))
        assertFalse(selected.toString().contains("这是另一栏"))
        val old = SelfPrefaceRoleRead.execute(app,
            JSONObject().put("area", "diary").put("date", "2026-11-17"), now)
        assertEquals("老版日记也要能找回来",
            old.getJSONArray("entries").getJSONObject(0).getString("body"))
        val today = SelfPrefaceRoleRead.execute(app,
            JSONObject().put("area", "所思").put("date", "today"), now)
        assertEquals(0, today.getInt("count"))
        assertEquals(original, prefs.getString("entries", null))
    }

    @Test fun readAllValuesAndWishesIncludesFulfilledAndTheirActualFolders() {
        val entries = JSONArray()
            .put(JSONObject().put("id", "value").put("area", "所重")
                .put("title", "价值判断").put("body", "愿意认真对待的事")
                .put("guide", "我为什么看重它").put("starred", true).put("updated", 100L))
            .put(JSONObject().put("id", "wish-completed").put("area", "所愿")
                .put("title", "完成的心愿").put("body", "已经实现，不能被过滤掉")
                .put("nextStep", "暂时没有").put("state", "fulfilled")
                .put("wishFolderId", "folder-1").put("updated", 100L))
            .put(JSONObject().put("id", "wish-active").put("area", "所愿")
                .put("title", "还在期待的心愿").put("body", "这个愿望仍在")
                .put("state", "active").put("updated", 150L))
        val prefs = app.getSharedPreferences("lulu_self_preface_v1", 0)
        assertTrue(prefs.edit()
            .putString("entries", entries.toString())
            .putString("wish_folders_v1", JSONArray().put(JSONObject()
                .put("id", "folder-1").put("name", "星空计划")).toString()).commit())
        val values = SelfPrefaceRoleRead.execute(app, JSONObject().put("area", "values"))
        assertEquals(1, values.getInt("count"))
        assertEquals("我为什么看重它", values.getJSONArray("entries")
            .getJSONObject(0).getString("guide"))
        assertFalse(values.toString().contains("完成的心愿"))
        val wishes = SelfPrefaceRoleRead.execute(app, JSONObject().put("area", "wishes"))
        assertEquals(2, wishes.getInt("count"))
        val finished = (0 until 2).map { wishes.getJSONArray("entries").getJSONObject(it) }
            .single { it.getString("id") == "wish-completed" }
        assertEquals("fulfilled", finished.getString("state"))
        assertEquals("星空计划", finished.getString("folderName"))
        assertEquals("已经实现，不能被过滤掉", finished.getString("body"))
        assertEquals(entries.toString(), prefs.getString("entries", null))
    }

    @Test fun unifiedPrefaceTodoReadsActualTodayCheckboxesAndRouterEvidence() = runBlocking {
        val today = LocalDate.now()
        val checkedAt = today.atTime(9, 0).atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val tasks = listOf(
            TodoTask("pending", "今天未完成的事项", "原始备注", today,
                null, TodoRepeat.ONCE, 100L),
            TodoTask("done", "今天已经做完的事项", "已勾选", today,
                null, TodoRepeat.ONCE, 100L,
                listOf(TodoCompletion(today, checkedAt))),
        )
        assertTrue(SelfTodoStore.save(app, tasks))
        val reading = SelfPrefaceRoleRead.execute(app, JSONObject().put("area", "todo"))
        assertTrue(reading.toString(), reading.getBoolean("success"))
        assertEquals("所行", reading.getString("area"))
        assertEquals(1, reading.getJSONArray("pending").length())
        assertEquals(1, reading.getJSONArray("completedToday").length())
        assertTrue(reading.toString().contains("今天未完成的事项"))
        assertTrue(reading.toString().contains("今天已经做完的事项"))
        val capability = requireNotNull(CapabilityRegistry.find("read_self_preface"))
        assertTrue(capability.retrySafe)
        assertFalse(CapabilityRegistry.allows(app, "resident:no-owner-notes", capability, false))
        val role = MigratedDomainStores.characters.create("好奇自序的角色").characterId
        val args = JSONObject().put("area", "todo")
        val execution = JSONObject(ToolRouter.execute(app, role, "read_self_preface",
            args, requestId = "preface-read-" + UUID.randomUUID()))
        assertTrue(execution.toString(), execution.getBoolean("success"))
        val receipt = SearchReceiptFeedback.from("read_self_preface", args, execution)
        assertNotNull(receipt)
        assertTrue(receipt!!.details.contains("今天已经做完的事项"))
        assertEquals(tasks, SelfTodoStore.read(app))
    }

    @Test fun corruptPrefaceAndBadDatesNeverPretendUserHasNoDiary() {
        val prefs = app.getSharedPreferences("lulu_self_preface_v1", 0)
        val invalid = "partly broken private notebook"
        assertTrue(prefs.edit().putString("entries", invalid).commit())
        val failed = SelfPrefaceRoleRead.execute(app, JSONObject().put("area", "wishes"))
        assertFalse(failed.getBoolean("success"))
        assertTrue(failed.getString("summary").contains("无法可靠读取"))
        assertEquals(invalid, prefs.getString("entries", null))
        assertTrue(prefs.edit().putString("entries", "[]").commit())
        val badDate = SelfPrefaceRoleRead.execute(app,
            JSONObject().put("area", "thoughts").put("date", "yesterday-but-not-a-date"))
        assertFalse(badDate.getBoolean("success"))
        assertTrue(badDate.getString("summary").contains("YYYY-MM-DD"))
    }

}
