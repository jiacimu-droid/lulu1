package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.core.LexiconStatus
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.PromiseKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Extracts non-task lexicon state from a completed user/character turn.
 *
 * Promise execution is owned by CommitmentTurnAutomation/CommitmentTaskStore. This automation
 * owns Life and Concern entries so there is only one execution source of truth for commitments.
 * A quiet window lets all bubbles from one character reply arrive before the turn is examined.
 */
object ChatLexiconAutomation {
    private const val PREFS_NAME = "lulu_chat_lexicon_automation"
    private const val KEY_PROCESSED_SIGNATURES = "processed_signatures_v3"
    private const val QUIET_WINDOW_MS = 5_000L
    private const val KEY_PENDING_TURNS = "pending_life_turns_v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conversationJobs = mutableMapOf<String, Job>()
    private val characterLocks = mutableMapOf<String, Mutex>()
    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null
    private var started = false

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    @Synchronized
    fun initialize(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        scope.launch {
            while (true) {
                delay(60_000)
                MigratedDomainStores.chat.conversations.value.forEach { conversation ->
                    runCatching { inspectImmediateUpdate(conversation); inspectLatestTurn(conversation) }
                }
            }
        }
        scope.launch {
            // Independent of latest-turn inspection: a stuck input generation
            // must not block recovery of an older failed extraction.
            while (true) {
                delay(10_000)
                try {
                    retryPendingTurns()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    android.util.Log.w("LuluLexicon", "Pending life extraction recovery failed", failure)
                }
            }
        }
        scope.launch {
            MigratedDomainStores.chat.conversations.collectLatest { conversations ->
                val liveIds = conversations.mapTo(mutableSetOf()) { it.id }
                conversationJobs.keys
                    .filterNot(liveIds::contains)
                    .forEach { conversationId -> conversationJobs.remove(conversationId)?.cancel() }

                conversations.forEach { conversation ->
                    if (conversation.id in conversationJobs) return@forEach
                    conversationJobs[conversation.id] = scope.launch {
                        // Do not cancel a paid extraction while another chat bubble arrives.
                        MigratedDomainStores.chat.messages(conversation.id)
                            .debounce(QUIET_WINDOW_MS)
                            .collect {
                                inspectImmediateUpdate(conversation)
                                inspectLatestTurn(conversation)
                            }
                    }
                }
            }
        }
    }

    private suspend fun inspectLatestTurn(conversation: LuluConversation) {
        // Character messages arrive in paced bubbles. Starting extraction
        // during delivery caused a fresh charge for every interim bubble.
        while (ChatGenerationActivity.isRunning(conversation.id) ||
            com.jiacimu.lulu.ChatReplyTaskManager.state(conversation.id).running) {
            delay(750L)
        }
        delay(1_200L)
        val messages = MigratedDomainStores.chat.messages(conversation.id).value
        val userIndex = messages.indexOfLast {
            it.sender == LuluChatMessage.Sender.User && it.status == LuluChatMessage.Status.Sent
        }
        if (userIndex < 0) return
        val userMessage = messages[userIndex]
        val replies = messages.drop(userIndex + 1).filter {
            it.sender == LuluChatMessage.Sender.Character && it.status == LuluChatMessage.Status.Sent
        }
        if (replies.isEmpty()) return

        replies.groupBy { reply -> reply.authorCharacterId ?: conversation.characterId }
            .forEach { (characterId, roleReplies) ->
                if (characterId.isBlank()) return@forEach
                val characterText = roleReplies.joinToString("\n", transform = LuluChatMessage::content).trim()
                val fullTurn = "${userMessage.content}\n$characterText"
                // Existing concerns are NOT a reason to charge a new model
                // request on every unrelated ordinary greeting.
                val unresolvedLife = LuluRepositories.lexicon.snapshot(characterId).any {
                    it.status == LexiconStatus.Active && it.section == LexiconSection.Life
                }
                // A plain "好了/取消/解决" may close a known life record even
                // without a new life keyword. Handle it in THIS completed
                // conversation extraction, not an earlier extra paid request.
                if (!looksLikeLexiconCandidate(fullTurn) &&
                    !(unresolvedLife && looksLikeImmediateLifeUpdate(userMessage.content)))
                    return@forEach

                val sourceTurnId = "${conversation.id}:${userMessage.id}:$characterId"
                // Stable across additional bubbles of the SAME actual turn.
                // Multiple model calls must not be triggered by partial replies.
                val signature = "lexicon-turn-v4:$sourceTurnId"
                if (isProcessed(signature)) return@forEach
                val lock = synchronized(characterLocks) { characterLocks.getOrPut(characterId) { Mutex() } }
                lock.withLock {
                    if (isProcessed(signature)) return@withLock
                    rememberPending(LexiconPendingTurn(signature, conversation.id, userMessage.id, characterId, false))
                    if (!permitAttempt(signature, characterId)) return@withLock
                    val saved = extractAndSave(
                        characterId = characterId,
                        userText = userMessage.content,
                        characterText = characterText,
                        groupName = conversation.groupChat?.name,
                        sourceEventIds = (listOf(userMessage.id) + roleReplies.map { it.id }).map {
                            if (conversation.groupChat == null) it else "$it:group:$characterId"
                        },
                    )
                    if (saved) markProcessed(signature) else schedulePromptRetry(signature)
                }
            }
    }

