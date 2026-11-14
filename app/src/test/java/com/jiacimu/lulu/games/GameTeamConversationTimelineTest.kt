package com.jiacimu.lulu.games

import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.SharedExperienceTimeline
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GameTeamConversationTimelineTest {
    @Test fun playerSpeechAndRoleReplyBecomeOneRoomInEachParticipantsOriginalTimeline() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        val first = "game-role-" + UUID.randomUUID()
        val second = "game-role-" + UUID.randomUUID()
        val stranger = "game-role-" + UUID.randomUUID()
        val run = UUID.randomUUID().toString()
        val now = Instant.now()
        assertTrue(GameTeamConversationTimeline.recordOwner(context, "block_island", run,
            listOf(first, second), "先去盖房子吧", true, "speech-1", now))
        assertTrue(GameTeamConversationTimeline.recordCompanion(context, "block_island", run,
            listOf(first, second), first, "我采木，你找地方", "reply-1", now.plusMillis(2)))
        for (role in listOf(first, second)) {
            val room = SharedExperienceTimeline.sessionEvents(role, "game-session-block_island-" + run)
            assertEquals(2, room.size)
            assertEquals("先去盖房子吧", room[0].content)
            assertEquals("game-team-owner-stt", room[0].source)
            assertEquals(EventEvidenceKind.UserStatement, room[0].evidenceKind)
            assertEquals("我采木，你找地方", room[1].content)
            assertEquals(EventEvidenceKind.CharacterStatement, room[1].evidenceKind)
            assertEquals(2, GameTeamConversationTimeline.recentLines(role, "block_island", run).size)
        }
        assertTrue(SharedExperienceTimeline.sessionEvents(stranger, "game-session-block_island-" + run).isEmpty())
        assertTrue(GameTeamConversationTimeline.recentLines(first, "block_island", "other-session").isEmpty())
    }

    @Test fun replayCannotEditSpokenWordsAndNonMemberCannotSpeakAsCharacter() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        val role = "game-role-" + UUID.randomUUID()
        val run = UUID.randomUUID().toString()
        val now = Instant.now()
        assertFalse(GameTeamConversationTimeline.recordOwner(context, "unknown", run,
            listOf(role), "not a real game", false))
        assertFalse(GameTeamConversationTimeline.recordCompanion(context, "relic_escape", run,
            listOf(role), "not-in-the-party", "should not be written"))
        assertTrue(GameTeamConversationTimeline.recordOwner(context, "relic_escape", run,
            listOf(role), "这个箱子让我开", false, "same-id", now))
        val eventId = "game-conversation-relic_escape-" + run + "-same-id-" + role
        val initial = SharedExperienceTimeline.eventsByIds(role, listOf(eventId)).single()
        assertTrue(GameTeamConversationTimeline.recordOwner(context, "relic_escape", run,
            listOf(role), "迟到回调不能改台词", true, "same-id", now.plusSeconds(30)))
        val replay = SharedExperienceTimeline.eventsByIds(role, listOf(eventId)).single()
        assertEquals(initial.content, replay.content)
        assertEquals(initial.occurredAt, replay.occurredAt)
        assertEquals(initial.revision, replay.revision)
        assertEquals("game-team-owner-text", replay.source)
    }
}
