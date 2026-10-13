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

    @Test fun noSubjectiveBasisCannotExecuteBackgroundLifeAction() {
        assertFalse(CharacterBehaviorMotivation.permitsExternalAction("digital_world"))
        assertFalse(CharacterBehaviorMotivation.permitsExternalAction("reading", "未说明"))
        assertFalse(CharacterBehaviorMotivation.permitsExternalAction("publish_moment"))
        assertFalse(CharacterBehaviorMotivation.permitsExternalAction("send_private_message"))
        // An undecided quiet turn remains possible; it is not a pretend action.
        assertTrue(CharacterBehaviorMotivation.permitsExternalAction("silent"))
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
        assertTrue(CharacterBehaviorMotivation.permitsExternalAction(
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

}
