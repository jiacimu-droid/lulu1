package com.jiacimu.lulu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

/**
 * The selected model is downloaded once to private app storage; ASR NEVER uploads audio.
 * Files are only considered installed after a complete, size-checked atomic promotion.
 */
internal object LocalWhisperModels {
    enum class Model(val id: String, val title: String, val sizeLabel: String, val minimumBytes: Long) {
        TINY("tiny", "Whisper Tiny · 更快", "约 75 MB", 70_000_000),
        BASE("base", "Whisper Base · 更准", "约 142 MB", 135_000_000);

        companion object {
            fun from(value: String?): Model = entries.firstOrNull { it.id == value } ?: TINY
        }
    }

    fun selected(context: Context): Model = Model.from(
        context.getSharedPreferences("lulu_advanced_settings", 0).getString("local_whisper_model", null)
    )

    private fun directory(context: Context) = File(context.filesDir, "whisper_models")

    private fun target(context: Context, model: Model) =
        File(directory(context), "ggml-${model.id}.bin")

    fun installedFile(context: Context, model: Model): File? =
        target(context, model).takeIf { it.isFile && it.length() >= model.minimumBytes }

    fun remove(context: Context, model: Model): Boolean {
        val file = target(context, model)
        return !file.exists() || file.delete()
    }

    suspend fun download(
        context: Context, model: Model, progress: suspend (Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dest = target(context, model)
        check(directory(context).isDirectory || directory(context).mkdirs()) { "无法建立本地模型目录" }
        val tmp = File(directory(context), "ggml-${model.id}.bin.part")
        val url = URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-${model.id}.bin")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 45_000
            setRequestProperty("User-Agent", "Lulu-Whisper-Android/1.0")
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "模型下载失败（HTTP ${connection.responseCode}）"
            }
            check(connection.url.protocol == "https") { "模型下载必须使用 HTTPS" }
            val expected = connection.contentLengthLong
            if (expected > 0) check(expected >= model.minimumBytes) { "服务器返回的模型文件大小异常" }
            var copied = 0L
            var lastProgress = 0L
            connection.inputStream.use { input ->
                tmp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (copied - lastProgress >= 1_048_576L) {
                            lastProgress = copied
                            progress(copied, expected)
                        }
                    }
                }
            }
            check(copied >= model.minimumBytes && (expected <= 0 || copied == expected)) {
                "模型下载不完整，请重新下载"
            }
            // Only promote a complete file. Keep a previously installed model if a retry fails.
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            progress(copied, copied)
            dest
        } finally {
            connection.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }
}

/** JNI speech inference runs on Dispatchers.IO and never makes an ASR network request. */
internal object LocalWhisperTranscriber {
    private val nativeLoaded by lazy {
        runCatching { System.loadLibrary("lulu-whisper") }
    }

    private external fun nativeTranscribe(modelPath: String, pcm16le: ByteArray): String

    suspend fun transcribe(context: Context, pcm16le: ByteArray): String = withContext(Dispatchers.IO) {
        val model = LocalWhisperModels.selected(context)
        val file = LocalWhisperModels.installedFile(context, model)
            ?: error("请先在语音设置下载 Whisper ${model.id} 模型")
        nativeLoaded.getOrThrow()
        nativeTranscribe(file.absolutePath, pcm16le).trim()
    }
}
