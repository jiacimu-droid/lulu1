package com.jiacimu.lulu.ai

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.data.CharacterIdentityStore
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.RelevantMemoryRecall
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.TokenBreakdownItem
import com.jiacimu.lulu.data.UserProfileContext
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

enum class ModelUsage {
    Chat,
    VoiceCall,
    Game,
}

/**
 * Full = normal Lulu world: identity + role settings + memories/worldbook/state.
 * PersonaAndScenario = cross-world scenario: role settings only; original-world identity is excluded.
 */
enum class CompanionContextMode {
    Full,
    PersonaAndScenario,
}

data class ModelLibraryState(
    val configurations: List<ApiConfiguration> = emptyList(),
    val archives: List<ModelArchive> = emptyList(),
    val activeArchiveId: String? = null,
    val chatArchiveId: String? = null,
    val voiceCallArchiveId: String? = null,
    val gameArchiveId: String? = null,
)

fun ModelLibraryState.archiveIdFor(usage: ModelUsage): String? = when (usage) {
    ModelUsage.Chat -> chatArchiveId
    ModelUsage.VoiceCall -> voiceCallArchiveId
    ModelUsage.Game -> gameArchiveId
}?.takeIf { candidate -> archives.any { it.id == candidate } }
    ?: activeArchiveId?.takeIf { candidate -> archives.any { it.id == candidate } }
    ?: archives.firstOrNull()?.id

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
        val archives = current.archives.filterNot { it.id == id }
        val active = current.activeArchiveId?.takeUnless { it in removedArchiveIds }
        persist(
            current.copy(
                configurations = current.configurations.filterNot { it.id == id },
                archives = archives,
                activeArchiveId = active ?: archives.firstOrNull()?.id,
                chatArchiveId = current.chatArchiveId?.takeUnless { it in removedArchiveIds },
                voiceCallArchiveId = current.voiceCallArchiveId?.takeUnless { it in removedArchiveIds },
                gameArchiveId = current.gameArchiveId?.takeUnless { it in removedArchiveIds },
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
                chatArchiveId = current.chatArchiveId?.takeUnless { it == id },
                voiceCallArchiveId = current.voiceCallArchiveId?.takeUnless { it == id },
                gameArchiveId = current.gameArchiveId?.takeUnless { it == id },
            ),
        )
    }

    fun selectArchive(id: String) {
        require(mutable.value.archives.any { it.id == id }) { "模型存档不存在" }
        persist(mutable.value.copy(activeArchiveId = id))
    }

    fun selectArchive(id: String, usage: ModelUsage) {
        require(mutable.value.archives.any { it.id == id }) { "模型存档不存在" }
        val current = mutable.value
        persist(
            when (usage) {
                ModelUsage.Chat -> current.copy(chatArchiveId = id)
                ModelUsage.VoiceCall -> current.copy(voiceCallArchiveId = id)
                ModelUsage.Game -> current.copy(gameArchiveId = id)
            },
        )
    }

    fun selectedArchiveId(usage: ModelUsage): String? = mutable.value.archiveIdFor(usage)

    fun resolveConnection(archiveId: String? = mutable.value.activeArchiveId): ModelConnection {
        val state = mutable.value
        val resolvedArchiveId = archiveId ?: state.activeArchiveId ?: state.archives.firstOrNull()?.id
        val archive = state.archives.firstOrNull { it.id == resolvedArchiveId }
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
        .put("chatArchiveId", state.chatArchiveId ?: JSONObject.NULL)
        .put("voiceCallArchiveId", state.voiceCallArchiveId ?: JSONObject.NULL)
        .put("gameArchiveId", state.gameArchiveId ?: JSONObject.NULL)

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
            chatArchiveId = root.optString("chatArchiveId").takeIf { candidate -> archives.any { it.id == candidate } },
            voiceCallArchiveId = root.optString("voiceCallArchiveId").takeIf { candidate -> archives.any { it.id == candidate } },
            gameArchiveId = root.optString("gameArchiveId").takeIf { candidate -> archives.any { it.id == candidate } },
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
    suspend fun embed(connection: ModelConnection, inputs: List<String>): Result<List<FloatArray>> = withContext(Dispatchers.IO) {
        runCatching {
            check(inputs.isNotEmpty()) { "Embedding 输入不能为空" }
            val json = requestPostJson(
                url = "${connection.baseUrl.trimEnd('/')}/embeddings",
                headers = mapOf("Authorization" to "Bearer ${connection.apiKey}"),
                body = JSONObject().put("model", connection.model).put("input", JSONArray(inputs)),
            )
            val data = json.optJSONArray("data") ?: error("Embedding 接口没有返回 data")
            val indexed = buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val values = item.optJSONArray("embedding") ?: continue
                    add((item.optInt("index", index)) to FloatArray(values.length()) { position -> values.optDouble(position).toFloat() })
                }
            }.sortedBy { it.first }.map { it.second }
            check(indexed.size == inputs.size) { "Embedding 返回数量与输入不一致" }
            indexed
        }
    }

    suspend fun rerank(connection: ModelConnection, query: String, documents: List<String>): Result<List<Int>> = withContext(Dispatchers.IO) {
        runCatching {
            check(documents.isNotEmpty()) { "Rerank 文档不能为空" }
            val json = requestPostJson(
                url = "${connection.baseUrl.trimEnd('/')}/rerank",
                headers = mapOf("Authorization" to "Bearer ${connection.apiKey}"),
                body = JSONObject()
                    .put("model", connection.model)
                    .put("query", query)
                    .put("documents", JSONArray(documents))
                    .put("top_n", documents.size)
                    .put("return_documents", false),
            )
            val results = json.optJSONArray("results") ?: json.optJSONArray("data") ?: error("Rerank 接口没有返回 results")
            buildList {
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    add(item.optInt("index", -1))
                }
            }.filter { it in documents.indices }.distinct()
        }
    }

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
        connectionOverride: ModelConnection? = null,
        usage: ModelUsage? = null,
        contextMode: CompanionContextMode = CompanionContextMode.Full,
        streamResponse: Boolean = false,
        readTimeoutMillis: Int = DEFAULT_MODEL_READ_TIMEOUT_MILLIS,
        onStreamText: ((String) -> Unit)? = null,
    ): Result<ModelReply> = withContext(Dispatchers.IO) {
        val totalStartedAt = System.nanoTime()
        var requestUrl: String? = null
        var attemptedModel: String? = null
        runCatching {
            val promptStartedAt = System.nanoTime()
            val connection = connectionOverride ?: connectionStore.resolveConnection(
                usage?.let(connectionStore::selectedArchiveId),
            )
            attemptedModel = connection.model
            requestUrl = "${connection.baseUrl}/chat/completions"
            val character = MigratedDomainStores.characters.get(characterId)
            val fullContext = contextMode == CompanionContextMode.Full
            val identity = CharacterIdentityStore.get(characterId).takeIf { fullContext }.orEmpty()
            val presence = CompanionPresenceStore.current(characterId).takeIf { fullContext }
            val memories = if (fullContext) RelevantMemoryRecall.recall(characterId, "$facts\n$instruction", limit = 12) else emptyList()
            val recentSharedTimeline = if (fullContext) SharedExperienceTimeline.recentContext(characterId) else ""
            val userProfileSection = if (fullContext) UserProfileContext.promptSection() else ""
            val lexicon = if (fullContext) LuluRepositories.lexicon.snapshot(characterId).take(24) else emptyList()
            val allWorldBooks = if (fullContext) LuluRepositories.worldBook.snapshot() else emptyList()
            val globalWorldBooks = allWorldBooks.filter { entry ->
                entry.globalEnabled && entry.characterOverrides[characterId] != false
            }
            val roleWorldBooks = allWorldBooks.filter { entry ->
                !entry.globalEnabled && entry.characterOverrides[characterId] == true
            }

            val baseRules = buildString {
                appendLine("你正在以‘${character.displayName.ifBlank { "角色" }}’参与露露机中的当前活动。")
                if (fullContext) {
                    appendLine("这是角色原本所属的露露机世界：角色身份与角色设定都必须生效，身份、关系边界、世界观和语言习惯拥有最高优先级。")
                } else {
                    appendLine("这是独立跨世界场景：只继承角色设定中的性格、语言习惯、价值观和关系边界；不得带入角色原世界的身份、职业、时代、阵营或背景。")
                }
                appendLine("角色与用户是什么关系、如何称呼用户，只能来自角色设定、当前场景或明确提供的事实；不得默认用户是‘主人’，也不得默认恋人、朋友或上下级关系。")
                appendLine("程序给出的题目、抽卡、计时、骰子、棋局、得分和历史记录都是不可修改的事实。")
                appendLine("不得默认温柔、亲密、活泼、顺从、吐槽或夸奖；只输出该角色按其设定真正会说的话。")
                appendLine("本次任务：$instruction")
            }.trim()
            val identitySection = identity.takeIf(String::isNotBlank)?.let { "角色身份：\n$it" }.orEmpty()
            val personaSection = character.persona.takeIf(String::isNotBlank)?.let { "角色设定：\n$it" }.orEmpty()
            val globalWorldBookSection = if (globalWorldBooks.isEmpty()) "" else buildString {
                appendLine("全局世界书：")
                globalWorldBooks.forEach { entry -> appendLine("- ${entry.title}：${entry.content}") }
            }.trim()
            val roleWorldBookSection = if (roleWorldBooks.isEmpty()) "" else buildString {
                appendLine("角色世界书：")
                roleWorldBooks.forEach { entry -> appendLine("- ${entry.title}：${entry.content}") }
            }.trim()
            val memorySection = if (memories.isEmpty()) "" else buildString {
                appendLine("可用连续记忆（只能按内容本身使用，不得扩写成未发生事实）：")
                memories.forEach { appendLine("- ${it.content}") }
            }.trim()
            val timelineSection = recentSharedTimeline.takeIf(String::isNotBlank)?.let {
                "最近共同时间线（真实原始记录，按时间连续发生）：\n$it"
            }.orEmpty()
            val presenceSection = presence?.let { state ->
                buildString {
                    appendLine("角色当前私密状态（只能内化后自然延续，不得原样复述标签或把心声全部说出口）：")
                    if (state.statusText.isNotBlank()) appendLine("- 状态：${state.statusText}")
                    if (state.gesture.isNotBlank()) appendLine("- 此刻动作：${state.gesture}")
                    if (state.mood.isNotBlank()) appendLine("- 心情：${state.mood}")
                    if (state.innerThought.isNotBlank()) appendLine("- 没说出口：${state.innerThought}")
                }.trim()
            }.orEmpty()
            val lexiconSection = if (lexicon.isEmpty()) "" else buildString {
                appendLine("辞海资料：")
                lexicon.forEach { appendLine("- ${it.section.name}/${it.title}：${it.content}") }
            }.trim()
            val systemPrompt = listOf(
                baseRules,
                identitySection,
                personaSection,
                userProfileSection,
                globalWorldBookSection,
                roleWorldBookSection,
                presenceSection,
                timelineSection,
                memorySection,
                lexiconSection,
            ).filter(String::isNotBlank).joinToString("\n\n")
            val fixedEstimatedTokens = estimateTokens(systemPrompt.length)
            check(fixedEstimatedTokens <= CHAT_FIXED_CONTEXT_TOKEN_LIMIT) {
                "当前角色的固定身份、设定和世界书约需 $fixedEstimatedTokens tokens，超过聊天安全预算 $CHAT_FIXED_CONTEXT_TOKEN_LIMIT；不会静默裁剪，请缩短固定设定或减少启用的世界书。"
            }
            val userPrompt = "真实事实：\n${facts.trim()}"
            val breakdown = listOf(
                tokenBreakdown("系统/角色身份与设定", baseRules.length + identitySection.length + personaSection.length),
                tokenBreakdown(
                    "记忆/状态/感知",
                    globalWorldBookSection.length + roleWorldBookSection.length + userProfileSection.length + presenceSection.length + timelineSection.length + memorySection.length + lexiconSection.length,
                ),
                tokenBreakdown("工具/MCP说明", 0),
                tokenBreakdown("用户上下文", userPrompt.length),
                tokenBreakdown("助手上下文", 0),
                tokenBreakdown("其他", 0),
            )
            val estimatedInputTokens = breakdown.sumOf { item -> item.estimatedTokens }
            val promptMillis = elapsedMillis(promptStartedAt)
            val modelStartedAt = System.nanoTime()
            val reply = openAiCompatible(
                connection = connection,
                system = systemPrompt,
                user = userPrompt,
                temperature = temperature,
                maxTokens = maxTokens,
                streamResponse = streamResponse,
                readTimeoutMillis = readTimeoutMillis,
                onStreamText = onStreamText,
            )
            val modelMillis = elapsedMillis(modelStartedAt)
            val totalMillis = elapsedMillis(totalStartedAt)
            LuluRepositories.performance.recordGeneration(
                source = source,
                title = attemptedModel?.let { "$title · $it" } ?: title,
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
                title = attemptedModel?.let { "$title · $it" } ?: title,
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
        streamResponse: Boolean,
        readTimeoutMillis: Int,
        onStreamText: ((String) -> Unit)?,
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
        val url = "${connection.baseUrl}/chat/completions"
        val headers = mapOf("Authorization" to "Bearer ${connection.apiKey}")
        if (streamResponse) {
            body.put("stream", true)
            try {
                return requestPostStreamingReply(
                    url = url,
                    headers = headers,
                    body = body,
                    readTimeoutMillis = readTimeoutMillis,
                    onStreamText = onStreamText,
                )
            } catch (error: ModelHttpException) {
                // A few OpenAI-compatible relays reject the stream flag even though their normal
                // chat endpoint works. Only retry those immediate protocol rejections; never turn a
                // timeout, rate limit or server failure into a second expensive generation.
                if (error.status !in setOf(400, 422)) throw error
                body.remove("stream")
            }
        }
        val json = requestPostJson(
            url = url,
            headers = headers,
            body = body,
            readTimeoutMillis = readTimeoutMillis,
        )
        return modelReplyFromJson(json).also { reply -> onStreamText?.invoke(reply.text) }
    }

    private fun modelReplyFromJson(json: JSONObject): ModelReply {
        val text = extractModelText(json)
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

    private fun extractModelText(json: JSONObject): String {
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        return sequenceOf(
            modelTextValue(message?.opt("content")),
            modelTextValue(choice?.opt("text")),
            modelTextValue(json.opt("output_text")),
            modelTextValue(json.opt("output")),
            modelTextValue(message?.opt("reasoning_content")),
        ).firstOrNull(String::isNotBlank).orEmpty().trim()
    }

    private fun modelTextValue(value: Any?): String = when (value) {
        is String -> value
        is JSONObject -> sequenceOf("text", "content", "output_text")
            .map { key -> modelTextValue(value.opt(key)) }
            .firstOrNull(String::isNotBlank)
            .orEmpty()
        is JSONArray -> (0 until value.length())
            .joinToString("") { index -> modelTextValue(value.opt(index)) }
        else -> ""
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
        readTimeoutMillis: Int = DEFAULT_MODEL_READ_TIMEOUT_MILLIS,
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = readTimeoutMillis.coerceIn(30_000, 300_000)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            readJsonResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestPostStreamingReply(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
        readTimeoutMillis: Int,
        onStreamText: ((String) -> Unit)?,
    ): ModelReply {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = readTimeoutMillis.coerceIn(30_000, 300_000)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "text/event-stream, application/json")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }

            val status = connection.responseCode
            if (status !in 200..299) {
                val raw = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw modelHttpException(status, raw)
            }

            val content = StringBuilder()
            val reasoning = StringBuilder()
            val plainResponse = StringBuilder()
            var inputTokens = 0
            var outputTokens = 0
            var cachedTokens = 0
            var lastCallbackLength = 0
            var lastCallbackNanos = System.nanoTime()

            fun publishVisibleProgress(force: Boolean = false) {
                if (onStreamText == null || content.isEmpty()) return
                val now = System.nanoTime()
                val enoughText = content.length - lastCallbackLength >= 48
                val enoughTime = now - lastCallbackNanos >= 120_000_000L
                if (force || enoughText || enoughTime) {
                    onStreamText.invoke(content.toString())
                    lastCallbackLength = content.length
                    lastCallbackNanos = now
                }
            }

            fun consumeEvent(rawEvent: String): Boolean {
                val payload = rawEvent.trim()
                if (payload.isBlank() || payload == "[DONE]") return true
                val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: return false
                val choice = chunk.optJSONArray("choices")?.optJSONObject(0)
                val delta = choice?.optJSONObject("delta")
                if (delta != null) {
                    content.append(modelTextValue(delta.opt("content")))
                    reasoning.append(modelTextValue(delta.opt("reasoning_content")))
                } else if (content.isEmpty()) {
                    val message = choice?.optJSONObject("message")
                    content.append(modelTextValue(message?.opt("content")))
                    content.append(modelTextValue(choice?.opt("text")))
                    reasoning.append(modelTextValue(message?.opt("reasoning_content")))
                }
                chunk.optJSONObject("usage")?.let { usage ->
                    inputTokens = usage.optInt("prompt_tokens", inputTokens)
                    outputTokens = usage.optInt("completion_tokens", outputTokens)
                    cachedTokens = usage.optJSONObject("prompt_tokens_details")
                        ?.optInt("cached_tokens", cachedTokens)
                        ?: cachedTokens
                }
                publishVisibleProgress()
                return true
            }

            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val eventData = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isBlank() -> {
                            if (eventData.isNotEmpty()) {
                                consumeEvent(eventData.toString())
                                eventData.setLength(0)
                            }
                        }
                        line.startsWith("data:") -> {
                            val payload = line.removePrefix("data:").trimStart()
                            if (eventData.isEmpty()) {
                                if (!consumeEvent(payload)) eventData.append(payload)
                            } else {
                                eventData.append('\n').append(payload)
                                if (consumeEvent(eventData.toString())) eventData.setLength(0)
                            }
                        }
                        line.startsWith(":") || line.startsWith("event:") ||
                            line.startsWith("id:") || line.startsWith("retry:") -> Unit
                        else -> plainResponse.appendLine(line)
                    }
                }
                if (eventData.isNotEmpty()) consumeEvent(eventData.toString())
            }

            if (content.isEmpty() && reasoning.isEmpty() && plainResponse.isNotBlank()) {
                return modelReplyFromJson(JSONObject(plainResponse.toString())).also { reply ->
                    onStreamText?.invoke(reply.text)
                }
            }
            val text = content.toString().trim().ifBlank { reasoning.toString().trim() }
            check(text.isNotBlank()) { "模型流式响应没有返回可读取的内容" }
            publishVisibleProgress(force = true)
            ModelReply(
                text = text,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cachedTokens = cachedTokens,
            )
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
            throw modelHttpException(status, raw)
        }
        check(raw.isNotBlank()) { "接口返回了空内容" }
        return if (raw.trimStart().startsWith("[")) {
            JSONObject().put("data", JSONArray(raw))
        } else {
            JSONObject(raw)
        }
    }

    private fun modelHttpException(status: Int, raw: String): ModelHttpException {
        val message = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }
            .getOrNull()
            .orEmpty()
            .ifBlank { raw.take(500) }
        return ModelHttpException(status, "模型请求失败（$status）：$message")
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

private class ModelHttpException(val status: Int, message: String) : IllegalStateException(message)

private const val DEFAULT_MODEL_READ_TIMEOUT_MILLIS = 90_000
private const val CHAT_FIXED_CONTEXT_TOKEN_LIMIT = 18_000

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
