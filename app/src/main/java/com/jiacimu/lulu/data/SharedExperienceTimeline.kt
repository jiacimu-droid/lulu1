package com.jiacimu.lulu.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.qqForwardContextText
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class SharedTimelineEvent(
    val id: String,
    val characterId: String,
    val channel: String,
    val speaker: String,
    val content: String,
    val occurredAt: Instant,
    val sessionId: String = "",
    val source: String = "legacy",
    val evidenceKind: EventEvidenceKind = EventEvidenceKind.Legacy,
    val taskId: String = "",
    val revision: Long = 1,
)

/** Durable, raw, chronological record shared by every companion feature. */
object SharedExperienceTimeline {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var helper: TimelineDatabase? = null

    @Synchronized
    fun initialize(context: Context) {
        if (helper != null) return
        val application = context.applicationContext
        helper = TimelineDatabase(application)
        helper?.writableDatabase
        MemoryExtractionJobStore.initialize(application)
    }

    fun backfillChatHistory() {
        MigratedDomainStores.chat.conversations.value.forEach { conversation ->
            MigratedDomainStores.chat.messages(conversation.id).value.forEach { message ->
                recordConversationMessage(conversation, message, triggerExtraction = false)
            }
        }
    }

    fun recordConversationMessage(
        conversation: LuluConversation,
        message: LuluChatMessage,
        triggerExtraction: Boolean = true,
    ) {
        if (message.sender == LuluChatMessage.Sender.System && message.content.startsWith("[共同活动]")) return
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
        val readableContent = qqForwardContextText(message.content)
        record(message.id, characterId, channel, speaker, readableContent, message.createdAt, triggerExtraction,
            sessionId = conversationId, source = "message",
            evidenceKind = when (message.sender) {
                LuluChatMessage.Sender.User -> EventEvidenceKind.UserStatement
                LuluChatMessage.Sender.Character -> EventEvidenceKind.CharacterStatement
                LuluChatMessage.Sender.System -> EventEvidenceKind.Observation
            })
    }

