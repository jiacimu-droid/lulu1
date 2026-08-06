package com.jiacimu.lulu.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.time.Instant

data class SharedTimelineEvent(
    val id: String,
    val characterId: String,
    val channel: String,
    val speaker: String,
    val content: String,
    val occurredAt: Instant,
)

/** Durable, raw, chronological record shared by every companion feature. */
object SharedExperienceTimeline {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var helper: TimelineDatabase? = null

    @Synchronized
    fun initialize(context: Context) {
        if (helper != null) return
        helper = TimelineDatabase(context.applicationContext)
        helper?.writableDatabase
    }

    fun backfillChatHistory() {
        MigratedDomainStores.chat.conversations.value.forEach { conversation ->
            MigratedDomainStores.chat.messages(conversation.id).value.forEach { message ->
                recordConversationMessage(conversation, message, triggerExtraction = false)
            }
        }
    }

    /**
     * A private chat belongs to its companion. A group message belongs to every member who was
     * present, with a per-character event id so one member can never overwrite another's copy.
     */
    fun recordConversationMessage(
        conversation: LuluConversation,
        message: LuluChatMessage,
        triggerExtraction: Boolean = true,
    ) {
        val group = conversation.groupChat
        if (group == null) {
            recordChatMessage(
                characterId = message.authorCharacterId ?: conversation.characterId,
                conversationId = conversation.id,
                message = message,
                triggerExtraction = triggerExtraction,
            )
            return
        }

        val speaker = when (message.sender) {
            LuluChatMessage.Sender.User -> group.userGroupNickname
            LuluChatMessage.Sender.Character -> {
                val authorId = message.authorCharacterId ?: conversation.characterId
                group.members.firstOrNull { it.characterId == authorId }
                    ?.groupNickname
                    ?.takeIf(String::isNotBlank)
                    ?: MigratedDomainStores.characters.get(authorId).displayName
            }
            LuluChatMessage.Sender.System -> "系统"
        }
        group.members.map(LuluGroupMember::characterId).distinct().forEach { memberId ->
            recordChatMessage(
                characterId = memberId,
                conversationId = conversation.id,
                message = message.copy(id = "${message.id}:group:$memberId"),
                channelOverride = "群聊·${group.name}",
                speakerOverride = speaker,
                triggerExtraction = triggerExtraction,
            )
        }
    }

    fun recordChatMessage(
        characterId: String,
        conversationId: String,
        message: LuluChatMessage,
        channelOverride: String? = null,
        speakerOverride: String? = null,
        triggerExtraction: Boolean = true,
    ) {
        val channel = channelOverride ?: "私聊"
        val speaker = speakerOverride ?: when (message.sender) {
            LuluChatMessage.Sender.User -> UserProfileContext.displayLabel()
            LuluChatMessage.Sender.Character -> "角色"
            LuluChatMessage.Sender.System -> "系统"
        }
        record(message.id, characterId, channel, speaker, message.content, message.createdAt, triggerExtraction)
    }

