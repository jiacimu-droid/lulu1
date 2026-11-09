package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.CompanionContextMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.time.Instant

/** Versioned mailbox: in-flight reflection cannot clear a newer experience request. */
internal object DevelopmentReflectionGeneration {
    @Synchronized fun request(p: android.content.SharedPreferences, characterId: String): Long {
        val key = "generation:$characterId"
        val next = p.getLong(key, 0L) + 1L
        check(p.edit().putLong(key, next).putBoolean("pending:$characterId", true).commit()) {
            "无法持久化新成长经历"
        }
        return next
    }

    fun snapshot(p: android.content.SharedPreferences, characterId: String): Long =
        p.getLong("generation:$characterId", 0L)

    /** Querying pending work on wake is read-only: a wake is not new evidence. */
    @Synchronized fun pendingGeneration(p: android.content.SharedPreferences, characterId: String): Long? =
        snapshot(p, characterId).takeIf { p.getBoolean("pending:$characterId", false) }

    @Synchronized fun acknowledge(p: android.content.SharedPreferences, characterId: String, observed: Long): Boolean {
        if (snapshot(p, characterId) != observed) return false
        return p.edit().putBoolean("pending:$characterId", false).commit()
    }
}

/** A paid growth review is NOT another full chat/perception prompt.
 * Select independent witnessed evidence across source kinds; the complete
 * timeline and full owner-authored persona stay in their authoritative stores.
 * Truncation here limits ONLY the optional model's working copy, not facts,
 * memories or the permanent persona. */
internal object GrowthReflectionInput {
    fun personaBrief(full: String): String {
        if (full.length <= 6_000) return full
        return full.take(3_900) +
            "\n【以下为较长原始设定的省流摘录；所有未展示的稳定人设仍有效，不可修改】\n" +
            full.takeLast(1_900)
    }

    /** Immediate speech is already integrated in the normal role decision.
     * Long-term trait consolidation waits for independent *real* experience
     * across time, not several bubbles or automated online-awareness ticks. */
    fun shouldConsolidate(
        newEvents: List<SharedTimelineEvent>,
    ): Boolean {
        val meaningful = newEvents.filter { event ->
            event.evidenceKind == EventEvidenceKind.UserStatement ||
                event.evidenceKind == EventEvidenceKind.ToolResult ||
                (event.evidenceKind == EventEvidenceKind.Observation &&
                    !event.source.startsWith("online-awareness") &&
                    !event.source.startsWith("background-awareness"))
        }.distinctBy { it.id }
        if (meaningful.size < 6) return false
        val oldest = meaningful.minOf(SharedTimelineEvent::occurredAt)
        val newest = meaningful.maxOf(SharedTimelineEvent::occurredAt)
        val differentDays = meaningful.map {
            it.occurredAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        }.distinct().size >= 2
        val independentToolResults = meaningful.count {
            it.evidenceKind == EventEvidenceKind.ToolResult
        }
        return differentDays ||
            java.time.Duration.between(oldest, newest).toHours() >= 4 ||
            independentToolResults >= 3
    }

    fun select(events: List<SharedTimelineEvent>): List<SharedTimelineEvent> {
        val zone = java.time.ZoneId.systemDefault()
        val owner = events.filter { it.evidenceKind == EventEvidenceKind.UserStatement }
        val distinctOldDates = owner.asReversed()
            .distinctBy { it.occurredAt.atZone(zone).toLocalDate() }.take(4)
        val tool = events.filter { it.evidenceKind == EventEvidenceKind.ToolResult }.takeLast(6)
        val observed = events.filter { it.evidenceKind == EventEvidenceKind.Observation }.takeLast(6)
        val spoken = events.filter {
            it.evidenceKind == EventEvidenceKind.CharacterStatement &&
                TimelineStatementOwnership.isOwnCharacterStatement(it)
        }.takeLast(4)
        val culture = events.filter { it.channel.contains("阅读") || it.channel.contains("游戏") }
            .takeLast(4)
        val core = (events.takeLast(7) + owner.takeLast(7) + distinctOldDates +
            tool + observed + spoken + culture).distinctBy { it.id }
        return core.sortedBy { it.occurredAt }
    }

