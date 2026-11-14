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

    @Test fun acceptedInviteCanEnterCompanionModeAfterOwnerPlayedSolo() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        LuluGames.initialize(context)
        val role = "invite-party-" + UUID.randomUUID()
        val store = LuluGames.store
        store.setPlayWithCharacter(false)
        assertFalse(store.state.value.playWithCharacter)
        // A valid invitation must use selectCharacters rather than selectCharacter:
        // the latter leaves an earlier solo session in solo mode.
        assertTrue(GameInvitationContinuity.recordAccepted(
            context, role, "invite-" + UUID.randomUUID(), "block_island"))
        store.selectCharacters(listOf(role))
        assertEquals(listOf(role), store.state.value.selectedCharacterIds)
        assertEquals(role, store.state.value.selectedCharacterId)
        assertTrue(store.state.value.playWithCharacter)
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
        // Two real world engines are now solo-playable as well; the invitation
        // catalogue is still separate and never proves the owner accepted.
        assertEquals(setOf("memory_match","yacht_dice","block_island","relic_escape"),
            AutonomousGameCatalog.gameNames.keys)
        assertEquals(6, GameInvitationCatalog.titles.size)
    }
}
