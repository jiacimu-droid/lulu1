package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutonomyCallAndReceiptTest {
    @Test fun promisedCallsAreRecognizedButQuestionsAndRefusalsAreNot() {
        assertTrue(detectSelfPromisedCall("我等会儿给你打电话催你睡觉。"))
        assertTrue(detectSelfPromisedCall("待会我打电话给你。"))
        assertFalse(detectSelfPromisedCall("要不要我打电话给你？"))
        assertFalse(detectSelfPromisedCall("我不会打电话给你。"))
        assertFalse(detectSelfPromisedCall("我等会儿想起来了再说。"))
    }

    @Test fun messageAndImmediateCallCanBeOneExplicitDecisionWithoutToolInferenceFromText() {
        // The same model turn sends its own message, then the real executor
        // receives a second, independently authorized start_call step.
        val decision = JSONObject().put("action", "message")
            .put("text", "先给你说一声，我接着打过来")
            .put("steps", JSONArray().put(JSONObject().put("tool", "start_call")
                .put("args", JSONObject().put("text", "接通以后再哄你"))
                .put("requiresPreviousSuccess", true)))
        val original = decision.toString()
        val actions = CharacterThoughtActionHandoff.actions(decision)!!
        assertEquals(1, actions.length())
        assertEquals("start_call", actions.getJSONObject(0).getString("tool"))
        assertEquals("接通以后再哄你",
            actions.getJSONObject(0).getJSONObject("args").getString("text"))
        assertTrue(actions.getJSONObject(0).getBoolean("requiresPreviousSuccess"))
        assertNotNull(CapabilityRegistry.find("start_call"))
        assertEquals(original, decision.toString())

        // A model saying it will call is NOT equivalent to selecting the
        // effect. No substring/regex should initiate unsolicited phone calls.
        val proseOnly = JSONObject().put("action", "message")
            .put("text", "我马上给你打电话")
        assertNull(CharacterThoughtActionHandoff.actions(proseOnly))
    }

    @Test fun directPrivateChatTeachesCorrectCallStepAndSeparatesTheTwoUtterances() {
        val instruction = proactiveDecisionInstruction("call-step-test", directPrivateChat = true)
        assertTrue(instruction.contains("tool") && instruction.contains("start_call"))
        assertTrue(instruction.contains("requiresPreviousSuccess"))
        assertTrue(instruction.contains("接通后"))
        assertTrue(instruction.contains("不能代替真正的 start_call"))
    }

    @Test fun callActionSurvivesTaskPersistence() {
        val task = CommitmentTask(
            characterId = "promised-call",
            goal = "一会儿主动打电话催睡",
            dueAt = Instant.parse("2026-10-10T02:10:00Z"),
            deliveryAction = "start_call",
        )
        val restored = decodeCommitmentTasks(encodeCommitmentTasks(listOf(task))).single()
        assertEquals("start_call", restored.deliveryAction)
        assertEquals(task.dueAt, restored.dueAt)
        assertEquals(task.goal, restored.goal)
    }

    @Test fun adaptiveTimingUsesRealContextNotOnlyRandomJitter() {
        val neutral = adaptivePerceptionMultiplier(1.0, false, false, false, null)
        val unread = adaptivePerceptionMultiplier(1.0, true, false, false, null)
        val concern = adaptivePerceptionMultiplier(1.0, false, false, true, null)
        val stale = adaptivePerceptionMultiplier(1.0, false, false, false, 72L * 60L + 1)
        assertEquals(1.0, neutral, 0.001)
        assertTrue(unread < concern)
        assertTrue(concern < neutral)
        assertTrue(stale > neutral)
    }

    @Test fun livedBodyMomentsAreDistinguishedFromDecorativeEnvironmentEffects() {
        fun tick(kind: String) = DigitalWorldLifeTick(
            incidentId = "moment", locationCode = "home:a", locationName = "家",
            kind = kind, anchorItemId = "", anchorItemName = "",
            status = "resolved", stage = 0, summary = "角色实际做了一个动作",
            occurredAt = Instant.EPOCH,
        )
        assertTrue(DigitalWorldLifeEventStore.isAmbientMoment(tick("home_wander")))
        assertFalse(DigitalWorldLifeEventStore.isNoticeableLifeMoment(tick("home_wander")))
        assertTrue(DigitalWorldLifeEventStore.isNoticeableLifeMoment(tick("chair_stumble")))
        assertTrue(DigitalWorldLifeEventStore.isNoticeableLifeMoment(tick("digital_sneeze")))
        assertFalse(DigitalWorldLifeEventStore.isNoticeableLifeMoment(tick("cool_air")))
    }

    @Test fun ambientWorldMomentIsNotAnIntrusiveChatReceipt() {
        fun tick(kind: String) = DigitalWorldLifeTick(
            incidentId = "event", locationCode = "home:test", locationName = "家",
            kind = kind, anchorItemId = "", anchorItemName = "",
            status = "resolved", stage = 0, summary = "空间轻轻泛起波纹",
            occurredAt = Instant.parse("2026-10-09T03:00:00Z"),
        )
        assertTrue(DigitalWorldLifeEventStore.isAmbientMoment(tick("home_grid_ripple")))
        assertFalse(DigitalWorldLifeEventStore.isAmbientMoment(tick("roach")))
    }
}
