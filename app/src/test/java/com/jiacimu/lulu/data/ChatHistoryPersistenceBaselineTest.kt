package com.jiacimu.lulu.data

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * UI-02.1: reproducible end-to-end baseline for the existing state_v1 authority.
 * Times include the real Store's JSON serialization and SharedPreferences write;
 * they are NOT a pure encoder benchmark or device ANR evidence.
 *
 * A single seeded snapshot avoids O(n²) setup from calling append N times.
 * The original preference is restored even if an assertion fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ChatHistoryPersistenceBaselineTest {
    private val app: Context = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("lulu_chat_store", Context.MODE_PRIVATE)
    private val key = "state_v1"
    private val conversationId = "ui02-history-baseline"
    private val otherConversationId = "ui02-unrelated-history"

    @Test fun thousandAndTenThousandMessagesWriteAndReopenWithoutLoss() {
        for (count in listOf(1_000, 10_000)) measure(count)
    }

    @Ignore("Opt-in heavy baseline: run explicitly on a memory-sized CI runner/device")
    @Test fun hundredThousandMessagesWriteAndReopenWithoutLoss() = measure(100_000)

    private fun measure(count: Int) {
        val previous = prefs.getString(key, null)
        try {
            val messages = JSONArray()
            repeat(count) { i ->
                messages.put(JSONObject()
                    .put("id", "ui02-$i")
                    .put("conversationId", conversationId)
                    .put("sender", "User")
                    .put("content", "History item $i with a representative short text payload")
                    .put("createdAt", "2026-01-01T00:00:00Z")
                    .put("status", "Sent"))
            }
            val conversation = JSONObject()
                .put("id", conversationId)
                .put("characterId", "ui02-character")
                .put("title", "UI-02 persistence baseline")
                .put("updatedAt", "2026-01-01T00:00:00Z")
            val otherConversation = JSONObject()
                .put("id", otherConversationId)
                .put("characterId", "ui02-other-character")
                .put("title", "Unrelated conversation must survive")
                .put("updatedAt", "2026-01-01T00:00:00Z")
            val otherMessages = JSONArray().put(JSONObject()
                .put("id", "ui02-unrelated-1")
                .put("conversationId", otherConversationId)
                .put("sender", "User")
                .put("content", "Do not discard another conversation")
                .put("createdAt", "2026-01-01T00:00:00Z")
                .put("status", "Sent"))
            val snapshot = JSONObject()
                .put("conversations", JSONArray().put(conversation).put(otherConversation))
                .put("messages", JSONObject().put(conversationId, messages)
                    .put(otherConversationId, otherMessages))
                .toString()
            assertTrue(prefs.edit().putString(key, snapshot).commit())

            val openStart = SystemClock.elapsedRealtimeNanos()
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            val openMillis = (SystemClock.elapsedRealtimeNanos() - openStart) / 1_000_000.0
            assertEquals(count, store.messages(conversationId).value.size)
            assertEquals(1, store.messages(otherConversationId).value.size)

            // Metadata-only mutation must still rewrite the entire JSON snapshot.
            val writeStart = SystemClock.elapsedRealtimeNanos()
            assertTrue(store.setConversationPinned(conversationId, true))
            val writeMillis = (SystemClock.elapsedRealtimeNanos() - writeStart) / 1_000_000.0
            val saved = prefs.getString(key, null)
            assertNotNull(saved)
            assertEquals(count, JSONObject(saved!!).getJSONObject("messages")
                .getJSONArray(conversationId).length())
            assertEquals("Do not discard another conversation", JSONObject(saved)
                .getJSONObject("messages").getJSONArray(otherConversationId)
                .getJSONObject(0).getString("content"))

            val reopenStart = SystemClock.elapsedRealtimeNanos()
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            val reopenMillis = (SystemClock.elapsedRealtimeNanos() - reopenStart) / 1_000_000.0
            assertEquals(count, reopened.messages(conversationId).value.size)
            assertEquals("Do not discard another conversation",
                reopened.messages(otherConversationId).value.single().content)
            assertTrue(reopened.conversations.value.first { it.id == conversationId }.pinned)
            println("UI-02.1 messages=$count snapshotBytes=${saved.toByteArray(Charsets.UTF_8).size} " +
                "initialOpenMs=$openMillis metadataWriteMs=$writeMillis reopenMs=$reopenMillis")
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove(key) else editor.putString(key, previous)
            assertTrue(editor.commit())
        }
    }
}
