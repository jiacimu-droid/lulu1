package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.ownerVisibleQqConversations
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
class CharacterCreatedGroupTest {
    @Test fun characterCreatesActualGroupVisibleToMembersButNotOwnerQQ() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MomentFriendAccess.initialize(context)
        val a = MigratedDomainStores.characters.create("建群角色-" + UUID.randomUUID())
        val b = MigratedDomainStores.characters.create("被邀请角色-" + UUID.randomUUID())
        listOf(a, b).forEach {
            DigitalLifeProfileStore.registerNewLife(it.characterId, it.displayName, "数字生命")
        }
        assertTrue(MomentFriendAccess.setFriends(a.characterId, b.characterId, true))
        val result = CompanionActionRuntime.execute(
            context, a.characterId, "create_group_chat",
            JSONObject().put("groupName", "周末聊天小分队")
                .put("memberIds", JSONArray().put(b.characterId)),
        )
        assertTrue(result.summary, result.success)
        val group = MigratedDomainStores.chat.conversations.value
            .single { it.id == result.conversationId }
        assertFalse(group.groupChat!!.ownerIncluded)
        assertEquals(a.characterId, group.groupChat!!.creatorCharacterId)
        assertEquals(setOf(a.characterId, b.characterId),
            group.groupChat!!.members.map(LuluGroupMember::characterId).toSet())
        assertEquals(a.characterId, group.groupChat!!.members.single {
            it.role == LuluGroupRole.Owner
        }.characterId)
        assertTrue(ownerVisibleQqConversations(listOf(group)).isEmpty())
        val seen = SharedExperienceTimeline.eventsByIds(b.characterId, listOf(
            "group-joined-${group.id}-${b.characterId}-${group.updatedAt.toEpochMilli()}"
        )).single()
        assertEquals("group-membership", seen.source)
        assertEquals(EventEvidenceKind.Observation, seen.evidenceKind)
        assertTrue(seen.content.contains("邀请你加入"))
    }

    @Test fun unrelatedCharactersCannotBePulledIntoPrivateGroup() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        MomentFriendAccess.initialize(context)
        val a = MigratedDomainStores.characters.create("建群陌生人甲-" + UUID.randomUUID())
        val b = MigratedDomainStores.characters.create("建群陌生人乙-" + UUID.randomUUID())
        listOf(a, b).forEach {
            DigitalLifeProfileStore.registerNewLife(it.characterId, it.displayName, "数字生命")
        }
        val before = MigratedDomainStores.chat.conversations.value.size
        val result = CompanionActionRuntime.execute(context, a.characterId,
            "create_group_chat", JSONObject().put("groupName", "不应存在的群")
                .put("memberIds", JSONArray().put(b.characterId)))
        assertFalse(result.success)
        assertEquals(before, MigratedDomainStores.chat.conversations.value.size)
    }

    @Test fun groupAndPhoneVisitAreOrdinaryPerceptionObservations() {
        val now = Instant.parse("2026-10-26T07:00:00Z")
        fun event(source: String) = SharedTimelineEvent(
            "e-$source", "some-role", "手机·查看痕迹", "主人",
            "主人确实查看了你的手机", now, source = source,
            evidenceKind = EventEvidenceKind.Observation,
        )
        val observed = CharacterPerceptionContext.selectRecent(
            listOf(event("owner-phone-inspection"), event("group-membership")),
            now.plusSeconds(20),
        )
        assertEquals(2, observed.size)
        // A character waking after a long rest must still perceive the owner
        // browsing its phone or a genuine group invitation.
        assertEquals(2, CharacterPerceptionContext.selectRecent(
            listOf(event("owner-phone-inspection"), event("group-membership")),
            now.plusSeconds(259_200),
        ).size)
        assertTrue("user" in CharacterPerceptionContext.stimulus(
            event("owner-phone-inspection")).socialIds)
    }
}
