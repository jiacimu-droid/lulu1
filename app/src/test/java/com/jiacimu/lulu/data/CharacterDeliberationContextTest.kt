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
class CharacterDeliberationContextTest {
    private val now = Instant.parse("2026-10-11T12:00:00Z")

    @Test fun multipleGuessesStayPrivateAndAreCarriedToNextDecision() {
        val appraisal = JSONObject()
            .put("meaning", "电话仍在接通")
            .put("possibleReadings", JSONArray().put("可能正在忙").put("也可能没听清").put("可能只是想安静"))
            .put("tension", "想问一声，又担心打扰")
        val normalized = CharacterDeliberationContext.conciseAppraisal(appraisal)
        assertEquals(3, normalized?.optJSONArray("possibleReadings")?.length())
        val transition = JSONObject()
            .put("at", now.minusSeconds(60).toString())
            .put("selectedAction", "silent")
            .put("appraisal", normalized)
            .put("innerThought", "再听一会儿，别急着下结论")
        val prompt = CharacterDeliberationContext.summary(JSONArray().put(transition), now)
        assertTrue(prompt.contains("可能正在忙"))
        assertTrue(prompt.contains("也可能没听清"))
        assertTrue(prompt.contains("想问一声，又担心打扰"))
        assertTrue(prompt.contains("没说出口"))
        assertTrue(prompt.contains("主观"))
    }

    @Test fun boundedHypothesesCannotGrowWithModelOutput() {
        val guesses = JSONArray().put("A").put("A").put("B").put("C").put("D")
        val normalized = CharacterDeliberationContext.conciseAppraisal(
            JSONObject().put("possibleReadings", guesses))
        assertEquals(2, normalized?.optJSONArray("possibleReadings")?.length())
        assertEquals(5, guesses.length())
    }

    @Test fun changedUncertaintyIsGenuineStateDeltaWithoutInventingAnExternalEvent() {
        val prior = JSONObject().put("causalTransitions", JSONArray().put(JSONObject()
            .put("appraisal", JSONObject().put("possibleReadings", JSONArray().put("也许只是忙碌"))
                .put("tension", "暂时不想打扰"))))
        val changed = PrivateStateDeltaEngine.evaluate(
            previous = prior,
            proposal = null,
            appraisal = JSONObject().put("possibleReadings", JSONArray().put("也许忘了手机还连着"))
                .put("tension", "想提醒一下又有点犹豫"),
            basis = JSONObject().put("focus", "电话仍然安静")
                .put("unsaidWhy", "不想打断对方"),
            thought = "要不要叫一声？",
        )
        assertTrue(changed.appraisalChanged)
        assertTrue(changed.meaningful)
        assertTrue(changed.privateResidue)
    }

    @Test fun oldConflictingThoughtsDoNotRemainAForcedScript() {
        val old = JSONObject()
            .put("at", now.minusSeconds(13L * 3600).toString())
            .put("selectedAction", "silent")
            .put("innerThought", "旧的犹豫")
        assertEquals("", CharacterDeliberationContext.summary(JSONArray().put(old), now))
    }
}
