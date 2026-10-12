package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterActionEvidenceTest {
    @Test fun aRealMessageMustNotBeReplacedByItsLaterToolReceipt() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(ctx)
        SharedExperienceTimeline.initialize(ctx)
        val role = "action-source-cross-scene-test"
        val at = Instant.parse("2026-10-12T03:00:00Z")
        SharedExperienceTimeline.record("actual-sent-text", role, "私聊", "角色",
            "真的发给了用户", at, triggerExtraction = false, source = "message",
            evidenceKind = EventEvidenceKind.CharacterStatement)
        SharedExperienceTimeline.record("newer-tool-receipt", role, "实际工具执行", "执行器",
            JSONObject().put("status", "succeeded").put("tool", "send_private_message")
                .put("result", JSONObject().put("success", true).toString()).toString(),
            at.plusSeconds(5), triggerExtraction = false, source = "tool-router",
            evidenceKind = EventEvidenceKind.ToolResult)
        val message = CharacterActionEvidence.resolve(role, "actual-sent-text")
        val tool = CharacterActionEvidence.resolve(role, "newer-tool-receipt")
        assertNotNull(message)
        assertNotNull(tool)
        assertTrue(CharacterActionEvidence.supports(message!!, "message"))
        assertFalse(CharacterActionEvidence.supports(tool!!, "message"))
        assertTrue(CharacterActionEvidence.supports(tool, "tool"))
        SharedExperienceTimeline.deleteEvent("actual-sent-text")
        assertNull(CharacterActionEvidence.resolve(role, "actual-sent-text"))
        SharedExperienceTimeline.deleteEvent("newer-tool-receipt")
    }
}
