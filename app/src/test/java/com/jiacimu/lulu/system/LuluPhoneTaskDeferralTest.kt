package com.jiacimu.lulu.system

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LuluPhoneTaskDeferralTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private fun authored() = JSONObject().put("title", "继续阅读评论")
        .put("intent", "已经打开视频，尚未读完评论，之后继续查看")
        .put("dueAt", "2026-01-01T08:03:00+08:00")

    @Test fun usesOriginalDurablePlanAndReopensActualAppWithoutReplayingOldCoordinates() {
        val proposal = authored().put("activityArgs", JSONObject().put("action", "publish_comment")
            .put("screenSnapshotId", "stale").put("x", 42).put("elementRef", "e1"))
        val plan = LuluPhoneTaskDeferral.plan(proposal, "test.video", now)
        assertEquals("background_phone", plan.getString("activityAction"))
        assertEquals("once", plan.getString("repeat"))
        assertEquals(proposal.getString("intent"), plan.getString("intent"))
        assertEquals("2026-01-01T00:03:00Z", plan.getString("dueAt"))
        val args = plan.getJSONObject("activityArgs")
        assertEquals(2, args.length())
        assertEquals("launch", args.getString("action"))
        assertEquals("test.video", args.getString("packageName"))
    }

    @Test fun missingOrInvalidTimeIsNeverReplacedWithAnInventedRetry() {
        listOf("", "later", "2026-01-01T00:03:00", "2026-01-01T00:00:30Z").forEach { time ->
            assertTrue(runCatching { LuluPhoneTaskDeferral.plan(authored().put("dueAt", time), "test.video", now) }.isFailure)
        }
    }

    @Test fun unknownAppOrMissingGoalCannotCreateAnotherPhoneTask() {
        assertTrue(runCatching { LuluPhoneTaskDeferral.plan(authored(), "", now) }.isFailure)
        assertTrue(runCatching { LuluPhoneTaskDeferral.plan(authored().put("intent", ""), "test.video", now) }.isFailure)
    }
}
