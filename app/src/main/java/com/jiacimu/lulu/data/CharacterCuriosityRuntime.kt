package com.jiacimu.lulu.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Questions are subjective; experiences are executor receipts. Neither creates a
 * permanent character trait without the existing evidence-driven development system.
 * Per-character state survives restarts, can be revised, and retracts deleted sources.
 */
internal object CharacterCuriosityRuntime {
    private const val PREFS = "lulu_curiosity_threads_v1"
    private var prefs: SharedPreferences? = null
    private val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revisions: kotlinx.coroutines.flow.StateFlow<Long> = changes

    @Synchronized fun initialize(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    @Synchronized fun clear(characterId: String) {
        if (characterId.isNotBlank() && prefs?.edit()?.remove(characterId)?.commit() == true) changes.value += 1
    }

    @Synchronized fun snapshot(characterId: String): JSONObject = runCatching {
        JSONObject(prefs?.getString(characterId, "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    private fun save(characterId: String, state: JSONObject) {
        check(prefs?.edit()?.putString(characterId, state.toString())?.commit() == true) { "探索线索未能保存" }
        changes.value += 1
    }

    private fun normalized(value: String) = value.lowercase().filter(Char::isLetterOrDigit)


    private fun entries(array: JSONArray?): List<JSONObject> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }

    private fun sameQuestion(a: JSONObject, b: JSONObject): Boolean =
        normalized(a.optString("topic")) == normalized(b.optString("topic")) &&
            normalized(a.optString("question")) == normalized(b.optString("question"))

    private fun matching(existing: List<JSONObject>, proposal: JSONObject?): JSONObject? {
        val p = proposal ?: return null
        val requested = p.optString("threadId").trim()
        if (requested.isNotBlank()) return existing.firstOrNull { it.optString("threadId") == requested }
        return existing.firstOrNull { sameQuestion(it, p) }
    }

    /** Old records without an ID retain a stable identity based on their original source. */
    private fun identity(record: JSONObject): String =
        record.optString("threadId").ifBlank {
            "legacy-" + listOf(record.optString("topic"), record.optString("question"),
                record.optString("observedId")).joinToString(":").hashCode().toUInt().toString(16)
        }

    private fun sameIdentity(a: JSONObject, b: JSONObject): Boolean =
        identity(a) == identity(b) || (
            a.optString("threadId").isBlank() && b.optString("threadId").isBlank() &&
                sameQuestion(a, b))

    private fun proposalFields(proposal: JSONObject?): List<String>? {
        val p = proposal ?: return null
        val topic = p.optString("topic").replace(Regex("\\s+"), " ").trim().take(90)
        val question = p.optString("question").replace(Regex("\\s+"), " ").trim().take(180)
        if (normalized(topic).length < 2 || question.length < 3) return null
        return listOf(topic, question, p.optString("why").trim().take(180),
            p.optString("nextStep").trim().take(180))
    }

    private fun linkedMotiveId(characterId: String, proposal: JSONObject?): String {
        val id = proposal?.optString("motiveId").orEmpty().trim()
        if (id.isBlank()) return ""
        return id.takeIf { requested ->
            CharacterMotiveState.active(
                CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives")
            ).any { it.optString("id") == requested }
        }.orEmpty()
    }

    private fun validQuestion(q: JSONObject, sources: Map<String, SharedTimelineEvent>): Boolean {
        val event = sources[q.optString("observedId")] ?: return false
        if (!CharacterExplorationContinuity.canGroundQuestion(event)) return false
        if (event.revision != q.optLong("observedRevision", event.revision)) return false
        val parentId = q.optString("parentEvidenceId")
        if (parentId.isBlank()) return true
        val parent = sources[parentId] ?: return false
        return parent.revision == q.optLong("parentRevision", parent.revision)
    }

    private fun pendingQuestions(role: String, root: JSONObject): List<JSONObject> {
        val q = root.optJSONArray("questions") ?: JSONArray()
        val entries = (0 until q.length()).mapNotNull(q::optJSONObject)
        val sources = SharedExperienceTimeline.eventsByIds(role, entries.flatMap {
            listOf(it.optString("observedId"), it.optString("parentEvidenceId"))
        }).associateBy { it.id }
        return entries.filter { validQuestion(it, sources) }
    }

    fun visibleQuestions(role: String): List<JSONObject> = pendingQuestions(role, snapshot(role))

    fun activeThreads(role: String, root: JSONObject = snapshot(role)): List<JSONObject> {
        val pending = pendingQuestions(role, root)
        val threads = root.optJSONArray("threads") ?: return emptyList()
        return entries(threads).filter { thread ->
            thread.optString("status") == "exploring" &&
                pending.none { sameIdentity(it, thread) } && lastRealStep(role, thread) != null
        }
    }

    private fun lastRealStep(role: String, thread: JSONObject): JSONObject? {
        val steps = thread.optJSONArray("steps") ?: return null
        for (i in steps.length() - 1 downTo 0) {
            val step = steps.optJSONObject(i) ?: continue
            val event = CharacterActionEvidence.resolve(role, step.optString("evidenceId")) ?: continue
            if (event.revision == step.optLong("revision", event.revision) &&
                CharacterActionEvidence.supports(event, step.optString("action"))) return step
        }
        return null
    }

    /** IDs already visible in the shared brief context, not an unrestricted history search. */
    fun continuityEvidenceIds(role: String): List<String> {
        val root = snapshot(role)
        val threads = root.optJSONArray("threads") ?: JSONArray()
        return pendingQuestions(role, root).takeLast(3).map { it.optString("observedId") } +
            (0 until threads.length()).mapNotNull(threads::optJSONObject)
                .filter { it.optString("status") == "exploring" }.takeLast(2)
                .mapNotNull { lastRealStep(role, it)?.optString("evidenceId") }
    }

    private fun previousResult(role: String, root: JSONObject, question: JSONObject): String {
        val threads = entries(root.optJSONArray("threads"))
        val exact = matching(threads, question)
        val legacy = threads.filter { normalized(it.optString("topic")) ==
            normalized(question.optString("topic")) }.singleOrNull()
        return (exact ?: legacy)?.let { lastRealStep(role, it)?.optString("receipt") }.orEmpty()
    }

    /** A genuine observed situation may make the character wonder; it is NOT progress. */
    @Synchronized fun recordInquiry(
        characterId: String, proposal: JSONObject?, observedId: String,
        observedText: String, now: Instant = Instant.now(),
    ): Boolean {
        if (prefs == null || characterId.isBlank() || observedId.isBlank() || observedText.isBlank()) return false
        val observed = SharedExperienceTimeline.eventsByIds(characterId, listOf(observedId)).firstOrNull() ?: return false
        if (observed.occurredAt.isAfter(now) || !CharacterExplorationContinuity.canGroundQuestion(observed)) return false
        val fields = proposalFields(proposal) ?: return false
        val root = snapshot(characterId)
        val old = entries(root.optJSONArray("questions"))
        val existingThreads = entries(root.optJSONArray("threads"))
        val all = old + existingThreads
        val parentId = proposal?.optString("parentThreadId").orEmpty().trim()
        val parentTopic = proposal?.optString("parentTopic").orEmpty().trim()
        val parent = when {
            parentId.isNotBlank() -> all.firstOrNull { identity(it) == parentId } ?: return false
            parentTopic.isNotBlank() -> all.filter {
                normalized(it.optString("topic")) == normalized(parentTopic)
            }.distinctBy(::identity).singleOrNull() ?: return false
            else -> null
        }
        val existing = matching(old, proposal)
        val continuingThread = matching(existingThreads, proposal)
        // Legacy result callbacks do not carry threadId. If a new question
        // directly follows the sole verified step for a topic, revise that
        // question's identity rather than spawning a phantom sibling.
        val continuedFromResult = if (existing == null && continuingThread == null &&
            proposal?.optString("threadId").isNullOrBlank()) {
            existingThreads.filter { thread ->
                normalized(thread.optString("topic")) == normalized(fields[0]) &&
                    lastRealStep(characterId, thread)?.optString("evidenceId") == observedId
            }.singleOrNull()
        } else null
        val assignedId = existing?.let(::identity) ?: continuingThread?.let(::identity)
            ?: continuedFromResult?.let(::identity) ?: UUID.randomUUID().toString()
        if (parent != null) {
            var ancestor: JSONObject? = parent
            val visited = mutableSetOf(assignedId)
            while (ancestor != null) {
                if (!visited.add(identity(ancestor))) return false
                val ancestorId = ancestor.optString("parentThreadId")
                ancestor = if (ancestorId.isNotBlank()) all.firstOrNull { identity(it) == ancestorId }
                    else ancestor.optString("parentTopic").takeIf(String::isNotBlank)?.let { legacy ->
                        all.filter { normalized(it.optString("topic")) == normalized(legacy) }
                            .distinctBy(::identity).singleOrNull()
                    }
            }
        }
        if (existing?.optString("observedId") == observedId &&
            existing.optLong("observedRevision", observed.revision) == observed.revision &&
            existing.optString("question") == fields[1] && existing.optString("nextStep") == fields[3] &&
            existing.optString("hypothesis") == proposal?.optString("hypothesis").orEmpty()) return false
        // Topic is a shelf, never an identity. Distinct questions can share it.
        val item = JSONObject().put("threadId", assignedId)
            .put("topic", fields[0]).put("question", fields[1])
            .put("why", fields[2]).put("nextStep", fields[3])
            .put("motiveId", linkedMotiveId(characterId, proposal))
            .put("hypothesis", proposal?.optString("hypothesis").orEmpty())
            .put("parentTopic", parentTopic)
            .put("observedId", observedId).put("observedRevision", observed.revision)
            .put("observed", observedText.take(220)).put("at", now.toString())
        if (parent != null) {
            item.put("parentThreadId", identity(parent))
            val id = parent.optString("observedId").ifBlank {
                val steps = parent.optJSONArray("steps")
                steps?.optJSONObject(steps.length() - 1)?.optString("evidenceId").orEmpty()
            }
            val event = SharedExperienceTimeline.eventsByIds(characterId, listOf(id)).firstOrNull() ?: return false
            item.put("parentEvidenceId", id).put("parentRevision", event.revision)
        }
        root.put("questions", JSONArray().apply {
            old.filterNot { existing != null && identity(it) == identity(existing) }.forEach(::put)
            put(item)
        })
        save(characterId, root)
        return true
    }

    /** Never credit a model's claimed action without a real, time-lined receipt. */
    @Synchronized fun recordOutcome(
        characterId: String, proposal: JSONObject?, action: String, succeeded: Boolean,
        receipt: String, evidenceId: String, now: Instant = Instant.now(),
    ) {
        if (!succeeded || !CharacterExplorationContinuity.informativeAction(action) || characterId.isBlank() ||
            receipt.isBlank() || evidenceId.isBlank() || prefs == null) return
        val verified = CharacterActionEvidence.resolve(characterId, evidenceId) ?: return
        if (!CharacterActionEvidence.supports(verified, action)) return
        // A slow model or a long-running real-world action may finish well
        // after the perception cycle started; only distant future IDs are suspect.
        if (verified.occurredAt.isAfter(now.plusSeconds(3600))) return
        val origin = proposal?.optString("sourceEvidenceId").orEmpty()
        if (origin.isNotBlank() &&
            SharedExperienceTimeline.eventsByIds(characterId, listOf(origin)).isEmpty()) return
        val fields = proposalFields(proposal) ?: return
        val root = snapshot(characterId)
        val threadRecords = entries(root.optJSONArray("threads")).map { JSONObject(it.toString()) }.toMutableList()
        val pending = entries(root.optJSONArray("questions"))
        val selectedQuestion = matching(pending, proposal)
        // A revised question can have different wording but the same stable
        // identity as an earlier explored thread. Keep all its real receipts.
        val match = matching(threadRecords, proposal) ?: selectedQuestion?.let { pendingQuestion ->
            threadRecords.firstOrNull { identity(it) == identity(pendingQuestion) }
        }
        // Legacy callers that have no ID can continue a uniquely named prior topic,
        // but must never steal the history of one of several same-topic branches.
        val legacyThread = if (proposal?.optString("threadId").isNullOrBlank() &&
            selectedQuestion == null && match == null) threadRecords.filter {
                normalized(it.optString("topic")) == normalized(fields[0])
            }.singleOrNull() else null
        val selected = match ?: legacyThread
        val assignedId = selectedQuestion?.let(::identity) ?: selected?.let(::identity)
            ?: UUID.randomUUID().toString()
        val current = selected?.let { JSONObject(it.toString()) } ?: JSONObject()
        val steps = current.optJSONArray("steps") ?: JSONArray()
        if ((0 until steps.length()).any { steps.optJSONObject(it)?.optString("evidenceId") == evidenceId }) return
        val kept = JSONArray()
        for (i in 0 until steps.length()) kept.put(steps.opt(i))
        val status = if (proposal?.optString("status") == "satisfied") "satisfied" else "exploring"
        kept.put(JSONObject().put("evidenceId", evidenceId)
            .put("sourceEvidenceId", proposal?.optString("sourceEvidenceId").orEmpty().take(180))
            .put("revision", verified.revision)
            .put("at", now.toString())
            .put("action", action.take(60)).put("receipt", receipt.take(240))
            .put("question", fields[1]).put("why", fields[2])
            .put("nextStep", fields[3]).put("status", status))
        current.put("threadId", assignedId)
            .put("topic", fields[0]).put("steps", kept)
            .put("motiveId", linkedMotiveId(characterId, proposal)
                .ifBlank { current.optString("motiveId") })
            .put("question", fields[1]).put("why", fields[2])
            .put("nextStep", fields[3]).put("status", status)
            .put("parentThreadId", selectedQuestion?.optString("parentThreadId").orEmpty()
                .ifBlank { current.optString("parentThreadId") })
            .put("parentTopic", proposal?.optString("parentTopic").orEmpty().ifBlank { current.optString("parentTopic") })
            .put("updatedAt", now.toString())
        if (selected != null) threadRecords.removeAll { identity(it) == identity(selected) }
        threadRecords.add(current)
        root.put("threads", JSONArray().apply { threadRecords.forEach(::put) })
        // Only resolve the exact inquiry that produced this action, not sibling
        // questions under the same topic.
        val questions = root.optJSONArray("questions") ?: JSONArray()
        root.put("questions", JSONArray().apply {
            for (i in 0 until questions.length()) {
                val q = questions.optJSONObject(i) ?: continue
                val at = runCatching { Instant.parse(q.optString("at")) }.getOrNull()
                if (selectedQuestion == null || identity(q) != identity(selectedQuestion) ||
                    q.optString("observedId") == evidenceId || at?.isAfter(now) == true) put(q)
            }
        })
        val failed = root.optJSONArray("failures") ?: JSONArray()
        root.put("failures", JSONArray().apply {
            for (i in 0 until failed.length()) {
                val f = failed.optJSONObject(i) ?: continue
                if (f.optString("threadId") != assignedId || assignedId.isBlank()) put(f)
            }
        })
        save(characterId, root)
    }

    /** The character may lose interest without inventing a completed exploration. */
    @Synchronized fun releaseInterest(
        characterId: String, proposal: JSONObject?, now: Instant = Instant.now(),
    ) {
        if (prefs == null || characterId.isBlank() || proposal?.optString("status") != "dropped") return
        val fields = proposalFields(proposal) ?: return
        val root = snapshot(characterId)
        val threads = root.optJSONArray("threads") ?: JSONArray()
        val questions = root.optJSONArray("questions") ?: JSONArray()
        val threadEntries = entries(threads)
        val questionEntries = entries(questions)
        val matchQuestion = matching(questionEntries, proposal)
        val matchThread = matching(threadEntries, proposal) ?: matchQuestion?.let { pendingQuestion ->
            threadEntries.firstOrNull { identity(it) == identity(pendingQuestion) }
        } ?: if (proposal?.optString("threadId").isNullOrBlank() &&
            matchQuestion == null) threadEntries.filter {
                normalized(it.optString("topic")) == normalized(fields[0])
            }.singleOrNull() else null
        if (matchQuestion == null && matchThread == null) return
        if (matchThread != null) {
            matchThread.put("status", "dropped")
                .put("releaseReason", fields[2]).put("releasedAt", now.toString())
            root.put("threads", threads)
        }
        root.put("questions", JSONArray().apply {
            questionEntries.filterNot { matchQuestion != null &&
                identity(it) == identity(matchQuestion) }.forEach(::put)
        })
        save(characterId, root)
    }

    /** Failure is useful feedback for action selection, never evidence of exploration. */
    @Synchronized fun recordFailure(
        characterId: String, proposal: JSONObject?, action: String,
        reason: String, now: Instant = Instant.now(),
    ) {
        if (prefs == null || characterId.isBlank() || action == "silent" || reason.isBlank()) return
        val fields = proposalFields(proposal) ?: return
        val root = snapshot(characterId)
        val previous = root.optJSONArray("failures") ?: JSONArray()
        val linked = matching(entries(root.optJSONArray("questions")) +
            entries(root.optJSONArray("threads")), proposal)
        val selectedId = linked?.let(::identity).orEmpty()
        val same = entries(previous).firstOrNull {
            it.optString("threadId") == selectedId &&
                normalized(it.optString("topic")) == normalized(fields[0]) &&
                normalized(it.optString("question")) == normalized(fields[1]) &&
                it.optString("action") == action
        }
        val repeats = same?.optInt("repeats", 0)?.plus(1)?.coerceAtMost(5) ?: 1
        root.put("failures", JSONArray().apply {
            val retained = entries(previous)
                .filterNot { it.optString("threadId") == selectedId &&
                    normalized(it.optString("topic")) == normalized(fields[0]) &&
                    normalized(it.optString("question")) == normalized(fields[1]) &&
                    it.optString("action") == action }.takeLast(5)
            retained.forEach { put(it) }
            put(JSONObject().put("threadId", selectedId).put("topic", fields[0])
                .put("question", fields[1]).put("action", action.take(60))
                .put("reason", reason.take(200)).put("repeats", repeats).put("at", now.toString()))
        })
        save(characterId, root)
    }

    /** The character may choose a concrete next attempt or abandon the topic. */
    fun pendingDecisionCues(characterId: String): List<String> {
        val state = snapshot(characterId)
        val questions = pendingQuestions(characterId, state)
            .takeLast(2).map { q ->
                "自己仍好奇：" + q.optString("topic").take(60) +
                    "；真正的问题：" + q.optString("question").take(130) +
                    "；threadId=" + identity(q) + "；源事件ID=" + q.optString("observedId") + "；父主题=" + q.optString("parentTopic") +
                    "；此前真实结果=" + previousResult(characterId, state, q).take(120) +
                    "；可以自选是否实践：" + q.optString("nextStep").take(110)
            }
        val ongoing = activeThreads(characterId, state).takeLast(1)
            .map { t ->
                "曾尝试的兴趣：" + t.optString("topic").take(60) +
                    "；仍想知道：" + t.optString("question").take(110) +
                    "；可以选择继续/换办法/放下"
            }
        return (questions + ongoing).distinct().take(3)
    }

    /** Retract dependent subjective questions and objectively confirmed steps together. */
    @Synchronized fun invalidateEvidence(eventId: String) {
        if (eventId.isBlank()) return
        val store = prefs ?: return
        store.all.keys.forEach { characterId ->
            val root = snapshot(characterId)
            var changed = false
            val threads = root.optJSONArray("threads") ?: JSONArray()
            val keptThreads = JSONArray()
            for (i in 0 until threads.length()) {
                val t = threads.optJSONObject(i) ?: continue
                val steps = t.optJSONArray("steps") ?: JSONArray()
                val kept = JSONArray()
                for (j in 0 until steps.length()) {
                    val step = steps.optJSONObject(j) ?: continue
                    if (step.optString("evidenceId") == eventId || step.optString("sourceEvidenceId") == eventId) changed = true
                    else kept.put(step)
                }
                if (kept.length() == 0) continue
                if (kept.length() != steps.length()) {
                    val last = kept.optJSONObject(kept.length() - 1) ?: continue
                    t.put("steps", kept).put("updatedAt", last.optString("at"))
                    listOf("question", "why", "nextStep", "status").forEach { t.put(it, last.optString(it)) }
                }
                keptThreads.put(t)
            }
            val questions = root.optJSONArray("questions") ?: JSONArray()
            val keptQuestions = JSONArray()
            for (i in 0 until questions.length()) {
                val q = questions.optJSONObject(i) ?: continue
                if (q.optString("observedId") == eventId || q.optString("parentEvidenceId") == eventId) changed = true else keptQuestions.put(q)
            }
            if (changed) {
                root.put("threads", keptThreads).put("questions", keptQuestions)
                save(characterId, root)
            }
        }
    }

    /**
     * Cross-surface handoff for chat, phone and meetings. Transient questions are not
     * rewritten as facts or promoted to permanent traits merely by being discussed.
     */
    fun briefContext(characterId: String): String {
        val root = snapshot(characterId)
        val threads = root.optJSONArray("threads") ?: JSONArray()
        val questions = root.optJSONArray("questions") ?: JSONArray()
        val active = activeThreads(characterId, root).takeLast(2)
        val pending = pendingQuestions(characterId, root).takeLast(2)
        if (active.isEmpty() && pending.isEmpty()) return ""
        return buildString {
            appendLine("【本人尚未结束的兴趣线索｜内部连续性，不要像报告一样念出来】")
            active.forEach { t ->
                val steps = t.optJSONArray("steps") ?: JSONArray()
                val receipt = steps.optJSONObject(steps.length() - 1)?.optString("receipt").orEmpty()
                appendLine("- 真正尝试过「" + t.optString("topic").take(70) + "」，最近结果=" +
                    receipt.take(120) + "；还想知道=" + t.optString("question").take(110) +
                    "；threadId=" + identity(t) + "；源事件ID=" + lastRealStep(characterId, t)?.optString("evidenceId").orEmpty() +
                    "；可选下一步=" + t.optString("nextStep").take(120))
            }
            pending.forEach { q ->
                appendLine("- 之前看到「" + q.optString("observed").take(110) +
                    "」后对「" + q.optString("topic").take(70) +
                    "」感到好奇：" + q.optString("question").take(105) +
                    "；父主题=" + q.optString("parentTopic") + "；待验证假设=" + q.optString("hypothesis") +
                    "；threadId=" + identity(q) + "；源事件ID=" + q.optString("observedId") + "；可选下一步=" + q.optString("nextStep") +
                    "；此前真实结果=" + previousResult(characterId, root, q).take(120) +
                    "（这个新问题尚未实践，不是客观结论）")
            }
            appendLine("兴趣影响注意、观点和话题选择，不要因此自动改变当前聊天主题；过去接触到的信息范围以真实记录为限。")
        }.trim()
    }

    fun promptSection(
        characterId: String, displayName: String, recentActions: List<String>,
        now: Instant = Instant.now(),
    ): String {
        val profile = CharacterLifeStore.state(characterId).optJSONObject("profile")
        val learned = CharacterDevelopmentStore.active(characterId)
            .filter { it.kind == DevelopmentKind.Interest || it.kind == DevelopmentKind.Preference }
            .takeLast(4)
        val root = snapshot(characterId)
        val threads = root.optJSONArray("threads") ?: JSONArray()
        val active = activeThreads(characterId, root).takeLast(4)
        val questions = root.optJSONArray("questions") ?: JSONArray()
        val unanswered = pendingQuestions(characterId, root).takeLast(3)
        val failures = root.optJSONArray("failures") ?: JSONArray()
        val recentFailures = (0 until failures.length()).mapNotNull(failures::optJSONObject).takeLast(3)
        val lived = SharedExperienceTimeline.all(characterId).asReversed().filter {
            it.speaker == displayName && (it.channel.startsWith("独自") ||
                it.channel.startsWith("数字世界") || it.channel.contains("朋友圈") ||
                it.channel.contains("日记") || it.channel.startsWith("现实世界窗口"))
        }.take(4)
        return buildString {
            appendLine("【持续好奇心｜允许新探索、深入、放弃或独处】")
            // The full authored profile is already provided by CharacterRuntime.
            // Repeating the first 230 chars here both wastes tokens and unfairly
            // favors whichever hobby happens to be written first.
            if (profile?.optString("interests")?.isNotBlank() == true)
                appendLine("当前人设中的兴趣与审美都是可以主动探索的起点；结合上方完整的本人设定，不只盯着最近一次用户发言。")
            if (learned.isNotEmpty()) appendLine("反复真实经历形成的兴趣：" +
                learned.joinToString("；") { it.content.take(110) })
            if (unanswered.isNotEmpty()) {
                appendLine("之前由真实观察引发、尚未采取行动的疑问（只是好奇，不等于经历）：")
                unanswered.forEach { q ->
                    appendLine("- " + q.optString("topic") + "：" + q.optString("question") +
                        "；当时确实观察到：" + q.optString("observed").take(120) +
                        "；threadId=" + identity(q) + "；源事件ID=" + q.optString("observedId") + "；父主题=" + q.optString("parentTopic") +
                        "；待验证假设=" + q.optString("hypothesis") + "；可能下一步：" + q.optString("nextStep"))
                }
            }
            if (active.isNotEmpty()) {
                appendLine("已经真实行动过、仍可能继续探索的主题：")
                active.forEach { t ->
                    val steps = t.optJSONArray("steps") ?: JSONArray()
                    val last = steps.optJSONObject(steps.length() - 1)
                    val hours = runCatching {
                        Duration.between(Instant.parse(t.optString("updatedAt")), now).toHours()
                    }.getOrDefault(0L).coerceAtLeast(0L)
                    appendLine("- " + t.optString("topic") + "；问题=" + t.optString("question") +
                        "；最近真实行动结果=" + last?.optString("receipt").orEmpty().take(150) +
                        "；约" + hours + "小时前；可选下一步=" + t.optString("nextStep"))
                }
            }
            if (recentFailures.isNotEmpty()) {
                appendLine("最近实际尝试未成功的办法（只是失败回执，不代表主题不值得继续）：")
                recentFailures.forEach { f ->
                    appendLine("- " + f.optString("topic") + "：" + f.optString("action") +
                        "；原因=" + f.optString("reason") + "；同类尝试=" + f.optInt("repeats"))
                }
            }
            if (lived.isNotEmpty()) {
                appendLine("最近自己真实经历过的事情：")
                lived.forEach { appendLine("- " + it.channel + "：" +
                    it.evidenceContent.replace(Regex("\\s+"), " ").take(150)) }
            }
            if (recentActions.takeLast(5).count { it == "SILENT" } >= 3)
                appendLine("最近多次安静：区分「不想打扰用户」与「自己不想做事」；若有正在在意的事情，认真比较可执行的小行动，但不强制活跃。")
            appendLine("把稳定人设的不同侧面当成可独立产生问题的来源：自己喜欢的知识领域可以在没有任何新用户消息时想到『最近有没有新进展、我哪里还不懂』；自己的幽默、温柔、社交倾向、审美也可能产生完全不同的念头。上次聊天只是一份线索，不是所有想法的唯一起点。")
            appendLine("若真想了解具体外部知识，并且能力提示明确显示已配置且已授权，可在本轮直接用 web_search(search_read) 真正搜读，或选择 read_xiaohongshu(keyword) 接触不同经历与评论；需要合适图片素材时选 image_search，但检索结果标题不等于已看见图片。不要因为还没明确列出网址就不敢探索；也不要只说想去查却不提交工具动作。成功回执进入原时间线和学习系统后，再独立判断进一步探索、分享、排进个人日程或放下；未读正文不能声称掌握。")
            appendLine("从兴趣、疑问与真实环境作选择：继续、探索新知、体验、表达、休息都可以。单调感是有时会影响求新的身体线索，不是强制出门、搜索或打扰用户的阈值；没有兴趣也允许安静。")
        }.trim()
    }
}
