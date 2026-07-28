package com.jiacimu.lulu.ai

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.TokenBreakdownItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** A named API site. One site may provide many models. */
data class ApiConfiguration(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val apiKey: String,
)

/** A chat-ready pairing of one saved API site and one model. */
data class ModelArchive(
    val id: String = UUID.randomUUID().toString(),
    val configurationId: String,
    val model: String,
)

data class ModelLibraryState(
    val configurations: List<ApiConfiguration> = emptyList(),
    val archives: List<ModelArchive> = emptyList(),
    val activeArchiveId: String? = null,
)

data class ModelConnection(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
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
    val library: StateFlow<ModelLibraryState> = mutable.asStateFlow()

    fun saveConfiguration(
        id: String?,
        name: String,
        baseUrl: String,
        apiKey: String,
    ): ApiConfiguration {
        val cleanName = name.trim()
        val cleanUrl = normalizeBaseUrl(baseUrl)
        val cleanKey = apiKey.trim()
        require(cleanName.isNotBlank()) { "请填写配置名称" }
        require(cleanUrl.isNotBlank()) { "请填写 API 地址" }
        require(cleanKey.isNotBlank()) { "请填写 API 密钥" }

        val current = mutable.value
        val resolvedId = id
            ?.takeIf { candidate -> current.configurations.any { it.id == candidate } }
            ?: current.configurations.firstOrNull { it.name == cleanName }?.id
            ?: UUID.randomUUID().toString()
        val saved = ApiConfiguration(
            id = resolvedId,
            name = cleanName,
            baseUrl = cleanUrl,
            apiKey = cleanKey,
        )
        val configurations = current.configurations
            .filterNot { it.id == resolvedId }
            .plus(saved)
        persist(current.copy(configurations = configurations))
        return saved
    }

    fun deleteConfiguration(id: String) {
        val current = mutable.value
        val removedArchiveIds = current.archives
            .filter { it.configurationId == id }
            .mapTo(mutableSetOf()) { it.id }
        val archives = current.archives.filterNot { it.id in removedArchiveIds }
        val active = current.activeArchiveId?.takeUnless { it in removedArchiveIds }
        persist(
            current.copy(
                configurations = current.configurations.filterNot { it.id == id },
                archives = archives,
                activeArchiveId = active ?: archives.firstOrNull()?.id,
            ),
        )
    }

    fun addArchive(configurationId: String, model: String): ModelArchive {
        val cleanModel = model.trim()
        require(cleanModel.isNotBlank()) { "请先选择模型" }
        require(mutable.value.configurations.any { it.id == configurationId }) { "请先保存 API 配置" }

        val current = mutable.value
        val existing = current.archives.firstOrNull {
            it.configurationId == configurationId && it.model == cleanModel
        }
        if (existing != null) {
            selectArchive(existing.id)
            return existing
        }
        val archive = ModelArchive(configurationId = configurationId, model = cleanModel)
        persist(
            current.copy(
                archives = current.archives + archive,
                activeArchiveId = archive.id,
            ),
        )
        return archive
    }

    fun removeArchive(id: String) {
        val current = mutable.value
        val archives = current.archives.filterNot { it.id == id }
        persist(
            current.copy(
                archives = archives,
                activeArchiveId = if (current.activeArchiveId == id) archives.firstOrNull()?.id else current.activeArchiveId,
            ),
        )
    }

    fun selectArchive(id: String) {
        require(mutable.value.archives.any { it.id == id }) { "模型存档不存在" }
        persist(mutable.value.copy(activeArchiveId = id))
    }

    fun resolveConnection(archiveId: String? = mutable.value.activeArchiveId): ModelConnection {
        val state = mutable.value
        val archive = state.archives.firstOrNull { it.id == archiveId }
            ?: error("请先在设置中获取模型并加入存档")
        val configuration = state.configurations.firstOrNull { it.id == archive.configurationId }
            ?: error("这个模型存档对应的 API 配置已经不存在")
        return ModelConnection(
            baseUrl = configuration.baseUrl,
            apiKey = configuration.apiKey,
            model = archive.model,
        )
    }

    fun archiveLabel(archive: ModelArchive): String {
        val configurationName = mutable.value.configurations
            .firstOrNull { it.id == archive.configurationId }
            ?.name
            .orEmpty()
            .ifBlank { "未命名配置" }
        return "$configurationName — ${archive.model}"
    }

    private fun load(): ModelLibraryState {
        val raw = prefs.getString(KEY_LIBRARY, null)
        if (!raw.isNullOrBlank()) {
            return runCatching { decode(JSONObject(raw)) }.getOrDefault(ModelLibraryState())
        }

        val legacyUrl = prefs.getString("base_url", "").orEmpty().trim()
        val legacyKey = prefs.getString("api_key", "").orEmpty().trim()
        val legacyModel = prefs.getString("model", "").orEmpty().trim()
        if (legacyUrl.isBlank() || legacyKey.isBlank()) return ModelLibraryState()
        val configuration = ApiConfiguration(
            name = "旧配置",
            baseUrl = normalizeBaseUrl(legacyUrl),
            apiKey = legacyKey,
        )
        val archive = legacyModel.takeIf { it.isNotBlank() }?.let {
            ModelArchive(configurationId = configuration.id, model = it)
        }
        return ModelLibraryState(
            configurations = listOf(configuration),
            archives = listOfNotNull(archive),
            activeArchiveId = archive?.id,
        ).also(::persistRaw)
    }

    private fun persist(state: ModelLibraryState) {
        persistRaw(state)
        mutable.value = state
    }

    private fun persistRaw(state: ModelLibraryState) {
        prefs.edit().putString(KEY_LIBRARY, encode(state).toString()).apply()
    }

    private fun encode(state: ModelLibraryState): JSONObject = JSONObject()
        .put(
            "configurations",
            JSONArray().apply {
                state.configurations.forEach { configuration ->
                    put(
                        JSONObject()
                            .put("id", configuration.id)
                            .put("name", configuration.name)
                            .put("baseUrl", configuration.baseUrl)
                            .put("apiKey", configuration.apiKey),
                    )
                }
            },
        )
        .put(
            "archives",
            JSONArray().apply {
                state.archives.forEach { archive ->
                    put(
                        JSONObject()
                            .put("id", archive.id)
                            .put("configurationId", archive.configurationId)
                            .put("model", archive.model),
                    )
                }
            },
        )
        .put("activeArchiveId", state.activeArchiveId ?: JSONObject.NULL)

    private fun decode(root: JSONObject): ModelLibraryState {
        val configurations = buildList {
            val array = root.optJSONArray("configurations") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
                val name = item.optString("name").trim()
                val baseUrl = normalizeBaseUrl(item.optString("baseUrl"))
                val apiKey = item.optString("apiKey").trim()
                if (name.isNotBlank() && baseUrl.isNotBlank() && apiKey.isNotBlank()) {
                    add(ApiConfiguration(id, name, baseUrl, apiKey))
                }
            }
        }
        val configurationIds = configurations.mapTo(mutableSetOf()) { it.id }
        val archives = buildList {
            val array = root.optJSONArray("archives") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val configurationId = item.optString("configurationId")
                val model = item.optString("model").trim()
                if (configurationId in configurationIds && model.isNotBlank()) {
                    add(
                        ModelArchive(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            configurationId = configurationId,
                            model = model,
                        ),
                    )
                }
            }
        }
        val savedActive = root.optString("activeArchiveId").takeIf { candidate ->
            archives.any { it.id == candidate }
        }
        return ModelLibraryState(
            configurations = configurations,
            archives = archives,
            activeArchiveId = savedActive ?: archives.firstOrNull()?.id,
        )
    }

    companion object {
        private const val PREFS_NAME = "lulu_model_connection"
        private const val KEY_LIBRARY = "model_library_v2"

        fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')

        fun create(context: Context): ModelConnectionStore = ModelConnectionStore(context.applicationContext)
    }
}

