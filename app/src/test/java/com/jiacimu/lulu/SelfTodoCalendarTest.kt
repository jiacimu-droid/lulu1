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
    fun scheduledAppointmentTodayBeatsEverydayAndUpcomingTasks() {
        val today = monday.plusDays(1)
        val routine = item("routine", repeat = TodoRepeat.DAILY, created = 1)
        val special = item("appointment", date = today, at = 14 * 60, created = 9999)
        val future = item("future", date = today.plusDays(1), created = 1)
        val sorted = SelfTodoCalendar.allPending(listOf(future, routine, special), today)
        assertEquals(listOf("appointment", "routine", "future"), sorted.map { it.task.id })
        assertEquals(TodoSection.TODAY_SPECIAL, SelfTodoCalendar.section(sorted[0], today))
    }

    @Test
    fun missedOneOffIsLastAndReschedulingMovesItBackToFuture() {
        val past = item("past", date = monday.minusDays(1))
        val routine = item("routine", repeat = TodoRepeat.DAILY)
        val future = item("future", date = monday.plusDays(5))
        val sorted = SelfTodoCalendar.allPending(listOf(past, future, routine), monday)
        assertEquals(listOf("routine", "future", "past"), sorted.map { it.task.id })
        assertEquals(TodoSection.OVERDUE, SelfTodoCalendar.section(sorted.last(), monday))
        val rescheduled = past.copy(firstDate = monday.plusDays(2))
        assertEquals(TodoSection.UPCOMING, SelfTodoCalendar.section(
            SelfTodoCalendar.pending(rescheduled, monday).single(), monday))
    }

    @Test
    fun undatedOnceRemainsOrdinaryWithoutAccumulatedOverdue() {
        val ordinary = item("ordinary", date = monday.minusWeeks(4)).copy(dateSpecified = false)
        val later = monday.plusDays(4)
        assertEquals(later, SelfTodoCalendar.pending(ordinary, later).single().date)
        assertEquals(TodoSection.REGULAR, SelfTodoCalendar.section(
            SelfTodoCalendar.pending(ordinary, later).single(), later))
        assertEquals(later.plusDays(1),
            SelfTodoCalendar.pending(ordinary, later.plusDays(1)).single().date)
    }

    @Test
    fun weeklyMissNeverAccumulatesAcrossWeeks() {
        val weekly = item("weekly", repeat = TodoRepeat.WEEKLY)
        val missedDay = monday.plusDays(1)
        assertEquals(TodoSection.OVERDUE, SelfTodoCalendar.section(
            SelfTodoCalendar.pending(weekly, missedDay).single(), missedDay))
        assertEquals(monday.plusWeeks(1),
            SelfTodoCalendar.pending(weekly, monday.plusWeeks(1)).single().date)
        assertEquals(1, SelfTodoCalendar.pending(weekly, monday.plusWeeks(1)).size)
    }

    @Test
    fun completedOneTimeTaskDoesNotReappearAfterDateChanges() {
        val done = SelfTodoCalendar.complete(item("exam"), monday, 150L)
        assertTrue(SelfTodoCalendar.pending(done.copy(firstDate = monday.plusDays(5)),
            monday.plusDays(5)).isEmpty())
    }
}
