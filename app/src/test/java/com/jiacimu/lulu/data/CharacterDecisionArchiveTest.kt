package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterDecisionArchiveTest {
    private lateinit var context: Context
    private val a = "decision-archive-a"
    private val b = "decision-archive-b"
    private val at = Instant.parse("2026-10-28T08:00:00Z")

    @Before fun setup() {
        MemoryTestEnvironment.release()
        context = RuntimeEnvironment.getApplication()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure)
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        LuluRepositories.initialize(context)
        MigratedDomainStores.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        PerceptionStimulusLedger.initialize(context)
        CharacterInnerLifeStore.clear(a)
        CharacterInnerLifeStore.clear(b)
    }

    @After fun teardown() {
        CharacterInnerLifeStore.clear(a)
        CharacterInnerLifeStore.clear(b)
        MemoryTestEnvironment.release()
    }

    private fun source(role: String, id: String) {
        SharedExperienceTimeline.record(id, role, "私聊", "用户", "一条真实请求", at,
            triggerExtraction = false, source = "message",
            evidenceKind = EventEvidenceKind.UserStatement)
    }

    private fun choice(role: String, id: String, cause: String, index: Int) {
        CharacterInnerLifeStore.recordDecision(role, id, "tool", "本人选择先查证",
            "", null, "执行尚未确认", false, at.plusSeconds(index.toLong()),
            causalEvidenceId = cause)
    }

    @Test fun oldDecisionRemainsQueryableAfterHundredsOfTurnsAndReplayIsIdempotent() {
        source(a, "archive-old-source")
        source(a, "archive-other-source")
        choice(a, "archive-old-choice", "archive-old-source", 0)
        repeat(270) { index ->
            choice(a, "archive-other-$index", "archive-other-source", index + 1)
        }
        assertEquals(60, CharacterInnerLifeStore.snapshot(a)
            .optJSONArray("decisions")!!.length())
        assertEquals(200, CharacterInnerLifeStore.historySnapshot(a, 200)
            .optJSONArray("decisions")!!.length())
        val old = CharacterInnerLifeStore.decisionsForEvidence(a, "archive-old-source")
        assertEquals(1, old.size)
        assertEquals("archive-old-choice", old.single().optString("id"))
        assertEquals(1L, old.single().optLong("causalEvidenceRevision"))
        choice(a, "archive-old-choice", "archive-old-source", 999)
        assertEquals(271, CharacterInnerLifeStore.historySnapshot(a, 400)
            .optJSONArray("decisions")!!.length())
        val anchor = SharedExperienceTimeline.eventsByIds(a, listOf("archive-old-source")).single()
        assertTrue(CharacterCausalTrace.inspect(context, a, anchor,
            SharedExperienceTimeline.all(a)).any {
                it.title.contains("本人选择") && it.detail.contains("本人选择先查证")
            })
        assertTrue(CharacterInnerLifeStore.decisionsForEvidence(b, "archive-old-source").isEmpty())
    }

    @Test fun archivedDecisionWithdrawalAndSourceRetractionNeverResurrectHistory() {
        source(a, "archive-delete-source")
        source(b, "archive-delete-source-b")
        choice(a, "archive-delete-target", "archive-delete-source", 0)
        choice(b, "archive-b-keeps", "archive-delete-source-b", 0)
        repeat(90) { index -> choice(a, "archive-fill-$index", "archive-delete-source", index + 1) }
        assertTrue(CharacterInnerLifeStore.forgetDecision(a, "archive-delete-target"))
        assertFalse(CharacterInnerLifeStore.forgetDecision(a, "archive-delete-target"))
        assertTrue(CharacterInnerLifeStore.decisionsForEvidence(a, "archive-delete-source")
            .none { it.optString("id") == "archive-delete-target" })
        CharacterInnerLifeStore.invalidateEvidence("archive-delete-source")
        assertTrue(CharacterInnerLifeStore.decisionsForEvidence(a, "archive-delete-source").isEmpty())
        assertEquals("archive-b-keeps", CharacterInnerLifeStore
            .decisionsForEvidence(b, "archive-delete-source-b").single().optString("id"))
    }

    @Test fun pendingChoiceCanFinishInColdArchiveWithoutRewritingItsSelection() {
        source(a, "archive-pending-source")
        CharacterInnerLifeStore.recordDecision(a, "archive-pending-choice",
            "tool", "本人想先查证", "", null, "尚无执行结果", false, at,
            causalEvidenceId = "archive-pending-source",
            executionRequestId = "archive-request-1",
            executionStepCount = 2, executionState = "pending",
            executionStepRequestIds = listOf("phone-legacy-0", "phone-legacy-1"))
        repeat(85) { index ->
            choice(a, "archive-other-pending-$index", "archive-pending-source", index + 1)
        }
        assertTrue(CharacterInnerLifeStore.completeDecision(a,
            "archive-pending-choice", "archive-request-1", "两步中仅一步成功", false))
        val finished = CharacterInnerLifeStore.decisionsForEvidence(a, "archive-pending-source", 120)
            .single { it.optString("id") == "archive-pending-choice" }
        assertEquals("tool", finished.optString("selected"))
        assertEquals("本人想先查证", finished.optString("reason"))
        assertEquals("reported", finished.optString("executionState"))
        assertEquals("archive-request-1", finished.optString("executionRequestId"))
        assertEquals(2, finished.optInt("executionStepCount"))
        assertEquals("phone-legacy-0", finished.optJSONArray("executionStepRequestIds")
            ?.optString(0))
        assertEquals("phone-legacy-1", finished.optJSONArray("executionStepRequestIds")
            ?.optString(1))
        assertEquals("两步中仅一步成功", finished.optString("outcome"))
        assertFalse(finished.optBoolean("succeeded"))
        assertFalse(CharacterInnerLifeStore.completeDecision(a,
            "archive-pending-choice", "archive-request-1", "迟到的假成功", true))
        assertTrue(CharacterInnerLifeStore.decisionsForEvidence(b, "archive-pending-source").isEmpty())
    }

    @Test fun editedOrWithdrawnSourceCannotBeFinalizedByLateExecution() {
        source(a, "archive-stale-source")
        CharacterInnerLifeStore.recordDecision(a, "archive-stale-choice",
            "publish_moment", "想分享", "", null, "等待执行", false, at,
            causalEvidenceId = "archive-stale-source",
            executionRequestId = "archive-stale-request", executionState = "pending")
        SharedExperienceTimeline.record("archive-stale-source", a, "私聊", "用户",
            "我改主意了，不要发", at.plusSeconds(5), triggerExtraction = false,
            source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        assertFalse(CharacterInnerLifeStore.completeDecision(a,
            "archive-stale-choice", "archive-stale-request", "已发出", true))
        val old = CharacterInnerLifeStore.decisionsForEvidence(a, "archive-stale-source")
            .firstOrNull { it.optString("id") == "archive-stale-choice" }
        assertEquals("pending", old?.optString("executionState"))
        SharedExperienceTimeline.deleteEvent("archive-stale-source")
        assertFalse(CharacterInnerLifeStore.completeDecision(a,
            "archive-stale-choice", "archive-stale-request", "已发出", true))
        assertTrue(CharacterInnerLifeStore.decisionsForEvidence(a, "archive-stale-source").isEmpty())
    }

    @Test fun clearingOnePersonsPrivateThoughtsAlsoClearsArchivedDecisions() {
        source(a, "archive-clear-source")
        repeat(85) { index -> choice(a, "archive-clear-$index", "archive-clear-source", index) }
        CharacterInnerLifeStore.clearSubjectiveThoughts(a)
        assertTrue(CharacterInnerLifeStore.decisionsForEvidence(a, "archive-clear-source").isEmpty())
        assertEquals(0, CharacterInnerLifeStore.historySnapshot(a, 200)
            .optJSONArray("decisions")!!.length())
    }
}
