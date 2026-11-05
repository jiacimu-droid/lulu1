package com.jiacimu.lulu

import com.jiacimu.lulu.data.CapabilityRegistry
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.MemoryTestEnvironment
import com.jiacimu.lulu.data.ToolRouter
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
        assertEquals(3, todayView.getJSONArray("pending").length())
        assertTrue(todayView.getString("summary").contains("学习时长"))
        assertFalse(todayView.getJSONArray("pending").toString().contains("future"))
        assertFalse(todayView.getJSONArray("pending").toString().contains("finished_old"))
        val all = SelfTodoRoleRead.snapshot(tasks, today, now, JSONObject().put("scope", "all"))
        assertEquals(4, all.getJSONArray("pending").length())
        assertTrue(all.getJSONArray("pending").toString().contains("future"))
        assertEquals("pending_today", all.getJSONArray("pending").getJSONObject(0).getString("state"))
        assertEquals("overdue", all.getJSONArray("pending").getJSONObject(2).getString("state"))
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
}
