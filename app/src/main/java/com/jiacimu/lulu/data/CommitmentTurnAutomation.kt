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
import com.jiacimu.lulu.ai.ModelUsage
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

    /** Preserve failures as machine-written receipts, never as character speech. */
    private fun showWakeScheduleProblem(
        conversation: LuluConversation,
        user: LuluChatMessage,
        characterId: String,
        reason: String,
    ) {
        val content = "叫醒约定尚未安排成功 · ${reason.take(130)}。手机上没有可确认的新叫醒任务。"
        val id = "wake-choice-failed:${user.id}:$characterId"
        val message = MigratedDomainStores.chat.appendSystemReceipt(conversation.id, id, content)
        if (message.content != content) MigratedDomainStores.chat.editMessage(id, content)
    }

    /** Return the character's explicitly selected executable method, never a guessed default. */
    internal fun parseModelWakeChoice(response: String): String? = runCatching {
        val clean = response.trim().removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val json = JSONObject(clean)
        if (!json.optBoolean("accepted", false)) null
        else json.optString("deliveryAction").takeIf {
            it in setOf("start_call", "alarm_only")
        }
    }.getOrNull()

    /**
     * This recovery is invoked ONLY when the character has already spoken and
     * a clear time for "wake me" has been offered. Model owns *whether the
     * character accepted* and *how they want to fulfil it*. Native code owns
     * time validation, permission, persistence and delivery receipts.
     *
     * One bounded extra model request occurs only on missing/incomplete wake
     * commitmentChanges; a successful original model action is reused instead.
     */
    private suspend fun reconcileRoleChosenWake(
        context: Context,
        conversation: LuluConversation,
        user: LuluChatMessage,
        sourceTurnId: String,
        evidenceIds: List<String>,
        userText: String,
        roleText: String,
        characterId: String,
    ): CommitmentTask? = wakeChoiceMutex.withLock {
        val zone = ZoneId.systemDefault()
        val normalized = userText.trim()
        val recognized = RelativeWakeCommitmentParser.parse(normalized, roleText, user.createdAt, zone)
            ?: WakeCommitmentParser.parse(normalized, roleText, user.createdAt, zone)
            ?: return@withLock null
        if (recognized.dueAt?.isAfter(Instant.now().plusSeconds(5)) != true)
            return@withLock null

        val saved = CommitmentTaskStore.snapshot(characterId).firstOrNull { task ->
            task.isWakeResponsibility() && evidenceIds.any { id ->
                task.sourceEventIds.any { it.substringBefore(":group:") ==
                    id.substringBefore(":group:") }
            }
        }
        val acceptedRoutes = setOf("start_call", "alarm_only")
        val route = if (saved?.deliveryAction in acceptedRoutes) {
            saved?.deliveryAction.orEmpty()
        } else {
            val callEnabled = MigratedDomainStores.characters.get(characterId)
                .contactPolicy.proactiveCallsEnabled
            val facts = buildString {
                appendLine("角色当前正在履行自己刚刚在私聊里说出口的约定。")
                appendLine("用户本轮原话：$userText")
                appendLine("角色已经发出的原话：$roleText")
                appendLine("用户要求的准确叫醒时间：${recognized.dueAt}")
                appendLine("角色主动来电权限：${if (callEnabled) "已开启" else "未开启"}")
                appendLine("此前是否已有相关任务：${saved?.lastActionResult ?: "没有"}")
                val preferences = CharacterRelationshipPreferences.promptSection(characterId)
                if (preferences.isNotBlank()) appendLine(preferences)
            }
            val decision = try {
                LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = facts,
                instruction = """
                    你是刚才发出上述回复的同一个角色。依据自己的性格、和用户的关系、
                    用户已经表达的相处偏好、手机可用的能力，判断是否确实接受了定时叫醒。
                    这次只补齐缺失的执行方式，不改写角色已发言、关系、人设或约定的时间。
                    已明确答应时不要因"不想增加任务"而否认自己的承诺。
                    如果用户没有规定方式，自己选择：start_call=角色真实来电
                    （须开启主动来电），alarm_only=本机有声闹钟。
                    不可使用 send_private_message 冒充能叫醒熟睡用户的响铃。
                    如果用户明确要求来电，不可私自改成闹钟。
                    如果主动来电未开启，可自行选择本机闹钟（仅当用户允许更换方式）。
                    用户喜欢哪种方式，仅是偏好；不是所有角色都必须做同一种决定。
                    只返回一个 JSON 对象：
                    {"accepted":true,"deliveryAction":"start_call|alarm_only"}
                    未承担约定时返回 {"accepted":false,"deliveryAction":""}。
                    不要模拟执行，也不要声称闹钟已登记。
                """.trimIndent(),
                source = "已答应叫醒约定·行动方式修复",
                title = "约定履行方式",
                maxTokens = 180,
                usage = ModelUsage.Chat,
            ).getOrThrow().text
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showWakeScheduleProblem(conversation, user, characterId,
                    "行动方式模型请求失败（${error.javaClass.simpleName}），请检查模型连接")
                throw error
            }
            val choice = parseModelWakeChoice(decision)
            if (choice == null) {
                showWakeScheduleProblem(conversation, user, characterId,
                    "没有取得有效的叫醒方式，电话与闹钟均未登记")
                return@withLock null
            }
            choice
        }
        if (route !in acceptedRoutes) {
            showWakeScheduleProblem(conversation, user, characterId, "未选出可执行的有声叫醒方式")
            return@withLock null
        }
        val task = CommitmentDecisionRuntime.ensureAcceptedWake(
            context = context, characterId = characterId, sourceTurnId = sourceTurnId,
            evidenceIds = evidenceIds, userText = normalized, roleText = roleText,
            userAt = user.createdAt, selectedDeliveryAction = route,
        )
        if (task == null) showWakeScheduleProblem(
            conversation, user, characterId,
            "角色选择的方式与用户明确的要求冲突，未擅自更换方式"
        )
        task
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
                val task = reconcileRoleChosenWake(
                    context = context.applicationContext, conversation = conversation,
                    user = user, characterId = characterId,
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
        if (task == null) return
        val content = when (task.status) {
            CommitmentTaskStatus.Scheduled ->
                if (!task.linkedAlarmId.isNullOrBlank()) {
                    "${if (task.deliveryAction == "start_call") "角色来电" else "本机响铃"}已登记 · ${task.goal}。可在「日程·约定」查看执行状态。" +
                        if (task.lastActionResult.contains("非精确")) " 手机未授予精确闹钟权限，可能延迟。" else ""
                } else "叫醒尚未真正登记到手机，请检查日程任务状态。"
            CommitmentTaskStatus.Blocked, CommitmentTaskStatus.NeedsClarification ->
                "叫醒约定未能安排成功：${task.lastActionResult.take(110)}。请检查日程和手机权限。"
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
            val ensuredWake = reconcileRoleChosenWake(
                context = appContext ?: return@forEach,
                conversation = conversation, user = userMessage,
                characterId = characterId, sourceTurnId = sourceTurnId,
                evidenceIds = evidenceIds, userText = combinedUserText,
                roleText = characterText,
            )
            showWakeReceipt(conversation, userMessage, characterId, ensuredWake)
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
