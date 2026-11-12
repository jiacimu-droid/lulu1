package com.jiacimu.lulu.data

import android.content.Context
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BackgroundModelRequestBudgetTest {
    private fun fresh(): Context = (RuntimeEnvironment.getApplication() as Context).also {
        it.getSharedPreferences("lulu_background_model_requests_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private val now = Instant.parse("2026-11-08T12:00:00Z")

    @Test fun userChatAndPrimaryPerceptionNeverHaveABackgroundQuota() {
        val ctx = fresh()
        repeat(100) {
            assertTrue(BackgroundModelRequestBudget.claim(ctx, "jiangdu",
                "后台主动感知", "本人决定", now, evidence = "turn:$it"))
        }
    }

    @Test fun oneHundredDifferentOwnerEvidencePayloadsAreNeverDroppedByDailyLimit() {
        val ctx = fresh()
        repeat(100) { index ->
            assertTrue(BackgroundModelRequestBudget.claim(ctx, "jiangdu",
                "记忆", "重要信息即时记忆", now,
                evidence = "owner-message-id:$index"))
        }
    }

    @Test fun exactSameEvidenceRetryIsDelayedEvenAfterRepositoryReload() {
        val ctx = fresh()
        assertTrue(BackgroundModelRequestBudget.claim(ctx, "jiangdu",
            "记忆", "连续记忆提取", now, evidence = "original-event:42"))
        assertFalse(BackgroundModelRequestBudget.claim(ctx.applicationContext, "jiangdu",
            "记忆", "连续记忆提取", now.plusSeconds(2), evidence = "original-event:42"))
        val later = now.plusMillis(BackgroundModelRequestBudget.SAME_EVIDENCE_RETRY_MS)
        assertTrue(BackgroundModelRequestBudget.claim(ctx, "jiangdu",
            "记忆", "连续记忆提取", later, evidence = "original-event:42"))
    }

    @Test fun aRelay524ForOneCommitmentTurnCannotChargeBothRecoveryScanners() {
        val ctx = fresh()
        val originalTurn = "chat-1:user-message-42:jiangdu"
        val stable = commitmentRecoveryEvidenceKey(originalTurn)
        assertEquals("commitment-source-turn:$originalTurn", stable)
        assertEquals(stable, commitmentRecoveryEvidenceKey(originalTurn))
        assertNull(commitmentRecoveryEvidenceKey(null))
        assertNull(commitmentRecoveryEvidenceKey(""))

        // Fast accepted-wake reconciliation and slow general recovery use
        // different prompt text but the same original source-turn identity.
        assertTrue(BackgroundModelRequestBudget.claim(ctx, "jiangdu",
            "承诺任务", "承诺任务提取", now, evidence = stable!!))
        assertFalse(BackgroundModelRequestBudget.claim(ctx, "jiangdu",
            "承诺任务", "承诺任务提取", now.plusSeconds(126),
            evidence = commitmentRecoveryEvidenceKey(originalTurn)!!))

        // Another user turn must still be eligible, even within 15 minutes.
        assertTrue(BackgroundModelRequestBudget.claim(ctx, "jiangdu",
            "承诺任务", "承诺任务提取", now.plusSeconds(126),
            evidence = commitmentRecoveryEvidenceKey("chat-1:user-message-43:jiangdu")!!))
        assertEquals(60_000, COMMITMENT_RECOVERY_READ_TIMEOUT_MS)
    }

    @Test fun distinctTurnsAndRolesDoNotBlockEachOther() {
        val ctx = fresh()
        assertTrue(BackgroundModelRequestBudget.claim(ctx, "jiangdu",
            "生活记录", "生活近况整理", now, evidence = "one-exact-turn"))
        assertTrue(BackgroundModelRequestBudget.claim(ctx, "jiangdu",
            "生活记录", "生活近况整理", now, evidence = "another-exact-turn"))
        assertTrue(BackgroundModelRequestBudget.claim(ctx, "resident:a",
            "生活记录", "生活近况整理", now, evidence = "one-exact-turn"))
        assertTrue(BackgroundModelRequestBudget.claim(ctx, "jiangdu",
            "承诺任务", "承诺任务提取", now, evidence = "one-exact-turn"))
    }
}
