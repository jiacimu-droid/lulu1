package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.core.MemoryTier
import com.jiacimu.lulu.core.MemoryPerspective
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

/** Preserve the original memory batch while a resident's activity is explicitly paused. */
internal class ResidentMemoryPaused(val resumeAt: Instant) :
    IllegalStateException("居民活动已暂停，未整理的记忆保留")

/**
 * Persistent memory store with retry-safe extraction checkpoints.
 * A batch is marked processed only after a valid model result has been parsed and saved.
 */
class LocalMemoryRepository internal constructor(
    private val extractionClock: () -> Instant,
) : MemoryRepository {
    constructor() : this(Instant::now)
    private val state = MutableStateFlow(MemoryStoreState())
    private val debugFlow = MutableStateFlow(MemoryDebugState())
    private val extractionLocks = mutableMapOf<String, Mutex>()
    private val historyGenerations = mutableMapOf<String, Long>()
    private var prefs: android.content.SharedPreferences? = null
    private var advancedPrefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null
    private val lock = Any()

    val debugState: StateFlow<MemoryDebugState> = debugFlow.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            appContext = context.applicationContext
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            advancedPrefs = context.applicationContext.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
            state.value = decode(prefs?.getString(KEY_STATE, null))
            MemoryRecallUsageStore.initialize(context)
            MemoryInspectionStore.setChangeListener { characterId ->
                if (debugFlow.value.characterId == characterId) refreshInspection(characterId)
            }
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
        if (appContext != null && policy.autoSummarize) {
            (MigratedDomainStores.characters.settings.value.keys + characterId).forEach { ChatMemoryAutomation.request(it) }
        }
        refreshDebug("已保存全部角色共用的记忆规则", characterId)
    }

    /** Personal settings override the global defaults without reviving obsolete policies. */
    suspend fun updatePersonalPolicy(characterId: String, policy: MemoryPolicy) {
        require(characterId.isNotBlank())
        require(policy.excludedRecentMessages >= 0 && policy.readableThreshold > 0)
        mutate { current -> current.copy(personalPolicies = current.personalPolicies + (characterId to policy)) }
        if (appContext != null && policy.autoSummarize) ChatMemoryAutomation.request(characterId)
        refreshDebug("已保存当前角色的记忆规则", characterId)
    }

    suspend fun followGlobalPolicy(characterId: String) {
        mutate { current -> current.copy(personalPolicies = current.personalPolicies - characterId) }
        if (appContext != null && state.value.resolvedPolicy(characterId).autoSummarize) ChatMemoryAutomation.request(characterId)
        refreshDebug("当前角色跟随统一记忆规则", characterId)
    }

    /** Clear history bookkeeping too, allowing the new life to learn the same fact again. */
    fun clearCharacterHistory(characterId: String) {
        mutate { current ->
            historyGenerations[characterId] = (historyGenerations[characterId] ?: 0L) + 1L
            current.copy(
                entries = current.entries.filterNot { it.characterId == characterId },
                processedMessageIds = current.processedMessageIds - characterId,
                extractionContinuationTurns = current.extractionContinuationTurns - characterId,
                deferredExtraction = current.deferredExtraction - characterId,
                immediateProcessedIds = current.immediateProcessedIds - characterId,
                forgottenSourceIds = current.forgottenSourceIds - characterId,
                deletedMemoryIds = current.deletedMemoryIds - characterId,
                deletedMemoryKeys = current.deletedMemoryKeys.filterNot { it.startsWith("$characterId:") }.toSet(),
            )
        }
        MemoryInspectionStore.clearCharacter(characterId)
        MemoryRecallUsageStore.clearCharacter(characterId)
        CoreMemoryInjectionAudit.clearCharacter(characterId)
        refreshDebug("经历与记忆已清空，角色资料与记忆设置保留", characterId)
    }

    internal suspend fun <T> withCharacterMemoryLock(characterId: String, block: suspend () -> T): T {
        val mutex = synchronized(extractionLocks) { extractionLocks.getOrPut(characterId) { Mutex() } }
        return mutex.withLock { block() }
    }

    override suspend fun summarizeNow(characterId: String) {
        require(characterId.isNotBlank()) { "角色不能为空" }
        withCharacterMemoryLock(characterId) {
            extractImmediateMemories(characterId)
            // The last N COMPLETE turns are protected even after an idle period.
            // Only an explicit flushNow may deliberately include that tail.
            summarizeContinuously(characterId, flushTail = false)
        }
    }

    /** Explicit user action drains even a partial protected tail. */
    suspend fun flushNow(characterId: String) {
        require(characterId.isNotBlank())
        val extractionLock = synchronized(extractionLocks) { extractionLocks.getOrPut(characterId) { Mutex() } }
        extractionLock.withLock {
            try {
                extractImmediateMemories(characterId)
                summarizeContinuously(characterId, flushTail = true)
            } catch (deferred: ResidentMemoryPaused) {
                // Keep a durable queue entry for the original, unprocessed tail.
                appContext?.let { MemoryExtractionJobStore.initialize(it) }
                val queued = MemoryExtractionJobStore.enqueue(characterId)
                MemoryExtractionJobStore.defer(queued, deferred.resumeAt)
                refreshDebug("居民活动已暂停；待处理原文和进度完整保留",
                    characterId, extracting = false)
            } catch (error: Exception) {
                refreshDebug("记忆整理失败，记录保留等待重试", characterId, lastError = error.message)
                throw error
            }
        }
    }

    private fun deferPausedResidentMemory(characterId: String, pendingCount: Int) {
        val context = appContext ?: return
        if (ResidentDailyPolicy.activityEnabled(context, characterId)) return
        refreshDebug("居民活动已暂停，待整理的 $pendingCount 条原文与进度保留。",
            characterId = characterId, pendingCount = pendingCount, extracting = false)
        throw ResidentMemoryPaused(BackgroundModelRequestBudget.retryAt())
    }

    private suspend fun extractImmediateMemories(characterId: String) {
        val generation = synchronized(lock) { historyGenerations[characterId] ?: 0L }
        while (true) {
            val events = memoryEligibleTimelineEvents(characterId)
            val done = state.value.immediateProcessedIds[characterId].orEmpty() +
                state.value.processedMessageIds[characterId].orEmpty()
            val deferred = state.value.deferredExtraction[characterId].orEmpty()
            val now = extractionClock()
            val targets = events.filter {
                it.id !in done && deferred[it.id]?.retryAt?.isAfter(now) != true &&
                    it.isUserMemoryStatement() && requestsImmediateMemory(it.content)
            }.take(8)
            if (targets.isEmpty()) return
            deferPausedResidentMemory(characterId, targets.size)
            val targetIds = targets.mapTo(mutableSetOf(), SharedTimelineEvent::id)
            // The immediate path must use the SAME semantic turns as regular
            // extraction: a hundred game moves cannot replace the preceding
            // five real exchanges in a user's important correction context.
            val turns = semanticTimelineTurns(characterId, events)
            val targetTurnIndices = turns.indices.filter { index ->
                turns[index].events.any { it.id in targetIds }
            }
            val nearby = turns.filterIndexed { index, _ ->
                targetTurnIndices.any { selected -> index in
                    ((selected - 5).coerceAtLeast(0)..(selected + 3).coerceAtMost(turns.lastIndex)) }
            }
            val view = MemoryExtractionTurnView.build(
                MemoryExtractionTurnView.fit(nearby, allowEmpty = true),
            )
            // Always preserve the explicitly targeted original statement.
            // The compact episode view is background, not permission to cite
            // game steps that were never presented to this model request.
            val targetFacts = targets.joinToString("\n") { event ->
                "[eventId=${event.id}@${event.revision}] [${event.occurredAt}]" +
                    " ${event.speaker}：${event.evidenceContent.take(2_400)}"
            }
            val selected = (view.visibleSources + targets).distinctBy { it.id }
            val canonical = SharedExperienceTimeline.eventsByIds(
                characterId, selected.mapTo(mutableSetOf(), SharedTimelineEvent::id))
                .associateBy { it.id }
            check(selected.all { canonical[it.id] == it }) {
                "即时记忆来源在准备期间改变；保留原话等待重试"
            }
            val context = selected
            val facts = view.text + "\n【需要立即理解的主人原话】\n" + targetFacts
            val prior = currentUnderstandingCandidates(characterId, facts)
            val result = LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = facts + renderPriorUnderstanding(prior),
                instruction = """
                    判断目标用户事件 ${targetIds.joinToString("|")} 是否包含应立即保存的稳定事实、明确偏好、边界或重要长期安排。
                    信号词只用于筛选：必须结合上下文理解，反问、假设、引用、角色台词、虚构故事、临时状态都不能当成长期事实。
                    只返回可直接 JSON 解析的数组；没有就返回 []，不用 Markdown 或解释。
                    每项必填 {"kind":"Fact","content":"有主体和语境的一两句真实事实","sourceEventIds":["输入中真实事件ID"]}。
                    可选 tier(Core|Stable)、strength(1-10)、subject、slot、supersedes(旧事实ID数组)、occurredAt、
                    sceneKey、measureKind、measureValue、measureUnit；没有真实依据就省略这些可选字段，
                    绝不为凑齐长表格补虚构字段。用户明确说要长期记住的重要事实应标 tier=Core。
                    每条来源必须包含至少一个目标用户事件。Core 用于用户郑重要求持续遵守或长期牢记的稳定事实，包括重要边界、关系、安排及明确要求始终记住的偏好；普通喜好用 Stable。
                    相处中的条件约定要保留完整触发条件和表达要求，例如“做某种互动时要带上指定拟声”；不能摘要成笼统的“喜欢亲密”而丢掉具体要求。用户反复强调、明确拒绝的相处方式与稳定称呼应进入 Core。
                    ‘今天不吃辣’不是‘以后不喜欢辣’；‘记住’后面的临时提醒交给责任系统，不保存为长期核心事实。
                    只保存真实明确内容，不猜测、不扩写，不把角色答应行动当作完成。
                    场景与可量数字只有原话明确给才填：sceneKey 写“活动|地点|参与人|时段”的短串（如睡觉|卧室|自己|夜里），原话没给地点或时段就留空；measureKind 只用于原话明确说出的可量数字（睡多久填 sleep_hours、几点睡填 bedtime），measureValue 填数字（bedtime 用 24 小时制小数，如 23.5 表示 23:30），measureUnit 填“小时”或“时”；没有就留空或 null，绝不换算、绝不猜测。
                    ${understandingUpdateInstruction()}
                """.trimIndent(),
                source = "记忆", title = "重要信息即时记忆", maxTokens = 3_200,
                contextMode = CompanionContextMode.Isolated, streamResponse = true,
                connectionOverride = extractionConnection(),
            )
            val response = readExtractionResult(result, characterId, context)
            val parsed = response.updates.filter { update ->
                update.memory.kind == MemoryKind.Fact && update.memory.sourceIds().any(targetIds::contains)
            }
            check(SharedExperienceTimeline.commitIfEvidenceUnchanged(characterId, context) {
                mutate { current ->
                    if ((historyGenerations[characterId] ?: 0L) != generation) return@mutate current
                    val valid = parsed.map { it.memory }.map { memory ->
                        memory.copy(sourceRevisions = context.filter { it.id in memory.sourceIds() }.associate { it.id to it.revision })
                    }
                    current.withExtractionRetry(
                        characterId, targetIds, response.hasRejectedRows, extractionClock(),
                    ).copy(entries = mergeExtractedEntries(current, valid),
                        immediateProcessedIds = if (response.hasRejectedRows) current.immediateProcessedIds
                        else current.immediateProcessedIds +
                            (characterId to (current.immediateProcessedIds[characterId].orEmpty() + targetIds)))
                }
                applyUnderstandingUpdates(characterId, parsed, prior, context.associate { it.id to it.revision })
            }) { "原始上下文已改变，重要信息保留等待重新理解" }
            if (synchronized(lock) { historyGenerations[characterId] ?: 0L } != generation) return
            if (response.hasRejectedRows) return
            maintain(characterId, silent = true)
        }
    }

    private suspend fun summarizeContinuously(characterId: String, flushTail: Boolean = false) {
        val generation = synchronized(lock) { historyGenerations[characterId] ?: 0L }
        val policy = state.value.resolvedPolicy(characterId)
        val threshold = policy.readableThreshold.coerceAtLeast(1)
        var processedTurnsThisRun = 0
        var extractedThisRun = 0
        // A size-bounded subrequest may finish only part of an eligible
        // 15-turn batch. The continuation count lives in the SAME durable
        // memory checkpoint, atomically committed with processed source IDs:
        // a crash after the first request cannot abandon the remaining turns.
        var triggeredTurnsRemaining =
            state.value.extractionContinuationTurns[characterId].orEmptyCount()

        while (true) {
            if (synchronized(lock) { historyGenerations[characterId] ?: 0L } != generation) return
            // Re-read every loop. A source event may be deleted while the model request is in flight;
            // a frozen readable list would otherwise keep retrying a no-longer-existing event.
            val readable = extractableTurns(characterId, policy, flushTail)
            val processed = state.value.processedMessageIds[characterId].orEmpty()
            // A group is pending if ANY of its original facts is unprocessed.
            // Always present the WHOLE conversation turn to extraction, even
            // when a legacy raw-message checkpoint split its three bubbles.
            // Marking the existing source IDs again is idempotent; dropping
            // the already-processed prefix would erase the context of the tail.
            val pending = readable.filter { turn ->
                turn.events.any { it.id !in processed }
            }
            val deferred = state.value.deferredExtraction[characterId].orEmpty()
            val (untried, retries) = pending.map { turn ->
                turn to turn.events.mapNotNull { deferred[it.id] }.maxByOrNull { it.retryAt }
            }.partition { (_, retry) -> retry == null }
            val fresh = untried.map { (turn, _) -> turn }
            // Committed source IDs define the starting point when the batch size changes.
            if (triggeredTurnsRemaining == 0 && fresh.size >= threshold) {
                triggeredTurnsRemaining = threshold
            }
            val freshReady = fresh.isNotEmpty() && (flushTail || triggeredTurnsRemaining > 0)
            val now = extractionClock()
            val dueRetry = retries.mapNotNull { (_, retry) -> retry }
                .filterNot { it.retryAt.isAfter(now) }.minByOrNull { it.retryAt }
            val retrying = !freshReady && dueRetry != null
            val requested = when {
                freshReady -> fresh.take(triggeredTurnsRemaining.takeIf { it > 0 } ?: threshold)
                dueRetry != null -> retries.filter { (_, retry) -> retry == dueRetry }.map { (turn, _) -> turn }
                    .take(when {
                        dueRetry.failures >= 3 -> 1
                        dueRetry.failures == 2 -> minOf(threshold, 5)
                        else -> threshold
                    })
                else -> emptyList()
            }

            if (requested.isEmpty()) {
                if (fresh.isEmpty() && triggeredTurnsRemaining > 0) {
                    mutate { current -> current.copy(
                        extractionContinuationTurns = current.extractionContinuationTurns - characterId,
                    ) }
                }
                if (processedTurnsThisRun > 0 || extractedThisRun > 0) maintain(characterId, silent = true)
                val deferredTurns = pending.size - fresh.size
                val freshCount = fresh.size
                refreshDebug(
                    message = if (deferredTurns > 0) {
                        "$deferredTurns 轮原话已保留，将自动补提取；$freshCount/$threshold 轮等待下一批"
                    } else if (processedTurnsThisRun > 0) {
                        "本次处理 $processedTurnsThisRun 轮，新增 $extractedThisRun 条记忆"
                    } else "可整理轮次 $freshCount/$threshold，等待下一批",
                    characterId = characterId, readableCount = readable.size,
                    pendingCount = pending.size, batchCount = 0, lastExtractedCount = extractedThisRun,
                )
                return
            }

            deferPausedResidentMemory(characterId, pending.size)
            val fitted = MemoryExtractionTurnView.fit(requested, allowEmpty = true)
            val batchTurns = fitted.ifEmpty { requested.take(1) }
            val batch = batchTurns.flatMap { it.events }
            val continuationAfterCommit = if (retrying) triggeredTurnsRemaining
                else (triggeredTurnsRemaining - batchTurns.size).coerceAtLeast(0)
            val batchCount = batchTurns.size
            val batchStart = processedTurnsThisRun + 1
            val batchEnd = processedTurnsThisRun + batchCount
            refreshDebug(
                message = if (retrying) "正在自动补提取 $batchCount 轮原话"
                    else "正在整理第 $batchStart-$batchEnd 轮真实交流与经历",
                characterId = characterId, readableCount = readable.size,
                pendingCount = pending.size, batchCount = batchCount,
                extracting = true, lastExtractedCount = extractedThisRun,
            )

            val batchIds = batch.mapTo(mutableSetOf(), SharedTimelineEvent::id)
            val batchEvents = SharedExperienceTimeline.eventsByIds(characterId, batchIds)
            // Some rows might have been deleted between selection and lookup.
            // Do not certify a partly missing episode as fully processed.
            check(batchEvents.size == batchIds.size) { "原始事件在准备总结时改变；保留整批等待重试" }
            // The input and commit guard MUST use one identical snapshot:
            // a source may be revised between selecting turns and fetching IDs.
            val canonical = batchEvents.associateBy { it.id }
            check(batchTurns.all { turn -> turn.events.all { event ->
                canonical[event.id] == event
            } }) { "原始来源或版本在轮次选择后改变；保留整批并重试" }
            val canonicalTurns = batchTurns.map { turn ->
                turn.copy(events = turn.events.map { event ->
                    canonical.getValue(event.id)
                })
            }
            val evidenceViews = if (fitted.isEmpty()) MemoryExtractionTurnView.splitLongTurn(canonicalTurns.single())
                else sequenceOf(MemoryExtractionTurnView.build(canonicalTurns))
            val responses = mutableListOf<ParsedMemoryResponse>()
            val allPrior = mutableListOf<MemoryEntry>()
            for (evidenceView in evidenceViews) {
                if (evidenceView.visibleSources.isEmpty()) {
                    responses += ParsedMemoryResponse(hasRejectedRows = true)
                    continue
                }
                val prior = currentUnderstandingCandidates(characterId, evidenceView.text)
                try {
                    responses += requestMemoryExtraction(characterId, evidenceView, prior)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    refreshDebug("后台记忆提取暂缓，原文已保留", characterId, lastError = error.message)
                    throw error
                }
                allPrior += prior
            }
            val parsed = ParsedMemoryResponse(responses.flatMap { it.updates }, responses.any { it.hasRejectedRows })
            val added = commitExtraction(
                characterId, generation, batchEvents, parsed, allPrior.distinctBy { it.id }, continuationAfterCommit,
            )
            if (synchronized(lock) { historyGenerations[characterId] ?: 0L } != generation) return
            if (!parsed.hasRejectedRows) processedTurnsThisRun += batchTurns.size
            triggeredTurnsRemaining = continuationAfterCommit
            extractedThisRun += added
        }
    }

    private suspend fun requestMemoryExtraction(
        characterId: String,
        evidenceView: MemoryExtractionTurnView.Batch,
        prior: List<MemoryEntry>,
    ): ParsedMemoryResponse {
        val result = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = memorySelectionPerspective(characterId) +
                "\n【待提取的真实事件｜只有这里的事件ID可用于记忆来源】\n" +
                evidenceView.text + renderPriorUnderstanding(prior),
            instruction = """
                把当事人日后可能回想起的真实经历、感受及稳定事实整理成简短记忆。只返回完整 JSON 数组。
                每条 {"kind":"Fact|Emotion|Timeline","content":"人物、事情及必要语境","sourceEventIds":["eventId"]}。
                Fact 保留明确的长期事实、偏好、关系、边界与安排。临时情况按当时经历记录。主人明确要求长久记住的稳定事实用 tier=Core，普通偏好用 Stable，其他用 Episode。
                Emotion 写明是谁、当时的感受、已知诱因。若前因或后果在本批另有原话或工具回执，可一起引用，方便从情绪想起整件事。
                Timeline 记录有辨识细节的真实经历，包括普通日常小事。寒暄、重复陪伴、睡前故事、假设和虚构演绎不提取为现实经历。已交由任务系统执行的约定无需再摘录。
                perspective 默认为 Record，按原话或回执注明主体，区分当事人陈述、实际观察与执行结果。只有角色本人实际说出或在「此刻」记录的感受才能使用 Subjective，并引用本人那条记录。
                每条复制本批 1–6 个直接支持它的 eventId，允许带原文中的 @版本号。已有理解只供查重与修订。标记了原文范围时，只摘录该段能独立确认的内容。
                可选字段仅在有依据时填写：occurredAt 是明确发生的时间（ISO 8601），strength 是重要程度 1–10，subject 是具体对象，slot 是该对象的事实槽位，sceneKey 是活动|地点|参与人|时段。
                measureKind、measureValue、measureUnit 仅保存原话明确的量值，例如 sleep_hours（小时）或 bedtime（24 小时制，23:30 为 23.5）。没有可保存的内容就返回 []。
                ${understandingUpdateInstruction()}
            """.trimIndent(),
            source = "记忆",
            title = "连续记忆提取",
            streamResponse = true,
            maxTokens = (1_200 + evidenceView.turnCount.coerceAtMost(20) * 270).coerceIn(3_200, 8_000),
            contextMode = CompanionContextMode.Isolated,
            readTimeoutMillis = 180_000,
            connectionOverride = extractionConnection(),
        )
        return readExtractionResult(result, characterId, evidenceView.visibleSources)
    }

    private fun commitExtraction(
        characterId: String,
        generation: Long,
        batch: List<SharedTimelineEvent>,
        response: ParsedMemoryResponse,
        prior: List<MemoryEntry>,
        remainingTurns: Int,
    ): Int {
        val revisions = batch.associate { it.id to it.revision }
        val ids = revisions.keys
        var added = 0
        var committed = false
        check(SharedExperienceTimeline.commitIfEvidenceUnchanged(characterId, batch) {
            mutate { current ->
                if ((historyGenerations[characterId] ?: 0L) != generation) return@mutate current
                val valid = response.updates.map { it.memory }.map { entry ->
                    entry.copy(sourceRevisions = revisions.filterKeys { it in entry.sourceIds() })
                }
                val merged = mergeExtractedEntries(current, valid)
                added = (merged.size - current.entries.size).coerceAtLeast(0)
                committed = true
                current.withExtractionRetry(characterId, ids, response.hasRejectedRows, extractionClock()).copy(
                    entries = merged,
                    processedMessageIds = if (response.hasRejectedRows) current.processedMessageIds else
                        current.processedMessageIds + (characterId to (current.processedMessageIds[characterId].orEmpty() + ids)),
                    extractionContinuationTurns = if (remainingTurns == 0)
                        current.extractionContinuationTurns - characterId
                    else current.extractionContinuationTurns + (characterId to remainingTurns),
                )
            }
            if (committed) applyUnderstandingUpdates(characterId, response.updates, prior, revisions)
        }) { "原始上下文已改变，整批保留等待重新理解" }
        return added
    }

    private data class ExtractedMemoryUpdate(val memory: MemoryEntry, val supersedes: List<String>?)

    private data class ParsedMemoryResponse(
        val updates: List<ExtractedMemoryUpdate> = emptyList(),
        val hasRejectedRows: Boolean = false,
    )

    private fun currentUnderstandingCandidates(characterId: String, query: String): List<MemoryEntry> {
        val terms = memorySearchTerms(query)
        return snapshot(characterId).filter { it.kind == MemoryKind.Fact && MemoryValidityStore.isActive(it.id) &&
            memoryHasLiveSources(it, characterId) }
            .sortedByDescending { terms.intersect(memorySearchTerms(it.content)).size }
            .let { selectMemoryWithinBudget(it, characterBudget = 6_000, safetyLimit = 20) }
    }

    private fun renderPriorUnderstanding(prior: List<MemoryEntry>): String = if (prior.isEmpty()) "" else
        "\n已有当前理解（不是新的原始依据）：\n" + prior.joinToString("\n") {
            "memoryId=${it.id}；perspective=${it.perspective}；subject=${it.subject}；slot=${it.slot}；${it.content}"
        }

    private fun understandingUpdateInstruction(): String =
        "新事实明确纠正或更新同一槽位时，在 supersedes 中填已有理解的真实 memoryId；主题相关、补充细节、临时状态或过去情绪都不算替代。不确定返回空数组。旧理解只能作比较，不能当作新事件的来源。"

    /** Versioned current understanding is a projection of existing memories, never a second fact DB. */
    private fun applyUnderstandingUpdates(characterId: String, updates: List<ExtractedMemoryUpdate>, prior: List<MemoryEntry>, expected: Map<String, Long>) {
        synchronized(SharedExperienceTimeline) {
            val current = state.value.entries.filter { it.characterId == characterId }
            val priorById = prior.associateBy { it.id }
            val liveIds = liveRevisionIds(characterId, expected)
            updates.forEach { update ->
                if (update.memory.sourceIds().any { it !in liveIds }) return@forEach
                val memory = current.firstOrNull { it.dedupeKey() == update.memory.dedupeKey() } ?: return@forEach
                if (!memoryHasLiveSources(memory, characterId)) return@forEach
                val proposed = update.supersedes ?: return@forEach
                val oldIds = proposed.filter { id ->
                    val before = priorById[id]
                    before != null && before.kind == MemoryKind.Fact && memory.kind == MemoryKind.Fact &&
                        before.perspective == memory.perspective &&
                        before.id != memory.id && current.any { it == before } && memoryHasLiveSources(before, characterId)
                }
                MemoryValidityStore.markSuperseded(oldIds, memory.id)
                MemorySupersessionAutomation.rememberInspected(memory.id)
            }
        }
    }

    private fun liveRevisionIds(characterId: String, expected: Map<String, Long>): Set<String> =
        SharedExperienceTimeline.eventsByIds(characterId, expected.keys)
            .filter { expected[it.id] == it.revision }.mapTo(mutableSetOf()) { it.id }

    private fun mergeExtractedEntries(current: MemoryStoreState, incoming: List<MemoryEntry>): List<MemoryEntry> {
        val entries = current.entries.toMutableList()
        incoming.forEach { entry ->
            if (entry.isOwnerDeleted(current.deletedMemoryKeys) ||
                entry.sourceIds().any { it in current.forgottenSourceIds[entry.characterId].orEmpty() }) return@forEach
            val index = entries.indexOfFirst { existing ->
                // The old 320-character dedupe key can collide when a later
                // sentence contains an important qualification. Use exact full
                // content and equal event scope before merging extracted facts.
                // Never combine separate dated experiences into one generic fact.
                memoriesEquivalentForMaintenance(existing, entry)
            }
            if (index < 0) entries += entry else entries[index] = mergeMemory(entries[index], entry)
        }
        return entries
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
        synchronized(SharedExperienceTimeline) {
        if (!memoryHasLiveSources(clean, clean.characterId)) return
        mutate { current ->
            if (!allowRestore && (clean.isOwnerDeleted(current.deletedMemoryKeys) || clean.id in current.deletedMemoryIds[clean.characterId].orEmpty())) return@mutate current
            if (current.entries.any { it.id == clean.id && it.characterId != clean.characterId }) return@mutate current
            val index = current.entries.indexOfFirst { item -> item.id == clean.id }
            val nextEntries = if (index < 0) current.entries + clean
            else current.entries.toMutableList().apply { set(index, clean) }
            current.copy(
                entries = nextEntries,
                deletedMemoryKeys = if (allowRestore) current.deletedMemoryKeys - scopedKey - clean.legacyScopedMemoryKey() else current.deletedMemoryKeys,
                deletedMemoryIds = if (allowRestore) current.deletedMemoryIds +
                    (clean.characterId to (current.deletedMemoryIds[clean.characterId].orEmpty() - clean.id)) else current.deletedMemoryIds,
            )
        }
        }
        MemoryEmbeddingIndex.removeMemory(entry.id)
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
                deletedMemoryIds = target?.let { current.deletedMemoryIds +
                    (it.characterId to (current.deletedMemoryIds[it.characterId].orEmpty() + it.id)) } ?: current.deletedMemoryIds,
            )
        }
        target?.let {
            CoreMemoryInjectionAudit.removeMemory(it.characterId, it.id)
            MemoryValidityStore.removeMemory(it.id)
            MemoryRecallUsageStore.removeMemory(it.characterId, it.id)
        }
        refreshDebug("记忆已删除")
    }

    /**
     * Legacy method name: deleting a memory can retire semantically equivalent
     * copies ONLY inside its owning character's life. Similar content in another
     * role belongs to that person's independent history, even if the text matches.
     */
    suspend fun deleteEverywhereEquivalent(id: String): Int {
        val target = state.value.entries.firstOrNull { entry -> entry.id == id } ?: return 0
        val targetKey = target.memoryIdentityKey()
        var removed = 0
        var removedEntries = emptyList<MemoryEntry>()
        mutate { current ->
            val victims = current.entries.filter { entry ->
                entry.characterId == target.characterId && entry.kind == target.kind &&
                    entry.perspective == target.perspective && entry.memoryIdentityKey() == targetKey
            }
            removedEntries = victims
            removed = victims.size
            current.copy(
                entries = current.entries.filterNot { entry ->
                    entry.characterId == target.characterId && entry.kind == target.kind &&
                        entry.perspective == target.perspective && entry.memoryIdentityKey() == targetKey
                },
                deletedMemoryKeys = current.deletedMemoryKeys + victims.map(MemoryEntry::scopedMemoryKey),
                deletedMemoryIds = current.deletedMemoryIds + victims.groupBy(MemoryEntry::characterId).mapValues { (role, entries) ->
                    current.deletedMemoryIds[role].orEmpty() + entries.map { it.id }
                },
                forgottenSourceIds = current.forgottenSourceIds + victims.groupBy(MemoryEntry::characterId).mapValues { (characterId, memories) ->
                    current.forgottenSourceIds[characterId].orEmpty() + memories.flatMap { it.sourceIds() }
                },
            )
        }
        removedEntries.forEach {
            CoreMemoryInjectionAudit.removeMemory(it.characterId, it.id)
            MemoryValidityStore.removeMemory(it.id)
            MemoryRecallUsageStore.removeMemory(it.characterId, it.id)
        }
        refreshDebug(if (removed > 1) "已删除本角色 $removed 条同内容记忆" else "记忆已删除")
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
                if (index < 0) kept += candidate
                else {
                    kept[index] = mergeMemory(kept[index], candidate)
                    removed += 1
                }
            }
            if (removed == 0) current else current.copy(
                entries = current.entries.filterNot { it.characterId == characterId } + kept,
            )
        }
        if (!silent) refreshDebug(
            if (removed == 0) "记忆维护完成，没有发现可安全合并的重复项" else "记忆维护完成，合并了 $removed 条重复记忆",
            characterId,
        )
        return removed
    }

    internal fun discardIfSourcesInvalid(memory: MemoryEntry) = synchronized(SharedExperienceTimeline) {
        if (memory.sourceIds().isEmpty() || memoryHasLiveSources(memory, memory.characterId)) return@synchronized
        var removed = false
        mutate { current ->
            if (memory !in current.entries) return@mutate current
            removed = true
            current.copy(entries = current.entries.filterNot { it == memory })
        }
        if (removed) {
            MemoryValidityStore.removeMemory(memory.id)
            CoreMemoryInjectionAudit.removeMemory(memory.characterId, memory.id)
        }
    }

    suspend fun deleteDerivedFromEvent(eventId: String) = invalidateDerivedFromEvent(eventId)

    /** Called synchronously under the timeline lock on edit/delete, before another recall can read. */
    fun invalidateDerivedFromEvent(eventId: String) {
        if (eventId.isBlank()) return
        var removedEntries = emptyList<MemoryEntry>()
        mutate { current ->
            removedEntries = current.entries.filter { entry -> eventId in entry.memorySourceEventIds() }
            current.copy(
                entries = current.entries.filterNot { entry -> eventId in entry.memorySourceEventIds() },
                processedMessageIds = current.processedMessageIds.mapValues { (_, ids) -> ids - eventId },
                immediateProcessedIds = current.immediateProcessedIds.mapValues { (_, ids) -> ids - eventId },
                deferredExtraction = current.deferredExtraction.mapValues { (_, retries) -> retries - eventId }
                    .filterValues { it.isNotEmpty() },
            )
        }
        removedEntries.forEach {
            CoreMemoryInjectionAudit.removeMemory(it.characterId, it.id)
            MemoryValidityStore.removeMemory(it.id)
            MemoryRecallUsageStore.removeMemory(it.characterId, it.id)
        }
        refreshDebug("已撤销由删除内容产生的派生记忆")
    }

    suspend fun togglePinned(id: String) {
        mutate { current ->
            current.copy(
                entries = current.entries.map { entry -> if (entry.id == id) entry.copy(pinned = !entry.isCoreMemory(), tier = if (entry.isCoreMemory()) { if (entry.kind == MemoryKind.Fact) MemoryTier.Stable else MemoryTier.Episode } else MemoryTier.Core) else entry },
            )
        }
    }

    suspend fun toggleRecall(id: String) {
        mutate { current ->
            current.copy(
                entries = current.entries.map { entry ->
                    if (entry.id == id) entry.copy(canRecallProactively = !entry.canRecallProactively) else entry
                },
            )
        }
    }

    suspend fun replaceAll(entries: List<MemoryEntry>) {
        mutate { current -> current.copy(entries = entries) }
    }

    fun allowsRawRecall(event: SharedTimelineEvent): Boolean {
        // Old, already archived phone-navigation traces must not slip back
        // into the character's model through semantic recall after their
        // event producer and perception route were retired.
        if (event.source == "owner-phone-inspection") return false
        val current = state.value
        if (event.id in current.forgottenSourceIds[event.characterId].orEmpty()) return false
        return current.entries.none { memory ->
            memory.characterId == event.characterId &&
                (!memory.canRecallProactively || !MemoryValidityStore.isActive(memory.id)) && event.id in memory.sourceIds()
        }
    }

    fun shouldFlushPendingTail(characterId: String, now: Instant = Instant.now()): Boolean {
        val policy = state.value.resolvedPolicy(characterId)
        val readable = extractableTurns(characterId, policy, flushTail = false)
        val processed = state.value.processedMessageIds[characterId].orEmpty()
        val retries = state.value.deferredExtraction[characterId].orEmpty()
        if (retries.isNotEmpty()) {
            val due = retries.filterValues { !it.retryAt.isAfter(now) }.keys
            if (readable.any { turn -> turn.events.any { it.id in due && it.id !in processed } }) return true
            val immediate = state.value.immediateProcessedIds[characterId].orEmpty()
            if (SharedExperienceTimeline.eventsByIds(characterId, due).any {
                    it.id !in processed && it.id !in immediate && it.isUserMemoryStatement() &&
                        requestsImmediateMemory(it.content)
                }) return true
        }
        val fresh = readable.filter { turn ->
            turn.events.any { it.id !in processed } && turn.events.none { it.id in retries }
        }
        return fresh.size >= policy.readableThreshold &&
            shouldFlushPendingMemoryTail(fresh.flatMap { it.events }, processed, now)
    }

    internal fun deferredExtractionTurnCount(characterId: String): Int {
        val retries = state.value.deferredExtraction[characterId].orEmpty()
        if (retries.isEmpty()) return 0
        return extractableTurns(characterId).count { turn ->
            turn.events.any { it.id in retries }
        }
    }

    fun hasUnprocessedEvents(characterId: String): Boolean {
        // Use the SAME semantic projection as the 20-turn reader and
        // 15+5 extraction queue. Diagnostics like online-awareness remain in
        // the raw ledger, but cannot keep memory "dirty" forever by themselves.
        val processed = state.value.processedMessageIds[characterId].orEmpty()
        return semanticTimelineTurns(characterId).any { turn ->
            turn.events.any { it.id !in processed }
        }
    }

    /** Pending is measured in complete semantic turns; the API still returns
     * genuine source rows so old debug, source and deletion callers stay safe. */
    fun pendingMessageCount(characterId: String): Int {
        val processed = state.value.processedMessageIds[characterId].orEmpty()
        return extractableTurns(characterId).count { turn ->
            turn.events.any { it.id !in processed }
        }
    }

    fun pendingTimelineEvents(characterId: String): List<SharedTimelineEvent> {
        val processed = state.value.processedMessageIds[characterId].orEmpty()
        return extractableTurns(characterId).flatMap { turn ->
            turn.events.filterNot { it.id in processed }
        }
    }

    /**
     * Select complete semantic turns, NOT the last N raw action/perception
     * receipts. A long co-op game or phone navigation can contain hundreds of
     * immutable facts but must not evict the owner's last real exchange.
     */
    internal fun contextTimelineTurns(characterId: String): List<RecentSemanticTimeline.Turn> =
        semanticTimelineTurns(characterId)
            .takeLast(state.value.resolvedPolicy(characterId).rawContextMessageCount)

    /** Exactly the same grouping for the reader, extraction queue and recap.
     * Never reinterpret already saved message IDs or raw tool receipts. */
    private fun semanticTimelineTurns(
        characterId: String,
        eligible: List<SharedTimelineEvent> = memoryEligibleTimelineEvents(characterId),
    ): List<RecentSemanticTimeline.Turn> {
        val cachedBatches = mutableMapOf<String, String?>()
        return RecentSemanticTimeline.select(eligible, Int.MAX_VALUE) { event ->
            if (event.source != "message" || event.sessionId.isBlank()) null
            else cachedBatches.getOrPut(event.id) {
                val id = event.id.substringBefore(":group:")
                MigratedDomainStores.chat.recordedMessage(event.sessionId, id)?.replyBatchId
            }
        }
    }

    private fun extractableTurns(
        characterId: String,
        policy: MemoryPolicy = state.value.resolvedPolicy(characterId),
        flushTail: Boolean = false,
    ): List<RecentSemanticTimeline.Turn> {
        val all = semanticTimelineTurns(characterId)
        return if (flushTail) all else
            all.dropLast(policy.excludedRecentMessages.coerceAtLeast(0))
    }

    /** Legacy event API still returns real original IDs, never synthetic rows. */
    fun contextTimelineEvents(characterId: String): List<SharedTimelineEvent> =
        contextTimelineTurns(characterId).flatMap { it.events }


    private fun memoryEligibleTimelineEvents(characterId: String): List<SharedTimelineEvent> =
        SharedExperienceTimeline.withoutRecalledGroupSpeech(SharedExperienceTimeline.all(characterId))
            .filterNot { event ->
                (event.channel == "私聊" && event.speaker == "系统") ||
                    event.source == "owner-phone-inspection"
            }

 
    private fun readExtractionResult(
        result: Result<com.jiacimu.lulu.ai.ModelReply>,
        characterId: String,
        sources: List<SharedTimelineEvent>,
    ): ParsedMemoryResponse {
        val reply = result.getOrElse { error ->
            if (error is IllegalStateException && error.message == "模型没有返回可读取的内容")
                return ParsedMemoryResponse(hasRejectedRows = true)
            throw error
        }
        val parsed = parseMemoryResponse(reply.text, characterId, sources)
        return if (reply.finishReason in setOf("length", "max_tokens"))
            parsed.copy(hasRejectedRows = true) else parsed
    }

    private fun parseMemoryResponse(
        raw: String,
        characterId: String,
        evidenceSnapshot: List<SharedTimelineEvent>,
    ): ParsedMemoryResponse {
        val array = runCatching { decodeMemoryResponseArray(raw) }.getOrElse {
            return ParsedMemoryResponse(hasRejectedRows = true)
        }
        val exactIds = evidenceSnapshot.associateBy { it.id }
        val versionedIds = evidenceSnapshot.associateBy { "${it.id}@${it.revision}" }
        var rejected = false
        val updates = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val kind = when (item.optString("kind").trim().lowercase()) {
                    "fact" -> MemoryKind.Fact
                    "emotion" -> MemoryKind.Emotion
                    "timeline" -> MemoryKind.Timeline
                    else -> null
                }
                val content = item.optString("content").trim()
                val sourceArray = item.optJSONArray("sourceEventIds")
                val sources = sourceArray?.let { ids ->
                    (0 until ids.length()).map { position ->
                        val ref = (ids.opt(position) as? String)?.trim()
                        exactIds[ref] ?: versionedIds[ref]
                    }.distinct()
                }.orEmpty()
                if (kind == null || content.isBlank() || sources.size !in 1..6 || sources.any { it == null }) {
                    rejected = true
                    continue
                }
                val evidence = sources.filterNotNull()
                val sourceIds = evidence.map { it.id }
                val perspective = when (item.optString("perspective").trim().lowercase()) {
                    "subjective" -> MemoryPerspective.Subjective
                    else -> MemoryPerspective.Record // Legacy model output remains compatible.
                }
                // The timeline owner may merely be a group-chat witness. A different
                // speaker's words cannot become this character's own remembered feeling.
                if (perspective == MemoryPerspective.Subjective &&
                    evidence.none(SharedTimelineEvent::isOwnSubjectiveMemorySource)) continue
                val ownFeelingAt = if (kind == MemoryKind.Emotion && perspective == MemoryPerspective.Subjective)
                    evidence.firstOrNull { it.isOwnSubjectiveMemorySource() }?.occurredAt else null
                val occurredAt = item.optString("occurredAt").trim().takeIf(String::isNotBlank)
                    ?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
                    ?: ownFeelingAt ?: evidence.minOf { it.occurredAt }
                val attributedContent = if (evidence.isNotEmpty() && evidence.all {
                    it.evidenceLabel.startsWith("主观表达") || it.evidenceLabel.startsWith("对话记录")
                }) "[表达记录的摘要，非独立执行事实] $content" else content
                add(ExtractedMemoryUpdate(
                    MemoryEntry(
                        id = UUID.randomUUID().toString(),
                        characterId = characterId,
                        content = attributedContent,
                        kind = kind,
                        source = "timeline-events:${sourceIds.joinToString("|")}",
                        occurredAt = occurredAt,
                        createdAt = Instant.now(),
                        strength = item.optInt("strength", 5).coerceIn(1, 10),
                        pinned = false,
                        canRecallProactively = true,
                        tier = parseMemoryTier(item.optString("tier"), kind),
                        subject = item.optString("subject").trim().take(100),
                        slot = item.optString("slot").trim().take(100),
                        sceneKey = item.optString("sceneKey").trim().take(100),
                        measureKind = item.optString("measureKind").trim().take(60),
                        measureValue = if (item.has("measureValue") && !item.isNull("measureValue")) {
                            item.optDouble("measureValue")
                        } else null,
                        measureUnit = item.optString("measureUnit").trim().take(20),
                        perspective = perspective,
                    ),
                    item.optJSONArray("supersedes")?.let { ids ->
                        (0 until ids.length()).map { ids.optString(it) }.filter(String::isNotBlank).distinct()
                    },
                ))
            }
        }
        return ParsedMemoryResponse(updates, rejected)
    }

    private fun mutate(transform: (MemoryStoreState) -> MemoryStoreState) {
        synchronized(lock) {
            val next = transform(state.value)
            prefs?.let { storage ->
                check(storage.edit().putString(KEY_STATE, encode(next).toString()).commit()) { "记忆保存失败，保留批次等待重试" }
            }
            state.value = next
        }
    }

    fun refreshInspection(characterId: String) {
        val current = debugFlow.value
        refreshDebug(
            message = current.message.substringBefore(INSPECTION_MARKER).trimEnd(),
            characterId = characterId,
            readableCount = current.readableCount,
            pendingCount = current.pendingCount,
            batchCount = current.batchCount,
            extracting = current.extracting,
            lastError = current.lastError,
            lastExtractedCount = current.lastExtractedCount,
        )
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
        val baseMessage = message.substringBefore(INSPECTION_MARKER).trimEnd()
        val inspection = inspectionText(characterId)
        debugFlow.value = MemoryDebugState(
            characterId = characterId,
            message = if (inspection.isBlank()) baseMessage else "$baseMessage$INSPECTION_MARKER\n$inspection",
            readableCount = readableCount,
            pendingCount = pendingCount,
            batchCount = batchCount,
            extracting = extracting,
            lastError = lastError,
            lastExtractedCount = lastExtractedCount,
            updatedAt = Instant.now(),
        )
    }

    private fun inspectionText(characterId: String): String {
        val entriesById = state.value.entries.associateBy(MemoryEntry::id)
        val supersessions = MemoryValidityStore.supersessionSnapshot()
            .mapNotNull { (oldId, newId) ->
                val old = entriesById[oldId] ?: return@mapNotNull null
                if (old.characterId != characterId) return@mapNotNull null
                val replacement = entriesById[newId]
                "- ${old.content.take(160)} → ${replacement?.content?.take(160) ?: "memoryId=$newId"}"
            }
            .takeLast(8)
        val jobs = MemoryExtractionJobStore.pending().filter { it.characterId == characterId }
        return buildString {
            appendLine("最近自动整理/状态：${debugFlow.value.lastExtractedCount} 条新增；待处理 ${debugFlow.value.pendingCount} 条。")
            if (supersessions.isEmpty()) appendLine("事实替代：暂无已确认的旧→新关系。")
            else {
                appendLine("事实替代：")
                supersessions.forEach(::appendLine)
            }
            appendLine(MemoryInspectionStore.render(characterId))
            if (jobs.isEmpty()) append("失败/重试作业：无。")
            else {
                appendLine("失败/重试作业：${jobs.size} 个")
                jobs.take(10).forEach { job ->
                    append("- ${job.id}；attempts=${job.attempts}")
                    if (job.lastError.isNotBlank()) append("；${job.lastError.take(180)}")
                    appendLine()
                }
            }
        }.trim()
    }

    private fun encode(value: MemoryStoreState): JSONObject = JSONObject()
        .put("entries", JSONArray().apply { value.entries.forEach { entry -> put(encodeEntry(entry)) } })
        .put(
            "policies",
            JSONObject().apply {
                value.policies.forEach { (characterId, policy) ->
                    put(
                        characterId,
                        encodePolicy(policy),
                    )
                }
            },
        )
        .put("globalPolicy", value.globalPolicy?.let(::encodePolicy) ?: JSONObject.NULL)
        .put("personalPolicies", JSONObject().apply {
            value.personalPolicies.forEach { (id, policy) -> put(id, encodePolicy(policy)) }
        })
        .put("extractionContinuationTurns", JSONObject().apply {
            value.extractionContinuationTurns.forEach { (id, remaining) ->
                if (remaining > 0) put(id, remaining)
            }
        })
        .put("deferredExtraction", JSONObject().apply {
            value.deferredExtraction.forEach { (characterId, retries) ->
                put(characterId, JSONObject().apply {
                    retries.forEach { (id, retry) ->
                        put(id, JSONObject().put("failures", retry.failures).put("retryAt", retry.retryAt.toString()))
                    }
                })
            }
        })
        .put(
            "processedMessageIds",
            JSONObject().apply {
                value.processedMessageIds.forEach { (characterId, ids) -> put(characterId, JSONArray(ids.toList())) }
            },
        )
        .put("forgottenSourceIds", JSONObject().apply {
            value.forgottenSourceIds.forEach { (characterId, ids) -> put(characterId, JSONArray(ids.toList())) }
        })
        .put("immediateProcessedIds", JSONObject().apply {
            value.immediateProcessedIds.forEach { (characterId, ids) -> put(characterId, JSONArray(ids.toList())) }
        })
        .put("deletedMemoryKeys", JSONArray(value.deletedMemoryKeys.toList()))
        .put("deletedMemoryIds", JSONObject().apply {
            value.deletedMemoryIds.forEach { (role, ids) -> put(role, JSONArray(ids.toList())) }
        })

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
                        decodePolicy(item),
                    )
                }
            }
            val personalObject = root.optJSONObject("personalPolicies") ?: JSONObject()
            val personalPolicies = buildMap {
                val keys = personalObject.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    personalObject.optJSONObject(id)?.let { put(id, decodePolicy(it)) }
                }
            }
            val globalPolicy = root.optJSONObject("globalPolicy")?.let(::decodePolicy)
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
                            for (index in 0 until ids.length()) ids.optString(index).takeIf(String::isNotBlank)?.let(::add)
                        },
                    )
                }
            }
            val immediateObject = root.optJSONObject("immediateProcessedIds") ?: JSONObject()
            val immediateProcessed = buildMap<String, Set<String>> {
                immediateObject.keys().forEach { id ->
                    val array = immediateObject.optJSONArray(id) ?: JSONArray()
                    put(id, (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank).toSet())
                }
            }
            val forgottenObject = root.optJSONObject("forgottenSourceIds") ?: JSONObject()
            val forgotten = buildMap<String, Set<String>> {
                forgottenObject.keys().forEach { id ->
                    val array = forgottenObject.optJSONArray(id) ?: JSONArray()
                    put(id, (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank).toSet())
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
                personalPolicies = personalPolicies,
                extractionContinuationTurns = root.optJSONObject("extractionContinuationTurns")
                    ?.let { saved -> saved.keys().asSequence()
                        .associateWith { key -> saved.optInt(key, 0) }
                        .filterValues { it > 0 }
                    }.orEmpty(),
                deferredExtraction = root.optJSONObject("deferredExtraction")?.let { roles ->
                    roles.keys().asSequence().associateWith { role ->
                        val rows = roles.optJSONObject(role) ?: JSONObject()
                        buildMap {
                            rows.keys().forEach { id ->
                                val row = rows.optJSONObject(id) ?: return@forEach
                                val retryAt = runCatching { Instant.parse(row.optString("retryAt")) }.getOrNull()
                                    ?: return@forEach
                                put(id, DeferredMemoryExtraction(row.optInt("failures", 1).coerceIn(1, 5), retryAt))
                            }
                        }
                    }
                }.orEmpty(),
                processedMessageIds = processed,
                immediateProcessedIds = immediateProcessed,
                forgottenSourceIds = forgotten,
                deletedMemoryKeys = deleted,
                deletedMemoryIds = root.optJSONObject("deletedMemoryIds")?.let { roles ->
                    roles.keys().asSequence().associateWith { role ->
                        val ids = roles.optJSONArray(role) ?: JSONArray()
                        (0 until ids.length()).map { ids.optString(it) }.filter(String::isNotBlank).toSet()
                    }
                }.orEmpty(),
            )
        }.getOrDefault(MemoryStoreState())
    }

    private fun encodePolicy(policy: MemoryPolicy): JSONObject = JSONObject()
        .put("excludedRecentMessages", policy.excludedRecentMessages)
        .put("readableThreshold", policy.readableThreshold)
        .put("autoSummarize", policy.autoSummarize)
        .put("semanticTurnPolicyVersion", 1)

    /** Old numbers counted raw events, so silently reusing them after the
     * semantic-turn migration would preserve a 25/20 configuration with a
     * completely different meaning. One-time migration uses the owner's
     * requested 5 protected + 15 extraction turns. Explicit future custom
     * settings are versioned and remain unchanged on app restart/import. */
    private fun decodePolicy(item: JSONObject): MemoryPolicy {
        val semantic = item.optInt("semanticTurnPolicyVersion", 0) >= 1
        return MemoryPolicy(
            excludedRecentMessages = if (semantic) item.optInt("excludedRecentMessages", 5)
                .coerceAtLeast(0) else 5,
            readableThreshold = if (semantic) item.optInt("readableThreshold", 15)
                .coerceAtLeast(1) else 15,
            autoSummarize = item.optBoolean("autoSummarize", true),
        )
    }

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
        .put("tier", entry.tier.name)
        .put("subject", entry.subject)
        .put("slot", entry.slot)
        .put("sceneKey", entry.sceneKey)
        .put("measureKind", entry.measureKind)
        .put("measureValue", entry.measureValue ?: JSONObject.NULL)
        .put("measureUnit", entry.measureUnit)
        .put("sourceRevisions", JSONObject(entry.sourceRevisions))
        .put("perspective", entry.perspective.name)

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
        tier = parseMemoryTier(item.optString("tier"), runCatching { MemoryKind.valueOf(item.optString("kind")) }.getOrDefault(MemoryKind.Fact)),
        subject = item.optString("subject"),
        slot = item.optString("slot"),
        sceneKey = item.optString("sceneKey"),
        measureKind = item.optString("measureKind"),
        measureValue = if (item.has("measureValue") && !item.isNull("measureValue")) item.optDouble("measureValue") else null,
        measureUnit = item.optString("measureUnit"),
        perspective = runCatching { MemoryPerspective.valueOf(item.optString("perspective")) }
            .getOrDefault(MemoryPerspective.Record),
        sourceRevisions = item.optJSONObject("sourceRevisions")?.let { revisions ->
            revisions.keys().asSequence().associateWith { revisions.optLong(it) }
        }.orEmpty(),
    )

    private companion object {
        const val PREFS_NAME = "lulu_memory_store"
        const val KEY_STATE = "state_v1"
        const val INSPECTION_MARKER = "\n\n【记忆检查】"
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
    val personalPolicies: Map<String, MemoryPolicy> = emptyMap(),
    val processedMessageIds: Map<String, Set<String>> = emptyMap(),
    val extractionContinuationTurns: Map<String, Int> = emptyMap(),
    val deferredExtraction: Map<String, Map<String, DeferredMemoryExtraction>> = emptyMap(),
    val immediateProcessedIds: Map<String, Set<String>> = emptyMap(),
    val forgottenSourceIds: Map<String, Set<String>> = emptyMap(),
    val deletedMemoryKeys: Set<String> = emptySet(),
    val deletedMemoryIds: Map<String, Set<String>> = emptyMap(),
)