    fun evidenceText(events: List<SharedTimelineEvent>): String =
        events.joinToString("\n") { event ->
            "eventId=${event.id} revision=${event.revision} atLocal=" +
                event.occurredAt.atZone(java.time.ZoneId.systemDefault()).toString() +
                " kind=${event.evidenceKind} source=${event.source} channel=${event.channel} " +
                "ownStatement=${TimelineStatementOwnership.isOwnCharacterStatement(event)} " +
                CharacterExperienceLearning.evidenceText(event).take(280)
        }

    /** Existing dispositions are a *reference index*, not an invitation to
     * resend the character's whole inner life, responsibilities and world. */
    fun currentSlots(characterId: String): String =
        CharacterDevelopmentStore.active(characterId).takeLast(16)
            .joinToString("\n") { record ->
                "${record.kind}/${record.slot}: ${record.content.take(130)}"
            }
}

private val focusedGrowthReflectionInstruction = """
    这是低频、跨经历的长期成长合并，不是一次新的聊天/心理决策。普通本轮情绪、自我修正、
    想法与行动已经在主感知中记录，不需要在这里重新决定或逐句总结。
    仅依据给定的可核验原始 eventId、类型、时间和真实经历。没有跨经历的新变化返回 []。
    返回纯 JSON 数组，最多3项，禁止多余解释。普通项：
    {"disposition":"upsert|retire","slot":"稳定主题键",
    "kind":"Interest|Preference|Habit|UserPreference|UserHabit|UserFact|UserImpression|ExpressionHabit|Judgment|SituationalPattern|NarrativeMeaning|RelationshipRoutine|VerifiedMethod",
    "content":"可以被后来经历修订的具体认识","evidenceIds":["真实ID"],"counterIds":[]}
    工具经验可用 kind=ToolExperience，带真实 situationEvidenceId、actionEvidenceId、
    understanding、applicableWhen、avoidWhen、uncertainty，后来明确用户评价才可附 feedbackEvidenceId。
    稳定人设不可改写；新增的兴趣、表达习惯、判断方式只能是从真实经验逐渐形成的可变倾向。
    UserFact/Preference/Habit 只能来自本角色亲见的用户原话；近似照片、他人的私聊、角色猜测、
    书中人物、网页材料都不是用户事实。UserImpression 必须有至少两条独立可见依据，
    标记为尚未证实的主观印象。客观操作规律须跨至少三个不同自然日才能写成 UserHabit。
    Interest 初步萌芽须至少两次独立真实接触，稳定兴趣须更多证据；情境习惯和表达习惯
    须至少三次独立经历，不因一句自述或偶发模仿升级为固定人格。
    NarrativeMeaning 至少来自两个不同原始经历；角色发言证明说过或感受过什么，
    不能代替实际阅读、游戏、见面、授权工具完成或用户满意的执行回执。
    VerifiedMethod 必须同时有实际角色行动和后来用户明确正面评价的来源，
    工具调用成功、发消息、用户沉默不等于对方已好转。新反例优先更新原 slot；
    retire 需真实 counterIds，不能凭一次不同表现撤销稳定倾向。
    用户身份、事实、更正与拒绝按来源验证；不得推断敏感个人特征或编造经历。
    保持现在的角色本人性格，不强迫每个分类都产生结果。所有证据 ID 必须确实在输入中。
""".trimIndent()

