package com.jiacimu.lulu

import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.SharedTimelineEvent
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RolePhoneBrowserHistoryTest {
    private fun receipt(id: String, tool: String, args: JSONObject,
        success: Boolean = true, role: String = "role-a"): SharedTimelineEvent {
        val execution = JSONObject()
            .put("tool", tool).put("fingerprint", "$tool:$args")
            .put("status", if (success) "succeeded" else "failed")
            .put("reason", "本人真实选的理由")
            .put("result", JSONObject().put("success", success)
                .put("summary", if (success) "确实查到了结果" else "没有成功"))
        return SharedTimelineEvent(
            id, role, "实际工具执行", "执行器", execution.toString(),
            Instant.parse("2026-10-26T00:00:00Z").plusSeconds(id.length.toLong()),
            source = "tool-router", evidenceKind = EventEvidenceKind.ToolResult,
        )
    }

    @Test fun showsOnlyActualSearchReceiptsAndTheirActualReason() {
        val events = listOf(
            receipt("web", "web_search", JSONObject().put("query", "月亮")),
            receipt("xhs", "read_xiaohongshu", JSONObject().put("keyword", "小猫")),
            receipt("images", "image_search", JSONObject().put("query", "大海"), false),
            receipt("not-search", "send_character_message", JSONObject().put("text", "你好")),
        )
        val view = rolePhoneSearchEntries(events)
        assertEquals(3, view.size)
        assertEquals(setOf("网页", "小红书", "图片"), view.map { it.kind }.toSet())
        assertEquals(setOf("月亮", "小猫", "大海"), view.map { it.query }.toSet())
        assertTrue(view.all { it.reason == "本人真实选的理由" })
        assertEquals("failed", view.single { it.kind == "图片" }.status)
        assertEquals("没有成功", view.single { it.kind == "图片" }.summary)
    }

    @Test fun rejectsForgedOrUnprovenNonRouterEvents() {
        val event = receipt("web", "web_search", JSONObject().put("query", "隐私"))
        assertTrue(rolePhoneSearchEntries(listOf(
            event.copy(source = "character-statement"),
            event.copy(evidenceKind = EventEvidenceKind.Observation),
            event.copy(channel = "角色心声"),
        )).isEmpty())
    }
}
