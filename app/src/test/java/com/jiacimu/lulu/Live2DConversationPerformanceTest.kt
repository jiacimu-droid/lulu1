package com.jiacimu.lulu

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Live2DConversationPerformanceTest {
    private fun plan(feeling: String = "", movement: String = "", strength: Double = .5, side: String = "right") =
        JSONObject().put("performance", JSONObject().put("feeling", feeling)
            .put("movement", movement).put("intensity", strength).put("side", side))

    private fun peak(request: JSONObject) = Live2DConversationPerformance.expand(request)
        .getJSONArray("beats").getJSONObject(1)

    @Test fun feelingAndMovementAreIndependentChoicesWithoutInventingExpressionFiles() {
        val thinking = peak(plan("thinking", "glance"))
        val smile = peak(plan("smile", "tilt"))
        val serious = peak(plan("serious"))
        assertTrue(thinking.getJSONObject("face").getDouble("gazeX") > 0)
        assertTrue(smile.getJSONObject("face").getDouble("smile") > .2)
        assertTrue(smile.getJSONObject("head").getDouble("roll") > 4)
        assertTrue(serious.getJSONObject("face").getDouble("smile") < 0)
        assertFalse(serious.has("head"))
        assertFalse(serious.has("expression"))
        val shy = Live2DConversationPerformance.expand(plan("shy", "turn"))
        assertTrue(shy.getJSONArray("beats").getJSONObject(1).has("body"))
        assertFalse(shy.has("expression")) // No automatic blush/heart/star or accessory switch.
        assertFalse(peak(plan(movement = "nod")).has("face"))
    }

    @Test fun intensityDirectionAndFiniteReleasePreserveAChoiceRatherThanRandomizingIt() {
        val soft = peak(plan("smile", "tilt", .25))
        val strong = peak(plan("smile", "tilt", .75))
        assertEquals(3.0, strong.getJSONObject("head").getDouble("roll") /
            soft.getJSONObject("head").getDouble("roll"), .0001)
        assertTrue(peak(plan("thinking", "glance", side = "left"))
            .getJSONObject("face").getDouble("gazeX") < 0)
        val beats = Live2DConversationPerformance.expand(plan(movement = "nod")).getJSONArray("beats")
        assertTrue(beats.getJSONObject(1).getJSONObject("head").getDouble("pitch") < 0)
        assertTrue(beats.getJSONObject(2).getJSONObject("head").getDouble("pitch") > 0)
        assertEquals(0.0, beats.getJSONObject(beats.length() - 1).getJSONObject("head").getDouble("pitch"), .0001)
        assertTrue(beats.getJSONObject(beats.length() - 1).getInt("durationMs") > 0)
        assertEquals(Live2DConversationPerformance.expand(plan("shy", "turn")).toString(),
            Live2DConversationPerformance.expand(plan("shy", "turn")).toString())
    }

    @Test fun explicitRigControlsAndLegacyBeatsKeepTheirMeaning() {
        val chosen = plan("amused", "tilt").put("expression", "my-authored-face")
            .put("face", JSONObject().put("smile", -.2))
            .put("head", JSONObject().put("roll", -8))
        val expanded = Live2DConversationPerformance.expand(chosen)
        assertEquals("my-authored-face", expanded.getString("expression"))
        for (i in 0 until expanded.getJSONArray("beats").length()) {
            val beat = expanded.getJSONArray("beats").getJSONObject(i)
            assertEquals(-.2, beat.getJSONObject("face").getDouble("smile"), .0001)
            assertEquals(-8.0, beat.getJSONObject("head").getDouble("roll"), .0001)
        }
        assertFalse(chosen.has("beats")) // Do not mutate the model's original decision evidence.
        val authored = plan("shy").put("beats", org.json.JSONArray()
            .put(JSONObject().put("atMs", 300).put("face", JSONObject().put("smile", .1))))
        assertSame(authored, Live2DConversationPerformance.expand(authored))
    }

    @Test fun omittedInvalidOrZeroIntentionDoesNotManufactureAHappyFace() {
        for (request in listOf(JSONObject(), plan("unknown", "dance"), plan("amused", "tilt", 0.0),
            plan("smile", strength = -1.0))) {
            assertSame(request, Live2DConversationPerformance.expand(request))
            assertFalse(request.has("beats"))
        }
        val note = Live2DConversationPerformance.capabilityNote(setOf("ParamBodyAngleX"))
        assertTrue(note.contains("turn("))
        assertFalse(note.contains("nod("))
        assertFalse(note.contains("tilt("))
        assertTrue(Live2DConversationPerformance.capabilityNote(emptySet()).contains("movement：无"))
    }
}
