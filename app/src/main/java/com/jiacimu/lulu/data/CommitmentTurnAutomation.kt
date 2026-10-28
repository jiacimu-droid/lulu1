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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.CompanionContextMode
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

object CommitmentTurnAutomation {
    private const val PREFS_NAME = "lulu_commitment_turns"
    private const val KEY_SIGNATURES = "processed_signatures_v1"
    private const val QUIET_WINDOW_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()
    private val immediateWakeJobs = mutableMapOf<String, Job>()
    private val wakeChoiceMutex = Mutex()
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

    /**
     * Trigger-only hints limit extra model cost. All meaning, target selection
     * and final dueAt come from one real model decision, never these hints.
     */
    private suspend fun reconcileModelCommitment(
        context: Context,
        conversation: LuluConversation,
        user: LuluChatMessage,
        userMessages: List<LuluChatMessage>,
        sourceTurnId: String,
        evidenceIds: List<String>,
        userText: String,
        roleText: String,
        characterId: String,
    ): CommitmentTask? = wakeChoiceMutex.withLock {
        if (userMessages.isEmpty()) return@withLock null
        val active = CommitmentTaskStore.active(characterId)
        val possibleCorrection = active.any { it.dueAt != null } &&
            listOf("点", "时", "日期", "时间", "不对", "更正", "改", "明天", "今天", "之后", "提醒")
                .any(userText::contains)
        if (!looksLikeTaskTurn(userText, roleText) && !possibleCorrection)
            return@withLock null

        val signature = "commitment-model-recovery:$sourceTurnId"
        if (isProcessed(signature)) return@withLock active.firstOrNull { task ->
            task.isWakeResponsibility() && evidenceIds.any(task.sourceEventIds::contains)
        }
        if (!permitAttempt(signature)) return@withLock null
        val earlier = MigratedDomainStores.chat.messages(conversation.id).value
            .filter { msg ->
                msg.status == LuluChatMessage.Status.Sent &&
                    (msg.sender == LuluChatMessage.Sender.User ||
                        (msg.sender == LuluChatMessage.Sender.Character &&
                            (msg.authorCharacterId ?: conversation.characterId) == characterId))
            }.takeLast(14)
            .joinToString("\n") { "${it.createdAt} ${it.sender}：${it.content.take(550)}" }

        // Focused model output is a complete JSON decision, including date
        // and selected delivery action, not just a choice between call/alarm.
        val drafts = extractCommitmentTaskDrafts(
            characterId = characterId,
            userText = userText,
            characterText = roleText,
            activeTasks = active,
            recentContext = earlier,
            now = user.createdAt,
            zone = ZoneId.systemDefault(),
            reconcileDeliveredReply = true,
        )
        val changed = CommitmentDecisionRuntime.applyModelRecovery(
            context, characterId, sourceTurnId, evidenceIds, drafts,
        )
        markProcessed(signature)
        changed.firstOrNull { it.isWakeResponsibility() } ?:
            CommitmentTaskStore.snapshot(characterId).firstOrNull { task ->
                task.isWakeResponsibility() && evidenceIds.any(task.sourceEventIds::contains)
            }
    }

