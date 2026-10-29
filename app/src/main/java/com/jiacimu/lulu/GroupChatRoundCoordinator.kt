package com.jiacimu.lulu

import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.archiveIdFor
import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.delay

/**
 * Single foreground owner of a group user's message batch. New owner input
 * cancels the old unpaid queue/unfinished generation before a new round starts.
 * In the background the existing durable per-role WorkManager path still works.
 */
internal object GroupChatRoundCoordinator {
    fun schedule(conversationId: String) {
        ChatReplyTaskManager.stop(conversationId)
        ChatReplyTaskManager.launch(conversationId) {
            // Collect bursts without showing anyone as "typing" before a model
            // actually begins that person's decision.
            delay(2_000L)
            val conversation = MigratedDomainStores.chat.conversations.value
                .firstOrNull { it.id == conversationId } ?: return@launch
            val group = conversation.groupChat ?: return@launch
            if (group.members.any { CompanionPresenceStore.isInCall(it.characterId) }) return@launch
            val messages = MigratedDomainStores.chat.messages(conversationId).value
            val lastCharacter = messages.indexOfLast { it.sender == LuluChatMessage.Sender.Character }
            val pending = messages.drop(lastCharacter + 1)
                .filter { it.sender == LuluChatMessage.Sender.User &&
                    it.status == LuluChatMessage.Status.Sent }
            if (pending.isEmpty()) return@launch
            val names = MigratedDomainStores.characters.settings.value.mapValues { it.value.displayName }
            val archiveId = LuluAiServices.connectionStore.library.value.archiveIdFor(ModelUsage.Chat)
            val modelLabel = LuluAiServices.connectionStore.library.value.archives
                .firstOrNull { it.id == archiveId }
                ?.let(LuluAiServices.connectionStore::archiveLabel) ?: "群聊模型"
            runGroupReplies(
                conversationId = conversationId,
                group = group,
                pendingText = pending.joinToString("\n") { qqForwardContextText(it.content) },
                initialHistory = "",
                activeLabel = modelLabel,
                archiveId = archiveId,
                characterNames = names,
                onError = { reportError(it) },
                onSpeakerChange = { setTypingCharacter(it) },
            )
        }
    }
}
