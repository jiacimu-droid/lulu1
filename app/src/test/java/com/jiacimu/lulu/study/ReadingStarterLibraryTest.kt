package com.jiacimu.lulu.study

import org.junit.Assert.*
import org.junit.Test

class ReadingStarterLibraryTest {
    @Test fun originalBooksHaveActualFiniteTextAndStableIDsForProgress() {
        val books = ReadingStarterLibrary.books()
        assertTrue(books.size >= 2)
        assertEquals(books.size, books.map { it.id }.distinct().size)
        books.forEach { book ->
            assertTrue(book.id.startsWith("starter:"))
            assertTrue(book.source.contains("原创"))
            assertTrue(book.content.length > 600)
            assertFalse(book.content.contains("未生成"))
        }
    }
}
