package com.jiacimu.lulu

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

internal data class Live2DModelControls(
    val expressions: List<String> = emptyList(),
    val motions: List<Pair<String, Int>> = emptyList(),
)

/** Only owns the renderer assets; character identity, memories and calls remain in their stores. */
internal object Live2DAssets {
    private const val STORAGE = "lulu_live2d"
    private const val PREFS = "lulu_live2d_models_v1"
    private val revisionState = MutableStateFlow(0L)
    val revisions = revisionState.asStateFlow()
    /** An installed official Core is shared by every role; this refreshes all open previews. */
    fun notifyCoreChanged() { revisionState.value++ }
    fun base(context: Context) = File(context.applicationContext.filesDir, STORAGE).apply { mkdirs() }
    fun coreFile(context: Context) = File(base(context), "core/live2dcubismcore.min.js")
    fun hasCore(context: Context) = coreFile(context).let { it.isFile && it.length() > 1024 }
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun modelUrl(context: Context, characterId: String): String? {
        val relative = prefs(context).getString("model:$characterId", null).orEmpty()
        if (!relative.startsWith("models/") || relative.contains("..")) return null
        val file = File(base(context), relative)
        if (!file.isFile || !inside(base(context), file)) return null
        return "https://appassets.androidplatform.net/private/" +
            relative.split('/').joinToString("/") { Uri.encode(it) }
    }
    fun modelLabel(context: Context, characterId: String) =
        prefs(context).getString("label:$characterId", null).orEmpty()
    /**
     * A creator portrait is not an atlas. The previous "largest unreferenced PNG"
     * heuristic mistakenly selected our generated 4096px atlas cache, particularly
     * when the texture directory was named 小狼.8192 instead of exactly /8192/.
     * Only a declared same-directory image matching the model stem may be shown.
     */
    private fun genuinePortrait(modelFile: File): File? {
        val directory = modelFile.parentFile ?: return null
        val refs = runCatching {
            JSONObject(modelFile.readText()).getJSONObject("FileReferences")
                .getJSONArray("Textures")
        }.getOrNull() ?: return null
        val referenced = (0 until refs.length()).map { i ->
            File(directory, refs.getString(i)).canonicalPath
        }.toSet()
        val stem = modelFile.name.removeSuffix(".model3.json")
        if (stem == modelFile.name || stem.isBlank()) return null
        val filenames = listOf(stem, stem + "_preview", stem + "_立绘")
        for (name in filenames) {
            for (extension in listOf("png", "jpg", "jpeg", "webp")) {
                val candidate = File(directory, name + "." + extension)
                if (candidate.isFile && candidate.canonicalPath !in referenced &&
                    !candidate.name.contains(".lulu-mobile-", ignoreCase = true) &&
                    candidate.canonicalFile.parentFile == directory.canonicalFile) {
                    return candidate
                }
            }
        }
        return null
    }

    /** Returns only an explicit creator portrait, never a model texture or derived atlas. */
    fun previewFile(context: Context, characterId: String): File? {
        val root = base(context)
        val relative = prefs(context).getString("model:" + characterId, null).orEmpty()
        if (!relative.startsWith("models/") || relative.contains("..")) return null
        val modelFile = File(root, relative)
        if (!inside(root, modelFile) || !modelFile.isFile) return null
        // Re-evaluate every old saved preview path to protect existing installs.
        return runCatching { genuinePortrait(modelFile) }.getOrNull()
    }

    /** An in-memory manifest for the renderer; the imported model3.json stays unmodified. */
    fun rendererManifest(context: Context, characterId: String): String? {
        val relative = prefs(context).getString("model:$characterId", null).orEmpty()
        if (!relative.startsWith("models/") || relative.contains("..")) return null
        val settings = File(base(context), relative)
        if (!inside(base(context), settings) || !settings.isFile) return null
        return runCatching {
            Live2DMobileTextureAdapter.prepare(
                settings, Live2DModelManifestAdapter.normalizedSettings(settings)
            ).toString()
        }.getOrElse { error ->
            // A missing or failed atlas cache must be a visible failure, not a silent
            // fallback to the original 8192px GPU texture that may crash WebView.
            org.json.JSONObject().put("luluMobileTextureError",
                error.message ?: "手机贴图优化失败").toString()
        }
    }

