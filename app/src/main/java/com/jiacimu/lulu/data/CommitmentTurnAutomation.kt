package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

object CommitmentTurnAutomation {
    private const val PREFS_NAME = "lulu_commitment_turns"
    private const val KEY_SIGNATURES = "processed_signatures_v1"
    private const val QUIET_WINDOW_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()
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
        CommitmentTaskStore.initialize(context)
        scope.launch {
            MigratedDomainStores.chat.conversations.collectLatest { conversations ->
                val ids = conversations.mapTo(mutableSetOf()) { it.id }
                jobs.keys.filterNot(ids::contains).forEach { jobs.remove(it)?.cancel() }
                CommitmentTaskStore.snapshot()
                    .filter { task -> task.status.isActive() && !task.sourceTurnId.isNullOrBlank() &&
                        task.sourceTurnId.substringBefore(':').isNotBlank() && task.sourceTurnId.substringBefore(':') !in ids }
                    .forEach { task -> CommitmentTaskStore.cancel(task.id, "来源会话已被删除") }
                conversations.forEach { conversation ->
                    if (conversation.id in jobs) return@forEach
                    jobs[conversation.id] = scope.launch {
                        // Compatibility recovery only. Normal turns are handled by the
                        // character planner itself, including its explicit empty decision.
                        // Debounce *before* calling the model, then use collect,
                        // not collectLatest: a reply's next bubble must not
                        // cancel the prior promise extraction mid-request.
                        MigratedDomainStores.chat.messages(conversation.id)
                            .debounce(QUIET_WINDOW_MS)
                            .collect {
                                retryTransientExtraction { inspectLatestTurn(conversation) }
                            }
                    }
                }
            }
        }
    }

    private suspend fun retryTransientExtraction(operation: suspend () -> Unit) {
        try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A transient failure is not permission for three back-to-back
            // expensive calls. The next genuinely new event may retry.
        }
    }

    private suspend fun inspectLatestTurn(conversation: LuluConversation) {
        while (ChatGenerationActivity.isRunning(conversation.id) ||
            com.jiacimu.lulu.ChatReplyTaskManager.state(conversation.id).running) {
            delay(750L)
        }
        delay(1_200L)
        val messages = MigratedDomainStores.chat.messages(conversation.id).value
        reconcileDeletedSources(conversation.id, messages)
        val userIndex = messages.indexOfLast { it.sender == LuluChatMessage.Sender.User && it.status == LuluChatMessage.Status.Sent }
        if (userIndex < 0) return
        val userMessage = messages[userIndex]
        // Respect the same conversational batching as the chat model. If the user types
        // "明天早上" and then "10点叫我" in separate bubbles, both fragments define
        // the promised time; using only the last bubble silently loses the date.
        val lastCharacterIndex = messages.subList(0, userIndex).indexOfLast {
            it.sender == LuluChatMessage.Sender.Character && it.status == LuluChatMessage.Status.Sent
        }
        val userBatch = messages.subList(lastCharacterIndex + 1, userIndex + 1)
            .filter { it.sender == LuluChatMessage.Sender.User && it.status == LuluChatMessage.Status.Sent }
            .takeLast(16)
        val combinedUserText = userBatch.joinToString("\n", transform = LuluChatMessage::content)
        val replies = messages.drop(userIndex + 1).filter {
            it.sender == LuluChatMessage.Sender.Character && it.status == LuluChatMessage.Status.Sent
        }
        if (replies.isEmpty()) return

        val grouped = replies.groupBy { reply -> reply.authorCharacterId ?: conversation.characterId }
        grouped.forEach { (characterId, roleReplies) ->
            if (characterId.isBlank()) return@forEach
            val characterText = roleReplies.joinToString("\n", transform = LuluChatMessage::content).trim()
            val sourceTurnId = "${conversation.id}:${userMessage.id}:$characterId"
            val evidenceIds = userBatch.map(LuluChatMessage::id)
            // A delivered "twenty minutes later, wake me" accepted by the role
            // is an executable promise even if the model's commitmentChanges was
            // an empty array already marked handled. Use the SAME typed task and
            // native alarm machinery; no second planner call or parallel schedule.
            val ensuredWake = CommitmentDecisionRuntime.ensureAcceptedWake(
                context = appContext ?: return@forEach,
                characterId = characterId,
                sourceTurnId = sourceTurnId,
                evidenceIds = evidenceIds,
                userText = combinedUserText,
                roleText = characterText,
                userAt = userMessage.createdAt,
            )
            // Generic, open-ended promises still rely on the main model's
            // semantic decision; only unhandled legacy turns need extra AI.
            if (CommitmentDecisionRuntime.handled(
                    appContext ?: return@forEach, characterId, evidenceIds
                )) return@forEach
            val signature = "commitment-turn-v2:$sourceTurnId"
            if (isProcessed(signature) || !permitAttempt(signature)) return@forEach

            val active = CommitmentTaskStore.active(characterId)
            val drafts = extractCommitmentTaskDrafts(
                characterId = characterId,
                userText = combinedUserText,
                characterText = characterText,
                activeTasks = active,
                recentContext = messages.take(lastCharacterIndex + 1).filter {
                    it.status == LuluChatMessage.Status.Sent &&
                        (it.sender == LuluChatMessage.Sender.User ||
                            (it.sender == LuluChatMessage.Sender.Character &&
                                (it.authorCharacterId ?: conversation.characterId) == characterId))
                }.takeLast(8).joinToString("\n") { "${it.createdAt} ${it.sender}：${it.content.take(600)}" },
                now = userMessage.createdAt,
            )
            val filtered = drafts.filterNot { draft ->
                // If we already installed a native wake promise for THIS
                // delivered turn, the generic model must not add a second
                // alarm with a different paraphrase of the same wake request.
                val wakeDuplicate = ensuredWake != null &&
                    draft.action == "create" &&
                    listOf("叫醒", "叫我起床", "喊醒", "喊我起床", "按约定叫我")
                        .any { (draft.goal + draft.completionCondition).contains(it) }
                wakeDuplicate || (draft.action == "create" && active.any { task ->
                    task.sourceTurnId == sourceTurnId && task.goal.taskKey() == draft.goal.taskKey()
                })
            }
            // The recovery uses the same typed executor and receipt as the primary model.
            CommitmentDecisionRuntime.apply(
                appContext ?: return@forEach, characterId, sourceTurnId,
                userBatch.map(LuluChatMessage::id), org.json.JSONArray().apply {
                    filtered.forEach { draft -> put(org.json.JSONObject().apply {
                        put("action", draft.action); put("goal", draft.goal)
                        put("targetTaskId", draft.targetTaskId.orEmpty())
                        put("dueAt", draft.dueAt?.toString().orEmpty()); put("timezone", draft.timezone.orEmpty())
                        put("promiseKind", draft.promiseKind.orEmpty()); put("deliveryAction", draft.deliveryAction)
                        put("needsClarification", draft.needsClarification); put("completionCondition", draft.completionCondition)
                        put("steps", org.json.JSONArray(draft.steps))
                    }) }
                },
            )
            markProcessed(signature)
        }
    }

    private fun reconcileDeletedSources(conversationId: String, messages: List<LuluChatMessage>) {
        val liveMessageIds = messages.mapTo(mutableSetOf(), LuluChatMessage::id)
        CommitmentTaskStore.snapshot()
            .filter { task ->
                task.status.isActive() &&
                    task.sourceTurnId?.startsWith("$conversationId:") == true &&
                    task.sourceEventIds.any { sourceId -> sourceId.substringBefore(":group:") !in liveMessageIds }
            }
            .forEach { task -> CommitmentTaskStore.cancel(task.id, "来源消息已删除，约定同步取消") }
    }

    private fun permitAttempt(signature: String): Boolean {
        val p = prefs ?: return false
        val key = "attempt:${signature.hashCode()}"
        val last = p.getLong("$key:at", 0L)
        val count = p.getInt("$key:count", 0)
        val now = System.currentTimeMillis()
        if (count >= 2 || (last > 0 && now - last < 15 * 60_000L)) return false
        return p.edit().putLong("$key:at", now).putInt("$key:count", count + 1).commit()
    }

    private fun isProcessed(signature: String): Boolean =
        signature in prefs?.getStringSet(KEY_SIGNATURES, emptySet()).orEmpty()

    private fun markProcessed(signature: String) {
        val current = prefs?.getStringSet(KEY_SIGNATURES, emptySet()).orEmpty().toMutableList()
        current += signature
        prefs?.edit()?.putStringSet(KEY_SIGNATURES, current.takeLast(500).toSet())?.apply()
    }
}

