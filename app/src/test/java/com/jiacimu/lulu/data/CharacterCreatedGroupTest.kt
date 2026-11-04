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
        val seen = SharedExperienceTimeline.recentEvents(b.characterId, 100).single {
            it.sessionId == group.id && it.source == "group-membership"
        }
        assertEquals("group-membership", seen.source)
        assertEquals(EventEvidenceKind.Observation, seen.evidenceKind)
        assertTrue(seen.content.contains("邀请你加入"))
        val notice = MigratedDomainStores.chat.messages(group.id).value
            .single { it.sender == LuluChatMessage.Sender.System }
        assertTrue(notice.content.contains("群聊通知"))
        assertTrue(notice.content.contains("创建了"))
        assertTrue(SharedExperienceTimeline.eventsByIds(b.characterId,
            listOf(notice.id + ":group:" + b.characterId)).isEmpty())
    }

    @Test fun autonomousGroupSocialContextUsesOnlyActualWitnessedMessages() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        SharedExperienceTimeline.initialize(context)
        val a = MigratedDomainStores.characters.create("群消息甲-" + UUID.randomUUID())
        val b = MigratedDomainStores.characters.create("群消息乙-" + UUID.randomUUID())
        val outsider = MigratedDomainStores.characters.create("群外角色-" + UUID.randomUUID())
        listOf(a, b, outsider).forEach {
            DigitalLifeProfileStore.registerNewLife(it.characterId, it.displayName, "数字生命")
        }
        val group = MigratedDomainStores.chat.createGroupConversation(
            "花园里的计划", listOf(a.characterId, b.characterId), ownerIncluded = false)
        val old = MigratedDomainStores.chat.appendCharacterMessage(
            group.id, "最早的旧话题-不要反复触发", a.characterId)
        val first = MigratedDomainStores.chat.appendCharacterMessage(
            group.id, "我们继续聊那盆薄荷", b.characterId)
        val second = MigratedDomainStores.chat.appendCharacterMessage(
            group.id, "周六想给它换盆", a.characterId)
        val withdrawn = MigratedDomainStores.chat.appendCharacterMessage(
            group.id, "这条后来从乙的实际见证中撤回", a.characterId)

        val seen = CharacterEverydaySocialContext.witnessedGroupDialogue(b.characterId, group)
        assertEquals(3, seen.size)
        assertFalse(seen.any { it.contains(old.content) })
        assertTrue(seen.any { it.contains(first.content) })
        assertTrue(seen.any { it.contains(second.content) })
        assertTrue(seen.any { it.contains(withdrawn.content) })
        assertTrue(seen.any { it.contains(a.displayName) })

        val rendered = CharacterEverydaySocialContext.render(b.characterId)
        assertTrue(rendered.contains("groupId=${group.id}"))
        assertTrue(rendered.contains(first.content))
        assertTrue(rendered.contains(second.content))
        assertTrue(rendered.contains(withdrawn.content))
        assertTrue(CharacterEverydaySocialContext.witnessedGroupDialogue(
            outsider.characterId, group).isEmpty())
        assertFalse(CharacterEverydaySocialContext.render(outsider.characterId)
            .contains("groupId=${group.id}"))

        SharedExperienceTimeline.deleteEvent("${withdrawn.id}:group:${b.characterId}")
        val after = CharacterEverydaySocialContext.render(b.characterId)
        assertTrue(after.contains(first.content))
        assertTrue(after.contains(second.content))
        assertFalse(after.contains(withdrawn.content))
        assertEquals(3, CharacterEverydaySocialContext.witnessedGroupDialogue(
            b.characterId, group).size)
    }

    @Test fun retiredResidentPrivateThreadIsHiddenButSharedGroupAndStoredMessagesSurvive() {
        val residentId = "resident:retired-example"
        val normalId = "active-role"
        val at = Instant.parse("2026-11-04T09:00:00Z")
        val stale = LuluConversation("old-private", residentId, residentId,
            "[共同活动] 在游戏馆见面", at, unreadCount = 3, pinned = true)
        val active = LuluConversation("active-private", normalId, "好朋友", "你好", at)
        val shared = LuluConversation("shared-history", residentId, "旧群", "曾一起玩",
            at, groupChat = LuluGroupChat(
                "旧群", members = listOf(
                    LuluGroupMember(residentId),
                    LuluGroupMember(normalId),
                )))
        val rows = listOf(stale, active, shared)
        // A removed actor's private card used to show the raw resident:ID
        // and a giant broken layout, while still contributing to unread badges.
        assertEquals(listOf(active.id, shared.id),
            ownerVisibleQqConversations(rows, setOf(normalId)).map { it.id })
        assertEquals(listOf(stale.id, active.id, shared.id),
            ownerVisibleQqConversations(rows, setOf(residentId, normalId)).map { it.id })
        // This is a read-only list projection: the archived records remain
        // available for their witnesses and for legitimate historical views.
        assertEquals(3, rows.size)
        assertEquals("[共同活动] 在游戏馆见面", stale.lastMessage)
    }

    @Test fun groupInviteIsInThePersonalTimelineAndRejoiningHasNewEvidence() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        SharedExperienceTimeline.initialize(context)
        val a = MigratedDomainStores.characters.create("建群-" + UUID.randomUUID()).characterId
        val b = MigratedDomainStores.characters.create("重进-" + UUID.randomUUID()).characterId
        val c = MigratedDomainStores.characters.create("留群-" + UUID.randomUUID()).characterId
        val created = MigratedDomainStores.chat.createGroupConversation(
            "周末一起聊天", listOf(a, b, c))
        val membership = created.groupChat!!
        val initial = SharedExperienceTimeline.recentEvents(b, 100)
            .single { it.sessionId == created.id && it.source == "group-membership" }
        assertTrue(initial.content.contains("周末一起聊天"))
        assertTrue(initial.content.contains("群成员"))
        assertTrue(initial.content.contains("拉进了群聊"))
        val leaving = membership.copy(members = membership.members.filterNot { it.characterId == b })
        assertTrue(MigratedDomainStores.chat.updateGroupConversation(created.id, leaving))
        val returnGroup = membership.copy(members = membership.members)
        assertTrue(MigratedDomainStores.chat.updateGroupConversation(created.id, returnGroup))
        val history = SharedExperienceTimeline.recentEvents(b, 150)
            .filter { it.sessionId == created.id && it.source == "group-membership" }
        assertEquals(3, history.size)
        assertEquals(2, history.count { it.content.contains("拉进了群聊") })
        assertEquals(1, history.count { it.content.contains("移出了群聊") })
        assertEquals(3, history.map { it.id }.distinct().size)
        // The remaining members get actual persisted membership-change notices;
        // the group change is not secret just because only the added member has a new invite.
        assertTrue(SharedExperienceTimeline.recentEvents(c, 160).any {
            it.sessionId == created.id && it.content.contains("群成员变更")
        })
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

    @Test fun legacyPhoneNavigationIsNotAStimulusButRealGroupInvitesStillAre() {
        val now = Instant.parse("2026-10-26T07:00:00Z")
        fun event(source: String) = SharedTimelineEvent(
            "e-$source", "some-role", "手机·查看痕迹", "主人",
            "过去查看过角色手机页面", now, source = source,
            evidenceKind = EventEvidenceKind.Observation,
        )
        val oldPhonePage = event("owner-phone-inspection")
        val invitation = event("group-membership")
        val recent = CharacterPerceptionContext.selectRecent(
            listOf(oldPhonePage, invitation), now.plusSeconds(20))
        assertEquals(listOf(invitation.id), recent.map { it.id })
        // Historical inspection events stay in the archive; they no longer
        // remain permanently eligible or distort the next character turn.
        assertEquals(listOf(invitation.id), CharacterPerceptionContext.selectRecent(
            listOf(oldPhonePage, invitation), now.plusSeconds(259_200),
        ).map { it.id })
    }
}
