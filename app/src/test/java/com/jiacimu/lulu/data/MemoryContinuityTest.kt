package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MemoryContinuityTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }
    private fun memory(id: String, content: String, source: String = "手动") = MemoryEntry(id, "role", content,
        MemoryKind.Fact, source, null, Instant.now(), 8, false, true)

    @Test fun thirtyCoreFactsSurviveOrdinaryAndCompactPromptBudgets() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        repeat(30) { index -> LuluRepositories.memory.save(memory("core-$index", "核心事实编号$index").copy(tier = MemoryTier.Core)) }
        repeat(60) { index -> LuluRepositories.memory.save(memory("ordinary-$index", "今天饮食聊天编号$index")) }
        val assembled = UnifiedMemoryOrchestrator.assemble("role", "普通问候", recallLimit = 1)
        assertEquals(30, assembled.coreMemories.size)
        assertTrue(assembled.memories.isEmpty())
        repeat(30) { index -> assertTrue(assembled.compactPromptSection(900).contains("核心事实编号$index")) }
        val restored = LocalMemoryRepository().apply { initialize(context) }
        assertEquals(30, restored.snapshot("role").count { it.tier == MemoryTier.Core })
    }

    @Test fun deletedOrDisabledMemoryCannotReturnThroughRawFallback() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        SharedExperienceTimeline.record("food-event", "role", "私聊", "用户", "我喜欢喝奶茶", triggerExtraction = false,
            evidenceKind = EventEvidenceKind.UserStatement)
        val entry = memory("food", "用户喜欢喝奶茶", "timeline-events:food-event")
        LuluRepositories.memory.save(entry)
        assertTrue(RawTimelineMemoryRecall.find("role", "喜欢喝奶茶").isNotEmpty())
        LuluRepositories.memory.toggleRecall(entry.id)
        assertTrue(RawTimelineMemoryRecall.find("role", "喜欢喝奶茶").isEmpty())
        LuluRepositories.memory.toggleRecall(entry.id)
        LuluRepositories.memory.deleteEverywhereEquivalent(entry.id)
        assertTrue(RawTimelineMemoryRecall.find("role", "喜欢喝奶茶").isEmpty())
        val restored = LocalMemoryRepository().apply { initialize(context) }
        assertFalse(restored.allowsRawRecall(SharedExperienceTimeline.all("role").single()))
    }

    @Test fun deletingAnEquivalentMemoryNeverDeletesAnotherCharactersPersonalHistory() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        SharedExperienceTimeline.initialize(context)
        val repository = LocalMemoryRepository().apply { initialize(context) }
        val suffix = java.util.UUID.randomUUID().toString()
        val roleA = "archive-a-$suffix"
        val roleB = "archive-b-$suffix"
        val sentence = "两个人亲历相同的雨天仍各自保存属于自己的记忆"
        val a = memory("memory-a-$suffix", sentence).copy(characterId = roleA)
        val b = memory("memory-b-$suffix", sentence).copy(characterId = roleB)
        repository.save(a)
        repository.save(b)
        assertEquals(1, repository.snapshot(roleA).size)
        assertEquals(1, repository.snapshot(roleB).size)
        assertEquals(1, repository.deleteEverywhereEquivalent(a.id))
        assertTrue(repository.snapshot(roleA).isEmpty())
        assertEquals(listOf(b.id), repository.snapshot(roleB).map(MemoryEntry::id))
        val reloaded = LocalMemoryRepository().apply { initialize(context) }
        assertEquals(listOf(b.id), reloaded.snapshot(roleB).map(MemoryEntry::id))
        assertEquals(1, reloaded.snapshot(roleB).size)
    }

    @Test fun oppositePreferencesAreNeverMergedByTextSimilarity() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val repository = LocalMemoryRepository().apply { initialize(context) }
        repository.save(memory("yes", "用户明确表示自己平时很喜欢吃麻辣火锅"))
        repository.save(memory("no", "用户明确表示自己平时不喜欢吃麻辣火锅"))
        assertEquals(0, repository.maintain("role"))
        assertEquals(2, repository.snapshot("role").size)
    }

    @Test fun oldMemoryDefaultsAndResolvedConcernsPersistWithoutDiaryEviction() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        LuluRepositories.lexicon.initialize(context)
        val old = memory("old", "普通偏好")
        LuluRepositories.memory.save(old)
        assertEquals(MemoryTier.Stable, LocalMemoryRepository().apply { initialize(context) }.snapshot("role").single().tier)
        val now = Instant.now()
        val concern = LexiconEntry("concern", "role", LexiconSection.Concern, "等待结果", "考试结果还没出来", null, now, now)
        LuluRepositories.lexicon.save(concern)
        repeat(60) { index -> LuluRepositories.lexicon.save(LexiconEntry("diary-$index", "role", LexiconSection.Diary, "日记$index", "无关日记", null, now, now.plusSeconds(index.toLong()))) }
        assertFalse(LexiconMemoryContext.select("role", "普通问候").any { it.id == concern.id })
        val legacy = LexiconMemoryContext.select("role", "等待结果 考试结果还没出来")
        assertTrue(legacy.any { it.id == concern.id })
        val historicalSet = MemoryWorkingSet.assemble(UnifiedMemoryContext(), legacy, emptyList(),
            PromptContextBudget(18, 1000, 1000, 1000, false, false, 1500, false,
                workingSetCharacters = 4000), "")
        assertTrue(historicalSet.sections[MemoryWorkingSet.LEXICON]
            .orEmpty().contains("旧版关心笔记/仅供历史参考"))
        SharedExperienceTimeline.initialize(context)
        SharedExperienceTimeline.record("event", "role", "私聊", "用户", "考试结果出来了", now,
            triggerExtraction = false, evidenceKind = EventEvidenceKind.UserStatement)
        LuluRepositories.lexicon.save(concern.copy(status = LexiconStatus.Resolved, sourceEventIds = listOf("event"), lastFollowUpAt = now))
        assertFalse(LexiconMemoryContext.select("role", "普通问候").any { it.id == concern.id })
        val restored = InMemoryLexiconRepository().apply { initialize(context) }.snapshot("role").first { it.id == concern.id }
        assertEquals(LexiconStatus.Resolved, restored.status)
        assertEquals(listOf("event"), restored.sourceEventIds)
        assertEquals(now, restored.lastFollowUpAt)
    }
}
