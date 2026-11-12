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


    /**
     * UI-02.2: a failed durable metadata write must not publish an in-memory
     * pin that disappears on reopen. The original state_v1 stays authoritative.
     */
    /** UI-02.2: read acknowledgement must not clear unread state on failed disk write. */

    /** UI-02.2: rejected chat commit must not mutate the independent favorites archive. */
    @Test fun rejectedFavoriteCommitDoesNotCreateOrphanedArchiveEntry() {
        val previous = prefs.getString(key, null)
        val favoritesPrefs = app.getSharedPreferences("lulu_user_message_favorites", Context.MODE_PRIVATE)
        val favoritesBefore = favoritesPrefs.getString("entries_v1", null)
        val messageId = "ui02-favorite-rejected-chat"
        val snapshot = JSONObject()
            .put("conversations", JSONArray().put(JSONObject()
                .put("id", conversationId).put("characterId", "ui02-character")
                .put("title", "Favorite rollback").put("updatedAt", "2026-01-01T00:00:00Z")))
            .put("messages", JSONObject().put(conversationId, JSONArray().put(JSONObject()
                .put("id", messageId).put("conversationId", conversationId)
                .put("sender", "User").put("content", "Favorite this exact text")
                .put("createdAt", "2026-01-01T00:00:00Z").put("status", "Sent"))))
            .toString()
        try {
            assertTrue(prefs.edit().putString(key, snapshot).commit())
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            val field = InMemoryLuluChatStore::class.java.getDeclaredField("prefs")
                .apply { isAccessible = true }
            val actual = field.get(store) as android.content.SharedPreferences
            val rejecting = java.lang.reflect.Proxy.newProxyInstance(
                actual.javaClass.classLoader,
                arrayOf(android.content.SharedPreferences::class.java),
            ) { _, method, args ->
                if (method.name != "edit") method.invoke(actual, *(args ?: emptyArray()))
                else {
                    val editor = actual.edit()
                    lateinit var proxy: android.content.SharedPreferences.Editor
                    proxy = java.lang.reflect.Proxy.newProxyInstance(
                        editor.javaClass.classLoader,
                        arrayOf(android.content.SharedPreferences.Editor::class.java),
                    ) { _, editorMethod, editorArgs ->
                        when (editorMethod.name) {
                            "commit" -> false
                            "apply" -> error("Favorite mutation must commit durably")
                            else -> {
                                val value = editorMethod.invoke(editor, *(editorArgs ?: emptyArray()))
                                if (value === editor) proxy else value
                            }
                        }
                    } as android.content.SharedPreferences.Editor
                    proxy
                }
            } as android.content.SharedPreferences
            field.set(store, rejecting)
            try {
                assertTrue(runCatching { store.toggleFavorite(messageId) }.exceptionOrNull() is IllegalStateException)
                assertFalse(store.messages(conversationId).value.single().favorite)
                assertEquals(snapshot, prefs.getString(key, null))
                assertEquals(favoritesBefore, favoritesPrefs.getString("entries_v1", null))
            } finally {
                field.set(store, actual)
            }
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            assertFalse(reopened.messages(conversationId).value.single().favorite)
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove(key) else editor.putString(key, previous)
            assertTrue(editor.commit())
        }
    }

    @Test fun rejectedFavoriteRemovalKeepsChatSnapshot() {
        val previous = prefs.getString(key, null)
        val favoritesPrefs = app.getSharedPreferences("lulu_user_message_favorites", Context.MODE_PRIVATE)
        val favoritesBefore = favoritesPrefs.getString("entries_v1", null)
        val messageId = "ui02-favorite-remove-rejected"
        val snapshot = JSONObject()
            .put("conversations", JSONArray().put(JSONObject()
                .put("id", conversationId).put("characterId", "ui02-character")
                .put("title", "Favorite rollback").put("updatedAt", "2026-01-01T00:00:00Z")))
            .put("messages", JSONObject().put(conversationId, JSONArray().put(JSONObject()
                .put("id", messageId).put("conversationId", conversationId)
                .put("sender", "User").put("content", "Favorite this exact text")
                .put("createdAt", "2026-01-01T00:00:00Z").put("status", "Sent").put("favorite", true))))
            .toString()
        try {
            assertTrue(prefs.edit().putString(key, snapshot).commit())
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            val field = InMemoryLuluChatStore::class.java.getDeclaredField("prefs")
                .apply { isAccessible = true }
            val actual = field.get(store) as android.content.SharedPreferences
            val rejecting = java.lang.reflect.Proxy.newProxyInstance(
                actual.javaClass.classLoader,
                arrayOf(android.content.SharedPreferences::class.java),
            ) { _, method, args ->
                if (method.name != "edit") method.invoke(actual, *(args ?: emptyArray()))
                else {
                    val editor = actual.edit()
                    lateinit var proxy: android.content.SharedPreferences.Editor
                    proxy = java.lang.reflect.Proxy.newProxyInstance(
                        editor.javaClass.classLoader,
                        arrayOf(android.content.SharedPreferences.Editor::class.java),
                    ) { _, editorMethod, editorArgs ->
                        when (editorMethod.name) {
                            "commit" -> false
                            "apply" -> error("Favorite mutation must commit durably")
                            else -> {
                                val value = editorMethod.invoke(editor, *(editorArgs ?: emptyArray()))
                                if (value === editor) proxy else value
                            }
                        }
                    } as android.content.SharedPreferences.Editor
                    proxy
                }
            } as android.content.SharedPreferences
            field.set(store, rejecting)
            try {
                assertTrue(runCatching { store.removeUserFavorite(messageId) }.exceptionOrNull() is IllegalStateException)
                assertTrue(store.messages(conversationId).value.single().favorite)
                assertEquals(snapshot, prefs.getString(key, null))
                assertEquals(favoritesBefore, favoritesPrefs.getString("entries_v1", null))
            } finally {
                field.set(store, actual)
            }
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            assertTrue(reopened.messages(conversationId).value.single().favorite)
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove(key) else editor.putString(key, previous)
            assertTrue(editor.commit())
        }
    }

    /** UI-02.2: archive rejection must restore the chat flag, not report a removal. */
    @Test fun rejectedArchiveRemovalRestoresFavoriteFlagAcrossReopen() {
        val previous = prefs.getString(key, null)
        val messageId = "ui02-archive-remove-rejected"
        val snapshot = JSONObject()
            .put("conversations", JSONArray().put(JSONObject()
                .put("id", conversationId).put("characterId", "ui02-character")
                .put("title", "Archive rollback").put("updatedAt", "2026-01-01T00:00:00Z")))
            .put("messages", JSONObject().put(conversationId, JSONArray().put(JSONObject()
                .put("id", messageId).put("conversationId", conversationId)
                .put("sender", "User").put("content", "Keep the favorite")
                .put("createdAt", "2026-01-01T00:00:00Z").put("status", "Sent").put("favorite", true))))
            .toString()
        val archivePrefs = app.getSharedPreferences("lulu_user_message_favorites", Context.MODE_PRIVATE)
        val archiveBefore = archivePrefs.getString("entries_v1", null)
        val instanceField = UserMessageFavorites::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val originalInstance = instanceField.get(UserMessageFavorites)
        var archiveStore: UserMessageFavoriteStore? = null
        var archiveField: java.lang.reflect.Field? = null
        var realPrefs: android.content.SharedPreferences? = null
        try {
            assertTrue(prefs.edit().putString(key, snapshot).commit())
            instanceField.set(UserMessageFavorites, null)
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            archiveStore = UserMessageFavorites.store
            val message = store.messages(conversationId).value.single()
            assertTrue(archiveStore.save(message, "ui02-character", "我", null, "Archive rollback"))
            assertTrue(archiveStore.contains(messageId))
            archiveField = UserMessageFavoriteStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            val actualPrefs = archiveField.get(archiveStore) as android.content.SharedPreferences
            realPrefs = actualPrefs
            val rejecting = java.lang.reflect.Proxy.newProxyInstance(
                actualPrefs.javaClass.classLoader,
                arrayOf(android.content.SharedPreferences::class.java),
            ) { _, method, args ->
                if (method.name != "edit") method.invoke(actualPrefs, *(args ?: emptyArray()))
                else {
                    val editor = actualPrefs.edit()
                    lateinit var proxy: android.content.SharedPreferences.Editor
                    proxy = java.lang.reflect.Proxy.newProxyInstance(
                        editor.javaClass.classLoader,
                        arrayOf(android.content.SharedPreferences.Editor::class.java),
                    ) { _, editorMethod, editorArgs ->
                        when (editorMethod.name) {
                            "commit" -> false
                            "apply" -> error("Archive must use durable commit")
                            else -> {
                                val value = editorMethod.invoke(editor, *(editorArgs ?: emptyArray()))
                                if (value === editor) proxy else value
                            }
                        }
                    } as android.content.SharedPreferences.Editor
                    proxy
                }
            } as android.content.SharedPreferences
            archiveField.set(archiveStore, rejecting)
            assertFalse(store.removeUserFavorite(messageId))
            assertTrue(store.messages(conversationId).value.single().favorite)
            assertTrue(archiveStore.contains(messageId))
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            assertTrue(reopened.messages(conversationId).value.single().favorite)
        } finally {
            if (archiveStore != null && archiveField != null && realPrefs != null) archiveField.set(archiveStore, realPrefs)
            instanceField.set(UserMessageFavorites, originalInstance)
            val chatEditor = prefs.edit()
            if (previous == null) chatEditor.remove(key) else chatEditor.putString(key, previous)
            assertTrue(chatEditor.commit())
            val archiveEditor = archivePrefs.edit()
            if (archiveBefore == null) archiveEditor.remove("entries_v1") else archiveEditor.putString("entries_v1", archiveBefore)
            assertTrue(archiveEditor.commit())
        }
    }


    /** UI-02.2: both archive rejection and failed chat compensation must surface a failure.
     * The archive remains authoritative; a second rejected chat commit is NOT atomic recovery. */
    @Test fun rejectedArchiveAndCompensationCannotReportFavoriteRemovalSuccess() {
        val previous = prefs.getString(key, null)
        val messageId = "ui02-double-favorite-rejection"
        val snapshot = JSONObject()
            .put("conversations", JSONArray().put(JSONObject()
                .put("id", conversationId).put("characterId", "ui02-character")
                .put("title", "Double rejection").put("updatedAt", "2026-01-01T00:00:00Z")))
            .put("messages", JSONObject().put(conversationId, JSONArray().put(JSONObject()
                .put("id", messageId).put("conversationId", conversationId)
                .put("sender", "User").put("content", "Preserve original favorite")
                .put("createdAt", "2026-01-01T00:00:00Z").put("status", "Sent").put("favorite", true))))
            .toString()
        val archivePrefs = app.getSharedPreferences("lulu_user_message_favorites", Context.MODE_PRIVATE)
        val archiveBefore = archivePrefs.getString("entries_v1", null)
        val instanceField = UserMessageFavorites::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val originalInstance = instanceField.get(UserMessageFavorites)
        var archiveStore: UserMessageFavoriteStore? = null
        var archiveField: java.lang.reflect.Field? = null
        var originalArchivePrefs: android.content.SharedPreferences? = null
        try {
            assertTrue(prefs.edit().putString(key, snapshot).commit())
            instanceField.set(UserMessageFavorites, null)
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            archiveStore = UserMessageFavorites.store
            assertTrue(archiveStore.save(store.messages(conversationId).value.single(),
                "ui02-character", "我", null, "Double rejection"))
            archiveField = UserMessageFavoriteStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            val actualArchivePrefs = archiveField.get(archiveStore) as android.content.SharedPreferences
            originalArchivePrefs = actualArchivePrefs
            val chatField = InMemoryLuluChatStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            val originalChatPrefs = chatField.get(store) as android.content.SharedPreferences
            fun intercept(
                original: android.content.SharedPreferences,
                allowCommit: () -> Boolean,
            ): android.content.SharedPreferences = java.lang.reflect.Proxy.newProxyInstance(
                original.javaClass.classLoader,
                arrayOf(android.content.SharedPreferences::class.java),
            ) { _, method, args ->
                if (method.name != "edit") method.invoke(original, *(args ?: emptyArray()))
                else {
                    val editor = original.edit()
                    lateinit var proxy: android.content.SharedPreferences.Editor
                    proxy = java.lang.reflect.Proxy.newProxyInstance(
                        editor.javaClass.classLoader,
                        arrayOf(android.content.SharedPreferences.Editor::class.java),
                    ) { _, editorMethod, editorArgs ->
                        when (editorMethod.name) {
                            "commit" -> if (allowCommit()) editor.commit() else false
                            "apply" -> error("Favorites must use durable commit")
                            else -> {
                                val value = editorMethod.invoke(editor, *(editorArgs ?: emptyArray()))
                                if (value === editor) proxy else value
                            }
                        }
                    } as android.content.SharedPreferences.Editor
                    proxy
                }
            } as android.content.SharedPreferences
            var chatCommitCount = 0
            chatField.set(store, intercept(originalChatPrefs) { ++chatCommitCount == 1 })
            archiveField.set(archiveStore, intercept(actualArchivePrefs) { false })
            try {
                val failure = runCatching { store.removeUserFavorite(messageId) }.exceptionOrNull()
                assertTrue("Failed compensation must not be reported as successful removal",
                    failure is IllegalStateException)
                assertEquals(2, chatCommitCount)
                assertTrue("Archive must remain intact", archiveStore.contains(messageId))
                assertEquals(archivePrefs.getString("entries_v1", null),
                    actualArchivePrefs.getString("entries_v1", null))
                // The first chat commit succeeded, but the archive remains the
                // durable saved-content authority. Reopen must repair the flag.
                // Reconciliation itself may fail to commit. Do not publish a
                // repaired flag that is absent from the durable chat snapshot.
                var rejectedRecoveryCommits = 0
                val rejectingContext = object : android.content.ContextWrapper(app) {
                    // ChatStore.initialize reads applicationContext, not the wrapper itself.
                    // Return this wrapper so the injected failed commit reaches that path.
                    override fun getApplicationContext(): android.content.Context = this
                    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                        if (name == "lulu_chat_store")
                            intercept(originalChatPrefs) { rejectedRecoveryCommits++; false }
                        else super.getSharedPreferences(name, mode)
                }
                val failedRecovery = InMemoryLuluChatStore()
                failedRecovery.initialize(rejectingContext)
                assertEquals(1, rejectedRecoveryCommits)
                assertFalse(failedRecovery.messages(conversationId).value.single().favorite)
                assertFalse(JSONObject(prefs.getString(key, null)!!)
                    .getJSONObject("messages").getJSONArray(conversationId)
                    .getJSONObject(0).getBoolean("favorite"))
                assertTrue(archiveStore.contains(messageId))
                // Once storage accepts writes, a fresh initialization repairs
                // the original flag without deleting saved content.
                val reopened = InMemoryLuluChatStore()
                reopened.initialize(app)
                assertTrue(reopened.messages(conversationId).value.single().favorite)
                assertTrue(archiveStore.contains(messageId))
                val reopenedAgain = InMemoryLuluChatStore()
                reopenedAgain.initialize(app)
                assertTrue(reopenedAgain.messages(conversationId).value.single().favorite)
            } finally {
                chatField.set(store, originalChatPrefs)
            }
        } finally {
            if (archiveStore != null && archiveField != null && originalArchivePrefs != null)
                archiveField.set(archiveStore, originalArchivePrefs)
            instanceField.set(UserMessageFavorites, originalInstance)
            val editor = prefs.edit()
            if (previous == null) editor.remove(key) else editor.putString(key, previous)
            assertTrue(editor.commit())
            val archiveEditor = archivePrefs.edit()
            if (archiveBefore == null) archiveEditor.remove("entries_v1") else archiveEditor.putString("entries_v1", archiveBefore)
            assertTrue(archiveEditor.commit())
        }
    }

    @Test fun rejectedReadAcknowledgementPreservesUnreadAcrossReopen() {
        val previous = prefs.getString(key, null)
        val conversation = JSONObject()
            .put("id", conversationId)
            .put("characterId", "ui02-character")
            .put("title", "Unread rollback")
            .put("unreadCount", 2)
            .put("updatedAt", "2026-01-01T00:00:00Z")
        val snapshot = JSONObject()
            .put("conversations", JSONArray().put(conversation))
            .put("messages", JSONObject().put(conversationId, JSONArray()))
            .toString()
        try {
            assertTrue(prefs.edit().putString(key, snapshot).commit())
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            assertEquals(2, store.conversations.value.first { it.id == conversationId }.unreadCount)
            val field = InMemoryLuluChatStore::class.java.getDeclaredField("prefs")
                .apply { isAccessible = true }
            val actual = field.get(store) as android.content.SharedPreferences
            val rejected = java.lang.reflect.Proxy.newProxyInstance(
                actual.javaClass.classLoader,
                arrayOf(android.content.SharedPreferences::class.java),
            ) { _, method, args ->
                if (method.name != "edit") {
                    method.invoke(actual, *(args ?: emptyArray()))
                } else {
                    val editor = actual.edit()
                    lateinit var proxy: android.content.SharedPreferences.Editor
                    proxy = java.lang.reflect.Proxy.newProxyInstance(
                        editor.javaClass.classLoader,
                        arrayOf(android.content.SharedPreferences.Editor::class.java),
                    ) { _, editorMethod, editorArgs ->
                        when (editorMethod.name) {
                            "commit" -> false
                            "apply" -> error("Read acknowledgement must be durable")
                            else -> {
                                val result = editorMethod.invoke(editor, *(editorArgs ?: emptyArray()))
                                if (result === editor) proxy else result
                            }
                        }
                    } as android.content.SharedPreferences.Editor
                    proxy
                }
            } as android.content.SharedPreferences
            field.set(store, rejected)
            try {
                val failure = runCatching { store.markConversationRead(conversationId) }.exceptionOrNull()
                assertTrue("Rejected read acknowledgement must surface failure", failure is IllegalStateException)
                assertEquals(2, store.conversations.value.first { it.id == conversationId }.unreadCount)
                assertEquals(snapshot, prefs.getString(key, null))
            } finally {
                field.set(store, actual)
            }
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            assertEquals(2, reopened.conversations.value.first { it.id == conversationId }.unreadCount)
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove(key) else editor.putString(key, previous)
            assertTrue(editor.commit())
        }
    }

    @Test fun rejectedPinCommitKeepsMemoryAndReopenedSnapshotUnchanged() {
        val previous = prefs.getString(key, null)
        val conversation = JSONObject()
            .put("id", conversationId)
            .put("characterId", "ui02-character")
            .put("title", "Durable pin failure")
            .put("updatedAt", "2026-01-01T00:00:00Z")
        val snapshot = JSONObject()
            .put("conversations", JSONArray().put(conversation))
            .put("messages", JSONObject().put(conversationId, JSONArray()))
            .toString()
        try {
            assertTrue(prefs.edit().putString(key, snapshot).commit())
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            val field = InMemoryLuluChatStore::class.java.getDeclaredField("prefs")
                .apply { isAccessible = true }
            val actual = field.get(store) as android.content.SharedPreferences
            val rejected = java.lang.reflect.Proxy.newProxyInstance(
                actual.javaClass.classLoader,
                arrayOf(android.content.SharedPreferences::class.java),
            ) { _, method, args ->
                if (method.name != "edit") {
                    method.invoke(actual, *(args ?: emptyArray()))
                } else {
                    val editor = actual.edit()
                    lateinit var proxy: android.content.SharedPreferences.Editor
                    proxy = java.lang.reflect.Proxy.newProxyInstance(
                        editor.javaClass.classLoader,
                        arrayOf(android.content.SharedPreferences.Editor::class.java),
                    ) { _, editorMethod, editorArgs ->
                        when (editorMethod.name) {
                            "commit" -> false
                            "apply" -> error("Durable pin must use commit, not apply")
                            else -> {
                                val result = editorMethod.invoke(editor, *(editorArgs ?: emptyArray()))
                                if (result === editor) proxy else result
                            }
                        }
                    } as android.content.SharedPreferences.Editor
                    proxy
                }
            } as android.content.SharedPreferences
            field.set(store, rejected)
            try {
                val failure = runCatching { store.setConversationPinned(conversationId, true) }.exceptionOrNull()
                assertTrue("A rejected durable commit must surface failure", failure is IllegalStateException)
                assertFalse(store.conversations.value.first { it.id == conversationId }.pinned)
                assertEquals(snapshot, prefs.getString(key, null))
            } finally {
                field.set(store, actual)
            }
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            assertFalse(reopened.conversations.value.first { it.id == conversationId }.pinned)
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove(key) else editor.putString(key, previous)
            assertTrue(editor.commit())
        }
    }

    /** UI-02.2: rejected durable character delivery cannot leave a phantom bubble. */
    @Test fun rejectedCharacterAppendRestoresMessagesAndConversationOnReopen() {
        val previous = prefs.getString(key, null)
        val conversation = JSONObject()
            .put("id", conversationId)
            .put("characterId", "ui02-character")
            .put("title", "Durable append failure")
            .put("updatedAt", "2026-01-01T00:00:00Z")
        val snapshot = JSONObject()
            .put("conversations", JSONArray().put(conversation))
            .put("messages", JSONObject().put(conversationId, JSONArray()))
            .toString()
        try {
            assertTrue(prefs.edit().putString(key, snapshot).commit())
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            val field = InMemoryLuluChatStore::class.java.getDeclaredField("prefs")
                .apply { isAccessible = true }
            val actual = field.get(store) as android.content.SharedPreferences
            val rejected = java.lang.reflect.Proxy.newProxyInstance(
                actual.javaClass.classLoader,
                arrayOf(android.content.SharedPreferences::class.java),
            ) { _, method, args ->
                if (method.name != "edit") {
                    method.invoke(actual, *(args ?: emptyArray()))
                } else {
                    val editor = actual.edit()
                    lateinit var proxy: android.content.SharedPreferences.Editor
                    proxy = java.lang.reflect.Proxy.newProxyInstance(
                        editor.javaClass.classLoader,
                        arrayOf(android.content.SharedPreferences.Editor::class.java),
                    ) { _, editorMethod, editorArgs ->
                        when (editorMethod.name) {
                            "commit" -> false
                            "apply" -> error("Background character delivery must be durable")
                            else -> {
                                val result = editorMethod.invoke(editor, *(editorArgs ?: emptyArray()))
                                if (result === editor) proxy else result
                            }
                        }
                    } as android.content.SharedPreferences.Editor
                    proxy
                }
            } as android.content.SharedPreferences
            field.set(store, rejected)
            try {
                val before = store.conversations.value
                val failure = runCatching {
                    store.appendCharacterMessage(conversationId, "A message that must not appear")
                }.exceptionOrNull()
                assertTrue("Rejected character delivery must fail", failure is IllegalStateException)
                assertTrue("No phantom bubble may remain", store.messages(conversationId).value.isEmpty())
                assertEquals("Conversation metadata must roll back", before, store.conversations.value)
                assertEquals(snapshot, prefs.getString(key, null))
            } finally {
                field.set(store, actual)
            }
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            assertTrue(reopened.messages(conversationId).value.isEmpty())
            assertEquals("", reopened.conversations.value.first { it.id == conversationId }.lastMessage)
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove(key) else editor.putString(key, previous)
            assertTrue(editor.commit())
        }
    }


    /** UI-02.2: rejected edit/delete must retain both durable text and visible history. */
    @Test fun rejectedMessageMutationPreservesOriginalAndReopenedHistory() {
        val previous = prefs.getString(key, null)
        val message = JSONObject()
            .put("id", "ui02-original-message")
            .put("conversationId", conversationId)
            .put("sender", "User")
            .put("content", "Original user wording")
            .put("createdAt", "2026-01-01T00:01:00Z")
            .put("status", "Sent")
        val conversation = JSONObject()
            .put("id", conversationId)
            .put("characterId", "ui02-character")
            .put("title", "Mutation rollback")
            .put("updatedAt", "2026-01-01T00:01:00Z")
        val snapshot = JSONObject()
            .put("conversations", JSONArray().put(conversation))
            .put("messages", JSONObject().put(conversationId, JSONArray().put(message)))
            .toString()
        try {
            assertTrue(prefs.edit().putString(key, snapshot).commit())
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            val field = InMemoryLuluChatStore::class.java.getDeclaredField("prefs")
                .apply { isAccessible = true }
            val actual = field.get(store) as android.content.SharedPreferences
            val rejected = java.lang.reflect.Proxy.newProxyInstance(
                actual.javaClass.classLoader,
                arrayOf(android.content.SharedPreferences::class.java),
            ) { _, method, args ->
                if (method.name != "edit") {
                    method.invoke(actual, *(args ?: emptyArray()))
                } else {
                    val editor = actual.edit()
                    lateinit var proxy: android.content.SharedPreferences.Editor
                    proxy = java.lang.reflect.Proxy.newProxyInstance(
                        editor.javaClass.classLoader,
                        arrayOf(android.content.SharedPreferences.Editor::class.java),
                    ) { _, editorMethod, editorArgs ->
                        when (editorMethod.name) {
                            "commit" -> false
                            "apply" -> error("Message mutations must not silently apply")
                            else -> {
                                val result = editorMethod.invoke(editor, *(editorArgs ?: emptyArray()))
                                if (result === editor) proxy else result
                            }
                        }
                    } as android.content.SharedPreferences.Editor
                    proxy
                }
            } as android.content.SharedPreferences
            val originalMessages = store.messages(conversationId).value
            val originalConversations = store.conversations.value
            field.set(store, rejected)
            try {
                for (mutation in listOf<() -> Boolean>(
                    { store.editMessage("ui02-original-message", "Changed wording") },
                    { store.deleteMessage("ui02-original-message") },
                )) {
                    assertTrue(runCatching { mutation() }.exceptionOrNull() is IllegalStateException)
                    assertEquals(originalMessages, store.messages(conversationId).value)
                    assertEquals(originalConversations, store.conversations.value)
                    assertEquals(snapshot, prefs.getString(key, null))
                }
            } finally {
                field.set(store, actual)
            }
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            assertEquals("Original user wording", reopened.messages(conversationId).value.single().content)
            assertEquals(originalConversations, reopened.conversations.value)
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove(key) else editor.putString(key, previous)
            assertTrue(editor.commit())
        }
    }

    /** UI-02.2: rejected conversation clear/delete must preserve both the chat and its durable history. */
    @Test fun rejectedConversationRemovalPreservesOriginalAndReopenedHistory() {
        val previous = prefs.getString(key, null)
        val message = JSONObject()
            .put("id", "ui02-original-message")
            .put("conversationId", conversationId)
            .put("sender", "User")
            .put("content", "Original user wording")
            .put("createdAt", "2026-01-01T00:01:00Z")
            .put("status", "Sent")
        val conversation = JSONObject()
            .put("id", conversationId)
            .put("characterId", "ui02-character")
            .put("title", "Conversation removal rollback")
            .put("updatedAt", "2026-01-01T00:01:00Z")
        val snapshot = JSONObject()
            .put("conversations", JSONArray().put(conversation))
            .put("messages", JSONObject().put(conversationId, JSONArray().put(message)))
            .toString()
        try {
            assertTrue(prefs.edit().putString(key, snapshot).commit())
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            val field = InMemoryLuluChatStore::class.java.getDeclaredField("prefs")
                .apply { isAccessible = true }
            val actual = field.get(store) as android.content.SharedPreferences
            val rejected = java.lang.reflect.Proxy.newProxyInstance(
                actual.javaClass.classLoader,
                arrayOf(android.content.SharedPreferences::class.java),
            ) { _, method, args ->
                if (method.name != "edit") {
                    method.invoke(actual, *(args ?: emptyArray()))
                } else {
                    val editor = actual.edit()
                    lateinit var proxy: android.content.SharedPreferences.Editor
                    proxy = java.lang.reflect.Proxy.newProxyInstance(
                        editor.javaClass.classLoader,
                        arrayOf(android.content.SharedPreferences.Editor::class.java),
                    ) { _, editorMethod, editorArgs ->
                        when (editorMethod.name) {
                            "commit" -> false
                            "apply" -> error("Conversation removal must not silently apply")
                            else -> {
                                val result = editorMethod.invoke(editor, *(editorArgs ?: emptyArray()))
                                if (result === editor) proxy else result
                            }
                        }
                    } as android.content.SharedPreferences.Editor
                    proxy
                }
            } as android.content.SharedPreferences
            val originalMessages = store.messages(conversationId).value
            val originalConversations = store.conversations.value
            field.set(store, rejected)
            try {
                for (mutation in listOf<() -> Boolean>(
                    { store.clearConversationMessages(conversationId) },
                    { store.deleteConversation(conversationId) },
                )) {
                    assertTrue(runCatching { mutation() }.exceptionOrNull() is IllegalStateException)
                    assertEquals(originalMessages, store.messages(conversationId).value)
                    assertEquals(originalConversations, store.conversations.value)
                    assertEquals(snapshot, prefs.getString(key, null))
                }
            } finally {
                field.set(store, actual)
            }
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            assertEquals("Original user wording", reopened.messages(conversationId).value.single().content)
            assertEquals(originalConversations, reopened.conversations.value)
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove(key) else editor.putString(key, previous)
            assertTrue(editor.commit())
        }
    }


    /** UI-02.2: regenerate must not retire the original bubble or rebind its sources on a failed commit. */
    @Test fun rejectedReplyRegenerationKeepsOriginalAfterStoreReopen() {
        val previous = prefs.getString(key, null)
        val originalId = "ui02-original-character-reply"
        val message = JSONObject()
            .put("id", originalId)
            .put("conversationId", conversationId)
            .put("sender", "Character")
            .put("content", "Original character reply")
            .put("createdAt", "2026-01-01T00:01:00Z")
            .put("status", "Sent")
        val conversation = JSONObject()
            .put("id", conversationId)
            .put("characterId", "ui02-character")
            .put("title", "Reply regeneration rollback")
            .put("updatedAt", "2026-01-01T00:01:00Z")
        val snapshot = JSONObject()
            .put("conversations", JSONArray().put(conversation))
            .put("messages", JSONObject().put(conversationId, JSONArray().put(message)))
            .toString()
        try {
            assertTrue(prefs.edit().putString(key, snapshot).commit())
            val store = InMemoryLuluChatStore()
            store.initialize(app)
            val field = InMemoryLuluChatStore::class.java.getDeclaredField("prefs")
                .apply { isAccessible = true }
            val actual = field.get(store) as android.content.SharedPreferences
            val rejected = java.lang.reflect.Proxy.newProxyInstance(
                actual.javaClass.classLoader,
                arrayOf(android.content.SharedPreferences::class.java),
            ) { _, method, args ->
                if (method.name != "edit") {
                    method.invoke(actual, *(args ?: emptyArray()))
                } else {
                    val editor = actual.edit()
                    lateinit var proxy: android.content.SharedPreferences.Editor
                    proxy = java.lang.reflect.Proxy.newProxyInstance(
                        editor.javaClass.classLoader,
                        arrayOf(android.content.SharedPreferences.Editor::class.java),
                    ) { _, editorMethod, editorArgs ->
                        when (editorMethod.name) {
                            "commit" -> false
                            "apply" -> error("Reply regeneration must not silently apply")
                            else -> {
                                val result = editorMethod.invoke(editor, *(editorArgs ?: emptyArray()))
                                if (result === editor) proxy else result
                            }
                        }
                    } as android.content.SharedPreferences.Editor
                    proxy
                }
            } as android.content.SharedPreferences
            val originalMessages = store.messages(conversationId).value
            val originalConversations = store.conversations.value
            field.set(store, rejected)
            try {
                val failure = runCatching {
                    store.replaceCharacterReply(conversationId, listOf(originalId), listOf("Regenerated reply"))
                }.exceptionOrNull()
                assertTrue("Rejected regeneration must report failure", failure is IllegalStateException)
                assertEquals(originalMessages, store.messages(conversationId).value)
                assertEquals(originalConversations, store.conversations.value)
                assertEquals(snapshot, prefs.getString(key, null))
            } finally {
                field.set(store, actual)
            }
            val reopened = InMemoryLuluChatStore()
            reopened.initialize(app)
            assertEquals(originalMessages, reopened.messages(conversationId).value)
            assertEquals(originalConversations, reopened.conversations.value)
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove(key) else editor.putString(key, previous)
            assertTrue(editor.commit())
        }
    }


    /** UI-02.2: a single corrupt item in a large legacy snapshot cannot be silently
     * skipped and replaced by an empty/default history on a metadata write. */
    @Test fun malformedItemInThousandMessageSnapshotCannotBeOverwritten() {
        val previous = prefs.getString(key, null)
        try {
            val messages = JSONArray()
            repeat(1_000) { index ->
                messages.put(JSONObject()
                    .put("id", "ui02-migration-$index")
                    .put("conversationId", conversationId)
                    .put("sender", "User")
                    .put("content", "Historical message $index")
                    .put("createdAt", "2026-01-01T00:00:00Z")
                    .put("status", "Sent"))
            }
            // One damaged row must not turn the other 999 durable rows into defaults.
            messages.getJSONObject(731).remove("id")
            val snapshot = JSONObject()
                .put("conversations", JSONArray().put(JSONObject()
                    .put("id", conversationId)
                    .put("characterId", "ui02-character")
                    .put("title", "Migration failure authority")
                    .put("updatedAt", "2026-01-01T00:00:00Z")))
                .put("messages", JSONObject().put(conversationId, messages))
                .toString()
            assertTrue(prefs.edit().putString(key, snapshot).commit())
            repeat(2) {
                val store = InMemoryLuluChatStore()
                store.initialize(app)
                assertTrue(runCatching {
                    // The damaged snapshot is not loaded as a normal conversation.
                    // Exercise an actual default-conversation metadata write instead
                    // of a missing-ID no-op (which only returns false).
                    val fallback = store.conversations.value.first { !it.pinned }
                    store.setConversationPinned(fallback.id, true)
                }.exceptionOrNull() is IllegalStateException)
                assertEquals(snapshot, prefs.getString(key, null))
            }
            assertEquals(1_000, JSONObject(prefs.getString(key, null)!!)
                .getJSONObject("messages").getJSONArray(conversationId).length())
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove(key) else editor.putString(key, previous)
            assertTrue(editor.commit())
        }
    }

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
            // Measure cold and repeated metadata-only writes independently.
            // No timing threshold: a CI runner is not an Android ANR benchmark.
            val writeMillis = mutableListOf<Double>()
            for (pinned in listOf(true, false, true)) {
                val writeStart = SystemClock.elapsedRealtimeNanos()
                assertTrue(store.setConversationPinned(conversationId, pinned))
                writeMillis += (SystemClock.elapsedRealtimeNanos() - writeStart) / 1_000_000.0
                assertEquals(pinned, store.conversations.value.first { it.id == conversationId }.pinned)
            }
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
