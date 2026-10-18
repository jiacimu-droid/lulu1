package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PhoneReadRequestEvidenceTest {
    private val role = "phone-read-source-owner"
    private val conversation = "phone-typed-conversation"

    @Before fun before() {
        MemoryTestEnvironment.release()
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
    }
    @After fun after() { MemoryTestEnvironment.release() }

    private fun say(id: String, text: String) {
        SharedExperienceTimeline.record(id, role, "私聊", "用户", text,
            triggerExtraction = false, sessionId = conversation, source = "message",
            evidenceKind = EventEvidenceKind.UserStatement)
    }

    @Test fun batchUsesExactSourcesAndAnEditToTheRequestInvalidatesTheWholeRead() {
        say("actual-query", "帮我去小红书搜一下咖啡")
        say("following-thanks", "谢谢啦")
        val sources = PhoneReadRequestEvidence.capture(role, conversation,
            listOf("actual-query", "following-thanks"))!!
        val encoded = JSONArray(PhoneReadRequestEvidence.encode(sources))
        assertEquals(sources, PhoneReadRequestEvidence.restore(role, conversation, encoded))
        assertNull(PhoneReadRequestEvidence.restore("someone-else", conversation, encoded))
        assertNull(PhoneReadRequestEvidence.restore(role, "another-conversation", encoded))
        assertNull(PhoneReadRequestEvidence.capture(role, conversation, listOf("actual-query", "actual-query")))
        say("actual-query", "刚刚那条请求改掉了")
        assertNull(PhoneReadRequestEvidence.restore(role, conversation, encoded))
    }

    @Test fun deletingAnyOriginalBubbleInvalidatesItsDelayedResult() {
        say("split-command", "你帮我去搜一下")
        say("split-platform", "小红书咖啡")
        val sources = PhoneReadRequestEvidence.capture(role, conversation,
            listOf("split-command", "split-platform"))!!
        val encoded = JSONArray(PhoneReadRequestEvidence.encode(sources))
        SharedExperienceTimeline.deleteEvent("split-command")
        assertNull(PhoneReadRequestEvidence.restore(role, conversation, encoded))
        assertNull(PhoneReadRequestEvidence.capture(role, conversation, emptyList()))
    }
}
