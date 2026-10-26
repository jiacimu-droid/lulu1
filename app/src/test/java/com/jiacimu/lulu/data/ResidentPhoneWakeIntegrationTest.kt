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
        // Other tests deliberately publish >1 phone page. Establish this
        // new viewer's baseline through real paging instead of assuming that
        // every later post fits in the oldest-first eight-item page.
        while (true) {
            val page = CharacterPhoneInbox.unreadMoments(person.characterId)
            if (page.isEmpty()) break
            assertTrue(CharacterPhoneInbox.acknowledgeObserved(person.characterId, page))
        }
        return person.characterId
    }

    @Test fun wakeDecisionUsesOnlyExecutableResidentToolContracts() {
        val lines = CompanionActionRuntime.capabilityContext(app, "resident:catalog-test")
        val projected = ResidentDailyDecision.wakeToolContext(lines)
        for (name in ResidentDailyDecision.wakeTools) assertTrue(name, projected.contains("- $name"))
        assertTrue(projected.contains("postId"))
        assertTrue(projected.contains("targetCharacterId"))
        assertFalse(projected.contains("- capture_screen"))
        assertFalse(projected.contains("- send_private_message"))
    }

    @Test fun socialContextShowsOwnActualPostsWithoutTreatingDraftsAsPublished() {
        val actor = role()
        val post = MomentsStore.publishCharacter(actor, "今天在院子里听风-${UUID.randomUUID()}")!!
        val facts = CharacterEverydaySocialContext.render(actor)
        assertTrue(facts.contains("postId=${post.id}"))
        assertTrue(facts.contains(post.content))
        assertTrue(facts.contains("publish_moment"))
        MomentsStore.delete(post.id)
        assertFalse(CharacterEverydaySocialContext.render(actor).contains("postId=${post.id}"))
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

    @Test fun failedPredecessorSkipsOnlyDependentStepsAndPreservesLegacyReceiptIds() = runBlocking {
        initializeBackgroundRuntime(app)
        val actor = role()
        val post = MomentsStore.publishUser("独立动作-${UUID.randomUUID()}")!!
        val turn = "dependency-wake-${UUID.randomUUID()}"
        val steps = JSONArray()
            .put(JSONObject().put("tool", "comment_moment").put("requiresPreviousSuccess", true)
                .put("args", JSONObject().put("postId", post.id).put("text", "不应发送")))
            .put(JSONObject().put("tool", "like_moment").put("requiresPreviousSuccess", false)
                .put("args", JSONObject().put("postId", post.id)))
        val decision = JSONObject().put("steps", steps)
        val originalDecision = decision.toString()
        assertEquals(1, ResidentDailyDecision.executeWakeSteps(app, actor, decision, turn,
            initialCompleted = false))
        assertEquals(1, ResidentDailyDecision.executeWakeSteps(app, actor, decision, turn,
            initialCompleted = false))
        val saved = MomentsStore.posts.value.first { it.id == post.id }
        assertTrue(actor in saved.likedCharacterIds)
        assertFalse(saved.comments.any { it.characterId == actor })
        val receipts = SharedExperienceTimeline.all(actor).filter { it.source == "tool-router" }
        assertEquals(1, receipts.count { it.id == "tool-$actor-$turn-extra-1" })
        assertFalse(receipts.any { it.id == "tool-$actor-$turn-extra-0" })
        assertEquals(originalDecision, decision.toString())
    }

    @Test fun sceneDenialCannotUnlockDependentSocialActionButIndependentActionContinues() = runBlocking {
        initializeBackgroundRuntime(app)
        val actor = role()
        val post = MomentsStore.publishUser("场景边界-${UUID.randomUUID()}")!!
        val decision = JSONObject().put("steps", JSONArray()
            .put(JSONObject().put("tool", "capture_screen").put("args", JSONObject()))
            .put(JSONObject().put("tool", "comment_moment").put("requiresPreviousSuccess", true)
                .put("args", JSONObject().put("postId", post.id).put("text", "不会发送")))
            .put(JSONObject().put("action", "moment").put("text", "独立分享-${UUID.randomUUID()}")))
        assertEquals(1, ResidentDailyDecision.executeWakeSteps(app, actor, decision,
            "boundary-wake-${UUID.randomUUID()}"))
        assertFalse(MomentsStore.posts.value.first { it.id == post.id }.comments.any {
            it.characterId == actor })
        assertTrue(MomentsStore.posts.value.any {
            it.authorCharacterId == actor && it.content == decision.getJSONArray("steps")
                .getJSONObject(2).getString("text") })
    }

    @Test fun wakeDoesNotSilentlyDiscardTheNinthExplicitAction() = runBlocking {
        initializeBackgroundRuntime(app)
        val actor = role()
        val posts = (1..9).map { MomentsStore.publishUser("多步-$it-${UUID.randomUUID()}")!! }
        val decision = JSONObject().put("steps", JSONArray().apply {
            posts.forEach { post -> put(JSONObject().put("tool", "like_moment")
                .put("args", JSONObject().put("postId", post.id))) }
        })
        assertEquals(9, ResidentDailyDecision.executeWakeSteps(app, actor, decision,
            "complete-wake-${UUID.randomUUID()}"))
        assertTrue(posts.all { post -> actor in MomentsStore.posts.value.first {
            it.id == post.id }.likedCharacterIds })
    }

    @Test fun eachWakeStepKeepsItsOwnMotiveLinkAndDetachedPlanCannotComplete() = runBlocking {
        initializeBackgroundRuntime(app)
        val actor = role()
        val now = Instant.now()
        val origin = "social-source-${UUID.randomUUID()}"
        SharedExperienceTimeline.record(origin, actor, "真实观察", "执行器", "刚在院子看见一片红叶",
            now, triggerExtraction = false, evidenceKind = EventEvidenceKind.Observation)
        CharacterInnerLifeStore.observe(actor, origin, "自己的所见", JSONObject()
            .put("motives", JSONArray().put(JSONObject().put("op", "start")
                .put("aim", "想分享院子的红叶").put("why", "自己觉得很漂亮"))), now = now)
        val motive = CharacterMotiveState.active(CharacterInnerLifeStore.snapshot(actor)
            .optJSONArray("motives")).single { it.optString("aim") == "想分享院子的红叶" }.optString("id")
        val turn = "linked-wake-${UUID.randomUUID()}"
        val text = "院子里有片漂亮的红叶-${UUID.randomUUID()}"
        val decision = JSONObject().put("steps", JSONArray().put(JSONObject()
            .put("tool", "publish_moment").put("motiveId", motive)
            .put("reason", "想把自己看见的红叶分享出去")
            .put("args", JSONObject().put("text", text).put("planId", "unrelated-plan"))))
        assertEquals(1, ResidentDailyDecision.executeWakeSteps(app, actor, decision, turn))
        val receipt = SharedExperienceTimeline.eventsByIds(actor,
            listOf("tool-$actor-$turn-extra-0")).single()
        assertEquals(motive, JSONObject(receipt.content).optString("motiveId"))
        assertEquals("", JSONObject(receipt.content).optString("planId"))
        assertEquals("想把自己看见的红叶分享出去", JSONObject(receipt.content).optString("reason"))
        val updated = CharacterMotiveState.active(CharacterInnerLifeStore.snapshot(actor)
            .optJSONArray("motives")).single { it.optString("id") == motive }
        val published = MomentsStore.posts.value.single { it.authorCharacterId == actor && it.content == text }
        val nativeEvidence = "moment-${published.id}-$actor"
        assertTrue(updated.optJSONArray("outcomes")?.let { array ->
            (0 until array.length()).any { array.getJSONObject(it).optString("id") == nativeEvidence }
        } == true)
        assertEquals("unrelated-plan", decision.getJSONArray("steps").getJSONObject(0)
            .getJSONObject("args").getString("planId"))
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
