package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.core.MemoryPolicy
import com.jiacimu.lulu.core.MemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Persistent memory store with retry-safe extraction checkpoints.
 * A batch is marked processed only after a valid model result has been parsed and saved.
 */
class LocalMemoryRepository : MemoryRepository {
    private val state = MutableStateFlow(MemoryStoreState())
    private val debugFlow = MutableStateFlow(MemoryDebugState())
    private val extractionLocks = mutableMapOf<String, Mutex>()
    private var prefs: android.content.SharedPreferences? = null
    private var advancedPrefs: android.content.SharedPreferences? = null
    private val lock = Any()

    val debugState: StateFlow<MemoryDebugState> = debugFlow.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            advancedPrefs = context.applicationContext.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
            state.value = decode(prefs?.getString(KEY_STATE, null))
            refreshDebug("记忆存档已载入")
        }
    }

    override fun observeMemories(characterId: String): Flow<List<MemoryEntry>> = state.map { snapshot ->
        snapshot.entries
            .filter { entry -> entry.characterId == characterId }
            .sortedWith(
                compareByDescending<MemoryEntry> { entry -> entry.pinned }
                    .thenByDescending { entry -> entry.occurredAt ?: entry.createdAt },
            )
    }

    override fun observePolicy(characterId: String): Flow<MemoryPolicy> = state.map { snapshot ->
        snapshot.resolvedPolicy(characterId)
    }

    override suspend fun updatePolicy(characterId: String, policy: MemoryPolicy) {
        require(characterId.isNotBlank()) { "角色不能为空" }
        require(policy.excludedRecentMessages >= 0) { "最近消息排除数量不能为负数" }
        require(policy.readableThreshold > 0) { "总结阈值必须大于 0" }
        mutate { current -> current.copy(globalPolicy = policy) }
        refreshDebug("已保存全部角色共用的记忆规则", characterId)
    }

    override suspend fun summarizeNow(characterId: String) {
        require(characterId.isNotBlank()) { "角色不能为空" }
        val extractionLock = synchronized(extractionLocks) {
            extractionLocks.getOrPut(characterId) { Mutex() }
        }
        extractionLock.withLock {
            summarizeContinuously(characterId)
        }
    }

    private suspend fun summarizeContinuously(characterId: String) {
        val policy = state.value.resolvedPolicy(characterId)
        val threshold = policy.readableThreshold.coerceAtLeast(1)
        val readable = readableMessages(characterId, policy)
        var processedThisRun = 0
        var extractedThisRun = 0

        while (true) {
            val processed = state.value.processedMessageIds[characterId].orEmpty()
            val pending = readable.filterNot { message -> message.id in processed }
            val batch = pending.take(threshold)

            if (batch.size < threshold) {
                if (processedThisRun > 0) maintain(characterId, silent = true)
                refreshDebug(
                    message = if (processedThisRun == 0) {
                        "可整理消息 ${batch.size}/$threshold，尚未达到阈值"
                    } else {
                        "连续整理完成：本次处理 $processedThisRun 条消息，新增 $extractedThisRun 条记忆；剩余 ${batch.size}/$threshold 条等待下一批"
                    },
                    characterId = characterId,
                    readableCount = readable.size,
                    pendingCount = pending.size,
                    batchCount = batch.size,
                    extracting = false,
                    lastExtractedCount = extractedThisRun,
                )
                return
            }

            val batchStart = processed.size + 1
            val batchEnd = processed.size + batch.size
            refreshDebug(
                message = "正在整理第 $batchStart-$batchEnd 条可读消息",
                characterId = characterId,
                readableCount = readable.size,
                pendingCount = pending.size,
                batchCount = batch.size,
                extracting = true,
                lastExtractedCount = extractedThisRun,
            )

            val batchIds = batch.mapTo(mutableSetOf()) { message -> message.id }
            val facts = batch.joinToString("\n") { message ->
                val sender = if (message.sender == LuluChatMessage.Sender.User) "用户" else "角色"
                "[事件ID=${message.id}] [${message.createdAt}] $sender：${message.content}"
            }
            val result = LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = facts,
                instruction = """
                    从给定真实对话与原始事件中提取值得长期保留的记忆。只返回 JSON 数组，不要代码块。
                    每项格式：
                    {"kind":"Fact|Emotion|Timeline","content":"包含人物、事件、原因、结果与必要语境的完整中文记忆","sourceEventIds":["直接支持该记忆的事件ID"],"occurredAt":"ISO-8601时间或空字符串","strength":1到10}
                    规则：
                    1. 不编造未发生事实，必须结合这一整批上下文理解语义，不能因为某一句出现“不是、其实、应该、喜欢、不要”等词就机械判定为纠正、偏好或边界。
                    2. Fact 只保存上下文能够确认的长期稳定身份事实、持续计划、明确偏好与边界。口头反驳、临时观点、针对当下情境的一句话、语气性否定都不是长期事实。
                    3. 如果用户是在纠正角色，必须从前后文确认“先前具体误解是什么、用户实际澄清的稳定事实是什么”，只保存澄清后的事实；无法确认就不要保存。
                    4. Emotion 只保存明确情绪、触发原因和发生时间；不要把普通语气猜成情绪。
                    5. Timeline 只保存有明确时间或里程碑意义的重要经历，例如开始备考、真正完成了一次有互动的共同活动；普通聊天、单纯打开计时器、无交流的番茄钟不要放入。
                    6. 角色要履行的约定、提醒、责任和监督属于辞海约定，不要重复放进记忆。
                    7. 输入中的 [私聊]、[电话]、[群聊]、[朋友圈]、[收藏]、[此刻] 等方括号内容只是原始事件来源标签，不是用户说的话，也不是需要记住的提示词。
                    8. 每条记忆必须给出1—6个直接支持它的 sourceEventIds，只能复制输入中真实存在的事件ID；不要把整批ID都塞进去。
                    9. 记忆必须保留将来理解同义改写所需的人物、对象、原因、结果和必要语境，避免只写模糊关键词；但不要复制无关寒暄。
                    10. 日常寒暄、同义重复、已经存在的总结不要重复写入；没有值得保存的内容时返回 []。
                """.trimIndent(),
                source = "记忆",
                title = "连续记忆提取",
                temperature = 0.2,
                maxTokens = 1800,
                connectionOverride = extractionConnection(),
            )

            if (result.isFailure) {
                val error = result.exceptionOrNull()
                refreshDebug(
                    message = "记忆提取失败，当前批次未跳过：${error?.message ?: "未知错误"}",
                    characterId = characterId,
                    readableCount = readable.size,
                    pendingCount = pending.size,
                    batchCount = batch.size,
                    extracting = false,
                    lastError = error?.message,
                    lastExtractedCount = extractedThisRun,
                )
                return
            }

            val reply = result.getOrThrow()
            val parsed = runCatching { parseMemoryArray(reply.text, characterId, batchIds) }
            if (parsed.isFailure) {
                val error = parsed.exceptionOrNull()
                refreshDebug(
                    message = "模型记忆格式无效，当前批次未跳过：${error?.message ?: "无法解析"}",
                    characterId = characterId,
                    readableCount = readable.size,
                    pendingCount = pending.size,
                    batchCount = batch.size,
                    extracting = false,
                    lastError = error?.message,
                    lastExtractedCount = extractedThisRun,
                )
                return
            }

            val provenance = "timeline-batch:${batchIds.joinToString("|")}"
            val snapshot = state.value
            val existingKeys = snapshot.entries
                .filter { entry -> entry.characterId == characterId }
                .mapTo(mutableSetOf()) { entry -> entry.dedupeKey() }
            val deletedKeys = snapshot.deletedMemoryKeys
            val unique = parsed.getOrThrow()
                .filter { entry -> entry.scopedMemoryKey() !in deletedKeys }
                .filter { entry -> existingKeys.add(entry.dedupeKey()) }
                .map { entry ->
                    if (entry.source.startsWith("timeline-events:")) entry else entry.copy(source = provenance)
                }

            mutate { current ->
                val currentProcessed = current.processedMessageIds[characterId].orEmpty()
                current.copy(
                    entries = current.entries + unique,
                    processedMessageIds = current.processedMessageIds +
                        (characterId to (currentProcessed + batchIds)),
                )
            }

            processedThisRun += batch.size
            extractedThisRun += unique.size
            val remaining = (pending.size - batch.size).coerceAtLeast(0)
            refreshDebug(
                message = if (remaining >= threshold) {
                    "第 $batchStart-$batchEnd 条整理完成，新增 ${unique.size} 条记忆；继续下一批"
                } else {
                    "本批读取 ${batch.size} 条消息，新增 ${unique.size} 条记忆"
                },
                characterId = characterId,
                readableCount = readable.size,
                pendingCount = remaining,
                batchCount = batch.size,
                extracting = remaining >= threshold,
                lastExtractedCount = extractedThisRun,
            )
        }
    }

    private fun extractionConnection(): ModelConnection? {
        val settings = advancedPrefs ?: return null
        val baseUrl = settings.getString("memory_extract_url", "").orEmpty().trim().trimEnd('/')
        val apiKey = settings.getString("memory_extract_key", "").orEmpty().trim()
        val model = settings.getString("memory_extract_model", "").orEmpty().trim()
        return if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) null else ModelConnection(baseUrl, apiKey, model)
    }

    fun snapshot(characterId: String): List<MemoryEntry> = state.value.entries
        .filter { entry ->
            entry.characterId == characterId &&
                entry.canRecallProactively &&
                DigitalLifeProfileStore.allowsTimestamp(characterId, entry.occurredAt ?: entry.createdAt)
        }
        .sortedWith(
            compareByDescending<MemoryEntry> { entry -> entry.pinned }
                .thenByDescending { entry -> entry.strength }
                .thenByDescending { entry -> entry.occurredAt ?: entry.createdAt },
        )

    /** Explicit editor save may intentionally restore a previously deleted wording. */
    suspend fun save(entry: MemoryEntry) {
        saveInternal(entry, allowRestore = true)
    }

    /** Programmatic writes never resurrect a memory the user explicitly deleted. */
    suspend fun upsert(entry: MemoryEntry) {
        saveInternal(entry, allowRestore = false)
    }

    private fun saveInternal(entry: MemoryEntry, allowRestore: Boolean) {
        require(entry.characterId.isNotBlank()) { "角色不能为空" }
        require(entry.content.isNotBlank()) { "记忆内容不能为空" }
        val clean = entry.copy(
            content = entry.content.trim(),
            source = entry.source.trim().ifBlank { "手动" },
            strength = entry.strength.coerceIn(1, 10),
        )
        val scopedKey = clean.scopedMemoryKey()
        mutate { current ->
            if (!allowRestore && scopedKey in current.deletedMemoryKeys) return@mutate current
            val index = current.entries.indexOfFirst { item -> item.id == clean.id }
            val nextEntries = if (index < 0) {
                current.entries + clean
            } else {
                current.entries.toMutableList().apply { set(index, clean) }
            }
            current.copy(
                entries = nextEntries,
                deletedMemoryKeys = if (allowRestore) current.deletedMemoryKeys - scopedKey else current.deletedMemoryKeys,
            )
        }
        refreshDebug("记忆已保存", entry.characterId)
    }

    /** Delete only the concrete entry; internal cleanup callers use this form. */
    suspend fun delete(id: String) {
        val target = state.value.entries.firstOrNull { it.id == id }
        mutate { current ->
            current.copy(
                entries = current.entries.filterNot { entry -> entry.id == id },
                deletedMemoryKeys = target?.let { current.deletedMemoryKeys + it.scopedMemoryKey() }
                    ?: current.deletedMemoryKeys,
            )
        }
        refreshDebug("记忆已删除")
    }

    /**
     * User-facing delete: if the same semantic memory leaked into several roles, one deletion clears
     * every equivalent copy and tombstones each affected role so batch extraction cannot recreate it.
     */
    suspend fun deleteEverywhereEquivalent(id: String): Int {
        val target = state.value.entries.firstOrNull { entry -> entry.id == id } ?: return 0
        val targetKey = target.memoryIdentityKey()
        var removed = 0
        mutate { current ->
            val victims = current.entries.filter { entry -> entry.memoryIdentityKey() == targetKey }
            removed = victims.size
            current.copy(
                entries = current.entries.filterNot { entry -> entry.memoryIdentityKey() == targetKey },
                deletedMemoryKeys = current.deletedMemoryKeys + victims.map(MemoryEntry::scopedMemoryKey),
            )
        }
        refreshDebug(if (removed > 1) "已删除 $removed 个角色中的同内容记忆" else "记忆已删除")
        return removed
    }

    /** Conservative local maintenance: merge exact and very-high-similarity duplicates only. */
    suspend fun maintain(characterId: String): Int = maintain(characterId, silent = false)

    /** One user action maintains every role's memory library, including roles not currently open. */
    suspend fun maintainAll(): Int {
        val characterIds = state.value.entries.mapTo(linkedSetOf(), MemoryEntry::characterId)
        val removed = characterIds.sumOf { characterId -> maintain(characterId, silent = true) }
        refreshDebug(
            if (removed == 0) "全部角色记忆维护完成，没有发现可安全合并的重复项"
            else "全部角色记忆维护完成，合并了 $removed 条重复记忆",
        )
        return removed
    }

    private fun maintain(characterId: String, silent: Boolean): Int {
        var removed = 0
        mutate { current ->
            val target = current.entries
                .filter { it.characterId == characterId }
                .sortedWith(
                    compareByDescending<MemoryEntry> { it.pinned }
                        .thenByDescending { it.strength }
                        .thenByDescending { it.occurredAt ?: it.createdAt },
                )
            val kept = mutableListOf<MemoryEntry>()
            target.forEach { candidate ->
                val index = kept.indexOfFirst { existing -> memoriesEquivalentForMaintenance(existing, candidate) }
                if (index < 0) {
                    kept += candidate
                } else {
                    kept[index] = mergeMemory(kept[index], candidate)
                    removed += 1
                }
            }
            if (removed == 0) current else current.copy(
                entries = current.entries.filterNot { it.characterId == characterId } + kept,
            )
        }
        if (!silent) refreshDebug(if (removed == 0) "记忆维护完成，没有发现可安全合并的重复项" else "记忆维护完成，合并了 $removed 条重复记忆", characterId)
        return removed
    }

    suspend fun deleteDerivedFromEvent(eventId: String) {
        if (eventId.isBlank()) return
        mutate { current ->
            current.copy(
                entries = current.entries.filterNot { entry ->
                    val sourceIds = when {
                        entry.source.startsWith("timeline-events:") -> entry.source.removePrefix("timeline-events:").split('|')
                        entry.source.startsWith("timeline-batch:") -> entry.source.removePrefix("timeline-batch:").split('|')
                        else -> emptyList()
                    }
                    eventId in sourceIds
                },
                processedMessageIds = current.processedMessageIds.mapValues { (_, ids) -> ids - eventId },
            )
        }
        refreshDebug("已撤销由删除内容产生的派生记忆")
    }

    suspend fun togglePinned(id: String) {
        mutate { current ->
            current.copy(
                entries = current.entries.map { entry ->
                    if (entry.id == id) entry.copy(pinned = !entry.pinned) else entry
                },
            )
        }
    }

    suspend fun toggleRecall(id: String) {
        mutate { current ->
            current.copy(
                entries = current.entries.map { entry ->
                    if (entry.id == id) {
                        entry.copy(canRecallProactively = !entry.canRecallProactively)
                    } else {
                        entry
                    }
                },
            )
        }
    }

    suspend fun replaceAll(entries: List<MemoryEntry>) {
        mutate { current -> current.copy(entries = entries) }
    }

    fun pendingMessageCount(characterId: String): Int = pendingTimelineEvents(characterId).size

    fun pendingTimelineEvents(characterId: String): List<SharedTimelineEvent> {
        val policy = state.value.resolvedPolicy(characterId)
        val processed = state.value.processedMessageIds[characterId].orEmpty()
        return memoryEligibleTimelineEvents(characterId)
            .dropLast(policy.excludedRecentMessages.coerceAtLeast(0))
            .filterNot { event -> event.id in processed }
            .sortedBy(SharedTimelineEvent::occurredAt)
    }

    /**
     * Complete raw context for generation. Normally this is exactly protected-tail + one batch.
     * If extraction fails or is disabled long enough to create a larger unprocessed backlog, the
     * window expands to include that backlog instead of silently creating a memory hole.
     */
    fun contextTimelineEvents(characterId: String): List<SharedTimelineEvent> {
        val snapshot = state.value
        val policy = snapshot.resolvedPolicy(characterId)
        val processed = snapshot.processedMessageIds[characterId].orEmpty()
        val eligible = memoryEligibleTimelineEvents(characterId)
        val baselineIds = eligible
            .takeLast(policy.rawContextMessageCount.coerceAtLeast(0))
            .mapTo(linkedSetOf(), SharedTimelineEvent::id)
        val unresolvedIds = eligible
            .asSequence()
            .filterNot { event -> event.id in processed }
            .mapTo(linkedSetOf(), SharedTimelineEvent::id)
        val requiredIds = baselineIds + unresolvedIds
        return eligible.filter { event -> event.id in requiredIds }
    }

    /**
     * Raw timeline keeps private system receipts for audit/history, but those receipts are not
     * material for memory extraction. The underlying diary/reading/moment/favorite/etc. already has
     * its own typed raw event, so counting the receipt again would duplicate the same experience.
     */
    private fun memoryEligibleTimelineEvents(characterId: String): List<SharedTimelineEvent> =
        SharedExperienceTimeline.all(characterId).filterNot { event ->
            event.channel == "私聊" && event.speaker == "系统"
        }

    private fun readableMessages(
        characterId: String,
        policy: MemoryPolicy,
    ): List<LuluChatMessage> = memoryEligibleTimelineEvents(characterId)
        .map { event ->
            LuluChatMessage(
                id = event.id,
                conversationId = "shared-timeline",
                sender = if (event.speaker in setOf("主人", "用户", UserProfileContext.displayLabel())) LuluChatMessage.Sender.User else LuluChatMessage.Sender.Character,
                content = "[${event.channel}] ${event.speaker}：${event.content}",
                createdAt = event.occurredAt,
            )
        }
        .dropLast(policy.excludedRecentMessages.coerceAtLeast(0))

    private fun parseMemoryArray(raw: String, characterId: String, allowedSourceIds: Set<String>): List<MemoryEntry> {
        val clean = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val array = JSONArray(clean)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val kind = when (item.optString("kind").trim().lowercase()) {
                    "fact" -> MemoryKind.Fact
                    "emotion" -> MemoryKind.Emotion
                    "timeline" -> MemoryKind.Timeline
                    else -> continue
                }
                val content = item.optString("content").trim()
                if (content.isBlank()) continue
                val occurredAt = item.optString("occurredAt").trim()
                    .takeIf { value -> value.isNotBlank() }
                    ?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
                val sourceIds = buildList {
                    val sourceArray = item.optJSONArray("sourceEventIds") ?: JSONArray()
                    for (sourceIndex in 0 until sourceArray.length()) {
                        sourceArray.optString(sourceIndex)
                            .takeIf { it in allowedSourceIds }
                            ?.let(::add)
                    }
                }.distinct().take(6)
                add(
                    MemoryEntry(
                        id = UUID.randomUUID().toString(),
                        characterId = characterId,
                        content = content,
                        kind = kind,
                        source = if (sourceIds.isEmpty()) "聊天" else "timeline-events:${sourceIds.joinToString("|")}",
                        occurredAt = occurredAt,
                        createdAt = Instant.now(),
                        strength = item.optInt("strength", 5).coerceIn(1, 10),
                        pinned = false,
                        canRecallProactively = true,
                    ),
                )
            }
        }
    }

    private fun mutate(transform: (MemoryStoreState) -> MemoryStoreState) {
        synchronized(lock) {
            val next = transform(state.value)
            state.value = next
            prefs?.edit()?.putString(KEY_STATE, encode(next).toString())?.apply()
        }
    }

    private fun refreshDebug(
        message: String,
        characterId: String = debugFlow.value.characterId,
        readableCount: Int = debugFlow.value.readableCount,
        pendingCount: Int = debugFlow.value.pendingCount,
        batchCount: Int = debugFlow.value.batchCount,
        extracting: Boolean = false,
        lastError: String? = null,
        lastExtractedCount: Int = debugFlow.value.lastExtractedCount,
    ) {
        debugFlow.value = MemoryDebugState(
            characterId = characterId,
            message = message,
            readableCount = readableCount,
            pendingCount = pendingCount,
            batchCount = batchCount,
            extracting = extracting,
            lastError = lastError,
            lastExtractedCount = lastExtractedCount,
            updatedAt = Instant.now(),
        )
    }

    private fun encode(value: MemoryStoreState): JSONObject = JSONObject()
        .put(
            "entries",
            JSONArray().apply { value.entries.forEach { entry -> put(encodeEntry(entry)) } },
        )
        .put(
            "policies",
            JSONObject().apply {
                value.policies.forEach { (characterId, policy) ->
                    put(
                        characterId,
                        JSONObject()
                            .put("excludedRecentMessages", policy.excludedRecentMessages)
                            .put("readableThreshold", policy.readableThreshold)
                            .put("autoSummarize", policy.autoSummarize),
                    )
                }
            },
        )
        .put(
            "globalPolicy",
            value.globalPolicy?.let(::encodePolicy) ?: JSONObject.NULL,
        )
        .put(
            "processedMessageIds",
            JSONObject().apply {
                value.processedMessageIds.forEach { (characterId, ids) ->
                    put(characterId, JSONArray(ids.toList()))
                }
            },
        )
        .put("deletedMemoryKeys", JSONArray(value.deletedMemoryKeys.toList()))

    private fun decode(raw: String?): MemoryStoreState {
        if (raw.isNullOrBlank()) return MemoryStoreState()
        return runCatching {
            val root = JSONObject(raw)
            val entries = root.optJSONArray("entries").decodeObjects { item -> decodeEntry(item) }
            val policiesObject = root.optJSONObject("policies") ?: JSONObject()
            val policies = buildMap {
                val keys = policiesObject.keys()
                while (keys.hasNext()) {
                    val characterId = keys.next()
                    val item = policiesObject.optJSONObject(characterId) ?: continue
                    put(
                        characterId,
                        MemoryPolicy(
                            excludedRecentMessages = item.optInt("excludedRecentMessages", 10).coerceAtLeast(0),
                            readableThreshold = item.optInt("readableThreshold", 20).coerceAtLeast(1),
                            autoSummarize = item.optBoolean("autoSummarize", true),
                        ),
                    )
                }
            }
            val globalPolicy = root.optJSONObject("globalPolicy")?.let(::decodePolicy)
                // Old saves were per-role. Pick the safest configured policy once, then every role
                // observes the same migrated value from this point onward.
                ?: policies.values.maxByOrNull { policy -> policy.rawContextMessageCount }
            val processedObject = root.optJSONObject("processedMessageIds") ?: JSONObject()
            val processed = buildMap {
                val keys = processedObject.keys()
                while (keys.hasNext()) {
                    val characterId = keys.next()
                    val ids = processedObject.optJSONArray(characterId) ?: JSONArray()
                    put(
                        characterId,
                        buildSet {
                            for (index in 0 until ids.length()) {
                                ids.optString(index).takeIf(String::isNotBlank)?.let(::add)
                            }
                        },
                    )
                }
            }
            val deleted = buildSet {
                val array = root.optJSONArray("deletedMemoryKeys") ?: JSONArray()
                for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
            MemoryStoreState(
                entries = entries,
                policies = policies,
                globalPolicy = globalPolicy,
                processedMessageIds = processed,
                deletedMemoryKeys = deleted,
            )
        }.getOrDefault(MemoryStoreState())
    }

    private fun encodePolicy(policy: MemoryPolicy): JSONObject = JSONObject()
        .put("excludedRecentMessages", policy.excludedRecentMessages)
        .put("readableThreshold", policy.readableThreshold)
        .put("autoSummarize", policy.autoSummarize)

    private fun decodePolicy(item: JSONObject): MemoryPolicy = MemoryPolicy(
        excludedRecentMessages = item.optInt("excludedRecentMessages", 25).coerceAtLeast(0),
        readableThreshold = item.optInt("readableThreshold", 20).coerceAtLeast(1),
        autoSummarize = item.optBoolean("autoSummarize", true),
    )

    private fun encodeEntry(entry: MemoryEntry): JSONObject = JSONObject()
        .put("id", entry.id)
        .put("characterId", entry.characterId)
        .put("content", entry.content)
        .put("kind", entry.kind.name)
        .put("source", entry.source)
        .put("occurredAt", entry.occurredAt?.toString() ?: JSONObject.NULL)
        .put("createdAt", entry.createdAt.toString())
        .put("strength", entry.strength)
        .put("pinned", entry.pinned)
        .put("canRecallProactively", entry.canRecallProactively)

    private fun decodeEntry(item: JSONObject): MemoryEntry = MemoryEntry(
        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
        characterId = item.optString("characterId").ifBlank { "lulu" },
        content = item.optString("content"),
        kind = runCatching { MemoryKind.valueOf(item.optString("kind")) }.getOrDefault(MemoryKind.Fact),
        source = item.optString("source").ifBlank { "未知" },
        occurredAt = item.nullableString("occurredAt")?.let { value -> runCatching { Instant.parse(value) }.getOrNull() },
        createdAt = item.optString("createdAt").let { value -> runCatching { Instant.parse(value) }.getOrDefault(Instant.now()) },
        strength = item.optInt("strength", 5).coerceIn(1, 10),
        pinned = item.optBoolean("pinned"),
        canRecallProactively = item.optBoolean("canRecallProactively", true),
    )

    private companion object {
        const val PREFS_NAME = "lulu_memory_store"
        const val KEY_STATE = "state_v1"
    }
}

