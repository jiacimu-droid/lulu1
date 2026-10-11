package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AutonomousActionWireProtocolTest {
    @Test fun explicitReadingOrDiaryToolNeverFallsThroughToSilent() {
        val reading = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "read_book")
                .put("args", JSONObject().put("readingBookId", "book-real")))
        assertEquals("tool", reading.getString("action"))
        assertEquals("read_book", reading.getString("tool"))
        assertEquals("book-real", reading.getJSONObject("args").getString("readingBookId"))

        val journal = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "write_journal")
                .put("title", "想过的事").put("content", "我真的得重新想一想。"))
        assertEquals("tool", journal.getString("action"))
        assertEquals("write_journal", journal.getString("tool"))
        assertEquals("想过的事", journal.getJSONObject("args").getString("title"))

        val publicPost = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "publish_moment")
                .put("args", JSONObject().put("text", "刚才散步时遇到了一件好玩的事。")))
        assertEquals("tool", publicPost.getString("action"))
        assertEquals("publish_moment", publicPost.getString("tool"))
    }

    @Test fun bothNamedWorldResidentInteractionAndFriendlyActionCanCarryRealIDs() {
        val direct = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "digital_world_action")
                .put("args", JSONObject().put("worldAction", "interact_resident")
                    .put("itemId", "resident-1").put("activityId", "greet")))
        assertEquals("tool", direct.getString("action"))
        assertEquals("digital_world_action", direct.getString("tool"))
        assertEquals("resident-1", direct.getJSONObject("args").getString("itemId"))

        val friendly = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "digital_world")
                .put("args", JSONObject().put("worldAction", "interact_resident")
                    .put("itemId", "resident-2").put("activityId", "chat")))
        assertEquals("digital_world", friendly.getString("action"))
        assertEquals("resident-2", friendly.getString("itemId"))
        assertEquals("interact_resident", friendly.getString("worldAction"))
    }

    @Test fun genuineSilenceIsNeverReplacedWithMandatoryAction() {
        val original = JSONObject().put("action", "silent")
            .put("reason", "我现在只想一个人慢慢整理这段心情")
        val parsed = AutonomousActionWireProtocol.normalize(original)
        assertEquals("silent", parsed.getString("action"))
        assertEquals(original.getString("reason"), parsed.getString("reason"))
    }
}
