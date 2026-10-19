package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SelfThoughtNotebookTest {
    private val oct18 = LocalDate.of(2026, 10, 18)
    private val oct19 = LocalDate.of(2026, 10, 19)
    private val oct20 = LocalDate.of(2026, 10, 20)

    private fun page(id: String, date: LocalDate, created: Long, updated: Long = created) =
        SelfThoughtPage(id, date, id, "内容", created, updated)

    @Test fun multiplePagesForOneDayKeepTheirOriginalOrderAfterEdit() {
        val pages = listOf(page("second", oct19, 20, 700), page("first", oct19, 10, 900),
            page("previousDay", oct18, 5))
        assertEquals(listOf("first", "second"), thoughtPagesOn(pages, oct19).map { it.id })
    }

    @Test fun pageNavigationSkipsUnwrittenDates() {
        val pages = listOf(page("18", oct18, 1), page("20", oct20, 2))
        assertEquals(oct20, nextThoughtDate(pages, oct18, true))
        assertEquals(oct18, nextThoughtDate(pages, oct20, false))
        assertNull(nextThoughtDate(pages, oct20, true))
    }

    @Test fun emptyDateHasNoInventedPages() {
        assertEquals(emptyList<SelfThoughtPage>(), thoughtPagesOn(listOf(page("old", oct18, 1)), oct19))
    }
}
