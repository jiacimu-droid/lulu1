package com.jiacimu.lulu

import android.content.Context
import java.time.LocalDate
import java.time.ZonedDateTime
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
        listOf("lulu_desktop_layout", "lulu_desktop_widgets_v1", "lulu_desktop_widget_role_notes_v1",
            "lulu_self_todo_v2", "lulu_self_preface_v1", "lulu_desktop_widget_daily_role_v1")
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @After fun cleanup() {
        listOf("lulu_desktop_layout", "lulu_desktop_widgets_v1", "lulu_desktop_widget_role_notes_v1",
            "lulu_self_todo_v2", "lulu_self_preface_v1", "lulu_desktop_widget_daily_role_v1")
            .forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun eightRowUpgradePersistsAppSlotsAndWidgetSettingsTogether() {
        val prefs = app.getSharedPreferences("lulu_desktop_widgets_v1", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putBoolean("home_cards_seeded_v1", true).putString("cards", """
            [{"id":"existing","kind":"note","page":0,"col":2,"row":6,"skin":1,
              "title":"我的便签","imageToken":"old-photo","characterId":"role-A"}]
        """.trimIndent()).commit())
        val oldSlots = MutableList<String?>(32) { null }.also { it[31] = "settings" }

        assertTrue(app.getSharedPreferences("lulu_desktop_layout", Context.MODE_PRIVATE).edit()
            .putString("slots_v3", oldSlots.joinToString("|") { it.orEmpty() }).commit())
        assertEquals(6, LuluDesktopWidgetStore.load(app).single().row)

        val slots = LuluDesktopWidgetStore.loadOrMigrateGrid(app, listOf("settings"))
        val migrated = LuluDesktopWidgetStore.load(app).single()
        assertEquals(5, migrated.row)
        assertEquals("existing", migrated.id)
        assertEquals(1, migrated.skin)
        assertEquals("old-photo", migrated.imageToken)
        assertEquals("role-A", migrated.characterId)
        assertTrue(luluWidgetFits(migrated, listOf(migrated), slots))
        assertEquals(listOf("settings"), slots.filterNotNull())
        assertEquals(slots, LuluDesktopWidgetStore.loadOrMigrateGrid(app, listOf("settings")))
    }

    @Test fun sixRowGridPersistsItsSecondPageAtTheNewStride() {
        val prefs = app.getSharedPreferences("lulu_desktop_widgets_v1", Context.MODE_PRIVATE)
        val old = MutableList(48) { "" }.apply {
            this[23] = "settings"
            this[24] = "chat"
        }
        assertTrue(prefs.edit().putBoolean("home_cards_seeded_v1", true)
            .putString("slots_v4", old.joinToString("|"))
            .putString("cards", """[{"id":"photo","kind":"photo","page":1,"col":2,"row":2,"imageToken":"album"}]""")
            .commit())

        val first = LuluDesktopWidgetStore.loadOrMigrateGrid(app, listOf("settings", "chat"))
        assertEquals("settings", first[desktopSlotIndex(0, 5, 3)])
        assertEquals("chat", first[desktopSlotIndex(1, 0, 0)])
        assertEquals(1, LuluDesktopWidgetStore.load(app).single().page)
        assertEquals("album", LuluDesktopWidgetStore.load(app).single().imageToken)
        assertTrue(prefs.contains("slots_v5"))
        assertEquals(first, LuluDesktopWidgetStore.loadOrMigrateGrid(app, listOf("settings", "chat")))
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

    @Test fun dailyMidnightScheduleUsesFirstInstantOfEveryLocalDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        val before = ZonedDateTime.of(2026, 11, 18, 23, 59, 59, 0, zone)
        val midnight = before.plusSeconds(1)
        val today = LocalDate.of(2026, 11, 18)
        val tomorrow = today.plusDays(1)
        // New day begins immediately at 00:00, not 08:00 the next morning.
        assertEquals(tomorrow, LuluWidgetDailyRole.plannedDate(midnight, true))
        assertEquals(tomorrow.plusDays(1), LuluWidgetDailyRole.plannedDate(midnight, false))
        assertEquals(tomorrow, LuluWidgetDailyRole.plannedDate(before, false))
        assertEquals(midnight.toInstant(), LuluWidgetDailyRole.scheduledAt(tomorrow, zone))
        assertEquals(1L, java.time.Duration.between(before.toInstant(),
            LuluWidgetDailyRole.scheduledAt(tomorrow, zone)).seconds)
        assertEquals(today, LuluWidgetDailyRole.plannedDate(before, true))
        assertEquals(today.atStartOfDay(zone).toInstant(),
            LuluWidgetDailyRole.scheduledAt(today, zone))
        // The device zone, not a hard-coded UTC midnight, determines the day.
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertEquals(tomorrow.atStartOfDay(tokyo).toInstant(),
            LuluWidgetDailyRole.scheduledAt(tomorrow, tokyo))
    }

    @Test fun sameRoleSameSignHasOneDateScopedCachedReadingForMultipleCards() {
        val date = LocalDate.now()
        val first = LuluDesktopWidget("copy-A", LuluWidgetKind.HOROSCOPE, 1, 0, 0,
            characterId = "role-A", sign = 6)
        val second = first.copy(id = "copy-B", page = 2)
        val fingerprint = date.toString() + "|6|role-A"
        val raw = JSONObject().put("fingerprint", fingerprint)
            .put("message", "本人真实写过的解读").put("notify", false).put("skin", -1)
        app.getSharedPreferences("lulu_desktop_widget_daily_role_v1", Context.MODE_PRIVATE)
            .edit().putString("reading_" + fingerprint, raw.toString()).commit()
        assertEquals("本人真实写过的解读", LuluWidgetDailyRole.read(app, first, date)?.message)
        assertEquals("本人真实写过的解读", LuluWidgetDailyRole.read(app, second, date)?.message)
        assertNull(LuluWidgetDailyRole.read(app, second, date.plusDays(1)))
        assertNull(LuluWidgetDailyRole.read(app, second.copy(sign = 7), date))
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
