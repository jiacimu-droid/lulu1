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