    private suspend fun inspectImmediateUpdate(conversation: LuluConversation) {
        // Let the main role decision finish. Otherwise a single "好了" user
        // message causes one paid pre-reply life extraction AND a second paid
        // post-reply extraction of the same update. Silent turns still get
        // handled after their actual chat generation ends.
        if (ChatGenerationActivity.isRunning(conversation.id) ||
            com.jiacimu.lulu.ChatReplyTaskManager.state(conversation.id).running) return
        val message = MigratedDomainStores.chat.messages(conversation.id).value.lastOrNull()?.takeIf {
            it.sender == LuluChatMessage.Sender.User && it.status == LuluChatMessage.Status.Sent
        } ?: return
        if (!looksLikeImmediateLifeUpdate(message.content)) return
        val ids = conversation.groupChat?.members?.map { it.characterId } ?: listOf(conversation.characterId)
        ids.distinct().forEach { characterId ->
            if (LuluRepositories.lexicon.snapshot(characterId).none { it.status == LexiconStatus.Active && it.section == LexiconSection.Life }) return@forEach
            val signature = "lexicon-update:${message.id}:$characterId"
            if (isProcessed(signature)) return@forEach
            val lock = synchronized(characterLocks) { characterLocks.getOrPut(characterId) { Mutex() } }
            lock.withLock {
                val sourceId = if (conversation.groupChat == null) message.id else "${message.id}:group:$characterId"
                rememberPending(LexiconPendingTurn(signature, conversation.id, message.id, characterId, true))
                if (!permitAttempt(signature, characterId)) return@withLock
                if (extractAndSave(characterId, message.content, "", conversation.groupChat?.name, listOf(sourceId))) {
                    markProcessed(signature)
                } else schedulePromptRetry(signature)
            }
        }
    }

