package com.jiacimu.lulu

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Live2DSpokenMotionContractTest {
    private val motions = listOf("Gesture" to 0, "Gesture" to 1)
    private val text = "你回来啦！我刚在想你。不过我才没一直等呢。"
    private fun complete() = JSONObject("""{"beats":[
        {"anchor":"你回来啦","motion":{"group":"Gesture","index":0}},
        {"anchor":"我刚在想你","motion":{"group":"Gesture","index":1}},
        {"anchor":"不过我才没","motion":{"group":"Gesture","index":0}}]}""")

    @Test fun completeSameTurnScoreNeedsNoExtraModelRequest() = runBlocking {
        var requests = 0
        val result = Live2DSpokenMotionContract.resolve(text, complete(), motions) { _, _ ->
            requests++; null
        }
        assertEquals(0, requests)
        assertTrue(result.errors.isEmpty())
        assertFalse(result.repaired)
    }

    @Test fun openingOnlyMotionIsNotCoverageForLaterSentences() = runBlocking {
        val opening = JSONObject("""{"motion":{"group":"Gesture","index":0}}""")
        assertEquals(2, Live2DSpokenMotionContract.errors(text, opening, motions).size)
        var requests = 0
        val result = Live2DSpokenMotionContract.resolve(text, opening, motions) { errors, previous ->
            assertSame(opening, previous)
            requests++
            assertEquals(2, errors.size)
            complete()
        }
        assertEquals(1, requests)
        assertTrue(result.repaired)
        assertTrue(result.errors.isEmpty())
    }

    @Test fun invalidClipOrMissingMetadataNeverInventsMotionAfterTwoCorrections() = runBlocking {
        var requests = 0
        val original = complete().also {
            it.getJSONArray("beats").getJSONObject(1).getJSONObject("motion").put("index", 99)
        }
        val result = Live2DSpokenMotionContract.resolve(text, original, motions) { _, _ ->
            requests++; JSONObject("""{"performance":{"movement":"nod"}}""")
        }
        assertEquals(2, requests)
        assertFalse(result.repaired)
        assertSame(original, result.chosen)
        assertTrue(result.errors.isNotEmpty())
        val missing = Live2DSpokenMotionContract.resolve(text, null, motions) { _, _ -> null }
        assertNull(missing.chosen)
        assertTrue(missing.errors.isNotEmpty())
    }

    @Test fun secondCorrectionReceivesRejectedPlanAndPreservesSpeech() = runBlocking {
        fun score(index: Int) = JSONObject().put("speech", org.json.JSONArray().put(
            JSONObject().put("id", "s1").put("text", text)
                .put("motion", JSONObject().put("group", "Gesture").put("index", index))
                .put("transition", "blend")))
        val rejected = score(99)
        var requests = 0
        val result = Live2DSpokenMotionContract.resolve(text, null, motions) { errors, previous ->
            requests++
            if (requests == 1) {
                assertNull(previous)
                rejected
            } else {
                assertSame(rejected, previous)
                assertEquals(1, errors.size)
                score(0)
            }
        }
        assertEquals(2, requests)
        assertTrue(result.repaired)
        assertTrue(result.errors.isEmpty())
        assertEquals(text, Live2DDialoguePlan.spokenText(result.chosen!!))
    }

    @Test fun modelsWithoutMotionResourcesDoNotBuyFutileCorrections() = runBlocking {
        val result = Live2DSpokenMotionContract.resolve(text, null, emptyList()) { _, _ ->
            fail("No callable Motion3 exists"); null
        }
        assertTrue(result.errors.isEmpty())
    }

    @Test fun delayedAndMidSentenceBeatsCannotPretendToCoverSentenceStart() {
        val plan = complete()
        plan.getJSONArray("beats").getJSONObject(1).put("atMs", 100)
        plan.getJSONArray("beats").getJSONObject(2).put("anchor", "我才没")
        assertEquals(2, Live2DSpokenMotionContract.errors(text, plan, motions).size)
    }

    @Test fun voiceTagsStayWithAudibleSentenceAndDoNotCauseSpuriousRepairs() {
        val tagged = "[warmly]你回来啦！[softly]我刚在想你。[laughs]不过我才没一直等呢。"
        assertTrue(Live2DSpokenMotionContract.errors(tagged, complete(), motions).isEmpty())
        val plan = Live2DSpeechAnchorPlan.build(tagged,
            listOf("你回来啦", "我刚在想你", "不过我才没"))
        assertEquals(listOf("[warmly]你回来啦！", "[softly]我刚在想你。",
            "[laughs]不过我才没一直等呢。"), plan.parts)
        assertEquals(0, plan.segmentForAnchor["你回来啦"])
        assertEquals(1, plan.segmentForAnchor["我刚在想你"])
        assertEquals(2, plan.segmentForAnchor["不过我才没"])
        assertEquals(tagged, plan.parts.joinToString(""))
    }
}
