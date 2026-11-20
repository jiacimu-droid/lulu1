package com.jiacimu.lulu

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

internal data class StickerPackImportResult(
    val pack: String,
    val imported: Int,
    val skipped: Int,
    val errors: List<String>,
)

/**
 * An entire user-shared sticker set is a normal ZIP with images and optional
 * manifest.json. Files are copied to random temporary names, never extracted
 * using archive paths (prevents path traversal). No AI/API call occurs.
 */
internal object StickerPackArchive {
    private const val MAX_FILE_BYTES = 5 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 80 * 1024 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 320
    private val allowed = setOf("png", "jpg", "jpeg", "gif", "webp")

    private data class Candidate(val name: String, val file: File, val extension: String)
    private data class Details(
        val name: String, val description: String, val usage: String, val pack: String,
    )

    suspend fun import(context: Context, uri: Uri, displayName: String): StickerPackImportResult =
        withContext(Dispatchers.IO) {
            val contextApp = context.applicationContext
            val defaultPack = displayName.substringBeforeLast('.').trim().take(40).ifBlank { "导入的表情包" }
            val folder = File(contextApp.cacheDir, "sticker-zip-" + UUID.randomUUID()).apply { mkdirs() }
            val candidates = mutableListOf<Candidate>()
            var manifest: String? = null
            var totalBytes = 0
            var count = 0
            try {
                val input = contextApp.contentResolver.openInputStream(uri) ?: error("无法读取 ZIP 文件")
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        count++
                        require(count <= MAX_ARCHIVE_ENTRIES) { "压缩包文件数量过多" }
                        val normalized = entry.name.replace('\\', '/').removePrefix("./")
                        require(!normalized.startsWith("/") &&
                            normalized.split('/').none { it == ".." }) {
                            "压缩包存在不安全的文件路径"
                        }
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }
                        val ext = normalized.substringAfterLast('.', "").lowercase()
                        val isManifest = normalized.substringAfterLast('/') in setOf(
                            "manifest.json", "stickers.json",
                        )
                        // Also bound ignored files: closing a ZIP entry implicitly
                        // decompresses it, which could otherwise hide a ZIP bomb.
                        if (ext !in allowed && !isManifest) {
                            val discard = ByteArray(16_384)
                            while (true) {
                                val n = zip.read(discard)
                                if (n < 0) break
                                totalBytes += n
                                require(totalBytes <= MAX_TOTAL_BYTES) { "压缩包超过 80MB 解压上限" }
                            }
                            zip.closeEntry()
                            continue
                        }
                        val buffer = ByteArray(16_384)
                        var currentBytes = 0
                        if (isManifest) {
                            val output = java.io.ByteArrayOutputStream()
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                currentBytes += read
                                totalBytes += read
                                require(currentBytes <= 200_000 && totalBytes <= MAX_TOTAL_BYTES) {
                                    "清单或压缩包过大"
                                }
                                output.write(buffer, 0, read)
                            }
                            manifest = output.toString("UTF-8")
                        } else {
                            val temp = File(folder, "${candidates.size}.$ext")
                            try {
                                temp.outputStream().use { output ->
                                    while (true) {
                                        val read = zip.read(buffer)
                                        if (read < 0) break
                                        currentBytes += read
                                        totalBytes += read
                                        require(currentBytes <= MAX_FILE_BYTES && totalBytes <= MAX_TOTAL_BYTES) {
                                            "压缩包超过容量限制（每张 5MB，整包 80MB）"
                                        }
                                        output.write(buffer, 0, read)
                                    }
                                }
                                candidates += Candidate(normalized, temp, ext)
                            } catch (failure: Throwable) {
                                temp.delete()
                                throw failure
                            }
                        }
                        zip.closeEntry()
                    }
                }
                check(candidates.isNotEmpty()) { "ZIP 里面没有 PNG/JPG/GIF/WebP 图片" }
                val (manifestPack, details) = parseMetadata(manifest, defaultPack)
                val usedPack = manifestPack.ifBlank { defaultPack }
                var imported = 0
                var skipped = 0
                val errors = mutableListOf<String>()
                for (candidate in candidates) {
                    val detail = details[candidate.name]
                        ?: details[candidate.name.substringAfterLast('/')]
                    val fallbackName = candidate.name.substringAfterLast('/').substringBeforeLast('.')
                    val name = detail?.name?.ifBlank { fallbackName } ?: fallbackName
                    val previouslyKnown = StickerLibraryStore.items.value.mapTo(hashSetOf()) { it.id }
                    val result = StickerLibraryStore.importPackedImage(
                        contextApp, candidate.file, candidate.extension, name,
                        detail?.pack?.ifBlank { usedPack } ?: usedPack,
                        detail?.description.orEmpty(), detail?.usage.orEmpty(),
                    )
                    result.onSuccess {
                        if (it.id in previouslyKnown) skipped++ else imported++
                    }.onFailure { errors += "${candidate.name}: ${it.message.orEmpty()}" }
                    // A full shelf should not trigger repeated work on remaining images.
                    if (errors.lastOrNull()?.contains("上限") == true) break
                }
                StickerPackImportResult(usedPack, imported, skipped, errors.take(8))
            } finally {
                folder.deleteRecursively()
            }
        }

    private fun parseMetadata(raw: String?, defaultPack: String): Pair<String, Map<String, Details>> {
        if (raw.isNullOrBlank()) return defaultPack to emptyMap()
        val input = raw.trim()
        val root = if (input.startsWith("{")) JSONObject(input) else null
        val list = root?.optJSONArray("items") ?: root?.optJSONArray("stickers")
            ?: if (root == null) JSONArray(input) else JSONArray()
        val pack = root?.optString("pack").orEmpty().trim().take(40).ifBlank { defaultPack }
        val result = mutableMapOf<String, Details>()
        for (i in 0 until list.length()) {
            val obj = list.optJSONObject(i) ?: continue
            fun field(vararg names: String): String = names.firstNotNullOfOrNull {
                obj.optString(it).takeIf(String::isNotBlank)
            }.orEmpty()
            val file = field("file", "filename", "path", "图片文件").replace('\\', '/').removePrefix("./")
            if (file.isBlank() || file.startsWith("/") || file.split('/').any { it == ".." }) continue
            result[file] = Details(
                field("name", "名称").take(90),
                field("description", "visualDescription", "画面描述", "描述").take(400),
                field("usage", "usageHint", "使用语气", "用途").take(160),
                field("pack", "group", "分组").take(40),
            )
        }
        return pack to result
    }
}