private data class DeferredMemoryExtraction(val failures: Int, val retryAt: Instant)

private fun MemoryStoreState.withExtractionRetry(
    characterId: String,
    sourceIds: Set<String>,
    deferred: Boolean,
    now: Instant,
): MemoryStoreState {
    val previous = deferredExtraction[characterId].orEmpty()
    val next = if (!deferred) previous - sourceIds else {
        val failures = ((sourceIds.mapNotNull(previous::get).maxOfOrNull { it.failures } ?: 0) + 1).coerceAtMost(5)
        val delay = when (failures) {
            1 -> 15L
            2 -> 60L
            3 -> 240L
            4 -> 720L
            else -> 1440L
        }
        previous + sourceIds.associateWith { DeferredMemoryExtraction(failures, now.plusSeconds(delay * 60)) }
    }
    return copy(deferredExtraction = if (next.isEmpty()) deferredExtraction - characterId
        else deferredExtraction + (characterId to next))
}

private fun Int?.orEmptyCount(): Int = (this ?: 0).coerceAtLeast(0)

private fun MemoryStoreState.resolvedPolicy(characterId: String): MemoryPolicy =
    personalPolicies[characterId] ?: globalPolicy ?: policies[characterId] ?: MemoryPolicy()

private fun MemoryEntry.dedupeKey(): String = kind.name + ":" + perspective.name + ":" + memoryIdentityKey()

