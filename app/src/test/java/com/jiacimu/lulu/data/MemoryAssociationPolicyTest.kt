package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class MemoryAssociationPolicyTest {
    private val now = Instant.parse("2026-10-12T12:00:00Z")
    private fun memory(id: String, content: String, source: String = "manual", subject: String = "", slot: String = "") =
        MemoryEntry(id, "role", content, MemoryKind.Fact, source, now, now, 5, false, true,
            subject = subject, slot = slot)

    @Test fun equallyRelevantRecentAndImportantEventsReceivePriorityWithoutErasingOlderOnes() {
        val old = memory("old", "共同经历").copy(occurredAt = now.minusSeconds(60 * 86_400))
        val recent = old.copy(id = "recent", occurredAt = now.minusSeconds(60))
        assertTrue(MemoryAssociationPolicy.priority(recent, 0.6, now = now) > MemoryAssociationPolicy.priority(old, 0.6, now = now))
        assertTrue(MemoryAssociationPolicy.priority(old.copy(strength = 10), 0.6, now = now) > MemoryAssociationPolicy.priority(old.copy(strength = 1), 0.6, now = now))
        assertTrue(MemoryAssociationPolicy.priority(old, 1.0, now = now) > MemoryAssociationPolicy.priority(recent.copy(strength = 10), 0.10, now = now))
    }

    @Test fun exactEvidenceAndSpecificSlotsExpandAtMostTwoHops() {
        val first = memory("a", "咖啡馆相遇", "timeline-events:arrival", "咖啡馆", "相遇")
        val second = memory("b", "她当时很疲惫", "timeline-events:arrival|reaction", "用户", "当时心情")
        val third = memory("c", "后来决定先安静陪她", "timeline-events:reaction|choice")
        val fourth = memory("d", "远处另一次经历", "timeline-events:choice|later")
        val hits = MemoryAssociationPolicy.expand(listOf(first, second, third, fourth), listOf(first))
        assertEquals(setOf("b", "c"), hits.map { it.memory.id }.toSet())
        assertEquals(2, hits.first { it.memory.id == "c" }.depth)
        assertTrue(hits.all { it.depth <= 2 })
    }

    @Test fun broadSubjectsAndLegacyBatchesDoNotCreateUnrelatedAssociations() {
        val seed = memory("a", "用户喜欢咖啡", "timeline-batch:1|2|3", "用户", "偏好")
        val unrelated = memory("b", "用户计划考试", "timeline-batch:1|2|3", "用户", "偏好")
        assertTrue(MemoryAssociationPolicy.expand(listOf(seed, unrelated), listOf(seed)).isEmpty())
        assertTrue(MemoryAssociationPolicy.localRelevant(listOf(unrelated), "咖啡", now = now).isEmpty())
    }

    @Test fun deletingABridgeRemovesItsAssociationsAndExpansionHonorsTheLimit() {
        val a = memory("a", "起因", "timeline-events:1")
        val b = memory("b", "经过", "timeline-events:1|2")
        val c = memory("c", "后续", "timeline-events:2")
        assertEquals(2, MemoryAssociationPolicy.expand(listOf(a, b, c), listOf(a)).size)
        assertTrue(MemoryAssociationPolicy.expand(listOf(a, c), listOf(a)).isEmpty())
        assertEquals(1, MemoryAssociationPolicy.expand(listOf(a, b, c), listOf(a), limit = 1).size)
    }

    @Test fun anActionCanFindItsEarlierMethodWithoutInjectingAllRecentLife() {
        val method = memory("method", "讲笑话时铺垫太长会让她失去兴趣")
        val unrelated = memory("unrelated", "今天洗了衣服").copy(strength = 10)
        val result = MemoryAssociationPolicy.localRelevant(listOf(method, unrelated), "讲笑话逗她开心", now = now)
        assertEquals(listOf("method"), result.map { it.id })
    }

    @Test fun manyRecordsWithTheSameBroadSlotCannotFloodTheGraph() {
        val records = (0..30).map { memory("$it", "日常记录$it", subject = "用户", slot = "饮食习惯") }
        assertTrue(MemoryAssociationPolicy.expand(records, listOf(records.first())).isEmpty())
    }
}
