package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.core.MemoryTier
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterAssociativeRecallTest {
    private val now = Instant.parse("2026-10-23T04:00:00Z")
    private fun memory(
        id: String,
        kind: MemoryKind = MemoryKind.Fact,
        tier: MemoryTier = MemoryTier.Stable,
        role: String = "role",
        proactive: Boolean = true,
        at: Instant = now.minusSeconds(3600),
    ) = MemoryEntry(id, role, id + " 喜欢披萨", kind, "test", at, at,
        7, false, proactive, tier)

    @Test fun classifiesFactsExperiencesAndPastFeelingsWithoutRewritingThem() {
        assertEquals(CharacterAssociativeRecall.MemoryLane.CORE,
            CharacterAssociativeRecall.memoryLane(memory("core", tier = MemoryTier.Core)))
        assertEquals(CharacterAssociativeRecall.MemoryLane.STABLE,
            CharacterAssociativeRecall.memoryLane(memory("preference")))
        assertEquals(CharacterAssociativeRecall.MemoryLane.EPISODE,
            CharacterAssociativeRecall.memoryLane(memory("meeting", kind = MemoryKind.Timeline)))
        assertEquals(CharacterAssociativeRecall.MemoryLane.PAST_FEELING,
            CharacterAssociativeRecall.memoryLane(memory("old mood", kind = MemoryKind.Emotion)))
    }

    @Test fun unrelatedRolesFutureEventsAndOptedOutMemoriesCannotSeedAutonomy() {
        val records = listOf(memory("mine"), memory("someone else", role = "other"),
            memory("tomorrow", at = now.plusSeconds(3600)),
            memory("opted out", proactive = false))
        val chosen = CharacterAssociativeRecall.selectMemories("role", records, "披萨", now)
        assertEquals(listOf("mine"), chosen.map { it.id })
    }

    @Test fun selectsDifferentMemoryKindsWithoutCreatingGoalsOrActions() {
        val records = listOf(memory("facts"), memory("scene", kind = MemoryKind.Timeline),
            memory("feelings", kind = MemoryKind.Emotion), memory("facts2"))
        val chosen = CharacterAssociativeRecall.selectMemories("role", records, "", now)
        assertEquals(4, chosen.size)
        assertTrue(chosen.any { it.id == "facts" })
        assertTrue(chosen.any { it.id == "scene" })
        assertTrue(chosen.any { it.id == "feelings" })
        assertFalse(chosen.any { it.characterId != "role" })
    }
}
