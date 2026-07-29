package com.jiacimu.lulu.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Imports the old Lulu/RikkaHub ZIP backup without trying to open the old app sandbox.
 * Supported migration domains: text conversations, long-term memories and recognisable
 * OpenAI-compatible API configurations. The original ZIP and settings.json are archived.
 */
object LegacyLuluBackupImporter {
    private const val ARCHIVE_FOLDER = "legacy-lulu-migration"

    fun importBackup(context: Context, input: InputStream): LegacyLuluImportResult {
        val appContext = context.applicationContext
        val workDir = File(appContext.cacheDir, "legacy-lulu-import-${System.currentTimeMillis()}")
        workDir.mkdirs()
        val archiveDir = File(appContext.filesDir, ARCHIVE_FOLDER).apply { mkdirs() }
        val originalArchive = File(archiveDir, "old-lulu-${System.currentTimeMillis()}.zip")

        val rawBytes = input.use { it.readBytes() }
        originalArchive.writeBytes(rawBytes)
        extractZip(rawBytes.inputStream(), workDir)

        val settingsFile = File(workDir, "settings.json")
        if (settingsFile.exists()) {
            settingsFile.copyTo(File(archiveDir, "settings-${System.currentTimeMillis()}.json"), overwrite = true)
        }

        val databaseFile = File(workDir, "rikka_hub.db")
        require(databaseFile.exists()) { "旧备份中没有 rikka_hub.db，请在旧露露的备份页勾选数据库后重新导出" }

        val chatResult = importConversations(appContext, databaseFile)
        val memoryResult = importMemories(appContext, databaseFile)
        val modelResult = if (settingsFile.exists()) {
            importModelConnections(appContext, settingsFile.readText(Charsets.UTF_8))
        } else {
            ModelImportResult()
        }

        workDir.deleteRecursively()
        return LegacyLuluImportResult(
            conversationsImported = chatResult.conversations,
            messagesImported = chatResult.messages,
            memoriesImported = memoryResult,
            apiConfigurationsImported = modelResult.configurations,
            modelArchivesImported = modelResult.archives,
            archivedBackupPath = originalArchive.absolutePath,
        )
    }