    @Synchronized
    fun record(
        eventId: String,
        characterId: String,
        channel: String,
        speaker: String,
        content: String,
        occurredAt: Instant = Instant.now(),
        triggerExtraction: Boolean = true,
        sessionId: String = "",
        source: String = "legacy",
        evidenceKind: EventEvidenceKind = EventEvidenceKind.Legacy,
        taskId: String = "",
        expectedRevision: Long? = null,
    ) {
        val clean = content.trim()
        val database = helper?.writableDatabase ?: return
        if (eventId.isBlank() || characterId.isBlank() || clean.isBlank()) return
        if (!DigitalLifeProfileStore.allowsTimestamp(characterId, occurredAt)) return
        if (eventId.startsWith("game-reply-")) return
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
        val previous = eventsByIds(characterId, listOf(eventId)).firstOrNull()
        // An event ID belongs to one character. Reject accidental cross-role reassignment.
        val owner = database.query("timeline_events", arrayOf("character_id"), "id = ?",
            arrayOf(eventId), null, null, null, "1").use { if (it.moveToFirst()) it.getString(0) else null }
        if (owner != null && owner != characterId) return
        if (expectedRevision != null && (previous?.revision ?: 0) != expectedRevision) return
        // Backfill and scene aliases must never rewrite a canonical event or queue extraction again.
        if (previous != null && previous.content == clean && previous.occurredAt.toEpochMilli() == occurredAt.toEpochMilli()) {
            val metadata = ContentValues()
            if (previous.sessionId.isBlank() && sessionId.isNotBlank()) metadata.put("session_id", sessionId)
            if (previous.evidenceKind == EventEvidenceKind.Legacy && evidenceKind != EventEvidenceKind.Legacy) {
                metadata.put("evidence_kind", evidenceKind.name)
                metadata.put("source", source)
            }
            if (previous.channel == "私聊" && channel.contains("电话")) metadata.put("channel", channel)
            if (metadata.size() > 0) database.update("timeline_events", metadata, "id = ? AND character_id = ?", arrayOf(eventId, characterId))
            return
        }
        val values = ContentValues().apply {
            put("id", eventId)
            put("character_id", characterId)
            put("channel", channel.trim().ifBlank { "共同经历" })
            put("speaker", speaker.trim().ifBlank { "事件" })
            put("content", clean)
            put("occurred_at", occurredAt.toEpochMilli())
            put("session_id", sessionId.ifBlank { previous?.sessionId.orEmpty() })
            put("source", if (source == "legacy") previous?.source ?: source else source)
            put("evidence_kind", if (evidenceKind == EventEvidenceKind.Legacy) previous?.evidenceKind?.name ?: evidenceKind.name else evidenceKind.name)
            put("task_id", taskId.ifBlank { previous?.taskId.orEmpty() })
            put("revision", (previous?.revision ?: 0) + 1)
        }
        database.insertWithOnConflict("timeline_events", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        if (previous != null) {
            CharacterDevelopmentStore.invalidateEvidence(characterId, eventId)
            CharacterLifeStore.invalidateReceipt(eventId)
            if (!triggerExtraction) scope.launch { LuluRepositories.memory.deleteDerivedFromEvent(eventId) }
        }
        if (triggerExtraction && SharedTimelineEvent(eventId, characterId, channel, speaker, clean, occurredAt,
                sessionId, source, evidenceKind).supportsDevelopmentReflection()) {
            CharacterDevelopmentRuntime.request(characterId)
        }
        if (triggerExtraction) {
            // Persist the recovery job before any asynchronous model work starts. A crash, process
            // kill or network failure after this point therefore cannot make the event disappear
            // from the automatic-memory queue.
            val job = MemoryExtractionJobStore.enqueue(characterId, sourceReplyId = eventId)
            scope.launch {
                if (previous != null) LuluRepositories.memory.deleteDerivedFromEvent(eventId)
                val policy = LuluRepositories.memory.observePolicy(characterId).first()
                if (!policy.autoSummarize) {
                    MemoryExtractionJobStore.complete(job.id)
                    return@launch
                }
                runCatching { LuluRepositories.memory.summarizeNow(characterId) }
                    .onSuccess { MemoryExtractionJobStore.complete(job.id) }
                    .onFailure { error -> MemoryExtractionJobStore.failed(job.id, error) }
            }
        }
    }

    fun all(characterId: String): List<SharedTimelineEvent> =
        query(characterId, null).sortedBy(SharedTimelineEvent::occurredAt)

    fun eventsByIds(characterId: String, eventIds: Collection<String>): List<SharedTimelineEvent> {
        val ids = eventIds.map(String::trim).filter(String::isNotBlank).distinct().take(120)
        val database = helper?.readableDatabase ?: return emptyList()
        if (characterId.isBlank() || ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val args = arrayOf(characterId, *ids.toTypedArray())
        return database.query(
            "timeline_events",
            EVENT_COLUMNS,
            "character_id = ? AND id IN ($placeholders)",
            args,
            null,
            null,
            "occurred_at ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val event = SharedTimelineEvent(
                        id = cursor.getString(0),
                        characterId = cursor.getString(1),
                        channel = cursor.getString(2),
                        speaker = cursor.getString(3),
                        content = cursor.getString(4),
                        occurredAt = Instant.ofEpochMilli(cursor.getLong(5)),
                        sessionId = cursor.getString(6),
                        source = cursor.getString(7),
                        evidenceKind = runCatching { EventEvidenceKind.valueOf(cursor.getString(8)) }.getOrDefault(EventEvidenceKind.Legacy),
                        taskId = cursor.getString(9),
                        revision = cursor.getLong(10),
                    )
                    if (DigitalLifeProfileStore.allowsTimestamp(characterId, event.occurredAt)) add(event)
                }
            }
        }
    }

    @Synchronized
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
        // Deletion invalidates every downstream owner immediately: retry job, executable promise
        // and derived memory/vector lifecycle. Late model responses are additionally checked by the
        // repository integrity guard before they can survive in recall.
        CharacterDevelopmentStore.invalidateEvidenceForAll(eventId)
        CharacterLifeStore.invalidateReceipt(eventId)
        MemoryExtractionJobStore.removeBySourceEvent(eventId)
        LuluRepositories.lexicon.invalidateSource(eventId)
        CommitmentTaskStore.removeBySourceEvent(eventId)
        scope.launch { LuluRepositories.memory.deleteDerivedFromEvent(eventId) }
    }

    /** Clear the raw ledger, including records hidden by an earlier birth boundary. */
    fun deleteCharacterEvents(characterId: String) {
        if (characterId.isBlank()) return
        val database = helper?.readableDatabase ?: return
        val ids = database.query("timeline_events", arrayOf("id"), "character_id = ?", arrayOf(characterId),
            null, null, null).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        ids.forEach(::deleteEvent)
    }

    fun deleteEventsByIdPrefix(characterId: String, eventIdPrefix: String) {
        if (characterId.isBlank() || eventIdPrefix.isBlank()) return
        val database = helper?.readableDatabase ?: return
        val ids = database.query(
            "timeline_events",
            arrayOf("id"),
            "character_id = ? AND id LIKE ?",
            arrayOf(characterId, "${eventIdPrefix}%"),
            null,
            null,
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        ids.forEach(::deleteEvent)
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

    fun recentEvents(characterId: String, limit: Int): List<SharedTimelineEvent> =
        if (limit <= 0) emptyList() else query(characterId, limit).sortedBy(SharedTimelineEvent::occurredAt)

    fun recentContext(characterId: String, limit: Int = 24, characterBudget: Int = 7_000): String {
        val events = recentEvents(characterId, limit)
        if (events.isEmpty()) return ""
        val lines = events.map { event ->
            "[${event.occurredAt}] [${event.channel}] ${event.speaker}：${event.evidenceContent.take(1_200)}"
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
        if (!DigitalLifeProfileStore.allowsTimestamp(characterId, occurredAt)) return
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
        val birth = DigitalLifeProfileStore.birthAt(characterId)
        val selection = if (birth == null) "character_id = ?" else "character_id = ? AND occurred_at >= ?"
        val args = if (birth == null) arrayOf(characterId) else arrayOf(characterId, birth.toEpochMilli().toString())
        return database.query(
            "timeline_events",
            EVENT_COLUMNS,
            selection,
            args,
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
                        sessionId = cursor.getString(6),
                        source = cursor.getString(7),
                        evidenceKind = runCatching { EventEvidenceKind.valueOf(cursor.getString(8)) }.getOrDefault(EventEvidenceKind.Legacy),
                        taskId = cursor.getString(9),
                        revision = cursor.getLong(10),
                        ),
                    )
                }
            }
        }
    }

    /** JSON backup includes the raw SQLite ledger and deletion tombstones, not just preferences. */
    @Synchronized
    fun exportBackup(): JSONObject {
        val db = helper?.readableDatabase ?: return JSONObject()
        val events = JSONArray()
        db.query("timeline_events", EVENT_COLUMNS, null, null, null, null, "occurred_at ASC").use { c ->
            while (c.moveToNext()) events.put(JSONObject().apply {
                EVENT_COLUMNS.forEachIndexed { i, key ->
                    put(key, if (key == "occurred_at" || key == "revision") c.getLong(i) else c.getString(i))
                }
            })
        }
        val deleted = JSONArray()
        db.query("deleted_timeline_events", arrayOf("event_id", "deleted_at"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) deleted.put(JSONObject().put("event_id", c.getString(0)).put("deleted_at", c.getLong(1)))
        }
        return JSONObject().put("events", events).put("deleted", deleted)
    }

    @Synchronized
    fun importBackup(root: JSONObject) {
        val db = helper?.writableDatabase ?: error("时间线尚未初始化")
        val events = root.optJSONArray("events") ?: JSONArray()
        val deleted = root.optJSONArray("deleted") ?: JSONArray()
        // Validate before starting any mutation. Import merges; it never clears existing records.
        val validated = (0 until events.length()).map { index ->
            val item = events.getJSONObject(index)
            require(item.getString("id").isNotBlank() && item.getString("character_id").isNotBlank())
            item.getLong("occurred_at")
            item
        }
        db.beginTransaction()
        try {
            for (i in 0 until deleted.length()) {
                val item = deleted.getJSONObject(i)
                val id = item.getString("event_id")
                db.insertWithOnConflict("deleted_timeline_events", null, ContentValues().apply {
                    put("event_id", id); put("deleted_at", item.getLong("deleted_at"))
                }, SQLiteDatabase.CONFLICT_IGNORE)
                db.delete("timeline_events", "id = ?", arrayOf(id))
            }
            validated.forEach { item ->
                if (!DigitalLifeProfileStore.allowsTimestamp(item.getString("character_id"), Instant.ofEpochMilli(item.getLong("occurred_at")))) return@forEach
                val id = item.getString("id")
                val tombstoned = db.query("deleted_timeline_events", arrayOf("event_id"), "event_id = ?", arrayOf(id), null, null, null).use { it.moveToFirst() }
                if (tombstoned) return@forEach
                val existing = db.query("timeline_events", arrayOf("character_id", "revision"), "id = ?", arrayOf(id), null, null, null).use {
                    if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
                }
                val incomingRevision = item.optLong("revision", 1)
                if (existing != null && (existing.first != item.getString("character_id") || existing.second >= incomingRevision)) return@forEach
                db.insertWithOnConflict("timeline_events", null, ContentValues().apply {
                    EVENT_COLUMNS.forEach { key ->
                        when (key) {
                            "occurred_at" -> put(key, item.getLong(key))
                            "revision" -> put(key, incomingRevision)
                            "source" -> put(key, item.optString(key, "legacy"))
                            "evidence_kind" -> put(key, item.optString(key, "Legacy"))
                            else -> put(key, item.optString(key))
                        }
                    }
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        for (i in 0 until deleted.length()) {
            val id = deleted.getJSONObject(i).getString("event_id")
            CharacterDevelopmentStore.invalidateEvidenceForAll(id)
            CharacterLifeStore.invalidateReceipt(id)
            MemoryExtractionJobStore.removeBySourceEvent(id)
            CommitmentTaskStore.removeBySourceEvent(id)
            scope.launch { LuluRepositories.memory.deleteDerivedFromEvent(id) }
        }
    }

    private val EVENT_COLUMNS = arrayOf("id", "character_id", "channel", "speaker", "content", "occurred_at",
        "session_id", "source", "evidence_kind", "task_id", "revision")

    private class TimelineDatabase(context: Context) : SQLiteOpenHelper(context, "shared_experience_timeline.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE timeline_events (
                    id TEXT PRIMARY KEY NOT NULL,
                    character_id TEXT NOT NULL,
                    channel TEXT NOT NULL,
                    speaker TEXT NOT NULL,
                    content TEXT NOT NULL,
                    occurred_at INTEGER NOT NULL,
                    session_id TEXT NOT NULL DEFAULT '',
                    source TEXT NOT NULL DEFAULT 'legacy',
                    evidence_kind TEXT NOT NULL DEFAULT 'Legacy',
                    task_id TEXT NOT NULL DEFAULT '',
                    revision INTEGER NOT NULL DEFAULT 1
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX timeline_character_time ON timeline_events(character_id, occurred_at)")
            createDeletionTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createDeletionTable(db)
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE timeline_events ADD COLUMN session_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE timeline_events ADD COLUMN source TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE timeline_events ADD COLUMN evidence_kind TEXT NOT NULL DEFAULT 'Legacy'")
                db.execSQL("ALTER TABLE timeline_events ADD COLUMN task_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE timeline_events ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
            }
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
