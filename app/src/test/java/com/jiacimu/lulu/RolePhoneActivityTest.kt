package com.jiacimu.lulu

import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.SharedTimelineEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RolePhoneActivityTest {
    private val now = Instant.parse("2026-11-12T07:10:00Z")

    private fun tool(
        name: String, status: String, secondsAgo: Long,
        source: String = "tool-router",
        kind: EventEvidenceKind = EventEvidenceKind.ToolResult,
        reason: String = "想搜小红书",
    ): SharedTimelineEvent {
        val realResult = JSONObject()
            .put("status", status).put("success", status == "succeeded")
            .put("error", "真实的后端错误")
        val attempt = JSONObject().put("tool", name).put("status", status)
            .put("result", realResult.toString()).put("reason", reason)
        return SharedTimelineEvent(
            id = "event-$secondsAgo", characterId = "role-a",
            channel = "实际工具执行", speaker = "执行器",
            content = attempt.toString(), occurredAt = now.minusSeconds(secondsAgo),
            source = source, evidenceKind = kind,
        )
    }

    @Test fun showsActualToolsNotUnverifiedRoleNarration() {
        val rows = inspectedPhoneActions(listOf(
            tool("background_phone", "failed", 4),
            tool("background_phone", "observed", 11),
            tool("send_private_message", "succeeded", 15),
            tool("background_phone", "succeeded", 1, source = "legacy"),
            tool("background_phone", "succeeded", 2,
                kind = EventEvidenceKind.CharacterStatement),
        ))
        assertEquals(2, rows.size)
        assertEquals("后台手机", rows[0].label)
        assertTrue(rows[0].isUnverified)
        assertTrue(rows[0].outcome.contains("真实的后端错误"))
        assertTrue(rows[0].purpose.contains("想搜"))
        assertFalse(rows[1].outcome.contains("任务完成"))
        assertEquals("已取得画面，任务结果仍需核实", rows[1].outcome)
    }

    @Test fun noModelStoryIsInventedForUnknownAppsOrMissingReceipts() {
        assertEquals("小红书", inspectedRunningAppName("com.xingin.xhs"))
        assertEquals("抖音", inspectedRunningAppName("com.ss.android.ugc.aweme"))
        assertEquals("后台应用", inspectedRunningAppName("unknown.example.app"))
        val unknown = tool("background_phone", "waiting_user", 3, reason = "")
        val list = inspectedPhoneActions(listOf(unknown))
        assertEquals(1, list.size)
        assertTrue(list.single().isUnverified)
        assertTrue(list.single().purpose.isBlank())
    }

    @Test fun sendHistoryDistinguishesAnObservedBubbleFromATitleCheck() {
        val event = tool("background_phone", "unverified", 5)
        fun send(status: String, detail: String) = event.copy(content = JSONObject(event.content)
            .put("fingerprint", """background_phone:{"action":"send_chat_message"}""")
            .put("result", JSONObject().put("status", status).put("verification", detail).toString())
            .toString())
        val blocked = inspectedPhoneActions(listOf(send("unverified", "发送停在收件人标题核实"))).single()
        assertEquals("后台手机 · 发送消息", blocked.label)
        assertTrue(blocked.outcome.contains("收件人标题核实"))
        assertTrue(blocked.isUnverified)
        val visible = inspectedPhoneActions(listOf(send("client_message_visible", ""))).single()
        assertFalse(visible.isUnverified)
        assertTrue(visible.outcome.contains("消息气泡"))
    }

    @Test fun inputHistorySeparatesConfirmedDraftFromUnverifiedDispatch() {
        val base = tool("background_phone", "unverified", 2)
        fun typed(status: String, verified: Boolean, stage: String) =
            base.copy(content = JSONObject(base.content)
                .put("fingerprint", """background_phone:{"action":"type"}""")
                .put("result", JSONObject()
                    .put("status", status)
                    .put("inputTextReadbackVerified", verified)
                    .put("inputStage", stage)
                    .put("verification", "原始设备回执仍未提供可确认的全文")
                    .toString())
                .toString())
        val unverified = inspectedPhoneActions(listOf(
            typed("unverified", false, "input_dispatched_unverified")
        )).single()
        assertTrue(unverified.isUnverified)
        assertTrue(unverified.outcome.contains("未从目标输入框读回全文"))
        assertFalse(unverified.outcome.contains("已读回完整"))
        val exact = inspectedPhoneActions(listOf(
            typed("observed", true, "draft_verified")
        )).single()
        assertFalse(exact.isUnverified)
        assertTrue(exact.outcome.contains("完整草稿"))
        assertTrue(exact.outcome.contains("未发送或提交"))
        val noFrame = inspectedPhoneActions(listOf(
            typed("unverified", true, "draft_verified")
        )).single()
        assertTrue(noFrame.isUnverified)
        assertTrue(noFrame.outcome.contains("新画面不可用"))
    }

    @Test fun recentRealReceiptsAreSortedAndBounded() {
        val rows = inspectedPhoneActions((1L..80L).map {
            tool("web_search", "succeeded", it)
        })
        assertEquals(48, rows.size)
        assertEquals(now.minusSeconds(1), rows.first().occurredAt)
    }
}
