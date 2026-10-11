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
class CharacterOpenConcernRuntimeTest {
    private val now = Instant.parse("2026-10-11T18:00:00Z")
    private val first = JSONObject().put("pendingConcern", JSONObject()
        .put("status", "open")
        .put("focus", "电话里突然没有回应，不知道是不是连接中断")
        .put("whyItMatters", "我还想和对方说完刚才那句话")
        .put("hesitation", "也许对方现在需要安静")
        .put("possibleNextStep", "过一会儿温和地确认一下"))

    @Test fun aSuspendedConcernSurvivesAnotherQuietTurn() {
        val opened = CharacterOpenConcernRuntime.update(null, first, "call-silence-1", now)
        assertEquals(1, opened.length())
        val unchanged = CharacterOpenConcernRuntime.update(opened, null, "no-new-input", now.plusSeconds(65))
        assertEquals(opened.getJSONObject(0).getString("id"), unchanged.getJSONObject(0).getString("id"))
        val context = CharacterOpenConcernRuntime.context(unchanged, now.plusSeconds(65))
        assertTrue(context.contains("没有回应"))
        assertTrue(context.contains("可能"))
        assertTrue(context.contains("不是用户事实"))
    }

    @Test fun revisingAndThenSettlingMustUseTheOriginalThreadId() {
        val opened = CharacterOpenConcernRuntime.update(null, first, "call-silence-1", now)
        val id = opened.getJSONObject(0).getString("id")
        val revised = CharacterOpenConcernRuntime.update(opened, JSONObject().put("pendingConcern", JSONObject()
            .put("threadId", id).put("status", "revise")
            .put("hesitation", "后来确认只是语音识别卡住")), "call-silence-2", now.plusSeconds(120))
        assertEquals(1, revised.length())
        assertEquals(id, revised.getJSONObject(0).getString("id"))
        val settled = CharacterOpenConcernRuntime.update(revised, JSONObject().put("pendingConcern", JSONObject()
            .put("threadId", id).put("status", "settled")), "user-explained", now.plusSeconds(240))
        assertEquals(0, settled.length())
        assertEquals("", CharacterOpenConcernRuntime.context(settled, now.plusSeconds(240)))
    }

    @Test fun failedAttemptIsRememberedWithoutPretendingThatTheConcernWasSettled() {
        val opened = CharacterOpenConcernRuntime.update(null, first, "call-silence-1", now)
        val threadId = opened.getJSONObject(0).getString("id")
        val attempted = CharacterOpenConcernRuntime.recordOutcome(
            opened, threadId, "real-tool-1", "send_private_sticker", false,
            "图像未入库，不能发送", now.plusSeconds(80),
        )
        assertEquals(1, attempted.length())
        assertFalse(attempted.getJSONObject(0).getJSONObject("lastOutcome").getBoolean("success"))
        assertTrue(CharacterOpenConcernRuntime.context(attempted, now.plusSeconds(90)).contains("执行失败"))
        val again = CharacterOpenConcernRuntime.recordOutcome(
            attempted, threadId, "real-tool-1", "send_private_sticker", true,
            "编造执行成功", now.plusSeconds(90),
        )
        assertFalse(again.getJSONObject(0).getJSONObject("lastOutcome").getBoolean("success"))
        val revised = CharacterOpenConcernRuntime.update(attempted, JSONObject().put("pendingConcern",
            JSONObject().put("status", "revise").put("threadId", threadId)
                .put("hesitation", "先检查图片能不能加载")), "real-new-evidence", now.plusSeconds(95))
        assertFalse(revised.getJSONObject(0).getJSONObject("lastOutcome").getBoolean("success"))
    }

    @Test fun casualEmotionCannotManufactureAContinuingConcern() {
        val incomplete = JSONObject().put("pendingConcern", JSONObject()
            .put("status", "open").put("focus", "有点高兴"))
        assertEquals(0, CharacterOpenConcernRuntime.update(null, incomplete, "greeting", now).length())
        assertEquals(0, CharacterOpenConcernRuntime.update(null, first, "", now).length())
    }

