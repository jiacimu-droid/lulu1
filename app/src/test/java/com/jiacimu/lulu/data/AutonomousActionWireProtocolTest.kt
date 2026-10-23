package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutonomousActionWireProtocolTest {
    @Test fun explicitReadingOrDiaryToolNeverFallsThroughToSilent() {
        val reading = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "read_book")
                .put("args", JSONObject().put("readingBookId", "book-real")))
        assertEquals("reading", reading.getString("action"))
        assertEquals("book-real", reading.getString("readingBookId"))

        val journal = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "write_journal")
                .put("title", "想过的事").put("content", "我真的得重新想一想。"))
        assertEquals("journal", journal.getString("action"))
        assertEquals("想过的事", journal.getString("journalTitle"))
        assertEquals("我真的得重新想一想。", journal.getString("journalContent"))

        val publicPost = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "publish_moment")
                .put("args", JSONObject().put("text", "刚才散步时遇到了一件好玩的事。")))
        assertEquals("moment", publicPost.getString("action"))
        assertEquals("刚才散步时遇到了一件好玩的事。", publicPost.getString("text"))
    }

    @Test fun bothNamedWorldResidentInteractionAndFriendlyActionCanCarryRealIDs() {
        val direct = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "digital_world_action")
                .put("args", JSONObject().put("worldAction", "interact_resident")
                    .put("itemId", "resident-1").put("activityId", "greet")))
        assertEquals("digital_world", direct.getString("action"))
        assertEquals("resident-1", direct.getString("itemId"))

        val friendly = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "digital_world")
                .put("args", JSONObject().put("worldAction", "interact_resident")
                    .put("itemId", "resident-2").put("activityId", "chat")))
        assertEquals("digital_world", friendly.getString("action"))
        assertEquals("resident-2", friendly.getString("itemId"))
        assertEquals("interact_resident", friendly.getString("worldAction"))
    }

    @Test fun toolFormPrivateReplyStillCountsAsActuallyDeliveringAMessage() {
        val result = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "tool").put("tool", "send_private_message")
                .put("args", JSONObject().put("text", "那我跟你说一件刚发现的趣事")))
        assertEquals("message", result.getString("action"))
        assertEquals("那我跟你说一件刚发现的趣事", result.getString("text"))
    }

    @Test fun nestedToolNameIsRecoveredWithoutInventingAnUnknownCapability() {
        val nested = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "tool")
                .put("args", JSONObject().put("tool", "read_xiaohongshu")
                    .put("keyword", "猫咪")))
        assertEquals("tool", nested.getString("action"))
        assertEquals("read_xiaohongshu", nested.getString("tool"))

        val reply = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "tool")
                .put("args", JSONObject().put("name", "send_private_message")
                    .put("text", "我还想和你说句话。")))
        assertEquals("message", reply.getString("action"))
        assertEquals("我还想和你说句话。", reply.getString("text"))

        val nameless = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "tool")
                .put("args", JSONObject().put("keyword", "猫咪")))
        assertEquals("", nameless.optString("tool"))
    }

    @Test fun directFavoriteUsesRegisteredToolWithExactMessageId() {
        val direct = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "favorite_user_message")
                .put("messageId", "msg-user-1").put("reason", "我想留住她这句鼓励"))
        assertEquals("tool", direct.getString("action"))
        assertEquals("favorite_user_message", direct.getString("tool"))
        assertEquals("msg-user-1", direct.getJSONObject("args").getString("messageId"))
        assertEquals("我想留住她这句鼓励", direct.getJSONObject("args").getString("reason"))
        val nested = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "tool").put("tool", "favorite_user_message")
                .put("args", JSONObject().put("messageId", "msg-user-2")
                    .put("reason", "这句话对我很特别")))
        assertEquals("tool", nested.getString("action"))
        assertEquals("msg-user-2", nested.getJSONObject("args").getString("messageId"))
    }

    @Test fun genuineSilenceIsNeverReplacedWithMandatoryAction() {
        val original = JSONObject().put("action", "silent")
            .put("reason", "我现在只想一个人慢慢整理这段心情")
        val parsed = AutonomousActionWireProtocol.normalize(original)
        assertEquals("silent", parsed.getString("action"))
        assertEquals(original.getString("reason"), parsed.getString("reason"))
    }

    @Test fun directRegisteredToolActionKeepsItsRealExecutorAndParameters() {
        val scheduled = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "plan_personal_activity")
                .put("args", JSONObject().put("title", "读会书")
                    .put("dueAt", "2026-10-25T15:00:00+08:00")))
        assertEquals("tool", scheduled.getString("action"))
        assertEquals("plan_personal_activity", scheduled.getString("tool"))
        assertEquals("读会书", scheduled.getJSONObject("args").getString("title"))

        val screenshot = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "capture_screen")
                .put("args", JSONObject().put("purpose", "看看当前画面")))
        assertEquals("tool", screenshot.getString("action"))
        assertEquals("capture_screen", screenshot.getString("tool"))

        val unknown = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "invented_external_tool")
                .put("args", JSONObject().put("text", "假的")))
        assertEquals("invented_external_tool", unknown.getString("action"))
        assertEquals("", unknown.optString("tool"))
    }

    @Test fun explicitSpeechOrToolWithoutActionLabelDoesNotBecomeFalseSilence() {
        val words = AutonomousActionWireProtocol.normalize(
            JSONObject().put("text", "我还在呢")
                .put("steps", org.json.JSONArray().put(
                    JSONObject().put("tool", "control_music")
                        .put("args", JSONObject().put("command", "pause"))))
        assertEquals("message", words.getString("action"))
        assertEquals(1, words.getJSONArray("steps").length())

        val bubbles = AutonomousActionWireProtocol.normalize(
            JSONObject().put("bubbles", org.json.JSONArray().put("等一下").put("我试试。")))
        assertEquals("message", bubbles.getString("action"))

        val directTool = AutonomousActionWireProtocol.normalize(
            JSONObject().put("tool", "read_xiaohongshu")
                .put("args", JSONObject().put("keyword", "第五人格")))
        assertEquals("tool", directTool.getString("action"))
        assertEquals("read_xiaohongshu", directTool.getString("tool"))

        val thoughtsOnly = AutonomousActionWireProtocol.normalize(
            JSONObject().put("innerThought", "好想读书"))
        assertEquals("", thoughtsOnly.optString("action"))
    }
}