/** Incremental reflection is detached from real-time response and limited to one batch per hour. */
object CharacterDevelopmentRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var prefs: android.content.SharedPreferences? = null
    private var appContext: android.content.Context? = null
    private val statusChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revisions: kotlinx.coroutines.flow.StateFlow<Long> = statusChanges

    fun lastStatus(characterId: String): String =
        prefs?.getString("status:$characterId", "").orEmpty()

    private fun note(characterId: String, summary: String) {
        val whenLocal = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .format(Instant.now().atZone(java.time.ZoneId.systemDefault()))
        prefs?.edit()?.putString("status:$characterId", "$whenLocal · $summary")?.apply()
        statusChanges.value += 1
    }

    /** Resume the existing pending generation, never create another generation on wake. */
    fun resumePending(characterId: String) {
        val p = prefs ?: return
        if (characterId.isNotBlank() &&
            DevelopmentReflectionGeneration.pendingGeneration(p, characterId) != null)
            schedulePending(characterId)
    }

    fun initialize(context: android.content.Context) {
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences("lulu_development_reflection", android.content.Context.MODE_PRIVATE)
    }

    // Long-term trait consolidation is NOT the reaction to a single message.
    // Coalesce the evidence and let the normal in-turn decision handle immediate
    // remorse, multiple thoughts, wishes and open concerns. These occasional
    // cross-event summaries must never compete with an active chat or call.
    private val waitingJobs = mutableMapOf<String, Job>()
    private val waitingLock = Any()

    fun request(characterId: String) {
        if (characterId.isBlank() || prefs == null) return
        DevelopmentReflectionGeneration.request(checkNotNull(prefs), characterId)
        schedulePending(characterId)
    }

    private fun schedulePending(characterId: String) {
        synchronized(waitingLock) {
            if (waitingJobs[characterId]?.isActive == true) return
            val job = scope.launch {
                delay(90_000L)
                // Wait for an actual quiet interval; don't steal the model from
                // a user while they are typing or speaking. The next experience
                // will queue another chance if this session never gets quiet.
                repeat(6) {
                    val inCall = CompanionPresenceStore.isInCall(characterId)
                    val inChat = MigratedDomainStores.chat.conversations.value.any { conv ->
                        (conv.characterId == characterId ||
                            conv.groupChat?.members?.any { it.characterId == characterId } == true) &&
                            ChatGenerationActivity.isRunning(conv.id)
                    }
                    if (!inCall && !inChat) {
                        if (!CompanionOnlineStore.isOnline(characterId)) {
                            note(characterId, "休眠中，醒来再整理")
                            return@launch
                        }
                        mutex.withLock {
                            if (CompanionOnlineStore.isOnline(characterId)) reflect(characterId)
                        }
                        return@launch
                    }
                    delay(45_000L)
                }
            }
            waitingJobs[characterId] = job
            job.invokeOnCompletion {
                synchronized(waitingLock) {
                    if (waitingJobs[characterId] === job) waitingJobs.remove(characterId)
                }
            }
        }
    }

    private suspend fun reflect(characterId: String) {
        val p = prefs ?: return
        val observedGeneration = DevelopmentReflectionGeneration.snapshot(p, characterId)
        val now = Instant.now().toEpochMilli()
        val persona = CharacterRuntime.personaConstraintSnapshot(characterId)
        val sameDefinition = p.getString("persona:$characterId", "") == persona
        if (sameDefinition && now - p.getLong("attempt:$characterId", 0) < 3_600_000) {
            note(characterId, "成长反思间隔中")
            return
        }
        val timeline = SharedExperienceTimeline.all(characterId)
        // Consolidating a repeated USER routine is distinct from learning the
        // companion's own Habit. Sample real messages across separate days so
        // a week of short conversations does not push out all recurring cues.
        // This reuses the existing low-frequency reflection API call, never
        // requests an additional habit-classification model.
        val userStatements = timeline.filter {
            it.evidenceKind == EventEvidenceKind.UserStatement
        }
        val earlierDailyExamples = userStatements.dropLast(12).asReversed()
            .distinctBy {
                it.occurredAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            }.take(14)
        // Balance exposure SOURCES. Chat volume must never erase actual books,
        // games, world encounters and independently visited information sources.
        // These are real records, not invented offline history or quotas.
        val toolEpisodes = CharacterExperienceLearning.episodeEvidence(timeline)
        val routinePatterns = UserRoutinePatterns.detect(timeline, Instant.ofEpochMilli(now))
        // Supply independent dates even if many recent chat or tool events intervene.
        val routineExamples = routinePatterns.take(5).flatMap { it.examples }
        val events = (toolEpisodes + routineExamples + timeline.filter { it.channel.startsWith("独自阅读") }.takeLast(10) +
            timeline.filter { it.channel.startsWith("独自游戏") }.takeLast(8) +
            timeline.filter { it.channel.startsWith("数字世界") }.takeLast(10) +
            timeline.filter { it.channel.startsWith("现实世界窗口") }.takeLast(8) +
            timeline.filter { it.isDevelopmentExposure() &&
                it.evidenceKind != EventEvidenceKind.UserStatement }.takeLast(10) +
            (earlierDailyExamples + userStatements.takeLast(12)) +
            timeline.filter { it.evidenceKind == EventEvidenceKind.CharacterStatement &&
                it.source !in setOf("journal:own", "moment:self") }.takeLast(16) +
            timeline.filter { it.evidenceKind == EventEvidenceKind.CharacterStatement &&
                it.source in setOf("journal:own", "moment:self") }.takeLast(8))
            .distinctBy { it.id }.sortedBy { it.occurredAt }
        // One direct self-description (e.g. a body feature or personal story)
        // deserves review too; the model judges its meaning, not a keyword gate.
        val directOwnerSignal = events.any { event ->
            event.evidenceKind == EventEvidenceKind.UserStatement &&
                (event.channel == "私聊" || event.channel.contains("电话") ||
                    event.channel.contains("见面"))
        }
        if (independentDevelopmentExposures(events).size < 2 && !directOwnerSignal && toolEpisodes.isEmpty()) {
            DevelopmentReflectionGeneration.acknowledge(p, characterId, observedGeneration)
            note(characterId, "真实经历还不足")
            return
        }
        val fingerprint = persona + "\n" + events.joinToString("|") { "${it.id}:${it.revision}" }
        val lastReviewed = p.getString("batch:$characterId", "").orEmpty()
        if (fingerprint == lastReviewed) {
            DevelopmentReflectionGeneration.acknowledge(p, characterId, observedGeneration)
            note(characterId, "暂无新经历")
            return
        }
        // The normal character turn has already interpreted each individual
        // message. Long-term trait formation needs a meaningful NEW group of
        // experiences, not another model request after every single chat.
        // This is a batch threshold, never a daily quota: all evidence remains.
        val seen = lastReviewed.substringAfter('\n', "").split('|').toSet()
        val newSignals = events.filter { "${it.id}:${it.revision}" !in seen }
        // Chat bubbles, passive presence observations and identical provider
        // retries must not count as several independent long-term experiences.
        // Real owner facts remain available in the memory system immediately.
        if (!GrowthReflectionInput.shouldConsolidate(newSignals)) {
            note(characterId, "长期成长证据尚需跨经历合并；本轮主决策已处理即时反应")
            return
        }
        // Resident learning is optional paid housekeeping; it must not take
        // the first call reserved for the native citizen's own daily plan.
        if (characterId.startsWith("resident:") && appContext?.let {
                residentMemoryBudgetDepleted(it, characterId, Instant.ofEpochMilli(now))
            } != false) {
            note(characterId, "居民日计划额度优先；成长证据保留待后续整理")
            return
        }
        val growthEvidence = GrowthReflectionInput.select(events)
        val growthFacts = buildString {
            appendLine("【不可改写的人格原始设定摘录；完整版本由原 Store 保留并核验】")
            appendLine(GrowthReflectionInput.personaBrief(persona))
            appendLine("【当前可修订长期成长目录，仅用于避免重复】")
            appendLine(GrowthReflectionInput.currentSlots(characterId))
            appendLine("【本人已有的相关方法与来源】")
            appendLine(CharacterLearningLedger.context(characterId,
                growthEvidence.takeLast(5).joinToString(" ") { it.content.take(70) },
                compact = true))
            appendLine(UserRoutinePatterns.reflectionContext(routinePatterns.take(3)))
            appendLine("【本轮选取的可核验原始经历；完整版仍在时间线】")
            appendLine(GrowthReflectionInput.evidenceText(growthEvidence))
        }
        LuluRepositories.performance.recordTiming("成长反思输入规划", 0,
            "角色=${characterId.take(36)};候选=${events.size};投入=${growthEvidence.size};" +
                "factsChars=${growthFacts.length};rulesChars=${focusedGrowthReflectionInstruction.length}")
        p.edit().putLong("attempt:$characterId", now).putString("persona:$characterId", persona).commit()
        val reply = LuluAiServices.gateway.generate(characterId,
            facts = growthFacts,
            instruction = focusedGrowthReflectionInstruction,
            source = "增量成长反思", title = "角色成长", maxTokens = 1400,
            contextMode = CompanionContextMode.Isolated,
            connectionOverride = MemoryModelRuntime.extractionConnection()).getOrNull() ?: run {
                // A down local proxy must not become a paid reflection attempt
                // every five minutes. Keep the existing hourly attempt budget,
                // pending generation and evidence unchanged for the next wake.
                note(characterId, "模型请求失败，经历仍待整理")
                return
            }
        runCatching {
            val array = decodeMemoryResponseArray(reply.text)
            var accepted = 0
            val considered = minOf(array.length(), 3)
            for (i in 0 until considered) {
                val item = array.getJSONObject(i)
                fun ids(key: String): List<String> {
                    val a = item.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).map { a.getString(it) }
                }
                if (item.optString("kind") == "ToolExperience") {
                    val ids = listOf("situationEvidenceId", "actionEvidenceId", "feedbackEvidenceId")
                        .map { item.optString(it) }.filter(String::isNotBlank)
                    // Never let a stale model response bless revised/deleted evidence.
                    if (ids.all { id -> events.any { it.id == id } } && ids.all { id ->
                            val expected = events.first { it.id == id }
                            SharedExperienceTimeline.eventsByIds(characterId, listOf(id)).firstOrNull()?.revision == expected.revision
                        } && CharacterRuntime.personaConstraintSnapshot(characterId) == persona &&
                        CharacterLearningLedger.recordExperience(characterId, item)) accepted++
                    continue
                }
                val slot = item.getString("slot")
                val disposition = item.optString("disposition", "upsert").lowercase()
                val changed = if (disposition == "retire") {
                    CharacterDevelopmentStore.retireSlotWithCounterEvidence(
                        characterId, slot, ids("counterIds"), persona, events.associate { it.id to it.revision },
                    )
                } else {
                    CharacterDevelopmentStore.applyProposal(characterId, slot,
                        DevelopmentKind.valueOf(item.getString("kind")), item.getString("content"),
                        ids("evidenceIds"), ids("counterIds"), persona, events.associate { it.id to it.revision })
                }
                if (changed) accepted++
            }
            check(p.edit().putString("batch:$characterId", fingerprint).commit())
            DevelopmentReflectionGeneration.acknowledge(p, characterId, observedGeneration)
            note(characterId, when {
                accepted > 0 -> "形成 $accepted 项变化"
                considered > 0 -> "候选未通过经历校验"
                else -> "这次没有可靠变化"
            })
        }.onFailure {
            // A possibly billed, malformed response is not an invitation to
            // regenerate the same evidence immediately. Leave pending work
            // intact so the next eligible real wake may reconcile it.
            note(characterId, "反思结果解析失败，经历仍待整理")
        }
    }
}
