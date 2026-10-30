package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterLifeDiagnosticsTest {
    private val now = Instant.parse("2026-10-30T10:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun noGoalsOrChoicesProduceNoFakeRunningActivity() {
        assertTrue(CharacterLifeDiagnostics.lines(JSONObject(), now, zone).isEmpty())
    }

    @Test fun pendingDecisionAndWaitingEffectsAreNotMisreportedAsSuccess() {
        val goal = JSONObject().put("id", "goal-1").put("aim", "确认对方确实睡着")
            .put("status", "waiting_feedback")
            .put("successCondition", "需要实际反馈")
            .put("fallbackReviewAt", now.plusSeconds(3600).toString())
        val pending = JSONObject().put("id", "decision-1").put("at", now.minusSeconds(60).toString())
            .put("selected", "tool").put("executionState", "pending")
            .put("outcome", "模型已选择；尚未取得本轮执行总结")
        val state = JSONObject().put("motives", JSONArray().put(goal))
            .put("decisions", JSONArray().put(pending))
        val original = state.toString()
        val lines = CharacterLifeDiagnostics.lines(state, now, zone)
        assertTrue(lines.any { it.contains("等待真实效果 1 项") })
        assertTrue(lines.any { it.contains("普通感知复查机会") })
        assertTrue(lines.any { it.contains("执行总结尚未回写") })
        assertFalse(lines.any { it.contains("执行器已回写成功") })
        assertEquals(original, state.toString())
    }

    @Test fun successSummaryNeverConcludesGoalAndFutureDecisionIsExcluded() {
        val valid = JSONObject().put("id", "old").put("at", now.minusSeconds(10).toString())
            .put("selected", "tool").put("executionState", "reported")
            .put("succeeded", true).put("outcome", "成功发送提醒")
        val future = JSONObject().put("id", "future").put("at", now.plusSeconds(600).toString())
            .put("selected", "call").put("executionState", "reported")
            .put("succeeded", true).put("outcome", "未来记录不得进入现在")
        val lines = CharacterLifeDiagnostics.lines(JSONObject()
            .put("decisions", JSONArray().put(valid).put(future)), now, zone)
        assertTrue(lines.any { it.contains("目标效果仍待单独核对") })
        assertTrue(lines.any { it.contains("成功发送提醒") })
        assertFalse(lines.any { it.contains("未来记录") })
    }
}
