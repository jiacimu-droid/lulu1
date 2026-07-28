package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.core.DurationSummary
import com.jiacimu.lulu.core.PerformanceRepository
import com.jiacimu.lulu.core.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ApiUsageSource(val label: String) {
    CHAT("聊天"),
    PHONE("电话"),
    GAME("游戏"),
    OTHER("其他");

    companion object {
        fun fromLabel(value: String): ApiUsageSource = when {
            value.contains("聊天") -> CHAT
            value.contains("电话") || value.contains("通话") -> PHONE
            value.contains("游戏") || value.contains("跑团") || value.contains("海龟汤") -> GAME
            else -> OTHER
        }
    }
}

data class ApiUsageRecord(
    val id: String = UUID.randomUUID().toString(),
    val source: ApiUsageSource,
    val title: String,
    val model: String,
    val provider: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    val estimated: Boolean,
)

data class ApiUsageSummary(
    val source: ApiUsageSource,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    val callCount: Int,
) {
    val cacheRate: Float
        get() = if (promptTokens > 0L) cachedTokens.toFloat() / promptTokens.toFloat() else 0f
}

data class TokenBreakdownItem(
    val label: String,
    val chars: Int,
    val estimatedTokens: Int,
)

data class TokenConsoleRecord(
    val id: String = UUID.randomUUID().toString(),
    val source: ApiUsageSource,
    val title: String,
    val model: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val reportedInputTokens: Long,
    val reportedOutputTokens: Long,
    val effectiveInputTokens: Long,
    val effectiveOutputTokens: Long,
    val cachedTokens: Long,
    val breakdown: List<TokenBreakdownItem>,
)

data class PerformanceTimingRecord(
    val id: String = UUID.randomUUID().toString(),
    val stage: String,
    val durationMillis: Long,
    val detail: String,
    val recordedAtMillis: Long = System.currentTimeMillis(),
)