private fun MemoryEntry.legacyScopedMemoryKey(): String = characterId + ":" + memoryIdentityKey()
private fun MemoryEntry.scopedMemoryKey(): String =
    characterId + ":" + kind.name + ":" + perspective.name + ":" + memoryIdentityKey()

/** Existing users' old unscoped tombstones remain effective after this migration. */
private fun MemoryEntry.isOwnerDeleted(deletedKeys: Set<String>): Boolean =
    scopedMemoryKey() in deletedKeys || legacyScopedMemoryKey() in deletedKeys

private fun MemoryEntry.memorySourceEventIds(): List<String> = when {
    source.startsWith("timeline-events:") -> source.removePrefix("timeline-events:").split('|')
    source.startsWith("timeline-batch:") -> source.removePrefix("timeline-batch:").split('|')
    else -> emptyList()
}.map(String::trim).filter(String::isNotBlank).distinct()

private fun MemoryEntry.memoryIdentityKey(): String = content
    .lowercase()
    .replace(Regex("^(用户明确表达过边界|用户纠正过一件事|用户明确表达过偏好)[：:]?"), "")
    .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
    .take(320)

/** Common gate for ingestion and daily maintenance: an identical heading or
 * first 320 characters cannot retire a different occasion, quantitative fact
 * or qualifying sentence. The weekly model review has its own stricter gate. */
