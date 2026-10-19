package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SelfTodoCalendarTest {
    private val monday = LocalDate.of(2026, 10, 19)

    private fun item(
        id: String,
        date: LocalDate = monday,
        at: Int? = null,
        repeat: TodoRepeat = TodoRepeat.ONCE,
        created: Long = 100L,
    ) = TodoTask(
        id = id, title = id, note = "",
        firstDate = date, minutes = at, repeat = repeat, createdAt = created,
    )

    @Test
    fun oneTimeUndatedTaskRemainsVisibleUntilCompleted() {
        val task = item("learn", at = null)
        assertEquals(monday, SelfTodoCalendar.pending(task, monday).single().date)
        assertEquals(monday, SelfTodoCalendar.pending(task, monday.plusWeeks(1)).single().date)
        val done = SelfTodoCalendar.complete(task, monday, 1234L)
        assertTrue(SelfTodoCalendar.pending(done, monday.plusDays(1)).isEmpty())
        assertEquals(1, done.completions.size)
    }

    @Test
    fun dailyTaskDisappearsTodayAndReturnsTomorrow() {
        val task = item("water", repeat = TodoRepeat.DAILY)
        val done = SelfTodoCalendar.complete(task, monday, 1200L)
        assertTrue(SelfTodoCalendar.pending(done, monday).isEmpty())
        assertEquals(monday.plusDays(1),
            SelfTodoCalendar.pending(done, monday.plusDays(1)).single().date)
    }

    @Test
    fun weeklyTaskRepeatsOnlyInNextWeeklyCycle() {
        val task = item("clean", repeat = TodoRepeat.WEEKLY)
        val done = SelfTodoCalendar.complete(task, monday, 1200L)
        assertTrue(SelfTodoCalendar.pending(done, monday.plusDays(3)).isEmpty())
        assertEquals(monday.plusWeeks(1),
            SelfTodoCalendar.pending(done, monday.plusWeeks(1)).single().date)
    }

    @Test
    fun upcomingOneOffDeadlinesStayInPendingList() {
        val nextMonday = monday.plusWeeks(1)
        val soon = item("today")
        val later = item("deadline", date = nextMonday)
        assertEquals(listOf("today", "deadline"),
            SelfTodoCalendar.allPending(listOf(later, soon), monday).map { it.task.id })
    }

    @Test
    fun timeHasPriorityAndUntimedTasksKeepInsertionOrder() {
        val untimedOne = item("first", created = 10)
        val untimedTwo = item("second", created = 20)
        val atNoon = item("noon", at = 12 * 60, created = 40)
        val atTwo = item("two", at = 14 * 60, created = 30)
        val sorted = SelfTodoCalendar.allPending(
            listOf(untimedTwo, atTwo, untimedOne, atNoon), monday)
        assertEquals(listOf("noon", "two", "first", "second"),
            sorted.map { it.task.id })
    }

    @Test
    fun undoRestoresAnOldRecurringOccurrenceEvenIfTodayIsDone() {
        val task = item("water", repeat = TodoRepeat.DAILY)
        val yesterdayDone = SelfTodoCalendar.complete(task, monday, 100L)
        val today = monday.plusDays(1)
        val bothDone = SelfTodoCalendar.complete(yesterdayDone, today, 200L)
        assertTrue(SelfTodoCalendar.pending(bothDone, today).isEmpty())
        val restored = SelfTodoCalendar.undo(bothDone, monday, today)
        assertEquals(listOf(monday), SelfTodoCalendar.pending(restored, today).map { it.date })
        assertEquals(listOf(today), restored.completions.map { it.date })
        val doneAgain = SelfTodoCalendar.complete(restored, monday, 300L)
        assertTrue(SelfTodoCalendar.pending(doneAgain, today).isEmpty())
    }

    @Test
    fun restoringOneOffMovesItBackToPendingAndOutOfHistory() {
        val task = item("learn")
        val done = SelfTodoCalendar.complete(task, monday, 150L)
        val restored = SelfTodoCalendar.undo(done, monday, monday)
        assertFalse(restored.completions.isNotEmpty())
        assertEquals(monday, SelfTodoCalendar.pending(restored, monday).single().date)
    }

    @Test
    fun completedOneTimeTaskDoesNotReappearAfterDateChanges() {
        val done = SelfTodoCalendar.complete(item("exam"), monday, 150L)
        assertTrue(SelfTodoCalendar.pending(done.copy(firstDate = monday.plusDays(5)),
            monday.plusDays(5)).isEmpty())
    }
}
