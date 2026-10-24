package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CharacterAttentionWorkingSetTest {
    private data class Cue(val id: String, val topic: String)
    private val at = Instant.parse("2026-10-24T12:00:00Z")
    private fun select(items: List<Cue>, now: Instant = at, limit: Int = 3) =
        CharacterAttentionWorkingSet.select(items, limit, now, 300, { it.id }, { it.topic })

    @Test fun newerSameThemeCannotPermanentlyHideAnOlderIndependentInterest() {
        val older = (0..5).map { Cue("old-$it", "interest-$it") }
        val recent = (0..6).map { Cue("new-$it", "recent-chat") }
        val all = older + recent
        val seen = (0..all.size).flatMap { select(all, at.plusSeconds(it * 300L)) }.map { it.id }.toSet()
        assertTrue(seen.containsAll(older.map { it.id }))
        assertTrue((0..all.size).all { offset ->
            val picked = select(all, at.plusSeconds(offset * 300L))
            picked.size == 3 && picked.any { it.id == "new-6" } && picked.map { it.topic }.distinct().size == 3
        })
        assertEquals(all, older + recent)
    }

    @Test fun attentionIsStableWithinTheExistingPulseAndNeverCreatesExtraItems() {
        val all = (0..8).map { Cue("cue-$it", "topic-$it") }
        assertEquals(select(all), select(all, at.plusSeconds(1)))
        assertTrue(select(emptyList()).isEmpty())
        assertTrue(select(all, limit = 0).isEmpty())
        assertEquals(all.take(2), select(all.take(2)))
        assertEquals(3, select(all).map { it.id }.distinct().size)
    }
}
