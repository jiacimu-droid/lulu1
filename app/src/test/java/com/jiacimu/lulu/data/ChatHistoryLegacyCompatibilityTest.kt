package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** UI-02.2: legacy state_v1 edit/delete compatibility before incremental migration. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ChatHistoryLegacyCompatibilityTest {

    @Test fun blankButPresentLegacySnapshotMustNotBeReplacedByDefaults() {
        val app: Context = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("lulu_chat_store", Context.MODE_PRIVATE)
        val previous = prefs.getString("state_v1", null)
        try {
            assertTrue(prefs.edit().putString("state_v1", "   ").commit())
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            val outcome = runCatching { store.setConversationPinned("lulu-main", true) }
            assertTrue("Present unreadable history must reject new metadata writes",
                outcome.isFailure || outcome.getOrNull() == false)
            assertEquals("   ", prefs.getString("state_v1", null))
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            runCatching { reopened.setConversationPinned("lulu-main", true) }
            assertEquals("   ", prefs.getString("state_v1", null))
        } finally {
            val edit = prefs.edit()
            if (previous == null) edit.remove("state_v1") else edit.putString("state_v1", previous)
            assertTrue(edit.commit())
        }
    }

    @Test fun malformedLegacySnapshotMustNotBeSilentlyOverwritten() {
        val app: Context = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("lulu_chat_store", Context.MODE_PRIVATE)
        val previous = prefs.getString("state_v1", null)
        val corrupt = """{"conversations":[{"id":"kept"}],"messages":"""
        try {
            assertTrue(prefs.edit().putString("state_v1", corrupt).commit())
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            // A corrupt but present snapshot is not an empty chat. A metadata mutation
            // must not replace the only remaining copy with a fresh default history.
            runCatching { store.setConversationPinned("lulu-main", true) }
            assertEquals(corrupt, prefs.getString("state_v1", null))
        } finally {
            val edit = prefs.edit()
            if (previous == null) edit.remove("state_v1") else edit.putString("state_v1", previous)
            assertTrue(edit.commit())
        }
    }


    @Test fun partiallyMalformedLegacyMessagesMustNotLoseOtherHistoryOnWrite() {
        val app: Context = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("lulu_chat_store", Context.MODE_PRIVATE)
        val previous = prefs.getString("state_v1", null)
        val corrupt = JSONObject()
            .put("conversations", JSONArray().put(JSONObject()
                .put("id", "ui02-partial").put("characterId", "ui02-character")
                .put("updatedAt", "2026-01-01T00:00:00Z")))
            .put("messages", JSONObject().put("ui02-partial", JSONArray()
                .put(JSONObject().put("id", "ui02-good").put("conversationId", "ui02-partial")
                    .put("sender", "User").put("content", "Keep this")
                    .put("createdAt", "2026-01-01T00:00:00Z").put("status", "Sent"))
                .put("not-a-message-object"))).toString()
        try {
            assertTrue(prefs.edit().putString("state_v1", corrupt).commit())
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            runCatching { store.setConversationPinned("ui02-partial", true) }
            assertEquals("Malformed member must not be discarded by a later full rewrite",
                corrupt, prefs.getString("state_v1", null))
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            runCatching { reopened.setConversationPinned("ui02-partial", false) }
            assertEquals(corrupt, prefs.getString("state_v1", null))
        } finally {
            val edit = prefs.edit()
            if (previous == null) edit.remove("state_v1") else edit.putString("state_v1", previous)
            assertTrue(edit.commit())
        }
    }

    @Test fun missingMessageIdentityMustNotBeRewrittenAsInventedHistory() {
        val app: Context = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("lulu_chat_store", Context.MODE_PRIVATE)
        val previous = prefs.getString("state_v1", null)
        val damaged = JSONObject()
            .put("conversations", JSONArray().put(JSONObject()
                .put("id", "ui02-identity").put("characterId", "ui02-character")
                .put("updatedAt", "2026-01-01T00:00:00Z")))
            .put("messages", JSONObject().put("ui02-identity", JSONArray().put(JSONObject()
                .put("conversationId", "ui02-identity")
                .put("sender", "User").put("content", "Do not invent an ID")
                .put("createdAt", "2026-01-01T00:00:00Z").put("status", "Sent"))))
            .toString()
        try {
            assertTrue(prefs.edit().putString("state_v1", damaged).commit())
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            runCatching { store.setConversationPinned("ui02-identity", true) }
            assertEquals("Missing source identity must not become a generated UUID on rewrite",
                damaged, prefs.getString("state_v1", null))
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            runCatching { reopened.setConversationPinned("ui02-identity", false) }
            assertEquals(damaged, prefs.getString("state_v1", null))
        } finally {
            val edit = prefs.edit()
            if (previous == null) edit.remove("state_v1") else edit.putString("state_v1", previous)
            assertTrue(edit.commit())
        }
    }

    @Test fun missingOriginalMessageContentMustNotBeRewrittenAsEmptyText() {
        val app: Context = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("lulu_chat_store", Context.MODE_PRIVATE)
        val previous = prefs.getString("state_v1", null)
        val conversationId = "ui02-missing-content"
        val damaged = JSONObject()
            .put("conversations", JSONArray().put(JSONObject()
                .put("id", conversationId).put("characterId", "ui02-character")
                .put("updatedAt", "2026-01-01T00:00:00Z")))
            .put("messages", JSONObject().put(conversationId, JSONArray().put(JSONObject()
                .put("id", "ui02-content-message").put("conversationId", conversationId)
                .put("sender", "User").put("createdAt", "2026-01-01T00:00:00Z")
                .put("status", "Sent")))).toString()
        try {
            assertTrue(prefs.edit().putString("state_v1", damaged).commit())
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            val outcome = runCatching { store.setConversationPinned(conversationId, true) }
            assertTrue("Missing content must not be accepted as a successful rewrite",
                outcome.isFailure || outcome.getOrNull() == false)
            assertEquals("Original snapshot must survive the attempted mutation",
                damaged, prefs.getString("state_v1", null))
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            runCatching { reopened.setConversationPinned(conversationId, true) }
            assertEquals(damaged, prefs.getString("state_v1", null))
        } finally {
            val edit = prefs.edit()
            if (previous == null) edit.remove("state_v1") else edit.putString("state_v1", previous)
            assertTrue(edit.commit())
        }
    }

    @Test fun malformedMessageTimestampMustNotBeRewrittenAsCurrentTime() {
        val app: Context = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("lulu_chat_store", Context.MODE_PRIVATE)
        val previous = prefs.getString("state_v1", null)
        val damaged = JSONObject()
            .put("conversations", JSONArray().put(JSONObject()
                .put("id", "ui02-time").put("characterId", "ui02-character")
                .put("updatedAt", "2026-01-01T00:00:00Z")))
            .put("messages", JSONObject().put("ui02-time", JSONArray().put(JSONObject()
                .put("id", "ui02-time-message").put("conversationId", "ui02-time")
                .put("sender", "User").put("content", "Original timestamp unknown")
                .put("createdAt", "not-a-timestamp").put("status", "Sent"))))
            .toString()
        try {
            assertTrue(prefs.edit().putString("state_v1", damaged).commit())
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            runCatching { store.setConversationPinned("ui02-time", true) }
            assertEquals("Do not invent a message timestamp on full rewrite",
                damaged, prefs.getString("state_v1", null))
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            runCatching { reopened.setConversationPinned("ui02-time", false) }
            assertEquals(damaged, prefs.getString("state_v1", null))
        } finally {
            val edit = prefs.edit()
            if (previous == null) edit.remove("state_v1") else edit.putString("state_v1", previous)
            assertTrue(edit.commit())
        }
    }

    @Test fun malformedConversationTimestampMustNotReplaceOriginalSnapshot() {
        val app: Context = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("lulu_chat_store", Context.MODE_PRIVATE)
        val previous = prefs.getString("state_v1", null)
        val damaged = JSONObject()
            .put("conversations", JSONArray().put(JSONObject()
                .put("id", "ui02-bad-conversation-time")
                .put("characterId", "ui02-character")
                .put("updatedAt", "invalid-original-time")))
            .put("messages", JSONObject().put("ui02-bad-conversation-time", JSONArray()
                .put(JSONObject().put("id", "ui02-valid-message")
                    .put("conversationId", "ui02-bad-conversation-time")
                    .put("sender", "User").put("content", "Keep the original")
                    .put("createdAt", "2026-01-01T00:00:00Z")
                    .put("status", "Sent")))).toString()
        try {
            assertTrue(prefs.edit().putString("state_v1", damaged).commit())
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            runCatching { store.setConversationPinned("ui02-bad-conversation-time", true) }
            assertEquals("Invalid conversation time must not become now() on rewrite",
                damaged, prefs.getString("state_v1", null))
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            runCatching { reopened.setConversationPinned("ui02-bad-conversation-time", false) }
            assertEquals(damaged, prefs.getString("state_v1", null))
        } finally {
            val edit = prefs.edit()
            if (previous == null) edit.remove("state_v1") else edit.putString("state_v1", previous)
            assertTrue(edit.commit())
        }
    }


    @Test fun failedPreferencesWriteMustNotAcknowledgePinOrChangeLiveState() {
        val base: Context = RuntimeEnvironment.getApplication()
        val real = base.getSharedPreferences("lulu_chat_store", Context.MODE_PRIVATE)
        val original = real.getString("state_v1", null)
        val conversationId = "ui02-write-failure"
        val snapshot = JSONObject()
            .put("conversations", JSONArray().put(JSONObject()
                .put("id", conversationId).put("characterId", "ui02-character")
                .put("updatedAt", "2026-01-01T00:00:00Z").put("pinned", false)))
            .put("messages", JSONObject().put(conversationId, JSONArray())).toString()
        try {
            assertTrue(real.edit().putString("state_v1", snapshot).commit())
            var writeAttempts = 0
            val failing = object : android.content.SharedPreferences by real {
                override fun edit(): android.content.SharedPreferences.Editor {
                    val underlying = real.edit()
                    return object : android.content.SharedPreferences.Editor by underlying {
                        override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                            underlying.putString(key, value)
                            return this
                        }
                        override fun commit(): Boolean {
                            writeAttempts++
                            return false
                        }
                        override fun apply() { writeAttempts++ }
                    }
                }
            }
            val context = object : android.content.ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                    if (name == "lulu_chat_store") failing else base.getSharedPreferences(name, mode)
            }
            val store = InMemoryLuluChatStore()
            store.initialize(context)
            val outcome = runCatching { store.setConversationPinned(conversationId, true) }
            assertEquals("The injected write failure must actually be reached", 1, writeAttempts)
            assertTrue("A failed write cannot be acknowledged as successful",
                outcome.isFailure || outcome.getOrNull() == false)
            assertFalse("A rejected mutation must not remain visible in memory",
                store.conversations.value.single { it.id == conversationId }.pinned)
            assertEquals(snapshot, real.getString("state_v1", null))
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(base)
            assertFalse(reopened.conversations.value.single { it.id == conversationId }.pinned)
        } finally {
            val editor = real.edit()
            if (original == null) editor.remove("state_v1") else editor.putString("state_v1", original)
            assertTrue(editor.commit())
        }
    }

    @Test fun legacyEditAndDeleteRemainDurableAcrossReopen() {
        val app: Context = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("lulu_chat_store", Context.MODE_PRIVATE)
        val previous = prefs.getString("state_v1", null)
        val first = "ui02-legacy-first"
        val second = "ui02-legacy-second"
        fun conversation(id: String) = JSONObject().put("id", id)
            .put("characterId", "ui02-$id").put("title", id)
            .put("updatedAt", "2026-01-01T00:00:00Z")
        fun message(id: String, conversationId: String, content: String) = JSONObject()
            .put("id", id).put("conversationId", conversationId).put("sender", "User")
            .put("content", content).put("createdAt", "2026-01-01T00:00:00Z")
            .put("status", "Sent")
        try {
            val snapshot = JSONObject()
                .put("conversations", JSONArray().put(conversation(first)).put(conversation(second)))
                .put("messages", JSONObject()
                    .put(first, JSONArray().put(message("ui02-edit", first, "Old"))
                        .put(message("ui02-delete", first, "Remove")))
                    .put(second, JSONArray().put(message("ui02-other", second, "Keep unrelated"))))
            assertTrue(prefs.edit().putString("state_v1", snapshot.toString()).commit())
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            assertEquals(2, store.messages(first).value.size)
            assertTrue(store.editMessage("ui02-edit", "Revised; do not send"))
            assertTrue(store.deleteMessage("ui02-delete"))
            assertFalse(store.deleteMessage("ui02-delete"))
            val saved = JSONObject(prefs.getString("state_v1", null)!!)
                .getJSONObject("messages")
            assertEquals(1, saved.getJSONArray(first).length())
            assertEquals("Revised; do not send",
                saved.getJSONArray(first).getJSONObject(0).getString("content"))
            assertEquals("Keep unrelated",
                saved.getJSONArray(second).getJSONObject(0).getString("content"))
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            assertEquals(listOf("ui02-edit"), reopened.messages(first).value.map { it.id })
            assertEquals("Revised; do not send", reopened.messages(first).value.single().content)
            assertEquals("Keep unrelated", reopened.messages(second).value.single().content)
            assertTrue(reopened.conversations.value.any { it.id == first })
            assertTrue(reopened.conversations.value.any { it.id == second })
        } finally {
            val edit = prefs.edit()
            if (previous == null) edit.remove("state_v1") else edit.putString("state_v1", previous)
            assertTrue(edit.commit())
        }
    }
}