data class MemoryDebugState(
    val characterId: String = "lulu",
    val message: String = "尚未执行整理",
    val readableCount: Int = 0,
    val pendingCount: Int = 0,
    val batchCount: Int = 0,
    val extracting: Boolean = false,
    val lastError: String? = null,
    val lastExtractedCount: Int = 0,
    val updatedAt: Instant = Instant.now(),
)

private data class MemoryStoreState(
    val entries: List<MemoryEntry> = emptyList(),
    val policies: Map<String, MemoryPolicy> = emptyMap(),
    val globalPolicy: MemoryPolicy? = null,
    val processedMessageIds: Map<String, Set<String>> = emptyMap(),
    val deletedMemoryKeys: Set<String> = emptySet(),
)

private fun MemoryStoreState.resolvedPolicy(characterId: String): MemoryPolicy =
    globalPolicy ?: policies[characterId] ?: MemoryPolicy()

private fun MemoryEntry.dedupeKey(): String =
    kind.name + ":" + memoryIdentityKey()

private fun MemoryEntry.scopedMemoryKey(): String =
    characterId + ":" + memoryIdentityKey()

private fun MemoryEntry.memoryIdentityKey(): String = content
    .lowercase()
    .replace(Regex("^(用户明确表达过边界|用户纠正过一件事|用户明确表达过偏好)[：:]?"), "")
    .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
    .take(320)

