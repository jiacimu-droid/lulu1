package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PromptContextRegressionTest {
    @Test fun recalledMaterialGuidesDiscretionWithoutForcingAnExtraMemoryDecision() {
        assertEquals("", UnifiedMemoryContext().recallUseGuidance)
        val context = UnifiedMemoryContext(understandingContext = "有来源的旧理解")
        val guidance = context.recallUseGuidance
        assertTrue(guidance.contains("只影响自己的理解"))
        assertTrue(guidance.contains("最新原话"))
        assertTrue(guidance.contains("这次不提"))
        assertTrue(context.compactPromptSection().contains(guidance))
        val budget = PromptContextPolicy.forRequest("私聊", UnifiedMemoryRequest(currentInput = "你好"))
        val working = MemoryWorkingSet.assemble(context, emptyList(), emptyList(), budget, "")
        assertTrue(working.sections.values.any { it.contains(guidance) })
        assertTrue(working.usedCharacters + working.protectedCharacters <= budget.workingSetCharacters)
        assertFalse(MemoryWorkingSet.assemble(UnifiedMemoryContext(), emptyList(), emptyList(), budget, "")
            .selected.contains("recall-use-guidance"))
    }

    @Test fun largeBacklogKeepsNewestEventsWithinHardBudget() {
        val events = (0 until 1000).map { index -> SharedTimelineEvent(
            id = "event-$index", characterId = "role", channel = "私聊", speaker = "用户",
            content = "最新内容$index " + "长消息".repeat(1500),
            occurredAt = Instant.parse("2026-10-07T00:00:00Z").plusSeconds(index.toLong()),
        ) }
        val rendered = renderEventLines(events, 7000).joinToString("\n")
        assertTrue(rendered.length <= 7000)
        assertTrue(rendered.contains("最新内容999 "))
        assertFalse(rendered.contains("最新内容0 "))
        assertTrue(renderEventLines(events, 0).isEmpty())
    }

    @Test fun completeMemoryArrayAcceptsFencesAndSurroundingProse() {
        val array = decodeMemoryResponseArray("整理结果：\n```json\n[{\"kind\":\"Fact\",\"content\":\"喜欢猫\"}]\n```")
        assertEquals(1, array.length())
        assertEquals("喜欢猫", array.getJSONObject(0).getString("content"))
        assertEquals(0, decodeMemoryResponseArray("[]").length())
    }

    @Test fun truncatedMemoryArrayFailsRatherThanAdvancingCheckpoint() {
        assertTrue(runCatching { decodeMemoryResponseArray("[{\"content\":\"长记忆\",\"sourceEventIds\":[\"one\"]") }.isFailure)
        assertTrue(runCatching { decodeMemoryResponseArray("没有返回数组") }.isFailure)
    }
}