/** Legacy routing hint; no longer gates model interpretation or creates tasks. */
internal fun looksLikeTaskTurn(userText: String, characterText: String): Boolean {
    val text = "$userText\n$characterText"
    // Generic chat phrases like "我会" or "等会儿" are not enough
    // to justify a paid scheduling-model call or a new promise.
    val broad = setOf("我会", "我来", "记得", "今晚", "明天", "一会儿", "等会儿",
        "待会儿", "稍后", "说好", "负责")
    return commitmentTurnSignals.any { signal ->
        signal !in broad && text.contains(signal, ignoreCase = true)
    }
}

private fun looksLikeImmediateTaskUpdate(text: String): Boolean = immediateTaskUpdateSignals.any { signal ->
    text.contains(signal, ignoreCase = true)
}

private fun String.taskKey(): String = lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), "").take(48)

private val immediateTaskUpdateSignals = listOf(
    "不用叫", "不用提醒", "不用催", "取消", "不用了", "算了",
    "我醒了", "醒了", "已经醒", "我起来了", "起床了", "完成了", "已经完成", "做完了",
    "改成", "改到", "改为", "换成", "延期", "推迟", "提前到", "提前至",
)

private val commitmentTurnSignals = listOf(
    "提醒", "叫我", "喊我", "催我", "监督", "答应", "承诺", "约定", "说好", "负责",
    "我会", "我来", "交给我", "记得", "别忘", "到时候", "明早", "今晚", "明天", "几点",
    "睡一会", "起床", "叫醒", "改成", "不用叫", "不用提醒", "我醒了", "完成了",
    "打电话", "来电话", "给你打", "电话催", "电话叫", "一会儿", "等会儿", "待会儿", "稍后",
    "来找我", "找我一下", "联系我", "给我发消息", "发消息给我", "来找你", "找你一下",
)