private fun memoriesEquivalentForMaintenance(left: MemoryEntry, right: MemoryEntry): Boolean {
    if (left.kind != right.kind) return false
    val a = left.memoryIdentityKey()
    val b = right.memoryIdentityKey()
    if (a == b) return true
    if (a.length < 12 || b.length < 12) return false
    val shorter = minOf(a.length, b.length).toDouble()
    val longer = maxOf(a.length, b.length).toDouble()
    if (shorter / longer >= 0.78 && (a.contains(b) || b.contains(a))) return true
    return bigramJaccard(a, b) >= 0.88
}

private fun bigramJaccard(left: String, right: String): Double {
    fun grams(value: String): Set<String> = if (value.length < 2) setOf(value) else value.windowed(2).toSet()
    val a = grams(left)
    val b = grams(right)
    if (a.isEmpty() || b.isEmpty()) return 0.0
    return a.intersect(b).size.toDouble() / a.union(b).size.coerceAtLeast(1).toDouble()
}

private fun mergeMemory(primary: MemoryEntry, duplicate: MemoryEntry): MemoryEntry {
    val preferredContent = when {
        primary.pinned && !duplicate.pinned -> primary.content
        duplicate.pinned && !primary.pinned -> duplicate.content
        duplicate.content.length > primary.content.length -> duplicate.content
        else -> primary.content
    }
    return primary.copy(
        content = preferredContent,
        strength = maxOf(primary.strength, duplicate.strength),
        pinned = primary.pinned || duplicate.pinned,
        canRecallProactively = primary.canRecallProactively || duplicate.canRecallProactively,
        source = mergeMemoryProvenance(primary.source, duplicate.source),
        occurredAt = listOfNotNull(primary.occurredAt, duplicate.occurredAt).minOrNull(),
        createdAt = minOf(primary.createdAt, duplicate.createdAt),
    )
}

private fun mergeMemoryProvenance(first: String, second: String): String {
    fun ids(source: String, prefix: String): List<String> =
        source.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.split('|')?.filter(String::isNotBlank).orEmpty()
    val precise = (ids(first, "timeline-events:") + ids(second, "timeline-events:")).distinct().take(12)
    if (precise.isNotEmpty()) return "timeline-events:${precise.joinToString("|")}"
    val batch = (ids(first, "timeline-batch:") + ids(second, "timeline-batch:")).distinct().take(40)
    if (batch.isNotEmpty()) return "timeline-batch:${batch.joinToString("|")}"
    return first.ifBlank { second }
}

private fun <T> JSONArray?.decodeObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            runCatching { transform(item) }.getOrNull()?.let { decoded -> add(decoded) }
        }
    }
}

private fun JSONObject.nullableString(key: String): String? =
    takeUnless { json -> json.isNull(key) }?.optString(key)?.takeIf(String::isNotBlank)
