package com.jiacimu.lulu

import android.content.Context
import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Imports an ordinary Word .docx with pictures pasted into the document.
 * Each embedded picture becomes a sticker; nearby author-written captions
 * provide the name/description/usage. No conversion service or AI is called.
 *
 * Legacy binary .doc is not OpenXML; the UI asks users to save it as .docx.
 */
internal object StickerWordDocument {
    private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    private const val MAX_XML_BYTES = 4 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 80 * 1024 * 1024
    private const val MAX_MEMBERS = 500
    private val allowed = setOf("png", "jpg", "jpeg", "gif", "webp")

    private data class Paragraph(
        val text: String,
        val imageIds: List<String>,
        val altText: String,
    )
    private data class Caption(
        val name: String, val description: String, val usage: String,
    )

    suspend fun import(context: Context, source: Uri, displayName: String): StickerPackImportResult =
        withContext(Dispatchers.IO) {
            require(displayName.lowercase().endsWith(".docx")) {
                "请选择 .docx Word 文档；旧版 .doc 请先用 Word/WPS 另存为 .docx"
            }
            val pack = displayName.substringBeforeLast('.').trim().take(40).ifBlank { "Word 表情包" }
            val dir = File(context.cacheDir, "sticker-word-" + UUID.randomUUID()).apply { mkdirs() }
            val images = mutableMapOf<String, Pair<File, String>>()
            var document: ByteArray? = null
            var relationships: ByteArray? = null
            var total = 0
            var entries = 0
            try {
                val stream = context.contentResolver.openInputStream(source)
                    ?: error("无法打开 Word 文档")
                ZipInputStream(stream.buffered()).use { zip ->
                    while (true) {
                        val member = zip.nextEntry ?: break
                        entries++
                        require(entries <= MAX_MEMBERS) { "Word 文件包含过多内容" }
                        val path = member.name.replace('\\', '/')
                        require(!path.startsWith("/") && path.split('/').none { it == ".." }) {
                            "文档存在不安全的文件路径"
                        }
                        if (member.isDirectory) { zip.closeEntry(); continue }
                        val ext = path.substringAfterLast('.', "").lowercase()
                        val isImage = path.startsWith("word/media/") && ext in allowed
                        val isDoc = path == "word/document.xml"
                        val isRels = path == "word/_rels/document.xml.rels"
                        val limit = when {
                            isImage -> MAX_IMAGE_BYTES
                            isDoc || isRels -> MAX_XML_BYTES
                            else -> MAX_TOTAL_BYTES
                        }
                        val outputFile = if (isImage) File(dir, "${images.size}.$ext") else null
                        val outputXml = if (isDoc || isRels) ByteArrayOutputStream() else null
                        var memberBytes = 0
                        val buffer = ByteArray(16_384)
                        try {
                            outputFile?.outputStream()?.use { output ->
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    memberBytes += n
                                    total += n
                                    require(memberBytes <= limit && total <= MAX_TOTAL_BYTES) {
                                        "Word 文件超过容量限制"
                                    }
                                    output.write(buffer, 0, n)
                                }
                            } ?: run {
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    memberBytes += n
                                    total += n
                                    require(memberBytes <= limit && total <= MAX_TOTAL_BYTES) {
                                        "Word 文件超过容量限制"
                                    }
                                    outputXml?.write(buffer, 0, n)
                                }
                            }
                        } catch (error: Throwable) {
                            outputFile?.delete()
                            throw error
                        }
                        when {
                            isImage && outputFile != null -> images[path] = outputFile to ext
                            isDoc -> document = outputXml?.toByteArray()
                            isRels -> relationships = outputXml?.toByteArray()
                        }
                        zip.closeEntry()
                    }
                }
                val docBytes = document ?: error("文档不含正文，请使用正常的 .docx Word 文件")
                val refs = relationships?.let(::parseRelationships).orEmpty()
                val paragraphs = parseParagraphs(docBytes)
                val ordered = linkedMapOf<String, MutableList<String>>()
                val alternate = mutableMapOf<String, String>()
                val imageLocations = mutableMapOf<String, Int>()
                paragraphs.forEachIndexed { index, paragraph ->
                    for (id in paragraph.imageIds) {
                        val file = refs[id] ?: continue
                        if (file !in images || file in ordered) continue
                        imageLocations[file] = index
                        ordered[file] = mutableListOf<String>().also { texts ->
                            if (paragraph.text.isNotBlank()) texts += paragraph.text
                        }
                        if (paragraph.altText.isNotBlank()) alternate[file] = paragraph.altText
                    }
                }
                if (ordered.isEmpty()) {
                    // Some curated Word packs contain a picture URL followed
                    // by its written name/description instead of an image embed.
                    val entries = mutableListOf<StickerImportEntry>()
                    val rows = paragraphs.map { it.text.trim() }
                    for (i in rows.indices) {
                        val line = rows[i]
                        val candidate = line.substringAfter("：", line).trim()
                        if (!candidate.startsWith("https://")) continue
                        val url = candidate.substringBefore(' ').trim().trimEnd('，', ',', '。', ';', '；')
                        val parsed = runCatching {
                            StickerImportManifest.parse(url, pack).single()
                        }.getOrNull() ?: continue
                        val descriptionLines = mutableListOf<String>()
                        for (next in (i + 1) until minOf(rows.size, i + 7)) {
                            val value = rows[next]
                            if (value.contains("https://")) break
                            if (value.isNotBlank()) descriptionLines += value
                        }
                        val caption = captionOf(descriptionLines, "", "自选表情")
                        entries += parsed.copy(
                            name = caption.name, visualDescription = caption.description,
                            usageHint = caption.usage,
                        )
                    }
                    check(entries.isNotEmpty()) {
                        "Word 里没有可导入的图片或 HTTPS 图片地址，请确认图片已插入文档"
                    }
                    var imported = 0
                    var skipped = 0
                    val errors = mutableListOf<String>()
                    for (entry in entries.distinctBy { it.url }.take(220)) {
                        val known = StickerLibraryStore.items.value.mapTo(hashSetOf()) { it.id }
                        val result = StickerLibraryStore.importRemote(context, entry)
                        result.onSuccess {
                            if (it.id in known) skipped++ else imported++
                        }.onFailure { errors += "${entry.url.take(80)}: ${it.message.orEmpty()}" }
                        if (errors.lastOrNull()?.contains("上限") == true) break
                    }
                    return@withContext StickerPackImportResult(pack, imported, skipped, errors.take(8))
                }
                // Text following the picture (including text in the adjacent
                // table cell) belongs to that picture until the next image.
                val sortedFiles = ordered.keys.toList()
                for ((position, file) in sortedFiles.withIndex()) {
                    val start = imageLocations[file] ?: continue
                    val next = sortedFiles.getOrNull(position + 1)
                        ?.let { imageLocations[it] } ?: paragraphs.size
                    val end = minOf(next, start + 7)
                    for (i in start + 1 until end) {
                        val paragraph = paragraphs[i]
                        if (paragraph.imageIds.isNotEmpty()) break
                        if (paragraph.text.isNotBlank()) ordered[file]?.add(paragraph.text)
                    }
                }
                var imported = 0
                var skipped = 0
                val errors = mutableListOf<String>()
                for ((file, texts) in ordered) {
                    val media = images[file] ?: continue
                    val caption = captionOf(texts, alternate[file].orEmpty(),
                        file.substringAfterLast('/').substringBeforeLast('.'))
                    val known = StickerLibraryStore.items.value.mapTo(hashSetOf()) { it.id }
                    val result = StickerLibraryStore.importPackedImage(
                        context, media.first, media.second, caption.name,
                        pack, caption.description, caption.usage,
                    )
                    result.onSuccess {
                        if (it.id in known) skipped++ else imported++
                    }.onFailure { errors += "${file.substringAfterLast('/')}: ${it.message.orEmpty()}" }
                    if (errors.lastOrNull()?.contains("上限") == true) break
                }
                StickerPackImportResult(pack, imported, skipped, errors.take(8))
            } finally {
                dir.deleteRecursively()
            }
        }

    private fun parseRelationships(bytes: ByteArray): Map<String, String> {
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")
        val refs = mutableMapOf<String, String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val type = parser.attribute("Type")
                val target = parser.attribute("Target").replace('\\', '/')
                if (type.endsWith("/image") && parser.attribute("TargetMode") != "External") {
                    // Targets are relative to word/document.xml.
                    val canonical = if (target.startsWith("/")) target.removePrefix("/")
                        else "word/$target"
                    if (!canonical.split('/').contains("..")) {
                        refs[parser.attribute("Id")] = canonical
                    }
                }
            }
            parser.next()
        }
        return refs
    }

    private fun parseParagraphs(bytes: ByteArray): List<Paragraph> {
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")
        val paragraphs = mutableListOf<Paragraph>()
        val text = StringBuilder()
        val ids = mutableListOf<String>()
        var alt = ""
        var depth = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "p" && depth == 0) {
                        depth = 1
                        text.clear()
                        ids.clear()
                        alt = ""
                    } else if (depth > 0) {
                        depth++
                        when (parser.name) {
                            "t" -> text.append(parser.nextText())
                            "blip" -> parser.attribute("embed").takeIf(String::isNotBlank)?.let(ids::add)
                            "imagedata" -> parser.attribute("id").takeIf(String::isNotBlank)?.let(ids::add)
                            "docPr" -> {
                                alt = parser.attribute("descr").ifBlank { parser.attribute("title") }
                                    .take(400)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0) {
                            paragraphs += Paragraph(text.toString().trim(), ids.distinct(), alt.trim())
                        }
                    }
                }
            }
            parser.next()
        }
        return paragraphs
    }

    private fun XmlPullParser.attribute(name: String): String {
        for (i in 0 until attributeCount) {
            if (getAttributeName(i) == name) return getAttributeValue(i).orEmpty()
        }
        return ""
    }

    private fun captionOf(lines: List<String>, alt: String, fallback: String): Caption {
        var name = ""
        var description = alt.takeIf { it.isNotBlank() && !it.matches(Regex("(?i)(picture|image|图片)\\s*\\d+")) }
            .orEmpty()
        var usage = ""
        val extras = mutableListOf<String>()
        val key = Regex("^(名称|名字|表情名称|画面描述|描述|画面|用途|语气|使用场景)\\s*[：:]\\s*(.*)$")
        for (line in lines.map(String::trim).filter(String::isNotBlank)) {
            val match = key.matchEntire(line)
            if (match != null) {
                val value = match.groupValues[2]
                when (match.groupValues[1]) {
                    "名称", "名字", "表情名称" -> name = value.take(90)
                    "描述", "画面", "画面描述" -> description = value.take(400)
                    "用途", "语气", "使用场景" -> usage = value.take(160)
                }
            } else extras += line
        }
        if (name.isBlank()) {
            val first = extras.firstOrNull().orEmpty()
            if (first.length in 1..40) name = first.take(90)
            else if (description.isBlank()) description = first.take(400)
        }
        // The second unlabelled line is supplied caption text, not model
        // inference; preserve it as a description when no labeled value exists.
        if (description.isBlank() && extras.size > 1) {
            description = extras[1].take(400)
        }
        if (usage.isBlank() && extras.size > 2) usage = extras[2].take(160)
        return Caption(name.ifBlank { fallback }.take(90), description.take(400), usage.take(160))
    }
}
