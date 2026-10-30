package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WorldLifeNarrativeGuideTest {
    @Test fun readerLengthAndRoutineWordsNeverChooseSkipForTheModel() {
        assertNull(WorldLifeNarrativeGuide.skipReason("走进咖啡店，点了一杯咖啡，然后坐下。"))
        assertNull(WorldLifeNarrativeGuide.skipReason("""{"scene":"走进咖啡店，点了一杯咖啡，然后坐下。"}"""))
        assertEquals("缺少可展开内容", WorldLifeNarrativeGuide.skipReason(
            "```json\n{\"publication\":\"skip\",\"reason\":\"缺少可展开内容\"}\n```"))
    }

    @Test fun selectedExchangeKeepsTheLastReplyAndLongActionDetailInOrder() {
        val at = Instant.parse("2026-10-30T03:00:00Z")
        val opening = SharedTimelineEvent("first", "a", "数字世界", "a", "具体动作".repeat(300) + "开场问题", at)
        val answer = SharedTimelineEvent("last", "a", "数字世界", "b", "这才是对方真正的回答和这件事的结果", at.plusSeconds(20))
        val middle = (1..18).map { i -> SharedTimelineEvent("turn-$i", "a", "数字世界", "a", "中间的实际交流$i", at.plusSeconds(i.toLong())) }
        val facts = WorldLifeNarrativeGuide.sourceFacts(listOf(answer) + middle.reversed() + opening)
        assertTrue(facts.contains(opening.content))
        assertTrue(facts.endsWith(answer.content))
        assertTrue(facts.indexOf("证据=first") < facts.indexOf("证据=turn-1"))
        assertTrue(facts.indexOf("证据=turn-18") < facts.indexOf("证据=last"))
    }
}
