package com.jiacimu.lulu

/**
 * Accepts human-curated sticker documents, not just a fixed data template.
 * Image URLs anchor each record. Nearby title/description/usage are hints,
 * never treated as proof of visual facts unless actually written by the author.
 */
internal object StickerFlexibleTextImport {
    private val url = Regex("""https://[^\s|｜\]\[<>"'，,；;）)]+""", RegexOption.IGNORE_CASE)
    private val field = Regex("""^\s*(?:[-*•]\s*)?(?:\d+[.、)]\s*)?([^：:=]{1,16})\s*[：:=]\s*(.*)$""")
    private val headerNames = setOf("标题", "名称", "名字", "表情", "表情名称", "表情名", "name", "title")
    private val headerDescriptions = setOf("描述", "图片描述", "画面描述", "图片内容", "画面", "内容", "动作", "description", "caption")
    private val headerUsage = setOf("使用场景", "适用场景", "使用语气", "用途", "语气", "情绪", "用法", "usage", "scene", "mood")
    private val headerUrls = setOf("链接", "图片链接", "图片地址", "地址", "网址", "图链", "url", "imageurl", "image", "图片url")
    private val headerPacks = setOf("分组", "系列", "表情包分组", "pack", "group")

    private data class Note(
        val name: String = "", val description: String = "", val usage: String = "", val pack: String = "",
    ) {
        fun withField(key: String, value: String): Note {
            val normalized = key.trim().lowercase().replace(" ", "").replace("_", "")
            val clean = value.trim().trim('"', '\'', '，', '。', ';', '；')
            return when (normalized) {
                in headerNames -> copy(name = clean.take(90))
                in headerDescriptions -> copy(description = clean.take(400))
                in headerUsage -> copy(usage = clean.take(160))
                in headerPacks -> copy(pack = clean.take(40))
                else -> this
            }
        }
    }