class CompanionModelGateway(
    private val connectionStore: ModelConnectionStore,
) {
    suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val cleanUrl = ModelConnectionStore.normalizeBaseUrl(baseUrl)
        val cleanKey = apiKey.trim()
        val requestUrl = "$cleanUrl/models"
        val startedAt = System.nanoTime()
        runCatching {
            check(cleanUrl.isNotBlank()) { "请填写 API 地址" }
            check(cleanKey.isNotBlank()) { "请填写 API 密钥" }
            val json = requestGetJson(
                url = requestUrl,
                headers = mapOf("Authorization" to "Bearer $cleanKey"),
            )
            val arrays = listOfNotNull(json.optJSONArray("data"), json.optJSONArray("models"))
            val models = buildList {
                arrays.forEach { array ->
                    for (index in 0 until array.length()) {
                        when (val item = array.opt(index)) {
                            is JSONObject -> {
                                val id = item.optString("id").ifBlank { item.optString("name") }.removePrefix("models/")
                                if (id.isNotBlank()) add(id)
                            }
                            is String -> if (item.isNotBlank()) add(item.removePrefix("models/"))
                        }
                    }
                }
            }.distinct().sorted()
            check(models.isNotEmpty()) { "接口连接成功，但没有读取到模型列表" }
            models
        }.onFailure { error ->
            LuluRepositories.performance.recordError(
                source = "设置",
                title = "获取模型",
                message = error.message ?: error::class.java.simpleName,
                requestUrl = requestUrl,
                durationMillis = elapsedMillis(startedAt),
            )
        }
    }

    suspend fun generate(
        characterId: String,
        facts: String,
        instruction: String,
        source: String,
        title: String,
        temperature: Double = 0.8,
        maxTokens: Int = 500,
    ): Result<ModelReply> = withContext(Dispatchers.IO) {
        val totalStartedAt = System.nanoTime()
        var requestUrl: String? = null
        runCatching {
            val promptStartedAt = System.nanoTime()
            val connection = connectionStore.resolveConnection()
            requestUrl = "${connection.baseUrl}/chat/completions"
            val character = MigratedDomainStores.characters.get(characterId)
            val memories = LuluRepositories.memory.snapshot(characterId).take(24)
            val lexicon = LuluRepositories.lexicon.snapshot(characterId).take(24)
            val worldBooks = LuluRepositories.worldBook.snapshot().filter { entry ->
                MigratedDomainStores.worldBookRules.isEnabled(
                    worldBookId = entry.id,
                    characterId = characterId,
                    globalEnabled = entry.globalEnabled,
                )
            }

            val baseRules = buildString {
                appendLine("你正在以‘${character.displayName.ifBlank { "角色" }}’的身份参与露露机中的真实活动。")
                appendLine("角色的人设、关系边界、世界观和语言习惯拥有最高优先级。")
                appendLine("程序给出的题目、抽卡、计时、骰子、棋局、得分和历史记录都是不可修改的事实。")
                appendLine("不得默认温柔、亲密、活泼、顺从、吐槽或夸奖；只输出该角色按其人设真正会说的话。")
                appendLine("本次任务：$instruction")
            }.trim()
            val personaSection = character.persona.takeIf(String::isNotBlank)?.let { "角色人设：\n$it" }.orEmpty()
            val worldBookSection = if (worldBooks.isEmpty()) "" else buildString {
                appendLine("适用世界书：")
                worldBooks.forEach { appendLine("- ${it.title}：${it.content}") }
            }.trim()
            val memorySection = if (memories.isEmpty()) "" else buildString {
                appendLine("可用连续记忆（只能按内容本身使用，不得扩写成未发生事实）：")
                memories.forEach { appendLine("- ${it.content}") }
            }.trim()
            val lexiconSection = if (lexicon.isEmpty()) "" else buildString {
                appendLine("辞海资料：")
                lexicon.forEach { appendLine("- ${it.section.name}/${it.title}：${it.content}") }
            }.trim()
            val systemPrompt = listOf(baseRules, personaSection, worldBookSection, memorySection, lexiconSection)
                .filter(String::isNotBlank)
                .joinToString("\n")
            val userPrompt = "真实事实：\n${facts.trim()}"
            val breakdown = listOf(
                tokenBreakdown("系统/角色人设", baseRules.length + personaSection.length),
                tokenBreakdown("记忆/状态/感知", worldBookSection.length + memorySection.length + lexiconSection.length),
                tokenBreakdown("工具/MCP说明", 0),
                tokenBreakdown("用户上下文", userPrompt.length),
                tokenBreakdown("助手上下文", 0),
                tokenBreakdown("其他", 0),
            )
            val estimatedInputTokens = breakdown.sumOf(TokenBreakdownItem::estimatedTokens)
            val promptMillis = elapsedMillis(promptStartedAt)
            val modelStartedAt = System.nanoTime()
            val reply = openAiCompatible(connection, systemPrompt, userPrompt, temperature, maxTokens)
            val modelMillis = elapsedMillis(modelStartedAt)
            val totalMillis = elapsedMillis(totalStartedAt)
            LuluRepositories.performance.recordGeneration(
                source = source,
                title = title,
                model = connection.model,
                provider = runCatching { URL(connection.baseUrl).host }.getOrDefault(connection.baseUrl),
                reportedInputTokens = reply.inputTokens,
                reportedOutputTokens = reply.outputTokens,
                cachedTokens = reply.cachedTokens,
                estimatedInputTokens = estimatedInputTokens,
                estimatedOutputTokens = estimateTokens(reply.text.length),
                breakdown = breakdown,
                promptMillis = promptMillis,
                modelMillis = modelMillis,
                totalMillis = totalMillis,
            )
            reply
        }.onFailure { error ->
            LuluRepositories.performance.recordError(
                source = source,
                title = title,
                message = error.message ?: error::class.java.simpleName,
                requestUrl = requestUrl,
                durationMillis = elapsedMillis(totalStartedAt),
            )
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
        val json = requestPostJson(
            url = "${connection.baseUrl}/chat/completions",
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

    private fun requestGetJson(url: String, headers: Map<String, String>): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            readJsonResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestPostJson(
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
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            readJsonResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readJsonResponse(connection: HttpURLConnection): JSONObject {
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
        check(raw.isNotBlank()) { "接口返回了空内容" }
        return if (raw.trimStart().startsWith("[")) {
            JSONObject().put("data", JSONArray(raw))
        } else {
            JSONObject(raw)
        }
    }
}

private fun tokenBreakdown(label: String, chars: Int): TokenBreakdownItem = TokenBreakdownItem(
    label = label,
    chars = chars.coerceAtLeast(0),
    estimatedTokens = estimateTokens(chars.coerceAtLeast(0)),
)

private fun estimateTokens(chars: Int): Int = ((chars / 1.8f) + 0.5f).toInt().coerceAtLeast(0)

private fun elapsedMillis(startedAtNanos: Long): Long =
    ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)

object LuluAiServices {
    private var connectionStoreInternal: ModelConnectionStore? = null
    private var gatewayInternal: CompanionModelGateway? = null

    val connectionStore: ModelConnectionStore
        get() = checkNotNull(connectionStoreInternal) { "LuluAiServices 尚未初始化" }

    val gateway: CompanionModelGateway
        get() = checkNotNull(gatewayInternal) { "LuluAiServices 尚未初始化" }

    fun initialize(context: Context) {
        if (connectionStoreInternal != null) return
        connectionStoreInternal = ModelConnectionStore.create(context)
        gatewayInternal = CompanionModelGateway(connectionStore)
    }
}