    fun record(
        eventId: String,
        characterId: String,
        channel: String,
        speaker: String,
        content: String,
        occurredAt: Instant = Instant.now(),
        triggerExtraction: Boolean = true,
    ) {
        val clean = content.trim()
        val database = helper?.writableDatabase ?: return
        if (eventId.isBlank() || characterId.isBlank() || clean.isBlank()) return
        val deleted = database.query(
            "deleted_timeline_events",
            arrayOf("event_id"),
            "event_id = ?",
            arrayOf(eventId),
            null,
            null,
            null,
            "1",
        ).use { it.moveToFirst() }
        if (deleted) return
        val values = ContentValues().apply {
            put("id", eventId)
            put("character_id", characterId)
            put("channel", channel.trim().ifBlank { "共同经历" })
            put("speaker", speaker.trim().ifBlank { "事件" })
            put("content", clean)
            put("occurred_at", occurredAt.toEpochMilli())
        }
        database.insertWithOnConflict("timeline_events", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        if (triggerExtraction) {
            scope.launch {
                val policy = LuluRepositories.memory.observePolicy(characterId).first()
                if (policy.autoSummarize) LuluRepositories.memory.summarizeNow(characterId)
            }
        }
    }

    fun all(characterId: String): List<SharedTimelineEvent> = query(characterId, null).sortedBy(SharedTimelineEvent::occurredAt)

    /** Permanently removes raw context and leaves a tombstone so history backfill cannot restore it. */
    fun deleteEvent(eventId: String) {
        if (eventId.isBlank()) return
        val database = helper?.writableDatabase ?: return
        database.beginTransaction()
        try {
            database.delete("timeline_events", "id = ?", arrayOf(eventId))
            database.insertWithOnConflict(
                "deleted_timeline_events",
                null,
                ContentValues().apply {
                    put("event_id", eventId)
                    put("deleted_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        scope.launch { LuluRepositories.memory.deleteDerivedFromEvent(eventId) }
    }

    fun deleteConversationMessage(conversation: LuluConversation, messageId: String) {
        val group = conversation.groupChat
        if (group == null) {
            deleteEvent(messageId)
        } else {
            group.members.map(LuluGroupMember::characterId).distinct().forEach { memberId ->
                deleteEvent("$messageId:group:$memberId")
            }
        }
    }

    fun deleteConversationData(conversation: LuluConversation, messages: List<LuluChatMessage>) {
        messages.forEach { message -> deleteConversationMessage(conversation, message.id) }
        val group = conversation.groupChat ?: return
        val database = helper?.writableDatabase ?: return
        val channels = arrayOf("群聊·${group.name}", "群聊电话·${group.name}")
        group.members.map(LuluGroupMember::characterId).distinct().forEach { characterId ->
            val ids = database.query(
                "timeline_events",
                arrayOf("id"),
                "character_id = ? AND channel IN (?, ?)",
                arrayOf(characterId, channels[0], channels[1]),
                null,
                null,
                null,
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            ids.forEach(::deleteEvent)
            deleteEvent("group-joined-${conversation.id}-$characterId")
        }
    }

    fun recentContext(characterId: String, limit: Int = 24, characterBudget: Int = 7_000): String {
        val events = query(characterId, limit).sortedBy(SharedTimelineEvent::occurredAt)
        if (events.isEmpty()) return ""
        val lines = events.map { event ->
            "[${event.occurredAt}] [${event.channel}] ${event.speaker}：${event.content.take(1_200)}"
        }
        val kept = mutableListOf<String>()
        var used = 0
        for (line in lines.asReversed()) {
            if (used + line.length > characterBudget && kept.isNotEmpty()) break
            kept += line
            used += line.length
        }
        return kept.asReversed().joinToString("\n")
    }

    /** Save a derived memory. Raw events are recorded separately and never replaced by this summary. */
    fun remember(
        memoryId: String,
        characterId: String,
        label: String,
        detail: String,
        occurredAt: Instant = Instant.now(),
        strength: Int = 5,
        source: String = "shared-experience",
    ) {
        val cleanDetail = detail.trim()
        if (memoryId.isBlank() || characterId.isBlank() || cleanDetail.isBlank()) return
        scope.launch {
            LuluRepositories.memory.upsert(
                MemoryEntry(
                    id = memoryId,
                    characterId = characterId,
                    content = "$label：${cleanDetail.take(2_400)}",
                    kind = MemoryKind.Timeline,
                    source = source,
                    occurredAt = occurredAt,
                    createdAt = Instant.now(),
                    strength = strength.coerceIn(1, 10),
                    pinned = false,
                    canRecallProactively = true,
                ),
            )
        }
    }

    private fun query(characterId: String, limit: Int?): List<SharedTimelineEvent> {
        val database = helper?.readableDatabase ?: return emptyList()
        val order = if (limit == null) "occurred_at ASC" else "occurred_at DESC"
        return database.query(
            "timeline_events",
            arrayOf("id", "character_id", "channel", "speaker", "content", "occurred_at"),
            "character_id = ?",
            arrayOf(characterId),
            null,
            null,
            order,
            limit?.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SharedTimelineEvent(
                            id = cursor.getString(0),
                            characterId = cursor.getString(1),
                            channel = cursor.getString(2),
                            speaker = cursor.getString(3),
                            content = cursor.getString(4),
                            occurredAt = Instant.ofEpochMilli(cursor.getLong(5)),
                        ),
                    )
                }
            }
        }
    }

    private class TimelineDatabase(context: Context) : SQLiteOpenHelper(context, "shared_experience_timeline.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE timeline_events (
                    id TEXT PRIMARY KEY NOT NULL,
                    character_id TEXT NOT NULL,
                    channel TEXT NOT NULL,
                    speaker TEXT NOT NULL,
                    content TEXT NOT NULL,
                    occurred_at INTEGER NOT NULL
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX timeline_character_time ON timeline_events(character_id, occurred_at)")
            createDeletionTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createDeletionTable(db)
        }

        private fun createDeletionTable(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS deleted_timeline_events (
                    event_id TEXT PRIMARY KEY NOT NULL,
                    deleted_at INTEGER NOT NULL
                )""".trimIndent(),
            )
        }
    }
}
