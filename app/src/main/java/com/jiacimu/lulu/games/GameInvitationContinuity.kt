package com.jiacimu.lulu.games

import android.content.Context
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.SharedExperienceTimeline
import java.time.Instant

/**
 * A click on a character-authored invite is a real user choice, not a completed match.
 * Reuse the shared timeline and the original invite message ID rather than a parallel
 * invitation/relationship database. Repeated taps never manufacture extra memories.
 */
internal object GameInvitationContinuity {
    fun recordAccepted(
        context: Context,
        inviterCharacterId: String,
        invitationMessageId: String,
        gameId: String,
        at: Instant = Instant.now(),
    ): Boolean {
        val characterId = inviterCharacterId.trim()
        val messageId = invitationMessageId.trim()
        val normalizedGame = gameId.trim().lowercase()
        val title = GameInvitationCatalog.title(normalizedGame) ?: return false
        if (characterId.isBlank() || messageId.isBlank()) return false
        SharedExperienceTimeline.initialize(context.applicationContext)
        val eventId = "game-invite-accepted-$characterId-$messageId"
        if (SharedExperienceTimeline.eventsByIds(characterId, listOf(eventId)).isNotEmpty()) return true
        SharedExperienceTimeline.record(
            eventId = eventId,
            characterId = characterId,
            channel = "共同游戏邀约",
            speaker = "主人操作",
            content = "主人点击接受了你发送的《$title》游戏邀请（gameId=$normalizedGame，邀请消息ID=$messageId）。这证明主人愿意进入游戏，不代表对局已经开始、完成或有人获胜；后续要以真实游戏记录为准。",
            occurredAt = at,
            triggerExtraction = false,
            sessionId = "game-invite-$messageId",
            source = "game-invitation-accepted",
            evidenceKind = EventEvidenceKind.Observation,
        )
        return SharedExperienceTimeline.eventsByIds(characterId, listOf(eventId)).isNotEmpty()
    }
}