internal fun memoriesEquivalentForMaintenance(left: MemoryEntry, right: MemoryEntry): Boolean {
    if (left.kind != right.kind || left.perspective != right.perspective ||
        left.characterId != right.characterId) return false
    // Hand-edited records are never consumed by automatic housekeeping.
    if (!left.source.startsWith("timeline-events:") ||
        !right.source.startsWith("timeline-events:")) return false
    if (left.subject.trim() != right.subject.trim() ||
        left.slot.trim() != right.slot.trim() ||
        left.sceneKey.trim() != right.sceneKey.trim() ||
        left.measureKind.trim() != right.measureKind.trim() ||
        left.measureValue != right.measureValue ||
        left.measureUnit.trim() != right.measureUnit.trim() ||
        left.occurredAt != right.occurredAt) return false
    if (left.kind != MemoryKind.Fact &&
        left.sourceIds().intersect(right.sourceIds().toSet()).isEmpty()) return false
    // Unlike the tombstone identity key, compare the entire statement, not
    // a 320-character prefix. Otherwise a late negation or condition vanishes.
    return left.content.lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), "") ==
        right.content.lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
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
        tier = if (primary.tier == MemoryTier.Core || duplicate.tier == MemoryTier.Core) MemoryTier.Core else primary.tier,
        subject = primary.subject.ifBlank { duplicate.subject },
        slot = primary.slot.ifBlank { duplicate.slot },
        // Never restore recall permission merely because a duplicate was extracted later.
        canRecallProactively = primary.canRecallProactively && duplicate.canRecallProactively,
        source = mergeMemoryProvenance(primary.source, duplicate.source),
        sourceRevisions = primary.sourceRevisions + duplicate.sourceRevisions,
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

