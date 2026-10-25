package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Tool dependencies may continue only from a verified terminal outcome. */
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
