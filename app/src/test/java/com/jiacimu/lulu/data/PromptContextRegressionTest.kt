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

    @Test fun coreAndUnfinishedResponsibilitiesCannotEatHistoricalRecallAllowance() {
        // Authoritative core and unfinished obligations have separate complete
        // outgoing sections. Their size cannot reduce historical recall to zero.
        val budget = PromptContextBudget(
            recallLimit = 40, memoryCharacters = 1_200,
            evidenceCharacters = 1_200, recentCharacters = 0,
            worldDetails = false, richInnerLife = true,
            lexiconCharacters = 1_200, precise = false,
            workingSetCharacters = 1_000,
        )
        val history = "【有来源的旧经历】\n" + "今天想起上次一起读书。"
        val selected = MemoryWorkingSet.assemble(
            UnifiedMemoryContext(activeTaskContext = "未完成的真实任务。".repeat(1_000)),
            emptyList(), listOf("旧经历摘要" to history), budget,
            coreSection = "不可丢失的核心记忆。".repeat(1_000),
        )
        assertEquals(0, selected.protectedCharacters)
        assertTrue(selected.sections["旧经历摘要"].orEmpty().contains(history))
        assertTrue(selected.usedCharacters <= budget.workingSetCharacters)
        assertFalse(selected.sections.values.joinToString().contains("未完成的真实任务。"))
    }

    @Test fun ownerProfileIsBoundedRecallAndNeverAnotherRolesSelfGrowth() {
        val owner = DevelopmentRecord(
            id = "about-owner", characterId = "knowing", slot = "user:preference",
            kind = DevelopmentKind.UserPreference, content = "喜欢晴天和柑橘茶",
            confidence = 0.8, evidence = mapOf("owner-message" to 1L),
            counterEvidence = emptyMap(), version = 1,
            createdAt = Instant.parse("2026-11-10T00:00:00Z"),
        )
        val stranger = owner.copy(id = "private-to-other", characterId = "other",
            content = "其他人的私密认识")
        val budget = PromptContextBudget(20, 1200, 1000, 0, false, true,
            1000, false, workingSetCharacters = 1600)
        val selected = MemoryWorkingSet.assemble(
            UnifiedMemoryContext(characterId = "knowing"), emptyList(), emptyList(), budget,
            coreSection = "", ownerKnowledge = listOf(owner, stranger))
        val rendered = selected.sections[MemoryWorkingSet.OWNER].orEmpty()
        assertTrue(rendered.contains(owner.content))
        assertFalse(rendered.contains(stranger.content))
        assertTrue(selected.selected.contains("owner:about-owner"))
        assertTrue(selected.usedCharacters <= budget.workingSetCharacters)
        val tiny = MemoryWorkingSet.assemble(UnifiedMemoryContext(characterId = "knowing"),
            emptyList(), emptyList(), budget.copy(workingSetCharacters = 20),
            coreSection = "", ownerKnowledge = listOf(owner))
        assertFalse(tiny.sections.values.joinToString().contains(owner.content))
        assertTrue(tiny.omitted.containsKey("owner:about-owner"))
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

    @Test fun memoryExtractionAcceptsConventionalObjectWrapperWithoutAnotherPaidRequest() {
        val array = decodeMemoryResponseArray(
            """{"memories":[{"kind":"Fact","content":"明天喜欢去公园",
                "sourceEventIds":["source-7"]}]}""")
        assertEquals(1, array.length())
        assertEquals("source-7",
            array.getJSONObject(0).getJSONArray("sourceEventIds").getString(0))
    }

    @Test fun memoryExtractionIgnoresBracketsInProseAndStringContents() {
        val json = """说明中的 [not json] 不属于结果
            ```json
            [{"kind":"Timeline","content":"今天记住了[重要]的事",
              "sourceEventIds":["event-9"]}]
            ```
            数组之后的附言[无关]"""
        val array = decodeMemoryResponseArray(json)
        assertEquals(1, array.length())
        assertEquals("今天记住了[重要]的事",
            array.getJSONObject(0).getString("content"))
    }

    @Test fun truncatedMemoryArrayFailsRatherThanAdvancingCheckpoint() {
        assertTrue(runCatching { decodeMemoryResponseArray("[{\"content\":\"长记忆\",\"sourceEventIds\":[\"one\"]") }.isFailure)
        assertTrue(runCatching { decodeMemoryResponseArray("没有返回数组") }.isFailure)
        assertTrue(runCatching { decodeMemoryResponseArray("""[{"content":"仍在生成","sourceEventIds":[]""") }.isFailure)
    }
}
