package com.jiacimu.lulu.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object VisionModelService {
    private const val PREFS_NAME = "lulu_advanced_settings"
    const val KEY_BASE_URL = "vision_base_url"
    const val KEY_API_KEY = "vision_api_key"
    const val KEY_MODEL = "vision_model"

    data class Configuration(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val sourceLabel: String,
    ) {
        val ready: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    }

    fun configuration(context: Context): Configuration {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Configuration(
            baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty().trim().trimEnd('/'),
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty().trim(),
            model = prefs.getString(KEY_MODEL, "").orEmpty().trim(),
            sourceLabel = "单独识图模型",
        )
    }

    suspend fun describeImage(
        context: Context,
        imageUri: String,
        caption: String = "",
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val dedicated = configuration(context).takeIf(Configuration::ready)
            val chat = chatConfiguration()
            val candidates = buildList {
                dedicated?.let(::add)
                if (chat != null && candidatesAreDifferent(dedicated, chat)) add(chat)
            }
            check(candidates.isNotEmpty()) {
                "没有可用的识图模型：可以在识图设置里单独选择一个支持图片的模型，或者直接把当前聊天模型换成支持视觉输入的模型。"
            }

            val dataUrl = imageDataUrl(context, Uri.parse(imageUri))
            val prompt = buildString {
                appendLine("请准确理解这张聊天或朋友圈图片，输出给角色作为看图上下文。")
                appendLine("请描述：主要人物或物体、场景、动作、明显细节、画面里能读到的文字、整体氛围。")
                appendLine("只写你能从图片确认或合理看出的内容；不确定的地方明确说不确定，不编造看不见的信息。")
                appendLine("不要尝试识别现实人物身份，也不要推断敏感隐私。")
                appendLine("用自然、紧凑的中文写成一段，不要 JSON，不要标题，控制在 600 字以内。")
                if (caption.isNotBlank()) appendLine("发图者配文：${caption.trim().take(1200)}")
            }

            val errors = mutableListOf<String>()
            for (candidate in candidates) {
                val attempt = runCatching { describeWith(candidate, dataUrl, prompt) }
                if (attempt.isSuccess) return@runCatching attempt.getOrThrow()
                val detail = attempt.exceptionOrNull()?.message.orEmpty().ifBlank { "未知错误" }
                errors += "${candidate.sourceLabel}（${candidate.model}）：$detail"
            }
            error("识图失败：${errors.joinToString("；").take(1_200)}")
        }
    }

    private fun chatConfiguration(): Configuration? = runCatching {
        val store = LuluAiServices.connectionStore
        val archiveId = store.selectedArchiveId(ModelUsage.Chat)
        val connection = store.resolveConnection(archiveId)
        Configuration(
            baseUrl = connection.baseUrl.trim().trimEnd('/'),
            apiKey = connection.apiKey.trim(),
            model = connection.model.trim(),
            sourceLabel = "当前聊天模型",
        )
    }.getOrNull()?.takeIf(Configuration::ready)

    private fun candidatesAreDifferent(first: Configuration?, second: Configuration): Boolean =
        first == null ||
            first.baseUrl != second.baseUrl ||
            first.apiKey != second.apiKey ||
            first.model != second.model

    private fun describeWith(config: Configuration, dataUrl: String, prompt: String): String {
        val userContent = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject()
                            .put("url", dataUrl)
                            .put("detail", "high"),
                    ),
            )
        val body = JSONObject()
            .put("model", config.model)
            .put("max_tokens", 900)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", userContent)),
            )
        val url = "${config.baseUrl}/chat/completions"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                val errorMessage = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty().ifBlank { raw.take(500) }
                error("请求失败（$status）：$errorMessage")
            }
            check(raw.isNotBlank()) { "接口返回了空内容" }
            val root = JSONObject(raw)
            val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            val text = extractText(message?.opt("content"))
                .ifBlank { extractText(root.opt("output_text")) }
                .ifBlank { extractText(root.opt("output")) }
                .trim()
            check(text.isNotBlank()) { "模型没有返回可读取的图片描述" }
            return text.take(1_800)
        } finally {
            connection.disconnect()
        }
    }

    private fun imageDataUrl(context: Context, uri: Uri): String {
        val resolver = context.applicationContext.contentResolver
        val bitmap = resolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: error("无法读取这张图片")
        val scaled = scaleDown(bitmap, 2_048)
        val bytes = ByteArrayOutputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 92, output)
            output.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        check(bytes.isNotEmpty()) { "图片转换失败" }
        return "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / largest.toFloat()
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun extractText(value: Any?): String = when (value) {
        is String -> value
        is JSONObject -> sequenceOf("text", "content", "output_text")
            .map { key -> extractText(value.opt(key)) }
            .firstOrNull(String::isNotBlank)
            .orEmpty()
        is JSONArray -> (0 until value.length()).joinToString("") { index -> extractText(value.opt(index)) }
        else -> ""
    }
}