    private fun extractZip(input: InputStream, destination: File) {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(destination, entry.name).canonicalFile
                require(target.path.startsWith(destination.canonicalPath + File.separator)) {
                    "备份中包含不安全路径：${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use(zip::copyTo)
                }
                zip.closeEntry()
            }
        }
    }

    private fun importConversations(context: Context, dbFile: File): ChatImportResult {
        val database = openLegacyDatabase(dbFile)
        database.use { db ->
            val table = findTable(db, "ConversationEntity", "conversation", "conversations")
                ?: return ChatImportResult()
            val rows = mutableListOf<LegacyConversationRow>()
            db.query(table, null, null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.string("id").ifBlank { UUID.randomUUID().toString() }
                    val title = cursor.string("title").ifBlank { "旧露露聊天" }
                    val nodes = cursor.string("nodes")
                    val createdAt = cursor.long("create_at")
                    val updatedAt = cursor.long("update_at")
                    val messages = parseLegacyNodes(nodes, id, updatedAt)
                    rows += LegacyConversationRow(id, title, createdAt, updatedAt, messages)
                }
            }
            if (rows.isEmpty()) return ChatImportResult()

            val prefs = context.getSharedPreferences("lulu_chat_store", Context.MODE_PRIVATE)
            val current = runCatching { JSONObject(prefs.getString("state_v1", null).orEmpty()) }
                .getOrElse { JSONObject() }
            val conversations = current.optJSONArray("conversations") ?: JSONArray()
            val messagesRoot = current.optJSONObject("messages") ?: JSONObject()
            val existingIds = buildSet {
                for (index in 0 until conversations.length()) {
                    conversations.optJSONObject(index)?.optString("id")?.takeIf(String::isNotBlank)?.let(::add)
                }
            }

            var importedConversations = 0
            var importedMessages = 0
            rows.forEach { row ->
                val targetId = uniqueLegacyId(row.id, existingIds + collectKeys(messagesRoot))
                val last = row.messages.lastOrNull()
                conversations.put(
                    JSONObject()
                        .put("id", targetId)
                        .put("characterId", "lulu")
                        .put("title", row.title)
                        .put("lastMessage", last?.content.orEmpty())
                        .put("updatedAt", epochMillisToInstant(row.updatedAt).toString())
                        .put("unreadCount", 0)
                        .put("parentConversationId", JSONObject.NULL)
                        .put("branchOriginMessageId", JSONObject.NULL),
                )
                val array = JSONArray()
                row.messages.forEach { message ->
                    array.put(
                        JSONObject()
                            .put("id", message.id)
                            .put("conversationId", targetId)
                            .put("sender", message.sender)
                            .put("content", message.content)
                            .put("createdAt", message.createdAt.toString())
                            .put("status", "Sent")
                            .put("favorite", message.favorite)
                            .put("branchOriginMessageId", JSONObject.NULL),
                    )
                }
                messagesRoot.put(targetId, array)
                importedConversations += 1
                importedMessages += row.messages.size
            }

            prefs.edit().putString(
                "state_v1",
                JSONObject().put("conversations", conversations).put("messages", messagesRoot).toString(),
            ).commit()
            return ChatImportResult(importedConversations, importedMessages)
        }
    }

    private fun importMemories(context: Context, dbFile: File): Int {
        val database = openLegacyDatabase(dbFile)
        database.use { db ->
            val table = findTable(db, "memory_bank", "MemoryBankEntity") ?: return 0
            val imported = JSONArray()
            db.query(table, null, "deprecated = 0 OR deprecated IS NULL", null, null, null, "created_at ASC")
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        val content = cursor.string("content").trim()
                        if (content.isBlank()) continue
                        val memoryKind = cursor.string("memory_kind")
                        val legacyType = cursor.string("type")
                        val kind = mapMemoryKind(memoryKind, legacyType, content)
                        val createdAt = firstPositive(
                            cursor.long("memory_created_at"),
                            cursor.long("created_at"),
                            cursor.long("extracted_at"),
                        )
                        val occurredAt = firstPositive(cursor.long("occurred_at"), cursor.long("source_message_at"))
                        imported.put(
                            JSONObject()
                                .put("id", "legacy-${cursor.string("id").ifBlank { UUID.randomUUID().toString() }}")
                                .put("characterId", "lulu")
                                .put("content", content)
                                .put("kind", kind)
                                .put("source", "旧露露迁移")
                                .put("occurredAt", occurredAt.takeIf { it > 0 }?.let(::epochMillisToInstant)?.toString() ?: JSONObject.NULL)
                                .put("createdAt", epochMillisToInstant(createdAt).toString())
                                .put("strength", cursor.int("importance").coerceIn(1, 10).takeIf { it > 0 } ?: 5)
                                .put("pinned", cursor.boolean("pinned"))
                                .put("canRecallProactively", true),
                        )
                    }
                }
            if (imported.length() == 0) return 0

            val prefs = context.getSharedPreferences("lulu_memory_store", Context.MODE_PRIVATE)
            val root = runCatching { JSONObject(prefs.getString("state_v1", null).orEmpty()) }
                .getOrElse { JSONObject() }
            val entries = root.optJSONArray("entries") ?: JSONArray()
            val dedupe = buildSet {
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    add(memoryKey(item.optString("kind"), item.optString("content")))
                }
            }.toMutableSet()
            var count = 0
            for (index in 0 until imported.length()) {
                val item = imported.optJSONObject(index) ?: continue
                if (dedupe.add(memoryKey(item.optString("kind"), item.optString("content")))) {
                    entries.put(item)
                    count += 1
                }
            }
            root.put("entries", entries)
            if (!root.has("policies")) root.put("policies", JSONObject())
            if (!root.has("processedMessageIds")) root.put("processedMessageIds", JSONObject())
            prefs.edit().putString("state_v1", root.toString()).commit()
            return count
        }
    }

    private fun importModelConnections(context: Context, rawSettings: String): ModelImportResult {
        val source = runCatching { JSONObject(rawSettings) }.getOrNull() ?: return ModelImportResult()
        val candidates = mutableListOf<ApiCandidate>()
        collectApiCandidates(source, candidates)
        val unique = candidates
            .filter { it.baseUrl.startsWith("http") && it.apiKey.isNotBlank() }
            .distinctBy { "${it.baseUrl.trimEnd('/')}\u0000${it.apiKey}" }
        if (unique.isEmpty()) return ModelImportResult()

        val prefs = context.getSharedPreferences("lulu_model_connection", Context.MODE_PRIVATE)
        val current = runCatching { JSONObject(prefs.getString("model_library_v2", null).orEmpty()) }
            .getOrElse { JSONObject() }
        val configurations = current.optJSONArray("configurations") ?: JSONArray()
        val archives = current.optJSONArray("archives") ?: JSONArray()
        val known = buildSet {
            for (index in 0 until configurations.length()) {
                val item = configurations.optJSONObject(index) ?: continue
                add("${item.optString("baseUrl").trimEnd('/')}\u0000${item.optString("apiKey")}")
            }
        }.toMutableSet()
        var configCount = 0
        var archiveCount = 0
        var firstArchiveId: String? = null
        unique.forEachIndexed { index, candidate ->
            val key = "${candidate.baseUrl.trimEnd('/')}\u0000${candidate.apiKey}"
            if (!known.add(key)) return@forEachIndexed
            val configId = UUID.randomUUID().toString()
            configurations.put(
                JSONObject()
                    .put("id", configId)
                    .put("name", candidate.name.ifBlank { "旧露露配置 ${index + 1}" })
                    .put("baseUrl", candidate.baseUrl.trimEnd('/'))
                    .put("apiKey", candidate.apiKey),
            )
            configCount += 1
            candidate.models.distinct().filter(String::isNotBlank).forEach { model ->
                val archiveId = UUID.randomUUID().toString()
                firstArchiveId = firstArchiveId ?: archiveId
                archives.put(
                    JSONObject()
                        .put("id", archiveId)
                        .put("configurationId", configId)
                        .put("model", model),
                )
                archiveCount += 1
            }
        }
        current.put("configurations", configurations).put("archives", archives)
        if (current.optString("activeArchiveId").isBlank() && firstArchiveId != null) {
            current.put("activeArchiveId", firstArchiveId)
        }
        prefs.edit().putString("model_library_v2", current.toString()).commit()
        return ModelImportResult(configCount, archiveCount)
    }

    private fun collectApiCandidates(value: Any?, output: MutableList<ApiCandidate>) {
        when (value) {
            is JSONObject -> {
                val baseUrl = firstString(value, "baseUrl", "base_url", "apiBase", "api_base")
                val apiKey = firstString(value, "apiKey", "api_key", "secretKey", "secret_key")
                if (baseUrl.isNotBlank() && apiKey.isNotBlank()) {
                    output += ApiCandidate(
                        name = firstString(value, "name", "displayName", "label"),
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        models = collectModels(value),
                    )
                }
                val keys = value.keys()
                while (keys.hasNext()) collectApiCandidates(value.opt(keys.next()), output)
            }
            is JSONArray -> for (index in 0 until value.length()) collectApiCandidates(value.opt(index), output)
        }
    }

    private fun collectModels(value: JSONObject): List<String> {
        val models = mutableListOf<String>()
        listOf("model", "modelId", "model_id").forEach { key ->
            value.optString(key).trim().takeIf(String::isNotBlank)?.let(models::add)
        }
        listOf("models", "modelList", "model_list").forEach { key ->
            val array = value.optJSONArray(key) ?: return@forEach
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is String -> item.trim().takeIf(String::isNotBlank)?.let(models::add)
                    is JSONObject -> firstString(item, "id", "modelId", "model", "name")
                        .takeIf(String::isNotBlank)?.let(models::add)
                }
            }
        }
        return models
    }

    private fun parseLegacyNodes(raw: String, conversationId: String, fallbackMillis: Long): List<LegacyMessage> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val nodes = JSONArray(raw)
            buildList {
                for (nodeIndex in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(nodeIndex) ?: continue
                    val choices = node.optJSONArray("messages") ?: continue
                    val selected = node.optInt("selectIndex", 0).coerceIn(0, (choices.length() - 1).coerceAtLeast(0))
                    val message = choices.optJSONObject(selected) ?: continue
                    val role = when (message.optString("role").uppercase()) {
                        "USER" -> "User"
                        "ASSISTANT" -> "Character"
                        else -> "System"
                    }
                    val content = extractText(message.opt("parts")).trim()
                    if (content.isBlank()) continue
                    add(
                        LegacyMessage(
                            id = "legacy-${message.optString("id").ifBlank { "$conversationId-$nodeIndex" }}",
                            sender = role,
                            content = content,
                            createdAt = parseLegacyDateTime(message.opt("createdAt"), fallbackMillis),
                            favorite = node.optBoolean("isFavorite", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun extractText(value: Any?): String = when (value) {
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                extractText(value.opt(index)).takeIf(String::isNotBlank)?.let(::add)
            }
        }.joinToString("\n")
        is JSONObject -> {
            when {
                value.has("text") -> value.optString("text")
                value.has("content") -> extractText(value.opt("content"))
                value.has("output") -> extractText(value.opt("output"))
                else -> ""
            }
        }
        is String -> value
        else -> ""
    }

    private fun parseLegacyDateTime(value: Any?, fallbackMillis: Long): Instant {
        val text = when (value) {
            is String -> value
            is JSONObject -> value.optString("value").ifBlank { value.toString() }
            else -> ""
        }.trim()
        if (text.isNotBlank()) {
            runCatching { Instant.parse(text) }.getOrNull()?.let { return it }
            runCatching { LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()?.let { return it }
        }
        return epochMillisToInstant(fallbackMillis)
    }

    private fun openLegacyDatabase(dbFile: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
        dbFile.absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    private fun findTable(database: SQLiteDatabase, vararg candidates: String): String? {
        database.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
            val names = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            candidates.forEach { candidate ->
                names.firstOrNull { it.equals(candidate, ignoreCase = true) }?.let { return it }
            }
        }
        return null
    }

    private fun Cursor.index(name: String): Int = getColumnIndex(name)
    private fun Cursor.string(name: String): String = index(name).takeIf { it >= 0 && !isNull(it) }?.let(::getString).orEmpty()
    private fun Cursor.long(name: String): Long = index(name).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: 0L
    private fun Cursor.int(name: String): Int = index(name).takeIf { it >= 0 && !isNull(it) }?.let(::getInt) ?: 0
    private fun Cursor.boolean(name: String): Boolean = int(name) != 0

    private fun firstString(root: JSONObject, vararg keys: String): String {
        keys.forEach { key -> root.optString(key).trim().takeIf(String::isNotBlank)?.let { return it } }
        return ""
    }

    private fun firstPositive(vararg values: Long): Long = values.firstOrNull { it > 0 } ?: System.currentTimeMillis()
    private fun epochMillisToInstant(value: Long): Instant = Instant.ofEpochMilli(value.takeIf { it > 0 } ?: System.currentTimeMillis())
    private fun memoryKey(kind: String, content: String): String = "$kind:${content.lowercase().replace(Regex("\\s+"), "").take(240)}"
    private fun collectKeys(root: JSONObject): Set<String> = buildSet {
        val keys = root.keys()
        while (keys.hasNext()) add(keys.next())
    }
    private fun uniqueLegacyId(source: String, existing: Set<String>): String {
        val base = "legacy-$source"
        if (base !in existing) return base
        return "$base-${UUID.randomUUID()}"
    }

    private fun mapMemoryKind(memoryKind: String, legacyType: String, content: String): String {
        val value = "$memoryKind $legacyType $content".lowercase()
        return when {
            listOf("emotion", "feeling", "情绪", "感受", "焦虑", "开心", "难过").any(value::contains) -> "Emotion"
            listOf("timeline", "event", "daily", "phase", "时间", "事件", "开始", "完成").any(value::contains) -> "Timeline"
            else -> "Fact"
        }
    }

    private data class LegacyConversationRow(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messages: List<LegacyMessage>,
    )

    private data class LegacyMessage(
        val id: String,
        val sender: String,
        val content: String,
        val createdAt: Instant,
        val favorite: Boolean,
    )

    private data class ApiCandidate(
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val models: List<String>,
    )

    private data class ChatImportResult(val conversations: Int = 0, val messages: Int = 0)
    private data class ModelImportResult(val configurations: Int = 0, val archives: Int = 0)
}

data class LegacyLuluImportResult(
    val conversationsImported: Int,
    val messagesImported: Int,
    val memoriesImported: Int,
    val apiConfigurationsImported: Int,
    val modelArchivesImported: Int,
    val archivedBackupPath: String,
)
