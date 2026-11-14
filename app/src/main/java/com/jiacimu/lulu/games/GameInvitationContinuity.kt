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
    /** An actual model-accepted lobby join, not an accepted chat invitation or a started match. */
    fun recordPartyJoined(
        context: Context, characterId: String, gameId: String, partySessionId: String,
        at: Instant = Instant.now(),
    ): Boolean = recordPartyFact(context, characterId, gameId, partySessionId,
        "joined", "你刚刚自行决定接受主人的组队邀请，已经进入游戏大厅的队伍；目前只是入队，尚未证明已经开局。", at)

    /** Only call after the game engine confirms starting the actual shared run. */
    fun recordPartyStarted(
        context: Context, characterId: String, gameId: String, partySessionId: String,
        at: Instant = Instant.now(),
    ): Boolean = recordPartyFact(context, characterId, gameId, partySessionId,
        "started", "主人已和你正式进入同一局游戏；这是已开局的真实事实，并不意味着已经获胜或完成目标。", at)

    private fun recordPartyFact(
        context: Context, characterId: String, gameId: String, partySessionId: String,
        stage: String, fact: String, at: Instant,
    ): Boolean {
        val title = GameInvitationCatalog.title(gameId) ?: return false
        if (characterId.isBlank() || partySessionId.isBlank()) return false
        SharedExperienceTimeline.initialize(context.applicationContext)
        val id = "game-party-$stage-$gameId-$characterId-$partySessionId"
        if (SharedExperienceTimeline.eventsByIds(characterId, listOf(id)).isNotEmpty()) return true
        SharedExperienceTimeline.record(
            eventId = id,
            characterId = characterId,
            channel = "共同游戏《$title》",
            speaker = "游戏现场",
            content = fact,
            occurredAt = at,
            triggerExtraction = false,
            sessionId = "game-party-$partySessionId",
            source = "game-party-$stage",
            evidenceKind = EventEvidenceKind.Observation,
        )
        return SharedExperienceTimeline.eventsByIds(characterId, listOf(id)).isNotEmpty()
    }

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
