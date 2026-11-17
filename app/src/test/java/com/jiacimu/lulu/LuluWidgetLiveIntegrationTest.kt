package com.jiacimu.lulu

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LuluWidgetLiveIntegrationTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        listOf("lulu_desktop_widgets_v1", "lulu_desktop_widget_role_notes_v1",
            "lulu_self_todo_v2", "lulu_self_preface_v1", "lulu_desktop_widget_daily_role_v1")
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @After fun cleanup() {
        listOf("lulu_desktop_widgets_v1", "lulu_desktop_widget_role_notes_v1",
            "lulu_self_todo_v2", "lulu_self_preface_v1", "lulu_desktop_widget_daily_role_v1")
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun checkboxUsesOneOwnerTodoRecordAndRejectsStaleOrDuplicateClicks() {
        val today = LocalDate.now()
        val at = today.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val once = TodoTask("real-id", "完成真实待办", "", today, null,
            TodoRepeat.ONCE, at)
        val future = TodoTask("future-id", "未来才执行", "", today.plusDays(3),
            null, TodoRepeat.ONCE, at)
        assertTrue(SelfTodoStore.save(app, listOf(once, future)))
        assertTrue(SelfTodoStore.completeFromWidget(app, once.id, today,
            System.currentTimeMillis()))
        assertFalse(SelfTodoStore.completeFromWidget(app, once.id, today,
            System.currentTimeMillis()))
        assertFalse(SelfTodoStore.completeFromWidget(app, future.id, today.plusDays(3),
            System.currentTimeMillis()))
        assertFalse(SelfTodoStore.completeFromWidget(app, "missing", today,
            System.currentTimeMillis()))
        val reloaded = SelfTodoStore.read(app)
        assertEquals(1, reloaded.first { it.id == once.id }.completions.size)
        assertEquals(today, reloaded.first { it.id == once.id }.completions.single().date)
        assertEquals(0, reloaded.first { it.id == future.id }.completions.size)
        val actual = SelfTodoRoleRead.execute(app, JSONObject())
        assertEquals(1, actual.getInt("checkedTodayCount"))
        assertEquals("real-id", actual.getJSONArray("completedToday")
            .getJSONObject(0).getString("taskId"))
    }

    @Test fun rolesCanOnlyWriteAndDecorateTheirOwnEnabledWidget() {
        val note = LuluDesktopWidget(
            id = "test-note", kind = LuluWidgetKind.NOTE,
            page = 1, col = 0, row = 0, characterId = "role-A",
        )
        val stars = LuluDesktopWidget(
            id = "test-stars", kind = LuluWidgetKind.HOROSCOPE,
            page = 1, col = 0, row = 2, characterId = "role-A",
        )
        assertTrue(LuluDesktopWidgetStore.save(app, listOf(note, stars)))
        val noteArgs = JSONObject().put("widgetId", note.id).put("text", "今天想和你一起去看花")
        assertFalse(LuluWidgetRoleActions.execute(app, "role-B",
            "write_desktop_widget_note", noteArgs).getBoolean("success"))
        assertNull(LuluWidgetRoleActions.readNote(app, note))
        assertTrue(LuluWidgetRoleActions.execute(app, "role-A",
            "write_desktop_widget_note", noteArgs).getBoolean("success"))
        assertEquals("今天想和你一起去看花",
            LuluWidgetRoleActions.readNote(app, note)?.content)
        val skinArgs = JSONObject().put("widgetId", stars.id).put("skin", 2)
        assertFalse(LuluWidgetRoleActions.execute(app, "role-A",
            "decorate_desktop_widget", skinArgs).getBoolean("success"))
        assertTrue(LuluDesktopWidgetStore.save(app,
            listOf(note, stars.copy(allowRoleStyle = true))))
        assertFalse(LuluWidgetRoleActions.execute(app, "role-B",
            "decorate_desktop_widget", skinArgs).getBoolean("success"))
        assertTrue(LuluWidgetRoleActions.execute(app, "role-A",
            "decorate_desktop_widget", skinArgs).getBoolean("success"))
        assertEquals(2, LuluDesktopWidgetStore.load(app).first { it.id == stars.id }.skin)
        val customPalette = JSONObject().put("widgetId", stars.id)
            .put("backgroundColor", "#203044").put("textColor", "#FFFFFF")
            .put("cornerDp", 12)
        assertTrue(LuluWidgetRoleActions.execute(app, "role-A",
            "decorate_desktop_widget", customPalette).getBoolean("success"))
        val styled = LuluDesktopWidgetStore.load(app).first { it.id == stars.id }
        assertEquals("#203044", styled.roleBackground)
        assertEquals("#FFFFFF", styled.roleInk)
        assertEquals(12, styled.roleCornerDp)
        assertFalse(LuluWidgetRoleActions.execute(app, "role-A",
            "decorate_desktop_widget", customPalette.put("backgroundColor", "https://unsafe.example"))
                .getBoolean("success"))
        assertEquals("#203044", LuluDesktopWidgetStore.load(app)
            .first { it.id == stars.id }.roleBackground)

    }

    @Test fun interpretationParsingKeepsActualModelWordsAndRejectsFakeFields() {
        val card = LuluDesktopWidget("daily", LuluWidgetKind.HOROSCOPE, 1, 0, 0,
            characterId = "role-A")
        val day = LocalDate.of(2026, 11, 18)
        val genuine = LuluWidgetDailyRole.parse(day, card,
            """{"message":"今天试试慢一点","notify":true,"skin":-1}""")
        assertEquals("今天试试慢一点", genuine?.message)
        assertTrue(genuine!!.wantsNotify)
        assertNull(LuluWidgetDailyRole.parse(day, card,
            """{"message":"假通知","notify":"true","skin":1}"""))
        assertNull(LuluWidgetDailyRole.parse(day, card,
            """{"message":"非法主题","notify":false,"skin":100}"""))
    }
}
