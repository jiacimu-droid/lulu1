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
                conversations.forEach { conversation ->
                    if (conversation.id in jobs) return@forEach
                    jobs[conversation.id] = scope.launch {
                        MigratedDomainStores.chat.messages(conversation.id).collectLatest {
                            delay(QUIET_WINDOW_MS)
                            inspectLatestTurn(conversation)
                        }
                    }
                }
            }
        }
    }

    private suspend fun inspectLatestTurn(conversation: LuluConversation) {
        val messages = MigratedDomainStores.chat.messages(conversation.id).value
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

    private fun isProcessed(signature: String): Boolean =
        signature in prefs?.getStringSet(KEY_SIGNATURES, emptySet()).orEmpty()

    private fun markProcessed(signature: String) {
        val current = prefs?.getStringSet(KEY_SIGNATURES, emptySet()).orEmpty().toMutableList()
        current += signature
        prefs?.edit()?.putStringSet(KEY_SIGNATURES, current.takeLast(300).toSet())?.apply()
    }
}

private fun looksLikeTaskTurn(userText: String, characterText: String): Boolean {
    val text = "$userText\n$characterText"
    return commitmentTurnSignals.any { signal -> text.contains(signal, ignoreCase = true) }
}

private fun String.taskKey(): String = lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), "").take(48)

private val commitmentTurnSignals = listOf(
    "提醒", "叫我", "喊我", "催我", "监督", "答应", "承诺", "约定", "说好", "负责",
    "我会", "我来", "交给我", "记得", "别忘", "到时候", "明早", "今晚", "明天", "几点",
    "睡一会", "起床", "叫醒", "改成", "不用叫", "不用提醒", "我醒了", "完成了",
)
