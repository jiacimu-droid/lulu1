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
class GameInvitationContinuityTest {
    @Test fun acceptanceCreatesOneRoleScopedObservationWithoutClaimingGameFinished() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        val role = "invite-role-${UUID.randomUUID()}"
        val messageId = "chat-${UUID.randomUUID()}"
        val now = Instant.now()
        assertFalse(GameInvitationContinuity.recordAccepted(context, role, messageId, "invented_game", now))
        assertFalse(GameInvitationContinuity.recordAccepted(context, role, "", "memory_match", now))
        assertTrue(GameInvitationContinuity.recordAccepted(context, role, messageId, "memory_match", now))
        val evidenceId = "game-invite-accepted-$role-$messageId"
        val first = SharedExperienceTimeline.eventsByIds(role, listOf(evidenceId)).single()
        assertEquals(role, first.characterId)
        assertEquals("game-invitation-accepted", first.source)
        assertEquals(EventEvidenceKind.Observation, first.evidenceKind)
        assertTrue(first.content.contains("点击接受"))
        assertTrue(first.content.contains("不代表对局已经开始"))
        assertTrue(first.content.contains("真实游戏记录"))
        assertTrue(GameInvitationContinuity.recordAccepted(context, role, messageId, "memory_match", now.plusSeconds(30)))
        val repeated = SharedExperienceTimeline.eventsByIds(role, listOf(evidenceId)).single()
        assertEquals(first.revision, repeated.revision)
        assertEquals(first.occurredAt, repeated.occurredAt)
    }

    @Test fun invitingOtherCharacterCannotClaimOwnersResponse() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        val role = "invite-scope-${UUID.randomUUID()}"
        val messageId = "shared-${UUID.randomUUID()}"
        val at = Instant.now()
        assertTrue(GameInvitationContinuity.recordAccepted(context, role, messageId, "yacht_dice", at))
        assertTrue(SharedExperienceTimeline.eventsByIds("unrelated-character", listOf(
            "game-invite-accepted-$role-$messageId")).isEmpty())
        assertNull(GameInvitationCatalog.title("unknown"))
        assertEquals(2, AutonomousGameCatalog.gameNames.size)
        assertEquals(6, GameInvitationCatalog.titles.size)
    }
}
