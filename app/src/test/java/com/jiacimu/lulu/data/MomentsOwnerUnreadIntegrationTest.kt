package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
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
class MomentsOwnerUnreadIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context

    @Before fun setup() {
        MomentsStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(MomentsStore, null)
        LuluRepositories.initialize(context)
        MigratedDomainStores.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MomentsStore.initialize(context)
    }

    @Test fun losingFeedPermissionImmediatelyRemovesInaccessibleUnreadPost() {
        val character = MigratedDomainStores.characters
            .create("好友红点-" + UUID.randomUUID()).characterId
        val post = MomentsStore.publishCharacter(character, "今天拍了一张照片")!!
        try {
            assertTrue(MomentsStore.unreadCharacterPosts.value > 0)
            assertTrue(MomentFriendAccess.setFriends(MomentFriendAccess.OWNER, character, false))
            assertEquals(0, MomentsStore.unreadCharacterPosts.value)
            // Re-adding a friend grants visibility, but must not invent a new post.
            assertTrue(MomentFriendAccess.setFriends(MomentFriendAccess.OWNER, character, true))
            assertEquals(0, MomentsStore.unreadCharacterPosts.value)
        } finally {
            MomentsStore.delete(post.id)
        }
    }

    @Test fun ownerGetsCharacterPostThenSeparateCommentUnreadAcrossRestart() {
        val character = MigratedDomainStores.characters
            .create("动态通知测试-" + UUID.randomUUID()).characterId
        val characterPost = MomentsStore.publishCharacter(character, "刚刚发了一条真实动态")!!
        try {
            assertTrue(MomentsStore.unreadCharacterPosts.value >= 1)
            MomentsStore.markVisiblePostsSeen(listOf(characterPost))
            assertEquals(0, MomentsStore.unreadCharacterPosts.value)

            val userPost = MomentsStore.publishUser("主人刚发的新动态")!!
            try {
                // An unrelated user's own post never creates a new notification.
                assertEquals(0, MomentsStore.unreadCharacterPosts.value)
                val oldSnapshot = MomentsStore.posts.value.single { it.id == userPost.id }
                val comment = MomentsStore.commentAsCharacter(
                    userPost.id, character, "看到啦，我也想来！",
                )!!
                assertEquals(comment.id, MomentsStore.posts.value
                    .single { it.id == userPost.id }.comments.last().id)
                assertEquals(1, MomentsStore.unreadCharacterPosts.value)
                MomentsStore.markVisiblePostsSeen(listOf(oldSnapshot))
                assertEquals("A stale page must not mark a new comment as read",
                    1, MomentsStore.unreadCharacterPosts.value)

                MomentsStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
                    .set(MomentsStore, null)
                MomentsStore.initialize(context)
                assertEquals("Unread activity is durable across initialization",
                    1, MomentsStore.unreadCharacterPosts.value)
                MomentsStore.markVisiblePostsSeen(listOf(
                    MomentsStore.posts.value.single { it.id == userPost.id }))
                assertEquals(0, MomentsStore.unreadCharacterPosts.value)
            } finally {
                MomentsStore.delete(userPost.id)
            }
        } finally {
            MomentsStore.delete(characterPost.id)
        }
    }
}
