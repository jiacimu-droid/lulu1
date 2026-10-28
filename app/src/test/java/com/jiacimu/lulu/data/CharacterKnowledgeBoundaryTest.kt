package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/** The FINAL model-prompt serializers must be role-owned even if a UI adapter
 * accidentally hands them a mixed list of private events, learned summaries
 * and personal diary entries. None of the original data is modified. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterKnowledgeBoundaryTest {
    private val now = Instant.parse("2026-10-28T06:00:00Z")
    private val a = "role-A"
    private val b = "role-B"
    private fun event(id: String, role: String, text: String) =
        SharedTimelineEvent(id, role, "私聊", "用户", text, now,
            evidenceKind = EventEvidenceKind.UserStatement)
    private fun memory(id: String, role: String, text: String) =
        MemoryEntry(id, role, text, MemoryKind.Fact, "manual", now, now,
            8, false, true)
    private fun diary(id: String, role: String, text: String) =
        LexiconEntry(id, role, LexiconSection.Diary, id, text, null, now, now)
    private fun budget() = PromptContextBudget(18, 2400, 2400, 2400, false, false,
        1800, false, workingSetCharacters = 12000)

    @Test fun aAndBKeepSeparateHistoriesThroughBothCompactAndFullPromptPaths() {
        val app = RuntimeEnvironment.getApplication()
        LuluRepositories.initialize(app)
        val aEvent = event("event-A", a, "A 私下得知的鲜花偏好")
        val bEvent = event("event-B", b, "B 私下得知的茶点偏好")
        val aMemory = memory("memory-A", a, "A 的独有长期认识")
        val bMemory = memory("memory-B", b, "B 的独有长期认识")
        val aDiary = diary("diary-A", a, "A 今天去过旧书店")
        val bDiary = diary("diary-B", b, "B 今天去过雪山")
        val rawEvents = listOf(aEvent, bEvent)
        val rawMemories = listOf(aMemory, bMemory)
        val rawDiaries = listOf(aDiary, bDiary)
        val sourceSnapshot = rawEvents.map { it.id to it.content }
        for ((role, own, foreign) in listOf(
            Triple(a, "A 私下得知", "B 私下得知"),
            Triple(b, "B 私下得知", "A 私下得知"))) {
            val profile = UnifiedMemoryContext(characterId = role,
                memories = rawMemories, coreMemories = rawMemories,
                sourceEvents = rawEvents, recentEvents = rawEvents)
            val compact = profile.compactPromptSection(9000)
            assertTrue(compact.contains(own))
            assertFalse(compact.contains(foreign))
            assertFalse(compact.contains(if (role == a) "B 的独有长期认识" else "A 的独有长期认识"))
            val selection = MemoryWorkingSet.assemble(profile, rawDiaries,
                emptyList(), budget(), profile.coreMemorySection)
            val fullText = selection.sections.values.joinToString("\n") +
                profile.coreMemorySection
            assertTrue(fullText.contains(own))
            assertTrue(fullText.contains(if (role == a) "A 今天去过旧书店" else "B 今天去过雪山"))
            assertFalse(fullText.contains(foreign))
            assertFalse(fullText.contains(if (role == a) "B 今天去过雪山" else "A 今天去过旧书店"))
            assertFalse(fullText.contains(if (role == a) "B 的独有长期认识" else "A 的独有长期认识"))
        }
        // Isolation is a read-only projection, not destructive cleanup.
        assertEquals(sourceSnapshot, rawEvents.map { it.id to it.content })
        assertEquals(2, rawMemories.size)
        assertEquals(2, rawDiaries.size)
    }

    @Test fun missingOwnerFailsClosedOnMixedRecordsButPreservesUnambiguousLegacyEntry() {
        val app = RuntimeEnvironment.getApplication()
        LuluRepositories.initialize(app)
        val aEvent = event("event-A-blank", a, "A 的仅本人经历")
        val bEvent = event("event-B-blank", b, "B 的仅本人经历")
        val mixed = UnifiedMemoryContext(sourceEvents = listOf(aEvent, bEvent),
            memories = listOf(memory("a", a, "A 的私有记忆"), memory("b", b, "B 的私有记忆")))
        assertEquals("", mixed.effectiveOwner)
        assertFalse(mixed.compactPromptSection().contains("私有记忆"))
        val mixedWork = MemoryWorkingSet.assemble(mixed,
            listOf(diary("a-diary", a, "A 的私人日记"), diary("b-diary", b, "B 的私人日记")),
            emptyList(), budget(), "")
        assertFalse(mixedWork.sections.values.joinToString().contains("私人日记"))
        val legacy = MemoryWorkingSet.assemble(UnifiedMemoryContext(),
            listOf(diary("old", a, "仅 A 的旧版日记")), emptyList(), budget(), "")
        assertTrue(legacy.sections.values.joinToString().contains("仅 A 的旧版日记"))
    }
}
