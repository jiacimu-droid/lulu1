package com.jiacimu.lulu.games

import android.content.Context
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.UserProfileContext
import java.time.Instant
import java.util.UUID

/**
 * Original-world game conversation adapter. Only witnessed player messages and
 * accepted role speech become facts; no parallel chat store or additional AI call.
 */
internal object GameTeamConversationTimeline {
    private val titles = mapOf("block_island" to "方块小岛", "relic_escape" to "迷雾摸金")
    private fun room(game: String, run: String) = "game-session-" + game + "-" + run
    private fun eventId(game: String, run: String, utterance: String, recipient: String) =
        "game-conversation-" + game + "-" + run + "-" + utterance + "-" + recipient

    fun recordOwner(
        context: Context, game: String, run: String, participants: Collection<String>,
        text: String, fromSpeech: Boolean,
        utteranceId: String = UUID.randomUUID().toString(),
        at: Instant = Instant.now(),
    ): Boolean = record(context, game, run, participants, text, null, utteranceId, at,
        if (fromSpeech) "game-team-owner-stt" else "game-team-owner-text")

    fun recordCompanion(
        context: Context, game: String, run: String, participants: Collection<String>,
        authorId: String, text: String,
        utteranceId: String = UUID.randomUUID().toString(),
        at: Instant = Instant.now(),
    ): Boolean = record(context, game, run, participants, text, authorId, utteranceId, at,
        "game-team-companion")

    private fun record(
        context: Context, game: String, run: String, participants: Collection<String>,
        text: String, authorId: String?, utteranceId: String, at: Instant, source: String,
    ): Boolean {
        val title = titles[game] ?: return false
        val recipients = participants.filter { it.isNotBlank() }.distinct()
        val clean = text.trim()
        if (recipients.isEmpty() || run.isBlank() || utteranceId.isBlank() || clean.isEmpty() ||
            (authorId != null && authorId !in recipients)) return false
        SharedExperienceTimeline.initialize(context.applicationContext)
        val speaker = if (authorId == null) UserProfileContext.displayLabel()
            else MigratedDomainStores.characters.get(authorId).displayName
        val kind = if (authorId == null) EventEvidenceKind.UserStatement
            else EventEvidenceKind.CharacterStatement
        recipients.forEach { recipient ->
            val id = eventId(game, run, utteranceId, recipient)
            // Replayed callbacks cannot rewrite the original heard utterance.
            if (SharedExperienceTimeline.eventsByIds(recipient, listOf(id)).isEmpty()) {
                SharedExperienceTimeline.record(
                    eventId = id, characterId = recipient,
                    channel = "共同游戏《" + title + "》·队伍交流",
                    speaker = speaker, content = clean, occurredAt = at,
                    sessionId = room(game, run), source = source, evidenceKind = kind,
                )
            }
        }
        return recipients.all { recipient ->
            SharedExperienceTimeline.eventsByIds(recipient,
                listOf(eventId(game, run, utteranceId, recipient))).isNotEmpty()
        }
    }

    /** Bounded first-hand session conversation for this role's existing game-model call. */
    fun recentLines(characterId: String, game: String, run: String): List<String> {
        if (characterId.isBlank() || game !in titles || run.isBlank()) return emptyList()
        return SharedExperienceTimeline.sessionEvents(characterId, room(game, run), 12)
            .filter { it.source.startsWith("game-team-") }
            .takeLast(8)
            .map { it.speaker + "：" + it.content.take(240) }
    }
}
