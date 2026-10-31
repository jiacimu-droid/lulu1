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

    @Test fun digitalWorldToolCopiesAuthoredFurnitureFieldsWithoutInventingAny() {
        val supplied = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "tool").put("tool", "digital_world_action")
                .put("worldAction", "build_home_item")
                .put("itemType", "shelf").put("itemName", "原木书架")
                .put("appearance", "浅橡木的开放式书架")
                .put("position", "靠墙"))
        assertEquals("digital_world", supplied.optString("action"))
        assertEquals("原木书架", supplied.optJSONObject("args")?.optString("name"))
        assertEquals("浅橡木的开放式书架", supplied.optJSONObject("args")?.optString("appearance"))
        assertTrue(AutonomousActionWireProtocol.missingWorldBuildFields(supplied).isEmpty())

        val incomplete = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "digital_world").put("worldAction", "build_home_item")
                .put("itemType", "lamp").put("position", "窗台"))
        assertEquals(listOf("name", "appearance"),
            AutonomousActionWireProtocol.missingWorldBuildFields(incomplete))
    }

    @Test fun incompleteFurnitureStepCanOnlyBeRepairedWithExplicitModelAuthoredValues() {
        val chat = JSONObject().put("tool", "send_private_message")
            .put("args", JSONObject().put("text", "我想在家里放个小台灯"))
        val build = JSONObject().put("tool", "digital_world_action")
            .put("args", JSONObject().put("worldAction", "build_home_item")
                .put("itemType", "lamp").put("position", "桌上"))
        val steps = org.json.JSONArray().put(chat).put(build)
        assertEquals(listOf("steps[1].name", "steps[1].appearance"),
            AutonomousActionWireProtocol.missingWorldBuildStepFields(steps))
        assertNull(AutonomousActionWireProtocol.repairWorldBuildSteps(steps,
            JSONObject().put("repairs", org.json.JSONArray().put(
                JSONObject().put("index", 1).put("name", "窗边小灯")))))
        val fixed = AutonomousActionWireProtocol.repairWorldBuildSteps(steps,
            JSONObject().put("repairs", org.json.JSONArray().put(
                JSONObject().put("index", 1).put("name", "窗边小灯")
                    .put("appearance", "乳白玻璃灯罩和木底座"))))!!
        assertTrue(AutonomousActionWireProtocol.missingWorldBuildStepFields(fixed).isEmpty())
        assertEquals(chat.toString(), fixed.getJSONObject(0).toString())
        assertEquals("lamp", fixed.getJSONObject(1).getJSONObject("args").getString("itemType"))
        assertEquals("窗边小灯", fixed.getJSONObject(1).getJSONObject("args").getString("name"))
        assertEquals("", steps.getJSONObject(1).getJSONObject("args").optString("name"))
    }

    @Test fun misplacedTopLevelWorldStepFieldsReachTheSameExecutorArguments() {
        val step = AutonomousActionWireProtocol.normalizeStep(
            JSONObject().put("action", "build_home_item")
                .put("itemType", "shelf").put("name", "原木书架")
                .put("appearance", "木制开放式")
                .put("position", "靠墙"))
        assertEquals("digital_world_action", step.getString("tool"))
        assertTrue(AutonomousActionWireProtocol.missingWorldBuildStepFields(
            org.json.JSONArray().put(step)).isEmpty())
        assertEquals("原木书架", step.getJSONObject("args").getString("name"))
    }

    @Test fun socialStepAndPrimaryToolKeepAuthoredIdsAndDecisionFields() {
        val acceptedStep = AutonomousActionWireProtocol.normalizeStep(
            JSONObject().put("tool", "respond_friend_request")
                .put("requestId", "incoming-42").put("decision", "accept"))
        assertEquals("respond_friend_request", acceptedStep.optString("tool"))
        assertEquals("incoming-42", acceptedStep.getJSONObject("args").getString("requestId"))
        assertEquals("accept", acceptedStep.getJSONObject("args").getString("decision"))

        val nestedWins = AutonomousActionWireProtocol.normalizeStep(
            JSONObject().put("tool", "respond_friend_request")
                .put("requestId", "stale-outer").put("decision", "accept")
                .put("args", JSONObject().put("requestId", "actual-incoming")
                    .put("decision", "decline")))
        assertEquals("actual-incoming", nestedWins.getJSONObject("args").getString("requestId"))
        assertEquals("decline", nestedWins.getJSONObject("args").getString("decision"))

        val socialActions = listOf(
            JSONObject().put("tool", "like_moment").put("postId", "real-post"),
            JSONObject().put("tool", "comment_moment").put("postId", "another-post")
                .put("text", "我自己的评论").put("replyToCommentId", "comment-1"),
            JSONObject().put("tool", "send_friend_request")
                .put("targetCharacterId", "resident:neighbor").put("greeting", "你好"),
            JSONObject().put("tool", "create_group_chat").put("groupName", "书友会")
                .put("memberIds", org.json.JSONArray().put("resident:neighbor")),
        )
        socialActions.forEach { original ->
            val normalized = AutonomousActionWireProtocol.normalizeStep(original)
            val args = normalized.getJSONObject("args")
            listOf("postId", "text", "replyToCommentId", "targetCharacterId",
                "greeting", "groupName", "memberIds").forEach { key ->
                if (original.has(key)) assertEquals(key, original.get(key).toString(),
                    args.get(key).toString())
            }
        }

        val primary = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "respond_friend_request")
                .put("requestId", "incoming-43").put("decision", "decline"))
        assertEquals("tool", primary.optString("action"))
        assertEquals("respond_friend_request", primary.optString("tool"))
        assertEquals("incoming-43", primary.getJSONObject("args").getString("requestId"))
        assertEquals("decline", primary.getJSONObject("args").getString("decision"))
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
                        .put("args", JSONObject().put("command", "pause")))))
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

        val emptyBubbles = AutonomousActionWireProtocol.normalize(
            JSONObject().put("bubbles", org.json.JSONArray().put(JSONObject().put("mood", "happy"))))
        assertEquals("", emptyBubbles.optString("action"))

        val caseVariant = AutonomousActionWireProtocol.normalize(
            JSONObject().put("action", "tool").put("tool", "WEB_SEARCH")
                .put("args", JSONObject().put("query", "第五人格")))
        assertEquals("tool", caseVariant.getString("action"))
        assertEquals("web_search", caseVariant.getString("tool"))
    }
    @Test fun nestedStepDecoderRecognizesExistingWorldAndAppCapabilities() {
        val world = AutonomousActionWireProtocol.normalizeStep(
            JSONObject().put("action", "digital_world")
                .put("worldAction", "use_location")
                .put("activityId", "order_drink"))
        assertEquals("digital_world_action", world.getString("tool"))
        assertEquals("use_location", world.getJSONObject("args").getString("worldAction"))
        assertEquals("order_drink", world.getJSONObject("args").getString("activityId"))

        val directVerb = AutonomousActionWireProtocol.normalizeStep(
            JSONObject().put("action", "use_location")
                .put("activityId", "slow_drink"))
        assertEquals("digital_world_action", directVerb.getString("tool"))
        assertEquals("use_location", directVerb.getJSONObject("args").getString("worldAction"))

        val nested = AutonomousActionWireProtocol.normalizeStep(
            JSONObject().put("action", "tool")
                .put("args", JSONObject().put("tool", "write_journal")
                    .put("title", "我的一天").put("content", "今天有些新收获")))
        assertEquals("write_journal", nested.getString("tool"))
        assertEquals("我的一天", nested.getJSONObject("args").getString("title"))

        val unknown = AutonomousActionWireProtocol.normalizeStep(
            JSONObject().put("action", "invented_magic")
                .put("text", "不能执行"))
        assertNull(CapabilityRegistry.find(unknown.getString("tool")))
        val nameless = AutonomousActionWireProtocol.normalizeStep(
            JSONObject().put("why", "只是想告诉她我吃完了"))
        assertEquals("", nameless.optString("tool"))
    }

}
