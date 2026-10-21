package com.jiacimu.lulu.data

import android.content.Context
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WorldLifeEpisodeStoreTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val role = "unit-test-solo-life"

    @Before fun before() {
        MemoryTestEnvironment.release()
        com.jiacimu.lulu.LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
    }
    @After fun after() { MemoryTestEnvironment.release() }
    private fun fact(id: String, at: Instant) {
        SharedExperienceTimeline.record(id, role, "数字世界", "执行器", "真实世界动作已完成", at,
            triggerExtraction = false)
    }

    @Test fun followupActionsAtSamePlaceShareOneEpisode() {
        WorldLifeEpisodeStore.initialize(context)
        WorldLifeEpisodeStore.clear(role)
        val start = Instant.parse("2026-10-14T09:00:00Z")
        fact("world-evidence-42", start)
        val prose = "他在自己实际到达的地点度过一段时间，想着没说出口的话。".repeat(5)
        try {
            assertNull(WorldLifeEpisodeStore.recentAt(role, "shared:cloud_meadow", start))
            val first = WorldLifeEpisodeStore.record(
                role, "shared:cloud_meadow", "一个午后", prose,
                "真实探索完成", "world-evidence-42", start,
            )
            assertNotNull(first)
            assertEquals(first?.id, WorldLifeEpisodeStore.recentAt(
                role, "shared:cloud_meadow", start.plusSeconds(6 * 60))?.id)
            assertNull(WorldLifeEpisodeStore.recentAt(
                role, "shared:arrival", start.plusSeconds(6 * 60)))
            assertNull(WorldLifeEpisodeStore.recentAt(
                role, "shared:cloud_meadow", start.plusSeconds(37 * 60)))
            // Work can finish out of sequence: both older and newer receipts
            // belong to the same outing, never create two chapters.
            assertEquals(first?.id, WorldLifeEpisodeStore.nearbyAt(
                role, "shared:cloud_meadow", start.minusSeconds(4 * 60))?.id)
            assertEquals(first?.id, WorldLifeEpisodeStore.nearbyAt(
                role, "shared:cloud_meadow", start.plusSeconds(5 * 60))?.id)
            assertNull(WorldLifeEpisodeStore.nearbyAt(
                role, "shared:cloud_meadow", start.plusSeconds(38 * 60)))
        } finally {
            WorldLifeEpisodeStore.clear(role)
        }
    }


    @Test fun subjectiveStoryQuestionSurvivesReloadButIsNotAWorldAction() {
        WorldLifeEpisodeStore.initialize(context)
        WorldLifeEpisodeStore.clear(role)
        val whenHappened = Instant.now().minusSeconds(120)
        fact("evidence-night-2", whenHappened)
        val prose = "他在家里想起刚刚发生的那句话。".repeat(12)
        try {
            val entry = WorldLifeEpisodeStore.record(
                role, "home:test", "夜里的迟疑", prose, "角色真实回家并停留",
                "evidence-night-2", whenHappened,
                unresolvedThread = "明天是否要解释自己刚才那句话",
            )
            assertNotNull(entry)
            assertEquals("明天是否要解释自己刚才那句话", entry?.unresolvedThread)
            val contextText = WorldLifeEpisodeStore.recentContext(role, whenHappened.plusSeconds(60))
            assertTrue(contextText.contains("角色真实回家并停留"))
            assertFalse(contextText.contains("明天是否要解释自己刚才那句话"))
            assertFalse(contextText.contains("他在家里想起刚刚发生的那句话"))
            WorldLifeEpisodeStore.invalidateEvidence("evidence-night-2")
            assertNull(WorldLifeEpisodeStore.find(entry!!.id))
        } finally {
            WorldLifeEpisodeStore.clear(role)
        }
    }

    @Test fun deletingOnlyTheChapterRetainsItsUnderlyingWorldAction() {
        WorldLifeEpisodeStore.initialize(context)
        WorldLifeEpisodeStore.clear(role)
        val whenHappened = Instant.now().minusSeconds(120)
        val evidenceId = "evidence-story-delete-only"
        fact(evidenceId, whenHappened)
        val chapter = WorldLifeEpisodeStore.record(role, "home:test", "晚光",
            "他坐在桌边，想起了自己的计划。".repeat(7), "真实坐下看书架",
            evidenceId, whenHappened)
        try {
            assertNotNull(chapter)
            assertTrue(WorldLifeEpisodeStore.delete(chapter!!.id))
            assertNull(WorldLifeEpisodeStore.find(chapter.id))
            assertEquals(1, SharedExperienceTimeline.eventsByIds(role, listOf(evidenceId)).size)
            assertFalse(WorldLifeEpisodeStore.delete(chapter.id))
            // A queued write must not resurrect the literary chapter.
            assertNull(WorldLifeEpisodeStore.record(role, "home:test", "晚光重试",
                "新的小说内容写了很多字。".repeat(8), "真实坐下看书架",
                evidenceId, whenHappened))
        } finally { WorldLifeEpisodeStore.clear(role) }
    }

    @Test fun verifiedWorldActionCanBecomeOneDurableClickThroughButTextAloneCannot() {
        WorldLifeEpisodeStore.initialize(context)
        WorldLifeEpisodeStore.clear(role)
        fact("real-world-evidence-id", Instant.parse("2026-10-14T06:00:00Z"))
        val prose = "他踏上真正已经存在的长椅旁的路，慢慢留意午后光影。" .repeat(6)
        try {
            assertNull(WorldLifeEpisodeStore.record(role, "garden", "午后", prose,
                "成功移动", "", Instant.parse("2026-10-14T06:00:00Z")))
            val first = WorldLifeEpisodeStore.record(role, "garden", "午后", prose,
                "真实世界已移动", "real-world-evidence-id", Instant.parse("2026-10-14T06:00:00Z"))
            assertNotNull(first)
            assertEquals(1, WorldLifeEpisodeStore.episodes(role).size)
            val repeated = WorldLifeEpisodeStore.record(role, "garden", "午后", prose,
                "真实世界已移动", "real-world-evidence-id", Instant.parse("2026-10-14T06:05:00Z"))
            assertEquals(first?.id, repeated?.id)
            assertEquals(1, WorldLifeEpisodeStore.episodes(role).size)
            assertEquals(first, WorldLifeEpisodeStore.find(first!!.id))
            WorldLifeEpisodeStore.invalidateEvidence("real-world-evidence-id")
            assertNull(WorldLifeEpisodeStore.find(first.id))
        } finally {
            WorldLifeEpisodeStore.clear(role)
        }
    }
    @Test fun residentConversationBecomesItsOwnChapterAndKeepsAllRawSources() {
        WorldLifeEpisodeStore.initialize(context)
        WorldLifeEpisodeStore.clear(role)
        val at = Instant.parse("2026-10-21T11:00:00Z")
        fact("outing-arrival", at.minusSeconds(90))
        fact("resident-said-hello", at)
        fact("character-replied", at.plusMillis(1))
        fact("resident-summary", at.plusMillis(2))
        val prose = "江渡在咖啡角遇见了老朋友，原本有点犹豫，随后终于聊起真正关心的事情。".repeat(7)
        try {
            val outing = WorldLifeEpisodeStore.record(role, "cafe", "咖啡角",
                prose, "他真实抵达咖啡角", "outing-arrival", at.minusSeconds(90))
            val encounter = WorldLifeEpisodeStore.record(role, "cafe", "咖啡角的交谈",
                prose, "双方确实交流", "resident-summary", at.plusMillis(2),
                sourceEvidenceIds = listOf("resident-summary", "resident-said-hello", "character-replied"),
                category = "encounter")
            assertNotNull(outing)
            assertNotNull(encounter)
            assertEquals("encounter", encounter?.category)
            assertEquals(3, encounter?.sourceEvidenceIds?.size)
            assertEquals(2, WorldLifeEpisodeStore.episodes(role).size)
            assertEquals(outing?.id, WorldLifeEpisodeStore.nearbyAt(role, "cafe", at)?.id)
            // A literary scene is a projection: delete one originating turn,
            // and the episode disappears without deleting the remaining facts.
            WorldLifeEpisodeStore.invalidateEvidence("resident-said-hello")
            assertNull(WorldLifeEpisodeStore.find(encounter!!.id))
            assertNotNull(WorldLifeEpisodeStore.find(outing!!.id))
            assertEquals(1, SharedExperienceTimeline.eventsByIds(role,
                listOf("resident-summary")).size)
        } finally { WorldLifeEpisodeStore.clear(role) }
    }

    @Test fun encounterCannotBePublishedWithMissingOrChangedSupportingEvidence() {
        WorldLifeEpisodeStore.initialize(context)
        WorldLifeEpisodeStore.clear(role)
        val at = Instant.now()
        fact("resident-anchor", at)
        fact("resident-first-turn", at)
        val oldTurn = SharedExperienceTimeline.eventsByIds(role, listOf("resident-first-turn")).single()
        SharedExperienceTimeline.record(oldTurn.id, role, "数字世界", "执行器",
            "已经更正的真实交流", at, triggerExtraction = false)
        val prose = "他们真的坐下来谈了很久，窗外的天色变化，交谈中产生新的想法。".repeat(7)
        try {
            assertNull(WorldLifeEpisodeStore.record(role, "cafe", "迟到的交流",
                prose, "真实交流", "resident-anchor", at,
                sourceEvidenceIds = listOf("resident-anchor", "不存在的台词"),
                category = "encounter"))
            // The store itself only accepts current sources; a stale worker
            // must also check snapshots before invoking it.
            assertNotEquals(oldTurn,
                SharedExperienceTimeline.eventsByIds(role, listOf(oldTurn.id)).single())
            assertTrue(WorldLifeEpisodeStore.episodes(role).isEmpty())
        } finally { WorldLifeEpisodeStore.clear(role) }
    }

    @Test fun lateProseCannotSurviveSourceEditingDeletionOrHistoryReset() {
        WorldLifeEpisodeStore.initialize(context)
        WorldLifeEpisodeStore.clear(role)
        val at = Instant.now()
        fact("late-scene-source", at)
        val expected = SharedExperienceTimeline.eventsByIds(role, listOf("late-scene-source")).single()
        SharedExperienceTimeline.record(expected.id, role, "数字世界", "执行器", "已更正的实际动作", at,
            triggerExtraction = false)
        assertNull(WorldLifeEpisodeStore.record(role, "garden", "过期", "一段完整的个人叙事。".repeat(20),
            expected.content, expected.id, at, expectedSource = expected))
        SharedExperienceTimeline.deleteEvent(expected.id)
        assertNull(WorldLifeEpisodeStore.record(role, "garden", "迟到", "一段完整的个人叙事。".repeat(20),
            expected.content, expected.id, at))
        assertTrue(WorldLifeEpisodeStore.episodes(role).isEmpty())
        fact("reset-source", at)
        val beforeReset = SharedExperienceTimeline.eventsByIds(role, listOf("reset-source")).single()
        SharedExperienceTimeline.deleteCharacterEvents(role)
        WorldLifeEpisodeStore.clear(role)
        assertNull(WorldLifeEpisodeStore.record(role, "garden", "旧生活", "一段完整的个人叙事。".repeat(20),
            beforeReset.content, beforeReset.id, at, expectedSource = beforeReset))
    }

}
