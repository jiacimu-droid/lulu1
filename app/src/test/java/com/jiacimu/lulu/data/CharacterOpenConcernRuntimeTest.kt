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

    @Test fun oldPrivateWorriesFadeInsteadOfBecomingPermanentPersonalityTraits() {
        val opened = CharacterOpenConcernRuntime.update(null, first, "silence", now)
        assertEquals("", CharacterOpenConcernRuntime.context(opened, now.plusSeconds(49L * 3600)))
        assertEquals(0, CharacterOpenConcernRuntime.update(opened, null, "ambient", now.plusSeconds(49L * 3600)).length())
    }
}
