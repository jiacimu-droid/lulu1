package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterMomentSnapshotIdeasTest {
    @Test fun moreThanThreeDistinctActuallyAuthoredIdeasStayVisible() {
        val entries = JSONArray().put("想看看那本书").put("想约好友玩游戏")
            .put("想试一种新口味").put("想整理房间").put("想问问朋友近况")
        val visible = CharacterMomentSnapshot.reflection(null,
            appraisal = JSONObject().put("privateIdeas", entries))
        assertEquals(5, visible.lines().size)
        assertTrue(visible.contains("想问问朋友近况"))
    }

    @Test fun noEvidenceDoesNotSynthesizePrivateIdeas() {
        assertEquals("", CharacterMomentSnapshot.reflection(null, appraisal = JSONObject()))
    }
    @Test fun privateNegationIsNotHiddenByItsOppositeOutwardStatement() {
        val outward = "想继续和她聊下去"
        val negative = "不想继续和她聊下去"
        val appraisal = JSONObject().put("privateIdeas",
            JSONArray().put(outward).put(negative))
        assertEquals(2, CharacterMomentSnapshot.reflection(null,
            appraisal = appraisal).lines().size)
        assertEquals(negative, CharacterMomentSnapshot.reflection(null,
            appraisal = appraisal, outward = outward))
    }

    @Test fun structuredPrivateIdeaShowsOnlyThoughtWhileKeepingOriginalActionDecision() {
        val action = JSONObject().put("tool", "control_volume")
            .put("args", JSONObject().put("action", "status"))
        val authored = JSONObject().put("thought", "夸她吃完早餐，再认真看工具如何操作")
            .put("nextAction", action)
        val decision = JSONObject().put("appraisal",
            JSONObject().put("privateIdeas", JSONArray().put(authored)))
        val visible = CharacterMomentSnapshot.reflection(null,
            appraisal = decision.getJSONObject("appraisal"))
        assertEquals("夸她吃完早餐，再认真看工具如何操作", visible)
        assertEquals("control_volume", authored.getJSONObject("nextAction").getString("tool"))
        assertEquals("control_volume",
            CharacterThoughtActionHandoff.actions(decision)?.optJSONObject(0)?.optString("tool"))
    }

    @Test fun serializedAndPreviouslyTruncatedMetadataNeverLeaksIntoPresence() {
        val raw = """{"thought":"早餐吃得真快，想研究下工具","nextAction":null}"""
        val legacy = raw.dropLast(3) // Older 64-char projections could cut after thought.
        assertEquals("早餐吃得真快，想研究下工具",
            CharacterMomentSnapshot.privateIdeaText(raw))
        assertEquals("早餐吃得真快，想研究下工具",
            CharacterMomentSnapshot.visibleReflection(legacy))
        assertEquals("", CharacterMomentSnapshot.visibleReflection("""{"thought":"没写完"""))
        val visible = CharacterMomentSnapshot.reflection(null,
            appraisal = JSONObject().put("privateIdeas", JSONArray().put(raw)))
        assertEquals("早餐吃得真快，想研究下工具", visible)
    }

    @Test fun authoredIdeasRemainCompleteAndCondensedHistoryContainsNoActionMetadata() {
        val longThought = "想认真弄懂这个问题的前因后果" + "而不是只看一眼".repeat(35)
        val ideas = JSONArray()
            .put(JSONObject().put("thought", longThought).put("nextAction", JSONObject.NULL))
            .put("但也想先陪她聊聊天")
        val appraisal = JSONObject().put("privateIdeas", ideas)
        val reflection = CharacterMomentSnapshot.reflection(null, appraisal = appraisal)
        assertEquals(listOf(longThought, "但也想先陪她聊聊天"), reflection.lines())
        val condensed = CharacterDeliberationContext.conciseAppraisal(appraisal)
        assertEquals(longThought, condensed?.optJSONArray("privateIdeas")?.optString(0))
        assertEquals("但也想先陪她聊聊天", condensed?.optJSONArray("privateIdeas")?.optString(1))
        assertTrue(!condensed.toString().contains("nextAction"))
    }

}