    /**
     * Fast post-delivery lane. A clear, accepted wake request should become a
     * native alarm right after actual role speech has been saved, not wait for
     * the slower general AI recovery scan. The recovery scan stays as a backup.
     * Every visible bubble restarts a tiny debounce, so "好……但做不到"
     * does not get interpreted from only the first bubble.
     */
    @Synchronized
    fun onDeliveredCharacterMessage(context: Context, conversationId: String, characterId: String) {
        if (conversationId.isBlank() || characterId.isBlank()) return
        val key = "$conversationId:$characterId"
        immediateWakeJobs.remove(key)?.cancel()
        immediateWakeJobs[key] = scope.launch {
            delay(650L)
            try {
                // Await the end of this reply batch. A first bubble saying "好"
                // is not consent if a later bubble says "我做不到".
                val finished = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                    while (ChatGenerationActivity.isRunning(conversationId) ||
                        com.jiacimu.lulu.ChatReplyTaskManager.state(conversationId).running) {
                        delay(250L)
                    }
                    true
                } == true
                if (!finished) return@launch // slow scanner remains the backup
                delay(250L)
                val conversation = MigratedDomainStores.chat.conversations.value
                    .firstOrNull { it.id == conversationId } ?: return@launch
                val messages = MigratedDomainStores.chat.messages(conversation.id).value
                val userIndex = messages.indexOfLast {
                    it.sender == LuluChatMessage.Sender.User &&
                        it.status == LuluChatMessage.Status.Sent
                }
                if (userIndex < 0) return@launch
                val user = messages[userIndex]
                val before = messages.subList(0, userIndex).indexOfLast {
                    it.sender == LuluChatMessage.Sender.Character &&
                        it.status == LuluChatMessage.Status.Sent
                }
                val userBatch = messages.subList(before + 1, userIndex + 1)
                    .filter { it.sender == LuluChatMessage.Sender.User &&
                        it.status == LuluChatMessage.Status.Sent }.takeLast(16)
                val replies = messages.drop(userIndex + 1).filter {
                    it.sender == LuluChatMessage.Sender.Character &&
                        it.status == LuluChatMessage.Status.Sent &&
                        (it.authorCharacterId ?: conversation.characterId) == characterId
                }
                if (userBatch.isEmpty() || replies.isEmpty()) return@launch
                val task = reconcileModelCommitment(
                    context = context.applicationContext, conversation = conversation,
                    user = user, userMessages = userBatch, characterId = characterId,
                    sourceTurnId = "${conversation.id}:${user.id}:$characterId",
                    evidenceIds = userBatch.map(LuluChatMessage::id),
                    userText = userBatch.joinToString("\n") { it.content },
                    roleText = replies.joinToString("\n") { it.content },
                )
                showWakeReceipt(conversation, user, characterId, task)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.e("LuluCommitment", "Accepted wake check failed after message delivery", failure)
            }
        }
    }

    /** One factual system receipt per accepted wake turn, never role-authored speech. */
    private fun showWakeReceipt(
        conversation: LuluConversation,
        userMessage: LuluChatMessage,
        characterId: String,
        task: CommitmentTask?,
    ) {
        // This UI receipt is only for a concrete timed wake-up. A generic
        // undated duty to supervise studying, bedtime and morning routines
        // must never be reported as a failed native wake alarm.
        if (task == null || task.dueAt == null || !task.isWakeResponsibility()) return
        val content = when (task.status) {
            CommitmentTaskStatus.Scheduled ->
                if (!task.linkedAlarmId.isNullOrBlank()) {
                    "${if (task.deliveryAction == "start_call") "角色来电" else "本机响铃"}已登记 · ${task.goal}。可在「日程·约定」查看执行状态。" +
                        if (task.lastActionResult.contains("非精确")) " 手机未授予精确闹钟权限，可能延迟。" else ""
                } else "叫醒尚未真正登记到手机，请检查日程任务状态。"
            CommitmentTaskStatus.NeedsClarification ->
                "叫醒还未登记：${task.lastActionResult.take(110)}。请先明确叫醒方式或时间。"
            CommitmentTaskStatus.Blocked ->
                "叫醒登记失败：${task.lastActionResult.take(110)}。请查看日程和相关权限。"
            else -> return
        }
        val failedId = "wake-choice-failed:${userMessage.id}:$characterId"
        val earlierFailure = MigratedDomainStores.chat.messages(conversation.id).value
            .firstOrNull { it.id == failedId }
        if (earlierFailure != null)
            MigratedDomainStores.chat.editMessage(
                failedId, "此前叫醒登记异常已恢复，请以新的真实系统登记回执为准。"
            )
        val id = "wake-schedule:${userMessage.id}:$characterId"
        val existing = MigratedDomainStores.chat.appendSystemReceipt(conversation.id, id, content)
        if (existing.content != content) MigratedDomainStores.chat.editMessage(id, content)
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
            val ensuredWake = reconcileModelCommitment(
                context = appContext ?: return@forEach,
                conversation = conversation, user = userMessage,
                userMessages = userBatch, characterId = characterId, sourceTurnId = sourceTurnId,
                evidenceIds = evidenceIds, userText = combinedUserText,
                roleText = characterText,
            )
            showWakeReceipt(conversation, userMessage, characterId, ensuredWake)
            // The fast and slow lanes share one model-recovery signature.
            // Never pay twice or re-run a previous interpreted deadline.
            if (isProcessed("commitment-model-recovery:$sourceTurnId") ||
                CommitmentDecisionRuntime.handled(
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
