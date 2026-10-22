package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LexiconWorkingSetTest {
    private val role = "lexicon-working-set"
    private val now = Instant.now()
    @Before fun before() {
        MemoryTestEnvironment.release()
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        LuluRepositories.lexicon.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CommitmentTaskStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.set(CommitmentTaskStore, null)
        CommitmentTaskStore.initialize(context)
    }
    @After fun after() { MemoryTestEnvironment.release() }
    private fun entry(id: String, section: LexiconSection, content: String) =
        LexiconEntry(id, role, section, id, content, null, now, now)
    private fun working(query: String, context: UnifiedMemoryContext = UnifiedMemoryContext()): MemoryWorkingSelection =
        MemoryWorkingSet.assemble(context, LexiconMemoryContext.select(role, query), emptyList(),
            PromptContextBudget(18, 1000, 1000, 1000, false, false, 1500, false, workingSetCharacters = 4000), "")

    @Test fun historicalConcernsNeverEvictDiariesOrEnterCurrentMemory() = runBlocking {
        repeat(120) { LuluRepositories.lexicon.save(entry("concern-$it", LexiconSection.Concern, "挂心的事情$it。".repeat(30))) }
        LuluRepositories.lexicon.save(entry("cat-diary", LexiconSection.Diary, "橘猫在桥西等我，我看得很开心"))
        val set = working("橘猫在桥西")
        assertTrue("lexicon:cat-diary" in set.selected)
        assertFalse(set.selected.any { it.startsWith("lexicon:concern-") })
        assertTrue(set.sections[MemoryWorkingSet.LEXICON]!!.length <= 1500)
        assertEquals(121, LuluRepositories.lexicon.snapshot(role).size)
    }

    @Test fun taskProjectionIsNotRepeatedAndAResolvedDiaryRemainsHistorical() = runBlocking {
        LuluRepositories.lexicon.save(entry("linked", LexiconSection.Promise, "监督学习的责任"))
        CommitmentTaskStore.save(CommitmentTask(id = "study-task", characterId = role, lexiconEntryId = "linked",
            goal = "监督学习的责任", status = CommitmentTaskStatus.Scheduled))
        LuluRepositories.lexicon.save(entry("cat-diary", LexiconSection.Diary, "橘猫在桥西让我想起了以前").copy(status = LexiconStatus.Resolved))
        val set = working("橘猫在桥西")
        assertFalse("lexicon:linked" in set.selected)
        assertTrue(UnifiedMemoryOrchestrator.assemble(role, "普通问候").activeTaskContext.contains("监督学习的责任"))
        assertTrue(set.sections[MemoryWorkingSet.LEXICON]!!.contains("Resolved"))
        assertTrue(set.sections[MemoryWorkingSet.LEXICON]!!.contains("Diary"))
    }

    @Test fun onlyPlainLifeCopiesAreDeduplicatedBySourceNotDifferentDiaryInterpretations() = runBlocking {
        SharedExperienceTimeline.record("event", role, "观察", "执行器", "橘猫在桥西", triggerExtraction = false)
        LuluRepositories.lexicon.save(entry("life", LexiconSection.Life, "橘猫在桥西").copy(sourceEventIds = listOf("event")))
        LuluRepositories.lexicon.save(entry("diary", LexiconSection.Diary, "橘猫在桥西让我开心").copy(sourceEventIds = listOf("event")))
        val set = working("橘猫在桥西", UnifiedMemoryContext(sourceEvents = SharedExperienceTimeline.all(role)))
        assertFalse("lexicon:life" in set.selected)
        assertTrue("lexicon:diary" in set.selected)
    }

    @Test fun sameRawEventIsIncludedOnceWhenBothRecentAndRecalled() {
        val event = SharedTimelineEvent(
            id = "same-observation",
            characterId = role,
            channel = "私聊",
            speaker = "用户",
            content = "刚才看见一只橘猫",
            occurredAt = now,
            evidenceKind = EventEvidenceKind.UserStatement,
        )
        val set = working("橘猫", UnifiedMemoryContext(
            characterId = role,
            recentEvents = listOf(event),
            sourceEvents = listOf(event),
        ))
        assertTrue("recent:${event.id}" in set.selected)
        assertFalse("event:${event.id}" in set.selected)
        assertEquals(1, set.sections.values.joinToString("\n").split("eventId=${event.id}@").size - 1)
    }

    @Test fun pastEmotionRetainsItsSubjectiveMeaningAlongsideTheOriginalEvent() {
        SharedExperienceTimeline.record("event", role, "观察", "执行器", "考试结果发布", triggerExtraction = false)
        val emotion = MemoryEntry("feeling", role, "当时既替她高兴又松了口气", MemoryKind.Emotion,
            "timeline-events:event", now, now, 5, false, true)
        val set = working("考试结果", UnifiedMemoryContext(sourceEvents = SharedExperienceTimeline.all(role), memories = listOf(emotion)))
        assertTrue("memory:feeling" in set.selected)
        assertTrue(set.sections[MemoryWorkingSet.SUMMARY]!!.contains("不代表此刻状态"))
    }
}