    fun parse(source: String, defaultPack: String = "我的添加"): List<StickerImportEntry> {
        val text = source.trim().removePrefix("\uFEFF").trim()
        require(text.length <= 200_000) { "文档文本过长，请拆分导入" }
        if (text.isEmpty()) return emptyList()
        if (text.startsWith("{") || text.startsWith("[")) {
            return StickerImportManifest.parse(text, defaultPack)
        }
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        val results = mutableListOf<StickerImportEntry>()
        var inheritedPack = defaultPack
        var pending = Note()
        var last: StickerImportEntry? = null

        fun persistLast() {
            last?.let { results += it }
            last = null
        }
        for (line in lines) {
            if (line.startsWith("#")) {
                val heading = line.trimStart('#', ' ').trim()
                if (heading.isNotBlank() && heading.length <= 40 && !heading.contains("https://")) {
                    // Headings become pack names only when they indicate a group.
                    if (heading.contains("表情包") || heading.contains("合集") || heading.contains("系列")) {
                        inheritedPack = heading.removeSuffix("表情包").trim().take(40).ifBlank { defaultPack }
                    }
                }
                continue
            }
            val columns = splitColumns(line)
            if (columns.size >= 2 && columns.any { url.containsMatchIn(it) }) {
                val header = columns.map { normalizeHeader(it) }
                if (header.none { it in headerUrls } && columns.size >= 2) {
                    val urlIndex = columns.indexOfFirst { url.containsMatchIn(it) }
                    val link = url.find(columns[urlIndex])!!.value.trimEnd('.', ':')
                    var record = Note()
                    for (i in columns.indices) {
                        if (i == urlIndex) continue
                        val column = columns[i].trim()
                        val keyValue = field.matchEntire(column)
                        record = if (keyValue != null) {
                            record.withField(keyValue.groupValues[1], keyValue.groupValues[2])
                        } else {
                            when {
                                record.name.isBlank() && column.length <= 90 -> record.copy(name = column)
                                record.description.isBlank() -> record.copy(description = column.take(400))
                                record.usage.isBlank() -> record.copy(usage = column.take(160))
                                else -> record
                            }
                        }
                    }
                    persistLast()
                    last = entry(link, record, inheritedPack)
                    pending = Note()
                    continue
                }
            }
            val links = url.findAll(line).map { it.value.trimEnd('.', ':') }.toList()
            if (links.isNotEmpty()) {
                val prefix = line.substringBefore(links.first()).trim().trimEnd('：', ':', '=', '-', ' ')
                val suffix = line.substringAfter(links.last()).trim().trimStart('：', ':', ' ', '-')
                var record = pending
                val prefixField = field.matchEntire(prefix)
                if (prefixField != null) {
                    record = record.withField(prefixField.groupValues[1], prefixField.groupValues[2])
                } else if (prefix.isNotBlank() && normalizeHeader(prefix) !in headerUrls &&
                    prefix.length <= 90) {
                    record = record.copy(name = record.name.ifBlank { prefix })
                }
                persistLast()
                links.forEachIndexed { index, link ->
                    if (index > 0) persistLast()
                    last = entry(link, record, inheritedPack)
                }
                pending = Note()
                val suffixField = field.matchEntire(suffix)
                if (suffixField != null) last = last?.merge(suffixField.groupValues[1], suffixField.groupValues[2])
                continue
            }
            val match = field.matchEntire(line)
            if (match != null) {
                val key = normalizeHeader(match.groupValues[1])
                val value = match.groupValues[2].trim()
                if (key in headerPacks && value.isNotBlank()) {
                    inheritedPack = value.take(40)
                } else if (key in headerNames && last?.name?.let { it != "自选表情" } == true) {
                    persistLast()
                    pending = pending.withField(key, value)
                } else if (last != null && key in (headerDescriptions + headerUsage)) {
                    last = last?.merge(key, value)
                } else {
                    pending = pending.withField(key, value)
                }
            } else if (line.length in 2..90 && !line.startsWith("---")) {
                if (last == null) {
                    if (pending.name.isBlank()) pending = pending.copy(name = line.take(90))
                } else if (last?.name == "自选表情") {
                    last = last?.copy(name = line.take(90))
                } else if (last?.visualDescription.isNullOrBlank() && looksLikeDescription(line)) {
                    last = last?.copy(visualDescription = line.take(400))
                }
            }
        }
        persistLast()
        return results.distinctBy { it.url }.take(220)
    }

    private fun entry(link: String, note: Note, pack: String) = StickerImportEntry(
        url = link, name = note.name.ifBlank { "自选表情" }.take(90),
        visualDescription = note.description.take(400),
        usageHint = note.usage.take(160),
        pack = note.pack.ifBlank { pack }.take(40),
    )

    private fun StickerImportEntry.merge(key: String, value: String): StickerImportEntry {
        val normalized = normalizeHeader(key)
        return when (normalized) {
            in headerNames -> copy(name = value.trim().take(90))
            in headerDescriptions -> copy(visualDescription = value.trim().take(400))
            in headerUsage -> copy(usageHint = value.trim().take(160))
            in headerPacks -> copy(pack = value.trim().take(40))
            else -> this
        }
    }

    private fun normalizeHeader(value: String) =
        value.trim().trimStart('|', '｜').trimEnd('|', '｜').lowercase().replace(" ", "").replace("_", "")

    private fun looksLikeDescription(line: String): Boolean =
        listOf("一只", "一张", "图中", "画面", "猫咪", "小猫", "双手", "眼睛", "嘴巴", "文字", "表情", "背景", "头上")
            .any(line::contains)

    private fun splitColumns(line: String): List<String> {
        val separator = when {
            '|' in line -> '|'
            '｜' in line -> '｜'
            '\t' in line -> '\t'
            else -> return listOf(line)
        }
        return line.trim(separator).split(separator).map(String::trim)
    }
}
