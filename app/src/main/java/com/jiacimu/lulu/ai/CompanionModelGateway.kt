package com.jiacimu.lulu.ai

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared model gateway used by independently redesigned Lulu1 features.
 *
 * The old project supported several providers. This implementation keeps the same
 * product capability without copying its provider/UI classes: OpenAI-compatible,
 * Anthropic Messages, and Gemini generateContent are normalized behind one API.
 */
enum class ModelProviderKind { OpenAICompatible, Anthropic, Gemini }

data class ModelConnection(
    val provider: ModelProviderKind = ModelProviderKind.OpenAICompatible,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "",
    val enabled: Boolean = false,
)

data class ModelReply(
    val text: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
)

class ModelConnectionStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<ModelConnection> = mutable.asStateFlow()

    fun save(connection: ModelConnection) {
        val clean = connection.copy(
            baseUrl = connection.baseUrl.trim().trimEnd('/'),
            apiKey = connection.apiKey.trim(),
            model = connection.model.trim(),
        )
        prefs.edit()
            .putString(KEY_PROVIDER, clean.provider.name)
            .putString(KEY_BASE_URL, clean.baseUrl)
            .putString(KEY_API_KEY, clean.apiKey)
            .putString(KEY_MODEL, clean.model)
            .putBoolean(KEY_ENABLED, clean.enabled)
            .apply()
        mutable.value = clean
    }

    private fun load(): ModelConnection {
        val provider = runCatching {
            ModelProviderKind.valueOf(prefs.getString(KEY_PROVIDER, null).orEmpty())
        }.getOrDefault(ModelProviderKind.OpenAICompatible)
        return ModelConnection(
            provider = provider,
            baseUrl = prefs.getString(KEY_BASE_URL, null)
                ?: defaultBaseUrl(provider),
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            model = prefs.getString(KEY_MODEL, "").orEmpty(),
            enabled = prefs.getBoolean(KEY_ENABLED, false),
        )
    }

    companion object {
        private const val PREFS_NAME = "lulu_model_connection"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_ENABLED = "enabled"

        fun defaultBaseUrl(provider: ModelProviderKind): String = when (provider) {
            ModelProviderKind.OpenAICompatible -> "https://api.openai.com/v1"
            ModelProviderKind.Anthropic -> "https://api.anthropic.com/v1"
            ModelProviderKind.Gemini -> "https://generativelanguage.googleapis.com/v1beta"
        }

        fun create(context: Context): ModelConnectionStore = ModelConnectionStore(context.applicationContext)
    }
}

