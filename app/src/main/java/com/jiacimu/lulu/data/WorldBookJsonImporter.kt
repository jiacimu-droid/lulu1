package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.WorldBookEntry
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Reads world-book files into the existing WorldBookEntry contract; this is not
 * a second lore store or an interpreter for foreign keyword-trigger rules.
 *
 * Accepted inputs: Lulu arrays/single entries, {"books":[...]}, {"worldBooks":[...]},
 * SillyTavern {"entries":{"0":{...}}} / {"entries":[...]}, and V2
 * {"data":{"character_book":{"entries":[...]}}}.
 */
internal object WorldBookJsonImporter {
    data class Preview(
        val entries: List<WorldBookEntry>,
        val skipped: Int,
        val conditionalEntries: Int,
        val foreignFormat: Boolean,
    )

    fun parse(json: String, selectedCharacterId: String): Preview {
        require(selectedCharacterId.isNotBlank()) { "请先选择角色" }
        val source = json.trim().removePrefix("\uFEFF").trim()
        require(source.isNotBlank()) { "JSON 文件为空" }
        val root = when (source.first()) {
            '[' -> JSONArray(source)
            '{' -> JSONObject(source)
            else -> throw IllegalArgumentException("文件不是世界书 JSON")
        }
        val (items, foreign) = extract(root)
        require(items.isNotEmpty()) { "JSON 中没有可导入的世界书条目" }
        var skipped = 0
        var conditional = 0
        val parsed = buildList {
            items.forEachIndexed { index, raw ->
                val item = raw as? JSONObject
                if (item == null) {
                    skipped++
                    return@forEachIndexed
                }
                val body = (item.opt("content") as? String)?.trim().orEmpty()
                val native = !foreign && (item.has("globalEnabled") || item.has("characterOverrides"))
                val title = if (foreign) {
                    listOf("comment", "name", "title").asSequence()
                        .map { item.optString(it).trim() }.firstOrNull(String::isNotBlank)
                        ?: keywords(item).firstOrNull()
                        ?: "条目 ${index + 1}"
                } else item.optString("title").trim().ifBlank { item.optString("name").trim() }
                if (title.isBlank() || body.isBlank()) {
                    skipped++
                    return@forEachIndexed
                }
                val isConditional = foreign && !item.optBoolean("constant", false)
                if (isConditional) conditional++
                val active = !item.optBoolean("disable", false) &&
                    item.optBoolean("enabled", true) && !isConditional
                val overrides = if (native) {
                    item.optJSONObject("characterOverrides")?.let { original ->
                        buildMap {
                            original.keys().forEach { role ->
                                val value = original.opt(role)
                                if (value is Boolean) put(role, value)
                            }
                        }
                    }.orEmpty()
                } else if (active) mapOf(selectedCharacterId to true) else emptyMap()
                val terms = if (foreign) keywords(item) else emptyList()
                val savedContent = if (terms.isEmpty()) body
                    else "原世界书关键词：${terms.joinToString("、")}\n\n$body"
                val created = item.optString("createdAt").takeIf(String::isNotBlank)
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()
                add(WorldBookEntry(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    content = savedContent,
                    globalEnabled = if (native) item.optBoolean("globalEnabled", false) else false,
                    characterOverrides = overrides,
                    createdAt = created,
                    updatedAt = created,
                ))
            }
        }
        require(parsed.isNotEmpty()) { "没有有效条目：每条需要标题和正文" }
        return Preview(parsed, skipped, conditional, foreign)
    }

    private fun keywords(item: JSONObject): List<String> =
        listOf("key", "keys", "keysecondary", "secondary_keys").flatMap { field ->
            when (val data = item.opt(field)) {
                is JSONArray -> (0 until data.length()).mapNotNull { index ->
                    (data.opt(index) as? String)?.trim()?.takeIf(String::isNotBlank)
                }
                is String -> listOf(data.trim()).filter(String::isNotBlank)
                else -> emptyList()
            }
        }.distinct()

    private fun extract(root: Any): Pair<List<Any?>, Boolean> = when (root) {
        is JSONArray -> elements(root) to false
        is JSONObject -> {
            val worldBooks = root.opt("worldBooks") ?: root.opt("books")
            val entries = root.opt("entries")
            when {
                worldBooks is JSONArray -> elements(worldBooks) to false
                entries is JSONArray -> elements(entries) to true
                entries is JSONObject -> entries.keys().asSequence().toList()
                    .sortedWith(compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it })
                    .map { entries.opt(it) } to true
                root.optJSONObject("character_book") != null ->
                    extract(root.getJSONObject("character_book"))
                root.optJSONObject("data")?.optJSONObject("character_book") != null ->
                    extract(root.getJSONObject("data").getJSONObject("character_book"))
                root.optJSONObject("world_info") != null ->
                    extract(root.getJSONObject("world_info"))
                root.has("content") -> listOf(root) to false
                else -> throw IllegalArgumentException("不支持的世界书 JSON 结构")
            }
        }
        else -> throw IllegalArgumentException("不支持的世界书 JSON 结构")
    }

    private fun elements(array: JSONArray): List<Any?> =
        (0 until array.length()).map { array.opt(it) }
}
