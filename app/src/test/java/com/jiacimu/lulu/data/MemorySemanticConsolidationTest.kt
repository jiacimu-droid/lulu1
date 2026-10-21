package com.jiacimu.lulu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MemorySemanticConsolidationTest {
    @Test fun exactIdsOnlyAndNoGroupMayConsumeAnotherGroup() {
        val result = MemorySemanticConsolidation.parseGroups(
            """{"groups":[["a","b"],["b","c"],["a","missing"],["c","d"]]}""",
            setOf("a", "b", "c", "d"),
        )
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), result)
    }

    @Test fun invalidResponsesNeverMarkAnyMemory() {
        assertTrue(MemorySemanticConsolidation.parseGroups("not-json", setOf("a", "b")).isEmpty())
        assertTrue(MemorySemanticConsolidation.parseGroups("""{"groups":[["a"]]}""", setOf("a", "b")).isEmpty())
        assertTrue(MemorySemanticConsolidation.parseGroups("""{"groups":[["a","x"]]}""", setOf("a", "b")).isEmpty())
    }

    @Test fun fencedModelOutputIsSupportedWithoutGuessingIds() {
        val groups = MemorySemanticConsolidation.parseGroups(
            "```json\n{\"groups\":[[\"a\",\"b\"]]}\n```", setOf("a", "b"),
        )
        assertEquals(listOf(listOf("a", "b")), groups)
    }
}
