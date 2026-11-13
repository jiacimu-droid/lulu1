package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.WorldBookEntry
import com.jiacimu.lulu.core.WorldBookPromptPosition
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
        val originalPresetFormat: Boolean = false,
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
        // Original SillyTavern prompt presets are not lorebook JSON. Adapt *their prompt bodies*
        // into user-controlled world-book entries, while retaining the complete original asset
        // (scripts, regexes, macros and all) unmodified. Do not execute foreign JavaScript.
        val presetRoot = (root as? JSONObject)?.takeIf { it.optJSONArray("prompts") != null }
        val (items, foreign) = if (presetRoot != null)
            extractPresetPrompts(presetRoot, selectedCharacterId) to false
        else extract(root)
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
                // Keep the original body byte-for-byte through import; only reject whitespace-only entries.
                val body = (item.opt("content") as? String).orEmpty()
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
                    // Foreign SillyTavern insertion rules are not equivalent to Lulu's
                    // system-prompt positions. Do not silently reinterpret them.
                    promptPosition = if (native) runCatching {
                        WorldBookPromptPosition.valueOf(
                            item.optString("promptPosition", "NORMAL").uppercase()
                        )
                    }.getOrDefault(WorldBookPromptPosition.NORMAL)
                    else WorldBookPromptPosition.NORMAL,
                ))
            }
        }
        require(parsed.isNotEmpty()) { "没有有效条目：每条需要标题和正文" }
        return Preview(parsed, skipped, conditional, foreign, presetRoot != null)
    }

    /**
     * Preserve the complete source preset in assets, and project every prompt (including
     * formerly disabled alternatives) into the existing world-book editor without rewriting
     * the user's text. Empty host marker slots cannot be WorldBookEntry (content must be
     * nonblank) and remain intact in the archived original preset.
     *
     * Enabled/order are derived from the larger character prompt_order when present;
     * the smaller group is the template for thinking steps only. Non-text extensions
     * remain part of the untouched source asset; these are not runnable world-book macros.
     */
    private fun extractPresetPrompts(root: JSONObject, characterId: String): List<JSONObject> {
        val prompts = root.optJSONArray("prompts") ?: return emptyList()
        val all = LinkedHashMap<String, JSONObject>()
        for (i in 0 until prompts.length()) {
            val item = prompts.optJSONObject(i) ?: continue
            val id = item.optString("identifier").takeIf(String::isNotBlank) ?: "prompt-$i"
            all[id] = item
        }
        val orderGroups = root.optJSONArray("prompt_order")
        val chosen = (0 until (orderGroups?.length() ?: 0))
            .mapNotNull { index -> orderGroups?.optJSONObject(index)?.optJSONArray("order") }
            .maxByOrNull { it.length() }
        val enabledFromOrder = LinkedHashMap<String, Boolean>()
        if (chosen != null) {
            for (i in 0 until chosen.length()) {
                val item = chosen.optJSONObject(i) ?: continue
                val id = item.optString("identifier")
                if (id.isNotBlank()) enabledFromOrder[id] = item.optBoolean("enabled", false)
            }
        }
        val ids = (enabledFromOrder.keys + all.keys).distinct()
        return ids.mapNotNull { id ->
            val prompt = all[id] ?: return@mapNotNull null
            // A copy of the exact original prompt text is the world-book content.
            val content = (prompt.opt("content") as? String).orEmpty()
            val enabled = enabledFromOrder[id] ?: prompt.optBoolean("enabled", false)
            JSONObject()
                .put("title", prompt.optString("name").ifBlank { id })
                .put("content", content)
                .put("globalEnabled", false)
                .put("characterOverrides", JSONObject().put(characterId, enabled))
                .put("promptPosition", "NORMAL")
        }
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