/** Tolerate prose/fences around a complete array, but never salvage truncated JSON. */
/** Decode a model-authored JSON array without paying for a second request
 * simply because the relay wrapped it in prose, fences, or a conventional
 * object envelope. Only accept complete arrays of objects (or genuine []).
 * Individual factual/source validity is still checked by the caller. */
internal fun decodeMemoryResponseArray(raw: String): JSONArray {
    val clean = raw.trim()
    fun valid(rows: JSONArray): Boolean = (0 until rows.length())
        .all { index -> rows.optJSONObject(index) != null }
    runCatching { JSONArray(clean) }.getOrNull()?.takeIf(::valid)?.let { return it }
    val objectText = clean.removePrefix("```json").removePrefix("```")
        .removeSuffix("```").trim()
    runCatching { JSONObject(objectText) }.getOrNull()?.let { envelope ->
        for (name in listOf("memories", "entries", "items", "result", "data",
            "updates", "changes")) {
            envelope.optJSONArray(name)?.takeIf(::valid)?.let { return it }
        }
    }
    // Recover a complete outer array surrounded by display prose. An inner
    // array from a truncated reply is never an empty successful extraction.
    var start = -1
    var depth = 0
    var quoted = false
    var escaped = false
    for ((index, ch) in clean.withIndex()) {
        if (quoted) {
            if (escaped) escaped = false
            else if (ch == '\\') escaped = true
            else if (ch == '"') quoted = false
            continue
        }
        if (depth > 0 && ch == '"') quoted = true
        if (ch == '[' || ch == '{') {
            if (depth == 0) start = index
            depth++
        } else if (depth > 0 && (ch == ']' || ch == '}')) {
            depth--
            if (depth == 0 && clean[start] == '[' && ch == ']') {
                runCatching { JSONArray(clean.substring(start, index + 1)) }
                    .getOrNull()?.takeIf(::valid)?.let { return it }
            }
        }
    }
    throw IllegalArgumentException("模型未返回完整且有效的记忆 JSON 数组")
}


internal fun parseMemoryTier(raw: String, kind: MemoryKind): MemoryTier =
    if (kind != MemoryKind.Fact) MemoryTier.Episode else when (raw.lowercase()) {
        "core" -> MemoryTier.Core
        else -> MemoryTier.Stable
    }
