package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object CommitmentTurnAutomation {
    private const val PREFS_NAME = "lulu_commitment_turns"
    private const val KEY_SIGNATURES = "processed_signatures_v1"
    private const val QUIET_WINDOW_MS = 2_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()
    private var prefs: android.content.SharedPreferences? = null
    private var started = false

    @Synchronized
    fun initialize(context: Context) {
        if (started) return
        started = true
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        CommitmentTaskStore.initialize(context)
        scope.launch {
            MigratedDomainStores.chat.conversations.collectLatest { conversations ->
                val ids = conversations.mapTo(mutableSetOf()) { it.id }
                jobs.keys.filterNot(ids::contains).forEach { jobs.remove(it)?.cancel() }
                CommitmentTaskStore.snapshot()
                    .filter { task -> task.status.isActive() && task.sourceTurnId?.substringBefore(':') !in ids }
                    .forEach { task -> CommitmentTaskStore.cancel(task.id, "来源会话已被删除") }
                conversations.forEach { conversation ->
                    if (conversation.id in jobs) return@forEach
                    jobs[conversation.id] = scope.launch {
                        MigratedDomainStores.chat.messages(conversation.id).collectLatest {
                            // Cancellation/completion/reschedule of an existing responsibility is an
                            // urgent state update. Apply it as soon as the user's message arrives so a
                            // scheduled alarm/call cannot survive merely because the role has not yet
                            // produced its next chat bubble.
                            inspectImmediateUserUpdate(conversation)
                            delay(QUIET_WINDOW_MS)
                            inspectLatestTurn(conversation)
                        }
                    }
                }
            }
        }
    }

    private suspend fun inspectImmediateUserUpdate(conversation: LuluConversation) {
        val messages = MigratedDomainStores.chat.messages(conversation.id).value
        val latest = messages.lastOrNull()?.takeIf {
            it.sender == LuluChatMessage.Sender.User && it.status == LuluChatMessage.Status.Sent
        } ?: return
        if (!looksLikeImmediateTaskUpdate(latest.content)) return

        val candidateCharacterIds = conversation.groupChat
            ?.members
            ?.map(LuluGroupMember::characterId)
            ?.distinct()
            ?: listOf(conversation.characterId)
        candidateCharacterIds.forEach { characterId ->
            if (characterId.isBlank()) return@forEach
            val active = CommitmentTaskStore.active(characterId)
            if (active.isEmpty()) return@forEach
            val signature = "immediate:${conversation.id}:${latest.id}:$characterId"
            if (isProcessed(signature)) return@forEach
            val drafts = extractCommitmentTaskDrafts(
                characterId = characterId,
                userText = latest.content,
                characterText = "（角色尚未回复；这里只处理现有任务的改期、取消或完成，不得据此新建责任）",
                activeTasks = active,
            ).filter { draft -> draft.action in setOf("reschedule", "cancel", "complete") }
            applyCommitmentTaskDrafts(
                characterId = characterId,
                sourceTurnId = "${conversation.id}:${latest.id}:$characterId:immediate",
                sourceEventIds = listOf(latest.id),
                drafts = drafts,
            )
            markProcessed(signature)
        }
    }

    private suspend fun inspectLatestTurn(conversation: LuluConversation) {
        val messages = MigratedDomainStores.chat.messages(conversation.id).value
        reconcileDeletedSources(conversation.id, messages)
        val userIndex = messages.indexOfLast { it.sender == LuluChatMessage.Sender.User && it.status == LuluChatMessage.Status.Sent }
        if (userIndex < 0) return
        val userMessage = messages[userIndex]
        val replies = messages.drop(userIndex + 1).filter {
            it.sender == LuluChatMessage.Sender.Character && it.status == LuluChatMessage.Status.Sent
        }
        if (replies.isEmpty()) return

        val grouped = replies.groupBy { reply -> reply.authorCharacterId ?: conversation.characterId }
        grouped.forEach { (characterId, roleReplies) ->
            if (characterId.isBlank()) return@forEach
            val characterText = roleReplies.joinToString("\n", transform = LuluChatMessage::content).trim()
            if (!looksLikeTaskTurn(userMessage.content, characterText)) return@forEach
            val sourceTurnId = "${conversation.id}:${userMessage.id}:$characterId"
            val signature = "$sourceTurnId:${characterText.hashCode()}"
            if (isProcessed(signature)) return@forEach

            val active = CommitmentTaskStore.active(characterId)
            val drafts = extractCommitmentTaskDrafts(
                characterId = characterId,
                userText = userMessage.content,
                characterText = characterText,
                activeTasks = active,
            )
            val filtered = drafts.filterNot { draft ->
                draft.action == "create" && active.any { task ->
                    task.sourceTurnId == sourceTurnId && task.goal.taskKey() == draft.goal.taskKey()
                }
            }
            applyCommitmentTaskDrafts(
                characterId = characterId,
                sourceTurnId = sourceTurnId,
                sourceEventIds = listOf(userMessage.id) + roleReplies.map(LuluChatMessage::id),
                drafts = filtered,
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
                    task.sourceEventIds.any { sourceId -> sourceId !in liveMessageIds }
            }
            .forEach { task -> CommitmentTaskStore.cancel(task.id, "来源消息已删除，约定同步取消") }
    }

    private fun isProcessed(signature: String): Boolean =
        signature in prefs?.getStringSet(KEY_SIGNATURES, emptySet()).orEmpty()

    private fun markProcessed(signature: String) {
        val current = prefs?.getStringSet(KEY_SIGNATURES, emptySet()).orEmpty().toMutableList()
        current += signature
        prefs?.edit()?.putStringSet(KEY_SIGNATURES, current.takeLast(500).toSet())?.apply()
    }
}

private fun looksLikeTaskTurn(userText: String, characterText: String): Boolean {
    val text = "$userText\n$characterText"
    return commitmentTurnSignals.any { signal -> text.contains(signal, ignoreCase = true) }
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
)
