package com.jiacimu.lulu.data

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RecentSemanticPromptTest {
    private fun gameEvent(id: String, second: Long, text: String) = SharedTimelineEvent(
        id = id, characterId = "my-role", channel = "共同游戏《迷雾摸金》",
        speaker = "游戏实况", content = text,
        occurredAt = Instant.parse("2026-11-17T00:00:00Z").plusSeconds(second),
        source = "game-relic", evidenceKind = EventEvidenceKind.ToolResult)

    @Test fun omittedMiddleCombatStepRemainsAvailableToExactSourceRecall() {
        val events = (0 until 75).map { i ->
            gameEvent("relic:run:my-role:$i", i.toLong(), if (i == 38)
                "主人要求我在宝库先拉开左侧机关，机关已触发"
                else "普通走位回执 $i")
        }
        val middle = events[38]
        val context = UnifiedMemoryContext(
            characterId = "my-role", recentEvents = events,
            recentTurns = RecentSemanticTimeline.select(events, 35),
            sourceEvents = listOf(middle), retrievalFocus = "左侧机关")
        val budget = PromptContextBudget(
            recallLimit = 40, memoryCharacters = 10_000, evidenceCharacters = 5_500,
            recentCharacters = 5_900, worldDetails = true, richInnerLife = true,
            lexiconCharacters = 3_300, precise = true, workingSetCharacters = 28_000)
        val working = MemoryWorkingSet.assemble(context, emptyList(), emptyList(), budget, "")
        val recent = working.sections[MemoryWorkingSet.RECENT].orEmpty()
        val recalled = working.sections[MemoryWorkingSet.EVIDENCE].orEmpty()
        assertEquals(1, working.selected.count { it.startsWith("recent-turn:game:relic_escape:run") })
        assertTrue(recalled.contains("左侧机关"))
        assertTrue(recalled.contains("eventId=${middle.id}@${middle.revision}"))
        assertFalse(working.omitted.containsKey("event:${middle.id}"))
        assertTrue(recent.contains("同一经历"))
        assertTrue(working.usedCharacters <= budget.workingSetCharacters)
    }

    @Test fun filteredOrDeletedSourcesAreNotReintroducedFromAnOldEpisode() {
        val old = gameEvent("relic:run:my-role:old", 1, "已经失效的原话")
        val live = gameEvent("relic:run:my-role:live", 2, "当前仍有效的真实回执")
        val budget = PromptContextBudget(40, 10_000, 5_500, 5_900, true, true,
            3_300, true, workingSetCharacters = 28_000)
        val current = UnifiedMemoryContext(characterId = "my-role",
            recentEvents = listOf(live),
            recentTurns = RecentSemanticTimeline.select(listOf(live), 35))
        val selection = MemoryWorkingSet.assemble(current, emptyList(), emptyList(), budget, "")
        assertTrue(selection.sections.values.joinToString().contains("当前仍有效"))
        assertFalse(selection.sections.values.joinToString().contains("已经失效"))
        // A stale cross-character projection must not bypass ownership either.
        val wrong = current.copy(recentTurns = RecentSemanticTimeline.select(
            listOf(old.copy(characterId = "other-role")), 35))
        assertFalse(MemoryWorkingSet.assemble(wrong, emptyList(), emptyList(), budget, "")
            .sections.values.joinToString().contains("已经失效"))
    }
}
