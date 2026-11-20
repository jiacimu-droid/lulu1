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
                // A document may mix real pasted pictures and ordinary links.
                // Parse titles, URL fields and descriptions regardless of the
                // author's template; URLs are downloaded into the same pack.
                val linkedEntries = StickerFlexibleTextImport.parse(
                    paragraphs.joinToString("\n") { it.text }, pack,
                )
                if (ordered.isEmpty() && linkedEntries.isEmpty()) {
                    error("Word 里没有可导入的图片或 HTTPS 图片地址")
                }
                // Authors may put the caption before the picture, after it,
                // or in its adjacent table cell. Prefer the following block
                // when it is clearly labeled; otherwise fall back to preceding
                // labeled text. Never attribute a caption to two images.
                val sortedFiles = ordered.keys.toList()
                for ((position, file) in sortedFiles.withIndex()) {
                    val start = imageLocations[file] ?: continue
                    val previous = sortedFiles.getOrNull(position - 1)
                        ?.let { imageLocations[it] } ?: -1
                    val next = sortedFiles.getOrNull(position + 1)
                        ?.let { imageLocations[it] } ?: paragraphs.size
                    val after = paragraphs.subList(start + 1, minOf(next, start + 7))
                        .filter { it.imageIds.isEmpty() }.map { it.text }.filter(String::isNotBlank)
                    val preceding = paragraphs.subList(maxOf(previous + 1, start - 6), start)
                        .filter { it.imageIds.isEmpty() }.map { it.text }.filter(String::isNotBlank)
                    val labelled = Regex("""^(名称|标题|名字|表情名称|画面描述|图片描述|描述|使用场景|用途|语气|情绪)\\s*[：:]""")
                    val afterTagged = after.any { labelled.containsMatchIn(it.trim()) }
                    val beforeTagged = preceding.any { labelled.containsMatchIn(it.trim()) }
                    when {
                        afterTagged -> ordered[file]?.addAll(after)
                        beforeTagged -> ordered[file]?.addAll(preceding)
                        else -> ordered[file]?.addAll(after)
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
                for (entry in linkedEntries) {
                    val known = StickerLibraryStore.items.value.mapTo(hashSetOf()) { it.id }
                    val result = StickerLibraryStore.importRemote(context, entry)
                    result.onSuccess { if (it.id in known) skipped++ else imported++ }
                        .onFailure { errors += "${entry.url.take(75)}: ${it.message.orEmpty()}" }
                    if (errors.lastOrNull()?.contains("上限") == true) break
                }
                StickerPackImportResult(pack, imported, skipped, errors.take(8))
            } finally {
                dir.deleteRecursively()
            }
        }

    private fun parseRelationships(bytes: ByteArray): Map<String, String> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
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
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(bytes.inputStream(), "UTF-8")
        val paragraphs = mutableListOf<Paragraph>()
        val text = StringBuilder()
        val ids = mutableListOf<String>()
        var alt = ""
        var insideParagraph = false
        var insideText = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "p" -> {
                        insideParagraph = true
                        insideText = false
                        text.clear()
                        ids.clear()
                        alt = ""
                    }
                    "t" -> if (insideParagraph) insideText = true
                    "tab" -> if (insideParagraph) text.append(' ')
                    "br" -> if (insideParagraph) text.append('\n')
                    "blip" -> if (insideParagraph) {
                        parser.attribute("embed").takeIf(String::isNotBlank)?.let(ids::add)
                    }
                    "imagedata" -> if (insideParagraph) {
                        parser.attribute("id").takeIf(String::isNotBlank)?.let(ids::add)
                    }
                    "docPr" -> if (insideParagraph) {
                        alt = parser.attribute("descr").ifBlank { parser.attribute("title") }.take(400)
                    }
                }
                XmlPullParser.TEXT -> if (insideParagraph && insideText) {
                    text.append(parser.text.orEmpty())
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> insideText = false
                    "p" -> if (insideParagraph) {
                        paragraphs += Paragraph(text.toString().trim(), ids.distinct(), alt.trim())
                        insideParagraph = false
                        insideText = false
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
        val key = Regex("^(名称|标题|名字|表情名称|图片名称|画面描述|图片描述|描述|画面|内容|用途|语气|情绪|使用场景|适用场景)\\s*[：:]\\s*(.*)$")
        for (line in lines.map(String::trim).filter(String::isNotBlank)) {
            val match = key.matchEntire(line)
            if (match != null) {
                val value = match.groupValues[2]
                when (match.groupValues[1]) {
                    "名称", "标题", "名字", "表情名称", "图片名称" -> name = value.take(90)
                    "描述", "画面", "画面描述", "图片描述", "内容" -> description = value.take(400)
                    "用途", "语气", "使用场景", "适用场景", "情绪" -> usage = value.take(160)
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
