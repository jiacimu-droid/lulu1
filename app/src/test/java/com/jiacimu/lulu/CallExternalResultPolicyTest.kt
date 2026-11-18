package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.data.CharacterPerceptionContext
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.MemoryTestEnvironment
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.Assert.*
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CallExternalResultPolicyTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun correctedReceiptCanQueueAgainButSameRevisionAndForeignRoleCannot() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        val role = MigratedDomainStores.characters.create("电话回执版本测试").characterId
        val other = MigratedDomainStores.characters.create("另一位回执角色").characterId
        val id = "queued-revision-$role"
        fun write(summary: String) {
            SharedExperienceTimeline.record(id, role, "实际工具执行", "执行器",
                JSONObject().put("tool", "web_search").put("status", "observed")
                    .put("result", JSONObject().put("success", true).put("summary", summary).toString())
                    .toString(), triggerExtraction = false,
                source = "tool-router", evidenceKind = EventEvidenceKind.ToolResult)
        }
        val pending = linkedSetOf<String>()
        val offered = mutableMapOf<String, Long>()
        fun offer(owner: String = role) = CallExternalResultPolicy.enqueue(context, owner, id, pending, offered)
        write("第一版结果")
        val first = CallExternalResultPolicy.pendingEvidence(context, role, id)!!
        assertTrue(offer())
        assertFalse(offer())
        assertEquals(setOf(id), pending)
        pending.remove(id)

        write("重新核实后的第二版结果")
        val revised = CallExternalResultPolicy.pendingEvidence(context, role, id)!!
        assertTrue(revised.revision > first.revision)
        assertFalse(offer(other))
        assertTrue(pending.isEmpty())
        assertTrue(offer())
        assertFalse(offer())
        assertEquals(revised.revision, offered[id])
        assertEquals(setOf(id), pending)

        // A further edit while queued is coalesced into the same slot.
        write("排队期间更新的第三版结果")
        assertTrue(offer())
        assertEquals(setOf(id), pending)
        val latest = CallExternalResultPolicy.pendingEvidence(context, role, pending.first())!!
        assertTrue(latest.revision > revised.revision)
        assertTrue(latest.content.contains("第三版结果"))
        pending.remove(id)
        val perception = CharacterPerceptionContext.integrate(context, role, listOf(latest),
            deferAcknowledgement = true)
        CharacterPerceptionContext.acknowledge(context, role, perception)
        assertNull(CallExternalResultPolicy.pendingEvidence(context, role, id))
        assertFalse(offer())
        assertTrue(pending.isEmpty())
    }


    @Test fun rejectedNoticeDoesNotConsumeTheLaterCanonicalReceipt() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        val owner = MigratedDomainStores.characters.create("延迟原件的回执角色").characterId
        val other = MigratedDomainStores.characters.create("误投通知的角色").characterId
        val id = "late-canonical-$owner"
        val pending = linkedSetOf<String>()
        val offered = mutableMapOf<String, Long>()
        fun offer(role: String) = CallExternalResultPolicy.enqueue(context, role, id, pending, offered)

        // A notification is not evidence. Rejection must leave no checkpoint.
        assertFalse(offer(owner))
        assertTrue(pending.isEmpty())
        assertTrue(offered.isEmpty())
        SharedExperienceTimeline.record(id, owner, "实际工具执行", "执行器",
            JSONObject().put("tool", "web_search").put("status", "observed")
                .put("result", JSONObject().put("success", true)
                    .put("summary", "稍后才持久化的真实结果").toString()).toString(),
            triggerExtraction = false, source = "tool-router",
            evidenceKind = EventEvidenceKind.ToolResult)
        val original = SharedExperienceTimeline.eventsByIds(owner, listOf(id)).single()

        assertFalse(offer(other))
        assertTrue(pending.isEmpty())
        assertTrue(offered.isEmpty())
        assertTrue(offer(owner))
        assertEquals(setOf(id), pending)
        assertEquals(mapOf(id to original.revision), offered)
        assertFalse(offer(owner))
        assertEquals(original, CallExternalResultPolicy.pendingEvidence(context, owner, id))

        // Another entry can acknowledge the result while this notice waits.
        val perception = CharacterPerceptionContext.integrate(context, owner, listOf(original),
            deferAcknowledgement = true)
        CharacterPerceptionContext.acknowledge(context, owner, perception)
        assertNull(CallExternalResultPolicy.pendingEvidence(context, owner, id))
        assertFalse(offer(owner))
        assertEquals(original, SharedExperienceTimeline.eventsByIds(owner, listOf(id)).single())
    }

    private fun ready(role: String = "a", call: String = "first", connected: Boolean = true,
        opening: Boolean = false, thinking: Boolean = false, speaking: Boolean = false,
        userSpeaking: Boolean = false, audioPending: Boolean = false,
        hangupPending: Boolean = false) =
        CallExternalResultPolicy.canDeliver("a", "first", role, call,
            connected, opening, thinking, speaking, userSpeaking, audioPending, hangupPending)

    @Test fun realResultWaitsForBothPeopleAndQueuedAudio() {
        assertTrue(ready())
        assertFalse(ready(opening = true))
        assertFalse(ready(thinking = true))
        assertFalse(ready(speaking = true))
        assertFalse(ready(userSpeaking = true))
        assertFalse(ready(audioPending = true))
    }

    @Test fun receiptWaitsThroughFarewellGraceAndCanProceedAfterUserCancelsIt() {
        assertFalse(ready(hangupPending = true))
        assertFalse(ready(hangupPending = true, userSpeaking = true))
        assertFalse(ready(connected = false, hangupPending = false))
        // A confirmed user turn cancels the farewell, then takes the turn slot.
        assertFalse(ready(hangupPending = false, userSpeaking = true))
        assertFalse(ready(hangupPending = false, thinking = true))
        assertTrue(ready(hangupPending = false))
    }

    @Test fun oldResultCannotSpeakInAnotherCallOrAsAnotherPerson() {
        assertFalse(ready(role = "b"))
        assertFalse(ready(call = "second"))
        assertFalse(ready(connected = false))
    }
}
