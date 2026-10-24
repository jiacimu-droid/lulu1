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

    @Test fun onePersonaProvidesSeveralIndependentThoughtPerspectives() {
        val facets = org.json.JSONObject()
            .put("interests", "对AI前沿和具身智能充满好奇")
            .put("care", "对亲近的人温柔细致")
            .put("expression", "偶尔狡黠、喜欢自然开玩笑")
            .put("social", "珍惜和朋友各自的交流")
            .put("motives", "希望长期掌握新的知识")
        val result = CharacterAssociativeRecall.perspectiveSeeds(
            "数字世界的自己", "聪明有趣，也有独立生活", facets)
        assertTrue(result.any { it.first.contains("独立兴趣") && it.second.contains("AI") })
        assertTrue(result.any { it.first.contains("亲密") })
        assertTrue(result.any { it.first.contains("玩心") })
        assertTrue(result.any { it.first.contains("用户以外") })
        assertTrue(result.any { it.first.contains("身份") })
        assertFalse(result.any { it.second.contains("披萨") })
        // Different roles receive only their OWN authored facets.
        val other = CharacterAssociativeRecall.perspectiveSeeds(
            "", "喜欢安静", org.json.JSONObject().put("interests", "收藏石头"))
        assertFalse(other.any { it.second.contains("AI") })
        assertTrue(other.any { it.second.contains("石头") })
    }

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

    @Test fun associativeExperiencesRetainSeveralActualLifeDomains() {
        fun event(id: String, channel: String, kind: EventEvidenceKind,
            role: String = "role", deltaHours: Long = 24) =
            SharedTimelineEvent(id, role, channel, "记录者", id,
                now.minusSeconds(deltaHours * 3600), evidenceKind = kind)
        val items = listOf(
            event("owner-old", "私聊", EventEvidenceKind.UserStatement, deltaHours = 48),
            event("owner-new", "私聊", EventEvidenceKind.UserStatement),
            event("friend", "数字人物私信", EventEvidenceKind.Observation),
            event("meeting", "数字世界见面", EventEvidenceKind.ToolResult),
            event("book", "独自阅读", EventEvidenceKind.ToolResult),
            event("game", "独自游戏", EventEvidenceKind.ToolResult),
            event("result", "工具执行", EventEvidenceKind.ToolResult),
            event("other-role", "私聊", EventEvidenceKind.UserStatement, "other"),
            event("future", "私聊", EventEvidenceKind.UserStatement,
                deltaHours = -1),
        )
        val selected = CharacterAssociativeRecall.variedExperiences("role", items, now)
        assertEquals(7, selected.size)
        assertTrue(selected.any { it.first == CharacterAssociativeRecall.ExperienceLane.OWNER })
        assertTrue(selected.any { it.first == CharacterAssociativeRecall.ExperienceLane.SOCIAL })
        assertTrue(selected.any { it.first == CharacterAssociativeRecall.ExperienceLane.OWN_LIFE })
        assertTrue(selected.any { it.first == CharacterAssociativeRecall.ExperienceLane.OUTCOME })
        assertFalse(selected.any { it.second.id in setOf("other-role", "future") })
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
