package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ResidentPhoneWakeIntegrationTest {
    private val app get() = RuntimeEnvironment.getApplication() as Context

    @Before fun init() {
        if (runCatching { androidx.work.WorkManager.getInstance(app) }.isFailure)
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        LuluRepositories.initialize(app)
        SharedExperienceTimeline.initialize(app)
        MigratedDomainStores.initialize(app)
        DigitalLifeProfileStore.initialize(app)
        DigitalWorldExpansionStore.initialize(app)
        DigitalWorldStore.initialize(app)
        ResidentSocialMailbox.initialize(app)
        MomentsStore.initialize(app)
        CharacterPhoneInbox.initialize(app)
    }

    private fun role(): String {
        val person = MigratedDomainStores.characters.create("手机居民-${UUID.randomUUID().toString().take(8)}")
        DigitalLifeProfileStore.registerNewLife(person.characterId, person.displayName,
            "数字生命", Instant.now())
        return person.characterId
    }

    @Test fun aPostCreatesARedDotButNoMentalObservationBeforePhoneRead() {
        val viewer = role()
        assertTrue(MomentFriendAccess.areFriends(MomentFriendAccess.OWNER, viewer))
        val post = MomentsStore.publishUser("新的QQ式朋友圈-${UUID.randomUUID()}")!!
        assertTrue(CharacterPhoneInbox.unreadMoments(viewer).any { it.id == post.id })
        assertTrue(SharedExperienceTimeline.eventsByIds(
            viewer, listOf("moment-${post.id}-$viewer")).isEmpty())
        val snapshot = CharacterPhoneInbox.modelContext(viewer,
            CharacterPhoneInbox.unreadMoments(viewer))
        assertTrue(snapshot.contains(post.id))
        assertTrue(snapshot.contains("朋友圈小红点"))
        assertTrue(CharacterPhoneInbox.acknowledge(viewer, listOf(post.id)))
        assertTrue(CharacterPhoneInbox.unreadMoments(viewer).none { it.id == post.id })
        val viewed = SharedExperienceTimeline.eventsByIds(
            viewer, listOf("moment-${post.id}-$viewer"))
        assertEquals(1, viewed.size)
        assertEquals("moment:phone-read", viewed.single().source)
    }

    @Test fun equalTimestampPostBoundaryKeepsUnreadTieAndLegacyCursorReadable() {
        val at = Instant.parse("2026-10-26T08:00:00Z")
        assertTrue(CharacterPhoneInbox.afterCursor(at, "post-b", at, "post-a"))
        assertFalse(CharacterPhoneInbox.afterCursor(at, "post-a", at, "post-a"))
        assertFalse(CharacterPhoneInbox.afterCursor(at, "post-0", at, "post-a"))
        assertTrue("Migrated timestamp-only cursors must not swallow equal-time posts",
            CharacterPhoneInbox.afterCursor(at, "post-a", at, ""))
        assertTrue(CharacterPhoneInbox.afterCursor(at.plusNanos(1), "post-0", at, "post-z"))
        assertFalse(CharacterPhoneInbox.afterCursor(at.minusNanos(1), "post-z", at, "post-a"))
    }

    @Test fun aNewLikeAfterReadingCreatesAnotherRedDotWithoutTelepathy() {
        val viewer = role()
        val liker = role()
        val post = MomentsStore.publishUser("看到这条了吗-${UUID.randomUUID()}")!!
        assertTrue(CharacterPhoneInbox.acknowledgeObserved(viewer,
            CharacterPhoneInbox.unreadMoments(viewer)))
        assertTrue(CharacterPhoneInbox.unreadMoments(viewer).none { it.id == post.id })
        assertTrue(MomentsStore.likeAsCharacter(post.id, liker))
        assertTrue("New real interaction must wake the feed red dot on the next normal wake",
            CharacterPhoneInbox.unreadMoments(viewer).any { it.id == post.id })
        assertTrue(CharacterPhoneInbox.unreadCount(viewer) > 0)
        assertTrue(CharacterPhoneInbox.acknowledgeObserved(viewer,
            CharacterPhoneInbox.unreadMoments(viewer)))
        assertFalse(CharacterPhoneInbox.unreadMoments(viewer).any { it.id == post.id })
    }

    @Test fun inspectingAFriendsPhoneRecordsOwnerProxyLikeWithoutTelepathicFanout() {
        val actor = role()
        val friend = role()
        val post = MomentsStore.publishUser("手机代点赞验证-${UUID.randomUUID()}")!!
        assertTrue(CharacterPhoneInbox.acknowledgeObserved(friend,
            CharacterPhoneInbox.unreadMoments(friend)))
        assertTrue(MomentsStore.toggleInspectedCharacterLike(post.id, actor))
        val afterLike = MomentsStore.posts.value.first { it.id == post.id }
        assertTrue(actor in afterLike.likedCharacterIds)
        val proxy = SharedExperienceTimeline.eventsByIds(actor,
            listOf("moment-like-${post.id}-$actor")).single()
        assertEquals("owner-on-behalf", proxy.source)
        assertTrue(proxy.content.contains("不是角色自主决定"))
        assertTrue("The friend observes their feed only when their phone is read",
            CharacterPhoneInbox.unreadMoments(friend).any { it.id == post.id })
        assertTrue(SharedExperienceTimeline.eventsByIds(friend,
            listOf("moment-visible-like-${post.id}-$actor-$friend")).isEmpty())
        assertTrue(MomentsStore.toggleInspectedCharacterLike(post.id, actor))
        assertFalse(actor in MomentsStore.posts.value.first { it.id == post.id }.likedCharacterIds)
        assertTrue(SharedExperienceTimeline.eventsByIds(actor,
            listOf("moment-like-${post.id}-$actor")).isEmpty())
    }

    @Test fun requestAndConsentAreSeparateEventsAndLeaveQQThreadNotices() {
        val from = role()
        val to = role()
        assertFalse(MomentFriendAccess.areFriends(from, to))
        assertFalse(CharacterFriendRequests.pendingFor(to).any { it.fromId == from })
        assertTrue(DigitalWorldStore.performAction(from, "visit_public_place",
            JSONObject().put("locationCode", DigitalWorldPublicPlaces.COURTYARD)).success)
        assertTrue(DigitalWorldStore.performAction(to, "visit_public_place",
            JSONObject().put("locationCode", DigitalWorldPublicPlaces.COURTYARD)).success)
        val requested = CharacterFriendRequests.send(from, to, "刚才聊得挺愉快")
        assertFalse(MomentFriendAccess.areFriends(from, to))
        assertEquals(requested.id, CharacterFriendRequests.pendingFor(to)
            .single { it.fromId == from }.id)
        val accepted = CharacterFriendRequests.respond(to, requested.id, accept = true)
        assertEquals("accepted", accepted.status)
        assertTrue(MomentFriendAccess.areFriends(from, to))
        assertTrue(MomentFriendAccess.areFriends(to, from))
        assertTrue(CharacterFriendRequests.pendingFor(to).none { it.id == requested.id })
        val event = SharedExperienceTimeline.eventsByIds(to,
            listOf("friend-request-${requested.id}-accepted-$to")).single()
        assertEquals("手机·好友消息", event.channel)
        assertTrue(event.content.contains("你们已成为好友"))
        assertEquals(1, CharacterFriendRequests.historyFor(from)
            .count { it.id == requested.id }) // one durable evolving application
    }

    @Test fun phoneStepsAreRealIndependentToolReceiptsAndNeverCompleteAnUnrelatedPlan() = runBlocking {
        initializeBackgroundRuntime(app)
        val actor = role()
        val target = MomentsStore.publishUser("来留言呀-${UUID.randomUUID()}")!!
        val plan = RoleDayScheduleStore.arrange(
            app, actor, "明天去读书", "我想读一会儿自己的书",
            Instant.now().plusSeconds(7200), activityAction = "play_solo_game",
            activityArgs = JSONObject().put("gameId", "memory_match"))
        val extra = JSONObject().put("steps", JSONArray()
            .put(JSONObject().put("tool", "like_moment")
                .put("args", JSONObject().put("postId", target.id)))
            .put(JSONObject().put("tool", "comment_moment")
                .put("args", JSONObject().put("postId", target.id)
                    .put("text", "这条我看到了！"))))
        val count = ResidentDailyDecision.executeWakeSteps(
            app, actor, extra, "phone-wake-${UUID.randomUUID()}")
        assertEquals(2, count)
        val saved = MomentsStore.posts.value.first { it.id == target.id }
        assertTrue(actor in saved.likedCharacterIds)
        assertTrue(saved.comments.any { it.characterId == actor && it.content == "这条我看到了！" })
        assertEquals("planned", RoleDayScheduleStore.plans(app, actor)
            .first { it.id == plan.id }.status)
    }
}
