package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class GrowthReflectionInputTest {
    private val start = Instant.parse("2026-11-01T08:00:00Z")
    private fun event(index: Int, kind: EventEvidenceKind, dayOffset: Long = 0L,
        source: String = "message", channel: String = "私聊"): SharedTimelineEvent =
        SharedTimelineEvent("growth-source-$index", "growth-role",
            channel, "实际来源", "真实经历片段$index", start.plusSeconds(dayOffset),
            source = source, evidenceKind = kind)

    @Test fun fastPrivateBubblesCannotTriggerAnIndependentPaidGrowthReview() {
        val bursts = (0 until 12).map { event(it, EventEvidenceKind.UserStatement,
            dayOffset = it * 10L) }
        assertFalse(GrowthReflectionInput.shouldConsolidate(bursts))
        assertFalse(GrowthReflectionInput.shouldConsolidate(
            listOf(event(1, EventEvidenceKind.UserStatement))))
        assertFalse(GrowthReflectionInput.shouldConsolidate(
            (0 until 20).map { event(it, EventEvidenceKind.Observation,
                source = "online-awareness") }))
    }

    @Test fun independentExperiencesAcrossDaysOrLongRealActionsCanConsolidate() {
        val multiDay = (0 until 7).map {
            event(it, EventEvidenceKind.UserStatement, it * 24L * 3600)
        }
        assertTrue(GrowthReflectionInput.shouldConsolidate(multiDay))
        val ongoing = (0 until 7).map {
            event(it, EventEvidenceKind.ToolResult, it * 10L)
        }
        assertTrue(GrowthReflectionInput.shouldConsolidate(ongoing))
    }

    @Test fun growthInputIsBalancedAndNeverResendsTheEntireChronicle() {
        val owner = (0 until 50).map {
            event(it, EventEvidenceKind.UserStatement, it * 86_400L)
        }
        val tools = (50 until 80).map {
            event(it, EventEvidenceKind.ToolResult, it * 86_400L,
                source = "tool-router", channel = "工具回执")
        }
        val observation = (80 until 100).map {
            event(it, EventEvidenceKind.Observation, it * 86_400L,
                source = "digital-world", channel = "数字世界")
        }
        val source = owner + tools + observation
        val selected = GrowthReflectionInput.select(source)
        assertTrue(selected.isNotEmpty())
        assertTrue(selected.size < source.size / 2)
        assertEquals(selected.size, selected.map { it.id }.distinct().size)
        assertTrue(selected.any { it.evidenceKind == EventEvidenceKind.UserStatement })
        assertTrue(selected.any { it.evidenceKind == EventEvidenceKind.ToolResult })
        assertTrue(selected.any { it.evidenceKind == EventEvidenceKind.Observation })
        assertTrue(GrowthReflectionInput.evidenceText(selected).length < 20_000)
    }

    @Test fun personaIsNeverChangedWhenSelectingACompactWorkingCopy() {
        val persona = "不变的核心人设；" + "本人长期稳定价值观".repeat(600) +
            "末尾真实人格约束"
        val compact = GrowthReflectionInput.personaBrief(persona)
        assertTrue(compact.length < persona.length)
        assertTrue(compact.length < 6_500)
        assertTrue(compact.startsWith("不变的核心人设"))
        assertTrue(compact.endsWith("末尾真实人格约束"))
        val short = "原本人设"
        assertEquals(short, GrowthReflectionInput.personaBrief(short))
    }
}