    @Test fun aRecentConcernCanCauseOneOptionalLaterReflectionButOldOnesCannot() {
        val opened = CharacterOpenConcernRuntime.update(null, first, "genuine-call-event", now)
        assertTrue(CharacterOpenConcernRuntime.meritsOneFollowThrough(opened, now.plusSeconds(1_500)))
        assertFalse(CharacterOpenConcernRuntime.meritsOneFollowThrough(opened, now.plusSeconds(4_200)))
        assertFalse(CharacterOpenConcernRuntime.meritsOneFollowThrough(JSONArray(), now))
    }

    @Test fun oldPrivateWorriesGoDormantWithoutErasingTheUnfinishedJourney() {
        val opened = CharacterOpenConcernRuntime.update(null, first, "silence", now)
        val late = now.plusSeconds(49L * 3600)
        val context = CharacterOpenConcernRuntime.context(opened, late)
        assertTrue(context.contains("搁置"))
        assertFalse(context.contains("自己仍未想通的心事"))
        assertFalse(CharacterOpenConcernRuntime.meritsOneFollowThrough(opened, late))
        val retained = CharacterOpenConcernRuntime.update(opened, null, "ambient", late)
        assertEquals(1, retained.length())
        assertEquals(opened.getJSONObject(0).getString("id"), retained.getJSONObject(0).getString("id"))
    }

    @Test fun innerJourneyOnlyAdvancesWhenEvidenceActuallyChangesTheView() {
        val opened = CharacterOpenConcernRuntime.update(null, first, "call-1", now)
        val id = opened.getJSONObject(0).getString("id")
        val repeated = CharacterOpenConcernRuntime.update(opened, JSONObject().put("pendingConcern", JSONObject()
            .put("threadId", id).put("status", "revise")
            .put("hesitation", "也许对方现在需要安静")), "call-2", now.plusSeconds(120))
        assertEquals(opened.getJSONObject(0).getString("updatedAt"), repeated.getJSONObject(0).getString("updatedAt"))
        val changed = CharacterOpenConcernRuntime.update(repeated, JSONObject().put("pendingConcern", JSONObject()
            .put("threadId", id).put("status", "revise")
            .put("hesitation", "后来收到了明确解释，应该不是在生气")), "call-3", now.plusSeconds(240))
        val journey = changed.getJSONObject(0).getJSONArray("journey")
        assertEquals(2, journey.length())
        assertEquals("call-1", journey.getJSONObject(0).getString("evidenceId"))
        assertEquals("call-3", journey.getJSONObject(1).getString("evidenceId"))
        assertTrue(CharacterOpenConcernRuntime.context(changed, now.plusSeconds(250), compact = false).contains("心路转变"))
        assertTrue(CharacterOpenConcernRuntime.dependsOnEvidence(changed.getJSONObject(0), "call-1"))
        assertTrue(CharacterOpenConcernRuntime.dependsOnEvidence(changed.getJSONObject(0), "call-3"))
        assertFalse(CharacterOpenConcernRuntime.dependsOnEvidence(changed.getJSONObject(0), "unrelated"))
    }

    @Test fun resolvedConcernCanBeRememberedAsACompletedPsychologicalTurn() {
        val opened = CharacterOpenConcernRuntime.update(null, first, "call-1", now)
        val id = opened.getJSONObject(0).getString("id")
        val closure = JSONObject().put("pendingConcern", JSONObject()
            .put("threadId", id).put("status", "settled")
            .put("resolution", "确认只是通话断线，我不必一直猜测她是不是不高兴"))
        val closed = CharacterOpenConcernRuntime.closure(opened, closure, "confirmed-signal", now.plusSeconds(360))
        assertNotNull(closed)
        assertTrue(CharacterOpenConcernRuntime.closureContext(JSONArray().put(closed), now.plusSeconds(400))
            .contains("通话断线"))
        assertEquals(0, CharacterOpenConcernRuntime.update(opened, closure, "confirmed-signal", now.plusSeconds(360)).length())
        assertTrue(CharacterOpenConcernRuntime.dependsOnEvidence(closed!!, "confirmed-signal"))
    }
}