class CompanionModelGateway(
    private val connectionStore: ModelConnectionStore,
) {
    suspend fun generate(
        characterId: String,
        facts: String,
        instruction: String,
        source: String,
        title: String,
        temperature: Double = 0.8,
        maxTokens: Int = 500,
    ): Result<ModelReply> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = connectionStore.state.value
            check(connection.enabled) { "模型调用未启用" }
            check(connection.apiKey.isNotBlank()) { "请先在设置中填写 API 密钥" }
            check(connection.model.isNotBlank()) { "请先在设置中填写模型名称" }

            val character = MigratedDomainStores.characters.get(characterId)
            val systemPrompt = buildString {
                appendLine("你正在以‘${character.displayName.ifBlank { "角色" }}’的身份参与露露机中的真实活动。")
                appendLine("角色的人设、关系边界、世界观和语言习惯拥有最高优先级。")
                appendLine("程序给出的题目、抽卡、计时、骰子、棋局、得分和历史记录都是不可修改的事实。")
                appendLine("不得默认温柔、亲密、活泼、顺从、吐槽或夸奖；只输出该角色按其人设真正会说的话。")
                if (character.persona.isNotBlank()) {
                    appendLine("角色人设：")
                    appendLine(character.persona)
                }
                appendLine("本次任务：$instruction")
            }.trim()
            val userPrompt = "真实事实：\n${facts.trim()}"

            val reply = when (connection.provider) {
                ModelProviderKind.OpenAICompatible -> openAiCompatible(connection, systemPrompt, userPrompt, temperature, maxTokens)
                ModelProviderKind.Anthropic -> anthropic(connection, systemPrompt, userPrompt, temperature, maxTokens)
                ModelProviderKind.Gemini -> gemini(connection, systemPrompt, userPrompt, temperature, maxTokens)
            }
            LuluRepositories.performance.addTokenUsage(
                input = reply.inputTokens,
                output = reply.outputTokens,
                cached = reply.cachedTokens,
            )
            reply
        }.onFailure { error ->
            LuluRepositories.performance.recordError("$source · $title：${error.message ?: error::class.java.simpleName}")
        }
    }

    private fun openAiCompatible(
        connection: ModelConnection,
        system: String,
        user: String,
        temperature: Double,
        maxTokens: Int,
    ): ModelReply {
        val body = JSONObject()
            .put("model", connection.model)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        val json = requestJson(
            url = "${connection.baseUrl.ifBlank { ModelConnectionStore.defaultBaseUrl(connection.provider) }}/chat/completions",
            headers = mapOf("Authorization" to "Bearer ${connection.apiKey}"),
            body = body,
        )
        val text = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
            .trim()
        check(text.isNotBlank()) { "模型没有返回可读取的内容" }
        val usage = json.optJSONObject("usage")
        val promptDetails = usage?.optJSONObject("prompt_tokens_details")
        return ModelReply(
            text = text,
            inputTokens = usage?.optInt("prompt_tokens") ?: 0,
            outputTokens = usage?.optInt("completion_tokens") ?: 0,
            cachedTokens = promptDetails?.optInt("cached_tokens") ?: 0,
        )
    }

    private fun anthropic(
        connection: ModelConnection,
        system: String,
        user: String,
        temperature: Double,
        maxTokens: Int,
    ): ModelReply {
        val body = JSONObject()
            .put("model", connection.model)
            .put("system", system)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", user),
                ),
            )
        val json = requestJson(
            url = "${connection.baseUrl.ifBlank { ModelConnectionStore.defaultBaseUrl(connection.provider) }}/messages",
            headers = mapOf(
                "x-api-key" to connection.apiKey,
                "anthropic-version" to "2023-06-01",
            ),
            body = body,
        )
        val text = json.optJSONArray("content")
            ?.let { array ->
                buildString {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index)
                        if (item?.optString("type") == "text") append(item.optString("text"))
                    }
                }
            }
            .orEmpty()
            .trim()
        check(text.isNotBlank()) { "模型没有返回可读取的内容" }
        val usage = json.optJSONObject("usage")
        return ModelReply(
            text = text,
            inputTokens = usage?.optInt("input_tokens") ?: 0,
            outputTokens = usage?.optInt("output_tokens") ?: 0,
            cachedTokens = (usage?.optInt("cache_read_input_tokens") ?: 0) +
                (usage?.optInt("cache_creation_input_tokens") ?: 0),
        )
    }

    private fun gemini(
        connection: ModelConnection,
        system: String,
        user: String,
        temperature: Double,
        maxTokens: Int,
    ): ModelReply {
        val modelName = connection.model.removePrefix("models/")
        val base = connection.baseUrl.ifBlank { ModelConnectionStore.defaultBaseUrl(connection.provider) }
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))),
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user))),
                ),
            )
            .put(
                "generationConfig",
                JSONObject().put("temperature", temperature).put("maxOutputTokens", maxTokens),
            )
        val json = requestJson(
            url = "$base/models/$modelName:generateContent?key=${connection.apiKey}",
            headers = emptyMap(),
            body = body,
        )
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
        val text = parts?.let { array ->
            buildString {
                for (index in 0 until array.length()) append(array.optJSONObject(index)?.optString("text").orEmpty())
            }
        }.orEmpty().trim()
        check(text.isNotBlank()) { "模型没有返回可读取的内容" }
        val usage = json.optJSONObject("usageMetadata")
        return ModelReply(
            text = text,
            inputTokens = usage?.optInt("promptTokenCount") ?: 0,
            outputTokens = usage?.optInt("candidatesTokenCount") ?: 0,
            cachedTokens = usage?.optInt("cachedContentTokenCount") ?: 0,
        )
    }

    private fun requestJson(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach(connection::setRequestProperty)
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }
                    .getOrNull()
                    .orEmpty()
                    .ifBlank { raw.take(500) }
                error("模型请求失败（$status）：$message")
            }
            JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }
}

object LuluAiServices {
    private lateinit var connectionStoreInternal: ModelConnectionStore
    private lateinit var gatewayInternal: CompanionModelGateway

    val connectionStore: ModelConnectionStore
        get() = checkNotNull(connectionStoreInternal.takeIf { ::connectionStoreInternal.isInitialized }) {
            "LuluAiServices 尚未初始化"
        }

    val gateway: CompanionModelGateway
        get() = checkNotNull(gatewayInternal.takeIf { ::gatewayInternal.isInitialized }) {
            "LuluAiServices 尚未初始化"
        }

    fun initialize(context: Context) {
        if (::connectionStoreInternal.isInitialized) return
        connectionStoreInternal = ModelConnectionStore.create(context)
        gatewayInternal = CompanionModelGateway(connectionStoreInternal)
    }
}
