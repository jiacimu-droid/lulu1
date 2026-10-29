package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MomentConversationContinuationTest {
    private val app get() = RuntimeEnvironment.getApplication() as Context
    private val ownedPosts = mutableListOf<String>()

    @Before fun initialize() {
        // Robolectric creates a new Application for each test while the Kotlin
        // singleton may retain a previous test's SharedPreferences instance.
        // Bind writes AND the reload assertion to this test's actual backing file.
        MomentsStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(MomentsStore, null)
        if (runCatching { androidx.work.WorkManager.getInstance(app) }.isFailure)
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        LuluRepositories.initialize(app)
        MigratedDomainStores.initialize(app)
        SharedExperienceTimeline.initialize(app)
        MomentsStore.initialize(app)
        CharacterPhoneInbox.initialize(app)
    }

    @After fun cleanup() { ownedPosts.forEach(MomentsStore::delete) }

    private fun role(): String = MigratedDomainStores.characters
        .create("评论来往-${UUID.randomUUID()}").characterId

    private fun post(author: String): MomentPost =
        MomentsStore.publishCharacter(author, "真实读完的一段故事-${UUID.randomUUID()}")!!
            .also { ownedPosts += it.id }

    private fun live(id: String) = MomentsStore.posts.value.single { it.id == id }

    private suspend fun speak(actor: String, postId: String, text: String,
        target: String? = null, request: String = UUID.randomUUID().toString()): JSONObject =
        JSONObject(ToolRouter.execute(app, actor, "comment_moment",
            JSONObject().put("postId", postId).put("text", text).apply {
                target?.let { put("replyToCommentId", it) }
            }, requestId = request))

    @Test fun realCommentReplyAndFollowUpRetainTargetsReceiptsUnreadAndReplayIdentity() = runBlocking {
        val author = role()
        val first = role()
        val second = role()
        val outsider = role()
        assertTrue(MomentFriendAccess.setFriends(author, first, true))
        assertTrue(MomentFriendAccess.setFriends(author, second, true))
        val original = post(author)
        assertTrue(speak(first, original.id, "结尾让我有点意外").optBoolean("success"))
        val initial = live(original.id).comments.single()
        val presented = live(original.id)
        val facts = CharacterPhoneInbox.modelContext(second, listOf(presented))
        assertTrue(facts.contains("commentId=${initial.id}"))
        assertTrue(facts.contains("replyToCommentId"))
        // No third-party friendship is required to reply on this author's post.
        assertFalse(MomentFriendAccess.areFriends(first, second))
        val request = "moment-reply-${UUID.randomUUID()}"
        val reply = speak(second, original.id, "你觉得他会再回来吗？", initial.id, request)
        assertTrue(reply.toString(), reply.optBoolean("success"))
        val written = live(original.id).comments.last()
        assertEquals(initial.id, written.replyToCommentId)
        assertEquals(first, written.replyToCharacterId)
        assertEquals("moment-public-comment-${written.id}-$second", reply.optString("evidenceId"))
        assertEquals(reply.toString(), speak(second, original.id,
            "你觉得他会再回来吗？", initial.id, request).toString())
        assertEquals(2, live(original.id).comments.size)
        // Reading the frozen earlier snapshot does not consume the later reply.
        assertTrue(CharacterPhoneInbox.acknowledgeObserved(first, listOf(presented)))
        assertTrue(CharacterPhoneInbox.unreadMoments(first, 20).any { it.id == original.id })
        val next = live(original.id)
        assertTrue(CharacterPhoneInbox.modelContext(first, listOf(next))
            .contains("回复 commentId=${initial.id}"))
        assertTrue(CharacterPhoneInbox.acknowledgeObserved(first, listOf(next)))
        assertTrue(speak(first, original.id, "我觉得会，他还留着那把钥匙。", written.id)
            .optBoolean("success"))
        val followUp = live(original.id).comments.last()
        assertEquals(written.id, followUp.replyToCommentId)
        assertEquals(second, followUp.replyToCharacterId)
        assertEquals(3, live(original.id).comments.size)
        val source = "moment-public-comment-${written.id}-$second"
        assertTrue(SharedExperienceTimeline.eventsByIds(second, listOf(source)).isNotEmpty())
        assertTrue(SharedExperienceTimeline.eventsByIds(first, listOf(source)).isEmpty())
        assertFalse(CharacterPhoneInbox.modelContext(outsider, listOf(live(original.id)))
            .contains(written.content))
        // Reload the existing authority, preserving reply links rather than rebuilding a thread.
        assertTrue(app.getSharedPreferences("lulu_moments", Context.MODE_PRIVATE)
            .getString("posts_v1", "").orEmpty().contains(followUp.id))
        MomentsStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(MomentsStore, null)
        MomentsStore.initialize(app)
        assertEquals(followUp, live(original.id).comments.last())
    }

    @Test fun invalidOrHiddenReplyCannotBecomeAnUnintendedTopLevelComment() = runBlocking {
        val author = role()
        val actor = role()
        assertTrue(MomentFriendAccess.setFriends(author, actor, true))
        val original = post(author)
        val another = post(author)
        val elsewhere = MomentsStore.commentAsCharacter(another.id, author, "另一条帖子的留言")!!
        assertFalse(speak(actor, original.id, "不能跨帖引用", elsewhere.id).optBoolean("success"))
        assertFalse(speak(actor, original.id, "不能虚构引用", "missing-comment").optBoolean("success"))
        assertTrue(live(original.id).comments.isEmpty())
        val target = MomentsStore.commentAsCharacter(original.id, author, "本帖留言")!!
        assertTrue(MomentFriendAccess.setFriends(author, actor, false))
        assertFalse(speak(actor, original.id, "撤权后不能接话", target.id).optBoolean("success"))
        assertEquals(listOf(target), live(original.id).comments)
    }

    @Test fun aNewExplicitTurnOnTheSamePostIsNotAOncePerRoleQuota() = runBlocking {
        val actor = role()
        val original = post(actor)
        assertTrue(speak(actor, original.id, "刚读完时的看法").optBoolean("success"))
        assertTrue(speak(actor, original.id, "又想了一下，我还有另一点想说。").optBoolean("success"))
        assertEquals(2, live(original.id).comments.size)
        assertTrue(live(original.id).comments.all { it.replyToCommentId == null })
    }
}
