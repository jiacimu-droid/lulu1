package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

data class UserMessageFavorite(
    val messageId: String,
    val conversationId: String,
    val characterId: String,
    val authorName: String,
    val avatarUri: String?,
    val conversationTitle: String,
    val content: String,
    val messageAt: Instant,
    val savedAt: Instant,
    val audioName: String = "",
    val characterMessage: Boolean = true,
)

/** Independent snapshots: deleting a chat or pruning its cache cannot erase a favorite. */
internal class UserMessageFavoriteStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lulu_user_message_favorites", Context.MODE_PRIVATE)
    private val audioDirectory = File(context.applicationContext.filesDir, "favorite_voice")
    private val mutableEntries = MutableStateFlow(load())
    val entries = mutableEntries.asStateFlow()

    @Synchronized
    fun save(message: LuluChatMessage, characterId: String, authorName: String, avatarUri: String?,
        conversationTitle: String, cachedAudio: File? = null): Boolean {
        if (message.sender == LuluChatMessage.Sender.System) return false
        val existing = mutableEntries.value.firstOrNull { it.messageId == message.id }
        val entry = existing ?: UserMessageFavorite(message.id, message.conversationId, characterId,
            authorName, avatarUri, conversationTitle, message.content, message.createdAt, Instant.now(),
            characterMessage = message.sender == LuluChatMessage.Sender.Character)
        val audioName = if (entry.audioName.isBlank()) copyAudio(message.id, cachedAudio) else entry.audioName
        return persist(mutableEntries.value.filterNot { it.messageId == message.id } + entry.copy(audioName = audioName))
    }

    @Synchronized
    fun retainAudio(messageId: String, audio: File): Boolean {
        val entry = mutableEntries.value.firstOrNull { it.messageId == messageId } ?: return false
        if (audioFile(entry) != null) return true // Keep the original performance, never replace it.
        val name = copyAudio(messageId, audio)
        if (name.isBlank()) return false
        return persist(mutableEntries.value.map { if (it.messageId == messageId) it.copy(audioName = name) else it })
    }

    @Synchronized
    fun remove(messageId: String): Boolean {
        val entry = mutableEntries.value.firstOrNull { it.messageId == messageId } ?: return true
        if (!persist(mutableEntries.value.filterNot { it.messageId == messageId })) return false
        audioFile(entry)?.delete()
        return true
    }

    fun contains(messageId: String): Boolean = mutableEntries.value.any { it.messageId == messageId }

    fun audioFile(entry: UserMessageFavorite): File? = entry.audioName
        .takeIf { it.matches(Regex("[a-f0-9]{64}\\.(mp3|wav)")) }
        ?.let { File(audioDirectory, it) }?.takeIf { it.isFile && it.length() > 0L }

    private fun copyAudio(messageId: String, source: File?): String {
        if (source == null || !source.isFile || source.length() == 0L || source.extension !in setOf("mp3", "wav")) return ""
        return runCatching {
            check(audioDirectory.isDirectory || audioDirectory.mkdirs())
            val hash = MessageDigest.getInstance("SHA-256").digest(messageId.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val target = File(audioDirectory, "$hash.${source.extension}")
            val temporary = File(audioDirectory, "$hash.tmp")
            try {
                source.copyTo(temporary, overwrite = true)
                check(temporary.length() == source.length() && temporary.length() > 0L)
                check(temporary.renameTo(target))
            } finally { temporary.delete() }
            target.name
        }.getOrElse { throw IllegalStateException("收藏语音保存失败", it) }
    }

    private fun persist(values: List<UserMessageFavorite>): Boolean {
        val ordered = values.sortedByDescending { it.savedAt }
        val json = JSONArray().apply { ordered.forEach { value ->
            put(JSONObject().put("messageId", value.messageId).put("conversationId", value.conversationId)
                .put("characterId", value.characterId).put("authorName", value.authorName)
                .put("avatarUri", value.avatarUri ?: JSONObject.NULL).put("conversationTitle", value.conversationTitle)
                .put("content", value.content).put("messageAt", value.messageAt.toString())
                .put("savedAt", value.savedAt.toString()).put("audioName", value.audioName)
                .put("characterMessage", value.characterMessage))
        } }
        if (!prefs.edit().putString("entries_v1", json.toString()).commit()) return false
        mutableEntries.value = ordered
        return true
    }

    private fun load(): List<UserMessageFavorite> = runCatching {
        val values = JSONArray(prefs.getString("entries_v1", "[]"))
        buildList { for (i in 0 until values.length()) {
            val item = values.optJSONObject(i) ?: continue
            runCatching { UserMessageFavorite(item.getString("messageId"), item.getString("conversationId"),
                item.optString("characterId"), item.optString("authorName"),
                item.optString("avatarUri").takeIf { it.isNotBlank() && it != "null" }, item.optString("conversationTitle"),
                item.getString("content"), Instant.parse(item.getString("messageAt")),
                Instant.parse(item.getString("savedAt")), item.optString("audioName"),
                item.optBoolean("characterMessage", item.optString("authorName") != "我")) }.getOrNull()?.let(::add)
        } }.distinctBy { it.messageId }.sortedByDescending { it.savedAt }
    }.getOrDefault(emptyList())
}

internal object UserMessageFavorites {
    private var instance: UserMessageFavoriteStore? = null
    val store: UserMessageFavoriteStore get() = checkNotNull(instance)

    @Synchronized
    fun initialize(context: Context) {
        if (instance == null) instance = UserMessageFavoriteStore(context)
    }

    fun retainAudio(messageId: String, audio: File) { instance?.retainAudio(messageId, audio) }
}
