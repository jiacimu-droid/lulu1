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

    @Test fun genuineSilenceIsNeverReplacedWithMandatoryAction() {
        val original = JSONObject().put("action", "silent")
            .put("reason", "我现在只想一个人慢慢整理这段心情")
        val parsed = AutonomousActionWireProtocol.normalize(original)
        assertEquals("silent", parsed.getString("action"))
        assertEquals(original.getString("reason"), parsed.getString("reason"))
    }
}