data class PerformanceErrorRecord(
    val id: String = UUID.randomUUID().toString(),
    val source: String,
    val title: String,
    val message: String,
    val requestUrl: String? = null,
    val durationMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

fun List<ApiUsageRecord>.summarizeApiUsage(): List<ApiUsageSummary> =
    groupBy(ApiUsageRecord::source)
        .map { (source, records) ->
            ApiUsageSummary(
                source = source,
                promptTokens = records.sumOf(ApiUsageRecord::promptTokens),
                completionTokens = records.sumOf(ApiUsageRecord::completionTokens),
                cachedTokens = records.sumOf(ApiUsageRecord::cachedTokens),
                callCount = records.size,
            )
        }
        .sortedBy { it.source.ordinal }

class LocalPerformanceRepository(context: Context) : PerformanceRepository {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val state = MutableStateFlow(load())

    val errorRecords: Flow<List<PerformanceErrorRecord>> = state
        .map(PerformanceState::errors)
        .distinctUntilChanged()
    val usageRecords: Flow<List<ApiUsageRecord>> = state
        .map(PerformanceState::usageRecords)
        .distinctUntilChanged()
    val consoleRecords: Flow<List<TokenConsoleRecord>> = state
        .map(PerformanceState::consoleRecords)
        .distinctUntilChanged()
    val timingRecords: Flow<List<PerformanceTimingRecord>> = state
        .map(PerformanceState::timings)
        .distinctUntilChanged()

    override fun observeErrors(): Flow<List<String>> = errorRecords.map { records ->
        records.map { record ->
            buildString {
                append(record.source)
                if (record.title.isNotBlank()) append(" · ${record.title}")
                append("：${record.message}")
            }
        }
    }

    override fun observeTokenUsage(): Flow<TokenUsage> = state.map { snapshot ->
        TokenUsage(
            input = snapshot.usageRecords.sumOf(ApiUsageRecord::promptTokens),
            output = snapshot.usageRecords.sumOf(ApiUsageRecord::completionTokens),
            cached = snapshot.usageRecords.sumOf(ApiUsageRecord::cachedTokens),
            model = snapshot.usageRecords.firstOrNull()?.model,
        )
    }.distinctUntilChanged()

    override fun observeDurations(): Flow<DurationSummary> = state
        .map(PerformanceState::durations)
        .distinctUntilChanged()

    override suspend fun clearErrors() {
        update { it.copy(errors = emptyList()) }
    }

    override suspend fun clearCache() {
        update { it.copy(usageRecords = emptyList()) }
    }

    suspend fun clearConsole() {
        update { it.copy(consoleRecords = emptyList()) }
    }

    suspend fun clearTimings() {
        update { it.copy(timings = emptyList()) }
    }

    suspend fun recordError(message: String) {
        recordError(
            source = "系统",
            title = "",
            message = message,
        )
    }

    suspend fun recordError(
        source: String,
        title: String,
        message: String,
        requestUrl: String? = null,
        durationMillis: Long? = null,
    ) {
        if (message.isBlank()) return
        val record = PerformanceErrorRecord(
            source = source.ifBlank { "系统" },
            title = title,
            message = message,
            requestUrl = requestUrl,
            durationMillis = durationMillis?.coerceAtLeast(0L),
        )
        update { current ->
            current.copy(errors = (listOf(record) + current.errors).take(ERROR_RECORD_LIMIT))
        }
    }

    suspend fun recordGeneration(
        source: String,
        title: String,
        model: String,
        provider: String,
        reportedInputTokens: Int,
        reportedOutputTokens: Int,
        cachedTokens: Int,
        estimatedInputTokens: Int,
        estimatedOutputTokens: Int,
        breakdown: List<TokenBreakdownItem>,
        promptMillis: Long,
        modelMillis: Long,
        totalMillis: Long,
    ) {
        val effectiveInput = reportedInputTokens.takeIf { it > 0 } ?: estimatedInputTokens.coerceAtLeast(0)
        val effectiveOutput = reportedOutputTokens.takeIf { it > 0 } ?: estimatedOutputTokens.coerceAtLeast(0)
        val usageSource = ApiUsageSource.fromLabel(source)
        val estimated = reportedInputTokens <= 0 || reportedOutputTokens <= 0
        val now = System.currentTimeMillis()
        val usage = ApiUsageRecord(
            source = usageSource,
            title = title,
            model = model,
            provider = provider,
            createdAtMillis = now,
            promptTokens = effectiveInput.toLong(),
            completionTokens = effectiveOutput.toLong(),
            cachedTokens = cachedTokens.coerceAtLeast(0).toLong(),
            estimated = estimated,
        )
        val console = TokenConsoleRecord(
            source = usageSource,
            title = title,
            model = model,
            createdAtMillis = now,
            reportedInputTokens = reportedInputTokens.coerceAtLeast(0).toLong(),
            reportedOutputTokens = reportedOutputTokens.coerceAtLeast(0).toLong(),
            effectiveInputTokens = effectiveInput.toLong(),
            effectiveOutputTokens = effectiveOutput.toLong(),
            cachedTokens = cachedTokens.coerceAtLeast(0).toLong(),
            breakdown = breakdown,
        )
        val timings = listOf(
            PerformanceTimingRecord(stage = "Prompt", durationMillis = promptMillis.coerceAtLeast(0L), detail = title),
            PerformanceTimingRecord(stage = "模型请求", durationMillis = modelMillis.coerceAtLeast(0L), detail = model),
            PerformanceTimingRecord(stage = "总耗时", durationMillis = totalMillis.coerceAtLeast(0L), detail = "$source · $title"),
        )
        update { current ->
            current.copy(
                usageRecords = (listOf(usage) + current.usageRecords).take(API_USAGE_RECORD_LIMIT),
                consoleRecords = (listOf(console) + current.consoleRecords).take(CONSOLE_RECORD_LIMIT),
                timings = (timings + current.timings).take(TIMING_RECORD_LIMIT),
            )
        }
    }

    suspend fun addTokenUsage(input: Int, output: Int, cached: Int = 0) {
        recordGeneration(
            source = "其他",
            title = "未标记调用",
            model = "",
            provider = "",
            reportedInputTokens = input,
            reportedOutputTokens = output,
            cachedTokens = cached,
            estimatedInputTokens = input,
            estimatedOutputTokens = output,
            breakdown = emptyList(),
            promptMillis = 0L,
            modelMillis = 0L,
            totalMillis = 0L,
        )
    }

    fun updateDurations(summary: DurationSummary) {
        update {
            it.copy(
                durations = summary.copy(
                    studyMinutes = summary.studyMinutes.coerceAtLeast(0),
                    chatMinutes = summary.chatMinutes.coerceAtLeast(0),
                    callMinutes = summary.callMinutes.coerceAtLeast(0),
                ),
            )
        }
    }

    private fun update(transform: (PerformanceState) -> PerformanceState) {
        synchronized(lock) {
            val next = transform(state.value)
            state.value = next
            prefs.edit().putString(KEY_STATE, encode(next).toString()).apply()
        }
    }

    private fun load(): PerformanceState {
        val raw = prefs.getString(KEY_STATE, null)
        return if (raw.isNullOrBlank()) PerformanceState() else runCatching {
            decode(JSONObject(raw))
        }.getOrDefault(PerformanceState())
    }

    private fun encode(value: PerformanceState): JSONObject = JSONObject()
        .put("errors", JSONArray().apply { value.errors.forEach { put(encodeError(it)) } })
        .put("usageRecords", JSONArray().apply { value.usageRecords.forEach { put(encodeUsage(it)) } })
        .put("consoleRecords", JSONArray().apply { value.consoleRecords.forEach { put(encodeConsole(it)) } })
        .put("timings", JSONArray().apply { value.timings.forEach { put(encodeTiming(it)) } })
        .put(
            "durations",
            JSONObject()
                .put("studyMinutes", value.durations.studyMinutes)
                .put("chatMinutes", value.durations.chatMinutes)
                .put("callMinutes", value.durations.callMinutes),
        )

    private fun decode(root: JSONObject): PerformanceState = PerformanceState(
        errors = root.optJSONArray("errors").decodeObjects(::decodeError).take(ERROR_RECORD_LIMIT),
        usageRecords = root.optJSONArray("usageRecords").decodeObjects(::decodeUsage).take(API_USAGE_RECORD_LIMIT),
        consoleRecords = root.optJSONArray("consoleRecords").decodeObjects(::decodeConsole).take(CONSOLE_RECORD_LIMIT),
        timings = root.optJSONArray("timings").decodeObjects(::decodeTiming).take(TIMING_RECORD_LIMIT),
        durations = root.optJSONObject("durations")?.let { item ->
            DurationSummary(
                studyMinutes = item.optInt("studyMinutes").coerceAtLeast(0),
                chatMinutes = item.optInt("chatMinutes").coerceAtLeast(0),
                callMinutes = item.optInt("callMinutes").coerceAtLeast(0),
            )
        } ?: DurationSummary(0, 0, 0),
    )

    private fun encodeError(value: PerformanceErrorRecord): JSONObject = JSONObject()
        .put("id", value.id)
        .put("source", value.source)
        .put("title", value.title)
        .put("message", value.message)
        .put("requestUrl", value.requestUrl ?: JSONObject.NULL)
        .put("durationMillis", value.durationMillis ?: JSONObject.NULL)
        .put("createdAtMillis", value.createdAtMillis)

    private fun decodeError(item: JSONObject): PerformanceErrorRecord = PerformanceErrorRecord(
        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
        source = item.optString("source").ifBlank { "系统" },
        title = item.optString("title"),
        message = item.optString("message"),
        requestUrl = item.nullableString("requestUrl"),
        durationMillis = item.nullableLong("durationMillis"),
        createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis()),
    )

    private fun encodeUsage(value: ApiUsageRecord): JSONObject = JSONObject()
        .put("id", value.id)
        .put("source", value.source.name)
        .put("title", value.title)
        .put("model", value.model)
        .put("provider", value.provider)
        .put("createdAtMillis", value.createdAtMillis)
        .put("promptTokens", value.promptTokens)
        .put("completionTokens", value.completionTokens)
        .put("cachedTokens", value.cachedTokens)
        .put("estimated", value.estimated)

    private fun decodeUsage(item: JSONObject): ApiUsageRecord = ApiUsageRecord(
        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
        source = runCatching { ApiUsageSource.valueOf(item.optString("source")) }.getOrDefault(ApiUsageSource.OTHER),
        title = item.optString("title"),
        model = item.optString("model"),
        provider = item.optString("provider"),
        createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis()),
        promptTokens = item.optLong("promptTokens").coerceAtLeast(0L),
        completionTokens = item.optLong("completionTokens").coerceAtLeast(0L),
        cachedTokens = item.optLong("cachedTokens").coerceAtLeast(0L),
        estimated = item.optBoolean("estimated"),
    )

    private fun encodeConsole(value: TokenConsoleRecord): JSONObject = JSONObject()
        .put("id", value.id)
        .put("source", value.source.name)
        .put("title", value.title)
        .put("model", value.model)
        .put("createdAtMillis", value.createdAtMillis)
        .put("reportedInputTokens", value.reportedInputTokens)
        .put("reportedOutputTokens", value.reportedOutputTokens)
        .put("effectiveInputTokens", value.effectiveInputTokens)
        .put("effectiveOutputTokens", value.effectiveOutputTokens)
        .put("cachedTokens", value.cachedTokens)
        .put(
            "breakdown",
            JSONArray().apply {
                value.breakdown.forEach { part ->
                    put(
                        JSONObject()
                            .put("label", part.label)
                            .put("chars", part.chars)
                            .put("estimatedTokens", part.estimatedTokens),
                    )
                }
            },
        )

    private fun decodeConsole(item: JSONObject): TokenConsoleRecord = TokenConsoleRecord(
        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
        source = runCatching { ApiUsageSource.valueOf(item.optString("source")) }.getOrDefault(ApiUsageSource.OTHER),
        title = item.optString("title"),
        model = item.optString("model"),
        createdAtMillis = item.optLong("createdAtMillis", System.currentTimeMillis()),
        reportedInputTokens = item.optLong("reportedInputTokens").coerceAtLeast(0L),
        reportedOutputTokens = item.optLong("reportedOutputTokens").coerceAtLeast(0L),
        effectiveInputTokens = item.optLong("effectiveInputTokens").coerceAtLeast(0L),
        effectiveOutputTokens = item.optLong("effectiveOutputTokens").coerceAtLeast(0L),
        cachedTokens = item.optLong("cachedTokens").coerceAtLeast(0L),
        breakdown = item.optJSONArray("breakdown").decodeObjects { part ->
            TokenBreakdownItem(
                label = part.optString("label"),
                chars = part.optInt("chars").coerceAtLeast(0),
                estimatedTokens = part.optInt("estimatedTokens").coerceAtLeast(0),
            )
        },
    )

    private fun encodeTiming(value: PerformanceTimingRecord): JSONObject = JSONObject()
        .put("id", value.id)
        .put("stage", value.stage)
        .put("durationMillis", value.durationMillis)
        .put("detail", value.detail)
        .put("recordedAtMillis", value.recordedAtMillis)

    private fun decodeTiming(item: JSONObject): PerformanceTimingRecord = PerformanceTimingRecord(
        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
        stage = item.optString("stage"),
        durationMillis = item.optLong("durationMillis").coerceAtLeast(0L),
        detail = item.optString("detail"),
        recordedAtMillis = item.optLong("recordedAtMillis", System.currentTimeMillis()),
    )

    private companion object {
        const val PREFS_NAME = "lulu_performance_monitor"
        const val KEY_STATE = "state_v1"
        const val ERROR_RECORD_LIMIT = 200
        const val API_USAGE_RECORD_LIMIT = 500
        const val CONSOLE_RECORD_LIMIT = 200
        const val TIMING_RECORD_LIMIT = 500
    }
}

private data class PerformanceState(
    val errors: List<PerformanceErrorRecord> = emptyList(),
    val usageRecords: List<ApiUsageRecord> = emptyList(),
    val consoleRecords: List<TokenConsoleRecord> = emptyList(),
    val timings: List<PerformanceTimingRecord> = emptyList(),
    val durations: DurationSummary = DurationSummary(0, 0, 0),
)

private fun <T> JSONArray?.decodeObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            runCatching { transform(item) }.getOrNull()?.let(::add)
        }
    }
}

private fun JSONObject.nullableString(key: String): String? =
    takeUnless { isNull(key) }?.optString(key)?.takeIf(String::isNotBlank)

private fun JSONObject.nullableLong(key: String): Long? =
    takeUnless { isNull(key) }?.optLong(key)
