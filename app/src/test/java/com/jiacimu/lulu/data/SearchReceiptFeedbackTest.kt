package com.jiacimu.lulu.data

import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Search must be completed by ToolRouter before a role can report results. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SearchReceiptFeedbackTest {
    @Test fun cancellingAnAcceptedSearchDoesNotSeparateItsToolReceiptFromFinalReply() = runBlocking {
        val toolStarted = CompletableDeferred<Unit>()
        val allowToolToFinish = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()
        val job = launch {
            SearchResultContinuation.finishAcceptedTurn(protect = true) {
                effects += "search receipt"
                toolStarted.complete(Unit)
                allowToolToFinish.await()
                // Exactly this missing continuation formerly ran only at the
                // next ordinary perception cycle after cancellation.
                effects += "role final reply"
            }
        }
        toolStarted.await()
        job.cancel() // A newer user bubble supersedes the old online batch.
        allowToolToFinish.complete(Unit)
        job.join()
        assertEquals(listOf("search receipt", "role final reply"), effects)
    }

    @Test fun onlyAcceptedUserSearchesOwnTheProtectedContinuation() {
        assertTrue(SearchResultContinuation.requiresCompletion(true, "web_search", null))
        assertTrue(SearchResultContinuation.requiresCompletion(true, "read_xiaohongshu", null))
        assertTrue(SearchResultContinuation.requiresCompletion(true, "",
            JSONArray().put(JSONObject().put("tool", "image_search"))))
        assertTrue(SearchResultContinuation.requiresCompletion(true, "send_private_message",
            JSONArray().put(JSONObject().put("tool", "control_volume"))
                .put(JSONObject().put("tool", "web_search"))))
        assertFalse(SearchResultContinuation.requiresCompletion(false, "web_search", null))
        assertFalse(SearchResultContinuation.requiresCompletion(true, "control_volume", null))
        assertFalse(SearchResultContinuation.requiresCompletion(true, "send_private_message",
            JSONArray().put(JSONObject().put("tool", "go_offline"))))
    }

    @Test fun webSearchAndReadPreserveRealExecutorEvidence() {
        val args = JSONObject().put("op", "search_read").put("query", "第五人格活动")
        val actual = JSONObject().put("success", true)
            .put("summary", "官网：新活动详情 https://example.org/event")
            .put("evidenceId", "tool-real-execution")
        val receipt = SearchReceiptFeedback.from("web_search", args, actual)
        assertNotNull(receipt)
        assertTrue(receipt!!.success)
        assertEquals("tool-real-execution", receipt.evidenceId)
        assertTrue(receipt.details.contains("https://example.org/event"))
        assertEquals("search_read", receipt.operation)
    }

    @Test fun failuresAndMissingProviderAreNotRewrittenIntoSuccess() {
        val failure = JSONObject().put("success", false).put("error", "搜索渠道尚未配置")
        val actual = SearchReceiptFeedback.from("web_search", JSONObject(), failure)!!
        assertFalse(actual.success)
        assertEquals("搜索渠道尚未配置", actual.details)
        assertNull(SearchReceiptFeedback.from("control_volume", JSONObject(), failure))
        assertNull(SearchReceiptFeedback.from("image_search",
            JSONObject().put("op", "search_send"), failure))
    }

    @Test fun xiaohongshuReadReturnsVerifiedOutcomeToTheSameRoleForAReply() {
        val args = JSONObject().put("keyword", "川大法硕")
        val result = JSONObject().put("success", true).put("status", "succeeded")
            .put("summary", "实际读到小红书《备考记录》；链接=https://www.xiaohongshu.com/explore/123")
            .put("evidenceId", "xhs-read-role-1")
        val feedback = SearchReceiptFeedback.from("read_xiaohongshu", args, result)
        assertNotNull(feedback)
        assertEquals("read_xiaohongshu", feedback!!.tool)
        assertEquals("search_read", feedback.operation)
        assertTrue(feedback.success)
        assertEquals("xhs-read-role-1", feedback.evidenceId)
        assertTrue(feedback.details.contains("备考记录"))
    }

    @Test fun xiaohongshuFailuresReturnTruthfulFeedbackButPendingIsNotFinishedReading() {
        val specified = JSONObject().put("url", "https://www.xiaohongshu.com/explore/123")
        val unavailable = JSONObject().put("success", false).put("status", "failed")
            .put("error", "本机阅读会话不可用")
        val failure = SearchReceiptFeedback.from("read_xiaohongshu", specified, unavailable)
        assertNotNull(failure)
        assertFalse(failure!!.success)
        assertEquals("shared_link", failure.operation)
        assertEquals("本机阅读会话不可用", failure.details)
        val pending = JSONObject().put("success", true).put("status", "pending")
            .put("summary", "只提交了阅读请求")
        assertNull(SearchReceiptFeedback.from("read_xiaohongshu", specified, pending))
        assertNull(SearchReceiptFeedback.from("read_xiaohongshu", specified,
            JSONObject().put("success", true).put("status", "running")))
    }

    @Test fun imageCandidateDiscoveryNeedsFollowupButSendingImageAlreadyDeliveredIt() {
        val args = JSONObject().put("op", "search")
        val result = JSONObject().put("success", true)
            .put("summary", "找到候选 searchId=real-id ; index=0")
        assertEquals("image_search", SearchReceiptFeedback.from("image_search", args, result)!!.tool)
        assertNull(SearchReceiptFeedback.from("image_search",
            JSONObject().put("op", "send"), result))
        assertNull(SearchReceiptFeedback.from("image_search",
            JSONObject().put("op", "search_send"), result))
    }
}