    /** Actual controls from the user's Cubism model, not invented universal emotion labels. */
    fun controls(context: Context, characterId: String): Live2DModelControls {
        val relative = prefs(context).getString("model:$characterId", null).orEmpty()
        val settings = File(base(context), relative)
        if (relative.isBlank() || !inside(base(context), settings) || !settings.isFile) return Live2DModelControls()
        return runCatching {
            val refs = Live2DModelManifestAdapter.normalizedSettings(settings)
                .getJSONObject("FileReferences")
            val expressions = refs.optJSONArray("Expressions")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("Name")?.takeIf(String::isNotBlank) }
            }.orEmpty()
            val motions = mutableListOf<Pair<String, Int>>()
            refs.optJSONObject("Motions")?.let { groups ->
                val names = groups.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    val items = groups.getJSONArray(name)
                    repeat(items.length()) { index -> motions.add(name to index) }
                }
            }
            Live2DModelControls(expressions, motions)
        }.getOrDefault(Live2DModelControls())
    }

    fun removeModel(context: Context, characterId: String) {
        val previous = prefs(context).getString("model:$characterId", null)
        check(prefs(context).edit().remove("model:$characterId").remove("label:$characterId")
            .remove("preview:$characterId").commit())
        previous?.let { deleteImportedFolder(context, it) }
        revisionState.value++
    }

    /** Core must come from the official, separately licensed Live2D Cubism Web SDK. */
    suspend fun importOfficialCore(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val target = coreFile(context)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "core-import-" + UUID.randomUUID() + ".tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val bytes = ByteArray(16 * 1024)
                    var size = 0L
                    while (true) {
                        val n = input.read(bytes)
                        if (n < 0) break
                        size += n
                        require(size <= 12L * 1024 * 1024) { "Cubism Core 文件过大" }
                        output.write(bytes, 0, n)
                    }
                }
            } ?: error("无法读取 SDK 文件")
            require(temporary.length() > 1024 &&
                temporary.readText().contains("Live2DCubismCore")) {
                "不是可识别的 Cubism Core JS；请从 Live2D 官方 SDK for Web 导入"
            }
            if (target.exists()) check(target.delete())
            check(temporary.renameTo(target)) { "无法保存 Cubism Core" }
            revisionState.value++
            "已导入官方 Cubism Core。"
        } finally { temporary.delete() }
    }

    suspend fun importModel(context: Context, characterId: String, uri: Uri): String = withContext(Dispatchers.IO) {
        require(characterId.isNotBlank()) { "请先选择角色" }
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
            if (it.length >= 0) require(it.length <= 180L * 1024 * 1024) { "ZIP 压缩包过大" }
        }
        val root = base(context)
        val stamp = UUID.randomUUID().toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(characterId.toByteArray()).take(12)
            .joinToString("") { "%02x".format(it) }
        val staging = File(root, "stage-$stamp").apply { mkdirs() }
        val destination = File(root, "models/$hash/$stamp")
        try {
            context.contentResolver.openInputStream(uri)?.use { source ->
                ZipInputStream(source.buffered()).use { zip ->
                    val buffer = ByteArray(16 * 1024)
                    var entries = 0
                    var expanded = 0L
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        require(entries <= 3000) { "ZIP 文件数量过多" }
                        val relative = entry.name.replace('\\', '/')
                        if (relative.startsWith("__MACOSX/") || relative.substringAfterLast('/') == ".DS_Store" ||
                            relative.substringAfterLast('/').startsWith("._")) {
                            zip.closeEntry()
                            continue
                        }
                        require(!relative.startsWith("/") && !relative.contains('\u0000') &&
                            relative.split('/').none { it == ".." || it == "." }) { "ZIP 存在不安全路径" }
                        val file = File(staging, relative)
                        require(inside(staging, file)) { "ZIP 路径越界" }
                        if (entry.isDirectory) file.mkdirs() else {
                            // AIRI model archives may include README/license/metadata
                            // or VTube Studio-specific files. Ignore unknown extensions
                            // rather than rejecting an otherwise valid Cubism ZIP.
                            // Every required model reference is checked below, so
                            // skipping extra entries cannot silently omit a needed file.
                            if (relative.substringAfterLast('.', "").lowercase() !in
                                setOf("json", "moc3", "png", "jpg", "jpeg", "webp", "wav", "mp3", "ogg")) {
                                zip.closeEntry()
                                continue
                            }
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).use { output ->
                                var entryBytes = 0L
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    entryBytes += n
                                    expanded += n
                                    require(entryBytes <= 70L * 1024 * 1024 &&
                                        expanded <= 320L * 1024 * 1024) { "解压资源超出安全预算" }
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: error("无法读取模型 ZIP")
            val settingsFiles = staging.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".model3.json", ignoreCase = true) }.toList()
            require(settingsFiles.size == 1) {
                "模型 ZIP 应恰好包含一个 .model3.json，实际找到 ${settingsFiles.size} 个"
            }
            val settings = settingsFiles.single()
            val refs = JSONObject(settings.readText()).getJSONObject("FileReferences")
            validateReference(staging, settings.parentFile!!, refs.getString("Moc"))
            val textures = refs.getJSONArray("Textures")
            require(textures.length() > 0) { "缺少纹理贴图" }
            for (i in 0 until textures.length()) validateReference(staging, settings.parentFile!!, textures.getString(i))
            for (name in listOf("Physics", "Pose", "UserData", "DisplayInfo")) {
                if (refs.has(name) && !refs.isNull(name) &&
                    refs.optString(name).isNotBlank()) {
                    validateReference(staging, settings.parentFile!!, refs.getString(name))
                }
            }
            refs.optJSONArray("Expressions")?.let { expressions ->
                for (i in 0 until expressions.length()) validateReference(staging,
                    settings.parentFile!!, expressions.getJSONObject(i).getString("File"))
            }
            refs.optJSONObject("Motions")?.let { motions ->
                val groups = motions.keys()
                while (groups.hasNext()) {
                    val motionsInGroup = motions.getJSONArray(groups.next())
                    for (i in 0 until motionsInGroup.length()) validateReference(staging,
                        settings.parentFile!!, motionsInGroup.getJSONObject(i).getString("File"))
                }
            }
            // Only select a creator portrait with the model's exact stem, never
            // a free-floating texture (or a generated .lulu-mobile atlas cache).
            val picture = genuinePortrait(settings)
            val previewRelative = picture?.relativeTo(staging)?.invariantSeparatorsPath
            val relativeSettings = settings.relativeTo(staging).invariantSeparatorsPath
            destination.parentFile?.mkdirs()
            check(staging.renameTo(destination)) { "无法存储 Live2D 模型" }
            val stored = "models/$hash/$stamp/$relativeSettings"
            val previous = prefs(context).getString("model:$characterId", null)
            val editor = prefs(context).edit().putString("model:$characterId", stored)
                .putString("label:$characterId", settings.nameWithoutExtension)
            if (previewRelative != null) editor.putString("preview:$characterId", "models/$hash/$stamp/$previewRelative")
            else editor.remove("preview:$characterId")
            check(editor.commit()) {
                "角色模型绑定保存失败"
            }
            if (previous != stored) previous?.let { deleteImportedFolder(context, it) }
            revisionState.value++
            "已导入 ${settings.name}，打开视频画面即可预览。"
        } finally {
            staging.deleteRecursively()
            if (prefs(context).getString("model:$characterId", null)?.contains("/$stamp/") != true)
                destination.deleteRecursively()
        }
    }

    private fun validateReference(root: File, directory: File, relative: String) {
        val target = File(directory, relative.replace('\\', '/'))
        require(inside(root, target) && target.isFile) { "模型资源缺失或越界：$relative" }
    }
    private fun deleteImportedFolder(context: Context, relative: String) {
        val parts = relative.split('/')
        if (parts.size < 4 || parts[0] != "models") return
        val folder = File(base(context), parts.take(3).joinToString("/"))
        if (inside(base(context), folder)) folder.deleteRecursively()
    }
    private fun inside(root: File, target: File) = runCatching {
        target.canonicalPath.startsWith(root.canonicalPath + File.separator)
    }.getOrDefault(false)
}