    private suspend fun extractAndSave(
        characterId: String,
        userText: String,
        characterText: String,
        groupName: String?,
        sourceEventIds: List<String>,
    ): Boolean {
        val active = LuluRepositories.lexicon.snapshot(characterId).filter {
            it.status == LexiconStatus.Active && it.section == LexiconSection.Life
        }
        val terms = memorySearchTerms(userText + " " + characterText)
        val relevant = active.filter { terms.intersect(memorySearchTerms(it.title + it.content)).isNotEmpty() }
        val existing = relevant + active.filterNot { it in relevant }.take(16)
        val context = SharedExperienceTimeline.eventsByIds(characterId, sourceEventIds)
        if (context.isEmpty()) return false
        val result = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                groupName?.let { appendLine("场景：群聊《$it》，当前判断对象是这个角色。") }
                appendLine("原始发言发生时间（本机时区）：")
                context.take(8).forEach { event ->
                    appendLine("- ${event.occurredAt.atZone(java.time.ZoneId.systemDefault())} · ${event.speaker}")
                }
                appendLine("用户：$userText")
                appendLine("已有生活近况（只允许更新真实生活条目，不含角色的私人挂心）：")
                existing.forEach { appendLine("entryId=${it.id}；${it.section}；${it.title}：${it.content}；上次回访=${it.lastFollowUpAt}") }
                appendLine("角色完整回复（可能由多个气泡组成）：")
                append(characterText)
            },
            instruction = """
                检查这一整轮对话（用户输入 + 当前角色全部回复气泡），仅提取具有真实来源的生活近况 life。
                私人挂心由统一心理状态 innerLife.openConcerns 唯一管理，不要输出 concern；承诺由 CommitmentTaskStore 唯一管理，不要输出 promise。用户说担心不代表角色本人产生了挂心。
                只返回 JSON 数组，不要代码块；没有内容时返回 []。
                每项格式：
                {"section":"life","kind":"","title":"简短标题","content":"可脱离聊天理解的生活近况","existingId":"更新时填已有entryId，否则空","status":"Active|Resolved","followedUp":false}

                规则：
                1. life 保存会影响日常陪伴的当前生活安排、作息、学习任务或现实处境；长期稳定身份与偏好交给记忆系统。
                2. 本提取器不能建立另一份角色心事、待办或自动回访计划；那些由同一次角色心理决策以及正式责任链路处理。
                3. 必须看完整轮次，不能只根据用户消息判断；第二、第三个回复气泡里的新信息同样有效。
                4. 群聊只记录属于当前角色主观关注的内容，不替其他角色承担挂心。
                5. 不编造时间、频率、病情、结果或用户未提供的状态；已经明确解决的小情绪不要长期挂心。
                6. 用户明确说问题解决、安排结束或取消时，更新对应 existingId 为 Resolved；未回复、角色安慰、推测好转不代表解决。临时变化与长期事实区分。
                7. 同一生活近况有新进展时更新已有条目，不重复创建。followedUp 只是已实际问过的记录，不授权再次主动联系。
                8. 角色完整回复为空时，只允许更新或解决已有条目，不新增；归属不明确就不改。
                9. title 不超过 12 个汉字，content 说明关注对象、当前未解决点和已知回访条件（没有条件就不要猜）。
                10. 对作息、睡觉、起床和学习进度等有明确时间的生活事实，结合原始发言时间记录可核实的日期/时刻；相对词「昨晚」相对该发言发生日理解，不等于角色此刻的「昨晚」。不确定具体日期就保留原说法，不伪造。
                11. 让后来能比较前后变化：例如之前真实说过凌晨两点睡，今天确实更早睡，可以自然地提一句；不能把一次作息当长期习惯，也不主动创造用户未说的新进展。
            """.trimIndent(),
            source = "生活记录",
            title = "生活近况整理",
            maxTokens = 1_000,
            usage = ModelUsage.Chat,
            contextMode = CompanionContextMode.Isolated,
            connectionOverride = MemoryModelRuntime.extractionConnection(),
        )
        if (result.isFailure) {
            // A provider/network failure is not a semantic "no update".
            // The original turn remains queued. Record the category without
            // persisting actual personal conversation or provider credentials.
            val failure = result.exceptionOrNull()
            LuluRepositories.performance.recordError(
                source = "生活记录", title = "独立提取请求失败",
                message = "类=${failure?.javaClass?.simpleName}; 来源数=${sourceEventIds.size}; 原始证据待恢复")
            return false
        }

        val rows = runCatching { decodeMemoryResponseArray(result.getOrThrow().text) }
            .getOrElse { failure ->
                LuluRepositories.performance.recordError(
                    source = "生活记录", title = "模型输出格式无效",
                    message = "类=${failure.javaClass.simpleName}; 返回长度=${result.getOrThrow().text.length}; 证据待重试")
                return false
            }
        val allowed = existing.associateBy(LexiconEntry::id)
        val existingKeys = LuluRepositories.lexicon.snapshot(characterId).mapTo(mutableSetOf(), LexiconEntry::automationDedupeKey)
        val liveSources = SharedExperienceTimeline.eventsByIds(characterId, sourceEventIds)
        if (liveSources.size != sourceEventIds.distinct().size ||
            context.any { before -> liveSources.none { it.id == before.id && it.revision == before.revision } }) return false
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: return false
            // Ignore old-model concern proposals; never persist a competing
            // subjective concern in the life-facts extractor.
            if (item.optString("section").trim().lowercase() !in setOf("life", "生活")) continue
            val existingId = item.optString("existingId").trim()
            val old = allowed[existingId]
            if (existingId.isNotBlank() && old == null) continue
            val entry = if (old != null) {
                // A delete/edit racing this request must not restore the old snapshot.
                val current = LuluRepositories.lexicon.snapshot(characterId).firstOrNull { it.id == old.id }
                if (current != old) continue
                old.copy(
                    content = item.optString("content").trim().ifBlank { old.content },
                    status = if (item.optString("status").equals("Resolved", true)) LexiconStatus.Resolved else LexiconStatus.Active,
                    updatedAt = Instant.now(),
                    sourceEventIds = (old.sourceEventIds + sourceEventIds).distinct(),
                    lastFollowUpAt = if (item.optBoolean("followedUp")) Instant.now() else old.lastFollowUpAt,
                )
            } else {
                if (characterText.isBlank()) continue
                val parsed = runCatching { parseLexiconEntries(JSONArray().put(item).toString(), characterId) }
                    .getOrElse { failure ->
                        LuluRepositories.performance.recordError(
                            source = "生活记录", title = "生活记录条目结构不合法",
                            message = "条目序号=$index; 类=${failure.javaClass.simpleName}; 原始证据保留")
                        return false
                    }
                val new = parsed.singleOrNull()?.takeIf { it.section == LexiconSection.Life } ?: continue
                if (!existingKeys.add(new.automationDedupeKey())) continue
                new.copy(sourceEventIds = sourceEventIds)
            }
            val revised = entry.copy(sourceRevisions = entry.sourceRevisions + context.associate { it.id to it.revision })
            if (!LuluRepositories.lexicon.saveDerived(revised, old)) continue
        }
        return true
    }

    /**
     * Reconstruct the exact failed source turn, rather than retrying whatever
     * happens to be the newest conversation after a timeout. Each job is saved
     * before its chargeable request so process death cannot drop the checkpoint.
     */
    private suspend fun retryPendingTurns() {
        pendingTurns().forEach { pending ->
            if (isProcessed(pending.signature)) {
                forgetPending(pending.signature)
                return@forEach
            }
            val conversation = MigratedDomainStores.chat.conversations.value
                .firstOrNull { it.id == pending.conversationId }
            if (conversation == null) {
                forgetPending(pending.signature)
                return@forEach
            }
            if (ChatGenerationActivity.isRunning(conversation.id) ||
                com.jiacimu.lulu.ChatReplyTaskManager.state(conversation.id).running) return@forEach
            val messages = MigratedDomainStores.chat.messages(conversation.id).value
            val userIndex = messages.indexOfFirst {
                it.id == pending.userMessageId && it.sender == LuluChatMessage.Sender.User &&
                    it.status == LuluChatMessage.Status.Sent
            }
            if (userIndex < 0) {
                // A deleted source must never be recreated from a stale checkpoint.
                forgetPending(pending.signature)
                return@forEach
            }
            val user = messages[userIndex]
            val nextUserIndex = ((userIndex + 1) until messages.size).firstOrNull { index ->
                messages[index].sender == LuluChatMessage.Sender.User &&
                    messages[index].status == LuluChatMessage.Status.Sent
            } ?: -1
            val turnEnd = if (nextUserIndex < 0) messages.size else nextUserIndex
            val roleReplies = if (pending.immediate) emptyList() else
                messages.subList(userIndex + 1, turnEnd).filter {
                    it.sender == LuluChatMessage.Sender.Character &&
                        it.status == LuluChatMessage.Status.Sent &&
                        (it.authorCharacterId ?: conversation.characterId) == pending.characterId
                }
            if (!pending.immediate && roleReplies.isEmpty()) {
                if (nextUserIndex >= 0) forgetPending(pending.signature)
                return@forEach
            }
            val sourceEventIds = (listOf(user.id) + roleReplies.map(LuluChatMessage::id)).map {
                if (conversation.groupChat == null) it else "$it:group:${pending.characterId}"
            }
            val lock = synchronized(characterLocks) {
                characterLocks.getOrPut(pending.characterId) { Mutex() }
            }
            lock.withLock {
                if (isProcessed(pending.signature) || !permitAttempt(pending.signature, pending.characterId)) return@withLock
                val saved = extractAndSave(
                    characterId = pending.characterId,
                    userText = user.content,
                    characterText = roleReplies.joinToString("\n", transform = LuluChatMessage::content).trim(),
                    groupName = conversation.groupChat?.name,
                    sourceEventIds = sourceEventIds,
                )
                if (saved) markProcessed(pending.signature) else schedulePromptRetry(pending.signature)
            }
        }
    }

    @Synchronized
    private fun pendingTurns(): List<LexiconPendingTurn> {
        val raw = prefs?.getString(KEY_PENDING_TURNS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val signature = item.optString("signature")
                    val conversationId = item.optString("conversationId")
                    val userMessageId = item.optString("userMessageId")
                    val characterId = item.optString("characterId")
                    if (signature.isBlank() || conversationId.isBlank() ||
                        userMessageId.isBlank() || characterId.isBlank()) continue
                    add(LexiconPendingTurn(signature, conversationId, userMessageId, characterId,
                        item.optBoolean("immediate")))
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun rememberPending(job: LexiconPendingTurn) {
        val current = pendingTurns()
        if (current.any { it.signature == job.signature }) return
        persistPending(current + job)
    }

    @Synchronized
    private fun forgetPending(signature: String) {
        val current = pendingTurns()
        if (current.none { it.signature == signature }) return
        persistPending(current.filterNot { it.signature == signature })
    }

    private fun persistPending(turns: List<LexiconPendingTurn>) {
        val array = JSONArray()
        turns.forEach { turn ->
            array.put(JSONObject()
                .put("signature", turn.signature)
                .put("conversationId", turn.conversationId)
                .put("userMessageId", turn.userMessageId)
                .put("characterId", turn.characterId)
                .put("immediate", turn.immediate))
        }
        check(prefs?.edit()?.putString(KEY_PENDING_TURNS, array.toString())?.commit() == true) {
            "生活近况整理的待重试任务未能保存；禁止无检查点地再次调用模型"
        }
    }

    /**
     * Promptly retry one failed background task without replaying a POST at
     * the transport level. The bounded schedule avoids hammering an outage.
     * The durable ten-second recovery poll also survives process death.
     */
    private fun schedulePromptRetry(signature: String) {
        val p = prefs ?: return
        val count = p.getInt("attempt:${signature.hashCode()}:count", 0)
        val delayMs = lexiconRetryDelayMillis(count) ?: return
        scope.launch {
            delay(delayMs)
            try {
                retryPendingTurns()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.w("LuluLexicon", "Prompt life extraction retry failed", failure)
            }
        }
    }

    private fun permitAttempt(signature: String, characterId: String): Boolean {
        val p = prefs ?: return false
        // Only the exact source turn's own retry checkpoint controls this
        // fallback; a separate user's new fact never consumes a shared quota.
        val key = "attempt:${signature.hashCode()}"
        val last = p.getLong("$key:at", 0L)
        val count = p.getInt("$key:count", 0)
        val now = System.currentTimeMillis()
        val delayMs = lexiconRetryDelayMillis(count) ?: return false
        if (last > 0 && now - last < delayMs) return false
        return p.edit().putInt("$key:count", count + 1).putLong("$key:at", now).commit()
    }

    private fun isProcessed(signature: String): Boolean =
        signature in prefs?.getStringSet(KEY_PROCESSED_SIGNATURES, emptySet()).orEmpty()

    private fun markProcessed(signature: String) {
        val current = prefs?.getStringSet(KEY_PROCESSED_SIGNATURES, emptySet()).orEmpty().toMutableList()
        current += signature
        check(prefs?.edit()?.putStringSet(KEY_PROCESSED_SIGNATURES, current.takeLast(400).toSet())?.commit() == true)
        forgetPending(signature)
    }
}

internal data class LexiconPendingTurn(
    val signature: String,
    val conversationId: String,
    val userMessageId: String,
    val characterId: String,
    val immediate: Boolean,
)

/** Task-level retry only; transport must not silently replay chargeable POSTs. */
internal fun lexiconRetryDelayMillis(attempts: Int): Long? = when (attempts) {
    0 -> 0L
    1 -> 15L * 60_000L     // A timeout may have been billed; never replay in 2 seconds.
    2 -> 60L * 60_000L     // Repeated invalid/provider responses are not a hot loop.
    3 -> 6L * 60 * 60_000L
    4 -> 24L * 60 * 60_000L
    else -> null // Five total attempts; original timeline evidence remains inspectable.
}

internal fun looksLikeCommitmentRequest(text: String): Boolean {
    val clean = text.trim()
    if (clean.isBlank()) return false
    return CommitmentSignals.any { signal -> signal in clean }
}

internal fun looksLikeImmediateLifeUpdate(text: String): Boolean =
    listOf("好了", "解决", "结束", "不用", "取消", "不再", "已经")
        .any(text::contains)

internal fun looksLikeLexiconCandidate(text: String): Boolean {
    val clean = text.trim()
    if (clean.isBlank()) return false
    // Private concerns are decided and stored in the character's one inner
    // life system, not extracted a second time with another paid model call.
    return LifeSignals.any { signal -> clean.contains(signal, ignoreCase = true) }
}

internal fun parseCommitmentEntries(raw: String, characterId: String): List<LexiconEntry> {
    return parseLexiconEntries(raw, characterId, defaultSection = LexiconSection.Promise)
}

internal fun parseLexiconEntries(
    raw: String,
    characterId: String,
    defaultSection: LexiconSection? = null,
): List<LexiconEntry> {
    val clean = raw.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    val array = JSONArray(clean)
    val now = Instant.now()
    val parsed = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val section = when (item.optString("section").trim().lowercase()) {
                "life", "生活" -> LexiconSection.Life
                "concern", "挂心", "关心" -> LexiconSection.Concern
                "promise", "约定", "承诺" -> LexiconSection.Promise
                else -> defaultSection ?: continue
            }
            val kind = item.optString("kind").toPromiseKindOrNull()
            if (section == LexiconSection.Promise && kind == null) continue
            val content = item.optString("content").trim()
            if (content.isBlank()) continue
            val title = item.optString("title").trim().ifBlank {
                if (section == LexiconSection.Promise) kind?.defaultTitle().orEmpty()
                else if (section == LexiconSection.Concern) "新的挂心"
                else "生活记录"
            }.take(24)
            add(
                LexiconEntry(
                    id = UUID.randomUUID().toString(),
                    characterId = characterId,
                    section = section,
                    title = title,
                    content = content,
                    promiseKind = if (section == LexiconSection.Promise) kind else null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }
    check(array.length() == 0 || parsed.isNotEmpty()) {
        "模型返回了非空辞海数组，但没有任何可保存条目"
    }
    return parsed
}

private fun String.toPromiseKindOrNull(): PromiseKind? = when (trim().lowercase()) {
    "promise", "承诺", "约定" -> PromiseKind.Promise
    "responsibility", "责任", "职责" -> PromiseKind.Responsibility
    "reminder", "提醒" -> PromiseKind.Reminder
    "long_term_supervision", "longtermsupervision", "supervision", "长期监督", "监督" ->
        PromiseKind.LongTermSupervision
    else -> null
}

private fun PromiseKind.defaultTitle(): String = when (this) {
    PromiseKind.Promise -> "新的约定"
    PromiseKind.Responsibility -> "新的责任"
    PromiseKind.Reminder -> "新的提醒"
    PromiseKind.LongTermSupervision -> "长期监督"
}

private fun LexiconEntry.automationDedupeKey(): String =
    "${section.name}|${promiseKind?.name.orEmpty()}|${content.normalizedCommitmentText()}"

private fun String.normalizedCommitmentText(): String = lowercase()
    .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")

private val CommitmentSignals = listOf(
    "监督", "督促", "提醒", "记得叫", "记得喊", "叫我", "喊我", "催我",
    "答应我", "承诺", "负责", "以后要", "以后帮我", "每天帮我", "每周帮我",
    "别让我忘", "要你帮我", "你要帮我", "监督我起床", "监督我睡觉", "监督我学习",
    "说好了", "说定了", "就这么定", "我们约好", "约定好", "约好", "约定", "你记得", "你可要",
    "记住这个", "别忘了", "以后", "拉钩", "我会", "我来", "交给我",
)

private val LifeSignals = listOf(
    "我最近", "我今天", "我明天", "我这周", "我每天", "我的作息", "我要考试", "我要考研",
    "我在备考", "我住在", "我搬到", "我开始上班", "我开始上学", "我的计划", "接下来我要",
    "今天要", "明天要", "最近在", "接下来", "准备去", "准备开始",
    "昨晚", "昨夜", "昨天睡", "凌晨", "点睡", "我睡了", "睡到", "睡得", "起床",
    "早睡", "熬夜", "今晚睡", "今天睡",
)

private val ConcernSignals = listOf(
    "我担心", "我焦虑", "我害怕", "我睡不着", "我失眠", "我不舒服", "我生病", "我难受",
    "压力很大", "快考试", "要出成绩", "等通知", "还没解决", "心情不好", "很紧张",
    "挂心", "放心不下", "之后问", "回头问", "我会关心", "我想知道后来",
)
