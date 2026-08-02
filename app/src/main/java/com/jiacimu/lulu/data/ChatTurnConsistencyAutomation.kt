package com.jiacimu.lulu.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Keeps the persisted chat turn consistent when a previously failed user message is retried.
 *
 * The compact chat UI can retry the same message id. Once a character reply is successfully
 * appended, the matching user message must no longer remain marked as Failed. This observer is
 * intentionally store-level so every current and future chat surface gets the same behaviour.
 */
object ChatTurnConsistencyAutomation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conversationJobs = mutableMapOf<String, Job>()
    private var started = false

    @Synchronized
    fun initialize() {
        if (started) return
        started = true
        scope.launch {
            MigratedDomainStores.chat.conversations.collect { conversations ->
                val liveIds = conversations.mapTo(mutableSetOf()) { it.id }
                conversationJobs.keys
                    .filterNot { it in liveIds }
                    .forEach { conversationJobs.remove(it)?.cancel() }

                conversations.forEach { conversation ->
                    if (conversation.id in conversationJobs) return@forEach
                    conversationJobs[conversation.id] = scope.launch {
                        MigratedDomainStores.chat.messages(conversation.id)
                            .drop(1)
                            .collect { messages ->
                                val latest = messages.lastOrNull() ?: return@collect
                                if (
                                    latest.sender != LuluChatMessage.Sender.Character ||
                                    latest.status != LuluChatMessage.Status.Sent
                                ) {
                                    return@collect
                                }

                                val matchingUser = messages
                                    .dropLast(1)
                                    .lastOrNull { message ->
                                        message.sender == LuluChatMessage.Sender.User &&
                                            message.status == LuluChatMessage.Status.Failed
                                    }
                                    ?: return@collect

                                MigratedDomainStores.chat.editMessage(
                                    messageId = matchingUser.id,
                                    content = matchingUser.content,
                                )
                            }
                    }
                }
            }
        }
    }
}
