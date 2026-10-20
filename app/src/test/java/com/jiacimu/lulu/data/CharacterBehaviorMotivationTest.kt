package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterBehaviorMotivationTest {
    @Test fun briefEverydayWishIsEnoughWithoutArtificialEmotionOrInnerMonologue() {
        val basis = CharacterBehaviorMotivation.resolve(
            "digital_world", "屋里待得有点闷，想出去吹吹风",
        )
        assertNotNull(basis)
        assertEquals("屋里待得有点闷，想出去吹吹风", basis!!.reason)
        assertEquals("chosen_reason", basis.source)
        assertEquals("", basis.feeling)
        assertEquals("", basis.understanding)
    }

    @Test fun absenceOfSeparateBasisIsAuditableButNotAnActionPermissionGate() {
        assertFalse(CharacterBehaviorMotivation.hasExplicitBasis("digital_world"))
        assertFalse(CharacterBehaviorMotivation.hasExplicitBasis("reading", "未说明"))
        assertFalse(CharacterBehaviorMotivation.hasExplicitBasis("publish_moment"))
        assertFalse(CharacterBehaviorMotivation.hasExplicitBasis("send_private_message"))
        // An undecided quiet turn remains possible; it is not a pretend action.
        assertTrue(CharacterBehaviorMotivation.hasExplicitBasis("silent"))
    }

    @Test fun responseAimAndFeltImpulseBothCountAsPersonalReasons() {
        val appraisal = JSONObject().put("meaning", "听见她说累了")
            .put("responseAim", "想轻声回应，陪她放松")
        val spoken = CharacterBehaviorMotivation.resolve("reply", appraisal = appraisal)
        assertEquals("想轻声回应，陪她放松", spoken?.reason)
        assertEquals("听见她说累了", spoken?.trace()?.optString("understanding"))

        val innerLife = JSONObject().put("emotion",
            JSONObject().put("feeling", "有点无聊").put("action", "digital_world").put("impulse", "想去外面逛逛"))
        val outdoors = CharacterBehaviorMotivation.resolve("digital_world", innerLife = innerLife)
        assertEquals("想去外面逛逛", outdoors?.reason)
        assertEquals("有点无聊", outdoors?.feeling)
    }

    @Test fun silentDoesNotNeedNewHeartVoiceAndConcreteOwnActivityCanBeExecuted() {
        val appraisal = JSONObject().put("ownActivity", JSONObject()
            .put("action", "reading").put("whyNow", "刚才那一章很有意思，我想继续读"))
        val basis = CharacterBehaviorMotivation.resolve("reading", appraisal = appraisal)
        assertEquals("刚才那一章很有意思，我想继续读", basis?.reason)
        assertTrue(CharacterBehaviorMotivation.hasExplicitBasis(
            "reading", appraisal = appraisal))
    }

    @Test fun onePlannedActivityMustNotExplainAnUnrelatedAction() {
        val unrelated = JSONObject().put("responseAim", "想给她回消息")
            .put("ownActivity", JSONObject().put("action", "reading")
                .put("whyNow", "想继续读下一章"))
        assertNull(CharacterBehaviorMotivation.resolve("digital_world", appraisal = unrelated))
        assertNotNull(CharacterBehaviorMotivation.resolve("reply", appraisal = unrelated))
        assertNotNull(CharacterBehaviorMotivation.resolve("reading", appraisal = unrelated))
    }

    @Test fun speechIntentCanExplainOrdinaryReplyButCannotJustifyUnrelatedTool() {
        assertEquals("想问问她今天怎么样",
            CharacterBehaviorMotivation.resolve("reply", speechIntent = "想问问她今天怎么样")?.reason)
        assertNull(CharacterBehaviorMotivation.resolve(
            "digital_world", speechIntent = "想问问她今天怎么样"))
    }
    @Test fun unrelatedEmotionalImpulseCannotAuthorizeOtherActions() {
        val life = JSONObject().put("emotion", JSONObject()
            .put("action", "reading").put("impulse", "好奇结局，想继续读书"))
        assertNull(CharacterBehaviorMotivation.resolve("publish_moment", innerLife = life))
        assertNotNull(CharacterBehaviorMotivation.resolve("read_book", innerLife = life))
        val unscoped = JSONObject().put("emotion", JSONObject().put("impulse", "想休息"))
        assertNull(CharacterBehaviorMotivation.resolve("start_call", innerLife = unscoped))
    }

    @Test fun actualStickerAndReadingActionsUseTheSamePersonalMotiveContract() {
        val social = JSONObject().put("responseAim", "想用表情告诉她我很开心")
        assertNotNull(CharacterBehaviorMotivation.resolve("sticker", appraisal = social))
        assertNotNull(CharacterBehaviorMotivation.resolve("group_sticker", appraisal = social))
        val reading = JSONObject().put("ownActivity", JSONObject()
            .put("action", "read_book").put("whyNow", "刚才那一章勾起了好奇"))
        assertNotNull(CharacterBehaviorMotivation.resolve("reading", appraisal = reading))
        assertNull(CharacterBehaviorMotivation.resolve("digital_world", appraisal = reading))
    }


    @Test fun selfChosenMessageCanBeDeliveredWithoutDuplicatingItsReason() {
        val basis = CharacterBehaviorMotivation.resolve(
            "message", chosenUtterance = "这本书的结局让我有点意外，想跟你说说",
        )
        assertNotNull(basis)
        assertEquals("chosen_utterance", basis?.source)
        assertTrue(basis!!.reason.contains("这本书的结局"))
        assertTrue(CharacterBehaviorMotivation.hasExplicitBasis(
            "message", chosenUtterance = "这本书的结局让我有点意外"))
        assertTrue(CharacterBehaviorMotivation.hasExplicitBasis(
            "group_message", chosenUtterance = "我也想说两句"))
        assertTrue(CharacterBehaviorMotivation.hasExplicitBasis(
            "kaomoji", chosenUtterance = "(๑˃ᴗ˂)"))
        assertTrue(CharacterBehaviorMotivation.hasExplicitBasis(
            "message", chosenUtterance = "嗯"))
    }

    @Test fun authoredToolMessageIsAnExplicitSpeechChoiceNotAMissingMotive() {
        assertTrue(CharacterBehaviorMotivation.hasExplicitBasis(
            "send_private_message",
            chosenUtterance = "刚刚看到你的话啦，我想回应一下"))
        assertTrue(CharacterBehaviorMotivation.hasExplicitBasis(
            "send_group_message",
            chosenUtterance = "我有个想法"))
        assertFalse(CharacterBehaviorMotivation.hasExplicitBasis(
            "send_private_message", chosenUtterance = ""))
        assertFalse(CharacterBehaviorMotivation.hasExplicitBasis(
            "start_call", chosenUtterance = "想跟你说话"))
        assertFalse(CharacterBehaviorMotivation.hasExplicitBasis(
            "publish_moment", chosenUtterance = "说话内容"))
    }

    @Test fun aChosenMessageMustNotAuthorizeAnotherTypeOfAction() {
        for (action in listOf("reading", "digital_world", "publish_moment", "start_call",
                "write_journal", "favorite_user_message", "read_xiaohongshu")) {
            assertFalse("$action cannot borrow a chat message's intent",
                CharacterBehaviorMotivation.hasExplicitBasis(
                    action, chosenUtterance = "刚刚我想说两句"))
        }
        assertFalse(CharacterBehaviorMotivation.hasExplicitBasis(
            "message", chosenUtterance = ""))
        assertFalse(CharacterBehaviorMotivation.hasExplicitBasis(
            "message", chosenUtterance = "null"))
        assertFalse(CharacterBehaviorMotivation.hasExplicitBasis(
            "message", chosenUtterance = "..."))
    }


}
