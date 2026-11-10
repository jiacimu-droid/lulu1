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
