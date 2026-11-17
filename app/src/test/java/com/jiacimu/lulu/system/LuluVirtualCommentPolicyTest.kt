package com.jiacimu.lulu.system

import org.junit.Assert.*
import org.junit.Test

class LuluVirtualCommentPolicyTest {
    private val valid = LuluVirtualCommentPolicy.Evidence(true, true, true, true, true, true, true)

    @Test fun opensCommentsAndRepliesButNeverPublishesThroughNavigation() {
        listOf("评论", "评论 1.2万", "查看全部123条评论", "查看回复", "回复", "Comments 28").forEach {
            assertTrue(it, LuluVirtualCommentPolicy.isReadOnlyEntry(it, false))
            assertFalse(it, LuluVirtualCommentPolicy.isReadOnlyEntry(it, true))
        }
        listOf("发表评论", "发布", "发送", "点赞", "评论并购买", "发送验证码").forEach {
            assertFalse(it, LuluVirtualCommentPolicy.isReadOnlyEntry(it, false))
        }
    }

    @Test fun enteredProseCannotTurnSearchOrPrivateMessageFieldIntoCommentEditor() {
        assertTrue(LuluVirtualCommentPolicy.isCommentEditor("留下你的评论", "input"))
        assertTrue(LuluVirtualCommentPolicy.isCommentEditor(null, "id/comment_input"))
        assertFalse(LuluVirtualCommentPolicy.isCommentEditor("搜索评论", "comment_input"))
        assertFalse(LuluVirtualCommentPolicy.isCommentEditor("私信", "comment_input"))
        assertFalse(LuluVirtualCommentPolicy.isCommentEditor("", "input"))
    }

    @Test fun staleTargetDraftOrIncompleteTreeRejectsBeforeDispatch() {
        assertNull(LuluVirtualCommentPolicy.rejection(valid))
        listOf(valid.copy(snapshotCurrent = false), valid.copy(ownedWindow = false),
            valid.copy(scanComplete = false), valid.copy(editorVerified = false),
            valid.copy(draftMatches = false), valid.copy(buttonVerified = false),
            valid.copy(contextMatches = false)).forEach { evidence ->
            var clicks = 0
            var marks = 0
            val result = LuluVirtualCommentPolicy.dispatchOnce(
                { LuluVirtualCommentPolicy.rejection(evidence) }, { marks++ }, { clicks++; true })
            assertFalse(result.attempted)
            assertEquals(0, clicks)
            assertEquals(0, marks)
        }
    }

    @Test fun rejectedOrThrowingClickIsStillAttemptedOnceAndMarkedFirst() {
        listOf(false, true).forEach { throwing ->
            var clicks = 0
            var marked = false
            val result = LuluVirtualCommentPolicy.dispatchOnce({ null }, { marked = true }) {
                assertTrue(marked)
                clicks++
                if (throwing) error("dispatch may already have reached Android")
                false
            }
            assertTrue(result.attempted)
            assertFalse(result.clicked)
            assertEquals(1, clicks)
        }
    }

    @Test fun textAlreadyVisibleOrUnclearedDraftCannotProvePublication() {
        assertFalse(LuluVirtualCommentPolicy.appeared(1, 1, true, true))
        assertFalse(LuluVirtualCommentPolicy.appeared(1, 2, false, true))
        assertFalse(LuluVirtualCommentPolicy.appeared(1, 2, true, false))
        assertTrue(LuluVirtualCommentPolicy.appeared(1, 2, true, true))
    }

    @Test fun submitMustBeBesideDraftInsteadOfReplyLinkHigherInThread() {
        val editor = LuluVirtualCommentPolicy.Box(60, 1000, 590, 1130)
        val send = LuluVirtualCommentPolicy.Box(600, 1030, 700, 1100)
        assertTrue(LuluVirtualCommentPolicy.besideEditor(editor, send, 720, 1280))
        assertFalse(LuluVirtualCommentPolicy.besideEditor(editor, send.copy(top = 450, bottom = 500), 720, 1280))
        assertFalse(LuluVirtualCommentPolicy.besideEditor(editor, send.copy(left = 0, right = 720), 720, 1280))
        assertFalse(LuluVirtualCommentPolicy.besideEditor(editor, send.copy(right = 900), 720, 1280))
    }
}
