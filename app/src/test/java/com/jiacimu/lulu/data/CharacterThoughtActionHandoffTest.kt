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

    @Test fun malformedChosenActionsReachTheExecutorAsFailuresInsteadOfBecomingImaginedSuccess() {
        val steps = CharacterThoughtActionHandoff.actions(JSONObject().put("nextAction", JSONObject()
            .put("tool", "unknown-tool")))!!
        assertEquals("unknown-tool", steps.getJSONObject(0).getString("tool"))
    }
}
