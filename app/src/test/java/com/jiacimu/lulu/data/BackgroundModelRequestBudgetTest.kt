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
    private val role = "budget-test"

    @Test fun userChatAndPrimaryPerceptionDoNotConsumeBackgroundBudget() {
        val ctx = fresh()
        repeat(40) {
            assertTrue(BackgroundModelRequestBudget.claim(ctx, role,
                "后台主动感知", "本人真正的决定", now))
            assertTrue(BackgroundModelRequestBudget.claim(ctx, role,
                "电话回复", "本人接电话", now))
        }
        assertTrue(BackgroundModelRequestBudget.allowed(ctx, role,
            "记忆", "连续记忆提取", now))
    }

    @Test fun repeatedFailuresAreChargedAtMostFourBatchMemoryHandoffsAndNeverResetOnReload() {
        val ctx = fresh()
        repeat(4) {
            assertTrue(BackgroundModelRequestBudget.claim(ctx, role,
                "记忆", "连续记忆提取", now))
        }
        assertFalse(BackgroundModelRequestBudget.allowed(ctx, role,
            "记忆", "连续记忆提取", now))
        assertFalse(BackgroundModelRequestBudget.claim(ctx.applicationContext, role,
            "记忆", "连续记忆提取", now.plusSeconds(60)))
        // An immediate important user fact retains a separate bounded lane.
        assertTrue(BackgroundModelRequestBudget.claim(ctx, role,
            "记忆", "重要信息即时记忆", now.plusSeconds(61)))
    }

    @Test fun allHousekeepingCategoriesShareEightPaidAttemptsPerRoleAndDay() {
        val ctx = fresh()
        repeat(3) { assertTrue(BackgroundModelRequestBudget.claim(ctx, role,
            "记忆", "重要信息即时记忆", now)) }
        repeat(4) { assertTrue(BackgroundModelRequestBudget.claim(ctx, role,
            "记忆", "连续记忆提取", now)) }
        assertTrue(BackgroundModelRequestBudget.claim(ctx, role,
            "增量成长反思", "角色成长", now))
        assertFalse(BackgroundModelRequestBudget.claim(ctx, role,
            "生活记录", "生活近况提取", now))
        assertFalse(BackgroundModelRequestBudget.claim(ctx, role,
            "承诺任务", "承诺任务提取", now))
    }

    @Test fun localMidnightRenewsAllowanceWithoutClearingOldMessages() {
        val ctx = fresh()
        repeat(4) { assertTrue(BackgroundModelRequestBudget.claim(ctx, role,
            "记忆", "连续记忆提取", now)) }
        val next = BackgroundModelRequestBudget.nextDay(now).plusSeconds(60)
        assertTrue(BackgroundModelRequestBudget.claim(ctx, role,
            "记忆", "连续记忆提取", next))
    }

    @Test fun roleBudgetsAreIndependent() {
        val ctx = fresh()
        repeat(2) { assertTrue(BackgroundModelRequestBudget.claim(ctx, role,
            "承诺任务", "承诺任务提取", now)) }
        assertFalse(BackgroundModelRequestBudget.allowed(ctx, role,
            "承诺任务", "承诺任务提取", now))
        assertTrue(BackgroundModelRequestBudget.claim(ctx, "another-person",
            "承诺任务", "承诺任务提取", now))
    }
}
