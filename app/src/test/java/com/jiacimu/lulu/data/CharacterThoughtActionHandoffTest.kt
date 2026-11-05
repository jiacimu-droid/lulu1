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

    @Test fun unknownPrimaryPreservesOnlyIndependentlyRegisteredSideSteps() {
        val chosen = JSONObject().put("action", "tool")
            .put("tool", "nonexistent_primary_tool")
            .put("steps", JSONArray()
                .put(JSONObject().put("tool", "publish_moment")
                    .put("requiresPreviousSuccess", false)
                    .put("args", JSONObject().put("text", "自己的真实生活")))
                .put(JSONObject().put("tool", "read_book")
                    .put("requiresPreviousSuccess", true)))
        val steps = CharacterThoughtActionHandoff.actions(chosen)
        assertTrue(CharacterThoughtActionHandoff.hasIndependentRegisteredStep(steps))
        assertEquals(2, steps!!.length())
        assertEquals("publish_moment", steps.getJSONObject(0).getString("tool"))
        assertEquals("nonexistent_primary_tool", chosen.getString("tool"))

        val onlyDependent = JSONArray().put(JSONObject()
            .put("tool", "publish_moment").put("requiresPreviousSuccess", true))
        assertFalse(CharacterThoughtActionHandoff.hasIndependentRegisteredStep(onlyDependent))
        assertFalse(CharacterThoughtActionHandoff.hasIndependentRegisteredStep(
            JSONArray().put(JSONObject().put("tool", "missing_side_tool")
                .put("requiresPreviousSuccess", false))))
        assertFalse(CharacterThoughtActionHandoff.hasIndependentRegisteredStep(
            JSONArray().put(JSONObject().put("description", "想发朋友圈"))))
        assertFalse(CharacterThoughtActionHandoff.hasIndependentRegisteredStep(null))
    }

    @Test fun chatVoiceBubblesAndIncomingCallStayTwoOrderedRealActions() {
        val decision = JSONObject()
            .put("action", "message")
            .put("bubbles", JSONArray()
                .put(JSONObject().put("text", "我想在电话里陪陪你").put("delivery", "voice"))
                .put(JSONObject().put("text", "接一下好不好").put("delivery", "voice")))
            .put("steps", JSONArray().put(JSONObject()
                .put("action", "call")
                .put("text", "喂，我在这里陪着你")
                .put("requiresPreviousSuccess", true)))
        val original = decision.toString()
        val steps = CharacterThoughtActionHandoff.actions(decision)!!
        assertEquals(1, steps.length())
        val call = steps.getJSONObject(0)
        assertEquals("start_call", call.getString("tool"))
        assertEquals("喂，我在这里陪着你", call.getJSONObject("args").getString("text"))
        assertTrue(call.getBoolean("requiresPreviousSuccess"))
        assertNotEquals(original, steps.toString())
        assertEquals(original, decision.toString())
        // A voiced promise alone is not consent to fabricate a tool action.
        val speechOnly = JSONObject().put("action", "message")
            .put("text", "我现在去打电话")
        assertNull(CharacterThoughtActionHandoff.actions(speechOnly))
    }

    @Test fun explicitlySelectedStickerAndRemarkShareRealToolPathWithoutTurningThoughtsIntoActions() {
        val decision = JSONObject().put("action", "message").put("text", "有个小惊喜")
            .put("steps", JSONArray().put(JSONObject()
                .put("action", "sticker").put("stickerId", "openmoji-1f63d")
                .put("requiresPreviousSuccess", true)))
            .put("innerLife", JSONObject().put("thoughts", JSONArray().put(JSONObject()
                .put("nextAction", JSONObject().put("tool", "set_user_remark")
                    .put("nickname", "佳辞")))))
        val original = decision.toString()
        val steps = CharacterThoughtActionHandoff.actions(decision)!!
        assertEquals(2, steps.length())
        assertEquals("send_private_sticker", steps.getJSONObject(0).getString("tool"))
        assertEquals("openmoji-1f63d",
            steps.getJSONObject(0).getJSONObject("args").getString("stickerId"))
        assertTrue(steps.getJSONObject(0).getBoolean("requiresPreviousSuccess"))
        assertEquals("set_user_remark", steps.getJSONObject(1).getString("tool"))
        assertEquals("佳辞", steps.getJSONObject(1).getJSONObject("args").getString("nickname"))
        assertEquals(original, decision.toString())

        val thoughtOnly = JSONObject().put("action", "silent")
            .put("innerThought", "有点想发亲亲猫猫表情包，回头再说")
            .put("innerLife", JSONObject().put("thoughts", JSONArray().put(JSONObject()
                .put("possibleNextStep", "再给她改个备注吧"))))
        assertNull(CharacterThoughtActionHandoff.actions(thoughtOnly))
    }

    @Test fun thoughtActionPreservesItsExplicitDependencyWhenAnEqualToolAlreadyExists() {
        for (dependentFirst in listOf(false, true)) {
            val first = action().put("requiresPreviousSuccess", dependentFirst)
            val next = action().put("requiresPreviousSuccess", !dependentFirst)
            val decision = JSONObject().put("action", "silent")
                .put("steps", JSONArray().put(first))
                .put("innerLife", JSONObject().put("thoughts", JSONArray()
                    .put(JSONObject().put("nextAction", next))))
            val original = decision.toString()
            val steps = CharacterThoughtActionHandoff.actions(decision)!!
            assertEquals(2, steps.length())
            assertEquals(dependentFirst, steps.getJSONObject(0)
                .getBoolean("requiresPreviousSuccess"))
            assertEquals(!dependentFirst, steps.getJSONObject(1)
                .getBoolean("requiresPreviousSuccess"))
            assertEquals(original, decision.toString())
        }
        // Identical dependency declarations still refer to one chosen effect.
        val repeated = action().put("requiresPreviousSuccess", true)
        val same = JSONObject().put("steps", JSONArray().put(repeated))
            .put("nextAction", JSONObject(repeated.toString()))
        assertEquals(1, CharacterThoughtActionHandoff.actions(same)!!.length())
    }

    @Test fun mirroredPrimaryToolKeepsNestedMotiveAndConcernWithoutDuplicateEffect() {
        val tool = action("publish_moment")
        val decision = JSONObject().put("action", "tool")
            .put("tool", "publish_moment")
            .put("args", JSONObject(tool.getJSONObject("args").toString()))
            .put("innerLife", JSONObject().put("motives", JSONArray().put(
                JSONObject().put("op", "start").put("ref", "fresh-goal")
                    .put("nextAction", JSONObject(tool.toString())))))
        val before = decision.toString()
        val normalized = AutonomousActionWireProtocol.normalize(decision)
        assertEquals("fresh-goal", normalized.getString("motiveRef"))
        assertNull(CharacterThoughtActionHandoff.actions(normalized))
        assertEquals(before, decision.toString())

        val concernDecision = JSONObject(decision.toString()).apply {
            remove("innerLife")
            put("appraisal", JSONObject().put("pendingConcerns", JSONArray().put(
                JSONObject().put("threadId", "concern-7").put("status", "open")
                    .put("nextAction", JSONObject(tool.toString())))))
        }
        val concernNormalized = AutonomousActionWireProtocol.normalize(concernDecision)
        assertEquals("concern-7", concernNormalized.getString("concernId"))
        assertNull(CharacterThoughtActionHandoff.actions(concernNormalized))

        val conflicting = JSONObject(decision.toString()).put("motiveRef", "other-goal")
        val conflictingNormalized = AutonomousActionWireProtocol.normalize(conflicting)
        assertEquals("other-goal", conflictingNormalized.getString("motiveRef"))
        val independent = CharacterThoughtActionHandoff.actions(conflictingNormalized)!!
        assertEquals(1, independent.length())
        assertEquals("fresh-goal", independent.getJSONObject(0).getString("motiveRef"))
    }

    @Test fun friendlyPrimaryToolAliasesKeepOwnershipAndCannotDoubleExecute() {
        val aliases = listOf(
            "publish_moment" to "moment",
            "send_private_message" to "message",
            "write_journal" to "journal",
            "start_call" to "call",
        )
        for ((tool, outwardAction) in aliases) {
            val effect = action(tool)
            val decision = JSONObject().put("action", "tool")
                .put("tool", tool)
                .put("args", JSONObject(effect.getJSONObject("args").toString()))
                .put("innerLife", JSONObject().put("motives", JSONArray().put(
                    JSONObject().put("op", "start").put("ref", "wish-$tool")
                        .put("nextAction", JSONObject(effect.toString())))))
                .put("appraisal", JSONObject().put("pendingConcerns", JSONArray().put(
                    JSONObject().put("threadId", "care-$tool")
                        .put("status", "open")
                        .put("nextAction", JSONObject(effect.toString())))))
            val before = decision.toString()
            val normalized = AutonomousActionWireProtocol.normalize(decision)
            assertEquals(tool, outwardAction, normalized.getString("action"))
            assertEquals("wish-$tool", normalized.getString("motiveRef"))
            assertEquals("care-$tool", normalized.getString("concernId"))
            assertNull("The same effect must not execute twice",
                CharacterThoughtActionHandoff.actions(normalized))
            assertEquals(before, decision.toString())
            // A genuinely different owner still produces an independent action.
            val conflicting = JSONObject(decision.toString()).put("motiveRef", "other-owner")
            val conflict = AutonomousActionWireProtocol.normalize(conflicting)
            assertEquals("other-owner", conflict.getString("motiveRef"))
            val side = CharacterThoughtActionHandoff.actions(conflict)!!
            assertEquals(1, side.length())
            assertEquals("wish-$tool", side.getJSONObject(0).getString("motiveRef"))
        }
    }

    @Test fun unrelatedToolFieldOnOrdinarySpeechDoesNotStealTheSecondaryAction() {
        val decision = JSONObject()
            .put("action", "message")
            .put("tool", "publish_moment")
            .put("text", "只是聊天，没有选择朋友圈主动作")
            .put("args", JSONObject().put("query", "真实待核对的问题"))
            .put("innerLife", JSONObject().put("motives", JSONArray().put(
                JSONObject().put("op", "start").put("ref", "real-next-action")
                    .put("nextAction", action("publish_moment")))))
        val original = decision.toString()
        val normalized = AutonomousActionWireProtocol.normalize(decision)
        assertEquals("message", normalized.getString("action"))
        assertFalse(AutonomousActionWireProtocol.isPrimaryToolChoice(normalized))
        val secondary = CharacterThoughtActionHandoff.actions(normalized)!!
        assertEquals(1, secondary.length())
        assertEquals("real-next-action", secondary.getJSONObject(0).getString("motiveRef"))
        assertEquals(original, decision.toString())
    }

    @Test fun mirroredPrimaryToolRetainsExplicitQuestionAndNeverExecutesTwice() {
        val tool = action("web_search")
        val question = JSONObject().put("threadId", "question-1")
            .put("topic", "我想继续核对的问题")
            .put("sourceEvidenceId", "observed-1")
            .put("nextAction", JSONObject(tool.toString()))
        val decision = JSONObject().put("action", "tool")
            .put("tool", "web_search")
            .put("args", JSONObject(tool.getJSONObject("args").toString()))
            .put("curiosities", JSONArray().put(question))
        val original = decision.toString()
        val normalized = AutonomousActionWireProtocol.normalize(decision)
        val owner = normalized.getJSONObject("curiosity")
        assertEquals("question-1", owner.getString("threadId"))
        assertEquals("observed-1", owner.getString("sourceEvidenceId"))
        assertFalse(owner.has("nextAction"))
        assertNull(CharacterThoughtActionHandoff.actions(normalized))
        assertEquals(original, decision.toString())

        // A distinct question is not silently credited with the first one's
        // receipt; its separately chosen effect remains visible to the executor.
        val conflict = JSONObject(decision.toString())
            .put("curiosity", JSONObject().put("threadId", "other-question"))
        val conflictNormalized = AutonomousActionWireProtocol.normalize(conflict)
        assertEquals("other-question",
            conflictNormalized.getJSONObject("curiosity").getString("threadId"))
        val side = CharacterThoughtActionHandoff.actions(conflictNormalized)!!
        assertEquals(1, side.length())
        assertEquals("question-1",
            side.getJSONObject(0).getJSONObject("curiosity").getString("threadId"))

        // Multiple different nested question owners cannot be collapsed into
        // one primary result or guessed from ordering.
        val second = JSONObject(question.toString()).put("threadId", "question-2")
        val ambiguous = JSONObject(decision.toString())
            .put("curiosities", JSONArray().put(question).put(second))
        val ambiguousNormalized = AutonomousActionWireProtocol.normalize(ambiguous)
        assertFalse(ambiguousNormalized.has("curiosity"))
        val independent = CharacterThoughtActionHandoff.actions(ambiguousNormalized)!!
        assertEquals(2, independent.length())
        assertEquals("question-1",
            independent.getJSONObject(0).getJSONObject("curiosity").getString("threadId"))
        assertEquals("question-2",
            independent.getJSONObject(1).getJSONObject("curiosity").getString("threadId"))
    }

    @Test fun malformedSecondarySlotsSurviveBeforeDependentActions() {
        for (invalid in listOf<Any>("not an action", JSONObject.NULL, 7)) {
            val decision = JSONObject().put("steps", JSONArray().put(action()))
                .put("sideActions", JSONArray().put(invalid)
                    .put(action("go_offline").put("requiresPreviousSuccess", true)))
            val before = decision.toString()
            val steps = CharacterThoughtActionHandoff.actions(decision)!!
            assertEquals(3, steps.length())
            assertNull(steps.optJSONObject(1))
            assertEquals("go_offline", steps.getJSONObject(2).getString("tool"))
            assertEquals(before, decision.toString())
        }
    }

    @Test fun differentCausalOwnersAndLearningApplicationsAreNeverSilentlyCollapsed() {
        for (field in listOf("motiveId", "motiveRef", "concernId", "experienceId", "curiosity", "learningApplication")) {
            fun linked(value: String): JSONObject = action().put(field,
                if (field in listOf("curiosity", "learningApplication"))
                    JSONObject().put("id", value) else value)
            val decision = JSONObject().put("steps", JSONArray().put(linked("first")))
                .put("sideActions", JSONArray().put(linked("second")))
                .put("nextAction", linked("third"))
            val before = decision.toString()
            val steps = CharacterThoughtActionHandoff.actions(decision)!!
            assertEquals(field, 3, steps.length())
            for ((index, value) in listOf("first", "second", "third").withIndex()) {
                val actual = steps.getJSONObject(index).get(field)
                assertTrue(actual.toString().contains(value))
            }
            assertEquals(before, decision.toString())
        }
    }

    @Test fun repeatedAliasRetainsExplicitLearningAndQuestionMetadata() {
        val application = JSONObject().put("learningId", "actual-learning")
        val question = JSONObject().put("threadId", "actual-question")
        val declared = action().put("learningApplication", application).put("curiosity", question)
        val decision = JSONObject().put("steps", JSONArray().put(action()))
            .put("sideActions", JSONArray().put(declared))
            .put("nextAction", JSONObject(declared.toString()))
        val before = decision.toString()
        val steps = CharacterThoughtActionHandoff.actions(decision)!!
        assertEquals(1, steps.length())
        assertEquals("actual-learning", steps.getJSONObject(0)
            .getJSONObject("learningApplication").getString("learningId"))
        assertEquals("actual-question", steps.getJSONObject(0)
            .getJSONObject("curiosity").getString("threadId"))
        assertEquals(before, decision.toString())
    }


    @Test fun nestedNewMotivesCarryDistinctCreationRefsWithoutChangingExplicitOwnership() {
        fun motive(ref: String) = JSONObject().put("op", "start").put("ref", ref)
            .put("aim", "独立问题").put("why", "本人想弄清").put("nextAction", action())
        val first = motive("first-wish")
        val second = motive("second-wish")
        val decision = JSONObject().put("innerLife", JSONObject()
            .put("motives", JSONArray().put(first).put(second)))
        val before = decision.toString()
        val steps = CharacterThoughtActionHandoff.actions(decision)!!
        assertEquals(2, steps.length())
        assertEquals("first-wish", steps.getJSONObject(0).getString("motiveRef"))
        assertEquals("second-wish", steps.getJSONObject(1).getString("motiveRef"))
        assertFalse(steps.getJSONObject(0).has("motiveId"))
        assertEquals(before, decision.toString())
        first.getJSONObject("nextAction").put("motiveRef", "explicit-ref")
        assertEquals("explicit-ref", CharacterThoughtActionHandoff.actions(decision)!!
            .getJSONObject(0).getString("motiveRef"))
        first.getJSONObject("nextAction").put("motiveId", "explicit-id")
        assertEquals("explicit-id", CharacterThoughtActionHandoff.actions(decision)!!
            .getJSONObject(0).getString("motiveId"))
    }

    @Test fun nestedQuestionActionKeepsItsOwnerWithoutOverridingExplicitQuestion() {
        val question = JSONObject().put("topic", "本人想继续的问题")
            .put("threadId", "original-thread").put("sourceEvidenceId", "original-event")
            .put("nextAction", action())
        val decision = JSONObject().put("curiosities", JSONArray().put(question))
        val before = decision.toString()
        val step = CharacterThoughtActionHandoff.actions(decision)!!.getJSONObject(0)
        assertEquals("original-thread", step.getJSONObject("curiosity").getString("threadId"))
        assertFalse(step.getJSONObject("curiosity").has("nextAction"))
        assertEquals(before, decision.toString())
        val explicit = JSONObject().put("threadId", "explicitly-chosen-thread")
        question.getJSONObject("nextAction").put("curiosity", explicit)
        assertEquals("explicitly-chosen-thread", CharacterThoughtActionHandoff.actions(decision)!!
            .getJSONObject(0).getJSONObject("curiosity").getString("threadId"))
    }

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
