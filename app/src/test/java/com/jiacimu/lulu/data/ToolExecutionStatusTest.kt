package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Android-compatible org.json is required: mockable android.jar throws from JSONObject.put. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ToolExecutionStatusTest {
    @Test fun pendingAuthorizationAndUnknownStatesCannotUnlockDependentActions() {
        val incomplete = listOf("pending", "waiting_user", "waiting_permission", "requested",
            "running", "queued", "in_progress", "requested_unverified", "mystery")
        incomplete.forEach { status ->
            assertFalse(status, ToolExecutionStatus.completed(
                JSONObject().put("success", true).put("status", status)))
        }
    }

    @Test fun expectedIncompleteOutcomeIsNotAnAppFailureButCannotUnlockLaterSteps() {
        val expected = listOf("unverified", "waiting_user", "waiting_permission",
            "requested", "requested_unverified", "pending", "denied")
        expected.forEach { status ->
            val result = JSONObject().put("success", false).put("status", status)
                .put("summary", "真实请求还未完成")
            assertFalse(status, ToolExecutionStatus.shouldReportPerformanceError(result))
            assertFalse(status, ToolExecutionStatus.completed(result))
        }
        assertTrue(ToolExecutionStatus.shouldReportPerformanceError(
            JSONObject().put("success", false).put("status", "failed")
                .put("error", "真正的执行失败")))
        assertTrue(ToolExecutionStatus.shouldReportPerformanceError(
            JSONObject().put("success", false).put("error", "无状态的执行失败")))
        assertFalse(ToolExecutionStatus.shouldReportPerformanceError(
            JSONObject().put("success", true).put("status", "succeeded")))
    }

    @Test fun firstOrderedStepIsNotBlockedByPhantomPrimaryTool() {
        val absentPrimary = JSONObject().put("success", true).put("status", "skipped")
        // Online/silent choices with only steps should behave like phone's
        // initial previousStepCompleted=true. No synthetic skipped action can
        // block a real first step.
        assertFalse(ToolExecutionStatus.completed(absentPrimary))
        assertTrue(ToolExecutionStatus.primaryAllowsDependentSteps("", absentPrimary))
        assertTrue(ToolExecutionStatus.primaryAllowsDependentSteps("   ", absentPrimary))
        val pending = JSONObject().put("success", true).put("status", "pending")
        val verified = JSONObject().put("success", true).put("status", "succeeded")
        assertFalse(ToolExecutionStatus.primaryAllowsDependentSteps("web_search", pending))
        assertFalse(ToolExecutionStatus.primaryAllowsDependentSteps("web_search",
            JSONObject().put("success", false).put("status", "failed")))
        assertTrue(ToolExecutionStatus.primaryAllowsDependentSteps("web_search", verified))
    }

    @Test fun actuallyCompletedOperationsAllowTheNextAction() {
        val completed = listOf("succeeded", "observed", "notification_posted",
            "popup_requested", "verified", "already_at_target", "unchanged",
            "capped", "at_safe_limit")
        completed.forEach { status ->
            assertTrue(status, ToolExecutionStatus.completed(
                JSONObject().put("success", true).put("status", status)))
        }
        // Legacy local executors omit status; ToolRouter normalizes the
        // confirmed successful result to succeeded when persisting it.
        assertTrue(ToolExecutionStatus.completed(JSONObject().put("success", true)))
        assertFalse(ToolExecutionStatus.completed(JSONObject()
            .put("success", false).put("status", "succeeded")))
    }
}
