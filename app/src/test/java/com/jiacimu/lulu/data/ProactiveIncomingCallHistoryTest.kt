package com.jiacimu.lulu.data

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/** Real incoming-call offers, rather than a role's text claiming it called. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProactiveIncomingCallHistoryTest {
    private lateinit var context: Context
    private lateinit var role: String
    private val time = Instant.parse("2026-11-15T00:00:00Z")

    @Before fun setUp() {
        // The real chat Store persists across Robolectric methods. Each test
        // needs an independent character to avoid prior missed-call receipts
        // being mistaken for this test's newly offered invitation.
        role = "incoming-history-" + java.util.UUID.randomUUID()
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("voice_call_ringtone_enabled", false).commit()
        MigratedDomainStores.initialize(context)
        MigratedDomainStores.chat.ensureConversation(role, "测试来电角色")
        ProactiveIncomingCallStore.initialize(context)
        ProactiveIncomingCallStore.clear()
    }

    private fun conversation() = MigratedDomainStores.chat.conversations.value
        .first { it.characterId == role && it.groupChat == null && !it.id.endsWith("-study-focus") }

    private fun receipts() = MigratedDomainStores.chat.messages(conversation().id).value
        .filter { it.sender == LuluChatMessage.Sender.System &&
            it.id.startsWith("incoming-call-unconnected-") }

    @Test fun autonomousMissedCallIsSavedOnceAndSurvivesChatRestart() {
        val conversationId = conversation().id
        ProactiveIncomingCallStore.offer(context, role, conversationId,
            "接通后才说的话", now = time)
        assertTrue(receipts().isEmpty()) // Pending is not yet a missed call.
        ProactiveIncomingCallStore.reconcileExpired(time.plusSeconds(121))
        assertNull(ProactiveIncomingCallStore.pending.value)
        val missed = receipts().single()
        assertTrue(missed.content.contains("未接通"))
        assertTrue(missed.content.contains("超时"))
        assertTrue(missed.content.contains("通知实际显示与否尚未确认"))
        assertFalse(missed.content.contains("接通后才说的话"))
        ProactiveIncomingCallStore.reconcileExpired(time.plusSeconds(600))
        assertEquals(1, receipts().size)

        // The same durable receipt exists in the shared chat source after restart.
        val reopened = InMemoryLuluChatStore().apply { initialize(context) }
        assertEquals(missed.id, reopened.messages(conversationId).value
            .single { it.id == missed.id }.id)
    }

    @Test fun explicitDeclineIsNotRecordedAsMissedOrAnswered() {
        val call = ProactiveIncomingCallStore.offer(context, role, conversation().id,
            "接通才说", now = time, mediaMode = "video")
        ProactiveIncomingCallStore.respond(call, answered = false)
        assertNull(ProactiveIncomingCallStore.pending.value)
        val message = receipts().single()
        assertTrue(message.content.contains("视频来电"))
        assertTrue(message.content.contains("已拒接"))
        assertFalse(message.content.contains("超时"))
        assertFalse(message.content.contains("刚刚打了个电话"))
    }

    @Test fun answeredCallDoesNotInventAnUnansweredReceipt() {
        val call = ProactiveIncomingCallStore.offer(context, role, conversation().id,
            "接通才说", now = time)
        ProactiveIncomingCallStore.respond(call, answered = true)
        assertNull(ProactiveIncomingCallStore.pending.value)
        assertTrue(receipts().isEmpty())
    }

    @Test fun successiveOffersDoNotSilentlyOverwritePreviousCall() {
        ProactiveIncomingCallStore.offer(context, role, conversation().id, "第一次", now = time)
        val second = ProactiveIncomingCallStore.offer(context, role, conversation().id,
            "第二次", now = time.plusSeconds(15))
        assertEquals(second, ProactiveIncomingCallStore.pending.value)
        assertTrue(receipts().single().content.contains("被下一通来电替换"))
        ProactiveIncomingCallStore.reconcileExpired(time.plusSeconds(136))
        assertEquals(2, receipts().size)
        assertEquals(2, receipts().map { it.id }.distinct().size)
    }
}
