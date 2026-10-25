package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterThoughtActionHandoffTest {
    private fun action(tool: String = "web_search") = JSONObject().put("tool", tool)
        .put("args", JSONObject().put("query", "真实待核对的问题"))
    private fun concern(item: JSONObject) = JSONObject().put("action", "silent")
        .put("appraisal", JSONObject().put("pendingConcerns", JSONArray().put(item)))

    @Test fun aPossibleStepInProseNeverExecutesByItself() {
        assertNull(CharacterThoughtActionHandoff.actions(concern(JSONObject()
            .put("possibleNextStep", "明天下午三点搜搜资料"))))
    }

    @Test fun chosenConcernActionJoinsTheSameReplysExecutorWithItsOwnLink() {
        val decision = concern(JSONObject().put("threadId", "real-concern").put("nextAction", action()))
            .put("action", "message").put("text", "我去核对一下。")
            .put("steps", JSONArray().put(action("write_journal")))
        val before = decision.toString()
        val steps = CharacterThoughtActionHandoff.actions(decision)!!
        assertEquals(2, steps.length())
        assertEquals("write_journal", steps.getJSONObject(0).getString("tool"))
        assertEquals("web_search", steps.getJSONObject(1).getString("tool"))
        assertEquals("real-concern", steps.getJSONObject(1).getString("concernId"))
        assertEquals(before, decision.toString())
    }

    @Test fun aChosenLaterStepUsesTheExistingScheduleAndKeepsTheOriginalGoal() {
        val scheduled = JSONObject().put("tool", "plan_personal_activity").put("args", JSONObject()
            .put("title", "继续核对资料").put("intent", "想弄明白原问题")
            .put("dueAt", "2026-11-01T06:00:00Z").put("activityAction", "learn_topic")
            .put("activityArgs", JSONObject().put("op", "study").put("goalId", "original-goal")))
        val decision = JSONObject().put("innerLife", JSONObject().put("motives", JSONArray()
            .put(JSONObject().put("op", "revise").put("id", "original-motive").put("nextAction", scheduled))))
        val step = CharacterThoughtActionHandoff.actions(decision)!!.getJSONObject(0)
        assertEquals("plan_personal_activity", step.getString("tool"))
        assertEquals("original-motive", step.getJSONObject("args").getString("motiveId"))
        assertEquals("original-goal", step.getJSONObject("args").getJSONObject("activityArgs").getString("goalId"))
    }

    @Test fun duplicateDeclarationsDoNotDoubleExecuteAndCancelledWishesDoNotSpawnActions() {
        val decision = concern(JSONObject().put("threadId", "c").put("nextAction", action()))
            .put("steps", JSONArray().put(action()))
        val steps = CharacterThoughtActionHandoff.actions(decision)!!
        assertEquals(1, steps.length())
        assertEquals("c", steps.getJSONObject(0).getString("concernId"))
        val paused = JSONObject().put("innerLife", JSONObject().put("motives", JSONArray()
            .put(JSONObject().put("op", "pause").put("nextAction", action()))))
        assertNull(CharacterThoughtActionHandoff.actions(paused))
        assertNull(CharacterThoughtActionHandoff.actions(concern(JSONObject()
            .put("status", "settled").put("nextAction", action()))))
        assertEquals(1, CharacterThoughtActionHandoff.actions(JSONObject().put("action", "tool")
            .put("tool", "web_search").put("args", action().getJSONObject("args"))
            .put("nextAction", action()).put("steps", JSONArray().put(action("write_journal"))))!!.length())
    }

    @Test fun stepsAndSideActionsBothExecuteOnceAndKeepTheirIndependentReceipts() {
        val search = action()
        val journal = JSONObject().put("tool", "write_journal")
            .put("args", JSONObject().put("title", "我今天的发现"))
            .put("requiresPreviousSuccess", true)
        val decision = JSONObject().put("action", "message").put("text", "我也想把这件事记下来")
            .put("steps", JSONArray().put(search))
            .put("sideActions", JSONArray().put(journal)
                .put(JSONObject(search.toString()).put("concernId", "original-concern")))
        val original = decision.toString()

        val steps = CharacterThoughtActionHandoff.actions(decision)!!
        assertEquals(2, steps.length())
        assertEquals("web_search", steps.getJSONObject(0).getString("tool"))
        assertEquals("original-concern", steps.getJSONObject(0).getString("concernId"))
        assertEquals("write_journal", steps.getJSONObject(1).getString("tool"))
        assertTrue(steps.getJSONObject(1).getBoolean("requiresPreviousSuccess"))
        assertEquals(original, decision.toString())
    }


    @Test fun threeFriendlyLifeStepsFromOneCompletedMealAreNotBlankUnknownTools() {
        // Regression for: a real sandwich receipt followed by three "未知工具"
        // steps even though their explicit action labels describe valid tools.
        val afterMeal = JSONObject().put("action", "digital_world")
            .put("worldAction", "use_location").put("activityId", "order_drink")
            .put("requiresPreviousSuccess", true)
        val drink = JSONObject().put("action", "use_location")
            .put("activityId", "slow_drink")
            .put("requiresPreviousSuccess", true)
        val share = JSONObject().put("action", "message")
            .put("text", "刚才吃了三明治，想跟你聊聊。")
            .put("requiresPreviousSuccess", true)
        val decision = JSONObject().put("action", "digital_world")
            .put("worldAction", "use_location").put("activityId", "cafe_consume_sandwich")
            .put("steps", JSONArray().put(afterMeal).put(drink))
            .put("sideActions", JSONArray().put(share))
        val original = decision.toString()
        val steps = CharacterThoughtActionHandoff.actions(decision)!!
        assertEquals(3, steps.length())
        assertEquals(listOf("digital_world_action", "digital_world_action", "send_private_message"),
            (0 until steps.length()).map { steps.getJSONObject(it).getString("tool") })
        assertEquals("use_location", steps.getJSONObject(0).getJSONObject("args").getString("worldAction"))
        assertEquals("order_drink", steps.getJSONObject(0).getJSONObject("args").getString("activityId"))
        assertEquals("slow_drink", steps.getJSONObject(1).getJSONObject("args").getString("activityId"))
        assertEquals("刚才吃了三明治，想跟你聊聊。",
            steps.getJSONObject(2).getJSONObject("args").getString("text"))
        assertTrue((0 until steps.length()).all { steps.getJSONObject(it).getBoolean("requiresPreviousSuccess") })
        assertEquals(original, decision.toString())
    }

    @Test fun nestedThoughtActionsAndDifferentSpellingsDeduplicateOneEffect() {
        val coffee = JSONObject().put("action", "digital_world")
            .put("worldAction", "use_location").put("activityId", "slow_drink")
        val duplicate = JSONObject().put("tool", "digital_world_action")
            .put("args", JSONObject().put("worldAction", "use_location")
                .put("activityId", "slow_drink"))
        val decision = JSONObject().put("steps", JSONArray().put(coffee))
            .put("sideActions", JSONArray().put(duplicate))
            .put("appraisal", JSONObject().put("pendingConcerns", JSONArray().put(
                JSONObject().put("threadId", "drink-real")
                    .put("nextAction", JSONObject(coffee.toString())))))
        val steps = CharacterThoughtActionHandoff.actions(decision)!!
        assertEquals(1, steps.length())
        assertEquals("digital_world_action", steps.getJSONObject(0).getString("tool"))
        assertEquals("drink-real", steps.getJSONObject(0).getString("concernId"))
    }

    @Test fun proseAndNamelessStepsStillCannotTurnIntoFabricatedToolCalls() {
        val wishes = JSONObject().put("nextAction",
            JSONObject().put("why", "去领杯水，待会找主人聊天"))
            .put("steps", JSONArray().put(JSONObject().put("description", "想喝水")))
        val steps = CharacterThoughtActionHandoff.actions(wishes)!!
        assertEquals(2, steps.length())
        assertEquals("", steps.getJSONObject(0).optString("tool"))
        assertEquals("", steps.getJSONObject(1).optString("tool"))
    }

    @Test fun separateInvalidSideActionsMustNotCollapseIntoOneBlankTool() {
        val decision = JSONObject()
            .put("steps", JSONArray().put(JSONObject().put("description", "先找温水")))
            .put("sideActions", JSONArray()
                .put(JSONObject().put("description", "去找别人聊天"))
                .put(JSONObject().put("description", "想发朋友圈")))
        val original = decision.toString()
        val actions = CharacterThoughtActionHandoff.actions(decision)!!
        assertEquals(3, actions.length())
        assertTrue((0 until actions.length()).all {
            actions.getJSONObject(it).optString("tool").isBlank()
        })
        assertEquals(original, decision.toString())
    }

    @Test fun malformedChosenActionsReachTheExecutorAsFailuresInsteadOfBecomingImaginedSuccess() {
        val steps = CharacterThoughtActionHandoff.actions(JSONObject().put("nextAction", JSONObject()
            .put("tool", "unknown-tool")))!!
        assertEquals("unknown-tool", steps.getJSONObject(0).getString("tool"))
    }
}
