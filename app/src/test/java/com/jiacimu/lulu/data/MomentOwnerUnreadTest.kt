package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class MomentOwnerUnreadTest {
    private fun characterPost(id: String, comments: List<MomentComment> = emptyList()) =
        MomentPost(id = id, authorType = MomentAuthorType.Character,
            authorCharacterId = "role-a", content = "今天出去玩了", comments = comments)

    private fun userPost(id: String, comments: List<MomentComment> = emptyList()) =
        MomentPost(id = id, authorType = MomentAuthorType.User,
            content = "用户的朋友圈", comments = comments)

    @Test fun characterPostGetsBadgeAndReadingOnlyThatSnapshotClearsIt() {
        val post = characterPost("p1")
        val unread = MomentOwnerUnread.afterMutation(emptyList(), listOf(post), emptySet()) { true }
        assertEquals(setOf("p1"), unread)
        assertTrue(MomentOwnerUnread.markViewed(unread, listOf(post)).isEmpty())
    }

    @Test fun replyingToUserCommentOnCharacterPostRestoresUnreadAfterAlreadySeen() {
        val old = characterPost("p2", listOf(
            MomentComment(id = "user-reply", characterId = "__user__", content = "真不错")))
        val later = old.copy(comments = old.comments + MomentComment(
            id = "answer", characterId = "role-a", content = "哈哈一起呀",
            replyToCommentId = "user-reply", replyToCharacterId = "__user__"))
        val unread = MomentOwnerUnread.afterMutation(listOf(old), listOf(later), emptySet()) { true }
        assertEquals(setOf("comment:answer"), unread)
        // An older feed snapshot never acknowledges a reply it did not display.
        assertEquals(unread, MomentOwnerUnread.markViewed(unread, listOf(old)))
        assertTrue(MomentOwnerUnread.markViewed(unread, listOf(later)).isEmpty())
    }

    @Test fun characterCommentsOnOwnersPostAreUnreadButOwnersActionsAreNot() {
        val original = userPost("mine")
        val userComment = MomentComment(id = "self", characterId = "__user__", content = "补充")
        val otherComment = MomentComment(id = "new", characterId = "role-a", content = "我看见了")
        val modified = original.copy(comments = listOf(userComment, otherComment))
        val unread = MomentOwnerUnread.afterMutation(listOf(original), listOf(modified), emptySet()) { true }
        assertEquals(setOf("comment:new"), unread)
    }

    @Test fun unrelatedThirdPartyCommentOnOtherPersonsPostIsNotAnOwnerNotification() {
        val original = characterPost("friend")
        val later = original.copy(comments = listOf(
            MomentComment(id = "unrelated", characterId = "role-b", content = "哈哈")))
        assertTrue(MomentOwnerUnread.afterMutation(listOf(original), listOf(later),
            emptySet()) { true }.isEmpty())
    }

    @Test fun oldUnreadPostsSurviveMigrationAndRemovedOrHiddenEntriesArePruned() {
        val post = characterPost("kept")
        assertEquals(setOf("kept"), MomentOwnerUnread.reconcile(
            setOf("kept", "deleted", "comment:deleted"), listOf(post)) { true })
        assertTrue(MomentOwnerUnread.reconcile(
            setOf("kept"), listOf(post)) { false }.isEmpty())
    }

    @Test fun oneReadDoesNotClearOtherPostOrLateReply() {
        val first = characterPost("a")
        val second = userPost("b", listOf(
            MomentComment(id = "reply-b", characterId = "role-b", content = "回复你")))
        assertEquals(setOf("comment:reply-b"),
            MomentOwnerUnread.markViewed(setOf("a", "comment:reply-b"), listOf(first)))
        assertTrue(MomentOwnerUnread.afterMutation(listOf(first), listOf(first),
            emptySet()) { true }.isEmpty())
        assertEquals(setOf("a"), MomentOwnerUnread.afterMutation(
            emptyList(), listOf(first, second), emptySet()) { it.id == "a" })
    }
}
