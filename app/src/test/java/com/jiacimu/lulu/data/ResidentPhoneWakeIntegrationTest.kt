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
