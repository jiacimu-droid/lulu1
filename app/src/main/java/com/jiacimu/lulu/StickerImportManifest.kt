package com.jiacimu.lulu

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/** A simple portable sticker list, independent from the local image storage. */
internal data class StickerImportEntry(
    val url: String,
    val name: String = "自选表情",
    val visualDescription: String = "",
    val usageHint: String = "",
    val pack: String = "我的添加",
)

internal object StickerImportManifest {
    private const val MAX_MANIFEST_CHARS = 200_000
    private const val MAX_ENTRIES = 200
    private val markdownImage = Regex("""!?\[[^\]]*\]\((https://[^)\s]+)\)""")

    /**
     * JSON: [{"url":"https://.../a.png","name":"...","description":"...","usage":"...","pack":"..."}]
     * Text/CSV/Markdown: URL | 名称 | 实际画面描述 | 使用语气 | 分组
     * Or just paste multiple HTTPS image URLs, one URL per line.
     */
    fun parse(raw: String): List<StickerImportEntry> {
        val input = raw.trim().removePrefix("\uFEFF").trim()
        require(input.isNotBlank()) { "请粘贴图片链接或选择表情包清单" }
        require(input.length <= MAX_MANIFEST_CHARS) { "清单过大，请分批导入" }
        val parsed = if (input.startsWith("[") || input.startsWith("{")) {
            parseJson(input)
        } else {
            parseRows(input)
        }
        val distinct = parsed.map { normalize(it) }.distinctBy { it.url }
        require(distinct.isNotEmpty()) { "没有找到可导入的图片链接" }
        require(distinct.size <= MAX_ENTRIES) { "一次最多导入 $MAX_ENTRIES 张，请拆分清单" }
        return distinct
    }

    private fun parseJson(input: String): List<StickerImportEntry> {
        val array = if (input.startsWith("[")) JSONArray(input) else {
            val root = JSONObject(input)
            root.optJSONArray("items") ?: root.optJSONArray("stickers")
                ?: error("JSON 应包含 items 或 stickers 数组")
        }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            fun get(vararg keys: String): String =
                keys.firstNotNullOfOrNull { key ->
                    item.optString(key).takeIf { it.isNotBlank() }
                }.orEmpty()
            StickerImportEntry(
                get("url", "imageUrl", "image_url", "image", "图片地址", "网页地址"),
                get("name", "名称", "名字"),
                get("description", "visualDescription", "画面描述", "描述"),
                get("usage", "usageHint", "使用语气", "用途"),
                get("pack", "group", "分组", "表情包"),
            )
        }
    }

    private fun parseRows(input: String): List<StickerImportEntry> =
        input.lineSequence().mapNotNull { original ->
            val line = original.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) {
                return@mapNotNull null
            }
            val columns = when {
                '|' in line -> line.trim('|').split('|').map(String::trim)
                '\t' in line -> line.split('\t').map(String::trim)
                ',' in line -> splitCsv(line)
                else -> listOf(line)
            }
            val first = columns.firstOrNull().orEmpty()
            if (first.lowercase() in setOf("url", "image", "imageurl", "image_url")
                || first in setOf("图片地址", "网页地址", "链接")
                || first.all { it == '-' || it == ':' || it.isWhitespace() }) {
                return@mapNotNull null
            }
            fun cell(index: Int) = columns.getOrNull(index).orEmpty()
            val url = markdownImage.find(first)?.groupValues?.get(1) ?: first
            StickerImportEntry(url, cell(1), cell(2), cell(3), cell(4))
        }.toList()

    private fun splitCsv(line: String): List<String> {
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    token.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    result += token.toString().trim()
                    token.clear()
                }
                else -> token.append(char)
            }
            index++
        }
        require(!quoted) { "CSV 引号没有闭合" }
        result += token.toString().trim()
        return result
    }

    private fun normalize(entry: StickerImportEntry): StickerImportEntry {
        val url = entry.url.trim()
        val parsed = runCatching { URL(url) }.getOrNull()
        require(parsed?.protocol == "https" && !parsed.host.isNullOrBlank()) {
            "请使用 HTTPS 图片直链：${url.take(90)}"
        }
        require(parsed.userInfo == null) { "图片链接不能包含账号信息" }
        return entry.copy(
            url = url,
            name = entry.name.trim().take(90).ifBlank { "自选表情" },
            visualDescription = entry.visualDescription.trim().take(400),
            usageHint = entry.usageHint.trim().take(160),
            pack = entry.pack.trim().take(40).ifBlank { "我的添加" },
        )
    }
}
